// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.withResumed
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import local.cardmin.ui.App
import local.cardmin.ui.AppViewModel
import local.cardmin.ui.Host
import java.io.File
import kotlin.coroutines.resume

class MainActivity : FragmentActivity(), Host {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Card data and TANs on screen: keep them out of screenshots and the recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        vm.host = this
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                App(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.store.sessionToken != null && vm.store.appUserId != null) {
            vm.refreshHome()
            vm.startPolling()
        }
    }

    override fun onPause() {
        vm.stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        if (vm.host === this) vm.host = null
        super.onDestroy()
    }

    override suspend fun authenticate(
        title: String,
        crypto: BiometricPrompt.CryptoObject?,
    ): BiometricPrompt.AuthenticationResult? {
        // BiometricPrompt silently does nothing once the fragment state is saved, which would leave
        // the caller waiting forever. Wait until the app is in the foreground again, and give up
        // after a while.
        lifecycle.withResumed {}
        if (supportFragmentManager.isStateSaved) return null
        return withTimeoutOrNull(PROMPT_TIMEOUT_MS) { showPrompt(title, crypto) }
    }

    private suspend fun showPrompt(
        title: String,
        crypto: BiometricPrompt.CryptoObject?,
    ): BiometricPrompt.AuthenticationResult? = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_LONG).show()
                }
                if (cont.isActive) cont.resume(null)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Bestätige mit Biometrie oder deiner Displaysperre")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    override fun openPdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Keine App zum Anzeigen von PDFs installiert.", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 120_000L
    }
}
