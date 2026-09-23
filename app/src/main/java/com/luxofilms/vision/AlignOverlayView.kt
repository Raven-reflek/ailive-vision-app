package com.luxofilms.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 标定用的可拖拽覆盖层,叠在 PreviewView(Step1)或静态参考图(Step2)上面,尺寸跟下面那层完全一致
 * (同一个 FrameLayout 里 match_parent 叠放),这样触摸坐标和 View 自身宽高就能直接当"比例坐标"用,
 * 不需要额外处理 letterbox(标定引导页固定用铺满型 scaleType,详见 CameraAlignActivity)。
 *
 * 两种模式:
 * - QUAD:4个可拖拽角点,连线画出任意四边形(用于"框住整台被摄手机屏幕")
 * - RECT:4个角落手柄,只能拖出轴对齐矩形(用于"框住弹幕区域",已经是摆正后的画面,不需要任意四边形)
 */
class AlignOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { QUAD, RECT }

    var mode: Mode = Mode.QUAD
        set(value) { field = value; invalidate() }

    /** 归一化点(0~1),QUAD模式用,固定4个,顺序:左上、右上、右下、左下 */
    private val quad = mutableListOf(
        PointF(0.18f, 0.09f), PointF(0.83f, 0.11f), PointF(0.81f, 0.92f), PointF(0.20f, 0.90f),
    )
    /** 归一化矩形,RECT模式用 */
    private var rect = RectF(0.06f, 0.62f, 0.94f, 0.93f)

    private var dragIndex = -1        // QUAD模式:正在拖哪个角点,-1=没有
    private var dragCorner = -1       // RECT模式:0=左上 1=右上 2=右下 3=左下 4=整体平移,-1=没有
    private var dragStartTouch = PointF()
    private var dragStartRect = RectF()

    private val handleRadiusPx = resources.displayMetrics.density * 12
    private val touchSlopPx = resources.displayMetrics.density * 28   // 手指判定命中角点的容差,比手柄本身大一圈方便点

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F97316"); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2.5f
    }
    private val fillDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99000000") }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F97316") }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2
    }

    fun getQuad(): List<PointF> = quad.map { PointF(it.x, it.y) }
    fun setQuad(points: List<PointF>) {
        if (points.size == 4) { quad.clear(); quad.addAll(points.map { PointF(it.x, it.y) }); invalidate() }
    }
    fun getRect(): RectF = RectF(rect)
    fun setRect(r: RectF) { rect = RectF(r); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        when (mode) {
            Mode.QUAD -> drawQuad(canvas, w, h)
            Mode.RECT -> drawRect(canvas, w, h)
        }
    }

    private fun drawQuad(canvas: Canvas, w: Float, h: Float) {
        val pts = quad.map { PointF(it.x * w, it.y * h) }
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        }
        for (p in pts) {
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleFill)
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleStroke)
        }
    }

    private fun drawRect(canvas: Canvas, w: Float, h: Float) {
        val r = RectF(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h)
        // 框外整体调暗,突出选中区域(店员一眼就能看出"这块会被识别")
        canvas.drawRect(0f, 0f, w, r.top, fillDim)
        canvas.drawRect(0f, r.bottom, w, h, fillDim)
        canvas.drawRect(0f, r.top, r.left, r.bottom, fillDim)
        canvas.drawRect(r.right, r.top, w, r.bottom, fillDim)
        canvas.drawRect(r, linePaint)
        for (p in listOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.right, r.bottom), PointF(r.left, r.bottom))) {
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleFill)
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return false
        val touch = PointF(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (mode) {
                    Mode.QUAD -> dragIndex = nearestQuadIndex(touch, w, h)
                    Mode.RECT -> { dragCorner = nearestRectCorner(touch, w, h); dragStartTouch = touch; dragStartRect = RectF(rect) }
                }
                return dragIndex >= 0 || dragCorner >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.QUAD && dragIndex >= 0) {
                    quad[dragIndex] = PointF((touch.x / w).coerceIn(0f, 1f), (touch.y / h).coerceIn(0f, 1f))
                    invalidate(); return true
                }
                if (mode == Mode.RECT && dragCorner >= 0) {
                    val dx = (touch.x - dragStartTouch.x) / w
                    val dy = (touch.y - dragStartTouch.y) / h
                    rect = when (dragCorner) {
                        0 -> RectF((dragStartRect.left + dx).coerceIn(0f, rect.right - 0.03f), (dragStartRect.top + dy).coerceIn(0f, rect.bottom - 0.03f), rect.right, rect.bottom)
                        1 -> RectF(rect.left, (dragStartRect.top + dy).coerceIn(0f, rect.bottom - 0.03f), (dragStartRect.right + dx).coerceIn(rect.left + 0.03f, 1f), rect.bottom)
                        2 -> RectF(rect.left, rect.top, (dragStartRect.right + dx).coerceIn(rect.left + 0.03f, 1f), (dragStartRect.bottom + dy).coerceIn(rect.top + 0.03f, 1f))
                        3 -> RectF((dragStartRect.left + dx).coerceIn(0f, rect.right - 0.03f), rect.top, rect.right, (dragStartRect.bottom + dy).coerceIn(rect.top + 0.03f, 1f))
                        4 -> {
                            val rw = dragStartRect.width(); val rh = dragStartRect.height()
                            val nl = (dragStartRect.left + dx).coerceIn(0f, 1f - rw)
                            val nt = (dragStartRect.top + dy).coerceIn(0f, 1f - rh)
                            RectF(nl, nt, nl + rw, nt + rh)
                        }
                        else -> rect
                    }
                    invalidate(); return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragIndex = -1; dragCorner = -1 }
        }
        return false
    }

    private fun nearestQuadIndex(touch: PointF, w: Float, h: Float): Int {
        var best = -1; var bestDist = touchSlopPx
        quad.forEachIndexed { i, p ->
            val d = hypot((p.x * w - touch.x).toDouble(), (p.y * h - touch.y).toDouble()).toFloat()
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** 4个角落手柄命中返回0~3;命中矩形内部(非边缘)返回4表示整体拖动;否则-1 */
    private fun nearestRectCorner(touch: PointF, w: Float, h: Float): Int {
        val corners = listOf(
            PointF(rect.left * w, rect.top * h), PointF(rect.right * w, rect.top * h),
            PointF(rect.right * w, rect.bottom * h), PointF(rect.left * w, rect.bottom * h),
        )
        corners.forEachIndexed { i, p ->
            if (hypot((p.x - touch.x).toDouble(), (p.y - touch.y).toDouble()) < touchSlopPx) return i
        }
        val r = RectF(rect.left * w, rect.top * h, rect.right * w, rect.bottom * h)
        if (r.contains(touch.x, touch.y)) return 4
        return -1
    }
}
