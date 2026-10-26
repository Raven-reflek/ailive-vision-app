package com.luxofilms.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 采集计数:相对秒数、"从未发生"为 null、连续失败的计法(429 既不算成功也不算失败)。用假的单调时钟驱动。 */
class CaptureStatsTest {
    private var now = 1_000_000L
    private val originalClock = CaptureStats.clock

    @Before fun setUp() {
        CaptureStats.clock = { now }
        CaptureStats.reset()
    }

    @After fun tearDown() { CaptureStats.clock = originalClock }

    private fun advance(seconds: Int) { now += seconds * 1000L }

    @Test fun afterReset_neverHappened_isNull_andUptimeStartsAtZero() {
        val s = CaptureStats.snapshot()
        assertNull(s.frameAgo); assertNull(s.ocrAgo); assertNull(s.textAgo); assertNull(s.uploadOkAgo)
        assertEquals(0, s.uploadFailStreak)
        assertEquals(0, s.uptime)
    }

    @Test fun uptime_countsWholeSecondsFromReset() {
        advance(125)
        assertEquals(125, CaptureStats.snapshot().uptime)
        now += 999                                       // 不满整秒的部分舍去
        assertEquals(125, CaptureStats.snapshot().uptime)
    }

    @Test fun agesAreRelativeWholeSeconds() {
        advance(10); CaptureStats.noteFrame()
        advance(3); CaptureStats.noteOcrDone()
        advance(4); CaptureStats.noteText()
        advance(5); CaptureStats.noteUploadOk()
        advance(6)
        val s = CaptureStats.snapshot()
        assertEquals(18, s.frameAgo)
        assertEquals(15, s.ocrAgo)
        assertEquals(11, s.textAgo)
        assertEquals(6, s.uploadOkAgo)
        assertEquals(28, s.uptime)
    }

    @Test fun snapshotAt_explicitNow() {
        CaptureStats.noteFrame()
        assertEquals(7, CaptureStats.snapshot(now + 7_500).frameAgo)
    }

    @Test fun eachSignalIsIndependent() {
        advance(20); CaptureStats.noteFrame()
        val s = CaptureStats.snapshot()
        assertEquals(0, s.frameAgo)
        assertNull(s.ocrAgo); assertNull(s.textAgo); assertNull(s.uploadOkAgo)
    }

    @Test fun reset_clearsEverythingAndRestartsUptime() {
        advance(30); CaptureStats.noteFrame(); CaptureStats.noteOcrDone(); CaptureStats.noteText(); CaptureStats.noteUploadOk()
        CaptureStats.noteUploadFailed(); CaptureStats.noteUploadFailed()
        advance(30)
        CaptureStats.reset()
        val s = CaptureStats.snapshot()
        assertNull(s.frameAgo); assertNull(s.ocrAgo); assertNull(s.textAgo); assertNull(s.uploadOkAgo)
        assertEquals(0, s.uploadFailStreak)
        assertEquals(0, s.uptime)
    }

    @Test fun failStreak_accumulates_andSuccessResetsIt() {
        CaptureStats.noteUploadFailed(); CaptureStats.noteUploadFailed(); CaptureStats.noteUploadFailed()
        assertEquals(3, CaptureStats.snapshot().uploadFailStreak)
        CaptureStats.noteUploadOk()
        assertEquals(0, CaptureStats.snapshot().uploadFailStreak)
        CaptureStats.noteUploadFailed()
        assertEquals(1, CaptureStats.snapshot().uploadFailStreak)
    }

    @Test fun failure_doesNotTouchLastUploadOk() {
        advance(5); CaptureStats.noteUploadOk()
        advance(10); CaptureStats.noteUploadFailed()
        val s = CaptureStats.snapshot()
        assertEquals(10, s.uploadOkAgo)
        assertEquals(1, s.uploadFailStreak)
    }

    @Test fun rateLimit429_isNeitherSuccessNorFailure() {
        CaptureStats.noteUploadFailed(500)
        advance(10)
        CaptureStats.noteUploadFailed(429)               // 限流:不增加连续失败
        CaptureStats.noteUploadFailed(429)
        val s = CaptureStats.snapshot()
        assertEquals(1, s.uploadFailStreak)
        assertNull("429 也不算上传成功", s.uploadOkAgo)
    }

    @Test fun rateLimit429_doesNotBreakAnExistingStreakOrReset() {
        advance(5); CaptureStats.noteUploadOk()
        CaptureStats.noteUploadFailed(503); CaptureStats.noteUploadFailed(null); CaptureStats.noteUploadFailed(401)
        CaptureStats.noteUploadFailed(429)
        val s = CaptureStats.snapshot()
        assertEquals("连续失败不被 429 清零也不被它加一", 3, s.uploadFailStreak)
        assertEquals(0, s.uploadOkAgo)
    }

    @Test fun clockGoingBackwards_neverNegative() {
        CaptureStats.noteFrame()
        now -= 5_000
        assertEquals(0, CaptureStats.snapshot().frameAgo)
    }

    @Test fun fakeClockStartingAtZero_isNotMistakenForNever() {
        now = 0L
        CaptureStats.reset()
        CaptureStats.noteFrame()
        advance(2)
        assertEquals(2, CaptureStats.snapshot().frameAgo)
        assertEquals(2, CaptureStats.snapshot().uptime)
    }
}
