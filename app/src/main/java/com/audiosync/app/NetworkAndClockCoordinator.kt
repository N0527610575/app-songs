package com.audiosync.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * =========================================================================================
 * רכז הרשת וסנכרון השעונים (Network & Clock Coordinator)
 * =========================================================================================
 * 
 * מחלקה זו היא הלב של מערך התקשורת וסנכרון הזמן המבוזר:
 * 1. מנוע Micro-NTP: מחשב היסט שעון מדויק (θ) ו-RTT על פני סינון דגימות סטטיסטי למניעת Jitter.
 * 2. תקשורת היברידית מקומית (LAN P2P):
 *    - גילוי מכשירים באמצעות Android NSD (mDNS).
 *    - שרת HTTP זעיר מובנה במכשיר המארח להזרמת קובצי שמע מקומיים לשאר המכשירים ב-Wi-Fi.
 *    - ערוץ פקודות מהיר ב-UDP Socket להשהיה של 1-4ms בלבד.
 * 3. תקשורת גלובלית (WAN Relay):
 *    - ערוץ WebSocket מתמשך מול שרת ה-Signaling עבור חדרים גלובליים ו-Matchmaking.
 * 4. חישוב נקודת יעד אבסולוטית: Target Time = Current Epoch + max(RTT_1..n) + Safety Buffer.
 */
