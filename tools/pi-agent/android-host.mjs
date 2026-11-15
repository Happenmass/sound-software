import { createInterface } from "node:readline";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createAgentManagement } from "./management.mjs";
import { createRecordingSessions } from "./recording-sessions.mjs";

const sessionCommand = createRecordingSessions();

async function serve() {
    const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
    const input = lines[Symbol.asyncIterator]();
    const abort = new AbortController();
    const send = (value) => {
        if (!process.stdout.destroyed) process.stdout.write(JSON.stringify(value) + "\n");
    };
    process.stdin.on("error", () => lines.close());
    lines.once("close", () => abort.abort());
    let management;
    let client;
    let recordings;
    try {
        const first = await input.next();
        if (first.done) return;
        const request = JSON.parse(first.value);
        if (request.recordingMcpCommand) {
            recordings = new Client({ name: "soundsoftware-pi", version: "1.0.0" });
            await recordings.connect(new StdioClientTransport({
                command: request.recordingMcpCommand, args: ["recording-mcp.cjs"], stderr: "inherit",
            }));
            const { tools } = await recordings.listTools();
            request.schemas = tools.map(({ name, description, inputSchema }) => ({
                type: "function", function: { name, description, parameters: inputSchema },
            }));
        }
        const run = (command, signal) => sessionCommand(command, async (kind, payload) => {
            abort.signal.throwIfAborted();
            signal.throwIfAborted();
            if (recordings && kind === "tool") {
                const result = await recordings.callTool({ name: payload.name, arguments: payload.args }, undefined, { signal });
                return result.structuredContent ?? JSON.parse(result.content[0].text);
            }
            if (recordings && kind === "model" && payload.tools) payload = { ...payload, schemas: request.schemas };
            send({ kind, ...payload });
            const next = await input.next();
            if (next.done) throw new Error("Android bridge closed");
            const reply = JSON.parse(next.value);
            if (reply.error) throw new Error(reply.error);
            return reply.value;
        }, (event, value) => send({ kind: "event", event, value }), signal);
        management = createAgentManagement(
            (question, signal, sessionId) => run({ ...request, action: "ask", question, sessionId }, signal),
            (action, scope) => run({ ...request, action, scope }, abort.signal),
        );
        lines.once("close", management.cancelAll);
        client = new Client({ name: "soundsoftware-android", version: "0.1.0" });
        const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
        await management.server.connect(serverTransport);
        await client.connect(clientTransport);
        async function call(name, args) {
            const result = await client.callTool({ name, arguments: args });
            if (result.isError) throw new Error(result.content[0]?.text ?? "Pi command failed");
            return result.structuredContent;
        }
        let snapshot;
        if (request.action === "ask") {
            const { taskId } = await call("agent_start", { agentId: "recording_assistant",
                question: request.question, sessionId: request.sessionId });
            await management.wait(taskId);
            const result = await call("agent_result", { taskId });
            if (result.status !== "succeeded") throw new Error(result.error ?? "Pi task did not complete");
            snapshot = result.answer;
        } else {
            if (request.action !== "open" && request.action !== "clear") throw new Error("Unknown session command");
            snapshot = await call("agent_session_" + request.action, { scope: request.scope });
        }
        send({ kind: "result", snapshot });
    } catch (error) {
        send({ kind: "result", error: error.message });
    } finally {
        management?.cancelAll();
        await client?.close();
        await management?.server.close();
        await recordings?.close();
        lines.close();
        process.stdin.pause();
    }
}

void serve();
