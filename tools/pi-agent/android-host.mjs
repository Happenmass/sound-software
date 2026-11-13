import { chmodSync } from "node:fs";
import { createServer } from "node:net";
import { createInterface } from "node:readline";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createAgentManagement } from "./management.mjs";
import { createRecordingSessions } from "./recording-sessions.mjs";

const socketPath = process.argv[2];
const sessionCommand = createRecordingSessions();

async function serve(socket) {
    const lines = createInterface({ input: socket, crlfDelay: Infinity });
    const input = lines[Symbol.asyncIterator]();
    const abort = new AbortController();
    const send = (value) => {
        if (!socket.destroyed) socket.write(JSON.stringify(value) + "\n");
    };
    socket.on("error", () => lines.close());
    socket.once("close", () => abort.abort());
    let management;
    let client;
    try {
        const first = await input.next();
        if (first.done) return;
        const request = JSON.parse(first.value);
        const run = (command, signal) => sessionCommand(command, async (kind, payload) => {
            abort.signal.throwIfAborted();
            signal.throwIfAborted();
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
        socket.once("close", management.cancelAll);
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
        lines.close();
        socket.end();
    }
}

createServer((socket) => void serve(socket)).listen(socketPath, () => chmodSync(socketPath, 0o600));
