package com.luxofilms.vision

/**
 * 纯 Kotlin 的透视几何,不依赖 android.graphics——Android 的 Matrix 在 JVM 单元测试里是空桩,没法测。
 *
 * 用途:弹幕区域是定义在"摆正后的手机正视图"里的比例矩形(RatioRect);预览页要把它画到**摄像头原图**上,
 * 就得用"摆正"那次透视变换的**反方向**,把这个矩形的四个角映射回原图坐标。
 * 摆正用的是 Matrix.setPolyToPoly(屏幕四边形的 4 个点 → 输出矩形的 4 个角)——4 点对应唯一确定一个透视变换,
 * 所以这里用同一组对应关系反过来算,结果和 FrameProcessor 实际裁剪的区域严格一致(不是近似)。
 */
object PerspectiveMath {

    /**
     * 单位正方形 → 任意四边形的透视映射(Heckbert 公式)。
     * quad 顺时针、左上角开始,依次对应单位正方形的 (0,0) (1,0) (1,1) (0,1),与 correctToFrontView 一致。
     * 比例坐标在两边做的只是各自独立的缩放(仿射),不改变 4 点确定的唯一透视变换,所以直接在比例坐标里算即可。
     */
    fun mapUnitSquareToQuad(quad: List<RatioPoint>, u: Float, v: Float): RatioPoint {
        require(quad.size == 4) { "需要 4 个点" }
        val x0 = quad[0].x.toDouble(); val y0 = quad[0].y.toDouble()
        val x1 = quad[1].x.toDouble(); val y1 = quad[1].y.toDouble()
        val x2 = quad[2].x.toDouble(); val y2 = quad[2].y.toDouble()
        val x3 = quad[3].x.toDouble(); val y3 = quad[3].y.toDouble()

        val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3

        val g: Double; val h: Double
        if (dx3 == 0.0 && dy3 == 0.0) {          // 平行四边形:退化成仿射
            g = 0.0; h = 0.0
        } else {
            val den = dx1 * dy2 - dy1 * dx2
            g = (dx3 * dy2 - dy3 * dx2) / den
            h = (dx1 * dy3 - dy1 * dx3) / den
        }
        val a = x1 - x0 + g * x1; val b = x3 - x0 + h * x3
        val d = y1 - y0 + g * y1; val e = y3 - y0 + h * y3

        val w = g * u + h * v + 1.0
        return RatioPoint(((a * u + b * v + x0) / w).toFloat(), ((d * u + e * v + y0) / w).toFloat())
    }

    /**
     * 把"正视图里的比例矩形"映射到摄像头原图上,返回 4 个角(顺时针,左上开始):左上、右上、右下、左下。
     * 原图上它通常是一个歪的四边形。
     */
    fun mapRectToQuad(rect: RatioRect, screenQuad: List<RatioPoint>): List<RatioPoint> = listOf(
        mapUnitSquareToQuad(screenQuad, rect.left, rect.top),
        mapUnitSquareToQuad(screenQuad, rect.right, rect.top),
        mapUnitSquareToQuad(screenQuad, rect.right, rect.bottom),
        mapUnitSquareToQuad(screenQuad, rect.left, rect.bottom),
    )
}
