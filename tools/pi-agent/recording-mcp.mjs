import { readFileSync } from "node:fs";
import { createConnection } from "node:net";
import { dirname, join } from "node:path";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";

const { port, token } = JSON.parse(readFileSync(join(dirname(process.argv[1]), "recording-mcp.json"), "utf8"));

function request(operation, parameters = {}) {
    return new Promise((resolve, reject) => {
        const socket = createConnection({ host: "127.0.0.1", port });
        socket.setEncoding("utf8");
        socket.setTimeout(5000, () => socket.destroy(new Error("Recording bridge timed out")));
        socket.once("connect", () => socket.write(JSON.stringify({ token, operation, ...parameters }) + "\n"));
        socket.once("error", reject);
        let data = "";
        socket.on("data", (chunk) => { data += chunk; });
        socket.once("end", () => {
            try { resolve(JSON.parse(data)); }
            catch (error) { reject(error); }
        });
    });
}

const server = new Server({ name: "sound-recordings", version: "1.0.0" }, { capabilities: { tools: {} } });
server.setRequestHandler(ListToolsRequestSchema, async () => {
    const { schemas } = await request("list");
    return { tools: schemas.map(({ function: fn }) => ({
        name: fn.name, description: fn.description, inputSchema: fn.parameters,
    })) };
});
server.setRequestHandler(CallToolRequestSchema, async ({ params }) => {
    const result = await request("call", { name: params.name, arguments: params.arguments ?? {} });
    return { content: [{ type: "text", text: JSON.stringify(result) }], structuredContent: result, isError: "error" in result };
});
void server.connect(new StdioServerTransport());
