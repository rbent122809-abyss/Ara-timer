package com.ara.timer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ara.timer.ui.AraTheme
import com.ara.timer.ui.TimerActions
import com.ara.timer.ui.TimerScreen
import com.ara.timer.ui.TimerUiState
import com.ara.timer.ui.Warning
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var previewPlayer: RingPlayer
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewing by mutableStateOf(false)
    private val endPreview = Runnable { stopPreview() }

    /** Asked the first time Start is pressed; the timer starts whatever the answer. */
    private val notificationPermissionThenStart =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTimer() }

    /** Asked from the "Notifications are off" card. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val actions = TimerActions(
        onStart = ::onStartClicked,
        onStop = ::onStopClicked,
        onVibrateChange = TimerController::setVibrate,
        onTogglePreview = ::togglePreview,
        onWarningAction = ::onWarningAction,
        onWarningDismiss = { if (it == Warning.Battery) TimerRepository.dismissBatteryTip() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        previewPlayer = RingPlayer(this)
        setContent {
            AraTheme {
                TimerRoute()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Brings a running session's service back if the app's process had been killed.
        TimerController.ensureService(this)
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    @Composable
    private fun TimerRoute() {
        val session by TimerRepository.session.collectAsStateWithLifecycle()
        val vibrate by TimerRepository.vibrate.collectAsStateWithLifecycle()
        val batteryTipDismissed by TimerRepository.batteryTipDismissed.collectAsStateWithLifecycle()
        val lifecycleOwner = LocalLifecycleOwner.current

        // Clock for the countdown; only ticks while a session runs and the screen is visible.
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(session.running, lifecycleOwner) {
            now = SystemClock.elapsedRealtime()
            if (session.running) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        now = SystemClock.elapsedRealtime()
                        delay(TICK_MS)
                    }
                }
            }
        }

        // Phone settings that affect the rings; re-checked (off the main thread) while the
        // screen is visible.
        var status by remember { mutableStateOf(DeviceStatus.read(this)) }
        var ringtone by remember { mutableStateOf<RingtoneLabel>(RingtoneLabel.Unnamed) }
        var wallClockOffset by remember { mutableLongStateOf(wallClockOffsetNow()) }
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ringtone = withContext(Dispatchers.IO) { RingtoneLabel.read(applicationContext) }
                while (true) {
                    status = withContext(Dispatchers.IO) { DeviceStatus.read(applicationContext) }
                    wallClockOffset = wallClockOffsetNow()
                    delay(STATUS_REFRESH_MS)
                }
            }
        }

        val warnings = buildList {
            if (status.alarmVolumeMuted) add(Warning.Volume)
            if (status.notificationsOff) add(Warning.Notifications)
            if (status.exactAlarmsDenied) add(Warning.ExactAlarms)
            if (status.batteryRestricted && !batteryTipDismissed) add(Warning.Battery)
        }
        val ringtoneText = when (val label = ringtone) {
            is RingtoneLabel.Named -> label.title
            RingtoneLabel.Unnamed -> getString(R.string.phone_ringtone)
            RingtoneLabel.Silent -> getString(R.string.ringtone_silent)
        }
        val state = TimerUiState.from(
            session = session,
            now = now,
            wallClockOffset = wallClockOffset,
            formatClock = { DateFormat.getTimeFormat(this).format(Date(it)) },
            ringtoneText = ringtoneText,
            vibrate = vibrate,
            previewing = previewing,
            warnings = warnings,
        )
        LaunchedEffect(state.ringing) {
            if (state.ringing) stopPreview()
        }
        TimerScreen(state, actions)
    }

    /** Converts elapsedRealtime to clock time; whole seconds so "Next at" never flickers. */
    private fun wallClockOffsetNow(): Long {
        val offset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return (offset + 500L) / 1000L * 1000L
    }

    private fun onStartClicked() {
        stopPreview()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission() &&
            !TimerRepository.notificationPermissionAsked
        ) {
            TimerRepository.notificationPermissionAsked = true
            notificationPermissionThenStart.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startTimer()
        }
    }

    private fun startTimer() {
        if (!TimerController.start(this)) {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onStopClicked() {
        stopPreview()
        TimerController.stop(this)
    }

    private fun togglePreview() {
        if (previewing) {
            stopPreview()
            return
        }
        val session = TimerRepository.session.value
        if (session.running && session.displayAt(SystemClock.elapsedRealtime()).isRinging) return
        previewPlayer.start(vibrate = false)
        previewing = true
        previewHandler.removeCallbacks(endPreview)
        previewHandler.postDelayed(endPreview, PREVIEW_MS)
    }

    private fun stopPreview() {
        previewHandler.removeCallbacks(endPreview)
        if (previewing) {
            previewPlayer.stop()
            previewing = false
        }
    }

    private fun onWarningAction(warning: Warning) {
        when (warning) {
            Warning.Volume -> raiseAlarmVolume()
            Warning.Notifications -> fixNotifications()
            Warning.ExactAlarms -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
            }
            Warning.Battery -> requestBatteryExemption()
        }
    }

    private fun raiseAlarmVolume() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return
        val target = (max * 0.7f).roundToInt().coerceIn(1, max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            // e.g. Do Not Disturb policy; let the user change it themselves.
            openSettings(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
    }

    private fun fixNotifications() {
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission() &&
            (!TimerRepository.notificationPermissionAsked ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        if (canAsk) {
            TimerRepository.notificationPermissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    @SuppressLint("BatteryLife") // Sideloaded timer app: the direct prompt is the friendliest option.
    private fun requestBatteryExemption() {
        val prompted = openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))
        if (!prompted) openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun packageUri(): Uri = Uri.fromParts("package", packageName, null)

    private fun openSettings(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val TICK_MS = 100L
        const val STATUS_REFRESH_MS = 1_000L
        const val PREVIEW_MS = 4_000L
    }
}
