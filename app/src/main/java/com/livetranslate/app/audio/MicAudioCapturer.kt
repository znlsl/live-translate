package com.livetranslate.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.livetranslate.app.R
import com.livetranslate.app.util.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures microphone PCM and resamples to 16 kHz mono LE for Live Translate.
 */
class MicAudioCapturer(
    private val scope: CoroutineScope,
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(
        onPcm16k: (ByteArray) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        stop()
        val sourceRate = 44_100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sourceRate, channelConfig, encoding)
        val bufferSize = (minBuf * 2).coerceAtLeast(sourceRate / 5 * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sourceRate,
            channelConfig,
            encoding,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(AppStrings.get(R.string.error_capture_init_mic))
        }

        audioRecord = record
        running = true
        record.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            // Read in ~100ms chunks so the first transcript appears sooner.
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
                            onError(AppStrings.get(R.string.error_capture_stalled_mic))
                            break
                        }
                        delay(20)
                    }
                    else -> {
                        Log.w(TAG, "mic read error: $n")
                        onError(AppStrings.get(R.string.error_capture_read_mic, n))
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
        // record.stop()/release() unblocks any in-flight read, so the capture
        // coroutine exits on its own — no main-thread join needed here.
        try {
            record?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    companion object {
        private const val TAG = "MicAudioCapturer"
        private const val SOURCE_RATE = 44_100
        // ~100ms of audio at 44.1 kHz: smaller reads reach the model sooner.
        private const val READ_CHUNK_SAMPLES = SOURCE_RATE / 10

        /** 50 × 20ms of zero reads (~1s) means the record is effectively dead. */
        private const val ZERO_READ_LIMIT = 50
    }
}
