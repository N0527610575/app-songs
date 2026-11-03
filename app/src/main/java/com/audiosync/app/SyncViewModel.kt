package com.audiosync.app

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.math.abs

/**
 * =========================================================================================
 * מצב ממשק המשתמש (UI State של Jetpack Compose)
 * =========================================================================================
 */
data class SyncUiState(
    val roomId: String = "",
    val isHost: Boolean = false,
    val mode: SyncMode = SyncMode.GLOBAL_DIRECT,
    val sourceType: SourceType = SourceType.RADIO_STREAM,
    val sourcePayload: String = "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val currentPositionMs: Long = 0L,
    val driftMs: Long = 0L,
    val rttMs: Long = 0L,
    val clockOffsetMs: Long = 0L,
    val hardwareLatencyMs: Long = 0L,
    val isSynchronizing: Boolean = false,
    val statusBanner: String = "מוכן לפעולה",
    val connectedPeers: List<PeerSession> = emptyList(),
    val signalingServerUrl: String = "ws://10.0.2.2:8080" // ברירת מחדל ל-Android Emulator
)

/**
 * =========================================================================================
 * מודל התצוגה המרכזי ושכבת ה-Optimistic Interceptor (SyncViewModel)
 * =========================================================================================
 * 
 * מחלקה זו מנהלת את התיאום בין שכבת ה-UI, מנוע השמע (SyncedAudioPlayerEngine)
 * ורכז הרשת והשעונים (NetworkAndClockCoordinator).
 * 
 * תפקידיה המרכזיים:
 * 1. שכבת יירוט הפעולות (Optimistic Interceptor):
 *    - לכידת לחיצות משתמש (Play, Pause, Seek).
 *    - חסימת לחיצות כפולות (Debounce / Anti-Race Condition).
 *    - הצגת חיווי מיידי "מסנכרן עם החדר..." ושילוח הכוונה לרשת.
 * 2. ניהול מודל תזמון עתידי (Target Execution):
 *    - המארח מחשב Target Time ומפיץ לכולם.
 *    - המכשירים מפעילים טעינה מוקדמת ושחרור מתוזמן.
 * 3. שידור אותות Beacon תקופתיים מהמארח לצורך Continuous Drift Correction.
 * 4. טיפול במצטרפים מאוחרים (Late Joiners) והשלמת פקודות שפוספסו (Auto-Catchup).
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AudioSync_ViewModel"
        private const val BEACON_INTERVAL_MS = 1000L // שידור אות בקרה בכל שנייה
    }

    val coordinator = NetworkAndClockCoordinator(application.applicationContext, viewModelScope)
    val playerEngine = SyncedAudioPlayerEngine(application.applicationContext, viewModelScope)

    // מצב UI ראשי הנצפה ע"י Jetpack Compose
    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    // דגל נעילת פעולה אופטימית למניעת Race Conditions
    private var isActionPending = false

    // משימת שידור ה-Beacon התקופתי של המארח
    private var hostBeaconJob: Job? = null

    init {
        setupCoordinatorCallbacks()
        setupPlayerEngineCallbacks()
        bindStateFlows()
    }

    /**
     * חיבור ה-StateFlows של הרכיבים הפנימיים ל-State הראשי של ה-UI
     */
    private fun bindStateFlows() {
        viewModelScope.launch {
            combine(
                playerEngine.playbackState,
                playerEngine.currentPositionMs,
                playerEngine.driftMs,
                playerEngine.hardwareLatencyMs,
                coordinator.clockOffsetMs,
                coordinator.lastRttMs,
                coordinator.connectedPeers,
                coordinator.isHost,
                coordinator.currentRoomId
            ) { values ->
                val pState = values[0] as PlaybackState
                val pos = values[1] as Long
                val drift = values[2] as Long
                val hwLat = values[3] as Long
                val offset = values[4] as Long
                val rtt = values[5] as Long
                @Suppress("UNCHECKED_CAST")
                val peers = values[6] as List<PeerSession>
                val isHost = values[7] as Boolean
                val roomId = values[8] as String

                _uiState.update { current ->
                    current.copy(
                        playbackState = pState,
                        currentPositionMs = pos,
                        driftMs = drift,
                        hardwareLatencyMs = hwLat,
                        clockOffsetMs = offset,
                        rttMs = rtt,
                        connectedPeers = peers,
                        isHost = isHost,
                        roomId = if (roomId.isNotEmpty()) roomId else current.roomId
                    )
                }
            }.collect()
        }
    }

    /**
     * הגדרת Callbacks מרכז הרשת
     */
    private fun setupCoordinatorCallbacks() {
        // קבלת פקודת ביצוע מתוזמנת
        coordinator.onScheduledActionReceived = { scheduledAction ->
            handleExecuteScheduledAction(scheduledAction)
        }

        // קבלת אות בקרה (Beacon) מהמארח
        coordinator.onBeaconReceived = { beacon ->
            handleBeaconReceived(beacon)
        }

        // קבלת כוונת משתמש (במכשיר המארח)
        coordinator.onActionIntentReceived = { intent ->
            handleIncomingUserActionIntent(intent)
        }

        // הצטרפות או יצירת חדר בהצלחה (כולל Late Joiners)
        coordinator.onRoomJoined = { roomEntity, isHost ->
            handleRoomJoined(roomEntity, isHost)
        }

        // עמית מדווח על סיום טעינה מוקדמת
        coordinator.onBufferReadyReceived = { bufferReadyMsg ->
            Log.d(TAG, "עמית ${bufferReadyMsg.peerId} סיים Pre-buffering")
        }
    }

    /**
     * הגדרת Callbacks ממנוע השמע
     */
    private fun setupPlayerEngineCallbacks() {
        // דיווח על סיום טעינה מוקדמת במכשיר המקומי
        playerEngine.onBufferReadyListener = {
            val roomId = _uiState.value.roomId
            if (roomId.isNotEmpty()) {
                val bufferReadyMsg = BufferReadyMessage(
                    type = "BUFFER_READY",
                    roomId = roomId,
                    peerId = coordinator.localPeerId,
                    ready = true
                )
                dispatchMessageToNetwork(SyncMessageSerializer.serialize(bufferReadyMsg))
            }
        }
    }

    // =====================================================================================
    // 1. שכבת יירוט הפעולות (Optimistic Interceptor Layer)
    // =====================================================================================

    /**
     * לחיצה על Play/Pause/Seek בממשק:
     * - חסימת לחיצות כפולות (Debounce).
     * - הצגת חיווי מיידי ("מסנכרן עם החדר...").
     * - שיגור הכוונה לרשת.
     */
    fun onUserActionClicked(action: ActionType, targetPositionMs: Long? = null) {
        if (isActionPending) {
            Log.d(TAG, "[Optimistic Interceptor] לחיצה כפולה נחסמה למניעת Race Condition")
            return
        }
        isActionPending = true

        val pos = targetPositionMs ?: playerEngine.getCurrentPosition()

        // 1. חיווי מצב ביניים מיידי למשתמש
        _uiState.update {
            it.copy(
                isSynchronizing = true,
                statusBanner = "מסנכרן עם החדר..."
            )
        }

        // 2. שילוח כוונת הפעולה לרשת באפס השהיה
        val intent = UserActionIntent(
            type = "USER_ACTION_INTENT",
            action = action.name,
            requestedPositionMs = pos,
            clientEpoch = System.currentTimeMillis(),
            peerId = coordinator.localPeerId,
            sourcePayload = _uiState.value.sourcePayload,
            sourceType = _uiState.value.sourceType.name
        )

        val isHost = _uiState.value.isHost
        if (isHost) {
            // אם המכשיר הנוכחי הוא המארח - מחשב מיד את מועד היעד ומשדר לכולם
            scheduleAndBroadcastAction(
                action = action.name,
                positionMs = pos,
                sourceType = _uiState.value.sourceType,
                sourcePayload = _uiState.value.sourcePayload
            )
        } else {
            // עמית רגיל - שולח את הכוונה למארח/שרת
            dispatchMessageToNetwork(SyncMessageSerializer.serialize(intent))
        }

        // שחרור נעילת הפעולה לאחר מרווח קצר
        viewModelScope.launch {
            delay(400)
            isActionPending = false
        }
    }

    /**
     * המארח מחשב את מועד היעד ומשדר לכל חברי החדר
     */
    private fun scheduleAndBroadcastAction(
        action: String,
        positionMs: Long,
        sourceType: SourceType,
        sourcePayload: String
    ) {
        val targetEpochMs = coordinator.calculateTargetEpoch(_uiState.value.connectedPeers)

        val scheduledAction = ExecuteScheduledAction(
            type = "EXECUTE_SCHEDULED_ACTION",
            action = action,
            target_epoch_ms = targetEpochMs,
            track_position_ms = positionMs,
            sourcePayload = sourcePayload,
            sourceType = sourceType.name
        )

        val messageJson = SyncMessageSerializer.serialize(scheduledAction)

        // שליחה כפולה (Redundant Messaging) למניעת איבודי חבילות ב-UDP/רשת אלחוטית
        dispatchMessageToNetwork(messageJson)
        viewModelScope.launch {
            delay(20)
            dispatchMessageToNetwork(messageJson)
        }

        // ביצוע גם אצל המארח עצמו
        handleExecuteScheduledAction(scheduledAction)

        // ניהול משימת שידור ה-Beacon התקופתי של המארח
        if (action == "PLAY") {
            startHostBeaconEmitter()
        } else {
            stopHostBeaconEmitter()
        }
    }

    /**
     * טיפול בפקודת ביצוע מתוזמנת שהתקבלה מהמארח/שרת
     */
    private fun handleExecuteScheduledAction(action: ExecuteScheduledAction) {
        val targetLocalEpoch = coordinator.hostEpochToLocalEpoch(action.target_epoch_ms)
        val sType = action.sourceType?.let { runCatching { SourceType.valueOf(it) }.getOrNull() } ?: _uiState.value.sourceType
        val sPayload = action.sourcePayload ?: _uiState.value.sourcePayload

        Log.d(TAG, "[Target Execution] נקלטה פקודת ${action.action}: targetEpoch=${action.target_epoch_ms}, localTarget=$targetLocalEpoch, pos=${action.track_position_ms}")

        playerEngine.scheduleExecution(
            action = action.action,
            targetLocalEpoch = targetLocalEpoch,
            trackPositionMs = action.track_position_ms,
            sourceType = sType,
            sourcePayload = sPayload
        )

        _uiState.update {
            it.copy(
                isSynchronizing = false,
                statusBanner = "מסונכרן עם החדר"
            )
        }
    }

    /**
     * המארח מקבל כוונת פעולה מאחד העמיתים בחדר
     */
    private fun handleIncomingUserActionIntent(intent: UserActionIntent) {
        if (!_uiState.value.isHost) return
        val sType = intent.sourceType?.let { runCatching { SourceType.valueOf(it) }.getOrNull() } ?: _uiState.value.sourceType
        val sPayload = intent.sourcePayload ?: _uiState.value.sourcePayload

        scheduleAndBroadcastAction(
            action = intent.action,
            positionMs = intent.requestedPositionMs,
            sourceType = sType,
            sourcePayload = sPayload
        )
    }

    // =====================================================================================
    // 2. אותות Beacon ובקרת סטייה מתמשכת (Continuous Drift Correction)
    // =====================================================================================

    private fun startHostBeaconEmitter() {
        hostBeaconJob?.cancel()
        hostBeaconJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(BEACON_INTERVAL_MS)
                if (_uiState.value.playbackState == PlaybackState.PLAYING) {
                    val beacon = BeaconMessage(
                        type = "BEACON",
                        hostEpochMs = coordinator.getEstimatedHostEpoch(),
                        track_position_ms = playerEngine.getCurrentPosition(),
                        isPlaying = true,
                        playbackSpeed = 1.0f
                    )
                    dispatchMessageToNetwork(SyncMessageSerializer.serialize(beacon))
                }
            }
        }
    }

    private fun stopHostBeaconEmitter() {
        hostBeaconJob?.cancel()
        hostBeaconJob = null
    }

    /**
     * טיפול ב-Beacon אצל העמיתים:
     * 1. הפעלת מנגנון תיקון הסטייה (Continuous Drift Correction).
     * 2. Auto-Catchup: אם העמית פוספס עקב אובדן חבילת פקודה והוא במצב PAUSED בזמן שהמארח מנגן.
     */
    private fun handleBeaconReceived(beacon: BeaconMessage) {
        if (_uiState.value.isHost) return

        // בדיקת Auto-Catchup במקרה של Packet Drop
        if (beacon.isPlaying && _uiState.value.playbackState != PlaybackState.PLAYING) {
            Log.w(TAG, "[Auto-Catchup] זוהה חוסר התאמה במצב הנגינה בעקבות Beacon! מבצע הצטרפות מיידית...")
            playerEngine.seekTo(beacon.track_position_ms)
            onUserActionClicked(ActionType.PLAY, beacon.track_position_ms)
            return
        }

        playerEngine.processBeaconDrift(beacon.track_position_ms, beacon.isPlaying)
    }

    // =====================================================================================
    // 3. טיפול במצטרפים מאוחרים (Late Joiners)
    // =====================================================================================

    private fun handleRoomJoined(room: RoomEntity, isHost: Boolean) {
        _uiState.update {
            it.copy(
                roomId = room.roomId,
                isHost = isHost,
                mode = room.mode,
                sourceType = room.sourceType,
                sourcePayload = room.sourcePayload,
                statusBanner = if (isHost) "מארח החדר (${room.roomId})" else "מחובר לחדר (${room.roomId})"
            )
        }

        // טעינה מקדימה של מקור השמע
        playerEngine.prepareSource(room.sourceType, room.sourcePayload, room.currentTrackTimeMs)

        // טיפול ב-Late Joiner: אם השיר כבר מתנגן בחדר
        if (room.state == PlaybackState.PLAYING && !isHost) {
            val now = coordinator.getEstimatedHostEpoch()
            val timePassedSinceSync = if (room.lastSyncEpoch > 0L) (now - room.lastSyncEpoch).coerceAtLeast(0L) else 0L
            val estimatedCurrentPos = room.currentTrackTimeMs + timePassedSinceSync

            Log.d(TAG, "[Late Joiner] מצטרף לחדר פעיל: מיקום מחושב = ${estimatedCurrentPos}ms (הפרש=${timePassedSinceSync}ms)")
            playerEngine.seekTo(estimatedCurrentPos)

            // תזמון השתלבות שקטה בסנכרון הבא
            viewModelScope.launch {
                delay(150)
                playerEngine.scheduleExecution(
                    action = "PLAY",
                    targetLocalEpoch = System.currentTimeMillis() + 200,
                    trackPositionMs = estimatedCurrentPos + 200,
                    sourceType = room.sourceType,
                    sourcePayload = room.sourcePayload
                )
            }
        }
    }

    // =====================================================================================
    // 4. ניהול חדרים ומצבי פעולה (P2P LAN / Global Room / Matchmaking)
    // =====================================================================================

    fun setMode(mode: SyncMode) {
        _uiState.update { it.copy(mode = mode) }
        when (mode) {
            SyncMode.LOCAL_P2P -> {
                coordinator.startHostingLanService()
                coordinator.startDiscovery()
            }
            SyncMode.GLOBAL_DIRECT, SyncMode.RANDOM_MATCH -> {
                coordinator.connectToSignalingServer(_uiState.value.signalingServerUrl)
            }
        }
    }

    fun setSource(sourceType: SourceType, payload: String) {
        _uiState.update {
            it.copy(sourceType = sourceType, sourcePayload = payload)
        }

        // אם מדובר בקובץ מקומי במכשיר המארח, נפעיל שרת הזרמת HTTP מקומי
        if (sourceType == SourceType.LOCAL_FILE && !payload.startsWith("http")) {
            val file = File(payload)
            if (file.exists()) {
                val streamUrl = coordinator.startLocalHttpAudioServer(file)
                if (streamUrl != null) {
                    _uiState.update { it.copy(sourcePayload = streamUrl) }
                    playerEngine.prepareSource(SourceType.LOCAL_FILE, streamUrl)
                    return
                }
            }
        }

        playerEngine.prepareSource(sourceType, payload)
    }

    fun createGlobalRoom(code: String = "") {
        setMode(SyncMode.GLOBAL_DIRECT)
        val msg = CreateRoomMessage(
            roomId = code,
            mode = "GLOBAL_DIRECT",
            sourceType = _uiState.value.sourceType.name,
            sourcePayload = _uiState.value.sourcePayload
        )
        coordinator.sendWebSocketMessage(SyncMessageSerializer.serialize(msg))
    }

    fun joinGlobalRoom(code: String) {
        setMode(SyncMode.GLOBAL_DIRECT)
        val msg = JoinRoomMessage(roomId = code.trim().toUpperCase())
        coordinator.sendWebSocketMessage(SyncMessageSerializer.serialize(msg))
    }

    fun startRandomMatchmaking() {
        setMode(SyncMode.RANDOM_MATCH)
        val msg = MatchmakeReqMessage()
        coordinator.sendWebSocketMessage(SyncMessageSerializer.serialize(msg))
        _uiState.update { it.copy(statusBanner = "מחפש שותף להאזנה ברחבי העולם...") }
    }

    fun updateSignalingServerUrl(url: String) {
        _uiState.update { it.copy(signalingServerUrl = url) }
        coordinator.connectToSignalingServer(url)
    }

    private fun dispatchMessageToNetwork(jsonString: String) {
        when (_uiState.value.mode) {
            SyncMode.LOCAL_P2P -> {
                coordinator.sendUdpMessage(jsonString)
            }
            SyncMode.GLOBAL_DIRECT, SyncMode.RANDOM_MATCH -> {
                coordinator.sendWebSocketMessage(jsonString)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        hostBeaconJob?.cancel()
        playerEngine.release()
        coordinator.destroy()
    }
}
