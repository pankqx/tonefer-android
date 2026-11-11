package com.pankqx.tonefer.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pankqx.tonefer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One-tap updates. Checks GitHub for a newer release, downloads the APK and hands it to
 * Android's installer, which installs it over the current app (settings and pairing are kept).
 *
 * This is the only part of Tonefer that uses the internet. Nothing about you or your transfers
 * is sent: it is a plain download of the public release.
 */
class Updater(private val context: Context) {

    data class State(
        val latest: String? = null,
        val available: Boolean = false,
        val checking: Boolean = false,
        val progress: Float? = null,      // 0..1 while downloading
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val current: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Tonefer-updater").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }

    /** Looks for a newer release. Returns false when GitHub can't be reached. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(checking = true, message = null)
        val latest = get(API)?.let { runCatching { JSONObject(it).optString("tag_name") }.getOrNull() }
            ?.trimStart('v', 'V')?.takeIf { it.isNotBlank() }
            ?: get(RAW)?.trim()?.takeIf { it.isNotBlank() && it.length < 20 }
        _state.value = if (latest == null) _state.value.copy(checking = false, message = "Can't reach GitHub. Check your internet.")
        else State(latest = latest, available = newer(latest, current),
            message = if (newer(latest, current)) null else "You have the newest version.")
        latest != null
    }

    /** Downloads the new APK and opens Android's installer. */
    suspend fun update() = withContext(Dispatchers.IO) {
        if (_state.value.progress != null) return@withContext
        // Android asks once for permission to install apps from Tonefer.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = _state.value.copy(message = "Allow Tonefer to install updates, then tap Update again.")
            return@withContext
        }
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val apk = File(dir, "tonefer.apk")
        val tmp = File(dir, "tonefer.apk.part")
        _state.value = _state.value.copy(progress = 0f, message = null)
        try {
            http.newCall(Request.Builder().url(APK).header("User-Agent", "Tonefer-updater").build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("GitHub answered ${r.code}")
                val body = r.body ?: throw IllegalStateException("empty download")
                val total = body.contentLength()
                var done = 0L
                tmp.outputStream().use { out ->
                    val src = body.byteStream()
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = _state.value.copy(progress = done.toFloat() / total)
                    }
                }
                if (done < 500_000) throw IllegalStateException("the download was incomplete")
            }
            apk.delete()
            tmp.renameTo(apk)
            _state.value = _state.value.copy(progress = null, message = "Tap Update in the screen that opened.")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            tmp.delete()
            _state.value = _state.value.copy(progress = null, message = "Update failed: ${e.message ?: "no connection"}")
        }
    }

    companion object {
        private const val REPO = "pankqx/tonefer-android"
        private const val API = "https://api.github.com/repos/$REPO/releases/latest"
        private const val RAW = "https://raw.githubusercontent.com/$REPO/main/VERSION"
        private const val APK = "https://github.com/$REPO/releases/latest/download/tonefer.apk"

        fun parse(v: String): List<Int> = v.trim().trimStart('v', 'V').split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        fun newer(latest: String, current: String): Boolean {
            val a = parse(latest); val b = parse(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
