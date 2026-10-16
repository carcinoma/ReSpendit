// SPDX-License-Identifier: Apache-2.0
package local.cardmin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.cardmin.Config
import local.cardmin.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import local.cardmin.data.DiagLog
import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import local.cardmin.data.Card
import local.cardmin.data.FraudCase
import local.cardmin.data.Notification
import local.cardmin.data.ScaChallenge
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val screen = vm.screen
    // While a request runs, swallow back presses so its result lands on the screen that started it.
    BackHandler(enabled = vm.busy || vm.canGoBack) { vm.back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleOf(screen)) },
                navigationIcon = {
                    if (vm.canGoBack) TextButton(onClick = vm::back) { Text("‹ Zurück") }
                },
                actions = { if (screen is Screen.Home) HomeMenu(vm) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.Loading -> {}
                    Screen.Login -> LoginScreen(vm)
                    Screen.Register -> RegisterCodeScreen(vm)
                    is Screen.RegisterAccount -> RegisterAccountScreen(vm, screen.registrationToken)
                    Screen.ActivateAccount -> SingleField(
                        "Füge den Link aus der Aktivierungs-E-Mail (oder den Token) ein.", "Link / Token", "Account aktivieren",
                    ) { vm.activateAccount(it) }
                    Screen.ForgotPassword -> SingleField(
                        "Gib deine E-Mail-Adresse ein. Wir senden dir einen Link zum Zurücksetzen.", "E-Mail Adresse", "Link anfordern",
                        KeyboardType.Email,
                    ) { vm.requestPasswordReset(it) }
                    Screen.ResetPassword -> ResetPasswordScreen(vm)
                    is Screen.ChooseCompany -> Form {
                        Text("Wähle das Unternehmen, mit dem du fortfahren möchtest.")
                        screen.companies.forEach { c ->
                            OutlinedButton(onClick = { vm.selectCompany(c) }, Modifier.fillMaxWidth()) {
                                Text(c.companyName ?: "Unternehmen ${c.companyId}")
                            }
                        }
                    }
                    Screen.Home -> HomeScreen(vm)
                    Screen.PhoneEntry -> SingleField(
                        "Gib deine Handynummer ein. Du erhältst eine SMS-TAN zur Bestätigung.", "Handynummer (z.B. +49 170 1234567)",
                        "Weiter", KeyboardType.Phone,
                    ) { vm.submitPhone(it) }
                    Screen.PhoneManage -> PhoneManageScreen(vm)
                    is Screen.Tan -> TanScreen(vm, screen)
                    Screen.BindDevice -> BindDeviceScreen(vm)
                    is Screen.Pin -> PinScreen(vm, screen)
                    is Screen.BlockCard -> Confirm(
                        if (screen.block) "Fahre fort, um deine Karte sofort zu sperren. Du kannst sie bis zur Bestellung einer Ersatzkarte jederzeit entsperren."
                        else "Fahre fort, wenn du deine Karte entsperren willst.",
                        if (screen.block) "Karte sperren" else "Karte entsperren",
                    ) { vm.setBlocked(screen.card, screen.block) }
                    is Screen.ActivateCard -> Confirm(
                        "Aktiviere deine Karte erst, wenn du sie erhalten hast. Eine bisherige Karte wird damit inaktiv.",
                        "Karte aktivieren",
                    ) { vm.activateCard(screen.card) }
                    is Screen.Region -> SingleField(
                        "Bitte wähle deine Wunschregion mit Hilfe einer Postleitzahl aus, in der du deine SpenditCard benutzen willst." +
                            (screen.card.details?.zipCode?.let { "\nAktuell: $it" } ?: ""),
                        "Postleitzahl", "Weiter", KeyboardType.Number,
                    ) { vm.setRegion(screen.card, it) }
                    is Screen.Transactions -> TransactionsScreen(vm, screen.card)
                    is Screen.Statements -> StatementsScreen(vm, screen.card)
                    Screen.Devices -> DevicesScreen(vm)
                    Screen.Notifications -> NotificationsScreen(vm)
                    Screen.About -> AboutScreen(vm)
                    Screen.Diagnostics -> DiagnosticsScreen()
                    is Screen.Done -> Form {
                        Text(screen.title, style = MaterialTheme.typography.headlineSmall)
                        Text(screen.text)
                        Button(onClick = vm::closeDone, Modifier.fillMaxWidth()) { Text("Fertig") }
                    }
                }
            }
        }
    }
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

