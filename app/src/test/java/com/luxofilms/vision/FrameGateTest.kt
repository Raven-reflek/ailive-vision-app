package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每帧做什么的判定。最重要的保证:**预览页开不开,都不改变 OCR 的节奏**。
 * 0.6.0 起(ADR-0041)不再有"429 冷却":上传在独立线程里排队,被限流只延后上传,识别照常,所以这里也不再认识限流。
 */
class FrameGateTest {
    private val ocrInterval = 2000L          // 默认 0.5 帧/秒
    private val t0 = 1_000_000L

    private fun decide(
        now: Long, lastOcr: Long = 0L,
        previewActive: Boolean = false, lastPreview: Long = 0L,
    ) = FrameGate.decide(now, lastOcr, ocrInterval, previewActive, lastPreview)

    // ── 预览关闭:与改动前完全一致 ──
    @Test fun previewOff_dueOcr_runsOcrOnly() {
        val d = decide(now = t0, lastOcr = t0 - 2000)
        assertTrue(d.ocr); assertFalse(d.preview); assertFalse(d.skip)
    }

    @Test fun previewOff_notDue_skips() {
        val d = decide(now = t0, lastOcr = t0 - 1999)
        assertTrue(d.skip)
    }

    @Test fun previewOff_longSinceLastOcr_runsOcr_noCooldownAnymore() {
        // 0.6.0 前,收到 429 后 65 秒内这里一律丢帧;现在 OCR 只看采样间隔
        val d = decide(now = t0, lastOcr = t0 - 10_000)
        assertTrue(d.ocr); assertFalse(d.skip)
    }

    // ── 预览打开:OCR 节奏不变,另外多出预览帧 ──
    @Test fun previewOn_ocrDecisionIdenticalToPreviewOff() {
        // 遍历一段时间轴,逐帧对比"预览开/关"两种情况下的 OCR 决定,必须完全相同
        var lastOcrOff = 0L; var lastOcrOn = 0L; var lastPreview = 0L
        var ocrCountOff = 0; var ocrCountOn = 0
        for (step in 0..200) {
            val now = t0 + step * 100L                       // 10 帧/秒的相机输出,共 20 秒
            val off = decide(now, lastOcrOff, previewActive = false)
            val on = decide(now, lastOcrOn, previewActive = true, lastPreview = lastPreview)
            assertEquals("t=$now OCR 决定在预览开/关时必须一致", off.ocr, on.ocr)
            if (off.ocr) { lastOcrOff = now; ocrCountOff++ }
            if (on.ocr) { lastOcrOn = now; ocrCountOn++ }
            if (on.preview) lastPreview = now
        }
        assertEquals(ocrCountOff, ocrCountOn)
        assertEquals("20 秒内 0.5 帧/秒约 10 次", 11, ocrCountOn)   // t=0(首帧)+每 2 秒一次,含端点
    }

    @Test fun previewOn_betweenOcrTicks_previewOnly() {
        val d = decide(now = t0, lastOcr = t0 - 500, previewActive = true, lastPreview = t0 - 400)
        assertFalse(d.ocr); assertTrue(d.preview)
    }

    @Test fun previewOn_ocrAndPreviewSameFrame() {
        val d = decide(now = t0, lastOcr = t0 - 2000, previewActive = true, lastPreview = t0 - 400)
        assertTrue(d.ocr); assertTrue(d.preview)
    }

    // ── 预览自身的节流 ──
    @Test fun preview_throttledTo400ms() {
        assertFalse(decide(now = t0, lastOcr = t0 - 100, previewActive = true, lastPreview = t0 - 399).preview)
        assertTrue(decide(now = t0, lastOcr = t0 - 100, previewActive = true, lastPreview = t0 - 400).preview)
    }

    @Test fun preview_firstFrame_isDue() =
        assertTrue(decide(now = t0, lastOcr = t0 - 100, previewActive = true, lastPreview = 0L).preview)

    @Test fun preview_clockWentBackwards_isDue() =
        assertTrue(decide(now = t0, lastOcr = t0 - 100, previewActive = true, lastPreview = t0 + 5000).preview)

    @Test fun preview_inactive_neverDue() =
        assertFalse(decide(now = t0, lastOcr = t0 - 100, previewActive = false, lastPreview = 0L).preview)

    @Test fun skip_onlyWhenNeither() {
        assertTrue(FrameGate.Decision(ocr = false, preview = false).skip)
        assertFalse(FrameGate.Decision(ocr = true, preview = false).skip)
        assertFalse(FrameGate.Decision(ocr = false, preview = true).skip)
    }
}
