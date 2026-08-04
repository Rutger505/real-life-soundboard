package com.rutger.soundboard

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One sound as returned by the MyInstants API. */
@Serializable
data class MyInstantSound(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val mp3: String = "",
)

@Serializable
private data class MyInstantsResponse(
    @SerialName("data") val data: List<MyInstantSound> = emptyList(),
)

/**
 * Thin client for the unofficial MyInstants JSON API
 * (https://github.com/abdipr/myinstants-api).
 *
 * It exposes trending + search browsing and downloads the selected sound's mp3
 * into the app's own files dir, so it becomes a first-class local sound the
 * soundboard can preload just like a user-picked file.
 */
object MyInstants {

    private const val BASE = "https://myinstants-api.vercel.app"
    private const val TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    /** Popular sounds to show when the search box is empty. `region` e.g. "us". */
    suspend fun trending(region: String = "us"): List<MyInstantSound> =
        fetch("$BASE/trending?q=${enc(region)}")

    /** Full-text search across MyInstants. */
    suspend fun search(query: String): List<MyInstantSound> =
        fetch("$BASE/search?q=${enc(query)}")

    private suspend fun fetch(url: String): List<MyInstantSound> =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return@withContext emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString<MyInstantsResponse>(body).data
                    .filter { it.mp3.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            } finally {
                conn.disconnect()
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