private fun titleOf(s: Screen) = when (s) {
    Screen.Loading, Screen.Login -> "ReSpendit"
    Screen.Register, is Screen.RegisterAccount -> "Registrierung"
    Screen.ActivateAccount -> "Account aktivieren"
    Screen.ForgotPassword, Screen.ResetPassword -> "Passwort zurücksetzen"
    is Screen.ChooseCompany -> "Unternehmen wählen"
    Screen.Home -> "Meine Karten"
    Screen.PhoneEntry, Screen.PhoneManage -> "Handynummer"
    is Screen.Tan -> "TAN eingeben"
    Screen.BindDevice -> "Gerät verknüpfen"
    is Screen.Pin -> if (s.mode == PinMode.CHANGE) "PIN ändern" else "Neue PIN setzen"
    is Screen.BlockCard -> if (s.block) "Karte sperren" else "Karte entsperren"
    is Screen.ActivateCard -> "Karte aktivieren"
    is Screen.Region -> "Wunschregion ändern"
    is Screen.Transactions -> "Umsätze"
    is Screen.Statements -> "Rechnungsabschlüsse"
    Screen.Devices -> "Geräteverwaltung"
    Screen.Notifications -> "Mitteilungen"
    is Screen.Done -> ""
    Screen.About -> "Über ReSpendit"
    Screen.Diagnostics -> "Diagnose-Log"
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Form(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String,
    type: KeyboardType = KeyboardType.Text, secret: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SingleField(
    info: String, label: String, button: String, type: KeyboardType = KeyboardType.Text, initial: String = "",
    onSubmit: (String) -> Unit,
) {
    var v by rememberSaveable { mutableStateOf(initial) }
    Form {
        Text(info)
        Field(v, { v = it }, label, type)
        Button(onClick = { onSubmit(v) }, Modifier.fillMaxWidth(), enabled = v.isNotBlank()) { Text(button) }
    }
}

@Composable
private fun Confirm(info: String, button: String, onConfirm: () -> Unit) = Form {
    Text(info)
    Button(onClick = onConfirm, Modifier.fillMaxWidth()) { Text(button) }
}

private val euro = NumberFormat.getCurrencyInstance(Locale.GERMANY)
private fun cents(v: Long) = euro.format(v / 100.0)
private fun date(iso: String?) = iso?.take(16)?.replace('T', ' ') ?: ""

// ---------------------------------------------------------------- login & registration

@Composable
private fun LoginScreen(vm: AppViewModel) {
    var email by rememberSaveable { mutableStateOf(vm.store.email ?: "") }
    var pw by remember { mutableStateOf("") }
    Form {
        Image(
            painter = painterResource(R.drawable.logo_full),
            contentDescription = "ReSpendit",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp).height(64.dp),
        )
        Text("Melde dich mit deiner E-Mail-Adresse an und gib dein Passwort ein.")
        Field(email, { email = it }, "E-Mail Adresse", KeyboardType.Email)
        Field(pw, { pw = it }, "Passwort", KeyboardType.Password, secret = true)
        Button(onClick = { vm.login(email, pw) }, Modifier.fillMaxWidth(), enabled = email.contains('@') && pw.isNotEmpty()) {
            Text("Login")
        }
        HorizontalDivider()
        TextButton(onClick = vm::openForgotPassword) { Text("Passwort zurücksetzen") }
        TextButton(onClick = vm::openResetPassword) { Text("Ich habe einen Link zum Zurücksetzen") }
        TextButton(onClick = vm::openRegister) { Text("Registrierung mit Code") }
        TextButton(onClick = vm::openActivateAccount) { Text("Account aktivieren") }
        TextButton(onClick = vm::openAbout) { Text("Über ReSpendit") }
    }
}

@Composable
private fun RegisterCodeScreen(vm: AppViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Form {
        Text("Gib deinen Nachnamen und den Registrierungscode deines Arbeitgebers ein.")
        Field(name, { name = it }, "Nachname")
        Field(code, { code = it }, "Registrierungscode")
        Button(onClick = { vm.submitRegistrationCode(name, code) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && code.isNotBlank()) {
            Text("Weiter")
        }
    }
}

@Composable
private fun RegisterAccountScreen(vm: AppViewModel, token: String) {
    var email by rememberSaveable { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    Form {
        Text("Account erstellen")
        Field(email, { email = it }, "E-Mail Adresse", KeyboardType.Email)
        Field(pw, { pw = it }, "Passwort", KeyboardType.Password, secret = true)
        Field(pw2, { pw2 = it }, "Passwort wiederholen", KeyboardType.Password, secret = true)
        Button(onClick = { vm.register(token, email, pw, pw2) }, Modifier.fillMaxWidth(), enabled = email.contains('@')) {
            Text("Account erstellen")
        }
    }
}

@Composable
private fun ResetPasswordScreen(vm: AppViewModel) {
    var link by rememberSaveable { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    Form {
        Text("Füge den Link aus der E-Mail ein und vergib ein neues Passwort.")
        Field(link, { link = it }, "Link / Token")
        Field(pw, { pw = it }, "Neues Passwort", KeyboardType.Password, secret = true)
        Field(pw2, { pw2 = it }, "Neues Passwort wiederholen", KeyboardType.Password, secret = true)
        Button(onClick = { vm.resetPassword(link, pw, pw2) }, Modifier.fillMaxWidth(), enabled = link.isNotBlank()) {
            Text("Passwort speichern")
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = vm::refreshHome) { Text("↻") }
    TextButton(onClick = { open = true }) { Text("☰") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        listOf(
            "Mitteilungen" to vm::openNotifications,
            "Geräteverwaltung" to vm::openDevices,
            "Handynummer" to vm::openPhone,
            "Über" to vm::openAbout,
            "Abmelden" to vm::logout,
        ).forEach { (label, action) ->
            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel) {
    val p = vm.pending
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(p.scaChallenges, key = { "sca" + it.challengeId }) { ScaCard(vm, it) }
        items(p.fraudCases, key = { "fraud" + it.fraudCaseId }) { FraudCard(vm, it) }
        if (!vm.isDeviceBound) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dieses Gerät ist nicht verknüpft.", fontWeight = FontWeight.Bold)
                    Text("Ohne Geräteverknüpfung kannst du keine PIN setzen und Zahlungen nur per SMS-TAN freigeben.")
                    OutlinedButton(onClick = vm::openBindDevice) { Text("Gerät verknüpfen") }
                }
            }
        }
        items(vm.cards, key = { "card" + it.id }) { CardItem(vm, it) }
        if (vm.cards.isEmpty() && !vm.busy) item { Text("Keine Karten gefunden. Tippe auf ↻ zum Aktualisieren.") }
    }
}

@Composable
private fun ScaCard(vm: AppViewModel, c: ScaChallenge) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zahlung freigeben", style = MaterialTheme.typography.titleMedium)
            Text("Händler: ${c.merchantName ?: "–"}")
            Text("Betrag: ${cents(c.amountCents)}")
            Text("Datum: ${date(c.challengedAt)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.approveSca(c) }) { Text("Freigeben") }
                OutlinedButton(onClick = { vm.declineSca(c) }) { Text("Ablehnen") }
            }
        }
    }
}

