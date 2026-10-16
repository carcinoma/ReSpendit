// SPDX-License-Identifier: Apache-2.0
package local.cardmin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import local.cardmin.Config
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class ApiException(val httpStatus: Int, val errorType: String?, message: String) : Exception(message)

/** Thrown when the session can no longer be refreshed; the UI returns to the login screen. */
class SessionExpiredException : Exception("Sitzung abgelaufen – bitte erneut anmelden")

/**
 * spendit REST client limited to the card-operational endpoints (client-spec/api.md, VERIFIED.md).
 * No analytics, no push, no receipts, deals or merchants.
 */
class Api(private val store: SecureStore, private val baseUrl: String = Config.BASE_URL) {
    private val json = ApiJson

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // W3C trace context, one trace id per app session like the original (api.md §1.4).
    private val traceId = randomHex(16)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(::defaultHeaders)
        .build()

    // ---------------------------------------------------------------- session

    suspend fun login(email: String, password: String): AuthTokens {
        val tokens = call("POST", "/login", LoginBody(email, password), LoginBody.serializer(), AuthTokens.serializer())
        store.sessionToken = tokens.sessionToken
        store.refreshToken = tokens.refreshToken
        store.email = email
        return tokens
    }

    suspend fun logout() {
        runCatching { callUnit("POST", "/logout") }
        store.clearSession()
    }

    suspend fun registrationCode(lastName: String, code: String) =
        call("POST", "/registration/code", RegistrationCodeBody(lastName, code), RegistrationCodeBody.serializer(), RegistrationResponse.serializer())

    suspend fun register(email: String, password: String, registrationToken: String) =
        callUnit("POST", "/registration", RegisterBody(email, password, registrationToken), RegisterBody.serializer())

    suspend fun activateAccount(token: String) =
        callUnit("PUT", "/activation", ActivationBody(token), ActivationBody.serializer())

    suspend fun requestPasswordReset(email: String) =
        callUnit("POST", "/password/reset", EmailBody(email), EmailBody.serializer())

    suspend fun setNewPassword(token: String, password: String, repeat: String) =
        callUnit("PUT", "/password", NewPasswordBody(password, repeat, token), NewPasswordBody.serializer())

    // ---------------------------------------------------------------- user / cards

    suspend fun home() = call("GET", user("/home") + deviceQuery(), serializer = HomePage.serializer())

    suspend fun userState() = call("GET", user("/state") + deviceQuery(), serializer = UserState.serializer())

    suspend fun cards() = call("GET", user("/card"), serializer = ListSerializer(Card.serializer()))

    suspend fun activateCard(cardId: Long) = callUnit("POST", card(cardId, "/activate"), "{}")
    suspend fun blockCard(cardId: Long) = callUnit("POST", card(cardId, "/block"))
    suspend fun unblockCard(cardId: Long) = callUnit("POST", card(cardId, "/unblock"))

    /** Raw RSA JWK used to encrypt the PIN. */
    suspend fun pinKey(cardId: Long): String = request("GET", card(cardId, "/pin"), null).toString(Charsets.UTF_8)

    suspend fun setPin(cardId: Long, body: PinBody) = callUnit("PUT", card(cardId, "/pin"), body, PinBody.serializer())

    suspend fun setZipCode(cardId: Long, zip: String) =
        callUnit("PUT", card(cardId, "/zipCode"), ZipCodeBody(zip), ZipCodeBody.serializer())

    suspend fun transactions(cardId: Long, page: Int) =
        call("GET", card(cardId, "/transactions") + "?page=$page", serializer = Transactions.serializer())

    suspend fun statements(cardId: Long) =
        call("GET", card(cardId, "/accountStatement"), serializer = ListSerializer(StatementPeriod.serializer()))

    /** PDF bytes; [month] is "YYYY-MM" (= period.start.substring(0, 7)). */
    suspend fun statementPdf(cardId: Long, month: String): ByteArray =
        request("GET", card(cardId, "/accountStatement/$month"), null)

    // ---------------------------------------------------------------- mobile number

    /** 404 means no number is stored yet; the original treats that as "not verified" too. */
    suspend fun mobileNumber(): MobileNumber = try {
        call("GET", user("/mobileNumber"), serializer = MobileNumber.serializer())
    } catch (e: ApiException) {
        if (e.httpStatus == 404) MobileNumber(null, false) else throw e
    }

    suspend fun addMobileNumber(number: String) =
        callUnit("POST", user("/mobileNumber"), MobileNumberBody(number, store.deviceId), MobileNumberBody.serializer())

    suspend fun confirmMobileNumber(number: String, tan: String) =
        callUnit("POST", user("/mobileNumber/confirmation"), MobileNumberTanBody(number, tan), MobileNumberTanBody.serializer())

    suspend fun deleteMobileNumber(number: String) =
        call("DELETE", user("/mobileNumber"), MobileNumberBody(number, store.deviceId), MobileNumberBody.serializer(), MobileNumberDeleteResponse.serializer())