class NetworkAndClockCoordinator(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioSync_NetClock"
        const val NSD_SERVICE_TYPE = "_audiosync._tcp."
        const val DEFAULT_HTTP_STREAM_PORT = 8990
        const val DEFAULT_UDP_COMMAND_PORT = 8991
        const val SAFETY_BUFFER_MS = 350L // מרווח ביטחון לטעינה מוקדמת ותזמון אבסולוטי
        const val NTP_SAMPLE_COUNT = 6   // מספר דגימות NTP לסינון סטיות רשת
    }

    // מזהה המכשיר המקומי ברשת
    val localPeerId: String = "android_" + (1000..9999).random()

    // -------------------------------------------------------------------------------------
    // מצבי סנכרון ושעון (StateFlows לתצוגה בממשק)
    // -------------------------------------------------------------------------------------
    private val _clockOffsetMs = MutableStateFlow<Long>(0L)
    val clockOffsetMs: StateFlow<Long> = _clockOffsetMs.asStateFlow()

    private val _lastRttMs = MutableStateFlow<Long>(0L)
    val lastRttMs: StateFlow<Long> = _lastRttMs.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerSession>>(emptyList())
    val connectedPeers: StateFlow<List<PeerSession>> = _connectedPeers.asStateFlow()

    private val _isHost = MutableStateFlow<Boolean>(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _currentRoomId = MutableStateFlow<String>("")
    val currentRoomId: StateFlow<String> = _currentRoomId.asStateFlow()

    // מאגר עמיתים פעילים במכשיר המארח
    private val activePeersMap = ConcurrentHashMap<String, PeerSession>()

    // -------------------------------------------------------------------------------------
    // רכיבי תקשורת LAN (NSD, MulticastLock, UDP Socket, HTTP Server)
    // -------------------------------------------------------------------------------------
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var udpSocket: DatagramSocket? = null
    private var udpReceiverJob: Job? = null

    private var localHttpServer: HttpServer? = null
    private var currentLocalAudioFile: File? = null

    // -------------------------------------------------------------------------------------
    // רכיבי תקשורת WAN (OkHttp WebSocket)
    // -------------------------------------------------------------------------------------
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private var activeWebSocket: WebSocket? = null

    // -------------------------------------------------------------------------------------
    // Callbacks לטיפול בפקודות שהתקבלו ברשת
    // -------------------------------------------------------------------------------------
    var onScheduledActionReceived: ((ExecuteScheduledAction) -> Unit)? = null
    var onBeaconReceived: ((BeaconMessage) -> Unit)? = null
    var onActionIntentReceived: ((UserActionIntent) -> Unit)? = null
    var onRoomJoined: ((RoomEntity, Boolean) -> Unit)? = null
    var onBufferReadyReceived: ((BufferReadyMessage) -> Unit)? = null

    // תור בקשות NTP ממתינות: t0_nano -> t0_epoch
    private val pendingNtpRequests = ConcurrentHashMap<Long, Long>()

    init {
        acquireMulticastLock()
        initUdpSocket()
    }

    /**
     * נעילת MulticastLock ברמת מערכת ההפעלה Android:
     * הכרחי לחלוטין כדי למנוע מדרייבר ה-Wi-Fi לסנן הודעות mDNS ושידורי UDP מוצלבים.
     */
    private fun acquireMulticastLock() {
        try {
            multicastLock = wifiManager.createMulticastLock("AudioSyncMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock הופעל בהצלחה עבור mDNS ו-UDP")
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בהפעלת MulticastLock: ${e.message}")
        }
    }

    /**
     * שחרור משאבים בסגירת המתאם
     */
    fun destroy() {
        stopDiscovery()
        stopHostingLanService()
        stopLocalHttpAudioServer()
        closeWebSocket()
        udpReceiverJob?.cancel()
        udpSocket?.close()
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        Log.d(TAG, "NetworkAndClockCoordinator שוחרר בהצלחה")
    }

    // =====================================================================================
    // 1. מנוע סנכרון שעונים Micro-NTP
    // =====================================================================================

    /**
     * חישוב זמן השעון האבסולוטי המשוער של המארח/שרת ברגע הנוכחי
     * Local Host Epoch = Local System Epoch + Calculated Offset
     */
    fun getEstimatedHostEpoch(): Long {
        return System.currentTimeMillis() + _clockOffsetMs.value
    }

    /**
     * המרת חותמת זמן אבסולוטית של המארח (Target Epoch) לשעון המקומי של המכשיר
     * Local Execution Epoch = Target Epoch - Calculated Offset
     */
    fun hostEpochToLocalEpoch(hostEpoch: Long): Long {
        return hostEpoch - _clockOffsetMs.value
    }

    /**
     * הפעלת סדרת דגימות NTP מהירה לקביעת היסט השעון ו-RTT בדיוק גבוה.
     * מריץ NTP_SAMPLE_COUNT בקשות, מנפה חריגות וקובע את ההיסט המדויק.
     */
    fun performMicroNtpSync(sendNtpRequestBlock: (TimeSyncMessage) -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            val offsetSamples = mutableListOf<Long>()
            val rttSamples = mutableListOf<Long>()

            repeat(NTP_SAMPLE_COUNT) { index ->
                val t0 = System.currentTimeMillis()
                val t0Nanos = SystemClock.elapsedRealtimeNanos()
                pendingNtpRequests[t0] = t0Nanos

                val ntpMsg = TimeSyncMessage(
                    type = "TIME_SYNC_REQ",
                    peerId = localPeerId,
                    t0 = t0
                )

                sendNtpRequestBlock(ntpMsg)
                delay(60) // מרווח קצר בין דגימות למניעת עומס ו-Jitter
            }
        }
    }

    /**
     * עיבוד תשובת Micro-NTP (חישוב RTT והיסט)
     */
    fun handleNtpResponse(response: TimeSyncMessage) {
        val t3 = System.currentTimeMillis()
        val t0 = response.t0
        if (t0 <= 0L) return

        val rtt = response.calculateRtt(t3)
        val offset = response.calculateOffset(t3)

        pendingNtpRequests.remove(t0)

        _lastRttMs.value = rtt
        // שימוש בממוצע נע או היסט ישיר במקרה של דגימה טובה (RTT נמוך)
        if (_lastRttMs.value == 0L || rtt <= _lastRttMs.value * 1.5) {
            _clockOffsetMs.value = offset
        }

        Log.d(TAG, "[Micro-NTP] סנכרון עודכן: RTT=${rtt}ms, היסט השעון (Offset)=${offset}ms")
    }

    /**
     * חישוב נקודת זמן עתידית מוחלטת לביצוע פקודה מתוזמנת:
     * Target Time = Current Epoch + max(RTT_1, ..., RTT_n) + Safety Buffer
     */
    fun calculateTargetEpoch(peers: Collection<PeerSession>): Long {
        val maxPeerRtt = peers.maxOfOrNull { it.lastRttMs } ?: _lastRttMs.value
        val effectiveRtt = maxOf(maxPeerRtt, _lastRttMs.value, 40L)
        val currentEpoch = getEstimatedHostEpoch()
        return currentEpoch + effectiveRtt + SAFETY_BUFFER_MS
    }

    // =====================================================================================
    // 2. תקשורת LAN P2P: שרת שמע זעיר HTTP מובנה
    // =====================================================================================

    /**
     * הפעלת שרת HTTP מקומי זעיר על מכשיר המארח.
     * מאפשר לשאר המכשירים ברשת ה-LAN להזרים קובץ שמע מקומי (MP3, WAV, FLAC) במהירות שיא.
     */
    fun startLocalHttpAudioServer(audioFile: File, port: Int = DEFAULT_HTTP_STREAM_PORT): String? {
        stopLocalHttpAudioServer()
        currentLocalAudioFile = audioFile

        return try {
            localHttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/stream", AudioStreamHttpHandler(audioFile))
                executor = null // ברירת מחדל: ביצוע חד-נימי קל-משקל
                start()
            }

            val localIp = getLocalWifiIpAddress()
            val streamUrl = "http://$localIp:$port/stream"
            Log.d(TAG, "שרת הזרמת שמע מקומי פעיל: $streamUrl")
            streamUrl
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בהפעלת שרת הזרמת שמע מקומי: ${e.message}")
            null
        }
    }

    fun stopLocalHttpAudioServer() {
        try {
            localHttpServer?.stop(0)
            localHttpServer = null
            currentLocalAudioFile = null
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בעצירת שרת שמע: ${e.message}")
        }
    }

    /**
     * מטפל HTTP התומך בהזרמת מקטעי שמע עם תמיכה ב-Partial Content (HTTP 206 Range Requests)
     * לצורך Seek מדויק על ידי נגני ה-ExoPlayer בעמיתים האחרים.
     */
    private class AudioStreamHttpHandler(private val file: File) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                if (!file.exists()) {
                    exchange.sendResponseHeaders(404, -1)
                    return
                }

                val totalLength = file.length()
                val rangeHeader = exchange.requestHeaders.getFirst("Range")

                var start = 0L
                var end = totalLength - 1

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val ranges = rangeHeader.substring(6).split("-")
                    start = ranges[0].toLongOrNull() ?: 0L
                    if (ranges.size > 1 && ranges[1].isNotEmpty()) {
                        end = ranges[1].toLongOrNull() ?: end
                    }
                }

                val contentLength = end - start + 1
                exchange.responseHeaders.set("Content-Type", determineMimeType(file.extension))
                exchange.responseHeaders.set("Accept-Ranges", "bytes")

                if (rangeHeader != null) {
                    exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalLength")
                    exchange.sendResponseHeaders(206, contentLength)
                } else {
                    exchange.sendResponseHeaders(200, totalLength)
                }

                FileInputStream(file).use { fis ->
                    fis.skip(start)
                    val outputStream: OutputStream = exchange.responseBody
                    val buffer = ByteArray(16 * 1024)
                    var bytesRemaining = contentLength
                    while (bytesRemaining > 0) {
                        val read = fis.read(buffer, 0, minOf(buffer.size.toLong(), bytesRemaining).toInt())
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        bytesRemaining -= read
                    }
                    outputStream.flush()
                }
            } catch (e: Exception) {
                // הלקוח ניתק או עצר את הטעינה מוקדם - תקין לחלוטין
            } finally {
                exchange.close()
            }
        }

        private fun determineMimeType(extension: String): String {
            return when (extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                "ogg", "oga" -> "audio/ogg"
                else -> "application/octet-stream"
            }
        }
    }

    // =====================================================================================
    // 3. תקשורת LAN P2P: ערוץ פקודות מהיר ב-UDP Socket
    // =====================================================================================

    private fun initUdpSocket() {
        try {
            udpSocket = DatagramSocket(DEFAULT_UDP_COMMAND_PORT).apply {
                broadcast = true
                reuseAddress = true
            }
            startUdpReceiver()
            Log.d(TAG, "ערוץ UDP מקומי הוקם בהצלחה בפורט $DEFAULT_UDP_COMMAND_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה באתחול שקע UDP בפורט ראשי: ${e.message}. מנסה פורט דינמי...")
            try {
                udpSocket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                }
                startUdpReceiver()
            } catch (e2: Exception) {
                Log.e(TAG, "כישלון סופי באתחול UDP: ${e2.message}")
            }
        }
    }

    private fun startUdpReceiver() {
        udpReceiverJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (isActive && udpSocket?.isClosed == false) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val messageJson = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingRawJson(messageJson, packet.address.hostAddress ?: "")
                } catch (e: Exception) {
                    if (isActive && udpSocket?.isClosed == false) {
                        Log.w(TAG, "שגיאה בקבלת חבילת UDP: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * שידור פקודת UDP ברשת המקומית (Broadcast או לעמית ספציפי)
     */
    fun sendUdpMessage(messageJson: String, targetIp: String = "255.255.255.255", port: Int = DEFAULT_UDP_COMMAND_PORT) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val data = messageJson.toByteArray(Charsets.UTF_8)
                val targetAddress = InetAddress.getByName(targetIp)
                val packet = DatagramPacket(data, data.size, targetAddress, port)
                udpSocket?.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "שגיאה בשידור UDP ל-$targetIp:$port - ${e.message}")
            }
        }
    }

    // =====================================================================================
    // 4. תקשורת LAN P2P: גילוי עמיתים באמצעות Android NSD (mDNS)
    // =====================================================================================

    /**
     * פרסום המכשיר הנוכחי כמארח ברשת המקומית (NSD Registration)
     */
    fun startHostingLanService(serviceName: String = "AudioSyncHost_${localPeerId.takeLast(4)}") {
        _isHost.value = true
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = NSD_SERVICE_TYPE
            this.port = udpSocket?.localPort ?: DEFAULT_UDP_COMMAND_PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "שירות NSD פורסם בהצלחה: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "כישלון ברישום שירות NSD: קוד $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "שירות NSD בוטל: ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "כישלון בביטול רישום שירות NSD: קוד $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "חריגה ברישום שירות NSD: ${e.message}")
        }
    }

    fun stopHostingLanService() {
        _isHost.value = false
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "שגיאה בעצירת רישום NSD: ${e.message}")
            }
            registrationListener = null
        }
    }

    /**
     * גילוי מארחים ברשת ה-LAN באמצעות mDNS
     */
    fun startDiscovery() {
        stopDiscovery()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "חיפוש עמיתים ב-LAN הופעל")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "נמצא שירות ברשת: ${service.serviceName}")
                if (service.serviceType.contains("audiosync")) {
                    resolveDiscoveredService(service)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "שירות נותק מהרשת: ${service.serviceName}")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "חיפוש עמיתים הופסק")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "כישלון בהפעלת גילוי עמיתים: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "כישלון בעצירת גילוי עמיתים: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בהפעלת NSD Discover: ${e.message}")
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "שגיאה בעצירת גילוי NSD: ${e.message}")
            }
            discoveryListener = null
        }
    }

    private fun resolveDiscoveredService(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "פענוח כתובת השירות נכשל: $errorCode")
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host.hostAddress ?: return
                val port = serviceInfo.port
                Log.d(TAG, "שירות פוענח בהצלחה: $host:$port")

                val discoveredPeer = PeerSession(
                    peerId = serviceInfo.serviceName,
                    ipAddress = host,
                    port = port
                )
                activePeersMap[discoveredPeer.peerId] = discoveredPeer
                _connectedPeers.value = activePeersMap.values.toList()

                // שליחת בקשת הצטרפות וסנכרון Micro-NTP ראשוני מול המארח שפוענח
                sendUdpMessage(
                    messageJson = SyncMessageSerializer.serialize(
                        TimeSyncMessage(type = "TIME_SYNC_REQ", peerId = localPeerId, t0 = System.currentTimeMillis())
                    ),
                    targetIp = host,
                    port = port
                )
            }
        })
    }

    // =====================================================================================
    // 5. תקשורת WAN Relay: ערוץ WebSockets מול שרת ה-Signaling
    // =====================================================================================

    /**
     * התחברות לשרת ה-Signaling החיצוני
     */
    fun connectToSignalingServer(serverWsUrl: String) {
        closeWebSocket()
        val request = Request.Builder().url(serverWsUrl).build()

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "חיבור WebSocket לשרת Signaling הוקם בהצלחה")
                // ביצוע סנכרון שעונים ראשוני מול שרת ה-Signaling
                performMicroNtpSync { ntpMsg ->
                    webSocket.send(SyncMessageSerializer.serialize(ntpMsg))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingRawJson(text, "WAN_SIGNALING")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ערוץ WebSocket נסגר: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "שגיאת תקשורת ב-WebSocket: ${t.message}")
            }
        })
    }

    fun closeWebSocket() {
        try {
            activeWebSocket?.close(1000, "App closed")
            activeWebSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה בסגירת WebSocket: ${e.message}")
        }
    }

    fun sendWebSocketMessage(messageJson: String) {
        activeWebSocket?.send(messageJson)
    }

    // =====================================================================================
    // 6. פענוח וניתוב הודעות JSON (נתמך ב-UDP ו-WebSocket כאחד)
    // =====================================================================================

    private fun handleIncomingRawJson(jsonString: String, senderSource: String) {
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val jsonElement = SyncJson.parseToJsonElement(jsonString).jsonObject
                val type = jsonElement["type"]?.jsonPrimitive?.content ?: return@launch

                when (type) {
                    "TIME_SYNC_REQ" -> {
                        val req = SyncJson.decodeFromString<TimeSyncMessage>(jsonString)
                        // אם אנחנו המארח או שיש לנו שעון, נשיב עם t1 ו-t2
                        val now = System.currentTimeMillis()
                        val response = TimeSyncMessage(
                            type = "TIME_SYNC_RESP",
                            peerId = localPeerId,
                            t0 = req.t0,
                            t1 = now,
                            t2 = System.currentTimeMillis()
                        )
                        val respJson = SyncMessageSerializer.serialize(response)
                        if (senderSource == "WAN_SIGNALING") {
                            sendWebSocketMessage(respJson)
                        } else if (senderSource.isNotEmpty()) {
                            sendUdpMessage(respJson, targetIp = senderSource)
                        }
                    }

                    "TIME_SYNC_RESP" -> {
                        val resp = SyncJson.decodeFromString<TimeSyncMessage>(jsonString)
                        handleNtpResponse(resp)
                    }

                    "EXECUTE_SCHEDULED_ACTION" -> {
                        val scheduledAction = SyncJson.decodeFromString<ExecuteScheduledAction>(jsonString)
                        onScheduledActionReceived?.invoke(scheduledAction)
                    }

                    "BEACON" -> {
                        val beacon = SyncJson.decodeFromString<BeaconMessage>(jsonString)
                        onBeaconReceived?.invoke(beacon)
                    }

                    "USER_ACTION_INTENT" -> {
                        val intent = SyncJson.decodeFromString<UserActionIntent>(jsonString)
                        onActionIntentReceived?.invoke(intent)
                    }

                    "BUFFER_READY" -> {
                        val bufferReady = SyncJson.decodeFromString<BufferReadyMessage>(jsonString)
                        onBufferReadyReceived?.invoke(bufferReady)
                    }

                    "ROOM_CREATED", "ROOM_JOINED", "MATCHMAKE_SUCCESS" -> {
                        val payload = SyncJson.decodeFromString<RoomResponsePayload>(jsonString)
                        _currentRoomId.value = payload.roomId
                        _isHost.value = payload.isHost
                        payload.room?.let { roomEntity ->
                            onRoomJoined?.invoke(roomEntity, payload.isHost)
                        }
                    }

                    "PEER_JOINED", "PEER_LEFT" -> {
                        val notif = SyncJson.decodeFromString<PeerStatusNotification>(jsonString)
                        Log.d(TAG, "עדכון עמיתים: ${notif.type} מ-${notif.peerId}, סה״כ: ${notif.totalPeers}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "שגיאה בפענוח הודעה נכנסת: ${e.message} - $jsonString")
            }
        }
    }

    /**
     * איתור כתובת ה-IP המקומית של המכשיר ברשת ה-Wi-Fi
     */
    private fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "שגיאה באיתור כתובת IP: ${e.message}")
        }
        return "127.0.0.1"
    }
}
