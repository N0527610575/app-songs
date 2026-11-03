package com.audiosync.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/**
 * =========================================================================================
 * ממשק משתמש נגיש לסנכרון שמע רב-מכשירי (AccessibleSyncScreen)
 * =========================================================================================
 * 
 * ממשק זה פותח ב-Jetpack Compose ומותאם לנגישות מרבית (WCAG AA):
 * 1. יעדי מגע מוגדלים (Touch Targets מעל 56dp) לכפתורי השליטה המרכזיים.
 * 2. תמיכה מלאה בקורא מסך TalkBack באמצעות contentDescription מפורט בעברית.
 * 3. ניגודיות צבעים גבוהה (High Contrast) וחיווי ויזואלי ברור לסטייה ולמצב הסנכרון.
 * 4. חיווי מצב ביניים מיידי ("מסנכרן עם החדר...") למניעת בלבול בזמן תיאום הרשת.
 */

// פלטת צבעים נגישה בניגודיות גבוהה
private val PrimaryAccent = Color(0xFF00E5FF)       // ציאן בוהק לקריאות גבוהה
private val BackgroundDark = Color(0xFF121212)      // רקע כהה עמוק
private val SurfaceDark = Color(0xFF1E1E1E)         // משטח כהה מוגבה
private val InSyncGreen = Color(0xFF00E676)         // ירוק לסנכרון מעולה (<15ms)
private val ModerateDriftYellow = Color(0xFFFFD600) // צהוב לסטייה בינונית (15-50ms)
private val HighDriftRed = Color(0xFFFF5252)        // אדום לסטייה גבוהה (>50ms)
private val SynchronizingOrange = Color(0xFFFF9100) // כתום למצב תיאום ברקע

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibleSyncScreen(
    viewModel: SyncViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // דיאלוגים
    var showJoinDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var joinRoomCodeInput by remember { mutableStateOf("") }

    // בורר קבצי שמע מקומיים
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.setSource(SourceType.LOCAL_FILE, it.toString())
            Toast.makeText(context, "קובץ שמע מקומי נבחר בהצלחה", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "סנכרון שמע רב-מכשירי",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.semantics { heading() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark
                ),
                actions = {
                    IconButton(
                        onClick = { showSourceDialog = true },
                        modifier = Modifier.semantics {
                            contentDescription = "בחירת מקור שמע: קובץ מקומי, רדיו או יוטיוב"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = PrimaryAccent
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 1. באנר חיווי סנכרון חי (Live Sync Status Indicator)
            item {
                SyncStatusBanner(
                    statusMessage = uiState.statusBanner,
                    isSynchronizing = uiState.isSynchronizing,
                    driftMs = uiState.driftMs,
                    rttMs = uiState.rttMs
                )
            }

            // 2. קוד חדר ומידע על מצב הפעולה
            item {
                RoomCodeCard(
                    roomId = uiState.roomId,
                    isHost = uiState.isHost,
                    mode = uiState.mode,
                    onCopyCode = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Room Code", uiState.roomId))
                        Toast.makeText(context, "קוד החדר הועתק ללוח", Toast.LENGTH_SHORT).show()
                    },
                    onCreateRoom = { viewModel.createGlobalRoom() },
                    onJoinRoomClicked = { showJoinDialog = true }
                )
            }

            // 3. בורר מצבי פעולה (LAN P2P / Global / Matchmaking)
            item {
                SyncModeSelector(
                    currentMode = uiState.mode,
                    onModeSelected = { viewModel.setMode(it) },
                    onMatchmake = { viewModel.startRandomMatchmaking() }
                )
            }

            // 4. נגן YouTube מוטמע (מוצג רק במידה ונבחר מקור יוטיוב)
            if (uiState.sourceType == SourceType.YOUTUBE_EMBED) {
                item {
                    Text(
                        text = "נגן YouTube חוקי מוטמע",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    viewModel.playerEngine.setupYoutubeWebView(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 5. לוח בקרה ראשי (Play/Pause/Seek) עם יעדי מגע מוגדלים
            item {
                AudioPlaybackControllerCard(
                    playbackState = uiState.playbackState,
                    currentPosMs = uiState.currentPositionMs,
                    isSynchronizing = uiState.isSynchronizing,
                    onPlayClicked = { viewModel.onUserActionClicked(ActionType.PLAY) },
                    onPauseClicked = { viewModel.onUserActionClicked(ActionType.PAUSE) },
                    onSeekRequested = { pos -> viewModel.onUserActionClicked(ActionType.SEEK, pos) }
                )
            }

            // 6. לוח מחוונים טכנולוגי (Metrics Dashboard)
            item {
                SyncMetricsDashboard(
                    driftMs = uiState.driftMs,
                    rttMs = uiState.rttMs,
                    clockOffsetMs = uiState.clockOffsetMs,
                    hardwareLatencyMs = uiState.hardwareLatencyMs
                )
            }

            // 7. רשימת עמיתים מחוברים
            item {
                Text(
                    text = "מכשירים מחוברים (${uiState.connectedPeers.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.semantics { heading() }
                )
            }

            if (uiState.connectedPeers.isEmpty()) {
                item {
                    Text(
                        text = "אין מכשירים נוספים מחוברים כרגע. שתף את קוד החדר להאזנה משותפת!",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            } else {
                items(uiState.connectedPeers) { peer ->
                    PeerItemCard(peer = peer)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // דיאלוג הצטרפות לחדר קיים
    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("הצטרפות לחדר קיים") },
            text = {
                OutlinedTextField(
                    value = joinRoomCodeInput,
                    onValueChange = { joinRoomCodeInput = it.take(6).uppercase() },
                    label = { Text("הכנס קוד חדר בן 6 תווים") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (joinRoomCodeInput.length == 6) {
                            viewModel.joinGlobalRoom(joinRoomCodeInput)
                            showJoinDialog = false
                        }
                    }
                ) {
                    Text("הצטרף")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) {
                    Text("ביטול")
                }
            }
        )
    }

    // דיאלוג בחירת מקור שמע
    if (showSourceDialog) {
        SourceSelectionDialog(
            currentType = uiState.sourceType,
            currentPayload = uiState.sourcePayload,
            onDismiss = { showSourceDialog = false },
            onSelectRadio = { url ->
                viewModel.setSource(SourceType.RADIO_STREAM, url)
                showSourceDialog = false
            },
            onSelectLocalFile = {
                showSourceDialog = false
                audioPickerLauncher.launch("audio/*")
            },
            onSelectYoutube = { videoId ->
                viewModel.setSource(SourceType.YOUTUBE_EMBED, videoId)
                showSourceDialog = false
            }
        )
    }
}

/**
 * =========================================================================================
 * רכיבי UI ייעודיים
 * =========================================================================================
 */

/**
 * 1. באנר חיווי סנכרון חי
 */
@Composable
fun SyncStatusBanner(
    statusMessage: String,
    isSynchronizing: Boolean,
    driftMs: Long,
    rttMs: Long
) {
    val absDrift = abs(driftMs)
    val statusColor = when {
        isSynchronizing -> SynchronizingOrange
        absDrift < 15 -> InSyncGreen
        absDrift < 50 -> ModerateDriftYellow
        else -> HighDriftRed
    }

    val accessibleDescription = when {
        isSynchronizing -> "מצב נוכחי: מסנכרן עם החדר ברקע"
        absDrift < 15 -> "סנכרון מצוין, סטייה $absDrift מילי-שניות, זמן סבב רשת $rttMs מילי-שניות"
        absDrift < 50 -> "סנכרון תקין בתיקון עדין, סטייה $absDrift מילי-שניות"
        else -> "סטייה גבוהה של $absDrift מילי-שניות, מבוצע יישור קו מהיר"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = accessibleDescription
            },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, statusColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // נורת חיווי צבעונית
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSynchronizing) "מסנכרן עם החדר..." else statusMessage,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                Text(
                    text = "סטייה: ${driftMs}ms | RTT: ${rttMs}ms",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
            }

            if (isSynchronizing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = SynchronizingOrange,
                    strokeWidth = 2.5.dp
                )
            }
        }
    }
}

/**
 * 2. כרטיס קוד חדר
 */
@Composable
fun RoomCodeCard(
    roomId: String,
    isHost: Boolean,
    mode: SyncMode,
    onCopyCode: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoomClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (roomId.isNotEmpty()) "קוד החדר שלך:" else "לא מחובר לחדר",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                    if (roomId.isNotEmpty()) {
                        Text(
                            text = roomId,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = PrimaryAccent,
                            modifier = Modifier.semantics {
                                contentDescription = "קוד חדר $roomId. לחץ להעתקה."
                            }
                        )
                    }
                }

                if (roomId.isNotEmpty()) {
                    IconButton(
                        onClick = onCopyCode,
                        modifier = Modifier
                            .size(56.dp)
                            .semantics { contentDescription = "העתק קוד חדר ללוח" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCreateRoom,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .semantics { contentDescription = "יצירת חדר גלובלי חדש" },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent)
                ) {
                    Text("צור חדר", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onJoinRoomClicked,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .semantics { contentDescription = "הצטרפות לחדר קיים באמצעות קוד" }
                ) {
                    Text("הצטרף לחדר", color = Color.White)
                }
            }
        }
    }
}

/**
 * 3. בורר מצבי פעולה
 */
@Composable
fun SyncModeSelector(
    currentMode: SyncMode,
    onModeSelected: (SyncMode) -> Unit,
    onMatchmake: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "אופן סנכרון",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 16.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = (currentMode == SyncMode.LOCAL_P2P),
                    onClick = { onModeSelected(SyncMode.LOCAL_P2P) },
                    label = { Text("מקומי (LAN P2P)") },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics { contentDescription = "מצב הגברת עוצמה מקומית ברשת אלחוטית P2P" }
                )

                FilterChip(
                    selected = (currentMode == SyncMode.GLOBAL_DIRECT),
                    onClick = { onModeSelected(SyncMode.GLOBAL_DIRECT) },
                    label = { Text("גלובלי (קוד חדר)") },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics { contentDescription = "מצב חדר גלובלי עם קוד ייעודי" }
                )
            }

            Button(
                onClick = onMatchmake,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics { contentDescription = "שידוך אקראי למשתמש זמין ברחבי העולם" },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EA))
            ) {
                Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("שידוך אקראי (Random Match)", color = Color.White)
            }
        }
    }
}

