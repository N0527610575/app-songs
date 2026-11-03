package com.audiosync.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * =========================================================================================
 * מודלי נתונים וחוזי תקשורת JSON - Multi-Device Audio Sync
 * =========================================================================================
 * 
 * קובץ זה מרכז את כל ישויות המערכת, ה-Enums ומבני ההודעות המועברים ברשת.
 * השימוש בספריית kotlinx.serialization מבטיח ביצועים אופטימליים, ללא שימוש
 * ב-Reflection כבד שעלול לגרום ל-GC Pause ולפגוע בסנכרון ברמת המילי-שנייה.
 */

/**
 * תצורת הסריאליזציה הגלובלית של המערכת:
 * - ignoreUnknownKeys: מאפשר גמישות קדימה בהוספת שדות עתידיים ללא קריסת לקוחות ישנים.
 * - isLenient: התמודדות עם הבדלי תחביר קלים ב-JSON.
 * - encodeDefaults: הבטחת שליחת כלל השדות הנחוצים למניעת ערכי null לא צפויים ברשת.
 */
val SyncJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * אופני פעולה של חדר סנכרון:
 * 1. LOCAL_P2P - הגברת עוצמה מקומית (Acoustic Amplification Mode) ברשת ה-LAN דרך mDNS ו-UDP.
 * 2. GLOBAL_DIRECT - חדר האזנה וירטואלי גלובלי עם קוד ייעודי בן 6 תווים דרך WebSocket.
 * 3. RANDOM_MATCH - שידוך אקראי גלובלי למשתמשים המחפשים שותפי האזנה ברחבי העולם.
 */
@Serializable
enum class SyncMode {
    @SerialName("LOCAL_P2P")
    LOCAL_P2P,

    @SerialName("GLOBAL_DIRECT")
    GLOBAL_DIRECT,

    @SerialName("RANDOM_MATCH")
    RANDOM_MATCH
}

/**
 * סוגי מקורות התוכן הנתמכים:
 * - LOCAL_FILE: קובץ שמע מקומי המאוחסן במכשיר המארח (מוזרם לשאר ב-HTTP זעיר).
 * - RADIO_STREAM: כתובת רדיו מקוונת (Icecast/HLS/Shoutcast).
 * - YOUTUBE_EMBED: נגן YouTube מוטמע חוקי דרך IFrame Player API.
 */
@Serializable
enum class SourceType {
    @SerialName("LOCAL_FILE")
    LOCAL_FILE,

    @SerialName("RADIO_STREAM")
    RADIO_STREAM,

    @SerialName("YOUTUBE_EMBED")
    YOUTUBE_EMBED
}

/**
 * מצב נגן השמע הנוכחי במערכת
 */
@Serializable
enum class PlaybackState {
    @SerialName("IDLE")
    IDLE,

    @SerialName("BUFFERING")
    BUFFERING,

    @SerialName("PLAYING")
    PLAYING,

    @SerialName("PAUSED")
    PAUSED
}

/**
 * מצב ה-Buffer של עמית עבור תיאום טעינה מוקדמת (Pre-buffering)
 */
@Serializable
enum class PeerBufferState {
    @SerialName("LOADING")
    LOADING,

    @SerialName("READY")
    READY
}

/**
 * סוגי פעולות משתמש לשכבת ה-Optimistic Interceptor
 */
@Serializable
enum class ActionType {
    @SerialName("PLAY")
    PLAY,

    @SerialName("PAUSE")
    PAUSE,

    @SerialName("SEEK")
    SEEK
}

/**
 * ישות חדר (RoomEntity)
 * מייצגת את מצב החדר המלא בזיכרון המכשיר או בשרת.
 * 
 * @param roomId קוד החדר בן 6 תווים (למשל: "7K9X2A") או מזהה מקומי לרשת ה-LAN
 * @param hostId מזהה העמית המשמש כמארח החדר והמקור לשעון האבסולוטי
 * @param mode אופן הפעולה (P2P מקומי / חדר גלובלי / שידוך אקראי)
 * @param sourceType סוג מקור השמע
 * @param sourcePayload נתיב הקובץ המקומי, כתובת הרדיו באינטרנט או מזהה הווידאו של YouTube
 * @param state מצב הנגינה הנוכחי (IDLE, BUFFERING, PLAYING, PAUSED)
 * @param currentTrackTimeMs מיקום הרצועה במילי-שניות בעת הסנכרון האחרון
 * @param lastSyncEpoch חותמת זמן אבסולוטית (Epoch MS) של עדכון המיקום האחרון
 * @param targetEpochMs חותמת זמן עתידית מתוכננת לביצוע הפעולה הבאה
 * @param peers רשימת העמיתים המחוברים לחדר
 */
