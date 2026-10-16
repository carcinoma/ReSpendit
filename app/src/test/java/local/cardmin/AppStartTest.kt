// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Starts the real MainActivity in Robolectric, walks the logged-out screens, saves screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30, 34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppStartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val sdk = android.os.Build.VERSION.SDK_INT
        compose.waitForIdle()
        // FLAG_SECURE blocks window capture (by design), so draw the view hierarchy directly.
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "sdk${sdk}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderIcon() {
        val ctx = compose.activity
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Draw like a launcher would: background + foreground, clipped to a circle.
        val path = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
        c.clipPath(path)
        c.drawColor(ctx.getColor(R.color.ic_bg))
        ctx.getDrawable(R.drawable.ic_launcher_fg)!!.apply { setBounds(0, 0, size, size); draw(c) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "sdk${android.os.Build.VERSION.SDK_INT}_0_icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun startsOnLoginAndNavigates() {
        compose.onNodeWithText("Melde dich mit deiner E-Mail-Adresse an und gib dein Passwort ein.").assertExists()
        shot("1_login")

        compose.onNodeWithText("Registrierung mit Code").performClick()
        compose.onNodeWithText("Registrierungscode").assertExists()
        shot("2_register")
        compose.onNodeWithText("‹ Zurück").performClick()

        compose.onNodeWithText("Passwort zurücksetzen").performClick()
        compose.onNodeWithText("Link anfordern").assertExists()
        compose.onNodeWithText("‹ Zurück").performClick()

        compose.onNodeWithText("Über ReSpendit").performClick()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})").assertExists()
        shot("3_about")
        compose.onNodeWithText("Diagnose-Log").performClick()
        compose.onNodeWithText("Kopieren").assertExists()
        shot("4_diagnose_log")
        compose.onNodeWithText("‹ Zurück").performClick()
        compose.onNodeWithText("‹ Zurück").performClick()

        compose.onNodeWithText("Login").assertExists()
    }
}
