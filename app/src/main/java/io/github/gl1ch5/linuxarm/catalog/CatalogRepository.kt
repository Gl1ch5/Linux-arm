package io.github.gl1ch5.linuxarm.catalog

import android.content.Context
import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.AppPaths
import io.github.gl1ch5.linuxarm.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The list of installable images: fetched from the rolling "images" release, cached on
 * disk, with a copy bundled in the APK as a last resort (first start without network).
 */
class CatalogRepository(private val context: Context, private val paths: AppPaths) {
    private val cacheFile = File(paths.catalogCache, "images.json")
    private val _images = MutableStateFlow<List<ImageEntry>>(emptyList())
    val images: StateFlow<List<ImageEntry>> = _images

    val indexUrl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/images/images.json"

    fun loadCached() {
        val text = runCatching { cacheFile.readText() }.getOrNull()
            ?: runCatching { context.assets.open("images.json").bufferedReader().readText() }.getOrNull()
        if (text != null) parse(text)?.let { _images.value = it }
    }

    suspend fun refresh(): Result<Unit> = runCatching {
        val text = Http.getText(indexUrl)
        val list = parse(text) ?: error("invalid images.json")
        cacheFile.writeText(text)
        _images.value = list
        AppLog.i("catalog: ${list.size} images from $indexUrl")
    }.onFailure { AppLog.w("catalog refresh failed: $it") }

    fun find(id: String) = _images.value.firstOrNull { it.id == id }

    private fun parse(text: String): List<ImageEntry>? =
        runCatching { JsonFormat.decodeFromString<ImagesIndex>(text).images }
            .onFailure { AppLog.w("images.json parse error", it) }.getOrNull()
}
