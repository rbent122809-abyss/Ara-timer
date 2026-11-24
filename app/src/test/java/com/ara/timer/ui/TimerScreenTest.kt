package com.ara.timer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ara.timer.Session
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

/**
 * Renders the screen in its main states (saved as PNGs under app/build/screenshots) and
 * checks the Start/Stop button. Galaxy A56 screen: 1080 x 2340 px.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class TimerScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val minute = 60_000L
    private val cycleStart = 1_000_000L

    /** Three rings done, 2:28 into the fourth countdown. */
    private val counting = Session(running = true, cycleStart = cycleStart, rings = 3)
    private val countingNow = cycleStart + 2 * minute + 28_000

    /** The fourth ring started 4 seconds ago. */
    private val ringing = Session(running = true, cycleStart = cycleStart, ringStart = cycleStart + 5 * minute, rings = 4)
    private val ringingNow = cycleStart + 5 * minute + 4_000

    private fun state(
        session: Session,
        now: Long,
        warnings: List<Warning> = emptyList(),
        ringtone: String = "Over the Horizon",
    ) = TimerUiState.from(
        session = session,
        now = now,
        wallClockOffset = 0L,
        formatClock = { "3:45 PM" },
        ringtoneText = ringtone,
        vibrate = true,
        previewing = false,
        warnings = warnings,
    )

    private fun state(running: Boolean, warnings: List<Warning> = emptyList()) =
        if (running) state(counting, countingNow, warnings) else state(Session.IDLE, 0L, warnings)

    private fun snap(name: String, state: TimerUiState, actions: TimerActions = TimerActions.NoOp) {
        compose.setContent { AraTheme { TimerScreen(state, actions) } }
        compose.waitForIdle()
        val dir = File(System.getProperty("ara.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        val root = compose.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun idle() {
        val s = state(running = false)
        assertEquals("05:00", s.countdownText)
        snap("1_idle", s)
        compose.onNodeWithText("Start").assertExists()
        compose.onNodeWithText("Ready").assertExists()
    }

    @Test
    fun running() {
        val s = state(running = true)
        assertEquals("02:32", s.countdownText)
        assertEquals("17:28", s.elapsedText)
        snap("2_running", s)
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("02:32").assertExists()
        compose.onNodeWithText("until next ring").assertExists()
    }

    @Test
    fun ringing_showsTheTimerPaused() {
        val s = state(ringing, ringingNow)
        assertTrue(s.ringing)
        assertEquals(8, s.ringSecondsLeft)
        assertEquals("elapsed paused at 4 x 5:00", "20:00", s.elapsedText)
        assertEquals(1f, s.cycleFraction)
        snap("3_ringing", s)
        compose.onAllNodesWithText("Ringing").assertCountEquals(2) // status pill + dial
        compose.onNodeWithText("Timer paused").assertExists()
        compose.onNodeWithText("resumes in 8 s").assertExists()
        compose.onNodeWithText("20:00").assertExists()
    }

    @Test
    fun pauseAndResume_asSeenOnScreen() {
        val ringEnd = ringing.ringEnd
        // Throughout the ring: elapsed frozen at 20:00, ring counts down to resuming.
        assertEquals("20:00", state(ringing, ringing.ringStart).elapsedText)
        assertEquals(12, state(ringing, ringing.ringStart).ringSecondsLeft)
        assertEquals("20:00", state(ringing, ringEnd - 1).elapsedText)
        assertEquals(1, state(ringing, ringEnd - 1).ringSecondsLeft)
        // As soon as the 12 seconds are up the screen resumes, even before the service
        // has processed the end of the ring.
        val resumed = state(ringing, ringEnd)
        assertFalse(resumed.ringing)
        assertEquals("05:00", resumed.countdownText)
        assertEquals("20:00", resumed.elapsedText)
        val oneSecondLater = state(ringing, ringEnd + 1_000)
        assertEquals("04:59", oneSecondLater.countdownText)
        assertEquals("20:01", oneSecondLater.elapsedText)
    }

    @Test
    fun warnings() {
        snap(
            "4_warnings",
            state(running = false, warnings = listOf(Warning.Volume, Warning.Battery)),
        )
        compose.onNodeWithText("Turn up").assertExists()
        compose.onNodeWithText("Not now").assertExists()
    }

    @Test
    @Config(qualifiers = "w832dp-h384dp-xxhdpi")
    fun landscape() {
        snap("5_landscape", state(running = true))
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi", fontScale = 1.5f)
    fun smallScreenLargeText() {
        snap("6_small_large_text", state(running = true, warnings = listOf(Warning.Notifications)))
        compose.onNodeWithText("Stop").assertExists()
    }

    @Test
    fun icons() {
        val dir = File(System.getProperty("ara.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        val context = compose.activity
        fun render(name: String, resId: Int, sizePx: Int, background: Int) {
            val drawable = context.getDrawable(resId)!!
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        render("7_launcher_icon", com.ara.timer.R.mipmap.ic_launcher, 432, android.graphics.Color.WHITE)
        render("8_status_icon", com.ara.timer.R.drawable.ic_stat_timer, 96, android.graphics.Color.DKGRAY)
    }

    @Test
    fun startStopButton_callsTheRightAction_andIgnoresAccidentalDoubleTap() {
        var starts = 0
        var stops = 0
        val actions = TimerActions(
            onStart = { starts++ },
            onStop = { stops++ },
            onVibrateChange = {},
            onTogglePreview = {},
            onWarningAction = {},
            onWarningDismiss = {},
        )
        var running by mutableStateOf(false)
        compose.setContent { AraTheme { TimerScreen(state(running = running), actions) } }
        val start = compose.onNodeWithContentDescription("Start the cleaning timer")
        start.performClick()
        start.performClick() // immediate second tap is ignored
        assertEquals(1, starts)
        assertEquals(0, stops)

        running = true
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        compose.onNodeWithContentDescription("Stop the cleaning timer").performClick()
        assertEquals(1, stops)
        assertEquals(1, starts)
    }
}
