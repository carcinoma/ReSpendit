// SPDX-License-Identifier: Apache-2.0
package local.cardmin.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.cardmin.Config
import local.cardmin.crypto.DeviceKeys
import local.cardmin.crypto.PinCrypto
import local.cardmin.data.Api
import local.cardmin.data.BoundDevice
import local.cardmin.data.Card
import local.cardmin.data.DiagLog
import local.cardmin.data.Company
import local.cardmin.data.DeviceChallenge
import local.cardmin.data.MobileNumberDeleteConfirmBody
import local.cardmin.data.Notification
import local.cardmin.data.PendingActions
import local.cardmin.data.PinBody
import local.cardmin.data.RestrictedKeyBody
import local.cardmin.data.ScaChallenge
import local.cardmin.data.ScaConfirmationBody
import local.cardmin.data.SecureStore
import local.cardmin.data.SessionExpiredException
import local.cardmin.data.StatementPeriod
import local.cardmin.data.Transaction
import java.io.File

/** Implemented by the Activity: things that need a window. */
interface Host {
    /** Shows a BiometricPrompt (biometrics or screen lock). Returns null if the user cancelled. */
    suspend fun authenticate(title: String, crypto: BiometricPrompt.CryptoObject?): BiometricPrompt.AuthenticationResult?
    fun openPdf(file: File)
}

class UserCancelled : Exception("Abgebrochen")

enum class PinMode { SETUP, CHANGE, ACTIVATE_WITH_NEW_PIN }

sealed interface TanPurpose {
    data class AddPhone(val number: String) : TanPurpose
    data class DeletePhone(val number: String, val changeRequestId: String) : TanPurpose
    data class BindDevice(val challenge: DeviceChallenge) : TanPurpose
    data class Sca(val challenge: ScaChallenge) : TanPurpose
}

sealed interface Screen {
    data object Loading : Screen
    data object Login : Screen
    data object Register : Screen
    data class RegisterAccount(val registrationToken: String) : Screen
    data object ActivateAccount : Screen
    data object ForgotPassword : Screen
    data object ResetPassword : Screen
    data class ChooseCompany(val companies: List<Company>) : Screen
    data object Home : Screen
    data object PhoneEntry : Screen
    data object PhoneManage : Screen
    data class Tan(val purpose: TanPurpose, val info: String) : Screen
    data object BindDevice : Screen
    data class Pin(val card: Card, val mode: PinMode) : Screen
    data class BlockCard(val card: Card, val block: Boolean) : Screen
    data class ActivateCard(val card: Card) : Screen
    data class Region(val card: Card) : Screen
    data class Transactions(val card: Card) : Screen
    data class Statements(val card: Card) : Screen
    data object Devices : Screen
    data object Notifications : Screen
    data class Done(val title: String, val text: String) : Screen
    data object About : Screen
    data object Diagnostics : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    private val api = Api(store)
    var host: Host? = null

    // ---- navigation
    private val stack = mutableStateListOf<Screen>(Screen.Loading)
    val screen: Screen get() = stack.last()
    val canGoBack get() = !busy && stack.size > 1 && screen !is Screen.Home && screen !is Screen.Login

    private fun push(s: Screen) { logScreen(s); stack.add(s) }
    private fun replace(s: Screen) { logScreen(s); stack[stack.lastIndex] = s }
    private fun reset(s: Screen) { logScreen(s); stack.clear(); stack.add(s) }
    private fun logScreen(s: Screen) {
        val extra = (s as? Screen.Tan)?.purpose?.let { " (${it::class.simpleName})" } ?: (s as? Screen.Pin)?.let { " (${it.mode})" } ?: ""
        DiagLog.event("SCREEN", s::class.simpleName + extra)
    }
    fun back() {
        if (busy) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        if (screen is Screen.Home) setupCard = null
    }
    private fun home() { setupCard = null; reset(Screen.Home); refreshHome() }
    private fun done(title: String, text: String) { reset(Screen.Home); push(Screen.Done(title, text)); refreshHome() }
    fun closeDone() = home()
    fun openAbout() = push(Screen.About)
    fun openDiagnostics() = push(Screen.Diagnostics)

