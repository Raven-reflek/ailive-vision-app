package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标定框整体拖动的几何:点是否在框内、保持形状的平移与整体夹紧。 */
class QuadMathTest {
    private val eps = 1e-5f

    private fun q(vararg xy: Float) = List(xy.size / 2) { RatioPoint(xy[it * 2], xy[it * 2 + 1]) }

    // 一个典型的"手机屏幕"梯形(和 AlignOverlayView 的初始框同量级)
    private val phone = q(0.18f, 0.09f, 0.83f, 0.11f, 0.81f, 0.92f, 0.20f, 0.90f)

    /** 两两点之间的向量(相对第 0 个点)——平移后必须完全不变,就是"形状和比例没变"。 */
    private fun shapeOf(p: List<RatioPoint>) = p.map { Pair(it.x - p[0].x, it.y - p[0].y) }

    private fun assertSameShape(a: List<RatioPoint>, b: List<RatioPoint>) {
        val sa = shapeOf(a); val sb = shapeOf(b)
        for (i in sa.indices) {
            assertEquals("点$i dx", sa[i].first, sb[i].first, eps)
            assertEquals("点$i dy", sa[i].second, sb[i].second, eps)
        }
    }

    // ── contains ──
    @Test fun contains_centerOfPhone() = assertTrue(QuadMath.contains(phone, 0.5f, 0.5f))

    @Test fun contains_outside() {
        assertFalse(QuadMath.contains(phone, 0.05f, 0.5f))     // 左边外
        assertFalse(QuadMath.contains(phone, 0.95f, 0.5f))     // 右边外
        assertFalse(QuadMath.contains(phone, 0.5f, 0.02f))     // 上边外
        assertFalse(QuadMath.contains(phone, 0.5f, 0.97f))     // 下边外
    }

    @Test fun contains_justInsideEachCorner_isInside() {
        assertTrue(QuadMath.contains(phone, 0.20f, 0.12f))     // 左上角内侧
        assertTrue(QuadMath.contains(phone, 0.80f, 0.13f))     // 右上角内侧
        assertTrue(QuadMath.contains(phone, 0.79f, 0.90f))     // 右下角内侧
        assertTrue(QuadMath.contains(phone, 0.22f, 0.88f))     // 左下角内侧
    }