@Serializable
data class RoomEntity(
    @SerialName("roomId") val roomId: String = "",
    @SerialName("hostId") val hostId: String = "",
    @SerialName("mode") val mode: SyncMode = SyncMode.GLOBAL_DIRECT,
    @SerialName("sourceType") val sourceType: SourceType = SourceType.RADIO_STREAM,
    @SerialName("sourcePayload") val sourcePayload: String = "",
    @SerialName("state") val state: PlaybackState = PlaybackState.IDLE,
    @SerialName("currentTrackTimeMs") val currentTrackTimeMs: Long = 0L,
    @SerialName("lastSyncEpoch") val lastSyncEpoch: Long = 0L,
    @SerialName("targetEpochMs") val targetEpochMs: Long = 0L,
    @SerialName("peers") val peers: List<PeerSession> = emptyList()
)

/**
 * ישות עמית (PeerSession)
 * מייצגת מידע על עמית מחובר (מקומי או מרוחק).
 * 
 * @param peerId מזהה ייחודי של העמית ברשת
 * @param roomId קוד החדר שאליו משויך העמית
 * @param calculatedOffsetMs היסט השעון המחושב ביחס למארח/שרת (θ)
 * @param lastRttMs זמן הלוך-חזור שנמדד בדגימה האחרונה (RTT)
 * @param bufferState האם העמית סיים טעינה מוקדמת (Pre-buffering) של מקטע השמע
 * @param hardwareOutputLatencyMs השהיית החומרה ודרייבר השמע של המכשיר הספציפי
 * @param ipAddress כתובת ה-IP ברשת המקומית (רלוונטי למצב LOCAL_P2P)
 * @param port פורט התקשורת של שירות ה-UDP המקומי
 */
@Serializable
data class PeerSession(
    @SerialName("peerId") val peerId: String,
    @SerialName("roomId") val roomId: String = "",
    @SerialName("calculatedOffsetMs") val calculatedOffsetMs: Long = 0L,
    @SerialName("lastRttMs") val lastRttMs: Long = 0L,
    @SerialName("bufferState") val bufferState: PeerBufferState = PeerBufferState.READY,
    @SerialName("hardwareOutputLatencyMs") val hardwareOutputLatencyMs: Long = 0L,
    @SerialName("ipAddress") val ipAddress: String = "",
    @SerialName("port") val port: Int = 0
)

/**
 * =========================================================================================
 * חוזי הודעות פרוטוקול התקשורת בזמן אמת (JSON Protocol Contracts)
 * =========================================================================================
 */

/**
 * הודעת בסיס גנרית לזיהוי מהיר של סוג ההודעה הנכנסת ברשת
 */
@Serializable
data class RawIncomingMessage(
    @SerialName("type") val type: String
)

/**
 * 1. הודעת Micro-NTP לסנכרון שעונים (סנכרון ברמת המילי-שנייה)
 * 
 * נוסחת אלגוריתם ה-NTP:
 * t0 = זמן שיגור הבקשה מהלקוח (שעון לקוח)
 * t1 = זמן קבלת הבקשה בשרת/מארח (שעון מארח)
 * t2 = זמן שיגור התשובה מהשרת/מארח (שעון מארח)
 * t3 = זמן קבלת התשובה בלקוח (שעון לקוח)
 * 
 * Round-Trip Time (RTT):
 * RTT = (t3 - t0) - (t2 - t1)
 * 
 * היסט השעון (Clock Offset θ):
 * θ = ((t1 - t0) + (t2 - t3)) / 2
 */
@Serializable
data class TimeSyncMessage(
    @SerialName("type") val type: String = "TIME_SYNC_REQ",
    @SerialName("peerId") val peerId: String = "",
    @SerialName("t0") val t0: Long = 0L,
    @SerialName("t1") val t1: Long = 0L,
    @SerialName("t2") val t2: Long = 0L,
    @SerialName("t3") val t3: Long = 0L
) {
    /**
     * חישוב היסט השעון המקומי מול השעון של המארח
     */
    fun calculateOffset(clientT3: Long): Long {
        val departureDiff = t1 - t0
        val arrivalDiff = t2 - clientT3
        return (departureDiff + arrivalDiff) / 2
    }

    /**
     * חישוב זמן הסיבוב המלא (Round Trip Time) נטו ללא זמן העיבוד במארח
     */
    fun calculateRtt(clientT3: Long): Long {
        val totalTransit = clientT3 - t0
        val hostProcessing = t2 - t1
        return (totalTransit - hostProcessing).coerceAtLeast(0L)
    }
}

/**
 * 2. בקשת פעולה משכבת ה-Optimistic Interceptor (לקוח -> מארח/שרת)
 * 
 * המשתמש לוחץ על Play/Pause/Seek, הממשק מציג חיווי מיידי "מסנכרן...",
 * והכוונה נשלחת לרשת עם חותמת הזמן של הלקוח.
 */
