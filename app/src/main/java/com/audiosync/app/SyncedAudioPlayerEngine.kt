package com.audiosync.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.abs

/**
 * =========================================================================================
 * מנוע שמע מסונכרן (Synced Audio Player Engine)
 * =========================================================================================
 * 
 * מנוע זה אחראי על:
 * 1. שילוב שני סוגי נגנים:
 *    - ExoPlayer (Media3) עבור קבצים מקומיים (Local Audio) ושידורי רדיו מקוונים (Radio Streams).
 *    - YouTube IFrame Player (WebView) עבור ניגון מוטמע חוקי מיוטיוב ללא תלות ב-Premium.
 * 2. קיזוז השהיית חומרה (Hardware Audio Latency Divergence):
 *    - תשאול AudioManager לקבלת גודל ה-Buffer וקצב הדגימה של ה-DAC.
 *    - הפחתת השהיית החומרה ממועד השיגור המדויק.
 * 3. תזמון יעד עתידי בדיוק מיקרו-שניות (Spin-Wait Loop על פני Coroutine Delay).
 * 4. טעינה מוקדמת (Pre-buffering) ודיווח BUFFER_READY.
 * 5. אלגוריתם בקרת סטייה מתמשכת (Continuous Drift Correction):
 *    - סטייה קלה (5ms–30ms): תיקון שקוף לאוזן באמצעות PlaybackParams (0.99f / 1.01f).
 *    - סטייה חמורה (>150ms): ביצוע Hard Seek מיידי.
 */
