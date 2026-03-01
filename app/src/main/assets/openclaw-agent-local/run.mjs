#!/usr/bin/env node
/**
 * OpenClaw Agent Local Runner for Anthroid
 *
 * Entry point that wraps OpenClaw's pi-embedded-runner and exposes it via
 * a stream-json stdio protocol compatible with anthroid's ClaudeCliClient.
 *
 * Protocol:
 *   stdin:  JSON lines — { type: "user", message: { role: "user", content: [...] } }
 *   stdout: JSON lines — { type: "system"|"stream_event"|"error", ... }
 *
 * Usage:
 *   node run.mjs [--session-dir <dir>] [--workspace-dir <dir>]
 */

import { createInterface } from "node:readline";
import { randomUUID, randomBytes } from "node:crypto";
import { createReadStream } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";

// Import the compiled agent bundle
import { runEmbeddedPiAgent } from "./agent/pi-embedded-runner.mjs";

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

const SESSION_DIR = process.env.SESSION_DIR
  || getArg("--session-dir")
  || path.join(process.cwd(), ".sessions");

const WORKSPACE_DIR = process.env.WORKSPACE_DIR
  || getArg("--workspace-dir")
  || process.cwd();

const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || "300000", 10); // 5 min default

// Provider config — defaults to Anthropic, overridable via env or stdin config
let PROVIDER = process.env.PROVIDER || "anthropic";
let MODEL = process.env.MODEL || undefined; // let agent pick default

// Base URL for provider API (e.g. ppinfra proxy)
let BASE_URL = process.env.ANTHROPIC_BASE_URL || "";

// MCP endpoint for Android tools bridge
const MCP_ENDPOINT = process.env.MCP_ENDPOINT || "http://localhost:8765/mcp";

// Android tools bridge script path
const BRIDGE_SCRIPT = new URL("./android-tools-bridge.mjs", import.meta.url).pathname;

// ---------------------------------------------------------------------------
// Stream-JSON output helpers
// ---------------------------------------------------------------------------

function emit(obj) {
  process.stdout.write(JSON.stringify(obj) + "\n");
}

function emitSystem(sessionId) {
  emit({ type: "system", session_id: sessionId });
}

function emitStreamEvent(event) {
  emit({ type: "stream_event", event });
}

function emitError(message) {
  emit({ type: "error", error: { message } });
}

function emitMessageStart(messageId) {
  emitStreamEvent({ type: "message_start", message: { id: messageId } });
}

function emitTextDelta(text) {
  emitStreamEvent({
    type: "content_block_delta",
    delta: { type: "text_delta", text },
  });
}

function emitThinkingStart() {
  emitStreamEvent({
    type: "content_block_start",
    content_block: { type: "thinking" },
  });
}

function emitThinkingDelta(text) {
  emitStreamEvent({
    type: "content_block_delta",
    delta: { type: "thinking_delta", thinking: text },
  });
}

function emitThinkingEnd() {
  emitStreamEvent({ type: "content_block_stop" });
}

function emitMessageEnd() {
  emitStreamEvent({ type: "message_stop" });
}

function emitToolUse(toolCallId, toolName, input) {
  emitStreamEvent({
    type: "tool_use",
    id: toolCallId,
    name: toolName,
    input: input || "{}",
  });
}

function emitToolResultEvent(toolCallId, content, isError) {
  emitStreamEvent({
    type: "tool_result",
    tool_use_id: toolCallId,
    content: content || "",
    is_error: isError || false,
  });
}

// ---------------------------------------------------------------------------
// Session management
// ---------------------------------------------------------------------------

let currentSessionId = null;
let currentSessionFile = null;

async function ensureSessionDir() {
  await fs.mkdir(SESSION_DIR, { recursive: true });
}

/** Read only the first line of a file without loading the entire content. */
async function readFirstLine(filePath) {
  return new Promise((resolve, reject) => {
    const stream = createReadStream(filePath, { encoding: "utf-8" });
    const rl = createInterface({ input: stream, crlfDelay: Infinity });
    rl.once("line", (line) => {
      rl.close();
      stream.destroy();
      resolve(line);
    });
    rl.once("close", () => resolve(null));
    stream.once("error", reject);
  });
}

