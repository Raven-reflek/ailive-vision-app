package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕区(正视图里的矩形)映射回摄像头原图的透视反算。预览页画的蓝框必须和实际裁剪识别的区域严格一致,
 * 否则会误导店员——所以用几何上能手算的例子逐项验证。
 */
class PerspectiveMathTest {
    private val eps = 1e-4f

    private fun assertPt(exp: RatioPoint, act: RatioPoint, msg: String = "") {
        assertEquals("$msg x", exp.x, act.x, eps)
        assertEquals("$msg y", exp.y, act.y, eps)
    }

    private fun quad(vararg xy: Float) = List(4) { RatioPoint(xy[it * 2], xy[it * 2 + 1]) }

    private val unit = quad(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    // ── 角点必须精确落在四边形的角上(单位正方形的四个角 → 屏幕四边形的四个角) ──
    @Test fun unitCorners_mapToQuadCorners_forSkewedQuad() {
        val q = quad(0.30f, 0.20f, 0.80f, 0.25f, 0.75f, 0.90f, 0.28f, 0.85f)
        assertPt(q[0], PerspectiveMath.mapUnitSquareToQuad(q, 0f, 0f), "左上")
        assertPt(q[1], PerspectiveMath.mapUnitSquareToQuad(q, 1f, 0f), "右上")
        assertPt(q[2], PerspectiveMath.mapUnitSquareToQuad(q, 1f, 1f), "右下")
        assertPt(q[3], PerspectiveMath.mapUnitSquareToQuad(q, 0f, 1f), "左下")
    }

    // ── 恒等 / 仿射 ──
    @Test fun identityQuad_leavesRectUnchanged() {
        val r = RatioRect(0.1f, 0.6f, 0.9f, 0.95f)
        val out = PerspectiveMath.mapRectToQuad(r, unit)
        assertPt(RatioPoint(0.1f, 0.6f), out[0]); assertPt(RatioPoint(0.9f, 0.6f), out[1])
        assertPt(RatioPoint(0.9f, 0.95f), out[2]); assertPt(RatioPoint(0.1f, 0.95f), out[3])
    }

    @Test fun axisAlignedQuad_scalesAndOffsetsRect() {
        // 屏幕占画面的 [0.2,0.8]x[0.1,0.9]:正视图里 (0.5,0.5) 应落在画面 (0.5,0.5)
        val q = quad(0.2f, 0.1f, 0.8f, 0.1f, 0.8f, 0.9f, 0.2f, 0.9f)
        assertPt(RatioPoint(0.5f, 0.5f), PerspectiveMath.mapUnitSquareToQuad(q, 0.5f, 0.5f))
        val out = PerspectiveMath.mapRectToQuad(RatioRect(0f, 0.5f, 1f, 1f), q)   // 下半屏
        assertPt(RatioPoint(0.2f, 0.5f), out[0]); assertPt(RatioPoint(0.8f, 0.5f), out[1])
        assertPt(RatioPoint(0.8f, 0.9f), out[2]); assertPt(RatioPoint(0.2f, 0.9f), out[3])
    }

    @Test fun parallelogram_isAffine_midpointIsAverage() {
        // 平行四边形(dx3=dy3=0):退化成仿射,中点就是四角平均
        val q = quad(0.1f, 0.1f, 0.6f, 0.2f, 0.7f, 0.8f, 0.2f, 0.7f)
        assertPt(RatioPoint(0.4f, 0.45f), PerspectiveMath.mapUnitSquareToQuad(q, 0.5f, 0.5f))
    }

    // ── 真正的透视(梯形):手算可验证 ──
    @Test fun trapezoid_center_isIntersectionOfDiagonals() {
        // 上宽下窄的梯形 (0,0)(1,0)(0.8,1)(0.2,1):对角线交点 = (0.5, 0.625)。
        // 透视变换下正视图中心恒映射到四边形对角线的交点(而不是几何中心 0.5,0.5)——这正是"摆正"时远处被压缩的体现
        val q = quad(0f, 0f, 1f, 0f, 0.8f, 1f, 0.2f, 1f)
        assertPt(RatioPoint(0.5f, 0.625f), PerspectiveMath.mapUnitSquareToQuad(q, 0.5f, 0.5f))
    }

    @Test fun trapezoid_edgeMidpoints_stayOnEdges() {
        val q = quad(0f, 0f, 1f, 0f, 0.8f, 1f, 0.2f, 1f)
        val topMid = PerspectiveMath.mapUnitSquareToQuad(q, 0.5f, 0f)      // 上边中点 → 仍在上边上、居中
        assertPt(RatioPoint(0.5f, 0f), topMid)
        val bottomMid = PerspectiveMath.mapUnitSquareToQuad(q, 0.5f, 1f)   // 下边中点 → 仍在下边上、居中
        assertPt(RatioPoint(0.5f, 1f), bottomMid)
        // 左边:直线上的点映射后仍在直线 (0,0)-(0.2,1) 上,x = 0.2*y
        val left = PerspectiveMath.mapUnitSquareToQuad(q, 0f, 0.5f)
        assertEquals(0.2f * left.y, left.x, eps)
    }

    @Test fun mapRectToQuad_returnsFourCorners_clockwiseFromTopLeft() {
        val q = quad(0.30f, 0.20f, 0.80f, 0.25f, 0.75f, 0.90f, 0.28f, 0.85f)
        val out = PerspectiveMath.mapRectToQuad(RatioRect(0.1f, 0.6f, 0.9f, 0.95f), q)
        assertEquals(4, out.size)
        // 顺时针且保持相对位置:左上.x < 右上.x,左上.y < 左下.y,右下 在 右上 下方
        assertTrue(out[0].x < out[1].x); assertTrue(out[3].x < out[2].x)
        assertTrue(out[0].y < out[3].y); assertTrue(out[1].y < out[2].y)
    }

    @Test fun fullRect_mapsToExactlyTheScreenQuad() {
        val q = quad(0.30f, 0.20f, 0.80f, 0.25f, 0.75f, 0.90f, 0.28f, 0.85f)
        val out = PerspectiveMath.mapRectToQuad(RatioRect(0f, 0f, 1f, 1f), q)
        for (i in 0..3) assertPt(q[i], out[i], "角$i")
    }

    @Test(expected = IllegalArgumentException::class)
    fun needFourPoints() { PerspectiveMath.mapUnitSquareToQuad(listOf(RatioPoint(0f, 0f)), 0f, 0f) }
}
