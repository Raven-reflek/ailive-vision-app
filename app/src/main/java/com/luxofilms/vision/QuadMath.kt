package com.luxofilms.vision

/**
 * 标定框(任意四边形)的纯几何计算,不依赖 android.graphics,可单元测试。
 * 坐标都是内容比例坐标(0~1,相对画面本身,见 AlignOverlayView)。
 */
object QuadMath {

    /**
     * 点 (x, y) 是否在多边形内部(射线法 / 奇偶规则)。凹四边形、扭成蝴蝶结的四边形也能判断——
     * 店员拖角点时可能把框拖成这种形状,不能因此"框内按不住"。恰好落在边上的点结果不保证(手指坐标是浮点,不会精确落在边上)。
     */
    fun contains(pts: List<RatioPoint>, x: Float, y: Float): Boolean {
        if (pts.size < 3) return false
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i].x; val yi = pts[i].y
            val xj = pts[j].x; val yj = pts[j].y
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * 把整个多边形平移 (dx, dy),**保持形状和比例不变**,并保证所有点仍在 [0,1] 内。
     *
     * 越界要按"整体"夹紧,而不是逐点夹紧:逐点夹紧时,靠边的点停住、其余点还在走,框会被压变形。
     * 这里先根据所有点的最小/最大坐标算出整体允许的位移范围,再统一夹紧——拖到边缘整体一起停住。
     * 起始点若已经有越界的(理论上不会),范围反过来时取"不动"那一侧,不会抛异常。
     */
    fun translateClamped(start: List<RatioPoint>, dx: Float, dy: Float): List<RatioPoint> {
        if (start.isEmpty()) return start
        val minX = start.minOf { it.x }; val maxX = start.maxOf { it.x }
        val minY = start.minOf { it.y }; val maxY = start.maxOf { it.y }
        val cdx = clamp(dx, -minX, 1f - maxX)
        val cdy = clamp(dy, -minY, 1f - maxY)
        return start.map { RatioPoint(it.x + cdx, it.y + cdy) }
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (lo > hi) 0f else maxOf(lo, minOf(hi, v))
}