    @Test fun contains_slantedEdgesAreRespected() {
        // 梯形上窄下宽:同一个高度,靠近左边斜线内外一点点,结果要不同
        val trapezoid = q(0.3f, 0.1f, 0.7f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        assertFalse("左斜边外侧", QuadMath.contains(trapezoid, 0.15f, 0.2f))
        assertTrue("左斜边内侧", QuadMath.contains(trapezoid, 0.30f, 0.2f))
    }

    @Test fun contains_concaveQuad() {
        // 凹四边形(箭头形):(0.9,0.1)→(0.5,0.4)→(0.1,0.9) 这一边向内凹,右下方的缺口是外部
        val arrow = q(0.1f, 0.1f, 0.9f, 0.1f, 0.5f, 0.4f, 0.1f, 0.9f)
        assertTrue("上方实体部分", QuadMath.contains(arrow, 0.5f, 0.2f))
        assertTrue("左侧实体部分", QuadMath.contains(arrow, 0.15f, 0.6f))
        assertFalse("右下缺口里", QuadMath.contains(arrow, 0.6f, 0.6f))
    }

    @Test fun contains_bowtie_evenOddRule() {
        // 蝴蝶结(自交):对角线 (0.1,0.1)-(0.9,0.9) 与 (0.9,0.1)-(0.1,0.9) 交于中心,竖边在 x=0.1 和 x=0.9。
        // 围住的是左、右两个三角形;上、下的 V 形开口是外部(没有横边封住)。奇偶规则应据此判断。
        val bow = q(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)
        assertTrue("左三角形", QuadMath.contains(bow, 0.15f, 0.5f))
        assertTrue("右三角形", QuadMath.contains(bow, 0.85f, 0.5f))
        assertFalse("上方 V 形开口", QuadMath.contains(bow, 0.5f, 0.2f))
        assertFalse("下方 V 形开口", QuadMath.contains(bow, 0.5f, 0.8f))
    }

    @Test fun contains_tooFewPoints_isFalse() = assertFalse(QuadMath.contains(q(0f, 0f, 1f, 1f), 0.5f, 0.5f))

    // ── translateClamped ──
    @Test fun translate_inBounds_movesAllPointsByTheSameDelta() {
        val out = QuadMath.translateClamped(phone, 0.05f, -0.03f)
        for (i in phone.indices) {
            assertEquals(phone[i].x + 0.05f, out[i].x, eps)
            assertEquals(phone[i].y - 0.03f, out[i].y, eps)
        }
        assertSameShape(phone, out)
    }

    @Test fun translate_zeroDelta_unchanged() {
        val out = QuadMath.translateClamped(phone, 0f, 0f)
        for (i in phone.indices) { assertEquals(phone[i].x, out[i].x, eps); assertEquals(phone[i].y, out[i].y, eps) }
    }

    @Test fun translate_toLeftEdge_stopsAsWhole_shapePreserved() {
        val out = QuadMath.translateClamped(phone, -5f, 0f)
        assertEquals("最左的点贴到 0", 0f, out.minOf { it.x }, eps)
        assertSameShape(phone, out)                                   // 不能被压扁
    }

    @Test fun translate_toRightEdge_stopsAsWhole_shapePreserved() {
        val out = QuadMath.translateClamped(phone, 5f, 0f)
        assertEquals("最右的点贴到 1", 1f, out.maxOf { it.x }, eps)
        assertSameShape(phone, out)
    }

    @Test fun translate_toTopAndBottomEdges_shapePreserved() {
        val up = QuadMath.translateClamped(phone, 0f, -5f)
        assertEquals(0f, up.minOf { it.y }, eps); assertSameShape(phone, up)
        val down = QuadMath.translateClamped(phone, 0f, 5f)
        assertEquals(1f, down.maxOf { it.y }, eps); assertSameShape(phone, down)
    }

    @Test fun translate_toCorner_bothAxesClampedIndependently() {
        val out = QuadMath.translateClamped(phone, 5f, 5f)
        assertEquals(1f, out.maxOf { it.x }, eps)
        assertEquals(1f, out.maxOf { it.y }, eps)
        assertSameShape(phone, out)
    }

    @Test fun translate_alwaysStaysWithinUnitSquare() {
        for (dx in listOf(-9f, -0.4f, 0f, 0.4f, 9f)) for (dy in listOf(-9f, -0.4f, 0f, 0.4f, 9f)) {
            val out = QuadMath.translateClamped(phone, dx, dy)
            assertTrue("dx=$dx dy=$dy", out.all { it.x in -eps..1f + eps && it.y in -eps..1f + eps })
        }
    }

    @Test fun translate_clampsAgainstStartNotAccumulated() {
        // 从同一个起点反复算:先拖到很远再拖回来,应该能回到合理位置(逐次累计夹紧会"卡"在边缘)
        val far = QuadMath.translateClamped(phone, 5f, 0f)
        val back = QuadMath.translateClamped(phone, 0.02f, 0f)         // 依旧以 phone 为起点
        assertEquals(phone[0].x + 0.02f, back[0].x, eps)
        assertTrue(far[0].x > back[0].x)
    }

    @Test fun translate_fullScreenQuad_cannotMove() {
        val full = q(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val out = QuadMath.translateClamped(full, 0.3f, -0.3f)
        for (i in full.indices) { assertEquals(full[i].x, out[i].x, eps); assertEquals(full[i].y, out[i].y, eps) }
    }

    @Test fun translate_alreadyOutOfBounds_doesNotThrow() {
        // 理论上不会出现(点都被夹在 [0,1]),但范围反过来时不能崩
        val bad = q(-0.2f, 0.1f, 1.3f, 0.1f, 1.3f, 0.9f, -0.2f, 0.9f)
        val out = QuadMath.translateClamped(bad, 0.1f, 0.1f)
        assertEquals(4, out.size)
    }

    @Test fun translate_emptyList_returnsEmpty() = assertTrue(QuadMath.translateClamped(emptyList(), 1f, 1f).isEmpty())
}