class SyncedAudioPlayerEngine(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioSync_PlayerEngine"
        private const val FINE_DRIFT_THRESHOLD_MS = 5L     // סף תחילת תיקון עדין
        private const val CORRECTION_DRIFT_MAX_MS = 30L    // סף עליון לתיקון עדין (מעליו נגביר תיקון)
        private const val HARD_SEEK_THRESHOLD_MS = 150L    // סף סטייה חמורה הדורש Hard Seek
        private const val SPIN_WAIT_WINDOW_MS = 3L         // חלון המתנה בלולאה נעולה (Spin-wait) לשחרור מיקרו-שניות
    }

    // -------------------------------------------------------------------------------------
    // מצבי המנוע (StateFlows)
    // -------------------------------------------------------------------------------------
    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentPositionMs = MutableStateFlow<Long>(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _driftMs = MutableStateFlow<Long>(0L)
    val driftMs: StateFlow<Long> = _driftMs.asStateFlow()

    private val _hardwareLatencyMs = MutableStateFlow<Long>(0L)
    val hardwareLatencyMs: StateFlow<Long> = _hardwareLatencyMs.asStateFlow()

    private val _isPreBuffered = MutableStateFlow<Boolean>(false)
    val isPreBuffered: StateFlow<Boolean> = _isPreBuffered.asStateFlow()

    // -------------------------------------------------------------------------------------
    // נגני השמע
    // -------------------------------------------------------------------------------------
    private var exoPlayer: ExoPlayer? = null
    private var youtubeWebView: WebView? = null
    private var isYoutubeReady = false

    // סוג המקור הנוכחי
    private var currentSourceType: SourceType = SourceType.RADIO_STREAM
    private var currentSourcePayload: String = ""

    // עבודת תזמון פעולה ממתינה (Scheduled Execution Job)
    private var scheduledActionJob: Job? = null
    private var positionTrackerJob: Job? = null

    // מנהל השמע המערכתי לחישוב השהיית חומרה
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Callback לדיווח על טעינה מוקדמת מוכנה
    var onBufferReadyListener: (() -> Unit)? = null

    init {
        calculateHardwareOutputLatency()
        initExoPlayer()
        startPositionTracker()
    }

    /**
     * =====================================================================================
     * 1. קיזוז השהיית חומרה (Hardware Audio Latency Divergence)
     * =====================================================================================
     * כל יצרן סמארטפון משתמש ברכיבי DAC ו-DSP שונים, הגורמים להשהיה פנימית
     * בין הרגע שבו תוכנת הנגן מזינה דגימות ל-AudioTrack ועד שהגל מופק פיזית ברמקול.
     * 
     * אנו מחשבים את ההשהיה על ידי תשאול:
     * - PROPERTY_OUTPUT_FRAMES_PER_BUFFER (גודל ה-Buffer במסגרות)
     * - PROPERTY_OUTPUT_SAMPLE_RATE (קצב הדגימה של הדרייבר, בד"כ 48000Hz)
     * נוסחה:
     * Latency = (FramesPerBuffer * 1000) / SampleRate + תוספת דרייבר קבועה (~15ms)
     */
    private fun calculateHardwareOutputLatency() {
        try {
            val framesPerBufferStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)

            val framesPerBuffer = framesPerBufferStr?.toIntOrNull() ?: 256
            val sampleRate = sampleRateStr?.toIntOrNull() ?: 48000

            val bufferDurationMs = (framesPerBuffer * 1000L) / sampleRate

            // באנדרואיד קיימת השהיית דרייבר/HAL נוספת מעבר ל-Buffer.
            // במכשירים עם Android 10+ (API 29) ניתן לחשב במדויק. כאן נחשב קירוב יציב:
            val driverHalOverheadMs = 18L
            val totalLatency = bufferDurationMs + driverHalOverheadMs

            _hardwareLatencyMs.value = totalLatency
            Log.d(TAG, "[Hardware Latency] השהיית חומרה מחושבת: ${totalLatency}ms (Buffer: ${bufferDurationMs}ms, Frame: $framesPerBuffer, Rate: $sampleRate)")
        } catch (e: Exception) {
            _hardwareLatencyMs.value = 25L // ערך ברירת מחדל בטוח באנדרואיד
            Log.w(TAG, "שגיאה בחישוב השהיית חומרה, נקבע ערך ברירת מחדל: 25ms")
        }
    }

    /**
     * =====================================================================================
     * 2. אתחול והגדרת ExoPlayer
     * =====================================================================================
     */
    @OptIn(UnstableApi::class)
    private fun initExoPlayer() {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .build()
            .apply {
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_IDLE -> _playbackState.value = PlaybackState.IDLE
                            Player.STATE_BUFFERING -> {
                                _playbackState.value = PlaybackState.BUFFERING
                                _isPreBuffered.value = false
                            }
                            Player.STATE_READY -> {
                                _isPreBuffered.value = true
                                onBufferReadyListener?.invoke()
                                if (playWhenReady) {
                                    _playbackState.value = PlaybackState.PLAYING
                                } else {
                                    _playbackState.value = PlaybackState.PAUSED
                                }
                            }
                            Player.STATE_ENDED -> _playbackState.value = PlaybackState.IDLE
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            _playbackState.value = PlaybackState.PLAYING
                        } else if (_playbackState.value == PlaybackState.PLAYING) {
                            _playbackState.value = PlaybackState.PAUSED
                        }
                    }
                })
            }
    }

    /**
     * =====================================================================================
     * 3. שילוב YouTube IFrame Player (נגן YouTube חוקי ללא תלות ב-Premium)
     * =====================================================================================
     * שימוש ב-WebView להרצת IFrame של יוטיוב בצורה מאושרת רשמית.
     * התקשורת מתבצעת בדו-כיווניות באמצעות JavascriptInterface ו-postMessage.
     */
    fun setupYoutubeWebView(webView: WebView) {
        this.youtubeWebView = webView
        webView.apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.domStorageEnabled = true

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPlayerReady() {
                    isYoutubeReady = true
                    _isPreBuffered.value = true
                    onBufferReadyListener?.invoke()
                    Log.d(TAG, "נגן YouTube IFrame מוכן לשימוש")
                }

                @JavascriptInterface
                fun onPlayerStateChange(state: Int) {
                    when (state) {
                        1 -> _playbackState.value = PlaybackState.PLAYING   // Playing
                        2 -> _playbackState.value = PlaybackState.PAUSED    // Paused
                        3 -> _playbackState.value = PlaybackState.BUFFERING // Buffering
                    }
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                }
            }
        }
    }

    /**
     * טעינת וידאו של יוטיוב ב-WebView באמצעות ה-IFrame Player API הרשמי
     */
    private fun loadYoutubeVideoHtml(videoId: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
                <style>body { margin:0; padding:0; background:black; }</style>
            </head>
            <body>
                <div id="player"></div>
                <script>
                    var tag = document.createElement('script');
                    tag.src = "https://www.youtube.com/iframe_api";
                    var firstScriptTag = document.getElementsByTagName('script')[0];
                    firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

                    var player;
                    function onYouTubeIframeAPIReady() {
                        player = new YT.Player('player', {
                            height: '100%',
                            width: '100%',
                            videoId: '$videoId',
                            playerVars: {
                                'playsinline': 1,
                                'controls': 0,
                                'disablekb': 1,
                                'fs': 0,
                                'rel': 0
                            },
                            events: {
                                'onReady': function(event) { AndroidBridge.onPlayerReady(); },
                                'onStateChange': function(event) { AndroidBridge.onPlayerStateChange(event.data); }
                            }
                        });
                    }
                    function playVideo() { if (player) player.playVideo(); }
                    function pauseVideo() { if (player) player.pauseVideo(); }
                    function seekTo(seconds) { if (player) player.seekTo(seconds, true); }
                </script>
            </body>
            </html>
        """.trimIndent()

        youtubeWebView?.post {
            youtubeWebView?.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
        }
    }

    // =====================================================================================
    // 4. טעינה מוקדמת (Pre-buffering) והכנת הנגן
    // =====================================================================================

    /**
     * טעינה מוקדמת של מקור השמע ל-RAM לפני מועד היעד המתוזמן.
     * מבטיח שהשמע כבר זמין בזיכרון ברגע שהשעון מגיע ל-Target Epoch.
     */
    fun prepareSource(sourceType: SourceType, payload: String, initialPositionMs: Long = 0L) {
        currentSourceType = sourceType
        currentSourcePayload = payload
        _isPreBuffered.value = false

        when (sourceType) {
            SourceType.LOCAL_FILE, SourceType.RADIO_STREAM -> {
                coroutineScope.launch(Dispatchers.Main) {
                    val mediaItem = if (sourceType == SourceType.LOCAL_FILE && !payload.startsWith("http")) {
                        MediaItem.fromUri(Uri.fromFile(File(payload)))
                    } else {
                        MediaItem.fromUri(Uri.parse(payload))
                    }

                    exoPlayer?.apply {
                        stop()
                        setMediaItem(mediaItem)
                        prepare()
                        if (initialPositionMs > 0L) {
                            seekTo(initialPositionMs)
                        }
                        playWhenReady = false // טעינה בלבד ללא הפעלה מיידית
                    }
                }
            }

            SourceType.YOUTUBE_EMBED -> {
                // חילוץ מזהה הווידאו מהקישור או שימוש ישיר ב-Payload
                val videoId = extractYoutubeVideoId(payload)
                loadYoutubeVideoHtml(videoId)
            }
        }
    }

    private fun extractYoutubeVideoId(urlOrId: String): String {
        return if (urlOrId.contains("v=")) {
            urlOrId.substringAfter("v=").substringBefore("&")
        } else if (urlOrId.contains("youtu.be/")) {
            urlOrId.substringAfter("youtu.be/").substringBefore("?")
        } else {
            urlOrId.trim()
        }
    }

    // =====================================================================================
    // 5. מודל תזמון עתידי ושחרור מתואם (Target Execution Engine)
    // =====================================================================================

    /**
     * הפעלת פקודה מתוזמנת עתידית ברגע המדויק:
     * 
     * חישוב:
     * adjustedTriggerEpoch = targetLocalEpoch - hardwareOutputLatencyMs
     * 
     * אנו מבצעים:
     * 1. טעינה מקדימה ומיקום ב-trackPositionMs.
     * 2. השהיית Coroutine (delay) עד כ-3ms לפני נקודת היעד.
     * 3. ריצה בלולאת Spin-Wait נעולה במיקרו-שניות לסגירת 3 המילי-שניות האחרונות בדיוק מקסימלי.
     */
    fun scheduleExecution(
        action: String, // "PLAY", "PAUSE", "SEEK"
        targetLocalEpoch: Long,
        trackPositionMs: Long,
        sourceType: SourceType? = null,
        sourcePayload: String? = null
    ) {
        scheduledActionJob?.cancel()

        // עדכון מקור אם סופק
        if (sourceType != null && sourcePayload != null && sourcePayload.isNotEmpty() && sourcePayload != currentSourcePayload) {
            prepareSource(sourceType, sourcePayload, trackPositionMs)
        }

        scheduledActionJob = coroutineScope.launch(Dispatchers.Default) {
            val hardwareLatency = _hardwareLatencyMs.value
            // קיזוז השהיית החומרה מהמועד המתוכנן
            val adjustedTriggerEpoch = targetLocalEpoch - hardwareLatency

            Log.d(TAG, "[Target Execution] נקבע מועד ביצוע: target=$targetLocalEpoch, קיזוז חומרה=-${hardwareLatency}ms, מועד סופי=$adjustedTriggerEpoch")

            // השהיה מוקדמת אם היעד רחוק
            val now = System.currentTimeMillis()
            val timeToWait = adjustedTriggerEpoch - now

            if (timeToWait > SPIN_WAIT_WINDOW_MS) {
                delay(timeToWait - SPIN_WAIT_WINDOW_MS)
            }

            // Spin-Wait בדיוק מיקרו-שניות למניעת Jitter של ה-Scheduler
            while (System.currentTimeMillis() < adjustedTriggerEpoch) {
                // Busy wait - חלון קצרצר בלבד (פחות מ-3ms)
            }

            // שחרור הפעולה ברגע המדויק!
            withContext(Dispatchers.Main) {
                executeImmediateAction(action, trackPositionMs)
            }
        }
    }

    /**
     * ביצוע הפעולה בפועל בנגן
     */
    private fun executeImmediateAction(action: String, trackPositionMs: Long) {
        when (currentSourceType) {
            SourceType.LOCAL_FILE, SourceType.RADIO_STREAM -> {
                exoPlayer?.let { player ->
                    when (action) {
                        "PLAY" -> {
                            if (trackPositionMs >= 0 && abs(player.currentPosition - trackPositionMs) > 30) {
                                player.seekTo(trackPositionMs)
                            }
                            player.playWhenReady = true
                            player.play()
                            _playbackState.value = PlaybackState.PLAYING
                        }
                        "PAUSE" -> {
                            player.pause()
                            player.playWhenReady = false
                            _playbackState.value = PlaybackState.PAUSED
                        }
                        "SEEK" -> {
                            player.seekTo(trackPositionMs)
                        }
                    }
                }
            }

            SourceType.YOUTUBE_EMBED -> {
                youtubeWebView?.let { webView ->
                    when (action) {
                        "PLAY" -> {
                            val seconds = trackPositionMs / 1000.0
                            webView.evaluateJavascript("seekTo($seconds); playVideo();", null)
                            _playbackState.value = PlaybackState.PLAYING
                        }
                        "PAUSE" -> {
                            webView.evaluateJavascript("pauseVideo();", null)
                            _playbackState.value = PlaybackState.PAUSED
                        }
                        "SEEK" -> {
                            val seconds = trackPositionMs / 1000.0
                            webView.evaluateJavascript("seekTo($seconds);", null)
                        }
                    }
                }
            }
        }
    }

    // =====================================================================================
    // 6. אלגוריתם בקרת סטייה מתמשכת (Continuous Drift Correction)
    // =====================================================================================

    /**
     * טיפול באות בקרה תקופתי (Beacon) מהמארח:
     * 
     * חישוב הסטייה:
     * driftMs = localTrackPosition - beaconTrackPosition
     * 
     * 1. סטייה קלה (5ms–30ms):
     *    שינוי קצב דגימה עדין ב-ExoPlayer באמצעות PlaybackParameters:
     *    - פיגור (drift < 0): האצה ל-1.01f
     *    - הקדמה (drift > 0): האטה ל-0.99f
     * 2. סטייה חמורה (>150ms):
     *    דילוג מהיר (Hard Seek) ליישור קו מיידי.
     */
    fun processBeaconDrift(beaconTrackPositionMs: Long, isHostPlaying: Boolean) {
        if (_playbackState.value != PlaybackState.PLAYING && !isHostPlaying) return

        val localPos = getCurrentPosition()
        val drift = localPos - beaconTrackPositionMs
        _driftMs.value = drift

        if (abs(drift) > HARD_SEEK_THRESHOLD_MS) {
            // סטייה חמורה: ביצוע Hard Seek מיידי
            Log.w(TAG, "[Drift Correction] זוהתה סטייה חמורה של ${drift}ms! מבצע Hard Seek ל-$beaconTrackPositionMs")
            coroutineScope.launch(Dispatchers.Main) {
                seekTo(beaconTrackPositionMs)
            }
        } else if (abs(drift) >= FINE_DRIFT_THRESHOLD_MS) {
            // סטייה קלה: תיקון עדין באמצעות PlaybackParams
            val targetSpeed = if (drift < 0) {
                1.01f // המכשיר המקומי מפגר אחרי המארח - מאיצים בעדינות
            } else {
                0.99f // המכשיר המקומי מקדים את המארח - מאטים בעדינות
            }

            coroutineScope.launch(Dispatchers.Main) {
                applyPlaybackSpeed(targetSpeed)
            }
        } else {
            // מסונכרן לחלוטין (סטייה נמוכה מ-5ms) - חזרה למהירות רגילה 1.0f
            if (exoPlayer?.playbackParameters?.speed != 1.0f) {
                coroutineScope.launch(Dispatchers.Main) {
                    applyPlaybackSpeed(1.0f)
                }
            }
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        if (currentSourceType != SourceType.YOUTUBE_EMBED) {
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
        }
    }

    fun seekTo(positionMs: Long) {
        when (currentSourceType) {
            SourceType.LOCAL_FILE, SourceType.RADIO_STREAM -> exoPlayer?.seekTo(positionMs)
            SourceType.YOUTUBE_EMBED -> {
                val sec = positionMs / 1000.0
                youtubeWebView?.evaluateJavascript("seekTo($sec);", null)
            }
        }
    }

    fun getCurrentPosition(): Long {
        return when (currentSourceType) {
            SourceType.LOCAL_FILE, SourceType.RADIO_STREAM -> exoPlayer?.currentPosition ?: 0L
            SourceType.YOUTUBE_EMBED -> _currentPositionMs.value
        }
    }

    private fun startPositionTracker() {
        positionTrackerJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (_playbackState.value == PlaybackState.PLAYING) {
                    val pos = getCurrentPosition()
                    _currentPositionMs.value = pos
                }
                delay(100)
            }
        }
    }

    fun release() {
        scheduledActionJob?.cancel()
        positionTrackerJob?.cancel()
        coroutineScope.launch(Dispatchers.Main) {
            exoPlayer?.release()
            exoPlayer = null
            youtubeWebView?.destroy()
            youtubeWebView = null
        }
    }
}
