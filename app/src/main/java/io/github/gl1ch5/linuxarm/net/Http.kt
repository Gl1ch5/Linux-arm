package io.github.gl1ch5.linuxarm.net

import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.core.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "linux-arm/${BuildConfig.VERSION_NAME}")
        .build()

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body.string()
        }
    }

    /**
     * Downloads [url] into [dest] (via a .part file), reporting (bytesDone, bytesTotal)
     * and verifying [sha256] when given. Throws on mismatch and deletes the file.
     */
    suspend fun download(
        url: String,
        dest: File,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        AppLog.i("download $url -> $dest")
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val total = r.body.contentLength()
            var done = 0L
            var lastReport = 0L
            r.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha256 != null && !sha256.equals(actual, ignoreCase = true)) {
            part.delete()
            throw IOException("SHA-256 mismatch for ${dest.name}: expected $sha256, got $actual")
        }
        AppLog.i("downloaded ${dest.name}, sha256 $actual")
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("cannot rename $part")
        dest
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