@Composable
private fun FraudCard(vm: AppViewModel, f: FraudCase) {
    var confirmFraud by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wir haben eine fragwürdige Zahlung entdeckt", style = MaterialTheme.typography.titleMedium)
            Text("${f.merchantName ?: "–"} · ${cents(f.amountCents)}")
            Text(
                "Diese Zahlung wurde aus Sicherheitsgründen der Solaris SE geblockt. Falls du diese Transaktion getätigt hast, " +
                    "gib deine Zahlung frei, ansonsten wird deine Karte aus Sicherheitsgründen gesperrt. Bitte antworte bis: ${date(f.respondUntil)}"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.respondFraud(f.fraudCaseId, legit = true) }) { Text("Zahlung freigeben") }
                OutlinedButton(onClick = { confirmFraud = true }) { Text("Zahlung ablehnen") }
            }
        }
    }
    if (confirmFraud) AlertDialog(
        onDismissRequest = { confirmFraud = false },
        text = { Text("Die Zahlung wird als Betrug gemeldet und deine Karte gesperrt. Fortfahren?") },
        confirmButton = { TextButton(onClick = { confirmFraud = false; vm.respondFraud(f.fraudCaseId, legit = false) }) { Text("Ablehnen und sperren") } },
        dismissButton = { TextButton(onClick = { confirmFraud = false }) { Text("Abbrechen") } },
    )
}