/**
 * 4. לוח בקרה ראשי (Play/Pause/Seek)
 */
@Composable
fun AudioPlaybackControllerCard(
    playbackState: PlaybackState,
    currentPosMs: Long,
    isSynchronizing: Boolean,
    onPlayClicked: () -> Unit,
    onPauseClicked: () -> Unit,
    onSeekRequested: (Long) -> Unit
) {
    val isPlaying = (playbackState == PlaybackState.PLAYING)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterVertically,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // מחוון זמן
            Text(
                text = formatTime(currentPosMs),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
                modifier = Modifier.semantics {
                    contentDescription = "מיקום רצועה נוכחי: ${formatTime(currentPosMs)}"
                }
            )

            // פס גלילה (Seek Slider)
            Slider(
                value = (currentPosMs / 1000f).coerceAtLeast(0f),
                onValueChange = { seconds ->
                    onSeekRequested((seconds * 1000).toLong())
                },
                valueRange = 0f..maxOf(300f, (currentPosMs / 1000f) + 60f),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "פס דילוג בתוך הרצועה"
                    },
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryAccent,
                    activeTrackColor = PrimaryAccent
                )
            )

            // כפתור Play / Pause ראשי ענק (Touch target 72dp)
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(PrimaryAccent)
                    .clickable(
                        enabled = !isSynchronizing,
                        onClick = {
                            if (isPlaying) onPauseClicked() else onPlayClicked()
                        }
                    )
                    .semantics {
                        contentDescription = if (isPlaying) "השהה שמע לכל החדר" else "נגן שמע מסונכרן לכל החדר"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

/**
 * 5. לוח מחוונים טכנולוגי (Metrics Dashboard)
 */
@Composable
fun SyncMetricsDashboard(
    driftMs: Long,
    rttMs: Long,
    clockOffsetMs: Long,
    hardwareLatencyMs: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "מדדי סנכרון ושעון מבוזר (Micro-NTP)",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 16.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(label = "זמן סבב רשת (RTT)", value = "${rttMs}ms")
                MetricItem(label = "היסט שעון (Offset θ)", value = "${clockOffsetMs}ms")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem(label = "השהיית חומרה (DAC)", value = "${hardwareLatencyMs}ms")
                MetricItem(label = "סטייה נוכחית (Drift)", value = "${driftMs}ms")
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column {
        Text(text = label, color = Color.Gray, fontSize = 12.sp)
        Text(
            text = value,
            color = PrimaryAccent,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * 6. כרטיס עמית מחובר
 */
@Composable
fun PeerItemCard(peer: PeerSession) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "מכשיר מחובר ${peer.peerId}, מצב טעינה ${peer.bufferState}"
            },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = Color.LightGray
                )
                Column {
                    Text(text = peer.peerId, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        text = "RTT: ${peer.lastRttMs}ms | היסט: ${peer.calculatedOffsetMs}ms",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = if (peer.bufferState == PeerBufferState.READY) "מוכן (Ready)" else "טוען...",
                color = if (peer.bufferState == PeerBufferState.READY) InSyncGreen else SynchronizingOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 7. דיאלוג בחירת מקור תוכן
 */
@Composable
fun SourceSelectionDialog(
    currentType: SourceType,
    currentPayload: String,
    onDismiss: () -> Unit,
    onSelectRadio: (String) -> Unit,
    onSelectLocalFile: () -> Unit,
    onSelectYoutube: (String) -> Unit
) {
    var radioUrlInput by remember { mutableStateOf("https://stream.live.vc.bbcmedia.co.uk/bbc_world_service") }
    var youtubeIdInput by remember { mutableStateOf("jfKfPfyJRdk") } // דוגמה ל-Lofi stream מוכר

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("בחירת מקור תוכן") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // א. קובץ מקומי
                OutlinedButton(
                    onClick = onSelectLocalFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("בחר קובץ שמע מקומי מהמכשיר")
                }

                Divider()

                // ב. רדיו אינטרנטי
                Column {
                    Text("רדיו מקוון (Icecast / HLS):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = radioUrlInput,
                        onValueChange = { radioUrlInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSelectRadio(radioUrlInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text("הפעל רדיו זה")
                    }
                }

                Divider()

                // ג. יוטיוב מוטמע
                Column {
                    Text("יוטיוב (מזהה או קישור):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = youtubeIdInput,
                        onValueChange = { youtubeIdInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onSelectYoutube(youtubeIdInput) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text("הפעל סרטון יוטיוב")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("סגור") }
        }
    )
}

/**
 * עיצוב מילי-שניות לפורמט דקות:שניות נגיש
 */
private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
