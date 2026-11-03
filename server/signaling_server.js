/**
 * =========================================================================================
 * Multi-Device Audio Synchronization - Signaling & Micro-NTP Server
 * שרת Signaling, סנכרון שעונים (Micro-NTP) ושידוך חדרים (Matchmaking)
 * =========================================================================================
 * 
 * שרת זה מנהל את המרחב הגלובלי (Global Shared Listening):
 * 1. ניהול חדרים לפי קוד בן 6 תווים ומנגנון Matchmaking אקראי למשתמשים ללא קוד.
 * 2. מענה לפניות סנכרון שעונים בדיוק מיקרו-שניות (Micro-NTP תשתיתי) לקביעת היסט שעון ו-RTT.
 * 3. ניתוב פקודות תזמון מבוזרות (EXECUTE_SCHEDULED_ACTION) ושידורי Beacon של המארח.
 * 4. ניהול מצב החדר (Late Joiners) - משתמש חדש מקבל מיד את מצב הנגינה הנוכחי מבלי להפריע למאזינים.
 */

const http = require('http');

// בדיקת תלויות: שימוש בספריית ws
let WebSocketServer;
try {
    const ws = require('ws');
    WebSocketServer = ws.WebSocketServer || ws.Server;
} catch (e) {
    console.error('================================================================');
    console.error('שגיאה: חבילת ws אינה מותקנת.');
    console.error('אנא הרץ: npm install בתיקיית server/ לפני הפעלת השרת.');
    console.error('================================================================');
    process.exit(1);
}

const PORT = process.env.PORT || 8080;

// יצירת שרת HTTP בסיסי לבדיקות Health Check וסטטיסטיקות
const server = http.createServer((req, res) => {
    if (req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({
            status: 'UP',
            activeRooms: rooms.size,
            matchmakingQueueSize: matchmakingQueue.length,
            serverEpoch: Date.now()
        }));
    } else {
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('Multi-Device Audio Sync Signaling Server is running.');
    }
});

const wss = new WebSocketServer({ server });

/**
 * מודל נתוני חדר בזיכרון השרת (RoomEntity בשרת)
 * @typedef {Object} Room
 * @property {string} roomId קוד חדר בן 6 תווים
 * @property {string} hostId מזהה העמית המשמש כמארח החדר
 * @property {string} mode מצב הפעולה (GLOBAL_DIRECT / RANDOM_MATCH)
 * @property {string} sourceType מקור השמע (LOCAL_FILE, RADIO_STREAM, YOUTUBE_EMBED)
 * @property {string} sourcePayload נתיב הקובץ, כתובת הרדיו או מזהה הווידאו
 * @property {string} state מצב הנגינה הנוכחי (IDLE, BUFFERING, PLAYING, PAUSED)
 * @property {number} currentTrackTimeMs מיקום הרצועה במילי-שניות בעת העדכון האחרון
 * @property {number} lastSyncEpoch חותמת הזמן שבה עודכן מיקום הרצועה
 * @property {Map<string, Object>} peers מפת העמיתים המחוברים לחדר
 */
const rooms = new Map();

/** תור משתמשים הממתינים לשידוך אקראי */
const matchmakingQueue = [];

/**
 * יצירת קוד חדר אלפא-נומרי אקראי בן 6 תווים (לדוגמה: "7K9X2A")
 * נמנע מתווים מבלבלים כמו 0/O, 1/I לטובת נגישות והקלדה נוחה
 */
function generateRoomCode() {
    const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    let code = '';
    do {
        code = '';
        for (let i = 0; i < 6; i++) {
            code += chars.charAt(Math.floor(Math.random() * chars.length));
        }
    } while (rooms.has(code));
    return code;
}

/**
 * שידור הודעה לכל המשתתפים בחדר
 * @param {string} roomId מזהה החדר
 * @param {Object} payload נתוני ההודעה
 * @param {string|null} excludePeerId מזהה עמית אופציונלי שלא יקבל את השידור
 */