    suspend fun confirmMobileNumberDeletion(body: MobileNumberDeleteConfirmBody) =
        callUnit("POST", user("/mobileNumber/deleteConfirmation"), body, MobileNumberDeleteConfirmBody.serializer())

    // ---------------------------------------------------------------- device binding

    suspend fun registerDevice(name: String, publicKeyHex: String) =
        call("POST", user("/device"), DeviceBody(name, publicKeyHex), DeviceBody.serializer(), DeviceChallenge.serializer())

    suspend fun confirmDevice(challengeId: String, signedTan: String) =
        callUnit("PUT", user("/device/confirmation"), DeviceConfirmBody(challengeId, signedTan), DeviceConfirmBody.serializer())

    suspend fun registerRestrictedKey(deviceId: String, body: RestrictedKeyBody) =
        callUnit("POST", user("/device/$deviceId/key"), body, RestrictedKeyBody.serializer())

    suspend fun devices() = call("GET", user("/device"), serializer = ListSerializer(BoundDevice.serializer()))

    suspend fun deleteDevice(deviceId: String) = callUnit("DELETE", user("/device/$deviceId"))

    // ---------------------------------------------------------------- payment authorization

    suspend fun pendingActions() = call("GET", user("/notifications"), serializer = PendingActions.serializer())

    suspend fun scaAuthorize(changeRequestId: String) =
        call("POST", user("/sca/challenge/authorization"), ScaAuthorizationBody(changeRequestId, store.deviceId),
            ScaAuthorizationBody.serializer(), StringToSign.serializer())

    /** SMS path (no bound device): the server sends a TAN; the response body is not needed. */
    suspend fun scaAuthorizeForSms(changeRequestId: String) =
        callUnit("POST", user("/sca/challenge/authorization"), ScaAuthorizationBody(changeRequestId, store.deviceId), ScaAuthorizationBody.serializer())

    suspend fun scaConfirm(body: ScaConfirmationBody) =
        callUnit("POST", user("/sca/challenge/confirmation"), body, ScaConfirmationBody.serializer())

    suspend fun scaConfirmTan(changeRequestId: String, tan: String) =
        callUnit("POST", user("/sca/challenge/tanConfirmation"), ScaTanBody(changeRequestId, tan), ScaTanBody.serializer())

    suspend fun scaDecline(challengeId: String) =
        callUnit("POST", user("/sca/challenge/decline"), ScaDeclineBody(challengeId), ScaDeclineBody.serializer())

    suspend fun respondFraudCase(fraudCaseId: String, responseType: String) =
        callUnit("POST", user("/fraudCase"), FraudCaseBody(fraudCaseId, responseType), FraudCaseBody.serializer())

    // ---------------------------------------------------------------- notification centre

    suspend fun notifications() = call("GET", user("/notification"), serializer = Notifications.serializer())

    suspend fun markNotificationRead(id: Long) = callUnit("PATCH", user("/notification/$id"), """{"status":"READ"}""")

    // ---------------------------------------------------------------- plumbing

    private fun user(suffix: String): String {
        val id = store.appUserId ?: throw SessionExpiredException()
        return "/appUser/$id$suffix"
    }

    private fun card(cardId: Long, suffix: String) = user("/card/$cardId$suffix")

    private fun deviceQuery() = store.deviceId?.let { "?deviceId=$it" } ?: ""

    private suspend fun <B, R> call(
        method: String, path: String, body: B? = null, bodySerializer: KSerializer<B>? = null, serializer: KSerializer<R>,
    ): R {
        val text = body?.let { json.encodeToString(bodySerializer!!, it) }
        return json.decodeFromString(serializer, request(method, path, text).toString(Charsets.UTF_8))
    }

    private suspend fun <R> call(method: String, path: String, serializer: KSerializer<R>): R =
        call<Unit, R>(method, path, null, null, serializer)

    private suspend fun <B> callUnit(method: String, path: String, body: B, bodySerializer: KSerializer<B>) {
        request(method, path, json.encodeToString(bodySerializer, body))
    }

    private suspend fun callUnit(method: String, path: String, rawBody: String? = null) {
        request(method, path, rawBody)
    }

    /**
     * Executes a request on the IO dispatcher and returns the response body. On 401 the session is
     * refreshed once and the request retried (api.md §1.5).
     */
    private suspend fun request(method: String, path: String, body: String?): ByteArray = withContext(Dispatchers.IO) {
        val authenticated = path.substringBefore('?') !in Config.UNAUTHENTICATED_PATHS
        val usedToken = store.sessionToken
        var result = execute(method, path, body, authenticated)
        if (result.code == 401 && authenticated) {
            refreshSession(usedToken)
            result = execute(method, path, body, authenticated)
            if (result.code == 401) {
                store.clearSession()
                throw SessionExpiredException()
            }
        }
        if (result.code !in 200..299) {
            val err = runCatching { json.decodeFromString(ErrorResponse.serializer(), result.body.toString(Charsets.UTF_8)) }.getOrNull()
            throw ApiException(result.code, err?.type, Errors.message(err?.type, result.code))
        }
        result.body
    }

