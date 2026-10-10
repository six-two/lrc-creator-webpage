package dev.lrceditor.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin

/**
 * Short synthesized click (1.8 kHz square, instant peak, ~45 ms exponential decay) held in a static
 * low-latency AudioTrack. No attack ramp: any ramp-up is audible as "late" when judging timing by ear.
 */
class TickPlayer {
    private val sampleRate = 48_000
    private val track: AudioTrack

    init {
        val n = (sampleRate * 0.05).toInt()
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val env = exp(-t / 0.045 * 9.21) // 1.0 -> 0.0001 over 45 ms
            (sign(sin(2 * PI * 1800 * t)) * env * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
    }

    fun tick(volume: Float) {
        track.setVolume(volume.coerceIn(0f, 1f))
        track.stop()
        track.reloadStaticData()
        track.play()
    }

    fun release() = track.release()
}
