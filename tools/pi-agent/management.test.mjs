import { once } from "node:events";
import { createServer } from "node:http";
import assert from "node:assert/strict";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createAgentManagement } from "./management.mjs";
import { runRecordingLoop } from "./recording-loop.mjs";

test("the shared management tools start, read and cancel Pi tasks over Streamable HTTP", async () => {
    const schema = {
        function: {
            name: "search_recordings", description: "Search recording transcripts",
            parameters: { type: "object", properties: { query: { type: "string" } }, required: ["query"] },
        },
    };
    const management = createAgentManagement(async (question, signal) => {
        let receivedToolResult = false;
        return runRecordingLoop(question, [schema], async (kind, input) => {
            if (question === "Wait") {
                await new Promise((_, reject) => {
                    signal.throwIfAborted();
                    signal.addEventListener("abort", () => reject(signal.reason), { once: true });
                });
            }
            if (kind === "model") {
                return receivedToolResult
                    ? { text: "Thursday", calls: [] }
                    : { text: "", calls: [{ id: "lookup", name: "search_recordings", arguments: { query: "launch" } }] };
            }
            if (kind === "tool") {
                assert.equal(input.name, "search_recordings");
                assert.equal(input.args.query, "launch");
                return { evidence: ["Thursday"] };
            }
            assert.equal(kind, "toolResult");
            assert.deepEqual(JSON.parse(input.text), { evidence: ["Thursday"] });
            receivedToolResult = true;
        }, signal);
    });
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: () => "test-session", enableJsonResponse: true });
    await management.server.connect(transport);
    const http = createServer((request, response) => void transport.handleRequest(request, response));
    http.listen(0, "127.0.0.1");
    await once(http, "listening");
    const client = new Client({ name: "pi-management-test", version: "0.1.0" });
    try {
        await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${http.address().port}/mcp`)));
        assert.deepEqual((await client.listTools()).tools.map(({ name }) => name).sort(),
            ["agent_cancel", "agent_list", "agent_result", "agent_start", "agent_status"]);
        const call = async (name, args = {}) => {
            const result = await client.callTool({ name, arguments: args });
            assert.ok(!result.isError, JSON.stringify(result.content));
            return result.structuredContent;
        };
        const { agents } = await call("agent_list");
        const completed = await call("agent_start", { agentId: agents[0].id, question: "When?" });
        await management.wait(completed.taskId);
        assert.equal((await call("agent_status", { taskId: completed.taskId })).status, "succeeded");
        assert.equal((await call("agent_result", { taskId: completed.taskId })).answer, "Thursday");
        const pending = await call("agent_start", { agentId: agents[0].id, question: "Wait" });
        assert.equal((await call("agent_cancel", { taskId: pending.taskId })).status, "cancelled");
        await management.wait(pending.taskId);
        assert.equal((await call("agent_result", { taskId: pending.taskId })).answer, undefined);
    } finally {
        management.cancelAll();
        await client.close();
        await management.server.close();
        await new Promise((resolve, reject) => http.close((error) => error ? reject(error) : resolve()));
    }
});

test("session commands work through the shared MCP transport", async () => {
    const { InMemoryTransport } = await import("@modelcontextprotocol/sdk/inMemory.js");
    const { randomUUID } = await import("node:crypto");
    let id = randomUUID();
    const management = createAgentManagement(async (_question, _signal, sessionId) => ({ id: sessionId }),
        (action, scope) => {
            assert.equal(scope.recordingId, null);
            if (action === "clear") id = randomUUID();
            return { id, turns: [] };
        });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "session-test", version: "0.1.0" });
    await management.server.connect(serverTransport);
    await client.connect(clientTransport);
    try {
        const call = async (name, args) => {
            const result = await client.callTool({ name, arguments: args });
            assert.ok(!result.isError, JSON.stringify(result.content));
            return result.structuredContent;
        };
        const scope = { recordingId: null };
        const opened = await call("agent_session_open", { scope });
        const { taskId } = await call("agent_start", { agentId: "recording_assistant", question: "Ask", sessionId: opened.id });
        await management.wait(taskId);
        assert.equal((await call("agent_result", { taskId })).answer.id, opened.id);
        assert.notEqual((await call("agent_session_clear", { scope })).id, opened.id);
    } finally {
        await client.close();
        await management.server.close();
    }
});
