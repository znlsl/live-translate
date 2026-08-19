package com.livetranslate.app.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.livetranslate.app.LiveTranslateApp
import com.livetranslate.app.R
import com.livetranslate.app.audio.MicAudioCapturer
import com.livetranslate.app.audio.PcmMixer
import com.livetranslate.app.audio.SystemAudioCapturer
import com.livetranslate.app.audio.TranslatedAudioPlayer
import com.livetranslate.app.data.AudioSourceMode
import com.livetranslate.app.data.SupportedLanguages
import com.livetranslate.app.data.UserSettings
import com.livetranslate.app.live.LiveTranslateClient
import com.livetranslate.app.overlay.SubtitleOverlayController
import com.livetranslate.app.ui.main.MainActivity
import com.livetranslate.app.util.SameLanguageCaptionMode
import com.livetranslate.app.util.TranscriptBuffer
import com.livetranslate.app.util.TranscriptLineBreaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Foreground session: audio capture (media / mic / both) + Live WS + floating overlay.
 */
class SubtitleSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var mediaCapturer: SystemAudioCapturer? = null
    private var micCapturer: MicAudioCapturer? = null
    private var pcmMixer: PcmMixer? = null
    private var liveClient: LiveTranslateClient? = null
    private var audioPlayer: TranslatedAudioPlayer? = null
    private var overlay: SubtitleOverlayController? = null
    private var settingsJob: Job? = null
    private var eventsJob: Job? = null
    private var commandJob: Job? = null

    private var currentSettings: UserSettings = UserSettings()
    private var audioSourceMode: AudioSourceMode = AudioSourceMode.MEDIA
    private var captureStarted = false
    private var stopping = false

    private var accumulatedInput = StringBuilder()
    private var accumulatedOutput = StringBuilder()
    private var fullInput = StringBuilder()
    private var fullOutput = StringBuilder()

    private var lastPreviewAtMs = 0L
    private var pendingPreviewInput: String? = null
    private var pendingPreviewOutput: String? = null
    private var previewFlushJob: Job? = null
    private val sameLanguageMode = SameLanguageCaptionMode()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        commandJob = scope.launch {
            SessionBus.commands.collect { cmd ->
                when (cmd) {
                    SessionBus.Command.Stop -> stopEverything("已停止")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything("已停止")
            }
            ACTION_START -> {
                val modeName = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                audioSourceMode = AudioSourceMode.fromStorage(modeName)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (audioSourceMode.needsMediaProjection) {
                    if (data == null || resultCode != Activity.RESULT_OK) {
                        SessionBus.setStatus(SessionBus.Status.Error, "录屏授权失败")
                        stopSelf()
                    } else {
                        startSession(resultCode, data)
                    }
                } else {
                    startSession(resultCode = null, data = null)
                }
            }
            else -> {
                // Sticky restart or unknown action: must not sit without startForeground.
                ensureForegroundNotification()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int?, data: Intent?) {
        stopping = false
        SessionBus.setStatus(SessionBus.Status.Starting, "正在启动…")
        SessionBus.clearExport()
        accumulatedInput.clear()
        accumulatedOutput.clear()
        fullInput.clear()
        fullOutput.clear()
        captureStarted = false
        lastPreviewAtMs = 0L
        pendingPreviewInput = null
        pendingPreviewOutput = null
        previewFlushJob?.cancel()
        previewFlushJob = null
        sameLanguageMode.reset()
        startAsForeground()

        val app = application as LiveTranslateApp
        if (!app.apiKeyStore.hasApiKey()) {
            SessionBus.setStatus(SessionBus.Status.Error, "请先在设置中填写 API Key")
            stopSelf()
            return
        }

        scope.launch {
            currentSettings = app.settingsRepository.settings.first()
            // audioSourceMode already set from Intent EXTRA in onStartCommand.

            if (audioSourceMode.needsMediaProjection) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(resultCode!!, data!!)
                if (projection == null) {
                    SessionBus.setStatus(SessionBus.Status.Error, "无法创建 MediaProjection")
                    stopSelf()
                    return@launch
                }
                mediaProjection = projection
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopEverything("录屏权限已撤销")
                        }
                    },
                    null,
                )
            }

            val overlayController = SubtitleOverlayController(this@SubtitleSessionService) { x, y, w, h ->
                ioScope.launch {
                    app.settingsRepository.update {
                        it.copy(overlayX = x, overlayY = y, overlayWidthDp = w, overlayHeightDp = h)
                    }
                }
            }
            overlay = overlayController
            overlayController.show(currentSettings)

            val client = LiveTranslateClient()
            liveClient = client

            val player = TranslatedAudioPlayer()
            audioPlayer = player
            player.setEnabled(currentSettings.playTranslatedAudio)
            player.setVolume(currentSettings.translatedVolume)

            eventsJob = scope.launch {
                launch {
                    client.connectionState.collect { state ->
                        when (state) {
                            is LiveTranslateClient.ConnectionState.Ready -> {
                                SessionBus.setStatus(SessionBus.Status.Running, "翻译中")
                                startCapturePipeline(client)
                            }
                            is LiveTranslateClient.ConnectionState.Failed -> {
                                stopEverything(state.message, error = true)
                            }
                            is LiveTranslateClient.ConnectionState.Closed -> {
                                stopEverything("连接已断开", error = true)
                            }
                            else -> Unit
                        }
                    }
                }
                client.events.collect { event ->
                    when (event) {
                        is LiveTranslateClient.LiveEvent.SetupComplete -> {
                            SessionBus.setStatus(SessionBus.Status.Running, "翻译中")
                            startCapturePipeline(client)
                        }
                        is LiveTranslateClient.LiveEvent.InputTranscript -> {
                            appendTranscript(accumulatedInput, event.text)
                            appendFull(fullInput, event.text)
                            val target = currentSettings.targetLanguageCode
                            val toggled = if (event.languageCode.isNullOrBlank()) {
                                sameLanguageMode.onInputTextFallback(
                                    accumulatedInput.toString(),
                                    target,
                                    outputEmpty = accumulatedOutput.isEmpty(),
                                )
                            } else {
                                sameLanguageMode.onDetectedInputLanguage(
                                    event.languageCode,
                                    target,
                                )
                            }
                            if (toggled != null) {
                                applyCaptionMode(toggled)
                            } else {
                                publishCaptionUpdate(inputChanged = true, outputChanged = false)
                            }
                        }
                        is LiveTranslateClient.LiveEvent.OutputTranscript -> {
                            appendTranscript(accumulatedOutput, event.text)
                            appendFull(fullOutput, event.text)
                            publishCaptionUpdate(inputChanged = false, outputChanged = true)
                        }
                        is LiveTranslateClient.LiveEvent.AudioChunk -> {
                            if (currentSettings.playTranslatedAudio) {
                                player.playPcm(event.pcm, event.mimeType)
                            }
                        }
                        is LiveTranslateClient.LiveEvent.Error -> {
                            stopEverything(event.message, error = true)
                        }
                        is LiveTranslateClient.LiveEvent.Debug -> {
                            Log.d(TAG, event.message)
                        }
                    }
                }
            }

            settingsJob = scope.launch {
                app.settingsRepository.settings.collectLatest { s ->
                    val prev = currentSettings
                    currentSettings = s
                    val appearanceOrAudioChanged =
                        prev.fontSizeSp != s.fontSizeSp ||
                            prev.backgroundAlpha != s.backgroundAlpha ||
                            prev.bilingual != s.bilingual ||
                            prev.playTranslatedAudio != s.playTranslatedAudio ||
                            prev.translatedVolume != s.translatedVolume
                    if (!appearanceOrAudioChanged) return@collectLatest
                    overlay?.updateSettings(s)
                    player.setEnabled(s.playTranslatedAudio)
                    player.setVolume(s.translatedVolume)
                    if (prev.playTranslatedAudio && !s.playTranslatedAudio) {
                        Log.i(TAG, "translated audio disabled")
                    }
                }
            }

            yield()
            // Rotate keys: try first available via round-robin start index
            val key = app.apiKeyStore.nextRotatedKey()
            if (key.isBlank()) {
                SessionBus.setStatus(SessionBus.Status.Error, "请先在设置中填写 API Key")
                stopSelf()
                return@launch
            }
            client.connect(
                LiveTranslateClient.SessionConfig(
                    endpoint = currentSettings.endpoint,
                    apiKey = key,
                    modelId = currentSettings.modelId,
                    targetLanguageCode = SupportedLanguages.canonicalOrDefault(
                        currentSettings.targetLanguageCode,
                    ),
                    // Official: when input is already the target language, echo
                    // instead of staying silent so same-language audio still
                    // produces captions (e.g. Chinese video → Chinese subtitles).
                    echoTargetLanguage = true,
                ),
            )
        }
    }

    private fun startCapturePipeline(client: LiveTranslateClient) {
        if (captureStarted) return
        captureStarted = true
        try {
            when (audioSourceMode) {
                AudioSourceMode.MEDIA -> {
                    val projection = mediaProjection
                        ?: throw IllegalStateException("缺少 MediaProjection")
                    val cap = SystemAudioCapturer(ioScope)
                    mediaCapturer = cap
                    cap.start(projection) { pcm -> client.sendPcm16le(pcm, 16_000) }
                }
                AudioSourceMode.MIC -> {
                    val mic = MicAudioCapturer(ioScope)
                    micCapturer = mic
                    mic.start { pcm -> client.sendPcm16le(pcm, 16_000) }
                }
                AudioSourceMode.MEDIA_AND_MIC -> {
                    val projection = mediaProjection
                        ?: throw IllegalStateException("缺少 MediaProjection")
                    val mixer = PcmMixer { mixed -> client.sendPcm16le(mixed, 16_000) }
                    pcmMixer = mixer
                    val media = SystemAudioCapturer(ioScope)
                    mediaCapturer = media
                    media.start(projection) { pcm -> mixer.offerMedia(pcm) }
                    val mic = MicAudioCapturer(ioScope)
                    micCapturer = mic
                    mic.start { pcm -> mixer.offerMic(pcm) }
                }
            }
            SessionBus.setStatus(SessionBus.Status.Running, "翻译中 · 等待声音…")
        } catch (e: Exception) {
            Log.e(TAG, "capture start failed", e)
            SessionBus.setStatus(SessionBus.Status.Error, e.message ?: "音频采集启动失败")
            stopEverything(e.message ?: "音频采集启动失败")
        }
    }

    /**
     * Collapse or restore the overlay when source language equals the target.
     * When collapsed, the input transcript is shown as the single caption line.
     */
    private fun applyCaptionMode(enabled: Boolean) {
        overlay?.setSameLanguageMode(enabled)
        publishCaptionUpdate(inputChanged = true, outputChanged = true)
    }

    private fun publishCaptionUpdate(inputChanged: Boolean, outputChanged: Boolean) {
        val inDisplay = TranscriptLineBreaker.format(accumulatedInput.toString())
        val outDisplay = TranscriptLineBreaker.format(accumulatedOutput.toString())
        if (sameLanguageMode.enabled) {
            val line = inDisplay.ifBlank { outDisplay }
            overlay?.updateTranscripts(input = "", output = line)
            publishPreview(input = "", output = line)
        } else {
            overlay?.updateTranscripts(
                input = if (inputChanged) inDisplay else null,
                output = if (outputChanged) outDisplay else null,
            )
            publishPreview(
                input = if (inputChanged) inDisplay else null,
                output = if (outputChanged) outDisplay else null,
            )
        }
    }

    private fun appendTranscript(buffer: StringBuilder, chunk: String) {
        TranscriptBuffer.append(buffer, chunk, maxChars = 800)
    }

    private fun appendFull(buffer: StringBuilder, chunk: String) {
        TranscriptBuffer.append(buffer, chunk, maxChars = 200_000)
    }

    private fun publishPreview(input: String? = null, output: String? = null) {
        if (input != null) pendingPreviewInput = input
        if (output != null) pendingPreviewOutput = output
        val now = System.currentTimeMillis()
        val wait = PREVIEW_THROTTLE_MS - (now - lastPreviewAtMs)
        if (wait <= 0L) {
            flushPreview()
            return
        }
        if (previewFlushJob?.isActive == true) return
        previewFlushJob = scope.launch {
            delay(wait)
            flushPreview()
        }
    }

    private fun flushPreview() {
        previewFlushJob?.cancel()
        previewFlushJob = null
        if (pendingPreviewInput == null && pendingPreviewOutput == null) return
        lastPreviewAtMs = System.currentTimeMillis()
        SessionBus.setPreview(input = pendingPreviewInput, output = pendingPreviewOutput)
        pendingPreviewInput = null
        pendingPreviewOutput = null
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, SubtitleSessionService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LiveTranslateApp.CHANNEL_SUBTITLE)
            .setContentTitle(getString(R.string.notification_subtitle_running))
            .setContentText(getString(R.string.notification_subtitle_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (audioSourceMode) {
                AudioSourceMode.MEDIA ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                AudioSourceMode.MIC ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                AudioSourceMode.MEDIA_AND_MIC ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fgsType != 0) {
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Used when the process is restarted with a null intent so we still hit startForeground. */
    private fun ensureForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopEverything(message: String, error: Boolean = false) {
        if (stopping) return
        stopping = true
        flushPreview()
        val inFull = fullInput.toString()
        val rawOut = fullOutput.toString()
        val outFull = when {
            rawOut.isNotBlank() -> rawOut
            sameLanguageMode.enabled -> inFull
            else -> rawOut
        }
        if (inFull.isNotBlank() || outFull.isNotBlank()) {
            SessionBus.markSessionFinished(inFull, outFull, message)
        }
        if (error) {
            SessionBus.setStatus(SessionBus.Status.Error, message)
        } else if (inFull.isBlank() && outFull.isBlank()) {
            SessionBus.setStatus(SessionBus.Status.Stopped, message)
        }
        mediaCapturer?.stop()
        mediaCapturer = null
        micCapturer?.stop()
        micCapturer = null
        pcmMixer?.close()
        pcmMixer = null
        liveClient?.close()
        liveClient?.destroy()
        liveClient = null
        audioPlayer?.release()
        audioPlayer = null
        overlay?.hide()
        overlay = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        settingsJob?.cancel()
        eventsJob?.cancel()
        captureStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mediaCapturer?.stop()
        micCapturer?.stop()
        pcmMixer?.close()
        liveClient?.destroy()
        audioPlayer?.release()
        overlay?.hide()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        scope.cancel()
        ioScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SubtitleSessionService"
        private const val NOTIFICATION_ID = 42
        private const val PREVIEW_THROTTLE_MS = 200L
        const val ACTION_START = "com.livetranslate.app.action.START_SUBTITLE"
        const val ACTION_STOP = "com.livetranslate.app.action.STOP_SUBTITLE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AUDIO_SOURCE = "audio_source"

        fun start(
            context: Context,
            audioSource: AudioSourceMode,
            resultCode: Int? = null,
            data: Intent? = null,
        ) {
            val intent = Intent(context, SubtitleSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_AUDIO_SOURCE, audioSource.name)
                if (resultCode != null && data != null) {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SubtitleSessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
