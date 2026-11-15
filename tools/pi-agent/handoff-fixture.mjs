// Kotlin/Node contract test: execute the production Pi loop with synthetic model I/O.
import { createRecordingSessions } from "./recording-sessions.mjs";
let input = "";
for await (const chunk of process.stdin) input += chunk;
const { session, question, systemPrompt, compactionPrompt } = JSON.parse(input);
let stored = session;
const seen = [];
await createRecordingSessions()({ action: "ask", scope: { recordingId: null }, sessionId: session.id,
    question, schemas: [], systemPrompt, compactionPrompt }, async (kind, payload) => {
    if (kind === "storage_read") return { body: stored, invalidated: false };
    if (kind === "storage_write") { stored = payload.body; return; }
    if (kind === "model") {
        seen.push(payload);
        return { text: payload.tools ? '{"found":false,"answer":"fixture answer","citations":[]}' : '{"summary":"summary from Pi"}', calls: [], usage: null };
    }
    if (kind === "verify") return { answer: "fixture answer", citations: [] };
    throw new Error(kind);
}, () => {}, new AbortController().signal);
process.stdout.write(JSON.stringify({ session: stored, seen }));
