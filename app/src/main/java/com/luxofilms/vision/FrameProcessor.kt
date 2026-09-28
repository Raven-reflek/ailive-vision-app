package com.luxofilms.vision

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * 一帧的完整处理流水线:透视纠正 → 裁剪弹幕区域 → 温和预处理 → 识别文字 → 上传云端。
 * 每次调用在CaptureService的单线程Analyzer回调里跑,天然串行,不需要额外加锁。
 *
 * 2026-09-23:识别这一步暂时改成本地(ML Kit)识别、传文字给 /api/vision/text——云端Ark视觉大模型
 * 账号那边卡在"模型不存在或无权限"没排查出来,先用本地OCR把真机联调这条路跑通。本地识别没有大模型
 * 的语义过滤能力,清洗/过滤噪音交给服务端 vision_ocr.ingest_local_ocr() 的启发式规则去兜底。
 * 等Ark那边打通了,把下面 process() 换回"编码JPEG + Api.visionFrame()"即可,其它步骤都不用动。
 */
object FrameProcessor {
    private const val TAG = "FrameProcessor"

    enum class Result { OK, RATE_LIMITED, ERROR }

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** 处理一帧并上传;成功/失败都不抛到调用方之外炸掉采集循环,内部吞掉异常只记日志。
     * 返回值区分"服务端限流"和"其它失败",调用方(CaptureService)据此决定要不要退避——
     * 以前不管什么原因失败都只是静默丢弃、下一帧照样按原节奏再打一次,遇到限流等于白打
     * (2026-09-28 修的硬伤,详见 CaptureService.RATE_LIMIT_COOLDOWN_MS 处注释)。 */
    fun process(proxy: ImageProxy, calibration: Calibration): Result {
        return try {
            val rotation = proxy.imageInfo.rotationDegrees
            val raw = proxy.toBitmap()
            val upright = if (rotation != 0) raw.rotated(rotation) else raw

            val frontView = PerspectiveUtils.correctToFrontView(upright, calibration.screenQuad, outW = 900, outH = 1400)
            val cropped = PerspectiveUtils.cropRegion(frontView, calibration.danmakuRegion)
            val enhanced = mildContrastEnhance(cropped)

            val lines = recognizeLocally(enhanced)
            if (lines.isNotEmpty()) Api.visionText(lines)
            Result.OK
        } catch (e: Api.ApiError) {
            Log.w(TAG, "上传失败 HTTP ${e.code}:${e.message}")
            if (e.code == 429) Result.RATE_LIMITED else Result.ERROR
        } catch (e: Exception) {
            Log.w(TAG, "这一帧处理/识别失败,丢弃:${e.message}")
            Result.ERROR
        }
    }

    /** ML Kit按"视觉上聚在一起的文字块"分组文字,弹幕通常一行一条,用 Line 粒度比 Block 更贴近实际。 */
    private fun recognizeLocally(bmp: Bitmap): List<String> {
        val image = InputImage.fromBitmap(bmp, 0)
        val result = Tasks.await(recognizer.process(image), 8, TimeUnit.SECONDS)
        return result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 温和的对比度增强,不做二值化——2026-09-23的设计结论:自然图像(不管是给Ark视觉大模型还是给
     * 本地OCR)普遍比强二值化后的图像识别效果好,二值化会丢失灰度/色彩语境信息,只留最基础的对比度提升。
     */
    private fun mildContrastEnhance(src: Bitmap): Bitmap {
        val contrast = 1.15f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
