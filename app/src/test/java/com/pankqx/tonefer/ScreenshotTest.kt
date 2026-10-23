package com.pankqx.tonefer

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.pankqx.tonefer.data.AppSettings
import com.pankqx.tonefer.data.Item
import com.pankqx.tonefer.data.Pairing
import com.pankqx.tonefer.data.ThemeMode
import com.pankqx.tonefer.data.Typeface
import com.pankqx.tonefer.data.WindowStyle
import com.pankqx.tonefer.net.Conn
import com.pankqx.tonefer.ui.screens.AboutScreen
import com.pankqx.tonefer.ui.screens.HomeScreen
import com.pankqx.tonefer.ui.screens.InstructionsScreen
import com.pankqx.tonefer.ui.screens.LoadingScreen
import com.pankqx.tonefer.ui.screens.PairScreen
import com.pankqx.tonefer.ui.screens.SettingsScreen
import com.pankqx.tonefer.ui.theme.ToneferTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen to app/screenshots/ so the UI can be reviewed without a device. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<ToneferApp>()

    private val sample = run {
        val now = System.currentTimeMillis()
        listOf(
            Item("a1", "text", "laptop", now - 3_600_000, text = "Slides for tomorrow: https://github.com/pankqx/tonefer"),
            Item("a2", "text", "phone", now - 3_000_000, text = "Pick up chai on the way back"),
            Item("a3", "file", "laptop", now - 2_000_000, name = "MCA-project-report.pdf", size = 2_480_000, mime = "application/pdf"),
            Item("a4", "file", "phone", now - 600_000, name = "IMG_2041.jpg", size = 3_100_000, mime = "image/jpeg"),
            Item("a5", "text", "phone", now - 60_000, text = "Sent the photo — check your Tonefer folder"),
        )
    }

    private fun pair() = runBlocking {
        app.settings.update { it.copy(pairing = Pairing.parse("http://10.42.0.15:8765/#k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")!!.copy(laptop = "dell-g15")) }
    }

    private fun shot(name: String, settings: AppSettings = app.settings.current, content: @Composable () -> Unit) {
        compose.setContent { ToneferTheme(settings) { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun pairing() = shot("01-pair") { PairScreen(app, onHelp = {}) }

    @Test fun home() {
        pair()
        app.client.preview(sample, Conn.Online("10.42.0.15", "dell-g15"))
        shot("02-home") { HomeScreen(app, {}, {}, {}) }
    }

    @Test fun homeDark() {
        pair()
        app.client.preview(sample, Conn.Online("10.42.0.15", "dell-g15"))
        shot("03-home-dark", app.settings.current.copy(theme = ThemeMode.DARK)) { HomeScreen(app, {}, {}, {}) }
    }

    @Test fun homeOffline() {
        pair()
        app.client.preview(emptyList(), Conn.Offline("Laptop not found"))
        shot("04-home-offline") { HomeScreen(app, {}, {}, {}) }
    }

    @Test fun homeClassicSans() {
        pair()
        app.client.preview(sample, Conn.Online("10.42.0.15", "dell-g15"))
        shot("05-home-classic-sans", app.settings.current.copy(windowStyle = WindowStyle.CLASSIC, typeface = Typeface.SANS)) { HomeScreen(app, {}, {}, {}) }
    }

    @Test fun settings() {
        pair()
        app.client.preview(sample, Conn.Online("10.42.0.15", "dell-g15"))
        shot("06-settings") { SettingsScreen(app, {}, {}) }
    }

    @Test fun instructions() = shot("07-instructions") { InstructionsScreen {} }

    @Test fun about() = shot("08-about") { AboutScreen {} }

    @Test fun loading() = shot("00-loading") { LoadingScreen(animate = false) }

    @Test fun homeQueued() {
        pair()
        app.client.preview(emptyList(), Conn.Searching)
        shot("09-home-searching-dark", app.settings.current.copy(theme = ThemeMode.DARK)) { HomeScreen(app, {}, {}, {}) }
    }
}
