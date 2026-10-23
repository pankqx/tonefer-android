package com.pankqx.tonefer.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android does not let ordinary apps switch the Wi‑Fi hotspot on (blocked since Android 8),
 * so Tonefer opens the hotspot switch directly instead: one tap for the user.
 */
object Hotspot {
    private val screens = listOf(
        ComponentName("com.android.settings", "com.android.settings.TetherSettings"),
        ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
        ComponentName("com.android.settings", "com.android.settings.Settings\$WifiTetherSettingsActivity"),
    )

    fun intent(): Intent = Intent(Intent.ACTION_MAIN).setComponent(screens.first()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the hotspot settings screen. Returns false if the device hides it. */
    fun open(context: Context): Boolean {
        for (c in screens) {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { }
        }
        return try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }
}
