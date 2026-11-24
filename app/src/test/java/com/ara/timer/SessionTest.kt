package com.ara.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {
    private val minute = 60_000L
    private val start = 1_000_000L
    private val session = Session(running = true, startElapsed = start, startWall = 0L)

    @Test
    fun firstRingIsDueFiveMinutesAfterStart() {
        assertEquals(start + 5 * minute, session.nextDueTime)
        assertEquals(0, session.dueIndexAt(start))
        assertEquals(0, session.dueIndexAt(start + 5 * minute - 1_000))
        assertEquals(1, session.dueIndexAt(start + 5 * minute))
        assertEquals(1, session.dueIndexAt(start + 10 * minute - 1_000))
        assertEquals(2, session.dueIndexAt(start + 10 * minute))
    }

    @Test
    fun aTriggerAHairEarlyCountsAsOnTime() {
        assertEquals(1, session.dueIndexAt(start + 5 * minute - 200))
    }

    @Test
    fun scheduleIsAnchoredToStartNotToWhenARingWasHandled() {
        val afterFirst = session.copy(lastIndex = 1)
        assertEquals(start + 10 * minute, afterFirst.nextDueTime)
        val afterSkips = session.copy(lastIndex = 7)
        assertEquals(start + 40 * minute, afterSkips.nextDueTime)
    }

    @Test
    fun countdownWrapsEveryFiveMinutes() {
        assertEquals(5 * minute, session.msUntilNextRing(start))
        assertEquals(1_000L, session.msUntilNextRing(start + 5 * minute - 1_000))
        assertEquals(5 * minute, session.msUntilNextRing(start + 5 * minute))
        assertEquals(4 * minute, session.msUntilNextRing(start + 11 * minute))
    }

    @Test
    fun nextRingWallTimeIsStable() {
        val s = session.copy(startWall = 50_000L)
        assertEquals(50_000L + 5 * minute, s.nextRingWallTime(start))
        assertEquals(50_000L + 5 * minute, s.nextRingWallTime(start + 4 * minute + 59_999))
        assertEquals(50_000L + 10 * minute, s.nextRingWallTime(start + 5 * minute))
    }

    @Test
    fun clockBeforeStartNeverGoesNegative() {
        assertEquals(0, session.dueIndexAt(start - 10 * minute))
        assertEquals(0L, session.elapsedMs(start - 1))
    }

    @Test
    fun formatting() {
        assertEquals("05:00", TimeFormat.countdown(5 * minute))
        assertEquals("04:59", TimeFormat.countdown(5 * minute - 1_000))
        assertEquals("00:01", TimeFormat.countdown(1))
        assertEquals("00:00", TimeFormat.countdown(0))
        assertEquals("0:00", TimeFormat.elapsed(0))
        assertEquals("7:05", TimeFormat.elapsed(7 * minute + 5_999))
        assertEquals("1:02:03", TimeFormat.elapsed(62 * minute + 3_000))
    }
}
