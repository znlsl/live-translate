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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
            val readBuf = ShortArray(bufferSize / 2)
            val resampler = PcmResampler(sourceRate, 16_000)
            while (isActive && running) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    val pcm16k = resampler.resample(readBuf, n)
                    if (pcm16k.isNotEmpty()) onPcm16k(pcm16k)
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord read error: $n")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        val record = audioRecord
        val job = captureJob
        captureJob = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        job?.cancel()
        if (job != null) {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(400) { job.join() }
                }
            }
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    companion object {
        private const val TAG = "SystemAudioCapturer"
    }
}
