package com.ara.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val second = 1_000L
    private val minute = 60_000L
    private val five = TimerConfig.INTERVAL_MS
    private val ring = TimerConfig.RING_DURATION_MS
    private val t0 = 1_000_000L
    private val fresh = Session.start(now = t0, wallNow = 0L, bootCount = 1)

    private fun countdown(s: Session, now: Long) = TimeFormat.countdown(s.countdownMs(now))
    private fun elapsed(s: Session, now: Long) = TimeFormat.elapsed(s.activeMs(now))

    @Test
    fun freshSession_countsDownFromFiveMinutes() {
        assertFalse(fresh.isRinging)
        assertEquals("05:00", countdown(fresh, t0))
        assertEquals("0:00", elapsed(fresh, t0))
        assertEquals("04:59", countdown(fresh, t0 + second))
        assertEquals("0:01", elapsed(fresh, t0 + second))
        assertEquals("00:01", countdown(fresh, t0 + five - 1))
        assertEquals("4:59", elapsed(fresh, t0 + five - 1))
        assertEquals(t0 + five, fresh.nextTransition)
    }

    @Test
    fun ringStartsExactlyWhenTheCountdownReachesZero() {
        assertFalse(fresh.advance(t0 + five - 1).isRinging)
        val s = fresh.advance(t0 + five)
        assertTrue(s.isRinging)
        assertEquals(t0 + five, s.ringStart)
        assertEquals(1, s.rings)
        assertEquals("next wake-up is the end of the ring", t0 + five + ring, s.nextTransition)
    }

    @Test
    fun timerAndElapsedTime_arePausedForTheWholeRing() {
        val s = fresh.advance(t0 + five)
        for (intoRing in listOf(0L, 1L, 6_000L, 11_999L)) {
            val now = t0 + five + intoRing
            assertEquals("countdown frozen", 0L, s.countdownMs(now))
            assertEquals("elapsed frozen at 5:00", "5:00", elapsed(s, now))
        }
        assertEquals(12_000L, s.ringMsLeft(t0 + five))
        assertEquals(6_000L, s.ringMsLeft(t0 + five + 6_000))
        assertEquals(1L, s.ringMsLeft(t0 + five + 11_999))
    }

    @Test
    fun whenTheRingEnds_bothResumeWhereTheyStopped() {
        val end = t0 + five + ring
        val resumed = fresh.advance(t0 + five).advance(end)
        assertFalse(resumed.isRinging)
        assertEquals(end, resumed.cycleStart)
        assertEquals("05:00", countdown(resumed, end))
        assertEquals("5:00", elapsed(resumed, end))
        assertEquals("04:59", countdown(resumed, end + second))
        assertEquals("5:01", elapsed(resumed, end + second))
        assertEquals("next ring 5:00 after the ring ended", end + five, resumed.ringDue)
    }

    @Test
    fun ringsFollow_fiveMinutes_thenTwelveSeconds_andRepeat() {
        var s = fresh
        for (n in 1..12) {
            val due = t0 + n * five + (n - 1) * ring
            s = s.advance(due - 1)
            assertFalse("ring $n not before its time", s.isRinging)
            assertEquals(n - 1, s.rings)
            s = s.advance(due)
            assertTrue("ring $n on time", s.isRinging)
            assertEquals(due, s.ringStart)
            assertEquals(n, s.rings)
            assertEquals(n * five, s.activeMs(due + 5_000))
            s = s.advance(due + ring - 1)
            assertTrue("ring $n lasts 12 s", s.isRinging)
            s = s.advance(due + ring)
            assertFalse("ring $n over after 12 s", s.isRinging)
        }
        assertEquals("an hour of timer time", "1:00:00", elapsed(s, s.cycleStart))
    }

    @Test
    fun lateRing_startsWhenItActuallyHappens_andTheNextCountdownFollowsIt() {
        val late = t0 + five + 30 * second
        // While waiting, the countdown sits at 00:00 and the elapsed time at 5:00.
        assertEquals("00:00", countdown(fresh, late - 1))
        assertEquals("5:00", elapsed(fresh, late - 1))
        val s = fresh.advance(late)
        assertEquals(late, s.ringStart)
        assertEquals(late + ring + five, s.nextRingDue)
    }

    @Test
    fun appKilledMidRing_backLater_ringEndedOnTime_andCountdownContinues() {
        val ringing = fresh.advance(t0 + five)
        val s = ringing.advance(t0 + 7 * minute)
        assertFalse(s.isRinging)
        assertEquals(t0 + five + ring, s.cycleStart)
        assertEquals("03:12", countdown(s, t0 + 7 * minute))
        assertEquals(1, s.rings)
    }

    @Test
    fun backAfterALongTime_ringsOnce_notOncePerMissedInterval() {
        val s = fresh.advance(t0 + five).advance(t0 + 60 * minute)
        assertTrue(s.isRinging)
        assertEquals(2, s.rings)
        assertEquals(t0 + 60 * minute, s.ringStart)
        assertEquals(1, fresh.advance(t0 + 60 * minute).rings)
    }

    @Test
    fun advance_isIdempotent() {
        val times = listOf(t0, t0 + five, t0 + five + 6_000, t0 + five + ring, t0 + 20 * minute)
        for (now in times) {
            val once = fresh.advance(t0 + five).advance(now)
            assertEquals(once, once.advance(now))
        }
    }

    @Test
    fun display_showsAFinishedRingAsOver_butNotADueRingAsStarted() {
        val ringing = fresh.advance(t0 + five)
        val shown = ringing.displayAt(t0 + five + ring + 500)
        assertFalse(shown.isRinging)
        assertEquals(five - 500, shown.countdownMs(t0 + five + ring + 500))
        assertFalse(fresh.displayAt(t0 + five + 100).isRinging)
        assertEquals(ringing, ringing.displayAt(t0 + five + 6_000))
    }

    @Test
    fun nextRingDue() {
        assertEquals(t0 + five, fresh.nextRingDue)
        assertEquals(t0 + 2 * five + ring, fresh.advance(t0 + five).nextRingDue)
    }

    @Test
    fun formatting() {
        assertEquals("05:00", TimeFormat.countdown(five))
        assertEquals("04:59", TimeFormat.countdown(five - 1_000))
        assertEquals("00:01", TimeFormat.countdown(1))
        assertEquals("00:00", TimeFormat.countdown(0))
        assertEquals("0:00", TimeFormat.elapsed(0))
        assertEquals("7:05", TimeFormat.elapsed(7 * minute + 5_999))
        assertEquals("1:02:03", TimeFormat.elapsed(62 * minute + 3_000))
        assertEquals(12, TimeFormat.secondsLeft(12_000))
        assertEquals(1, TimeFormat.secondsLeft(1))
        assertEquals(0, TimeFormat.secondsLeft(0))
    }
}