    // ---- shared UI state
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var cards by mutableStateOf<List<Card>>(emptyList()); private set
    var pending by mutableStateOf(PendingActions()); private set
    var devices by mutableStateOf<List<BoundDevice>>(emptyList()); private set
    var phoneNumber by mutableStateOf<String?>(null); private set
    var notifications by mutableStateOf<List<Notification>>(emptyList()); private set
    var transactions by mutableStateOf<List<Transaction>>(emptyList()); private set
    var nextTransactionPage by mutableStateOf<Int?>(null); private set
    var balanceCents by mutableStateOf<Long?>(null); private set
    var statements by mutableStateOf<List<StatementPeriod>>(emptyList()); private set

    private val activatedCards = mutableSetOf<Long>()

    /** Card whose setup wizard is running (phone → device → PIN). */
    private var setupCard: Card? = null

    val deviceName: String get() = Build.MODEL ?: "Android"
    private var lastDeviceName = deviceName
    val isDeviceBound get() = store.deviceId != null && DeviceKeys.hasDeviceKey()

    init {
        DiagLog.init(app)
        // Data is loaded by the Activity's onResume.
        reset(if (store.sessionToken != null && store.appUserId != null) Screen.Home else Screen.Login)
    }

    /** Runs [block] with a busy indicator and turns exceptions into a message. */
    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: SessionExpiredException) {
                DiagLog.event("SESSION", "expired")
                store.clearSession()
                reset(Screen.Login)
                message = e.message
            } catch (e: UserCancelled) {
                DiagLog.event("PROMPT", "cancelled by user")
            } catch (e: Exception) {
                DiagLog.error(screen::class.simpleName ?: "?", e)
                message = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    // ================================================================ session

    fun login(email: String, password: String) = run {
        val previousEmail = store.email
        val tokens = api.login(email.trim(), password)
        if (previousEmail != null && !previousEmail.equals(email.trim(), ignoreCase = true)) {
            // Another user on this install: the binding keys belong to the previous account.
            DeviceKeys.deleteAll()
            store.clearDevice()
        }
        val companies = tokens.appUsers.filter { it.appUserId != null }
        when {
            companies.isEmpty() -> error("Kein Unternehmen mit diesem Account verknüpft.")
            companies.size == 1 -> selectCompanyNow(companies.single())
            else -> { reset(Screen.Login); push(Screen.ChooseCompany(companies)) }
        }
    }

    fun selectCompany(c: Company) = run { selectCompanyNow(c) }

    private fun selectCompanyNow(c: Company) {
        store.appUserId = c.appUserId.toString()
        home()
        startPolling()
    }

    fun logout() = run {
        stopPolling()
        api.logout()
        cards = emptyList(); pending = PendingActions()
        reset(Screen.Login)
    }

    fun openRegister() = push(Screen.Register)
    fun openActivateAccount() = push(Screen.ActivateAccount)
    fun openForgotPassword() = push(Screen.ForgotPassword)
    fun openResetPassword() = push(Screen.ResetPassword)

    fun submitRegistrationCode(lastName: String, code: String) = run {
        val r = api.registrationCode(lastName.trim(), code.trim())
        replace(Screen.RegisterAccount(r.registrationToken))
    }

    fun register(token: String, email: String, password: String, repeat: String) = run {
        Validation.password(password, repeat)?.let { error(it) }
        api.register(email.trim(), password, token)
        reset(Screen.Login)
        message = "Account erstellt. Wir haben dir eine E-Mail gesendet – bestätige den Link bzw. füge den Aktivierungs-Code unter \"Account aktivieren\" ein."
    }

    fun activateAccount(token: String) = run {
        api.activateAccount(Validation.tokenFromLink(token))
        reset(Screen.Login)
        message = "Dein Account wurde aktiviert. Du kannst dich jetzt anmelden!"
    }

    fun requestPasswordReset(email: String) = run {
        api.requestPasswordReset(email.trim())
        replace(Screen.ResetPassword)
        message = "Sofern du einen Account besitzt, haben wir dir eine E-Mail zum Zurücksetzen gesendet."
    }

    fun resetPassword(token: String, password: String, repeat: String) = run {
        Validation.password(password, repeat)?.let { error(it) }
        api.setNewPassword(Validation.tokenFromLink(token), password, repeat)
        reset(Screen.Login)
        message = "Neues Passwort wurde vergeben. Du kannst dich jetzt anmelden."
    }

    // ================================================================ home + polling

    fun refreshHome() = viewModelScope.launch {
        try {
            cards = api.cards()
            pending = api.pendingActions()
        } catch (e: SessionExpiredException) {
            store.clearSession(); reset(Screen.Login); message = e.message
        } catch (e: Exception) {
            DiagLog.error("refreshHome", e)
            message = e.message
        }
    }

    private var pollJob: Job? = null

    /** The original relies on push; without it, poll pending approvals while the app is visible. */
    fun startPolling() {
        if (pollJob?.isActive == true || store.appUserId == null) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                runCatching { if (store.sessionToken != null) pending = api.pendingActions() }
                    .onFailure { if (it is SessionExpiredException) { reset(Screen.Login); return@launch } }
                delay(Config.PENDING_POLL_MS)
            }
        }
    }

    fun stopPolling() { pollJob?.cancel(); pollJob = null }

    // ================================================================ card setup wizard

    fun startSetup(card: Card) = run {
        setupCard = card
        continueSetup()
    }

    private suspend fun resumeSetup() {
        val card = setupCard
        reset(Screen.Home)
        setupCard = card
        continueSetup()
    }

    private suspend fun continueSetup() {
        val card = setupCard ?: return home()
        val mobile = api.mobileNumber()
        phoneNumber = mobile.mobileNumber
        when {
            !mobile.verified -> push(Screen.PhoneEntry)
            !isDeviceBound -> push(Screen.BindDevice)
            else -> push(Screen.Pin(card, if (card.has("ACTIVATE_CARD_NEW_PIN")) PinMode.ACTIVATE_WITH_NEW_PIN else PinMode.SETUP))
        }
    }

    // ================================================================ phone number

    fun openPhone() = run {
        phoneNumber = api.mobileNumber().takeIf { it.verified }?.mobileNumber
        push(Screen.PhoneManage)
    }

    fun submitPhone(input: String) = run {
        val number = Validation.phone(input) ?: error("Die Handynummer ist nicht gültig")
        api.addMobileNumber(number)
        push(Screen.Tan(TanPurpose.AddPhone(number), "Wir haben dir eine TAN an $number gesendet."))
    }

    /** Removing the old number comes first when changing it (flows.md §4.6). */
    fun removePhone() = run {
        val number = phoneNumber ?: return@run push(Screen.PhoneEntry)
        val r = api.deleteMobileNumber(number)
        val deviceId = store.deviceId
        if (isDeviceBound && deviceId != null && r.stringToSign != null) {
            val signature = signRestricted(r.stringToSign, "Handynummer entfernen")
            api.confirmMobileNumberDeletion(MobileNumberDeleteConfirmBody(r.changeRequestId, deviceId = deviceId, signature = signature))
            phoneNumber = null
            replace(Screen.PhoneEntry)
        } else {
            push(Screen.Tan(TanPurpose.DeletePhone(number, r.changeRequestId), "Wir haben dir eine TAN an deine bisherige Nummer gesendet."))
        }
    }

    // ================================================================ device binding

    fun bindDevice(name: String) = run {
        if (name.isBlank()) error("Bitte gib einen Gerätenamen ein.")
        // The server refuses POST /device (412) until a mobile number is confirmed.
        if (!api.mobileNumber().verified) {
            push(Screen.PhoneEntry)
            message = "Bitte bestätige zuerst deine Handynummer. Danach kannst du das Gerät verknüpfen."
            return@run
        }
        val km = getApplication<Application>().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) error("Dein Gerät ist nicht ausreichend geschützt. Bitte aktiviere eine Bildschirmsperre (PIN, Muster oder biometrische Anmeldung).")
        val bound = runCatching { api.devices() }.getOrNull()
        if (bound != null && bound.size >= Config.MAX_BOUND_DEVICES) {
            error("Du hast die maximale Anzahl von ${Config.MAX_BOUND_DEVICES} verknüpften Geräten erreicht. Entferne zuerst ein Gerät in der Geräteverwaltung.")
        }
        DeviceKeys.regenerate()
        DiagLog.event("KEYS", "generated device + restricted key")
        store.clearDevice()
        lastDeviceName = name.trim()
        val challenge = api.registerDevice(lastDeviceName, DeviceKeys.devicePublicKeyHex())
        push(Screen.Tan(TanPurpose.BindDevice(challenge), "Wir haben dir eine TAN an ${challenge.maskedMobileNumber ?: "deine Handynummer"} gesendet."))
    }

    private suspend fun finishDeviceBinding(challenge: DeviceChallenge, tan: String) {
        // The binding TAN itself is signed with the new device key (VERIFIED.md).
        api.confirmDevice(challenge.challengeId, DeviceKeys.signWithDeviceKey(tan).trim())
        // The server has bound the device now; persist that before anything else can fail.
        store.deviceId = challenge.deviceId
        registerRestrictedKey(challenge.deviceId)
    }

    private suspend fun registerRestrictedKey(deviceId: String) {
        store.restrictedKeyRegistered = null
        val pub = DeviceKeys.restrictedPublicKeyHex()
        api.registerRestrictedKey(deviceId, RestrictedKeyBody(pub, DeviceKeys.signWithDeviceKey(pub)))
        store.restrictedKeyRegistered = "1"
    }

    /** Before a restricted operation: recreate a missing key, retry an unfinished registration. */
    private suspend fun ensureRestrictedKey() {
        val deviceId = store.deviceId ?: error("Gerät nicht verknüpft")
        if (!DeviceKeys.hasRestrictedKey()) {
            DiagLog.event("KEYS", "restricted key missing -> regenerate + re-register")
            DeviceKeys.regenerateRestricted()
            registerRestrictedKey(deviceId)
        } else if (store.restrictedKeyRegistered == null) {
            registerRestrictedKey(deviceId)
        }
    }

    /** Signs with the auth-bound restricted key; re-registers it if the screen lock changed. */
    private suspend fun signRestricted(text: String, title: String): String {
        ensureRestrictedKey()
        val sig = try {
            DeviceKeys.prepareRestrictedSignature()
        } catch (e: KeyPermanentlyInvalidatedException) {
            DiagLog.event("KEYS", "restricted key invalidated -> regenerate + re-register")
            DeviceKeys.regenerateRestricted()
            registerRestrictedKey(store.deviceId ?: error("Gerät nicht verknüpft"))
            DeviceKeys.prepareRestrictedSignature()
        }
        val result = host?.authenticate(title, BiometricPrompt.CryptoObject(sig)) ?: throw UserCancelled()
        DiagLog.event("PROMPT", "ok ($title)")
        return DeviceKeys.signPrepared(result.cryptoObject?.signature ?: sig, text)
    }

    fun openDevices() = run {
        devices = api.devices()
        push(Screen.Devices)
    }

    fun openBindDevice() {
        if (isDeviceBound) message = "Dieses Gerät ist bereits verknüpft. Um es neu zu verknüpfen, entferne zuerst die Verknüpfung in der Geräteverwaltung."
        else push(Screen.BindDevice)
    }

    fun removeDevice(d: BoundDevice) = run {
        val id = d.id ?: return@run
        api.deleteDevice(id)
        if (id == store.deviceId) {
            store.clearDevice()
            DeviceKeys.deleteAll()
        }
        devices = api.devices()
    }

    // ================================================================ TAN screen

    fun submitTan(purpose: TanPurpose, tan: String) = run {
        val t = tan.trim()
        if (t.isEmpty() || !t.all { it.isDigit() }) error("Die TAN besteht nur aus Ziffern.")
        when (purpose) {
            is TanPurpose.AddPhone -> {
                api.confirmMobileNumber(purpose.number, t)
                phoneNumber = purpose.number
                if (setupCard != null) resumeSetup()
                else done(
                    "Handynummer bestätigt",
                    "Deine Handynummer ${purpose.number} ist bestätigt." +
                        if (!isDeviceBound) " Als Nächstes kannst du dein Gerät verknüpfen (Karte einrichten)." else "",
                )
            }
            is TanPurpose.DeletePhone -> {
                api.confirmMobileNumberDeletion(MobileNumberDeleteConfirmBody(purpose.changeRequestId, tan = t))
                phoneNumber = null
                replace(Screen.PhoneEntry)
                message = "Die alte Nummer wurde entfernt. Gib jetzt deine neue Nummer ein."
            }
            is TanPurpose.BindDevice -> {
                finishDeviceBinding(purpose.challenge, t)
                if (setupCard != null) resumeSetup()
                else done("Gerät verknüpft", "Du hast dein Gerät erfolgreich verknüpft.")
            }
            is TanPurpose.Sca -> {
                api.scaConfirmTan(purpose.challenge.authenticateChangeRequestId, t)
                done("Online Zahlung bestätigt", "Zahlung bestätigt. Bitte kehre in den Webbrowser zurück.")
            }
        }
    }

    fun resendTan(purpose: TanPurpose) = run {
        when (purpose) {
            is TanPurpose.AddPhone -> api.addMobileNumber(purpose.number)
            is TanPurpose.DeletePhone -> {
                val r = api.deleteMobileNumber(purpose.number)
                replace(Screen.Tan(purpose.copy(changeRequestId = r.changeRequestId), "TAN erneut gesendet."))
            }
            is TanPurpose.BindDevice -> {
                // A new challenge needs a new key pair, so repeat POST /device.
                stack.removeAt(stack.lastIndex); bindDeviceAgain()
            }
            is TanPurpose.Sca -> api.scaAuthorizeForSms(purpose.challenge.authenticateChangeRequestId)
        }
        message = "TAN erneut gesendet."
    }

    private suspend fun bindDeviceAgain() {
        DeviceKeys.regenerate()
        val challenge = api.registerDevice(lastDeviceName, DeviceKeys.devicePublicKeyHex())
        push(Screen.Tan(TanPurpose.BindDevice(challenge), "Neue TAN an ${challenge.maskedMobileNumber ?: "deine Handynummer"} gesendet."))
    }

    // ================================================================ card actions

    fun openQuickAction(card: Card, type: String) {
        if (busy) return
        when (type) {
            "SETUP_CARD" -> startSetup(card)
            "BIND_DEVICE" -> openBindDevice()
            "ACTIVATE_CARD" -> requireBinding { push(Screen.ActivateCard(card)) }
            "ACTIVATE_CARD_NEW_PIN" -> requireBinding { push(Screen.Pin(card, PinMode.ACTIVATE_WITH_NEW_PIN)) }
            "CHANGE_PIN" -> requireBinding { push(Screen.Pin(card, PinMode.CHANGE)) }
            "BLOCK_CARD" -> push(Screen.BlockCard(card, block = true))
            "UNBLOCK_CARD" -> push(Screen.BlockCard(card, block = false))
            "CHANGE_REGION" -> push(Screen.Region(card))
            "SHOW_TRANSACTIONS" -> openTransactions(card)
        }
    }

    private fun requireBinding(next: () -> Unit) {
        if (isDeviceBound) next() else message = "Verknüpfe zuerst dein Gerät, um diese Aktion durchzuführen."
    }

    fun activateCard(card: Card) = run {
        api.activateCard(card.id)
        done("Karte aktiviert", "Du hast deine Karte erfolgreich aktiviert.")
    }

    fun setBlocked(card: Card, block: Boolean) = run {
        if (block) api.blockCard(card.id) else api.unblockCard(card.id)
        if (block) done("Karte gesperrt", "Du hast deine Karte erfolgreich gesperrt.")
        else done("Karte entsperrt", "Du hast deine Karte erfolgreich entsperrt.")
    }

    fun submitPin(card: Card, mode: PinMode, pin: String, repeat: String) = run {
        Validation.pin(pin, repeat)?.let { error(it) }
        val deviceId = store.deviceId ?: error("Gerät nicht verknüpft")
        val enc = PinCrypto.encrypt(api.pinKey(card.id), pin)
        val signature = signRestricted(enc.jwe, if (mode == PinMode.CHANGE) "PIN ändern" else "PIN setzen")
        if (mode == PinMode.ACTIVATE_WITH_NEW_PIN && card.id !in activatedCards) {
            api.activateCard(card.id)
            activatedCards += card.id // a retry after a failed PUT /pin must not activate again
        }
        api.setPin(card.id, PinBody(enc.jwe, enc.keyId, deviceId, signature))
        setupCard = null
        when (mode) {
            PinMode.CHANGE -> done("PIN geändert", "Deine neue PIN ist gesetzt.")
            PinMode.SETUP -> done("Karte eingerichtet", "Deine PIN ist gesetzt. Deine Karte ist einsatzbereit.")
            PinMode.ACTIVATE_WITH_NEW_PIN -> done("Karte aktiviert", "Du hast deine Karte erfolgreich aktiviert und eine neue PIN gesetzt.")
        }
    }

    fun setRegion(card: Card, zip: String) = run {
        val z = zip.trim()
        if (!Regex("^\\d{5}$").matches(z)) error("Bitte gib eine gültige Postleitzahl ein.")
        api.setZipCode(card.id, z)
        done("Wunschregion geändert", "Deine Wunschregion ist jetzt $z.")
    }

    fun openTransactions(card: Card) = run {
        balanceCents = null
        // The balance is not part of the transaction data; the original shows it from /home.
        balanceCents = runCatching { api.home() }
            .onFailure { DiagLog.error("balance", it) }
            .getOrNull()?.let { home ->
                val benefits = home.benefits
                (benefits.firstOrNull { it.detail?.cardId == card.id }
                    ?: benefits.singleOrNull { it.detail?.benefitType == card.benefitType })?.availableAmount
            }
        val t = api.transactions(card.id, FIRST_PAGE)
        transactions = (t.reservations.orEmpty() + t.transactions.orEmpty()).filterNotNull()
        nextTransactionPage = t.cardBookingsMeta?.nextPage
        push(Screen.Transactions(card))
    }

    fun loadMoreTransactions(card: Card) = run {
        val page = nextTransactionPage ?: return@run
        val t = api.transactions(card.id, page)
        transactions = transactions + t.transactions.orEmpty().filterNotNull()
        nextTransactionPage = t.cardBookingsMeta?.nextPage?.takeIf { it != page }
    }

    fun openStatements(card: Card) = run {
        statements = api.statements(card.id).sortedByDescending { it.start }
        push(Screen.Statements(card))
    }

    fun openStatement(card: Card, period: StatementPeriod) = run {
        val month = period.start.take(7)
        val bytes = api.statementPdf(card.id, month)
        val dir = File(getApplication<Application>().cacheDir, "statements").apply { mkdirs() }
        val file = File(dir, "Rechnungsabschluss_${card.id}_$month.pdf").apply { writeBytes(bytes) }
        host?.openPdf(file)
    }

    // ================================================================ payment approvals

    fun approveSca(c: ScaChallenge) = run {
        val deviceId = store.deviceId
        if (isDeviceBound && deviceId != null) {
            // The original shows no prompt here (REPORT §8.1); we ask for the screen lock first.
            host?.authenticate("Zahlung freigeben", null) ?: throw UserCancelled()
            val toSign = api.scaAuthorize(c.authenticateChangeRequestId).stringToSign
            api.scaConfirm(ScaConfirmationBody(c.authenticateChangeRequestId, deviceId, DeviceKeys.signWithDeviceKey(toSign)))
            done("Online Zahlung bestätigt", "Zahlung bestätigt. Bitte kehre in den Webbrowser zurück.")
        } else {
            api.scaAuthorizeForSms(c.authenticateChangeRequestId)
            push(Screen.Tan(TanPurpose.Sca(c), "Bitte gib den SMS Code ein, den wir dir gesendet haben."))
        }
    }

    fun declineSca(c: ScaChallenge) = run {
        api.scaDecline(c.challengeId)
        done("Zahlung abgelehnt", "Die Online-Zahlung wurde abgelehnt.")
    }

    fun respondFraud(fraudCaseId: String, legit: Boolean) = run {
        api.respondFraudCase(fraudCaseId, if (legit) "WHITELIST" else "CONFIRM")
        if (legit) done("Zahlung wurde freigegeben", "Die Zahlung wurde für die nächsten 10 Minuten freigegeben. Tätige die Zahlung am Kartenterminal erneut und beende deinen Einkauf.")
        else done("Deine Zahlung wurde abgelehnt", "Die Zahlung wurde abgelehnt und deine Karte gesperrt. Bitte wende dich an den Kundenservice.")
    }

    // ================================================================ notifications

    fun openNotifications() = run {
        notifications = api.notifications().notifications
        push(Screen.Notifications)
    }

    fun markRead(n: Notification) = run {
        if (n.status == "READ") return@run
        api.markNotificationRead(n.id)
        notifications = notifications.map { if (it.id == n.id) it.copy(status = "READ") else it }
    }

    private companion object {
        const val FIRST_PAGE = 1
    }
}
