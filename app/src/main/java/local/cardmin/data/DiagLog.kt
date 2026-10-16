// SPDX-License-Identifier: Apache-2.0
package local.cardmin.data

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log for troubleshooting: HTTP requests/responses, app errors and crashes.
 *
 * Secrets never reach it. JSON values are replaced by their "shape" (type, length, character
 * class) unless the key is on [VISIBLE_KEYS], ids in paths are replaced by placeholders, and
 * tokens / Authorization headers are not logged at all. It lives in the app's private storage
 * and only leaves the phone when the user copies or shares it.
 */
object DiagLog {
    private const val MAX_BYTES = 256 * 1024
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** Values of these keys are harmless and needed to understand server behaviour. */
    private val VISIBLE_KEYS = setOf(
        "status", "type", "verified", "hasSolarisCard", "isDeviceBound", "hasCardBenefit",
        "quickActionType", "benefitType", "zipCodeChangeType", "responseType", "purpose",
        "nextPage", "currency", "isReservation", "declineReason", "resultType", "isMandatory",
        "kty", "use", "alg", "applePayAvailable", "googlePayAvailable", "formattedExpirationDate",
        "start", "end", "challengedAt", "respondUntil", "timestamp", "created_at", "date",
    )

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "diag.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { event("CRASH", e.stackTraceToString().lines().take(40).joinToString("\n")) }
            previous?.uncaughtException(thread, e)
        }
        event("START", "app ${local.cardmin.BuildConfig.VERSION_NAME} (${local.cardmin.BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
    }

    fun event(tag: String, text: String) = append("$tag  $text")

    fun error(where: String, e: Throwable) {
        val api = e as? ApiException
        val detail = if (api != null) "ApiException http=${api.httpStatus} type=${api.errorType}: ${api.message}"
        else e.stackTraceToString().lines().take(12).joinToString("\n    ")
        append("ERROR  $where: $detail")
    }

    fun http(method: String, path: String, requestBody: String?, code: Int, responseBody: ByteArray?, ms: Long, contentType: String?) {
        val sb = StringBuilder("HTTP  $method ${redactPath(path)} -> $code (${ms} ms)")
        requestBody?.let { sb.append("\n  req: ").append(redactJson(it)) }
        if (responseBody != null && responseBody.isNotEmpty()) {
            val isText = contentType == null || contentType.contains("json") || contentType.startsWith("text")
            val shown = if (isText) redactJson(responseBody.toString(Charsets.UTF_8)) else "<${responseBody.size} bytes $contentType>"
            sb.append("\n  res: ").append(shown.take(if (code in 200..299) 1500 else 4000))
        }
        append(sb.toString())
    }

    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty()

    fun clear() {
        runCatching { file?.writeText("") }
        event("CLEARED", "")
    }

    fun header(): String =
        "ReSpendit ${local.cardmin.BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n"

    @Synchronized
    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${time.format(Date())}  $line\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length - MAX_BYTES / 2).substringAfter('\n'))
            }
        }
    }

    internal fun redactPath(path: String): String = path
        .replace(Regex("/appUser/[^/?]+"), "/appUser/{u}")
        .replace(Regex("/card/[^/?]+"), "/card/{c}")
        .replace(Regex("/device/(?!confirmation)[^/?]+"), "/device/{d}")
        .replace(Regex("deviceId=[^&]+"), "deviceId={d}")

    internal fun redactJson(text: String): String {
        val el = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return shape(text)
        return redact(el, null).toString()
    }

    private fun redact(el: JsonElement, key: String?): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { (k, v) -> redact(v, k) })
        is JsonArray -> JsonArray(el.map { redact(it, key) })
        is JsonNull -> el
        is JsonPrimitive -> when {
            key != null && key in VISIBLE_KEYS -> el
            el.isString -> JsonPrimitive(shape(el.content))
            else -> JsonPrimitive("<${if (el.content == "true" || el.content == "false") "bool" else "num"}>")
        }
    }

    /** E.g. "<str 130 hex>", "<str 13 +digits>", "<str 812 jwe>" – enough to spot format errors. */
    internal fun shape(s: String): String {
        val cls = when {
            s.isEmpty() -> "empty"
            s.all { it.isDigit() } -> "digits"
            s.startsWith("+") && s.drop(1).all { it.isDigit() } -> "+digits"
            s.all { it in '0'..'9' || it in 'a'..'f' } -> "hex"
            s.all { it in '0'..'9' || it in 'A'..'F' } -> "HEX"
            s.count { it == '.' } == 4 && s.all { it.isLetterOrDigit() || it in "-_." } -> "jwe"
            s.count { it == '.' } == 2 && s.all { it.isLetterOrDigit() || it in "-_." } -> "jwt"
            s.contains('@') -> "email"
            Regex("^\\d{4}-\\d{2}-\\d{2}.*").matches(s) -> "date"
            Regex("^[0-9a-fA-F-]{36}$").matches(s) -> "uuid"
            s.all { it.isLetterOrDigit() || it in "-_=+/" } -> "token"
            else -> "text"
        }
        return "<str ${s.length} $cls>"
    }
}
