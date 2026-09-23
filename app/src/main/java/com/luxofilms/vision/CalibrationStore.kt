package com.luxofilms.vision

import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/** 屏幕上的一个点,比例坐标(0~1),相对某个参照画面的宽高。 */
data class RatioPoint(val x: Float, val y: Float) {
    fun toJson(): JSONArray = JSONArray().put(x.toDouble()).put(y.toDouble())
    companion object {
        fun fromJson(a: JSONArray): RatioPoint = RatioPoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
    }
}

/** 比例坐标矩形,相对"透视纠正后的手机正视图"局部坐标系。 */
data class RatioRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toJson(): JSONObject = JSONObject()
        .put("left", left.toDouble()).put("top", top.toDouble())
        .put("right", right.toDouble()).put("bottom", bottom.toDouble())
    companion object {
        fun fromJson(o: JSONObject): RatioRect = RatioRect(
            o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
            o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat(),
        )
    }
}

/**
 * 标定数据:screenQuad 是"被摄手机屏幕四角"在原始摄像头画面里的比例坐标(任意四边形,顺时针,
 * 左上角开始);danmakuRegion 是"弹幕区域"在透视纠正后的手机正视图里的比例矩形。
 * analysisSize 记录标定时 ImageAnalysis 的实际输出尺寸,仅作调试参考(比例坐标本身不依赖它)。
 */
data class Calibration(
    val calibratedAt: Long,
    val analysisWidth: Int,
    val analysisHeight: Int,
    val screenQuad: List<RatioPoint>,   // 固定4个点
    val danmakuRegion: RatioRect,
) {
    fun toJson(): JSONObject {
        val quad = JSONArray()
        screenQuad.forEach { quad.put(it.toJson()) }
        return JSONObject()
            .put("calibrated_at", calibratedAt)
            .put("analysis_size", JSONObject().put("w", analysisWidth).put("h", analysisHeight))
            .put("screen_quad_ratio", quad)
            .put("danmaku_region_ratio", danmakuRegion.toJson())
    }

    companion object {
        fun fromJson(s: String): Calibration? {
            if (s.isEmpty()) return null
            return try {
                val o = JSONObject(s)
                val size = o.getJSONObject("analysis_size")
                val quadArr = o.getJSONArray("screen_quad_ratio")
                val quad = (0 until quadArr.length()).map { RatioPoint.fromJson(quadArr.getJSONArray(it)) }
                Calibration(
                    calibratedAt = o.optLong("calibrated_at"),
                    analysisWidth = size.optInt("w"),
                    analysisHeight = size.optInt("h"),
                    screenQuad = quad,
                    danmakuRegion = RatioRect.fromJson(o.getJSONObject("danmaku_region_ratio")),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** AlignOverlayView 用 Android 原生 PointF/RectF 画图和处理触摸,跟 Calibration 存储用的
 *  RatioPoint/RatioRect 是两套等价的类型——这两个转换函数是它们之间唯一的桥梁。 */
fun PointF.toRatioPoint() = RatioPoint(x, y)
fun RectF.toRatioRect() = RatioRect(left, top, right, bottom)

/** Prefs.calibrationJson 的强类型读写包装,避免各处直接摸JSONObject出错。 */
object CalibrationStore {
    fun load(): Calibration? = Calibration.fromJson(Prefs.calibrationJson)
    fun save(c: Calibration) {
        Prefs.calibrationJson = c.toJson().toString()
    }
    fun clear() {
        Prefs.calibrationJson = ""
    }
}
