package io.github.gl1ch5.linuxarm.distro

import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import io.github.gl1ch5.linuxarm.core.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Unpacks a rootfs.tar.zst into the app's private storage. Ownership is ignored (proot
 * fakes root), hard links become copies (apps may not create hard links), device nodes
 * are skipped (proot binds the real /dev).
 */
object RootfsExtractor {
    private class CountingStream(input: InputStream, val onRead: (Long) -> Unit) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { count++; onRead(count) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    }

    suspend fun extract(archive: File, dest: File, onProgress: (Float) -> Unit) {
        val total = archive.length().coerceAtLeast(1)
        dest.mkdirs()
        val destCanonical = dest.canonicalFile
        var lastReported = 0L
        var files = 0
        var links = 0
        var skipped = 0
        val counting = CountingStream(BufferedInputStream(archive.inputStream(), 1 shl 20)) { n ->
            if (n - lastReported > 1 shl 20) {
                lastReported = n
                onProgress(n.toFloat() / total)
            }
        }
        TarArchiveInputStream(ZstdInputStream(counting).apply { setContinuous(false) }).use { tar ->
            val buf = ByteArray(256 * 1024)
            val deferredDirModes = ArrayList<Pair<File, Int>>()
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                val out = File(dest, name)
                // Refuse entries escaping the destination (../ or absolute names).
                val parent = out.parentFile!!.canonicalFile
                if (!parent.path.startsWith(destCanonical.path)) {
                    throw IOException("unsafe path in archive: ${entry.name}")
                }
                parent.mkdirs()
                when {
                    entry.isDirectory -> {
                        out.mkdirs()
                        deferredDirModes += out to entry.mode
                    }
                    entry.isSymbolicLink -> {
                        deleteIfExists(out)
                        Os.symlink(entry.linkName, out.path)
                        links++
                    }
                    entry.isLink -> {
                        // Hard link: copy the already extracted target.
                        val target = File(dest, entry.linkName.removePrefix("./"))
                        deleteIfExists(out)
                        target.copyTo(out)
                        Os.chmod(out.path, (entry.mode and 0x1ff) or 0x180)
                        files++
                    }
                    entry.isFile -> {
                        deleteIfExists(out)
                        out.outputStream().use { o ->
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                        }
                        // Keep rwx bits, make sure we can always read/write our own files.
                        Os.chmod(out.path, (entry.mode and 0x1ff) or 0x180)
                        files++
                    }
                    else -> skipped++ // devices, fifos
                }
            }
            for ((dir, mode) in deferredDirModes) {
                runCatching { Os.chmod(dir.path, (mode and 0x1ff) or 0x1c0) }
            }
        }
        onProgress(1f)
        AppLog.i("extracted $files files, $links symlinks, skipped $skipped special entries into $dest")
    }

    private fun deleteIfExists(f: File) {
        if (Files.isSymbolicLink(f.toPath()) || f.exists()) {
            if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) deleteTree(f) else f.delete()
        }
    }

    /** Deletes a directory tree without ever following symlinks out of it. */
    fun deleteTree(root: File) {
        val path = root.toPath()
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Os.chmod(dir.toString(), 0x1c0) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                runCatching { Files.deleteIfExists(file) }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                // A process still writing into the tree (a session being started) can leave
                // a directory non-empty; the rest is deleted anyway.
                runCatching { Files.deleteIfExists(dir) }
                return FileVisitResult.CONTINUE
            }
        })
    }
}