private val actionLabels = linkedMapOf(
    "SETUP_CARD" to "Karte einrichten",
    "ACTIVATE_CARD" to "Karte aktivieren",
    "ACTIVATE_CARD_NEW_PIN" to "Neue Karte aktivieren",
    "CHANGE_PIN" to "PIN ändern",
    "BLOCK_CARD" to "Karte sperren",
    "UNBLOCK_CARD" to "Karte entsperren",
    "CHANGE_REGION" to "Wunsch-Region ändern",
    "SHOW_TRANSACTIONS" to "Umsätze einsehen",
    "BIND_DEVICE" to "Gerät verknüpfen",
)

private fun cardName(c: Card) = when {
    c.benefitType.startsWith("MOBILITY") -> "Mobility Karte"
    c.benefitType == "SPENDITCARD" -> "SpenditCard"
    else -> "Karte"
}

private fun statusText(s: String) = when (s) {
    "ACTIVE" -> "Aktiv"
    "BLOCKED" -> "Gesperrt"
    "INACTIVE" -> "Inaktiv"
    "NOT_DELIVERED" -> "Noch nicht zugestellt"
    "CARD_SETUP_REQUIRED" -> "Einrichtung erforderlich"
    else -> s.ifEmpty { "Unbekannt" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardItem(vm: AppViewModel, c: Card) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(cardName(c), style = MaterialTheme.typography.titleLarge)
            Text("Status: ${statusText(c.status)}", fontWeight = FontWeight.Bold)
            Text("Karteninhaber: ${c.line1 ?: "–"}")
            Text("Kartennummer: ${c.maskedPan ?: "–"}")
            Text("Ablaufdatum: ${c.formattedExpirationDate ?: "–"}")
            c.details?.zipCode?.let { Text("Wunschregion (PLZ): $it") }
            when {
                c.status == "NOT_DELIVERED" ->
                    Text("Um deine Karte einrichten zu können, muss dein Arbeitgeber die Zustellung deiner Karte bestätigen.")
                !c.isSetupCompleted -> Button(onClick = { vm.startSetup(c) }) { Text("Karte einrichten") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.quickActions.map { it.quickActionType }.distinct()
                    .filter { it in actionLabels && !(it == "SETUP_CARD" && !c.isSetupCompleted) }
                    .forEach { t -> OutlinedButton(onClick = { vm.openQuickAction(c, t) }) { Text(actionLabels.getValue(t)) } }
                OutlinedButton(onClick = { vm.openStatements(c) }) { Text("Rechnungsabschlüsse") }
            }
        }
    }
}

// ---------------------------------------------------------------- setup flows

@Composable
private fun TanScreen(vm: AppViewModel, s: Screen.Tan) {
    var tan by remember(s) { mutableStateOf("") }
    var resends by remember(s) { mutableIntStateOf(0) }
    var seconds by remember(s) { mutableIntStateOf(Config.TAN_RESEND_SECONDS) }
    LaunchedEffect(s, resends) {
        seconds = Config.TAN_RESEND_SECONDS
        while (seconds > 0) { delay(1000); seconds-- }
    }
    Form {
        Text(s.info)
        Field(tan, { tan = it.filter(Char::isDigit) }, "TAN", KeyboardType.NumberPassword)
        Button(onClick = { vm.submitTan(s.purpose, tan) }, Modifier.fillMaxWidth(), enabled = tan.isNotEmpty()) { Text("Bestätigen") }
        TextButton(onClick = { if (!vm.busy) { vm.resendTan(s.purpose); resends++ } }, enabled = seconds == 0 && !vm.busy) {
            Text(if (seconds > 0) "TAN erneut senden in $seconds Sekunden" else "TAN erneut senden")
        }
    }
}

