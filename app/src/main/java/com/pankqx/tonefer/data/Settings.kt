package com.pankqx.tonefer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class TextSize(val label: String, val scale: Float) {
    SMALL("Small", 0.9f), MEDIUM("Medium", 1f), LARGE("Large", 1.15f)
}

enum class Typeface(val label: String) { MONO("Mono"), SANS("Sans") }

enum class Backdrop(val label: String) { DOTS("Dotted"), PLAIN("Plain") }

enum class WindowStyle(val label: String) { CLASSIC("Classic"), FLAT("Flat") }

enum class ImageQuality(val label: String, val maxSide: Int?) {
    ORIGINAL("Original", null), HIGH("High", 2560), BALANCED("Balanced", 1600)
}

/** Everything the user can customise, plus the pairing with their laptop. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textSize: TextSize = TextSize.MEDIUM,
    val typeface: Typeface = Typeface.MONO,
    val backdrop: Backdrop = Backdrop.DOTS,
    val windowStyle: WindowStyle = WindowStyle.CLASSIC,
    val autoSavePhotos: Boolean = true,
    val autoSaveFiles: Boolean = false,
    val autoCopyText: Boolean = false,
    val imageQuality: ImageQuality = ImageQuality.ORIGINAL,
    val notify: Boolean = true,
    val vibrate: Boolean = true,
    val background: Boolean = true,
    val pairing: Pairing? = null,
)

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "tonefer")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val ds = context.store

    private object K {
        val theme = stringPreferencesKey("theme")
        val textSize = stringPreferencesKey("text_size")
        val typeface = stringPreferencesKey("typeface")
        val backdrop = stringPreferencesKey("backdrop")
        val windowStyle = stringPreferencesKey("window_style")
        val autoSavePhotos = booleanPreferencesKey("auto_save_photos")
        val autoSaveFiles = booleanPreferencesKey("auto_save_files")
        val autoCopyText = booleanPreferencesKey("auto_copy_text")
        val imageQuality = stringPreferencesKey("image_quality")
        val notify = booleanPreferencesKey("notify")
        val vibrate = booleanPreferencesKey("vibrate")
        val background = booleanPreferencesKey("background")
        val key = stringPreferencesKey("pair_key")
        val host = stringPreferencesKey("pair_host")
        val port = intPreferencesKey("pair_port")
        val laptop = stringPreferencesKey("pair_laptop")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        val key = p[K.key]
        val host = p[K.host]
        return AppSettings(
            theme = p.enum(K.theme, d.theme),
            textSize = p.enum(K.textSize, d.textSize),
            typeface = p.enum(K.typeface, d.typeface),
            backdrop = p.enum(K.backdrop, d.backdrop),
            windowStyle = p.enum(K.windowStyle, d.windowStyle),
            autoSavePhotos = p[K.autoSavePhotos] ?: d.autoSavePhotos,
            autoSaveFiles = p[K.autoSaveFiles] ?: d.autoSaveFiles,
            autoCopyText = p[K.autoCopyText] ?: d.autoCopyText,
            imageQuality = p.enum(K.imageQuality, d.imageQuality),
            notify = p[K.notify] ?: d.notify,
            vibrate = p[K.vibrate] ?: d.vibrate,
            background = p[K.background] ?: d.background,
            pairing = if (key != null && host != null)
                Pairing.fromStored(key, host, p[K.port] ?: Pairing.DEFAULT_PORT, p[K.laptop] ?: "")
            else null,
        )
    }

    /** Loaded synchronously once at start so the first frame already knows the theme and pairing. */
    val state: StateFlow<AppSettings> = ds.data.map(::read).stateIn(
        scope, SharingStarted.Eagerly, runBlocking { read(ds.data.first()) }
    )

    val current: AppSettings get() = state.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        ds.edit { p ->
            val s = transform(read(p))
            p[K.theme] = s.theme.name
            p[K.textSize] = s.textSize.name
            p[K.typeface] = s.typeface.name
            p[K.backdrop] = s.backdrop.name
            p[K.windowStyle] = s.windowStyle.name
            p[K.autoSavePhotos] = s.autoSavePhotos
            p[K.autoSaveFiles] = s.autoSaveFiles
            p[K.autoCopyText] = s.autoCopyText
            p[K.imageQuality] = s.imageQuality.name
            p[K.notify] = s.notify
            p[K.vibrate] = s.vibrate
            p[K.background] = s.background
            val pairing = s.pairing
            if (pairing == null) {
                p.remove(K.key); p.remove(K.host); p.remove(K.port); p.remove(K.laptop)
            } else {
                p[K.key] = pairing.keyB64
                p[K.host] = pairing.host
                p[K.port] = pairing.port
                p[K.laptop] = pairing.laptop
            }
        }
    }

    suspend fun resetAppearance() = update {
        val d = AppSettings()
        it.copy(theme = d.theme, textSize = d.textSize, typeface = d.typeface,
            backdrop = d.backdrop, windowStyle = d.windowStyle)
    }
}
