package com.luxofilms.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/** 透视纠正 + 裁剪相关的共享工具,CameraAlignActivity(标定预览)和 FrameProcessor(正式采集)都用同一套,
 * 避免两处各写一份、参数细节不一致导致"标定时看到的"和"实际识别用的"其实是两码事。 */
object PerspectiveUtils {

    /** 用四点透视变换,把 src 图里 quad(比例坐标,顺时针,左上开始)框住的部分"摆正"成 outW x outH 的矩形正视图。 */
    fun correctToFrontView(src: Bitmap, quad: List<RatioPoint>, outW: Int, outH: Int): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val srcPts = FloatArray(8)
        quad.forEachIndexed { i, p -> srcPts[i * 2] = p.x * w; srcPts[i * 2 + 1] = p.y * h }
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    /** 从已经摆正的正视图里,按比例矩形裁出弹幕区域小图。 */
    fun cropRegion(frontView: Bitmap, region: RatioRect): Bitmap {
        val w = frontView.width; val h = frontView.height
        val left = (region.left * w).toInt().coerceIn(0, w - 1)
        val top = (region.top * h).toInt().coerceIn(0, h - 1)
        val right = (region.right * w).toInt().coerceIn(left + 1, w)
        val bottom = (region.bottom * h).toInt().coerceIn(top + 1, h)
        return Bitmap.createBitmap(frontView, left, top, right - left, bottom - top)
    }
}

// 注意:ImageProxy.toBitmap() 不用自己写——CameraX 1.3+ 的 camera-core 本身就带这个方法
// (配合 ImageAnalysis 设的 OUTPUT_IMAGE_FORMAT_RGBA_8888 用),自己再写一份会被编译器警告
// "Extension is shadowed by a member",而且多一份重复逻辑,直接用 proxy.toBitmap() 官方实现即可。

/** 摄像头传感器方向跟屏幕方向不一致时(常见是90°),按 ImageInfo.rotationDegrees 转正。 */
fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}