@Composable
private fun BindDeviceScreen(vm: AppViewModel) {
    var name by rememberSaveable { mutableStateOf(vm.deviceName) }
    Form {
        Text("Bitte wähle einen Namen für das Gerät, mit dem du deine Karte verknüpfen möchtest. Der Name wird an spendit übertragen.")
        Text("Hinweis: Diese App erzeugt eigene Schlüssel. Sie zählt als zusätzliches Gerät (maximal ${Config.MAX_BOUND_DEVICES}).")
        Field(name, { name = it }, "Gerätename")
        Button(onClick = { vm.bindDevice(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) { Text("Weiter") }
    }
}

@Composable
private fun PinScreen(vm: AppViewModel, s: Screen.Pin) {
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    Form {
        Text(
            if (s.mode == PinMode.CHANGE) "Gib deine neue PIN ein. Zur Bestätigung wird deine Displaysperre oder Biometrie abgefragt."
            else "Gib deine gewünschte PIN ein. Zur Bestätigung wird deine Displaysperre oder Biometrie abgefragt."
        )
        Field(pin, { pin = it.filter(Char::isDigit).take(4) }, "PIN", KeyboardType.NumberPassword, secret = true)
        Field(pin2, { pin2 = it.filter(Char::isDigit).take(4) }, "PIN wiederholen", KeyboardType.NumberPassword, secret = true)
        Button(onClick = { vm.submitPin(s.card, s.mode, pin, pin2) }, Modifier.fillMaxWidth(), enabled = pin.length == 4) {
            Text("PIN setzen")
        }
    }
}

@Composable
private fun PhoneManageScreen(vm: AppViewModel) = Form {
    val number = vm.phoneNumber
    if (number == null) {
        Text("Es ist keine bestätigte Handynummer hinterlegt.")
        Button(onClick = vm::removePhone, Modifier.fillMaxWidth()) { Text("Handynummer hinzufügen") }
    } else {
        Text("Hinterlegte Nummer: $number")
        Text(
            "Zum Ändern wird zuerst die bisherige Nummer entfernt" +
                (if (vm.isDeviceBound) " (Bestätigung per Displaysperre)." else " (TAN an die bisherige Nummer).") +
                " Danach bestätigst du die neue Nummer per SMS-TAN."
        )
        Button(onClick = vm::removePhone, Modifier.fillMaxWidth()) { Text("Handynummer ändern") }
    }
}

// ---------------------------------------------------------------- lists

@Composable
private fun TransactionsScreen(vm: AppViewModel, card: Card) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Verfügbares Guthaben", style = MaterialTheme.typography.labelLarge)
                    Text(
                        vm.balanceCents?.let(::cents) ?: "nicht verfügbar",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (vm.transactions.isEmpty()) item { Text("Keine Umsätze vorhanden.") }
        items(vm.transactions) { t ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t.recipientName ?: t.senderName ?: t.description ?: "–", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(t.amount?.let { cents(it.value) } ?: "")
                }
                Text(
                    date(t.date) + (if (t.isReservation == true) " · vorgemerkt" else "") +
                        (t.declineReason?.takeIf { it.isNotEmpty() && it != "NOT_DECLINED" }?.let { " · abgelehnt ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        if (vm.nextTransactionPage != null) item {
            OutlinedButton(onClick = { vm.loadMoreTransactions(card) }) { Text("Mehr laden") }
        }
    }
}

@Composable
private fun StatementsScreen(vm: AppViewModel, card: Card) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.statements.isEmpty()) item { Text("Bisher keine Rechnungsabschlüsse.") }
        items(vm.statements) { p ->
            OutlinedButton(onClick = { vm.openStatement(card, p) }, Modifier.fillMaxWidth()) {
                Text("Rechnungsabschluss ${p.start.take(7)}")
            }
        }
    }
}