function broadcastToRoom(roomId, payload, excludePeerId = null) {
    const room = rooms.get(roomId);
    if (!room) return;

    const messageStr = JSON.stringify(payload);
    for (const [peerId, peer] of room.peers.entries()) {
        if (peerId !== excludePeerId && peer.ws && peer.ws.readyState === 1 /* OPEN */) {
            try {
                peer.ws.send(messageStr);
            } catch (err) {
                console.error(`[Error] שידור לעמית ${peerId} נכשל:`, err.message);
            }
        }
    }
}

/**
 * טיפול בחיבור לקוח WebSocket חדש
 */
wss.on('connection', (ws, req) => {
    let currentPeerId = 'peer_' + Math.random().toString(36).substring(2, 9);
    let currentRoomId = null;

    console.log(`[Connect] לקוח חדש התחבר: ${currentPeerId} מכתובת ${req.socket.remoteAddress}`);

    // שליחת הודעת אישור חיבור עם ה-peerId שהוקצה
    ws.send(JSON.stringify({
        type: 'CONNECTION_ACK',
        peerId: currentPeerId,
        serverEpoch: Date.now()
    }));

    ws.on('message', (data) => {
        let msg;
        try {
            msg = JSON.parse(data.toString());
        } catch (err) {
            console.error('[Error] קבלת JSON לא תקין:', data.toString());
            return;
        }

        const now = Date.now();

        switch (msg.type) {
            /**
             * -----------------------------------------------------------------
             * א. מנגנון סנכרון שעונים Micro-NTP
             * -----------------------------------------------------------------
             * הלקוח שולח t0 (זמן יציאה מהמכשיר שלו).
             * השרת מוסיף t1 (זמן הגעה לשרת) ו-t2 (זמן יציאה מהשרת).
             * הלקוח שיקבל את ההודעה ימדוד t3 ויחשב:
             * Offset = ((t1 - t0) + (t2 - t3)) / 2
             * RTT = (t3 - t0) - (t2 - t1)
             */
            case 'TIME_SYNC_REQ': {
                const t0 = msg.t0;
                const t1 = now;
                const t2 = Date.now(); // זמן יציאה מהשרת

                ws.send(JSON.stringify({
                    type: 'TIME_SYNC_RESP',
                    peerId: currentPeerId,
                    t0: t0,
                    t1: t1,
                    t2: t2
                }));
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ב. יצירת חדר חדש (CREATE_ROOM)
             * -----------------------------------------------------------------
             */
            case 'CREATE_ROOM': {
                const requestedCode = (msg.roomId || '').trim().toUpperCase();
                const roomId = (requestedCode.length === 6) ? requestedCode : generateRoomCode();

                const newRoom = {
                    roomId: roomId,
                    hostId: currentPeerId,
                    mode: msg.mode || 'GLOBAL_DIRECT',
                    sourceType: msg.sourceType || 'RADIO_STREAM',
                    sourcePayload: msg.sourcePayload || '',
                    state: 'IDLE',
                    currentTrackTimeMs: 0,
                    lastSyncEpoch: now,
                    peers: new Map()
                };

                newRoom.peers.set(currentPeerId, {
                    peerId: currentPeerId,
                    ws: ws,
                    bufferState: 'READY',
                    lastRttMs: 0,
                    calculatedOffsetMs: 0
                });

                rooms.set(roomId, newRoom);
                currentRoomId = roomId;

                console.log(`[Room Created] חדר נוצר בהצלחה: ${roomId} על ידי מארח ${currentPeerId}`);

                ws.send(JSON.stringify({
                    type: 'ROOM_CREATED',
                    roomId: roomId,
                    isHost: true,
                    room: sanitizeRoomForWire(newRoom)
                }));
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ג. הצטרפות לחדר קיים (JOIN_ROOM) - כולל טיפול ב-Late Joiners
             * -----------------------------------------------------------------
             */
            case 'JOIN_ROOM': {
                const targetRoomId = (msg.roomId || '').trim().toUpperCase();
                const room = rooms.get(targetRoomId);

                if (!room) {
                    ws.send(JSON.stringify({
                        type: 'ERROR',
                        code: 'ROOM_NOT_FOUND',
                        message: `חדר ${targetRoomId} לא נמצא במערכת.`
                    }));
                    return;
                }

                currentRoomId = targetRoomId;
                room.peers.set(currentPeerId, {
                    peerId: currentPeerId,
                    ws: ws,
                    bufferState: 'LOADING',
                    lastRttMs: 0,
                    calculatedOffsetMs: 0
                });

                console.log(`[Join] עמית ${currentPeerId} הצטרף לחדר ${targetRoomId}`);

                // חישוב מיקום מוערך בזמן אמת עבור Late Joiner כדי שלא יחווה קטיעה
                let estimatedTrackPositionMs = room.currentTrackTimeMs;
                if (room.state === 'PLAYING') {
                    const elapsedSinceLastSync = Math.max(0, now - room.lastSyncEpoch);
                    estimatedTrackPositionMs += elapsedSinceLastSync;
                }

                // 1. שליחת מצב החדר העדכני למצטרף החדש
                ws.send(JSON.stringify({
                    type: 'ROOM_JOINED',
                    roomId: targetRoomId,
                    isHost: (room.hostId === currentPeerId),
                    room: {
                        ...sanitizeRoomForWire(room),
                        currentTrackTimeMs: estimatedTrackPositionMs
                    }
                }));

                // 2. עדכון שאר החברים בחדר על הצטרפות העמית
                broadcastToRoom(targetRoomId, {
                    type: 'PEER_JOINED',
                    peerId: currentPeerId,
                    totalPeers: room.peers.size
                }, currentPeerId);
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ד. שידוך אקראי (MATCHMAKE_REQ)
             * -----------------------------------------------------------------
             */
            case 'MATCHMAKE_REQ': {
                console.log(`[Matchmaking] עמית ${currentPeerId} ביקש שידוך אקראי`);

                // בדיקה אם קיים חדר פתוח שמחפש חברים
                let matchedRoom = null;
                for (const r of rooms.values()) {
                    if (r.mode === 'RANDOM_MATCH' && r.peers.size < 8) {
                        matchedRoom = r;
                        break;
                    }
                }

                if (matchedRoom) {
                    // שידוך לחדר קיים
                    currentRoomId = matchedRoom.roomId;
                    matchedRoom.peers.set(currentPeerId, {
                        peerId: currentPeerId,
                        ws: ws,
                        bufferState: 'LOADING',
                        lastRttMs: 0,
                        calculatedOffsetMs: 0
                    });

                    ws.send(JSON.stringify({
                        type: 'MATCHMAKE_SUCCESS',
                        roomId: matchedRoom.roomId,
                        isHost: false,
                        room: sanitizeRoomForWire(matchedRoom)
                    }));

                    broadcastToRoom(matchedRoom.roomId, {
                        type: 'PEER_JOINED',
                        peerId: currentPeerId,
                        totalPeers: matchedRoom.peers.size
                    }, currentPeerId);
                } else if (matchmakingQueue.length > 0) {
                    // הוצאת עמית ממתין מהתור ויצירת חדר משותף לשניהם
                    const otherPeer = matchmakingQueue.shift();
                    const newRoomId = generateRoomCode();

                    const newRoom = {
                        roomId: newRoomId,
                        hostId: otherPeer.peerId,
                        mode: 'RANDOM_MATCH',
                        sourceType: 'RADIO_STREAM',
                        sourcePayload: 'https://stream.live.vc.bbcmedia.co.uk/bbc_world_service',
                        state: 'IDLE',
                        currentTrackTimeMs: 0,
                        lastSyncEpoch: now,
                        peers: new Map()
                    };

                    newRoom.peers.set(otherPeer.peerId, otherPeer);
                    newRoom.peers.set(currentPeerId, {
                        peerId: currentPeerId,
                        ws: ws,
                        bufferState: 'LOADING',
                        lastRttMs: 0,
                        calculatedOffsetMs: 0
                    });

                    rooms.set(newRoomId, newRoom);
                    currentRoomId = newRoomId;
                    otherPeer.roomId = newRoomId;

                    // שליחת הודעת הצלחה לשני הצדדים
                    if (otherPeer.ws.readyState === 1) {
                        otherPeer.ws.send(JSON.stringify({
                            type: 'MATCHMAKE_SUCCESS',
                            roomId: newRoomId,
                            isHost: true,
                            room: sanitizeRoomForWire(newRoom)
                        }));
                    }

                    ws.send(JSON.stringify({
                        type: 'MATCHMAKE_SUCCESS',
                        roomId: newRoomId,
                        isHost: false,
                        room: sanitizeRoomForWire(newRoom)
                    }));
                } else {
                    // אין שותף זמין כרגע - כניסה לתור המתנה
                    matchmakingQueue.push({
                        peerId: currentPeerId,
                        ws: ws,
                        bufferState: 'READY',
                        lastRttMs: 0,
                        calculatedOffsetMs: 0
                    });

                    ws.send(JSON.stringify({
                        type: 'MATCHMAKE_WAITING',
                        message: 'מחפש שותף להאזנה משותפת ברחבי העולם...'
                    }));
                }
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ה. יירוט כוונת פעולה (USER_ACTION_INTENT) מהלקוח
             * -----------------------------------------------------------------
             * הלקוח שולח כוונה (Play/Pause/Seek).
             * אם הלקוח הוא המארח, או אם מועבר למארח לחישוב מועד עתידי:
             * Target Time = Current Epoch + max(RTT_1..RTT_n) + Safety Buffer (למשל 400ms)
             */
            case 'USER_ACTION_INTENT': {
                if (!currentRoomId || !rooms.has(currentRoomId)) return;
                const room = rooms.get(currentRoomId);

                console.log(`[Intent] התקבלה כוונת פעולה: ${msg.action} מ-${currentPeerId} בחדר ${currentRoomId}`);

                // אם השולח הוא המארח או שהשרת מחשב בשמו
                const action = msg.action; // PLAY, PAUSE, SEEK
                const requestedPos = (typeof msg.requested_position_ms === 'number') ? msg.requested_position_ms : room.currentTrackTimeMs;

                // חישוב מרווח ביטחון: מקסימום RTT בין החברים בחדר + 300ms מרווח פעולה
                let maxRtt = 100;
                for (const peer of room.peers.values()) {
                    if (peer.lastRttMs > maxRtt) {
                        maxRtt = peer.lastRttMs;
                    }
                }
                const safetyBufferMs = 350;
                const targetEpochMs = now + maxRtt + safetyBufferMs;

                // עדכון נתוני החדר
                room.currentTrackTimeMs = requestedPos;
                room.lastSyncEpoch = now;
                if (action === 'PLAY') room.state = 'PLAYING';
                if (action === 'PAUSE') room.state = 'PAUSED';

                // שידור פקודת הביצוע המתוזמנת לכלל המכשירים בחדר
                const scheduledActionMsg = {
                    type: 'EXECUTE_SCHEDULED_ACTION',
                    action: action,
                    target_epoch_ms: targetEpochMs,
                    track_position_ms: requestedPos,
                    sourcePayload: msg.sourcePayload || room.sourcePayload,
                    sourceType: msg.sourceType || room.sourceType
                };

                broadcastToRoom(currentRoomId, scheduledActionMsg);
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ו. אות Beacon תקופתי מהמארח
             * -----------------------------------------------------------------
             * משודר מהמארח לעמיתים כדי לאפשר Drift Correction מתמשך.
             */
            case 'BEACON': {
                if (!currentRoomId || !rooms.has(currentRoomId)) return;
                const room = rooms.get(currentRoomId);

                // רק המארח מורשה לשדר Beacon
                if (room.hostId === currentPeerId) {
                    room.currentTrackTimeMs = msg.track_position_ms;
                    room.lastSyncEpoch = now;
                    room.state = msg.isPlaying ? 'PLAYING' : 'PAUSED';

                    // שידור העתק לכל העמיתים
                    broadcastToRoom(currentRoomId, {
                        type: 'BEACON',
                        hostEpochMs: now,
                        track_position_ms: msg.track_position_ms,
                        isPlaying: msg.isPlaying,
                        playbackSpeed: msg.playbackSpeed || 1.0
                    }, currentPeerId);
                }
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ז. דיווח BUFFER_READY מלקוח
             * -----------------------------------------------------------------
             */
            case 'BUFFER_READY': {
                if (!currentRoomId || !rooms.has(currentRoomId)) return;
                const room = rooms.get(currentRoomId);
                const peer = room.peers.get(currentPeerId);
                if (peer) {
                    peer.bufferState = 'READY';
                }

                // עדכון המארח שמשתתף מוכן לניגון
                broadcastToRoom(currentRoomId, {
                    type: 'PEER_BUFFER_UPDATE',
                    peerId: currentPeerId,
                    bufferState: 'READY'
                }, currentPeerId);
                break;
            }

            /**
             * -----------------------------------------------------------------
             * ח. עדכון RTT / Offset שהלקוח מדד
             * -----------------------------------------------------------------
             */
            case 'METRICS_UPDATE': {
                if (!currentRoomId || !rooms.has(currentRoomId)) return;
                const room = rooms.get(currentRoomId);
                const peer = room.peers.get(currentPeerId);
                if (peer) {
                    if (typeof msg.rttMs === 'number') peer.lastRttMs = msg.rttMs;
                    if (typeof msg.offsetMs === 'number') peer.calculatedOffsetMs = msg.offsetMs;
                }
                break;
            }

            default:
                console.log(`[Unknown] הודעה לא מוכרת: ${msg.type}`);
        }
    });

    ws.on('close', () => {
        console.log(`[Disconnect] לקוח התנתק: ${currentPeerId}`);

        // הסרה מתור ה-Matchmaking אם היה שם
        const mmIndex = matchmakingQueue.findIndex(p => p.peerId === currentPeerId);
        if (mmIndex !== -1) {
            matchmakingQueue.splice(mmIndex, 1);
        }

        // הסרה מהחדר וסגירתו אם התרוקן
        if (currentRoomId && rooms.has(currentRoomId)) {
            const room = rooms.get(currentRoomId);
            room.peers.delete(currentPeerId);

            if (room.peers.size === 0) {
                rooms.delete(currentRoomId);
                console.log(`[Room Closed] חדר ${currentRoomId} נסגר כי לא נותרו בו משתתפים`);
            } else {
                // אם המארח עזב, נעביר את תפקיד המארח למשתתף הראשון שנותר
                if (room.hostId === currentPeerId) {
                    const newHostId = room.peers.keys().next().value;
                    room.hostId = newHostId;
                    console.log(`[Host Migrated] מארח חדש הוקצה לחדר ${currentRoomId}: ${newHostId}`);

                    const newHostPeer = room.peers.get(newHostId);
                    if (newHostPeer && newHostPeer.ws.readyState === 1) {
                        newHostPeer.ws.send(JSON.stringify({
                            type: 'HOST_PROMOTED',
                            roomId: currentRoomId
                        }));
                    }
                }

                broadcastToRoom(currentRoomId, {
                    type: 'PEER_LEFT',
                    peerId: currentPeerId,
                    totalPeers: room.peers.size
                });
            }
        }
    });

    ws.on('error', (err) => {
        console.error(`[WS Error] שגיאה בערוץ של ${currentPeerId}:`, err.message);
    });
});

/**
 * המרת אובייקט חדר לפורמט בטוח לשידור ברשת (הסרת ה-WebSocket sockets)
 */
function sanitizeRoomForWire(room) {
    const peersArray = [];
    for (const [id, peer] of room.peers.entries()) {
        peersArray.push({
            peerId: id,
            bufferState: peer.bufferState || 'READY',
            lastRttMs: peer.lastRttMs || 0,
            calculatedOffsetMs: peer.calculatedOffsetMs || 0
        });
    }

    return {
        roomId: room.roomId,
        hostId: room.hostId,
        mode: room.mode,
        sourceType: room.sourceType,
        sourcePayload: room.sourcePayload,
        state: room.state,
        currentTrackTimeMs: room.currentTrackTimeMs,
        lastSyncEpoch: room.lastSyncEpoch,
        peers: peersArray
    };
}

// הפעלת השרת
server.listen(PORT, () => {
    console.log('================================================================');
    console.log(`שרת Signaling ו-Micro-NTP פעיל ומאזין בפורט: ${PORT}`);
    console.log(`בדיקת Health Check: http://localhost:${PORT}/health`);
    console.log('================================================================');
});
