package io.github.gl1ch5.linuxarm.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.gl1ch5.linuxarm.core.AppLog
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * Plays what the guest's PulseAudio writes into its pipe sink (48 kHz, s16le, stereo;
 * see assets/pulse.pa). The FIFO lives at [rootfs]/tmp/.la-audio/out.
 */
class AudioBridge(private val rootfs: File) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-bridge", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        val fifo = File(rootfs, "tmp/.la-audio/out")
        val minBuf = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuf * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val buf = ByteArray(minBuf)
        try {
            while (running) {
                // PulseAudio creates the FIFO once it starts.
                if (!fifo.exists()) { Thread.sleep(500); continue }
                AppLog.i("audio: connected to $fifo (buffer $minBuf bytes)")
                track.play()
                FileInputStream(fifo).use { input ->
                    while (running) {
                        val n = input.read(buf)
                        if (n < 0) break
                        track.write(buf, 0, n)
                    }
                }
                track.pause()
                track.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (running) AppLog.w("audio bridge stopped", e)
        } finally {
            runCatching { track.release() }
        }
    }
}
