package com.example.cctv.camera

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SirenPlayer {

    private var audioTrack: AudioTrack? = null
    private var sirenJob: Job? = null
    private var isPlaying = false

    fun startSiren(scope: CoroutineScope) {
        if (isPlaying) return
        isPlaying = true

        sirenJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val numSamples = sampleRate / 10 // 0.1 sec chunks
            val sample = ShortArray(numSamples)

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize.coerceAtLeast(numSamples * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()

            var phase = 0.0
            var frequency = 600.0 // Hz
            var sweepUp = true

            while (isActive && isPlaying) {
                // Sweep frequency between 600Hz and 1400Hz for high-pitched police siren
                if (sweepUp) {
                    frequency += 15.0
                    if (frequency >= 1400.0) sweepUp = false
                } else {
                    frequency -= 15.0
                    if (frequency <= 600.0) sweepUp = true
                }

                for (i in 0 until numSamples) {
                    phase += 2.0 * Math.PI * frequency / sampleRate
                    sample[i] = (Math.sin(phase) * 28000).toInt().toShort()
                }

                track.write(sample, 0, numSamples)
            }

            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopSiren() {
        isPlaying = false
        sirenJob?.cancel()
        sirenJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null
    }

    fun isSirenPlaying(): Boolean = isPlaying
}
