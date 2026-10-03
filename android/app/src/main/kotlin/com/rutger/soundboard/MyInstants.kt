package com.rutger.soundboard

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

data class MyInstantSound(
    val id: String,
    val title: String,
    val url: String,
    val mp3: String,
)

/**
 * Scrapes myinstants.com directly for trending + search results and downloads
 * the selected sound's mp3 into the app's own files dir, so it becomes a
 * first-class local sound the soundboard can preload like a user-picked file.
 *
 * Same selectors as https://github.com/abdipr/myinstants-api, whose hosted
 * instance went offline.
 */
object MyInstants {

    private const val SITE = "https://www.myinstants.com"
    private const val TIMEOUT_MS = 15_000
    private const val PAGE_TIMEOUT_MS = 25_000L
    private const val POLL_MS = 300L

    // Returns null until the real page has rendered: before navigation, while
    // Cloudflare's "Just a moment..." challenge runs, and during loading.
    private val EXTRACT_JS = """
        (function() {
          if (location.hostname.indexOf('myinstants.com') < 0) return null;
          if (document.title.indexOf('Just a moment') >= 0) return null;
          if (document.readyState !== 'complete') return null;
          return Array.from(document.querySelectorAll('div.instant')).map(function(d) {
            var a = d.querySelector('a.instant-link');
            var b = d.querySelector('button.small-button');
            var m = b && /play\('([^']+)'/.exec(b.getAttribute('onclick') || '');
            return a && m ? { href: a.getAttribute('href'), title: a.textContent.trim(), mp3: m[1] } : null;
          }).filter(Boolean);
        })()
    """.trimIndent()

    /** Popular sounds to show when the search box is empty. `region` e.g. "us". */
    suspend fun trending(context: Context, region: String = "us"): List<MyInstantSound> =
        scrape(context, "$SITE/en/index/${enc(region)}/")

    suspend fun search(context: Context, query: String): List<MyInstantSound> =
        scrape(context, "$SITE/en/search/?name=${enc(query)}")

    // Cloudflare answers plain HTTP clients with a JS challenge (403), so the
    // pages are loaded in a real browser engine that passes it on its own.
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrape(context: Context, url: String): List<MyInstantSound> =
        withContext(Dispatchers.Main) {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Never attached to a window, so give it a phone-sized viewport:
                // a 0x0 window is a bot signal for the challenge.
                layout(0, 0, 1080, 2340)
                loadUrl(url)
            }
            try {
                withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    var sounds: List<MyInstantSound>? = null
                    while (sounds == null) {
                        delay(POLL_MS)
                        sounds = parseSounds(webView.evaluate(EXTRACT_JS))
                    }
                    sounds
                } ?: run {
                    Log.w("MyInstants", "Timed out loading $url")
                    emptyList()
                }
            } finally {
                webView.destroy()
            }
        }

    private suspend fun WebView.evaluate(js: String): String =
        suspendCancellableCoroutine { cont -> evaluateJavascript(js) { cont.resume(it) } }

    private fun parseSounds(json: String): List<MyInstantSound>? {
        if (json == "null") return null
        val items = JSONArray(json)
        return List(items.length()) { i ->
            val item = items.getJSONObject(i)
            val href = item.getString("href")
            MyInstantSound(
                id = href.trimEnd('/').substringAfterLast('/'),
                title = item.getString("title"),
                url = SITE + href,
                mp3 = SITE + item.getString("mp3"),
            )
        }
    }

    /**
     * Download [sound]'s mp3 into app storage and return a content Uri that the
     * rest of the app (AudioPlayer, prefs) can treat like any picked file.
     * Returns null on failure.
     */
    suspend fun download(context: Context, sound: MyInstantSound): Uri? =
        withContext(Dispatchers.IO) {
            if (sound.mp3.isBlank()) return@withContext null
            val dir = File(context.filesDir, "myinstants").apply { mkdirs() }
            val safeId = sound.id.ifBlank { sound.title }
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .ifBlank { "sound_${System.currentTimeMillis()}" }
            val outFile = File(dir, "$safeId.mp3")

            val conn = (URL(sound.mp3).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(context))
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                conn.inputStream.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                outFile.delete()
                return@withContext null
            } finally {
                conn.disconnect()
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile,
            )
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
