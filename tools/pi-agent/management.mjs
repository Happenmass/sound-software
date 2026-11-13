import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { randomUUID } from "node:crypto";
import { z } from "zod";

// Both hosts register this module. Each registry belongs to one authorized caller.
export function createAgentManagement(run, sessions) {
    const server = new McpServer({ name: "soundsoftware-agents", version: "0.1.0" });
    const jobs = new Map();
    const jobInput = { taskId: z.string().uuid() };
    const result = (value) => ({
        content: [{ type: "text", text: JSON.stringify(value) }],
        structuredContent: value,
    });
    const register = (name, description, inputSchema, handler) => {
        server.registerTool(name, { description, inputSchema }, async (input) => result(await handler(input)));
    };
    const getJob = (taskId) => {
        const job = jobs.get(taskId);
        if (!job) throw new Error("Unknown agent task");
        return job;
    };
    const snapshot = ({ taskId, status }) => ({ taskId, status });
    const scope = z.object({ recordingId: z.string().min(1).nullable() });

    if (sessions) {
        register("agent_session_open", "Open the conversation for an authorized recording scope", { scope },
            ({ scope }) => sessions("open", scope));
        register("agent_session_clear", "Reset the conversation for an authorized recording scope", { scope },
            ({ scope }) => sessions("clear", scope));
    }

    register("agent_list", "List the available agents", {}, () => ({
        agents: [{ id: "recording_assistant", description: "Answer questions from authorized recording transcripts" }],
    }));
    register("agent_start", "Start a recording assistant task", {
        agentId: z.literal("recording_assistant"),
        question: z.string().trim().min(1),
        sessionId: z.string().uuid().optional(),
    }, ({ question, sessionId }) => {
        const job = { taskId: randomUUID(), status: "running", abort: new AbortController() };
        jobs.set(job.taskId, job);
        job.done = Promise.resolve().then(() => run(question, job.abort.signal, sessionId)).then((answer) => {
            if (job.status === "running") {
                job.answer = answer;
                job.status = "succeeded";
            }
        }, (error) => {
            if (job.status === "running") {
                job.error = error instanceof Error ? error.message : "Agent task failed";
                job.status = "failed";
            }
        });
        return snapshot(job);
    });
    register("agent_status", "Read the current task status", jobInput, ({ taskId }) => snapshot(getJob(taskId)));
    register("agent_cancel", "Cancel a running agent task", jobInput, ({ taskId }) => {
        const job = getJob(taskId);
        if (job.status === "running") {
            job.status = "cancelled";
            job.abort.abort();
        }
        return snapshot(job);
    });
    register("agent_result", "Read the completed agent result", jobInput, ({ taskId }) => {
        const job = getJob(taskId);
        return { ...snapshot(job), ...(job.answer !== undefined && { answer: job.answer }), ...(job.error && { error: job.error }) };
    });
    return {
        server,
        wait: (taskId) => getJob(taskId).done,
        cancelAll: () => {
            for (const job of jobs.values()) {
                if (job.status === "running") {
                    job.status = "cancelled";
                    job.abort.abort();
                }
            }
        },
    };
}
