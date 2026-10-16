package com.anthroid.sms

import android.content.Context
import android.system.Os
import com.anthroid.shared.logger.Logger
import com.anthroid.shared.termux.TermuxConstants
import java.io.File

/**
 * Installs the SMS client library from assets into the terminal prefix:
 *  - Python: $PREFIX/lib/anthroid/anthroid_sms.py, plus `anthroid.pth` in each
 *    python3.x site-packages so `import anthroid_sms` works without path setup
 *  - Node:   $PREFIX/lib/node/anthroid-sms.js, a global folder for `require('anthroid-sms')`
 *  - CLI:    $PREFIX/bin/anthroid-sms
 *  - Skill:  SKILL.md for Claude Code (~/.claude/skills/anthroid-sms) and the OpenClaw
 *            local agent (<agent workspace>/skills/anthroid-sms), so agents learn the
 *            library without a tool that returns message bodies
 * Files are rewritten only when their content changed (app updates ship new versions).
 */
object SmsClientInstaller {

    private const val LOG_TAG = "SmsClientInstaller"

    fun install(context: Context) {
        val prefix = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        if (!File(prefix, "bin").isDirectory) return  // bootstrap not extracted yet
        try {
            val libDir = File(prefix, "lib/anthroid")
            writeAsset(context, "sms/anthroid_sms.py", File(libDir, "anthroid_sms.py"), 0b110_000_000)
            writeAsset(context, "sms/anthroid-sms.js", File(prefix, "lib/node/anthroid-sms.js"), 0b110_000_000)
            writeAsset(context, "sms/anthroid-sms", File(prefix, "bin/anthroid-sms"), 0b111_000_000)
            val home = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
            for (skillDir in listOf(
                File(home, ".claude/skills/anthroid-sms"),
                File(home, "openclaw-agent-local/workspace/skills/anthroid-sms"),
            )) {
                writeAsset(context, "sms/SKILL.md", File(skillDir, "SKILL.md"), 0b110_000_000)
            }
            File(prefix, "lib").listFiles { f -> f.isDirectory && f.name.matches(Regex("python3\\.\\d+")) }
                ?.forEach { py ->
                    val sitePackages = File(py, "site-packages")
                    if (sitePackages.isDirectory) {
                        writeText(File(sitePackages, "anthroid.pth"), libDir.absolutePath + "\n", 0b110_000_000)
                    }
                }
        } catch (e: Exception) {
            Logger.logWarn(LOG_TAG, "Failed to install SMS client: ${e.message}")
        }
    }

    private fun writeAsset(context: Context, asset: String, target: File, mode: Int) {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        writeBytes(target, bytes, mode)
    }

    private fun writeText(target: File, text: String, mode: Int) = writeBytes(target, text.toByteArray(), mode)

    private fun writeBytes(target: File, bytes: ByteArray, mode: Int) {
        if (target.isFile && target.readBytes().contentEquals(bytes)) return
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        Os.chmod(target.absolutePath, mode)
        Logger.logInfo(LOG_TAG, "Installed ${target.absolutePath}")
    }
}
