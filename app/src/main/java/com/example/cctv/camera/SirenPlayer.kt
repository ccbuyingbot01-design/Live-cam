package com.example.cctv.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SirenPlayer
 *
 * High-decibel emergency siren player compatible with all Android devices and OS versions.
 * Uses STREAM_MUSIC / USAGE_MEDIA to guarantee loudspeaker playback even in Silent/Vibrate mode.
 * Features dual synthesized audio synthesis and system alarm MediaPlayer fallback.
 */
class SirenPlayer {

    companion object {
        private const val TAG = "SirenPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var sirenJob: Job? = null
    private var isPlaying = false

    fun startSiren(scope: CoroutineScope, context: Context? = null) {
        if (isPlaying) return
        isPlaying = true

        // 1. Maximize STREAM_MUSIC volume (guaranteed speaker route on all Android phones)
        context?.let { ctx ->
            try {
                val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    val maxMusicVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0)

                    val maxAlarmVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set max stream volume", e)
            }

            // 2. Play high-volume system alarm sound via MediaPlayer as rock-solid hardware fallback
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                if (alarmUri != null) {
                    val mp = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        setDataSource(ctx, alarmUri)
                        isLooping = true
                        setVolume(1.0f, 1.0f)
                        prepare()
                        start()
                    }
                    mediaPlayer = mp
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer alarm fallback error", e)
            }

            // 3. Tactile emergency alarm vibration
            try {
                @Suppress("DEPRECATION")
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator = vib
                val pattern = longArrayOf(0, 400, 150, 400, 150)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(pattern, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vibrator error", e)
            }
        }

        // 4. Synthesize loud police/security siren waveform on STREAM_MUSIC
        sirenJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val numSamples = sampleRate / 10 // 0.1s chunk
            val sample = ShortArray(numSamples)

            val rawMinBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (rawMinBufferSize > 0) {
                rawMinBufferSize.coerceAtLeast(numSamples * 2).coerceAtLeast(8192)
            } else {
                8192
            }

            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.Builder failed, trying legacy constructor", e)
            }

            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                try {
                    @Suppress("DEPRECATION")
                    track = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy AudioTrack constructor failed", e)
                    track = null
                }
            }

            audioTrack = track

            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    track.play()
                    var phase = 0.0
                    var frequency = 750.0 // Hz
                    var sweepUp = true

                    while (isActive && isPlaying) {
                        // High-pitched undulating siren frequency modulation (750Hz to 1600Hz)
                        if (sweepUp) {
                            frequency += 30.0
                            if (frequency >= 1600.0) sweepUp = false
                        } else {
                            frequency -= 30.0
                            if (frequency <= 750.0) sweepUp = true
                        }

                        for (i in 0 until numSamples) {
                            phase += 2.0 * Math.PI * frequency / sampleRate
                            sample[i] = (Math.sin(phase) * 32000).toInt().coerceIn(-32768, 32767).toShort()
                        }

                        track.write(sample, 0, numSamples)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack write loop error", e)
                } finally {
                    try {
                        track.stop()
                        track.release()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun stopSiren() {
        isPlaying = false
        sirenJob?.cancel()
        sirenJob = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vibrator = null
    }

    fun isSirenPlaying(): Boolean = isPlaying
}
