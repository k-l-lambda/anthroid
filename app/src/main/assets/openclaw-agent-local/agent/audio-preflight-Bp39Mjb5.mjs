import "./paths-DgyWxrss.mjs";
import { Q as logVerbose, tt as shouldLogVerbose } from "./runtime-BX7HNrbF.mjs";
import "./plugins-CaH02lsp.mjs";
import "./accounts-BoTA3mqw.mjs";
import "./command-format-DbBGrQvz.mjs";
import "./agent-scope-Br-sgzCy.mjs";
import "./bindings-DmfwnNeP.mjs";
import "./accounts-Dk0u3MJy.mjs";
import "./image-ops-D2G5yUKM.mjs";
import "./model-auth-13_YNSQX.mjs";
import "./github-copilot-token-OVWr9mpT.mjs";
import "./dock-DJ2WpQs6.mjs";
import "./pi-model-discovery-TGFsxLkx.mjs";
import "./message-channel-Cw4zGH0u.mjs";
import "./pi-embedded-helpers-BspB02a8.mjs";
import "./chrome-yxgKkDp0.mjs";
import "./ssrf-Be_yD-tU.mjs";
import "./skills-CTvOpjdv.mjs";
import "./path-alias-guards-D3nAPD9G.mjs";
import "./redact-BjXVKS-j.mjs";
import "./errors-Dh6E9Fe4.mjs";
import "./fs-safe-CCL9Ndkr.mjs";
import "./store-Cdl4o9Mp.mjs";
import "./sessions-bouhtj9W.mjs";
import "./accounts-BN87G_jv.mjs";
import "./paths-DZte-KQN.mjs";
import "./tool-images-DpAW6JlK.mjs";
import "./thinking-ngsOAFzx.mjs";
import "./image-BWXU_JZH.mjs";
import "./gemini-auth-CtMfXb8D.mjs";
import "./fetch-guard-DtDHh4FW.mjs";
import "./local-roots-Czj16KUv.mjs";
import { a as resolveMediaAttachmentLocalRoots, n as createMediaAttachmentCache, o as runCapability, r as normalizeMediaAttachments, t as buildProviderRegistry, u as isAudioAttachment } from "./runner-CGHs1qp-.mjs";

//#region D:/user/work/openclaw/src/media-understanding/audio-preflight.ts
/**
* Transcribes the first audio attachment BEFORE mention checking.
* This allows voice notes to be processed in group chats with requireMention: true.
* Returns the transcript or undefined if transcription fails or no audio is found.
*/
async function transcribeFirstAudio(params) {
	const { ctx, cfg } = params;
	const audioConfig = cfg.tools?.media?.audio;
	if (!audioConfig || audioConfig.enabled === false) return;
	const attachments = normalizeMediaAttachments(ctx);
	if (!attachments || attachments.length === 0) return;
	const firstAudio = attachments.find((att) => att && isAudioAttachment(att) && !att.alreadyTranscribed);
	if (!firstAudio) return;
	if (shouldLogVerbose()) logVerbose(`audio-preflight: transcribing attachment ${firstAudio.index} for mention check`);
	const providerRegistry = buildProviderRegistry(params.providers);
	const cache = createMediaAttachmentCache(attachments, { localPathRoots: resolveMediaAttachmentLocalRoots({
		cfg,
		ctx
	}) });
	try {
		const result = await runCapability({
			capability: "audio",
			cfg,
			ctx,
			attachments: cache,
			media: attachments,
			agentDir: params.agentDir,
			providerRegistry,
			config: audioConfig,
			activeModel: params.activeModel
		});
		if (!result || result.outputs.length === 0) return;
		const audioOutput = result.outputs.find((output) => output.kind === "audio.transcription");
		if (!audioOutput || !audioOutput.text) return;
		firstAudio.alreadyTranscribed = true;
		if (shouldLogVerbose()) logVerbose(`audio-preflight: transcribed ${audioOutput.text.length} chars from attachment ${firstAudio.index}`);
		return audioOutput.text;
	} catch (err) {
		if (shouldLogVerbose()) logVerbose(`audio-preflight: transcription failed: ${String(err)}`);
		return;
	} finally {
		await cache.cleanup();
	}
}

//#endregion
export { transcribeFirstAudio };