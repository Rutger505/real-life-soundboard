package com.rutger.soundboard

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
 * Port of the parser in https://github.com/abdipr/myinstants-api; its hosted
 * instance went offline, so the app no longer depends on it.
 */
object MyInstants {

    private const val SITE = "https://www.myinstants.com"
    private const val TIMEOUT_MS = 15_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private val playRegex = Regex("""play\('([^']+)'""")
    private val linkRegex =
        Regex("""<a\s+href="(/[a-z]{2}/instant/[^"]+)"[^>]*class="instant-link[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    /** Popular sounds to show when the search box is empty. `region` e.g. "us". */
    suspend fun trending(region: String = "us"): List<MyInstantSound> =
        fetch("$SITE/en/index/${enc(region)}/")

    suspend fun search(query: String): List<MyInstantSound> =
        fetch("$SITE/en/search/?name=${enc(query)}")

    private suspend fun fetch(url: String): List<MyInstantSound> =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w("MyInstants", "HTTP ${conn.responseCode} for $url")
                    return@withContext emptyList()
                }
                parseSounds(conn.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.w("MyInstants", "Fetch failed for $url", e)
                emptyList()
            } finally {
                conn.disconnect()
            }
        }

    private fun parseSounds(html: String): List<MyInstantSound> =
        html.split("""<div class="instant">""").drop(1).mapNotNull { block ->
            val mp3Path = playRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val link = linkRegex.find(block) ?: return@mapNotNull null
            val href = link.groupValues[1]
            MyInstantSound(
                id = href.trimEnd('/').substringAfterLast('/'),
                title = Html.fromHtml(link.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                url = SITE + href,
                mp3 = SITE + mp3Path,
            )
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
                setRequestProperty("User-Agent", USER_AGENT)
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
