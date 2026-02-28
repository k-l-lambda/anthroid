#!/usr/bin/env node
/**
 * Creates stub packages for channel-specific dependencies that are
 * statically imported by the agent bundle but never actually invoked
 * in anthroid's local-agent mode.
 *
 * Run: node create-stubs.mjs
 */
import fs from "node:fs/promises";
import path from "node:path";

const NM = "node_modules";

// Packages that need stubs (channel-specific, not actually used at runtime)
// Format: { named: [...export names], hasDefault: bool }
const STUB_PACKAGES = {
  "@aws-sdk/client-bedrock": {
    named: ["BedrockClient", "ListFoundationModelsCommand"],
  },
  "@buape/carbon": {
    named: [
      "Button", "ChannelSelectMenu", "ChannelType", "CheckboxGroup", "Client",
      "Command", "CommandWithSubcommands", "Container", "Embed", "File", "Label",
      "LinkButton", "MediaGallery", "MentionableSelectMenu", "MessageCreateListener",
      "MessageReactionAddListener", "MessageReactionRemoveListener", "MessageType",
      "Modal", "PresenceUpdateListener", "RadioGroup", "RateLimitError", "ReadyListener",
      "RequestClient", "RoleSelectMenu", "Row", "Section", "Separator",
      "StringSelectMenu", "TextDisplay", "TextInput", "Thumbnail", "UserSelectMenu",
      "parseCustomId", "serializePayload",
    ],
    subpaths: {
      gateway: { named: ["GatewayCloseCodes", "GatewayIntents", "GatewayPlugin"] },
      voice: { named: ["VoicePlugin"] },
    },
  },
  "@clack/prompts": {
    named: ["spinner"],
  },
  "@discordjs/voice": {
    named: [
      "AudioPlayerStatus", "EndBehaviorType", "VoiceConnectionStatus",
      "createAudioPlayer", "createAudioResource", "entersState", "joinVoiceChannel",
    ],
  },
  "@grammyjs/runner": {
    named: ["run", "sequentialize"],
  },
  "@grammyjs/transformer-throttler": {
    named: ["apiThrottler"],
  },
  "@line/bot-sdk": {
    named: ["messagingApi"],
  },
  "@slack/bolt": {
    named: [],
    hasDefault: true,
  },
  "@slack/web-api": {
    named: ["WebClient"],
  },
  "@whiskeysockets/baileys": {
    named: [
      "DisconnectReason", "downloadMediaMessage", "extractMessageContent",
      "fetchLatestBaileysVersion", "getContentType", "isJidGroup",
      "makeCacheableSignalKeyStore", "makeWASocket", "normalizeMessageContent",
      "useMultiFileAuthState",
    ],
  },
  "discord-api-types": {
    // Main package — just needs package.json with exports
    named: [],
    subpathFiles: {
      "v10.js": {
        named: [
          "ApplicationCommandOptionType", "ButtonStyle", "ChannelType",
          "MessageFlags", "PermissionFlagsBits", "Routes", "StickerFormatType",
          "TextInputStyle",
        ],
      },
      "payloads/v10.js": {
        named: ["PollLayoutType"],
      },
    },
  },
  express: {
    named: [],
    hasDefault: true,
  },
  grammy: {
    named: ["API_CONSTANTS", "Bot", "GrammyError", "HttpError", "InputFile", "webhookCallback"],
  },
  "node-edge-tts": {
    named: ["EdgeTTS"],
  },
  "playwright-core": {
    named: ["chromium", "devices"],
  },
  "qrcode-terminal": {
    named: [],
    hasDefault: true,
    subpathFiles: {
      "vendor/QRCode/QRErrorCorrectLevel.js": { named: [], hasDefault: true },
      "vendor/QRCode/index.js": { named: [], hasDefault: true },
    },
  },
};

function generateStubCode(spec) {
  const lines = ["// Auto-generated stub — not used in anthroid agent mode"];
  // Use a class-like constructor for all exports so they can be used as
  // superclasses (Class extends X) or called as constructors (new X()).
  const stubClass = "class StubClass { constructor() {} }";
  lines.push(`const StubClass = ${stubClass.replace("class StubClass", "class")};`);
  for (const name of spec.named || []) {
    // All exports are classes (safe for both `extends` and property access)
    lines.push(`export const ${name} = class ${name} extends StubClass {};`);
  }
  if (spec.hasDefault) {
    lines.push("export default class extends StubClass {};");
  }
  return lines.join("\n") + "\n";
}

async function writeStub(filePath, spec) {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  await fs.writeFile(filePath, generateStubCode(spec));
}

async function main() {
  for (const [pkg, spec] of Object.entries(STUB_PACKAGES)) {
    const dir = path.join(NM, pkg);
    await fs.mkdir(dir, { recursive: true });

    // Create package.json
    const pkgJson = {
      name: pkg,
      version: "0.0.0-stub",
      type: "module",
      main: "index.js",
    };

    // Add exports map if subpaths exist
    if (spec.subpaths || spec.subpathFiles) {
      pkgJson.exports = { ".": "./index.js" };
      if (spec.subpaths) {
        for (const sub of Object.keys(spec.subpaths)) {
          pkgJson.exports["./" + sub] = "./" + sub + "/index.js";
        }
      }
      if (spec.subpathFiles) {
        for (const sub of Object.keys(spec.subpathFiles)) {
          // Map "./<dir>/<file>" style
          const withoutExt = sub.replace(/\.js$/, "");
          pkgJson.exports["./" + withoutExt] = "./" + sub;
        }
      }
    }

    await fs.writeFile(path.join(dir, "package.json"), JSON.stringify(pkgJson, null, 2));

    // Create main index.js
    await writeStub(path.join(dir, "index.js"), spec);

    // Create subpath directories with index.js
    if (spec.subpaths) {
      for (const [sub, subSpec] of Object.entries(spec.subpaths)) {
        const subDir = path.join(dir, sub);
        await fs.mkdir(subDir, { recursive: true });
        await fs.writeFile(path.join(subDir, "package.json"), '{"type":"module"}');
        await writeStub(path.join(subDir, "index.js"), subSpec);
      }
    }

    // Create subpath files
    if (spec.subpathFiles) {
      for (const [subFile, subSpec] of Object.entries(spec.subpathFiles)) {
        await writeStub(path.join(dir, subFile), subSpec);
      }
    }

    console.log(`  stub: ${pkg}`);
  }

  console.log(`\nCreated ${Object.keys(STUB_PACKAGES).length} stub packages.`);
}

main().catch(console.error);
