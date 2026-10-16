package com.anthroid.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads SMS from the system provider for [SmsSocketServer].
 *
 * Request fields (all optional): since / until (epoch ms), since_minutes, address
 * (substring; digits-only match when the filter has digits), keyword (body substring),
 * box (inbox | sent | all, default inbox), unread_only, limit (1..200, default 20), offset.
 *
 * Message bodies are returned as stored. Callers must not log them.
 */
object SmsQuery {

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 200

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Handle one request object and build the response object. */
    fun handle(context: Context, request: JSONObject): JSONObject {
        return when (val op = request.optString("op", "query")) {
            "status" -> JSONObject().apply {
                put("ok", true)
                put("permission", hasPermission(context))
            }
            "query" -> query(context, request)
            else -> error("INVALID_REQUEST: unknown op '$op'")
        }
    }

    private fun query(context: Context, req: JSONObject): JSONObject {
        if (!hasPermission(context)) {
            return error("SMS_PERMISSION_REQUIRED: open Anthroid and grant the SMS permission")
        }

        val uri: Uri = when (val box = req.optString("box", "inbox")) {
            "inbox" -> Telephony.Sms.Inbox.CONTENT_URI
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            "all" -> Telephony.Sms.CONTENT_URI
            else -> return error("INVALID_REQUEST: box must be inbox, sent or all, got '$box'")
        }

        val where = mutableListOf<String>()
        val args = mutableListOf<String>()

        var since = req.optLong("since", 0L)
        val sinceMinutes = req.optLong("since_minutes", 0L)
        if (sinceMinutes > 0) {
            since = maxOf(since, System.currentTimeMillis() - sinceMinutes * 60_000L)
        }
        if (since > 0) {
            where += "${Telephony.Sms.DATE} >= ?"
            args += since.toString()
        }
        val until = req.optLong("until", 0L)
        if (until > 0) {
            where += "${Telephony.Sms.DATE} <= ?"
            args += until.toString()
        }

        val keyword = req.optString("keyword", "")
        if (keyword.isNotEmpty()) {
            where += "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'"
            args += "%${escapeLike(keyword)}%"
        }
        if (req.optBoolean("unread_only", false)) {
            where += "${Telephony.Sms.READ} = 0"
        }

        // Address matching is done after the query: stored numbers vary in format
        // (+86, spaces, dashes), so compare digits-only when the filter has digits.
        val addressFilter = req.optString("address", "").trim()
        val addressDigits = addressFilter.filter { it.isDigit() }

        val limit = req.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = req.optInt("offset", 0).coerceAtLeast(0)

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.BODY,
        )

        val messages = JSONArray()
        var skipped = 0
        val cursor = try {
            context.contentResolver.query(
                uri,
                projection,
                where.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                args.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${Telephony.Sms.DATE} DESC",
            )
        } catch (e: SecurityException) {
            return error("SMS_PERMISSION_REQUIRED: ${e.message}")
        } catch (e: Exception) {
            return error("QUERY_FAILED: ${e.javaClass.simpleName}: ${e.message}")
        } ?: return error("QUERY_FAILED: provider returned no cursor")

        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val iAddress = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iDateSent = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (c.moveToNext() && messages.length() < limit) {
                val address = c.getString(iAddress).orEmpty()
                if (addressFilter.isNotEmpty() && !addressMatches(address, addressFilter, addressDigits)) {
                    continue
                }
                if (skipped < offset) {
                    skipped++
                    continue
                }
                val type = c.getInt(iType)
                messages.put(JSONObject().apply {
                    put("id", c.getLong(iId))
                    put("thread_id", c.getLong(iThread))
                    put("address", address)
                    put("date", c.getLong(iDate))
                    put("date_sent", c.getLong(iDateSent))
                    put("type", typeName(type))
                    put("read", c.getInt(iRead) != 0)
                    put("body", c.getString(iBody).orEmpty())
                })
            }
        }

        return JSONObject().apply {
            put("ok", true)
            put("count", messages.length())
            put("messages", messages)
        }
    }

    private fun addressMatches(address: String, filter: String, filterDigits: String): Boolean {
        if (filterDigits.isNotEmpty()) {
            return address.filter { it.isDigit() }.contains(filterDigits)
        }
        // Alphanumeric senders (e.g. "BANK"): plain case-insensitive substring
        return address.contains(filter, ignoreCase = true)
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun typeName(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> type.toString()
    }

    fun error(message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }
}
