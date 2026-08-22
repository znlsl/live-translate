package com.livetranslate.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures other apps' media playback via AudioPlaybackCapture (API 29+),
 * then downsamples to 16-bit mono PCM @ 16 kHz for Live Translate.
 */
class SystemAudioCapturer(
    private val scope: CoroutineScope,
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var running = false

    fun start(
        mediaProjection: MediaProjection,
        onPcm16k: (ByteArray) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        stop()
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sourceRate = 44_100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sourceRate, channelConfig, encoding)
        val bufferSize = (minBuf * 2).coerceAtLeast(sourceRate / 5 * 2)

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sourceRate)
                    .setChannelMask(channelConfig)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord 初始化失败，无法内录系统音频")
        }

        audioRecord = record
        running = true
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            // Read in ~100ms chunks so the first transcript appears sooner: the
            // AudioRecord only returns once a read fills, so a large read buffer
            // adds that much latency before audio reaches the model. The underlying
            // bufferSize stays large to avoid underruns; only the read size shrinks.
            val readBuf = ShortArray(READ_CHUNK_SAMPLES)
            val resampler = PcmResampler(sourceRate, 16_000)
            var zeroStreak = 0
            while (isActive && running) {
                val n = record.read(readBuf, 0, readBuf.size)
                when {
                    n > 0 -> {
                        zeroStreak = 0
                        val pcm16k = resampler.resample(readBuf, n)
                        if (pcm16k.isNotEmpty()) onPcm16k(pcm16k)
                    }
                    n == 0 -> {
                        // Stalled record returns 0 immediately — back off instead
                        // of busy-spinning, then give up and report.
                        zeroStreak++
                        if (zeroStreak >= ZERO_READ_LIMIT) {
                            onError("系统音频采集停滞（AudioRecord 持续无数据）")
                            break
                        }
                        delay(20)
                    }
                    else -> {
                        Log.w(TAG, "AudioRecord read error: $n")
                        onError("系统音频采集读取失败：$n")
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        val record = audioRecord
        captureJob = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        // record.stop()/release() below unblocks any in-flight read, so the
        // capture coroutine exits on its own — no need to join it here (this
        // method runs on the main thread during session teardown).
        try {
            record?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    companion object {
        private const val TAG = "SystemAudioCapturer"
        private const val SOURCE_RATE = 44_100
        // ~100ms of audio at 44.1 kHz: smaller reads reach the model sooner.
        private const val READ_CHUNK_SAMPLES = SOURCE_RATE / 10

        /** 50 × 20ms of zero reads (~1s) means the record is effectively dead. */
        private const val ZERO_READ_LIMIT = 50
    }
}