async function getOrCreateSession() {
  await ensureSessionDir();

  // Find session files and sort by mtime (most recent first)
  const fileNames = await fs.readdir(SESSION_DIR).catch(() => []);
  const jsonlFiles = fileNames.filter(f => f.endsWith(".jsonl"));

  if (jsonlFiles.length > 0) {
    const withStats = await Promise.all(
      jsonlFiles.map(async (f) => {
        const full = path.join(SESSION_DIR, f);
        const stat = await fs.stat(full).catch(() => null);
        return { file: full, mtime: stat?.mtimeMs ?? 0 };
      }),
    );
    withStats.sort((a, b) => b.mtime - a.mtime);

    for (const { file: sessionFile } of withStats) {
      try {
        const firstLine = await readFirstLine(sessionFile);
        if (!firstLine) continue;
        const header = JSON.parse(firstLine);
        if (header.type === "session" && header.id) {
          return { sessionId: header.id, sessionFile };
        }
      } catch {
        // Quarantine corrupted session file
        const corruptName = sessionFile + ".corrupt." + Date.now();
        await fs.rename(sessionFile, corruptName).catch(() => {});
      }
    }
  }

  // Create a new session
  const sessionId = randomUUID();
  const sessionFile = path.join(SESSION_DIR, `session-${sessionId}.jsonl`);
  const header = {
    type: "session",
    version: 3,
    id: sessionId,
    timestamp: new Date().toISOString(),
    cwd: WORKSPACE_DIR,
  };
  await fs.writeFile(sessionFile, JSON.stringify(header) + "\n");
  return { sessionId, sessionFile };
}

// ---------------------------------------------------------------------------
// Android tools discovery
// ---------------------------------------------------------------------------

let androidToolsPrompt = null;

