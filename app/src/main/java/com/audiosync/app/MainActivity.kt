package com.audiosync.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

/**
 * =========================================================================================
 * נקודת הכניסה לאפליקציה (MainActivity)
 * =========================================================================================
 * 
 * מנהלת:
 * 1. בקשת הרשאות זמן-ריצה לקריאת קובצי שמע (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE).
 * 2. שמירת המסך והמעבד פעילים (FLAG_KEEP_SCREEN_ON) למניעת Jitter בשעון וב-Wi-Fi.
 * 3. אתחול מודל התצוגה (SyncViewModel) וטעינת ה-AccessibleSyncScreen.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SyncViewModel by viewModels()

    // משגר בקשת הרשאות מערכת
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            // כל ההרשאות אושרו
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // שמירת המסך פעיל למניעת כניסת המעבד למצב חיסכון בסוללה הפוגע ב-Micro-NTP
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // בקשת הרשאות קריאת שמע בהתאם לגרסת ה-Android
        requestRequiredPermissions()

        setContent {
            AccessibleSyncScreen(viewModel = viewModel)
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
