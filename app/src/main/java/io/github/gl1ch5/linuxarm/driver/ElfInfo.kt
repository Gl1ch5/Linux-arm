package io.github.gl1ch5.linuxarm.driver

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Just enough ELF64 parsing to read DT_NEEDED and tell glibc, musl and bionic apart. */
object ElfInfo {
    enum class Libc { GLIBC, MUSL, BIONIC, UNKNOWN }

    data class Info(val machine: Int, val needed: List<String>) {
        val isAarch64 get() = machine == 183
        val libc: Libc
            get() = when {
                needed.any { it == "libc.so.6" || it.startsWith("ld-linux-aarch64") } -> Libc.GLIBC
                needed.any { it.startsWith("libc.musl") } -> Libc.MUSL
                needed.any { it == "libc.so" || it == "libdl.so" || it == "liblog.so" || it == "libandroid.so" } -> Libc.BIONIC
                else -> Libc.UNKNOWN
            }
    }

    fun read(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(16).also { raf.readFully(it) }
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[4] != 2.toByte()) return null
            fun buf(off: Long, len: Int): ByteBuffer {
                val b = ByteArray(len)
                raf.seek(off); raf.readFully(b)
                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            }
            val h = buf(0, 64)
            val machine = h.getShort(18).toInt() and 0xffff
            val phoff = h.getLong(32)
            val phentsize = h.getShort(54).toInt() and 0xffff
            val phnum = h.getShort(56).toInt() and 0xffff
            data class Seg(val type: Int, val offset: Long, val vaddr: Long, val filesz: Long)
            val segs = (0 until phnum).map { i ->
                val p = buf(phoff + i.toLong() * phentsize, 56)
                Seg(p.getInt(0), p.getLong(8), p.getLong(16), p.getLong(32))
            }
            fun vaToOff(va: Long): Long? = segs.firstOrNull { it.type == 1 && va >= it.vaddr && va < it.vaddr + it.filesz }
                ?.let { va - it.vaddr + it.offset }
            val dyn = segs.firstOrNull { it.type == 2 } ?: return Info(machine, emptyList())
            val d = buf(dyn.offset, dyn.filesz.toInt())
            val neededOffs = ArrayList<Long>()
            var strtab = 0L
            var strsz = 0L
            var i = 0
            while (i + 16 <= d.capacity()) {
                val tag = d.getLong(i)
                val v = d.getLong(i + 8)
                when (tag) {
                    0L -> break
                    1L -> neededOffs += v
                    5L -> strtab = v
                    10L -> strsz = v
                }
                i += 16
            }
            val strOff = vaToOff(strtab) ?: return Info(machine, emptyList())
            val strs = buf(strOff, strsz.toInt().coerceAtMost(1 shl 20)).array()
            val needed = neededOffs.map { o ->
                var end = o.toInt()
                while (end < strs.size && strs[end] != 0.toByte()) end++
                String(strs, o.toInt(), end - o.toInt())
            }
            Info(machine, needed)
        }
    }.getOrNull()
}