@Serializable
data class UserActionIntent(
    @SerialName("type") val type: String = "USER_ACTION_INTENT",
    @SerialName("action") val action: String, // "PLAY", "PAUSE", "SEEK"
    @SerialName("requested_position_ms") val requestedPositionMs: Long = 0L,
    @SerialName("client_epoch") val clientEpoch: Long = 0L,
    @SerialName("peerId") val peerId: String = "",
    @SerialName("sourcePayload") val sourcePayload: String? = null,
    @SerialName("sourceType") val sourceType: String? = null
)

/**
 * 3. פקודת ביצוע מתוזמנת עתידית (Target Execution Engine)
 * משודרת מהמארח/שרת לכלל הלקוחות בחדר.
 * 
 * חישוב מועד היעד:
 * Target Time = Current Epoch + max(RTT_1, ..., RTT_n) + Safety Buffer
 */
@Serializable
data class ExecuteScheduledAction(
    @SerialName("type") val type: String = "EXECUTE_SCHEDULED_ACTION",
    @SerialName("action") val action: String, // "PLAY", "PAUSE", "SEEK"
    @SerialName("target_epoch_ms") val targetEpochMs: Long,
    @SerialName("track_position_ms") val trackPositionMs: Long,
    @SerialName("sourcePayload") val sourcePayload: String? = null,
    @SerialName("sourceType") val sourceType: String? = null
)

/**
 * 4. שידור אות בקרה תקופתי (Beacon) מהמארח
 * משמש לבקרת סטייה מתמשכת (Continuous Drift Correction):
 * - סטייה קלה (5ms–30ms): תיקון שקוף ע"י PlaybackParams (0.99f / 1.01f).
 * - סטייה חמורה (>150ms): ביצוע Hard Seek.
 */
@Serializable
data class BeaconMessage(
    @SerialName("type") val type: String = "BEACON",
    @SerialName("hostEpochMs") val hostEpochMs: Long,
    @SerialName("track_position_ms") val trackPositionMs: Long,
    @SerialName("isPlaying") val isPlaying: Boolean,
    @SerialName("playbackSpeed") val playbackSpeed: Float = 1.0f
)

/**
 * 5. הודעת טעינה מוקדמת מוכנה (Pre-buffering Ready)
 */
@Serializable
data class BufferReadyMessage(
    @SerialName("type") val type: String = "BUFFER_READY",
    @SerialName("roomId") val roomId: String,
    @SerialName("peerId") val peerId: String,
    @SerialName("ready") val ready: Boolean = true
)

/**
 * 6. הודעות ניהול חדרים ב-Signaling Server
 */
@Serializable
data class CreateRoomMessage(
    @SerialName("type") val type: String = "CREATE_ROOM",
    @SerialName("roomId") val roomId: String = "",
    @SerialName("mode") val mode: String = "GLOBAL_DIRECT",
    @SerialName("sourceType") val sourceType: String = "RADIO_STREAM",
    @SerialName("sourcePayload") val sourcePayload: String = ""
)

@Serializable
data class JoinRoomMessage(
    @SerialName("type") val type: String = "JOIN_ROOM",
    @SerialName("roomId") val roomId: String
)

@Serializable
data class MatchmakeReqMessage(
    @SerialName("type") val type: String = "MATCHMAKE_REQ"
)

@Serializable
data class RoomResponsePayload(
    @SerialName("type") val type: String,
    @SerialName("roomId") val roomId: String = "",
    @SerialName("isHost") val isHost: Boolean = false,
    @SerialName("room") val room: RoomEntity? = null,
    @SerialName("message") val message: String = ""
)

@Serializable
data class PeerStatusNotification(
    @SerialName("type") val type: String,
    @SerialName("peerId") val peerId: String,
    @SerialName("totalPeers") val totalPeers: Int = 0
)

/**
 * פונקציות עזר להמרה מהירה ובטוחה של מחרוזות JSON לאובייקטים
 */
object SyncMessageSerializer {
    fun serialize(obj: Any): String {
        return when (obj) {
            is TimeSyncMessage -> SyncJson.encodeToString(obj)
            is UserActionIntent -> SyncJson.encodeToString(obj)
            is ExecuteScheduledAction -> SyncJson.encodeToString(obj)
            is BeaconMessage -> SyncJson.encodeToString(obj)
            is BufferReadyMessage -> SyncJson.encodeToString(obj)
            is CreateRoomMessage -> SyncJson.encodeToString(obj)
            is JoinRoomMessage -> SyncJson.encodeToString(obj)
            is MatchmakeReqMessage -> SyncJson.encodeToString(obj)
            is RoomEntity -> SyncJson.encodeToString(obj)
            else -> SyncJson.encodeToString(obj.toString())
        }
    }
}
