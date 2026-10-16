package com.anthroid.sms

import android.content.Context
import com.anthroid.shared.logger.Logger
import com.anthroid.shared.net.socket.local.LocalClientSocket
import com.anthroid.shared.net.socket.local.LocalSocketManager
import com.anthroid.shared.net.socket.local.LocalSocketManagerClientBase
import com.anthroid.shared.net.socket.local.LocalSocketRunConfig
import com.anthroid.shared.termux.TermuxConstants
import org.json.JSONObject

/**
 * Unix socket server that lets terminal scripts read SMS (Python / Node / `anthroid-sms`).
 *
 * Deliberately not an MCP tool: scripts can filter messages and print only what an agent
 * needs, instead of a tool call returning message bodies into the model context.
 *
 * Built on termux-shared LocalSocketManager (like termux-am-socket): a filesystem socket
 * in the app's private apps dir, and peers whose uid is not the app's (or root) are
 * rejected, so other apps cannot connect.
 *
 * Protocol: the client writes one JSON request and shuts down its write side; the server
 * replies with one JSON object and closes. See [SmsQuery] for request fields.
 */
object SmsSocketServer {

    private const val LOG_TAG = "SmsSocketServer"
    private const val TITLE = "AnthroidSms"

    val SOCKET_PATH: String = TermuxConstants.TERMUX_APP.APPS_DIR_PATH + "/sms/sms.sock"

    private var manager: LocalSocketManager? = null

    @Synchronized
    fun start(context: Context) {
        if (manager?.isRunning == true) return
        val config = LocalSocketRunConfig(TITLE, SOCKET_PATH, Client())
        val localSocketManager = LocalSocketManager(context.applicationContext, config)
        val error = localSocketManager.start()
        if (error != null) {
            localSocketManager.onError(error)
            return
        }
        manager = localSocketManager
        Logger.logInfo(LOG_TAG, "SMS socket server listening on $SOCKET_PATH")
    }

    @Synchronized
    fun stop() {
        manager?.stop()
        manager = null
    }

    private class Client : LocalSocketManagerClientBase() {

        override fun getLogTag(): String = LOG_TAG

        override fun onClientAccepted(localSocketManager: LocalSocketManager, clientSocket: LocalClientSocket) {
            val response = try {
                val data = StringBuilder()
                val readError = clientSocket.readDataOnInputStream(data, true)
                if (readError != null) {
                    SmsQuery.error("READ_FAILED: ${readError.message}")
                } else {
                    val request = if (data.isBlank()) JSONObject() else JSONObject(data.toString())
                    // Log the request shape only: filter values and results may be private.
                    Logger.logDebug(LOG_TAG, "request op=${request.optString("op", "query")} " +
                        "keys=${request.keys().asSequence().toList()}")
                    SmsQuery.handle(localSocketManager.context, request).also {
                        Logger.logDebug(LOG_TAG, "response ok=${it.optBoolean("ok")} count=${it.optInt("count", 0)}")
                    }
                }
            } catch (e: Exception) {
                SmsQuery.error("INVALID_REQUEST: ${e.javaClass.simpleName}: ${e.message}")
            }
            val sendError = clientSocket.sendDataToOutputStream(response.toString(), true)
            if (sendError != null) {
                Logger.logWarn(LOG_TAG, "Failed to send SMS response: ${sendError.message}")
            }
            super.onClientAccepted(localSocketManager, clientSocket)
        }
    }
}
