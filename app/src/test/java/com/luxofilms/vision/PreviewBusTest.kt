package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 预览信箱的状态与缩放尺寸计算(不涉及真正的位图,JVM 里即可测)。 */
class PreviewBusTest {

    @Before fun setUp() = PreviewBus.setActive(false)

    @Test fun defaultInactive_andEmpty() {
        assertFalse(PreviewBus.active)
        assertNull(PreviewBus.latest())
        assertTrue(PreviewBus.lines().isEmpty())
        assertEquals(0L, PreviewBus.linesAt())
    }

    @Test fun setActiveTrue_enables() {
        PreviewBus.setActive(true)
        assertTrue(PreviewBus.active)
    }

    @Test fun setActiveFalse_clearsEverything_andBumpsVersion() {
        PreviewBus.setActive(true)
        PreviewBus.noteLines(listOf("弹幕"), 123L)
        val v = PreviewBus.version()
        PreviewBus.setActive(false)
        assertFalse(PreviewBus.active)
        assertTrue("离开预览页要清掉识别文字,下次进来不闪旧内容", PreviewBus.lines().isEmpty())
        assertEquals(0L, PreviewBus.linesAt())
        assertNull(PreviewBus.latest())
        assertNotEquals("清空要让页面感知到", v, PreviewBus.version())
    }

    // ── "识别用的画面"是否展开(决定要不要生成识别小图)──
    @Test fun cropWanted_defaultsOff() = assertFalse(PreviewBus.cropWanted)

    @Test fun cropWanted_canBeToggled() {
        PreviewBus.setCropWanted(true)
        assertTrue(PreviewBus.cropWanted)
        PreviewBus.setCropWanted(false)
        assertFalse(PreviewBus.cropWanted)
    }

    @Test fun setActiveFalse_resetsCropWanted() {
        PreviewBus.setActive(true)
        PreviewBus.setCropWanted(true)
        PreviewBus.setActive(false)
        assertFalse("离开预览页后不能继续要识别小图(否则采集服务白白多做一整套处理)", PreviewBus.cropWanted)
    }

    @Test fun noteLines_storesLinesAndTime() {
        PreviewBus.noteLines(listOf("a", "b"), 42L)
        assertEquals(listOf("a", "b"), PreviewBus.lines())
        assertEquals(42L, PreviewBus.linesAt())
    }

    @Test fun clear_resetsLines() {
        PreviewBus.noteLines(listOf("a"), 42L)
        PreviewBus.clear()
        assertTrue(PreviewBus.lines().isEmpty())
        assertEquals(0L, PreviewBus.linesAt())
    }

    // ── 缩放尺寸 ──
    @Test fun scaledSize_downscalesKeepingRatio() {
        assertEquals(Pair(640, 360), PreviewBus.scaledSize(1280, 720, 640))
        assertEquals(Pair(480, 747), PreviewBus.scaledSize(900, 1400, 480))     // 1400*480/900=746.67
    }

    @Test fun scaledSize_neverUpscales() {
        assertEquals(Pair(300, 200), PreviewBus.scaledSize(300, 200, 640))
        assertEquals(Pair(640, 360), PreviewBus.scaledSize(640, 360, 640))
    }

    @Test fun scaledSize_extremeRatio_neverZero() {
        val (w, h) = PreviewBus.scaledSize(10_000, 1, 480)
        assertEquals(480, w)
        assertTrue("高度至少 1 像素", h >= 1)
    }

    @Test fun scaledSize_badInput_returnsOneByOne() {
        assertEquals(Pair(1, 1), PreviewBus.scaledSize(0, 100, 480))
        assertEquals(Pair(1, 1), PreviewBus.scaledSize(100, 0, 480))
        assertEquals(Pair(1, 1), PreviewBus.scaledSize(100, 100, 0))
    }
}