    private val refreshLock = Any()

    /** Refreshes the tokens unless another request already did so after [usedToken] failed. */
    private fun refreshSession(usedToken: String?) = synchronized(refreshLock) {
        if (store.sessionToken != null && store.sessionToken != usedToken) return@synchronized
        val refresh = store.refreshToken ?: run { store.clearSession(); throw SessionExpiredException() }
        val body = json.encodeToString(RefreshBody.serializer(), RefreshBody(refresh))
        val r = execute("POST", "/refreshToken", body, authenticated = false)
        if (r.code !in 200..299) {
            store.clearSession()
            throw SessionExpiredException()
        }
        val tokens = json.decodeFromString(AuthTokens.serializer(), r.body.toString(Charsets.UTF_8))
        store.sessionToken = tokens.sessionToken
        store.refreshToken = tokens.refreshToken
    }

    private class Raw(val code: Int, val body: ByteArray)

    /** Blocking; call on the IO dispatcher. Reads the whole body so nothing touches the socket later. */
    private fun execute(method: String, path: String, body: String?, authenticated: Boolean): Raw {
        val requestBody: RequestBody? = when {
            body != null -> body.toRequestBody(jsonType)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(jsonType)
            else -> null
        }
        val builder = Request.Builder().url(baseUrl + path).method(method, requestBody)
        if (authenticated) store.sessionToken?.let { builder.header("Authorization", "Bearer $it") }
        val started = System.currentTimeMillis()
        try {
            http.newCall(builder.build()).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                // The refresh exchange carries only tokens; log its status, never its bodies.
                val secret = path == "/refreshToken" || path == "/login"
                DiagLog.http(method, path, if (secret) null else body, r.code, if (secret && r.isSuccessful) null else bytes,
                    System.currentTimeMillis() - started, r.header("Content-Type"))
                return Raw(r.code, bytes)
            }
        } catch (e: IOException) {
            DiagLog.event("HTTP", "$method ${DiagLog.redactPath(path)} failed: ${e.javaClass.simpleName}: ${e.message}")
            throw ApiException(0, null, "Keine Verbindung zum Server. Bitte prüfe deine Internetverbindung.")
        }
    }

    private fun defaultHeaders(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder()
            .header("User-Agent", Config.USER_AGENT)
            .header("Accept-Language", Config.LANGUAGE)
            .header("traceparent", "00-$traceId-${randomHex(8)}-01")
            .build()
    )

    private companion object {
        val random = SecureRandom()
        fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

/** German messages for the server's ErrorType values (models.md, flows.md). */
object Errors {
    fun message(type: String?, status: Int): String = when (type) {
        "LoginFailureCredentialFailed" -> "Deine E-Mail Adresse oder Passwort ist nicht korrekt."
        "LoginFailureAccountLocked" -> "Dein Account ist vorübergehend gesperrt. Bitte versuche es in 15 Minuten erneut."
        "AlreadyActivated" -> "E-Mail bereits bestätigt."
        "ActivationTokenNotFound", "ActivationTokenNotValid" -> "Der E-Mail Link ist abgelaufen oder ungültig."
        "WrongCode" -> "Der Bestätigungs-Code ist falsch."
        "CodeExpired" -> "Der Bestätigungs-Code ist abgelaufen."
        "TooManyAttempts" -> "Zu viele Fehlversuche. Bitte starte den Vorgang erneut."
        "NoPendingChange" -> "Es gibt keinen offenen Vorgang. Bitte starte den Vorgang erneut."
        "SendLimitExceeded", "ResendLimitExceeded" -> "Zu viele Versuche. Bitte versuche es später erneut."
        "InvalidTan" -> "Die TAN ist falsch."
        "ExpiredTan" -> "Die TAN ist abgelaufen."
        "InvalidOrExpiredTan" -> "Die TAN ist falsch oder abgelaufen."
        "SignatureIncorrect" -> "Die Gerätesignatur wurde abgelehnt. Bitte verknüpfe dein Gerät neu."
        else -> when (status) {
            403 -> "Vorgang nicht erlaubt (HTTP 403)."
            404 -> "Nicht gefunden (HTTP 404)."
            412 -> "Eine Voraussetzung fehlt (HTTP 412). Meist muss zuerst die Handynummer bestätigt werden."
            429 -> "Zu viele Anfragen. Bitte versuche es in 15 Minuten erneut."
            in 500..599 -> "Serverfehler (HTTP $status). Bitte versuche es später erneut."
            else -> "Fehler (HTTP $status${type?.let { ", $it" } ?: ""})."
        }
    }
}