async function discoverAndroidTools() {
  try {
    const body = JSON.stringify({
      jsonrpc: "2.0",
      method: "tools/list",
      params: {},
      id: "discover",
    });
    const res = await fetch(MCP_ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      signal: AbortSignal.timeout(5000),
    });

    if (!res.ok) return null;
    const json = await res.json();
    if (json.error || !json.result?.tools) return null;

    const tools = json.result.tools;
    const toolDocs = tools.map((t) => {
      const params = t.inputSchema?.properties
        ? Object.entries(t.inputSchema.properties)
            .map(([name, schema]) => {
              const req = (t.inputSchema.required || []).includes(name);
              return `    ${name}${req ? " (required)" : ""}: ${schema.type || "any"} — ${schema.description || ""}`;
            })
            .join("\n")
        : "    (no parameters)";
      return `- **${t.name}**: ${t.description || ""}\n${params}`;
    });

    // Build allowlist of valid tool names for validation
    const validNames = tools.map((t) => t.name).filter((n) => /^[a-z_][a-z0-9_]*$/i.test(n));

    return (
      `\n# Android Device Tools\n\n` +
      `You are running on an Android device. You have access to ${validNames.length} Android tools ` +
      `via the MCP bridge. To call a tool, use the bash tool:\n\n` +
      "```bash\n" +
      `node ${BRIDGE_SCRIPT} call <tool_name> '<json_params>'\n` +
      "```\n\n" +
      `IMPORTANT: tool_name MUST be one of the names listed below (alphanumeric + underscore only). ` +
      `json_params must be valid JSON wrapped in single quotes.\n\n` +
      `Examples:\n` +
      "```bash\n" +
      `node ${BRIDGE_SCRIPT} call get_device_info\n` +
      `node ${BRIDGE_SCRIPT} call take_screenshot\n` +
      `node ${BRIDGE_SCRIPT} call click_element '{"text":"OK"}'\n` +
      `node ${BRIDGE_SCRIPT} call show_notification '{"title":"Hello","message":"World"}'\n` +
      "```\n\n" +
      `Available tools:\n${toolDocs.join("\n\n")}\n`
    );
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Abort control
// ---------------------------------------------------------------------------

let activeRunAbort = null;

function abortActiveRun() {
  if (activeRunAbort) {
    activeRunAbort.abort();
    activeRunAbort = null;
  }
}

// ---------------------------------------------------------------------------
// Agent execution
// ---------------------------------------------------------------------------

async function runAgent(prompt, images) {
  if (!currentSessionId) {
    const session = await getOrCreateSession();
    currentSessionId = session.sessionId;
    currentSessionFile = session.sessionFile;
  }

  emitSystem(currentSessionId);

  const messageId = `msg_${randomBytes(12).toString("hex")}`;
  const runId = `run_${randomBytes(8).toString("hex")}`;

  // Create abort controller for this run
  const abortCtrl = new AbortController();
  activeRunAbort = abortCtrl;

  emitMessageStart(messageId);

  let isThinking = false;
  let hasText = false;
  let lastPartialText = "";  // Track cumulative text to emit only incremental deltas
  let activeToolCallId = null;  // Currently executing tool call ID
  let toolResultText = "";      // Accumulated tool result text

  try {
    // Discover Android tools on first run
    if (androidToolsPrompt === null) {
      androidToolsPrompt = await discoverAndroidTools() || "";
    }

    // Build config with provider overrides for custom base URL / proxy models.
    // When ANTHROPIC_BASE_URL is set (e.g. ppinfra proxy), configure the provider
    // with that base URL. If the MODEL includes a prefix like "pa/", register it
    // as a custom model so the agent's model resolution recognizes it.
    let agentConfig;
    if (BASE_URL || (MODEL && MODEL.includes("/"))) {
      const providerCfg = {};
      if (BASE_URL) providerCfg.baseUrl = BASE_URL;

      // Register prefixed models (e.g. "pa/claude-sonnet-4-6") as custom models.
      // The models field must be an array of { id, contextWindow, ... } objects.
      if (MODEL && MODEL.includes("/")) {
        providerCfg.models = [{
          id: MODEL,
          api: "anthropic-messages",
          contextWindow: 200000,
          maxOutput: 64000,
          input: ["text", "image"],
          output: ["text"],
        }];
      }

      agentConfig = {
        models: {
          providers: {
            [PROVIDER]: providerCfg,
          },
        },
      };
    }

    const result = await runEmbeddedPiAgent({
      sessionId: currentSessionId,
      sessionFile: currentSessionFile,
      workspaceDir: WORKSPACE_DIR,
      prompt,
      images: images || undefined,
      provider: PROVIDER,
      model: MODEL,
      config: agentConfig,
      timeoutMs: TIMEOUT_MS,
      runId,
      abortSignal: abortCtrl.signal,
      extraSystemPrompt: androidToolsPrompt || undefined,

      // Stream text to stdout as deltas.
      // onPartialReply sends cumulative text, so we diff against lastPartialText
      // to emit only the new incremental portion.
      onPartialReply: (payload) => {
        if (isThinking) {
          emitThinkingEnd();
          isThinking = false;
        }
        if (payload.text) {
          hasText = true;
          const newText = payload.text.startsWith(lastPartialText)
            ? payload.text.slice(lastPartialText.length)
            : payload.text;
          lastPartialText = payload.text;
          if (newText) {
            emitTextDelta(newText);
          }
        }
      },

      // Stream reasoning/thinking
      onReasoningStream: (payload) => {
        if (!isThinking) {
          emitThinkingStart();
          isThinking = true;
        }
        if (payload.text) {
          emitThinkingDelta(payload.text);
        }
      },

      onReasoningEnd: () => {
        if (isThinking) {
          emitThinkingEnd();
          isThinking = false;
        }
      },

      // Tool result text — accumulate for the active tool call
      onToolResult: (payload) => {
        if (payload.text && activeToolCallId) {
          toolResultText += payload.text;
        }
      },

      // Agent lifecycle events — emit tool_use / tool_result for UI
      onAgentEvent: (evt) => {
        if (evt?.stream === "tool" && evt.data) {
          if (evt.data.phase === "start") {
            activeToolCallId = evt.data.toolCallId;
            toolResultText = "";
            emitToolUse(evt.data.toolCallId, evt.data.name);
          } else if (evt.data.phase === "result") {
            emitToolResultEvent(
              evt.data.toolCallId,
              toolResultText.slice(0, 2000),
              evt.data.isError || false,
            );
            activeToolCallId = null;
            toolResultText = "";
          }
        }
      },

      shouldEmitToolResult: () => true,
      shouldEmitToolOutput: () => true,
    });

    // Close thinking if still open
    if (isThinking) {
      emitThinkingEnd();
      isThinking = false;
    }

    // If agent returned payloads that weren't streamed, emit them now
    if (result.payloads) {
      for (const payload of result.payloads) {
        if (payload.text && !hasText) {
          emitTextDelta(payload.text);
        }
        if (payload.isError) {
          emitError(payload.text || "Unknown agent error");
        }
      }
    }

    // Emit run metadata as a final text block (optional)
    if (result.meta?.agentMeta) {
      const meta = result.meta.agentMeta;
      const metaText = `\n<!-- agent: ${meta.provider}/${meta.model}, tokens: ${meta.usage?.total || "?"} -->`;
      // Emit as comment for debugging (Kotlin can optionally hide these)
      emitTextDelta(metaText);
    }

    emitMessageEnd();

    // Check for errors
    if (result.meta?.error) {
      emitError(`Agent error (${result.meta.error.kind}): ${result.meta.error.message}`);
    }
  } catch (err) {
    if (isThinking) {
      emitThinkingEnd();
    }
    // Redact potential API keys from error messages
    const errMsg = (err.message || String(err))
      .replace(/sk-[a-zA-Z0-9_-]{10,}/g, "sk-***REDACTED***")
      .replace(/key[=:]\s*[^\s,;]{10,}/gi, "key=***REDACTED***");
    emitError(errMsg);
    emitMessageEnd();
  } finally {
    activeRunAbort = null;
  }
}

// ---------------------------------------------------------------------------
// Stdin reader — accepts JSON lines
// ---------------------------------------------------------------------------

function getArg(flag) {
  const idx = process.argv.indexOf(flag);
  return idx !== -1 && idx + 1 < process.argv.length ? process.argv[idx + 1] : null;
}

async function main() {
  // If --prompt is given, run single-shot
  const singlePrompt = getArg("--prompt");
  if (singlePrompt) {
    await runAgent(singlePrompt);
    process.exit(0);
  }

  // Otherwise, read JSON lines from stdin (interactive mode)
  const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });

  for await (const line of rl) {
    const trimmed = line.trim();
    if (!trimmed) continue;

    try {
      const msg = JSON.parse(trimmed);

      if (msg.type === "config") {
        // Receive API credentials via stdin instead of env (security: avoids /proc exposure)
        if (msg.apiKey) process.env.ANTHROPIC_API_KEY = msg.apiKey;
        if (msg.baseUrl) {
          process.env.ANTHROPIC_BASE_URL = msg.baseUrl;
          BASE_URL = msg.baseUrl;
        }
        if (msg.model) {
          process.env.MODEL = msg.model;
          MODEL = msg.model;
        }
        if (msg.provider) PROVIDER = msg.provider;
        process.stderr.write("[openclaw-agent] config received via stdin\n");
        continue;
      }

      if (msg.type === "user" && msg.message) {
        // Extract text and images from the message content
        let prompt = "";
        const images = [];

        if (typeof msg.message.content === "string") {
          prompt = msg.message.content;
        } else if (Array.isArray(msg.message.content)) {
          for (const block of msg.message.content) {
            if (block.type === "text") {
              prompt += block.text;
            } else if (block.type === "image" && block.source) {
              images.push({
                type: "base64",
                media_type: block.source.media_type,
                data: block.source.data,
              });
            }
          }
        }

        if (prompt) {
          await runAgent(prompt, images.length > 0 ? images : undefined);
        }
      } else if (msg.type === "abort") {
        abortActiveRun();
        process.stderr.write("[openclaw-agent] abort requested\n");
      }
    } catch (err) {
      process.stderr.write(`[openclaw-agent] parse error: ${err.message}\n`);
      emitError(`Input parse error: ${err.message}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Graceful shutdown
// ---------------------------------------------------------------------------

function shutdown(signal) {
  process.stderr.write(`[openclaw-agent] received ${signal}, shutting down\n`);
  abortActiveRun();
  // Give a brief moment for cleanup, then exit
  setTimeout(() => process.exit(0), 500);
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGHUP", () => shutdown("SIGHUP"));

main().catch((err) => {
  process.stderr.write(`[openclaw-agent] fatal: ${err.message}\n${err.stack}\n`);
  process.exit(1);
});
