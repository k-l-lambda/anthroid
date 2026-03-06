#!/usr/bin/env node
/**
 * Android Tools Bridge for OpenClaw Agent
 *
 * CLI interface to anthroid's MCP server. Called by the agent via its
 * built-in bash tool:
 *
 *   node android-tools-bridge.mjs list
 *   node android-tools-bridge.mjs call <tool_name> [params_json]
 *
 * This bridges the gap between OpenClaw's agent (which has bash/read/write
 * tools) and anthroid's Android tools (exposed via MCP on localhost:8765).
 */

import { randomUUID } from "node:crypto";

const MCP_ENDPOINT = process.env.MCP_ENDPOINT || "http://localhost:8765/mcp";
const REQUEST_TIMEOUT_MS = 30000;

// ---------------------------------------------------------------------------
// MCP client helpers
// ---------------------------------------------------------------------------

async function mcpRequest(method, params = {}) {
  const body = JSON.stringify({
    jsonrpc: "2.0",
    method,
    params,
    id: randomUUID(),
  });

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const res = await fetch(MCP_ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      signal: controller.signal,
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      // Truncate error body to avoid leaking sensitive info
      const truncated = text.length > 200 ? text.slice(0, 200) + "..." : text;
      throw new Error(`MCP server returned ${res.status}: ${truncated}`);
    }

    const json = await res.json();

    if (json.error) {
      throw new Error(`MCP error ${json.error.code}: ${json.error.message}`);
    }

    return json.result;
  } finally {
    clearTimeout(timeout);
  }
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

async function listTools() {
  const result = await mcpRequest("tools/list");
  const tools = result.tools || [];

  // Output a concise summary for the agent
  const summary = tools.map((t) => {
    const params = t.inputSchema?.properties
      ? Object.entries(t.inputSchema.properties)
          .map(([name, schema]) => {
            const req = (t.inputSchema.required || []).includes(name);
            return `${name}${req ? "*" : ""}:${schema.type || "any"}`;
          })
          .join(", ")
      : "";
    return `  ${t.name}(${params}) — ${t.description || ""}`;
  });

  console.log(`Available Android tools (${tools.length}):\n${summary.join("\n")}`);
}

async function callTool(toolName, paramsJson) {
  // Validate tool name: must be alphanumeric + underscore only
  if (!/^[a-z_][a-z0-9_]*$/i.test(toolName)) {
    console.error(`Error: Invalid tool name "${toolName}". Must be alphanumeric/underscore only.`);
    process.exit(1);
  }

  let args = {};
  if (paramsJson) {
    try {
      args = JSON.parse(paramsJson);
    } catch (err) {
      console.error(`Error: Invalid JSON params: ${err.message}`);
      console.error(`Received (${paramsJson.length} chars): ${paramsJson.slice(0, 100)}...`);
      process.exit(1);
    }
  }

  const result = await mcpRequest("tools/call", {
    name: toolName,
    arguments: args,
  });

  // Extract text content from MCP result
  if (result?.content) {
    for (const block of result.content) {
      if (block.type === "text") {
        console.log(block.text);
      } else if (block.type === "image") {
        // Tolerate both mimeType and mime_type
        const mime = block.mimeType || block.mime_type || "unknown";
        console.log(`[Image: ${mime}, ${block.data?.length || 0} bytes base64]`);
      } else {
        console.log(JSON.stringify(block));
      }
    }
  } else {
    console.log(JSON.stringify(result, null, 2));
  }
}

async function healthCheck() {
  try {
    await mcpRequest("ping");
    console.log("MCP server is reachable at " + MCP_ENDPOINT);
  } catch (err) {
    console.error("MCP server unreachable: " + err.message);
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

const [, , command, ...args] = process.argv;

async function main() {
  switch (command) {
    case "list":
      await listTools();
      break;
    case "call":
      if (!args[0]) {
        console.error("Usage: android-tools-bridge.mjs call <tool_name> [params_json]");
        process.exit(1);
      }
      await callTool(args[0], args[1]);
      break;
    case "health":
      await healthCheck();
      break;
    default:
      console.error(
        "Usage:\n" +
          "  android-tools-bridge.mjs list              — List available tools\n" +
          "  android-tools-bridge.mjs call <name> [json] — Call a tool\n" +
          "  android-tools-bridge.mjs health             — Check MCP server\n"
      );
      process.exit(1);
  }
}

main().catch((err) => {
  console.error("Error: " + err.message);
  process.exit(1);
});
