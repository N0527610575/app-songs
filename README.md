# MultiDeviceAudioSync 🎵📱
מערכת מבוזרת לסנכרון שמע רב-מכשירי בדיוק של פחות מ-15 מילי-שניות עבור אנדרואיד.

---

## 🌟 מטרות העל של המערכת
1. **הגברת עוצמה מקומית (Acoustic Amplification Mode):** איחוד מספר מכשירי Android בחלל אחד כמערך רמקולים אקוסטי מתואם, עם דיוק סנכרון של פחות מ-15ms למניעת תופעות Phasing והד.
2. **האזנה משותפת חוצת גבולות (Global Shared Listening Mode):** יצירת חלל האזנה וירטואלי עם קוד חדר בן 6 תווים או שידוך אקראי (Random Matchmaking) ברחבי העולם דרך שרת WebSocket.
3. **תמיכה במקורות תוכן מגוונים (Source Agnostic):**
   - קובצי שמע מקומיים מזיכרון המכשיר (MP3, FLAC, WAV) באמצעות שרת HTTP זעיר מובנה במכשיר המארח.
   - שידורי רדיו מקוונים (Icecast, Shoutcast, HLS).
   - הטמעה חוקית של YouTube IFrame Player API ללא תלות במנויי פרימיום.

---

## 📐 ארכיטקטורה הנדסית ונוסחאות מתמטיות

### 1. אלגוריתם סנכרון שעונים (Micro-NTP)
לכל מכשיר שעון חומרה פנימי בעל סטייה קלה (Clock Drift). כדי ליישר קו לשעון אבסולוטי יחיד, מתבצע סבב דגימות מהיר:
* $t_0$ = זמן שיגור הבקשה מהלקוח (שעון לקוח)
* $t_1$ = זמן הגעת הבקשה למארח (שעון מארח)
* $t_2$ = זמן שיגור התשובה מהמארח (שעון מארח)
* $t_3$ = זמן קבלת התשובה בלקוח (שעון לקוח)

$$RTT = (t_3 - t_0) - (t_2 - t_1)$$
$$\text{Clock Offset } (\theta) = \frac{(t_1 - t_0) + (t_2 - t_3)}{2}$$

זמן המארח המוערך ברגע נתון:
$$\text{Host Epoch} = \text{Client Epoch} + \theta$$

### 2. מודל תזמון עתידי (Target Execution Engine)
כדי שכל המכשירים יפעילו את גל הקול באותה נקודת זמן אבסולוטית, המארח קובע את חותמת הזמן העתידית לפי הנוסחה:
$$\text{Target Time} = \text{Current Epoch} + \max(RTT_1, \dots, RTT_n) + \text{Safety Buffer (350ms)}$$

כל מכשיר ממיר את זמן היעד לשעונו המקומי, מקזז את השהיית החומרה הפנימית שלו ($\text{Hardware Output Latency}$ מ-`AudioManager`), ומבצע טעינה מוקדמת (Pre-buffering) של מקטע השמע. בשלוש המילי-שניות האחרונות לפני ההפעלה, המערכת עוברת מ-Coroutine Delay ללולאת Spin-Wait נעולה לסגירת הזמן בדיוק של מיקרו-שניות.

### 3. בקרת סטייה מתמשכת (Continuous Drift Correction)
המארח משדר אות בקרה תקופתי (Beacon) בכל שנייה:
* **סטייה קלה (5ms–30ms):** תיקון שקוף לאוזן האנושית על ידי שינוי קצב דגימה עדין ב-`ExoPlayer` באמצעות `PlaybackParameters` בערכי `0.99f` או `1.01f`.
* **סטייה חמורה (מעל 150ms):** ביצוע דילוג מהיר (`Hard Seek`) ליישור קו מיידי.

---

## 📂 מבנה עץ הקבצים בפרויקט

```text
MultiDeviceAudioSync/
├── settings.gradle.kts                   # הגדרות ניהול הפרויקט והספריות
├── build.gradle.kts                      # קובץ בנייה שורשי והגדרת פלאגינים
├── README.md                             # מדריך הנדסי מקיף זה
├── server/
│   ├── package.json                      # תלויות והגדרות שרת ה-Signaling
│   └── signaling_server.js               # שרת WebSocket, Micro-NTP ו-Matchmaking
└── app/
    ├── build.gradle.kts                  # תלויות Jetpack Compose, Media3 ו-OkHttp
    └── src/main/
        ├── AndroidManifest.xml           # הרשאות Wi-Fi/NSD, אינטרנט ושמע
        └── java/com/audiosync/app/
            ├── MainActivity.kt           # נקודת כניסה, הרשאות runtime ומסך פעיל
            ├── SyncModels.kt             # כל ישויות המערכת וחוזי הודעות ה-JSON
            ├── NetworkAndClockCoordinator.kt # מנוע Micro-NTP, גילוי NSD, שרת HTTP ו-UDP
            ├── SyncedAudioPlayerEngine.kt   # מנוע שמע ExoPlayer + יוטיוב, קיזוז חומרה ו-Drift
            ├── SyncViewModel.kt          # שכבת ה-Optimistic Interceptor, Beacons ו-Auto-Catchup
            └── AccessibleSyncScreen.kt   # ממשק משתמש נגיש (WCAG AA) ב-Jetpack Compose
```

---

## 🚀 הוראות הרצה צעד אחר צעד

### 1. הפעלת שרת ה-Signaling (Node.js)
יש לנווט לתיקיית `server/` ולהריץ:
```bash
cd MultiDeviceAudioSync/server
npm install
node signaling_server.js
```
ברירת המחדל של הפורט היא `8080`. בדיקת תקינות דרך הדפדפן: `http://localhost:8080/health`.

### 2. פתיחה והרצת פרויקט ה-Android ב-Android Studio
1. פתח את **Android Studio**.
2. בחר ב-**Open an Existing Project** ובחר בתיקיית `MultiDeviceAudioSync`.
3. המתן לסיום סנכרון ה-Gradle (`Sync Project with Gradle Files`).
4. הפעל במכשיר פיזי או באמולטור (API 26 ומעלה).
   * **לתשומת לב באמולטור Android:** הכתובת ל-localhost של המחשב המארח מתוך האמולטור היא `ws://10.0.2.2:8080`.
   * **במכשירים פיזיים:** מומלץ לוודא שכל המכשירים מחוברים לאותה רשת Wi-Fi, ולהשתמש במצב **מקומי (LAN P2P)** לגילוי אוטומטי מבוסס mDNS.