@Composable
private fun DevicesScreen(vm: AppViewModel) {
    var toRemove by remember { mutableStateOf<local.cardmin.data.BoundDevice?>(null) }
    val thisId = vm.store.deviceId
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(if (vm.isDeviceBound) "Dieses Gerät ist verknüpft." else "Dieses Gerät ist nicht verknüpft.")
        }
        item { Text("Liste der verknüpften Geräte:", fontWeight = FontWeight.Bold) }
        items(vm.devices) { d ->
            Row(Modifier.fillMaxWidth().clickable { toRemove = d }.padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text((d.name ?: d.id ?: "–") + if (d.id == thisId) " (dieses Gerät)" else "")
                    d.createdAt?.let { Text("seit ${date(it)}", style = MaterialTheme.typography.bodySmall) }
                }
                Text("Entfernen", color = MaterialTheme.colorScheme.error)
            }
        }
        if (!vm.isDeviceBound) item {
            Button(onClick = vm::openBindDevice) { Text("Verknüpfe dein Gerät") }
        }
    }
    toRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Verknüpfung aufheben") },
            text = {
                Text(
                    if (d.id == thisId) "Mit dem Entfernen deiner Geräteverknüpfung wirst du nicht mehr deinen PIN oder deine Telefonnummer ändern können."
                    else "Willst du die Verknüpfung des ausgewählten Gerätes wirklich aufheben?"
                )
            },
            confirmButton = { TextButton(onClick = { toRemove = null; vm.removeDevice(d) }) { Text("Verknüpfung aufheben") } },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun NotificationsScreen(vm: AppViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.notifications.isEmpty()) item { Text("Keine Benachrichtigungen.") }
        items(vm.notifications, key = { it.id }) { n ->
            Column(Modifier.fillMaxWidth().clickable { vm.markRead(n) }) {
                Text(notificationTitle(n), fontWeight = if (n.status == "NEW") FontWeight.Bold else FontWeight.Normal)
                Text(notificationText(n.detail), style = MaterialTheme.typography.bodyMedium)
                Text(date(n.timestamp), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun JsonObject.str(key: String) = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
private fun JsonObject.textValue(key: String) = runCatching { (this[key] as? JsonObject)?.str("value") }.getOrNull()

private fun notificationTitle(n: Notification) = when (n.detail?.str("type")) {
    "DECLINED_TRANSACTION" -> "Zahlung abgelehnt"
    "TOP_UP_TRANSACTION" -> "Zahlung erhalten"
    "MERCHANT_CONTROL_RESULT" -> "Händlerprüfung"
    "BENEFIT_ADDED" -> "Benefit hinzugefügt"
    "BENEFIT_REMOVED" -> "Benefit entfernt"
    "BACKEND" -> n.detail.textValue("title") ?: "Mitteilung"
    else -> "Mitteilung"
}

private fun notificationText(d: JsonObject?): String {
    d ?: return ""
    val amount = d.str("amount")?.toLongOrNull()?.let(::cents)
    return when (d.str("type")) {
        "DECLINED_TRANSACTION" -> listOfNotNull(d.str("merchantName"), amount, d.str("declineReason")?.let { "Grund: $it" }).joinToString(" · ")
        "TOP_UP_TRANSACTION" -> amount ?: ""
        "MERCHANT_CONTROL_RESULT" -> listOfNotNull(d.str("merchantName"), d.str("resultType")).joinToString(" · ")
        "BACKEND" -> d.textValue("text") ?: ""
        else -> d.str("benefitType") ?: ""
    }
}

// ---------------------------------------------------------------- diagnostics

@Composable
private fun AboutScreen(vm: AppViewModel) = Form {
    Image(
        painter = painterResource(R.drawable.logo_full),
        contentDescription = "ReSpendit",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp).height(64.dp),
    )
    Text("ReSpendit", style = MaterialTheme.typography.headlineSmall)
    Text("Version ${local.cardmin.BuildConfig.VERSION_NAME} (Build ${local.cardmin.BuildConfig.VERSION_CODE})")
    Text(
        "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Schlanker Client für die SpenditCard: Karte, Geräteverknüpfung und Zahlungsfreigaben – ohne Tracking.")
    Text("Lizenz: Apache License 2.0", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Bei Problemen hilft das Diagnose-Log: Vorgang wiederholen, dann das Log kopieren oder teilen.")
    OutlinedButton(onClick = vm::openDiagnostics, Modifier.fillMaxWidth()) { Text("Diagnose-Log") }
}

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(DiagLog.read()) }
    val full = DiagLog.header() + text
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Protokoll der Anfragen an spendit und der Fehler. Passwörter, TANs, PINs, Tokens und Signaturen sind " +
                "durch Platzhalter wie <str 6 digits> ersetzt. Zum Melden eines Problems: kopieren oder teilen.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(full)) }) { Text("Kopieren") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, full)
                context.startActivity(Intent.createChooser(send, "Diagnose-Log teilen"))
            }) { Text("Teilen") }
            OutlinedButton(onClick = { DiagLog.clear(); text = DiagLog.read() }) { Text("Löschen") }
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
            Text(text.ifEmpty { "(leer)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
