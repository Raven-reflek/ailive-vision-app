package com.luxofilms.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Size
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 标定用的可拖拽覆盖层,叠在展示画面的 ImageView(Step1:实时预览帧;Step2:透视纠正后的静态参考图)
 * 上面,两层用同一个 FrameLayout match_parent 叠放。
 *
 * 2026-09-28 修的硬伤:以前直接拿 View 自身宽高当比例坐标的分母,假设画面铺满了整个 View——
 * 但下面的 ImageView 是 fitCenter(等比缩放、居中、留黑边,不裁剪),画面在 View 里实际占的是一个更小的
 * "内容矩形"(取决于画面本身的长宽比 vs View 的长宽比),两者长宽比一旦不一致就会有黑边。以前拖出来的框
 * 用户看着是框住了,但存下来的比例坐标其实是相对 View 算的,不是相对画面本身——套到 FrameProcessor 真正
 * 处理的那张图上,框就偏了。现在改成显式知道"画面的真实像素尺寸"(contentSize),画框和摸手指都先换算到
 * 画面在 View 里实际占的那个矩形(contentRect)上,而不是整个 View。
 *
 * 两种模式:
 * - QUAD:4个可拖拽角点,连线画出任意四边形(用于"框住整台被摄手机屏幕")
 * - RECT:4个角落手柄,只能拖出轴对齐矩形(用于"框住弹幕区域",已经是摆正后的画面,不需要任意四边形)
 */
class AlignOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { QUAD, RECT }

    var mode: Mode = Mode.QUAD
        set(value) { field = value; invalidate() }

    /** 当前展示画面的真实像素尺寸(Step1=ImageAnalysis 输出帧;Step2=透视纠正后的参考图,固定 900x1400)。
     * null = 还不知道(比如相机还没吐出第一帧),这时先按整个 View 当画面处理,等第一帧到了会自动纠正。 */
    var contentSize: Size? = null
        set(value) { field = value; invalidate() }

    /** 归一化点(0~1,相对 contentRect,不是相对 View),QUAD模式用,固定4个,顺序:左上、右上、右下、左下 */
    private val quad = mutableListOf(
        PointF(0.18f, 0.09f), PointF(0.83f, 0.11f), PointF(0.81f, 0.92f), PointF(0.20f, 0.90f),
    )
    /** 归一化矩形(相对 contentRect),RECT模式用 */
    private var rect = RectF(0.06f, 0.62f, 0.94f, 0.93f)

    private var dragIndex = -1        // QUAD模式:正在拖哪个角点,-1=没有
    private var dragCorner = -1       // RECT模式:0=左上 1=右上 2=右下 3=左下 4=整体平移,-1=没有
    private var dragStartRatio = PointF()   // 整体平移(dragCorner==4)起点的内容比例坐标
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

    /** fitCenter 语义:画面等比缩放到刚好放进 View、居中,多出的部分留黑边。返回画面在 View 坐标系里占的矩形。
     * contentSize 未知(null)或非法时退化成整个 View——保持标定引导页第一帧到达前的旧行为。 */
    private fun contentRect(viewW: Float, viewH: Float): RectF {
        val cs = contentSize
        if (cs == null || cs.width <= 0 || cs.height <= 0) return RectF(0f, 0f, viewW, viewH)
        val scale = minOf(viewW / cs.width, viewH / cs.height)
        val cw = cs.width * scale; val ch = cs.height * scale
        val left = (viewW - cw) / 2f; val top = (viewH - ch) / 2f
        return RectF(left, top, left + cw, top + ch)
    }

    /** View 像素坐标 → 内容比例坐标(0~1,相对 contentRect),越界会被夹紧到边缘——手指划出画面外时框仍然
     * 跟着走到画面边界,不是撒手不管或者记下一个 >1/<0 的坐标喂给后面的透视变换。 */
    private fun toContentRatio(touch: PointF, cr: RectF): PointF =
        PointF(((touch.x - cr.left) / cr.width()).coerceIn(0f, 1f), ((touch.y - cr.top) / cr.height()).coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cr = contentRect(w, h)
        when (mode) {
            Mode.QUAD -> drawQuad(canvas, cr)
            Mode.RECT -> drawRect(canvas, w, h, cr)
        }
    }

    private fun drawQuad(canvas: Canvas, cr: RectF) {
        val pts = quad.map { PointF(cr.left + it.x * cr.width(), cr.top + it.y * cr.height()) }
        for (i in pts.indices) {
            val a = pts[i]; val b2 = pts[(i + 1) % pts.size]
            canvas.drawLine(a.x, a.y, b2.x, b2.y, linePaint)
        }
        for (p in pts) {
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleFill)
            canvas.drawCircle(p.x, p.y, handleRadiusPx, handleStroke)
        }
    }

    private fun drawRect(canvas: Canvas, w: Float, h: Float, cr: RectF) {
        val r = RectF(cr.left + rect.left * cr.width(), cr.top + rect.top * cr.height(),
                       cr.left + rect.right * cr.width(), cr.top + rect.bottom * cr.height())
        // 框外整体调暗,突出选中区域(店员一眼就能看出"这块会被识别");调暗范围盖住整个 View(含黑边),
        // 不止 cr,黑边本来就不是弹幕会出现的地方。
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
        val cr = contentRect(w, h)
        val touch = PointF(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (mode) {
                    Mode.QUAD -> dragIndex = nearestQuadIndex(touch, cr)
                    Mode.RECT -> {
                        dragCorner = nearestRectCorner(touch, cr)
                        dragStartRatio = toContentRatio(touch, cr)
                        dragStartRect = RectF(rect)
                    }
                }
                return dragIndex >= 0 || dragCorner >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.QUAD && dragIndex >= 0) {
                    quad[dragIndex] = toContentRatio(touch, cr)
                    invalidate(); return true
                }
                if (mode == Mode.RECT && dragCorner >= 0) {
                    val p = toContentRatio(touch, cr)
                    val dx = p.x - dragStartRatio.x
                    val dy = p.y - dragStartRatio.y
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

    private fun nearestQuadIndex(touch: PointF, cr: RectF): Int {
        var best = -1; var bestDist = touchSlopPx
        quad.forEachIndexed { i, p ->
            val px = cr.left + p.x * cr.width(); val py = cr.top + p.y * cr.height()
            val d = hypot((px - touch.x).toDouble(), (py - touch.y).toDouble()).toFloat()
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** 4个角落手柄命中返回0~3;命中矩形内部(非边缘)返回4表示整体拖动;否则-1 */
    private fun nearestRectCorner(touch: PointF, cr: RectF): Int {
        val corners = listOf(
            PointF(cr.left + rect.left * cr.width(), cr.top + rect.top * cr.height()),
            PointF(cr.left + rect.right * cr.width(), cr.top + rect.top * cr.height()),
            PointF(cr.left + rect.right * cr.width(), cr.top + rect.bottom * cr.height()),
            PointF(cr.left + rect.left * cr.width(), cr.top + rect.bottom * cr.height()),
        )
        corners.forEachIndexed { i, p ->
            if (hypot((p.x - touch.x).toDouble(), (p.y - touch.y).toDouble()) < touchSlopPx) return i
        }
        val r = RectF(cr.left + rect.left * cr.width(), cr.top + rect.top * cr.height(),
                       cr.left + rect.right * cr.width(), cr.top + rect.bottom * cr.height())
        if (r.contains(touch.x, touch.y)) return 4
        return -1
    }
}
