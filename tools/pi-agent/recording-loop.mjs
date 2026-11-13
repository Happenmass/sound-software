import { runAgentLoop } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";

const model = { id: "recording-assistant", api: "openai-completions", provider: "openai" };

export async function runRecordingLoop(question, schemas, bridge, signal) {
    const tools = schemas.map(({ function: schema }) => ({
        name: schema.name,
        label: schema.name,
        description: schema.description,
        parameters: schema.parameters,
        async execute(id, args) {
            const result = await bridge("tool", { id, name: schema.name, args }, signal);
            return { content: [{ type: "text", text: JSON.stringify(result) }], isError: "error" in result };
        },
    }));

    const streamModel = async () => {
        const stream = createAssistantMessageEventStream();
        const message = {
            role: "assistant",
            content: [],
            model: model.id,
            api: model.api,
            provider: model.provider,
            usage: {
                input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
                cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
            },
            stopReason: "stop",
            timestamp: Date.now(),
        };
        try {
            const reply = await bridge("model", {}, signal);
            if (reply.text) message.content.push({ type: "text", text: reply.text });
            for (const call of reply.calls) {
                message.content.push({ type: "toolCall", id: call.id, name: call.name, arguments: call.arguments });
            }
            if (reply.calls.length) message.stopReason = "toolUse";
            stream.push({ type: "done", reason: message.stopReason, message });
        } catch (error) {
            message.stopReason = signal.aborted ? "aborted" : "error";
            message.errorMessage = error.message;
            stream.push({ type: "error", reason: message.stopReason, error: message });
        }
        return stream;
    };
    const messages = await runAgentLoop(
        [{ role: "user", content: question, timestamp: Date.now() }],
        { messages: [], tools },
        { model, convertToLlm: (history) => history, toolExecution: "sequential" },
        async (event) => {
            if (event.type === "message_end" && event.message.role === "toolResult") {
                const text = event.message.content.map((part) => part.text ?? "").join("\n");
                await bridge("toolResult", { id: event.message.toolCallId, text }, signal);
            }
        },
        signal,
        streamModel,
    );
    signal.throwIfAborted();
    const final = messages.at(-1);
    if (final?.role !== "assistant" || final.stopReason !== "stop") {
        throw new Error(final?.errorMessage ?? "Pi did not produce a final answer");
    }
    return final.content.filter((part) => part.type === "text").map((part) => part.text).join("");
}
