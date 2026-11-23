package com.example

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.service.RescueMeshForegroundService
import com.example.service.ShakeDetector
import com.example.ui.RescueMeshApp
import com.example.ui.theme.RescueMeshTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ARM_SOS_CONFIRM = "extra_arm_sos_confirm"
        const val EXTRA_OPEN_SESSION_ID = "extra_open_session_id"
    }

    private var armedSosDialog by mutableStateOf(false)
    private var deepLinkSessionId by mutableStateOf<String?>(null)

    private var shakeDetector: ShakeDetector? = null
    private var shakeEnabled = false
    private var volumeShortcutEnabled = false
    private var relayEnabled = true

    private var volumeDownPressCount = 0
    private var lastVolumePressAt = 0L

    private val radioStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncMeshRadioState()
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val app = application as RescueMeshApplication
        shakeDetector = ShakeDetector(this) {
            if (shakeEnabled) {
                armedSosDialog = true
            }
        }

        lifecycleScope.launch {
            app.preferencesRepository.settings.collect { settings ->
                shakeEnabled = settings.shakeSosEnabled
                volumeShortcutEnabled = settings.volumeShortcutEnabled
                relayEnabled = settings.relayParticipationEnabled
                syncMeshRadioState()
            }
        }

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            RescueMeshTheme {
                RescueMeshApp(
                    app = app,
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    initialSessionId = deepLinkSessionId,
                    showArmedSosDialog = armedSosDialog,
                    onDismissArmedSosDialog = { armedSosDialog = false }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        registerReceiver(radioStateReceiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(radioStateReceiver) }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_ARM_SOS_CONFIRM, false)) {
            armedSosDialog = true
        }
        intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.let { sid ->
            deepLinkSessionId = sid
        }
    }

    override fun onResume() {
        super.onResume()
        shakeDetector?.start()
        syncMeshRadioState()
    }

    override fun onPause() {
        shakeDetector?.stop()
        super.onPause()
    }

    private fun syncMeshRadioState() {
        val app = application as RescueMeshApplication
        if (relayEnabled && app.bleTransport.hasRequiredBlePermissions()) {
            if (!RescueMeshForegroundService.start(this)) {
                app.meshCoordinator.start()
            } else {
                app.meshCoordinator.start()
            }
        } else {
            // Update transportStatus accurately (e.g. PERMISSION_MISSING / BLUETOOTH_DISABLED)
            app.meshCoordinator.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeShortcutEnabled && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event?.repeatCount == 0) {
            val now = System.currentTimeMillis()
            if (now - lastVolumePressAt > 1_500L) {
                volumeDownPressCount = 0
            }
            lastVolumePressAt = now
            volumeDownPressCount++
            if (volumeDownPressCount >= 3) {
                volumeDownPressCount = 0
                armedSosDialog = true
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
