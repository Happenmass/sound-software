import { randomUUID } from "node:crypto";
import { runRecordingLoop } from "./recording-loop.mjs";
import { compactionPrompt, systemPrompt } from "./recording-prompts.mjs";

const VERSION = 1;
const COMPACT_AT = 200_000;
const KEEP_RECENT = 5;

function emptySession() {
    return { hostVersion: VERSION, id: randomUUID(), turns: [], contextMessages: [],
        contextInitialized: true, memory: "", firstKeptTurn: 0, compactions: 0, lastUsage: null };
}

function restore(stored, invalidateContext) {
    if (!stored) return emptySession();
    if (stored.hostVersion != null && stored.hostVersion !== VERSION) throw new Error("Unsupported recording session version");
    const turns = stored.turns ?? [];
    const offset = Math.max(0, stored.firstKeptTurn ?? 0);
    const contextMessages = stored.contextInitialized ? stored.contextMessages ?? [] : turns.slice(offset).flatMap((turn) => [
        { role: "user", content: turn.question },
        { role: "assistant", content: JSON.stringify({ answer: turn.answer, found: turn.citations?.length > 0, citations: turn.citations ?? [] }) },
    ]);
    const session = { ...stored, hostVersion: VERSION, turns, contextMessages, contextInitialized: true };
    return invalidateContext ? {
        ...session, contextMessages: [], firstKeptTurn: turns.length, memory: "", lastUsage: null,
    } : session;
}

function snapshot({ id, turns, compactions, lastUsage }) {
    return { id, turns, compactions: compactions ?? 0, lastUsage: lastUsage ?? null };
}

// The transport supplies opaque storage, recording access, citation verification and model I/O.
export function createRecordingSessions({ runLoop = runRecordingLoop } = {}) {
    const busy = new Set();

    return async function command(request, bridge, emit, signal) {
        const { scope, action } = request;
        const key = scope.recordingId ?? null;
        if (busy.has(key)) throw new Error("Recording session is busy");
        busy.add(key);
        try {
            const { body, invalidated } = await bridge("storage_read", {});
            signal.throwIfAborted();
            if (action === "clear") {
                const next = emptySession();
                await bridge("storage_write", { body: next, reset: true });
                return snapshot(next);
            }

            const session = restore(body, invalidated);
            if (!body || body.hostVersion !== VERSION || invalidated) {
                await bridge("storage_write", { body: session });
            }
            if (action === "open") return snapshot(session);
            if (action !== "ask") throw new Error("Unknown recording session command");
            if (request.sessionId !== session.id) throw new Error("Recording session changed. Reopen it and try again.");
            const next = await answer(session, request, bridge, emit, signal, runLoop);
            signal.throwIfAborted();
            await bridge("storage_write", { body: next });
            return snapshot(next);
        } finally {
            busy.delete(key);
        }
    };
}

function retainedStart(messages) {
    let start = Math.max(0, messages.length - KEEP_RECENT);
    if (messages[start]?.role === "tool") {
        const ids = new Set(messages.slice(start).filter((message) => message.role === "tool").map((message) => message.toolCallId));
        for (let i = start - 1; i >= 0; i--) {
            if (messages[i].calls?.some((call) => ids.has(call.id))) {
                start = i;
                break;
            }
        }
    }
    return start;
}

async function answer(session, request, bridge, emit, signal, runLoop) {
    const { question, schemas, language, scope } = request;
    const english = language === "en";
    const label = (zh, en) => english ? en : zh;
    const system = (english ? systemPrompt.replace("简体中文回答", "英语回答") + "\nAnswer in English." : systemPrompt)
        + (scope.recordingId == null ? "" : `\n只允许查询当前录音，recordingId=${scope.recordingId}。`)
        + `\n当前时间 ${request.time ?? new Date().toISOString()}。`;
    let messages = [...session.contextMessages, { role: "user", content: question }];
    let memory = session.memory ?? "";
    let usage = session.lastUsage ?? null;
    let compactions = session.compactions ?? 0;
    const trace = [];
    const ids = new Set();

    async function step(kind, title, fields, run, describe = () => ({})) {
        const initial = { id: trace.length, kind, title, status: "running", ...fields };
        trace.push(initial);
        emit("trace", initial);
        const start = performance.now();
        function finish(status, extra) {
            const ended = { ...initial, status, elapsedMs: Math.round(performance.now() - start), ...extra };
            trace[initial.id] = ended;
            emit("trace", ended);
        }
        try {
            const value = await run();
            finish("done", describe(value));
            return value;
        } catch (error) {
            finish(signal.aborted ? "cancelled" : "failed", {});
            throw error;
        }
    }

    async function compact() {
        if ((usage?.totalTokens ?? 0) < COMPACT_AT) return;
        const cut = retainedStart(messages);
        if (cut === 0) return;
        await step("compact", label("压缩上下文", "Compact context"), {}, async () => {
            const reply = await bridge("model", { tools: false, messages: [
                { role: "system", content: compactionPrompt },
                { role: "user", content: JSON.stringify({
                    previousSummary: memory, currentQuestion: question, messages: messages.slice(0, cut),
                }) },
            ] });
            if (reply.calls.length) throw new Error("Context compaction returned tool calls");
            const summary = JSON.parse(reply.text).summary?.trim();
            if (!summary) throw new Error("Context compaction returned no summary");
            memory = summary;
            messages = messages.slice(cut);
            compactions++;
            usage = null;
            emit("usage", null);
        });
    }

    async function callback(kind, input) {
        signal.throwIfAborted();
        if (kind === "model") {
            await compact();
            emit("draft", "");
            const requestMessages = [{ role: "system", content: system }];
            if (memory) requestMessages.push({ role: "user", content: JSON.stringify({
                conversationSummary: memory, note: "此前对话与工具的摘要仅作线索，事实仍需本轮原文证据。",
            }) });
            requestMessages.push(...messages);
            const reply = await step("model", label("分析与检索", "Plan and search"), {},
                () => bridge("model", { tools: true, messages: requestMessages }),
                (value) => ({ usage: value.usage ?? undefined }));
            usage = reply.usage ?? null;
            emit("usage", usage);
            if (reply.calls.length) emit("draft", "");
            for (const call of reply.calls) {
                if (!call.id || ids.has(call.id)) throw new Error("The model returned a duplicate tool call ID");
                ids.add(call.id);
            }
            messages.push({ role: "assistant", content: reply.text,
                calls: reply.calls.map(({ id, name, arguments: args }) => ({ id, name, arguments: JSON.stringify(args) })) });
            return reply;
        }
        if (kind === "tool") {
            return step("tool", input.name, { toolName: input.name, arguments: JSON.stringify(input.args) },
                () => bridge(kind, input), (value) => ({
                    status: "error" in value ? "failed" : "done", result: JSON.stringify(value),
                }));
        }
        if (kind === "toolResult") {
            messages.push({ role: "tool", content: input.text, toolCallId: input.id });
            return;
        }
        throw new Error("Unknown Pi callback");
    }

    const raw = await runLoop(question, schemas, callback, signal);
    const checked = await step("verify", label("核对原文引用", "Check citations"), {}, () => bridge("verify", { raw }));
    // Store the verified assistant message as the completed turn in the model transcript.
    const last = messages.at(-1);
    messages[messages.length - 1] = { ...last, content: JSON.stringify({
        found: checked.citations.length > 0, answer: checked.answer, citations: checked.citations,
    }) };
    await compact();
    return { ...session, turns: [...session.turns, { question, ...checked, trace, createdAt: Date.now() }],
        contextMessages: messages, memory, compactions, lastUsage: usage };
}
