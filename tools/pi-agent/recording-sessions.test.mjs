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
    let invalidated = false;
    const calls = [];
    const schema = { function: { name: "search_recordings", description: "Search",
        parameters: { type: "object", properties: {}, additionalProperties: false } } };
    const signal = new AbortController().signal;
    let host = createRecordingSessions();
    const run = (scope, action, input = {}) => host({ action, scope, schemas: [schema], systemPrompt: "Recording assistant", compactionPrompt: "Summarize", ...input }, async (kind, payload) => {
        const key = scope.recordingId;
        if (kind === "storage_read") return { body: structuredClone(stored.get(key)), invalidated: key == null && invalidated };
        if (kind === "storage_write") {
            stored.set(key, structuredClone(payload.body));
            if (key == null) invalidated = false;
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
    invalidated = true;
    assert.deepEqual((await run(global, "open")).turns, answered.turns);
    assert.deepEqual(stored.get(null).contextMessages, []);
    assert.equal(stored.get(null).memory, "");
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

function fixture({ model, tool = () => ({}), session, verify = () => ({ answer: "checked", citations: [] }) }) {
    let stored = session ?? { hostVersion: 1, id: "session", turns: [], contextInitialized: true, contextMessages: [] };
    const events = [];
    const controller = new AbortController();
    const host = createRecordingSessions();
    return {
        get stored() { return stored; }, events, controller,
        ask: () => host({ action: "ask", scope: { recordingId: null }, sessionId: "session", question: "next",
            systemPrompt: "assistant", compactionPrompt: "summary", schemas: [{ function: { name: "read_segments", description: "read",
                parameters: { type: "object", properties: { offset: { type: "integer" } }, required: ["offset"] } } }] },
        async (kind, payload) => {
            if (kind === "storage_read") return { body: structuredClone(stored), invalidated: false };
            if (kind === "storage_write") { stored = structuredClone(payload.body); return; }
            if (kind === "model") return model(payload, controller.signal);
            if (kind === "tool") return tool(payload);
            if (kind === "verify") return verify(payload);
            throw Error(kind);
        }, (kind, value) => events.push({ kind, value }), controller.signal),
    };
}

test("production Pi loop completes 60 tool calls without legacy limits and saves verified output", async () => {
    let page = 0;
    const pages = [];
    const f = fixture({ model: ({ messages }) => {
        if (page === 60) {
            assert.equal(messages.filter(m => m.role === "tool").length, 60);
            return { text: "raw answer", calls: [], usage: { promptTokens: 123, completionTokens: 45, totalTokens: 168 } };
        }
        const count = page === 0 ? 9 : 1;
        return { text: "", calls: Array.from({ length: count }, () => {
            const offset = page++;
            return { id: `page-${offset}`, name: "read_segments", arguments: { offset } };
        }) };
    }, tool: ({ args }) => { pages.push(args.offset); return { nextOffset: args.offset + 1 }; } });
    await f.ask();
    assert.deepEqual(pages, Array.from({ length: 60 }, (_, i) => i));
    assert.equal(f.stored.lastUsage.totalTokens, 168);
    assert.match(f.stored.contextMessages.at(-1).content, /checked/);
    assert.doesNotMatch(f.stored.contextMessages.at(-1).content, /raw answer/);
});

test("duplicate calls fail without committing and a subsequent request can retry", async () => {
    let duplicate = true;
    const f = fixture({ model: () => duplicate ? { text: "", calls: [0, 1].map(() => ({ id: "same", name: "read_segments", arguments: { offset: 0 } })) }
        : { text: "answer", calls: [], usage: null } });
    await assert.rejects(f.ask(), /duplicate/);
    assert.equal(f.stored.turns.length, 0);
    assert.ok(f.events.some(e => e.kind === "draft"));
    duplicate = false;
    await f.ask();
    assert.equal(f.stored.turns.length, 1);
});

test("cancelling during compaction never commits a partial turn or summary", async () => {
    const original = { hostVersion: 1, id: "session", turns: [], memory: "old summary", contextInitialized: true,
        contextMessages: Array.from({ length: 10 }, (_, i) => ({ role: "user", content: `old-${i}` })), lastUsage: { totalTokens: 200000 } };
    let entered;
    const ready = new Promise(resolve => entered = resolve);
    const f = fixture({ session: original, model: async ({ tools }, signal) => {
        assert.equal(tools, false); entered();
        await new Promise((_, reject) => signal.addEventListener("abort", () => reject(signal.reason), { once: true }));
    } });
    const pending = f.ask();
    await ready; f.controller.abort(new Error("cancel test"));
    await assert.rejects(pending, /cancel test/);
    assert.deepEqual(f.stored, original);
    assert.ok(f.events.some(e => e.kind === "trace" && e.value.status === "cancelled"));
});

test("compaction retains both parents when the five-message tail starts at the second parent", async () => {
    const context = Array.from({ length: 10 }, (_, i) => ({ role: "user", content: `old-${i}` }));
    context.push({ role: "assistant", calls: [{ id: "a", name: "read_segments", arguments: "{}" }] },
        { role: "assistant", calls: [{ id: "b", name: "read_segments", arguments: "{}" }] },
        { role: "tool", toolCallId: "a", content: "page a" }, { role: "tool", toolCallId: "b", content: "page b" },
        { role: "assistant", content: "last" });
    const f = fixture({ session: { hostVersion: 1, id: "session", turns: [], contextInitialized: true,
        contextMessages: context, lastUsage: { totalTokens: 200000 } }, model: ({ tools, messages }) => {
        if (!tools) return { text: '{"summary":"summary"}', calls: [] };
        const ids = messages.flatMap(m => m.calls ?? []).map(c => c.id);
        assert.deepEqual(ids, ["a", "b"]);
        return { text: "answer", calls: [], usage: null };
    } });
    await f.ask();
    assert.equal(f.stored.compactions, 1); assert.equal(f.stored.lastUsage, null);
});
