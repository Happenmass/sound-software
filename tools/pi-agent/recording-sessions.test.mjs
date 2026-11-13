import assert from "node:assert/strict";
import { test } from "node:test";
import { createRecordingSessions } from "./recording-sessions.mjs";

test("migrates independently scoped history, compacts it, and resets only the selected session", async () => {
    const global = { recordingId: null };
    const recording = { recordingId: "recording-a" };
    const legacyGlobal = { id: "global-old", turns: [{ question: "global question", answer: "old answer" }],
        memory: "global-old-summary", contextInitialized: true,
        contextMessages: Array.from({ length: 8 }, (_, i) => ({
            role: i % 2 === 0 ? "user" : "assistant", content: "old-global-" + i,
        })), lastUsage: { totalTokens: 200_000 } };
    const legacyLocal = { id: "recording-old", turns: [{ question: "local question", answer: "local answer" }] };
    const stored = new Map([[null, legacyGlobal], [recording.recordingId, legacyLocal]]);
    const calls = [];
    const schema = { function: { name: "search_recordings", description: "Search",
        parameters: { type: "object", properties: {}, additionalProperties: false } } };
    const signal = new AbortController().signal;
    let host = createRecordingSessions();
    const run = (scope, action, input = {}) => host({ action, scope, schemas: [schema], ...input }, async (kind, payload) => {
        const key = scope.recordingId;
        if (kind === "storage_read") return { body: structuredClone(stored.get(key)) };
        if (kind === "storage_write") {
            stored.set(key, structuredClone(payload.body));
            return;
        }
        if (kind === "model") {
            calls.push(payload);
            return { text: payload.tools ? '{"found":false,"answer":"new answer","citations":[]}' : '{"summary":"compacted global"}',
                calls: [], usage: null };
        }
        assert.equal(kind, "verify");
        return { answer: "new answer", citations: [] };
    }, () => {}, signal);

    const opened = await run(global, "open");
    assert.deepEqual(opened.turns, legacyGlobal.turns);
    assert.equal(opened.id, legacyGlobal.id);
    assert.equal(stored.get(null).hostVersion, 1);
    assert.equal((await run(recording, "open")).id, legacyLocal.id);
    assert.match(stored.get(recording.recordingId).contextMessages[0].content, /local question/);
    host = createRecordingSessions(); // Reopen through storage after a host restart.
    const answered = await run(global, "ask", { sessionId: opened.id, question: "follow up" });
    assert.equal(answered.turns.length, 2);
    assert.equal(answered.compactions, 1);
    assert.equal(calls[0].tools, false);
    assert.match(JSON.stringify(calls[0]), /global-old-summary/);
    assert.match(JSON.stringify(calls[1]), /compacted global/);
    assert.match(JSON.stringify(calls[1]), /old-global-7/);
    assert.doesNotMatch(JSON.stringify(calls), /local question/);
    const cleared = await run(global, "clear");
    assert.notEqual(cleared.id, opened.id);
    assert.deepEqual(cleared.turns, []);
    assert.equal((await run(recording, "open")).id, legacyLocal.id);
    await assert.rejects(run(global, "ask", { sessionId: opened.id, question: "stale" }), /changed/);
    await run(global, "ask", { sessionId: cleared.id, question: "fresh question" });
    assert.deepEqual(calls.at(-1).messages.map(({ role }) => role), ["system", "user"]);
    assert.equal(calls.at(-1).messages[1].content, "fresh question");
    assert.doesNotMatch(JSON.stringify(stored.get(null)), /old-global|compacted global|global question|local question/);
});
