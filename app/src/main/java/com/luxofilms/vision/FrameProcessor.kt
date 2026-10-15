package com.luxofilms.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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

    /** NEED_REBIND:服务端明确说这台设备的令牌已作废(401/410 + need_bind),调用方要停采集并提示重新扫码。 */
    enum class Result { OK, RATE_LIMITED, ERROR, NEED_REBIND }

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** 最近上传过的弹幕(模糊判重)。只在 CaptureService 的单线程 Analyzer 里用;进程内常驻,窗口过期后自然清空。 */
    private val deduper = DanmakuDeduper()

    /** 处理一帧并上传;成功/失败都不抛到调用方之外炸掉采集循环,内部吞掉异常只记日志。
     * 返回值区分"服务端限流"和"其它失败",调用方(CaptureService)据此决定要不要退避——
     * 以前不管什么原因失败都只是静默丢弃、下一帧照样按原节奏再打一次,遇到限流等于白打
     * (2026-09-28 修的硬伤,详见 CaptureService.RATE_LIMIT_COOLDOWN_MS 处注释)。
     *
     * 识别出的内容(不管上传成不成功)都记进 RecognizedLog,首页拿它实时展示"摄像头认出了什么"——
     * 装机/调试时用肉眼比对实际弹幕,比连电脑翻日志方便太多(2026-09-28 加)。 */
    fun process(proxy: ImageProxy, calibration: Calibration, publishPreview: Boolean = false): Result {
        val items: List<LineMerger.Item> = try {
            val f = prepare(proxy, calibration)
            // 预览页打开时顺手发布一份(缩小的拷贝,不影响下面送去识别的原图);发布失败不能影响识别
            if (publishPreview) publishPreview(f.upright, if (PreviewBus.cropWanted) f.enhanced else null, calibration)
            // 视觉行 → 弹幕条:折成两行的弹幕合回一条,不再各问一次(2026-10-15 修)
            LineMerger.merge(recognizeLocally(f.enhanced), f.enhanced.height)
        } catch (e: Exception) {
            Log.w(TAG, "这一帧处理/识别失败,丢弃:${e.message}")
            return Result.ERROR
        }
        val now = System.currentTimeMillis()
        if (PreviewBus.active) PreviewBus.noteLines(items.map { it.text }, now)
        if (items.isEmpty()) return Result.OK   // 这一帧弹幕区域没识别到文字,不算失败,也没什么好上传的
        // 同一条弹幕会在画面上停留很多帧、每帧的 OCR 错字还不一样:只上传没见过的,识别日志也只记这些
        val fresh = deduper.select(items, now)
        if (fresh.isEmpty()) return Result.OK
        val texts = fresh.map { it.text }
        return try {
            Api.visionText(fresh.map { it.content })   // 只传内容,"昵称:" 前缀去掉(昵称 OCR 不可靠,服务端也是固定占位昵称)
            deduper.commit(fresh, now)
            RecognizedLog.add(texts, uploaded = true)
            Result.OK
        } catch (e: Api.ApiError) {
            Log.w(TAG, "上传失败 HTTP ${e.code}:${e.message}")
            RecognizedLog.add(texts, uploaded = false)
            when {
                e.code == 429 -> Result.RATE_LIMITED
                BindingGuard.classify(e) == BindingGuard.Verdict.TOKEN_INVALID -> Result.NEED_REBIND
                else -> Result.ERROR
            }
        } catch (e: Exception) {
            Log.w(TAG, "上传失败:${e.message}")
            RecognizedLog.add(texts, uploaded = false)
            Result.ERROR
        }
    }

    /** 一帧的三个中间图:摄像头原画面(已转正)→ 摆正后的整屏 → 增强后的弹幕区小图。OCR 和预览共用,保证"预览看到的"就是"识别用的"。 */
    private class Frames(val upright: Bitmap, val front: Bitmap, val enhanced: Bitmap)

    private fun uprightOf(proxy: ImageProxy): Bitmap {
        val rotation = proxy.imageInfo.rotationDegrees
        val raw = proxy.toBitmap()
        return if (rotation != 0) raw.rotated(rotation) else raw
    }

    private fun prepare(proxy: ImageProxy, calibration: Calibration): Frames {
        val upright = uprightOf(proxy)
        val frontView = PerspectiveUtils.correctToFrontView(upright, calibration.screenQuad, outW = 900, outH = 1400)
        val cropped = PerspectiveUtils.cropRegion(frontView, calibration.danmakuRegion)
        return Frames(upright, frontView, mildContrastEnhance(cropped))
    }

    /** 只出预览图、不识别也不上传。预览页打开期间,非 OCR 的帧走这里(取帧频率比 OCR 高,见 FrameGate)。
     * 只有展开了"识别用的画面"才需要摆正+裁剪+增强;否则只转正原图、叠框,开销小得多。 */
    fun preview(proxy: ImageProxy, calibration: Calibration) {
        try {
            if (PreviewBus.cropWanted) {
                val f = prepare(proxy, calibration)
                publishPreview(f.upright, f.enhanced, calibration)
            } else {
                publishPreview(uprightOf(proxy), null, calibration)
            }
        } catch (e: Exception) {
            Log.w(TAG, "预览帧处理失败,丢弃:${e.message}")
        }
    }

    private const val PREVIEW_RAW_MAX_W = 960
    private const val PREVIEW_CROP_MAX_W = 640

    /** 缩小 + 叠标定框,发布给预览页。全部画在新建的小位图上,绝不改动传入的原图(它们还要送去识别)。
     * enhanced 非 null 时(预览页展开了"识别用的画面")才额外生成识别用小图。 */
    private fun publishPreview(upright: Bitmap, enhanced: Bitmap?, cal: Calibration) {
        try {
            val raw = scaledCopy(upright, PREVIEW_RAW_MAX_W)
            drawOverlays(raw, cal)
            val crop = enhanced?.let { scaledCopy(it, PREVIEW_CROP_MAX_W) }
            PreviewBus.publish(PreviewBus.Snapshot(raw, crop, System.currentTimeMillis()))
        } catch (e: Throwable) {
            // 包括内存不足:预览是锦上添花,出问题只丢这一张,绝不能连累识别和上传
            Log.w(TAG, "预览发布失败:${e.message}")
        }
    }

    /** 总是返回一张新的、可绘制的小位图(createScaledBitmap 在尺寸不变时会返回原对象,不能直接在上面画)。 */
    private fun scaledCopy(src: Bitmap, maxW: Int): Bitmap {
        val (w, h) = PreviewBus.scaledSize(src.width, src.height, maxW)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private const val COLOR_SCREEN = 0xFFE8620F.toInt()    // 橙:屏幕
    private const val COLOR_DANMAKU = 0xFF1E88E5.toInt()   // 蓝:弹幕区

    /**
     * 在同一张摄像头画面上叠两个框:橙=程序认为的手机屏幕(四边形),蓝=弹幕区域。
     * 弹幕区在正视图里是矩形,这里用摆正那次透视变换的反方向映射回原图(PerspectiveMath),画出来通常是歪的四边形,
     * 和 FrameProcessor 实际裁剪识别的区域严格一致。四个角标 1~4(1=左上,顺时针),用来判断四点顺序有没有标反;
     * 两个框各带文字标注,不只靠颜色区分。
     */
    private fun drawOverlays(bmp: Bitmap, cal: Calibration) {
        if (cal.screenQuad.size < 4) return
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        drawQuad(canvas, w, h, cal.screenQuad, COLOR_SCREEN, "屏幕", numbered = true)
        val region = PerspectiveMath.mapRectToQuad(cal.danmakuRegion, cal.screenQuad)
        drawQuad(canvas, w, h, region, COLOR_DANMAKU, "弹幕区", numbered = false)
    }

    private fun drawQuad(canvas: Canvas, w: Float, h: Float, pts: List<RatioPoint>, color: Int, label: String, numbered: Boolean) {
        val stroke = maxOf(3f, w / 160f)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = stroke }
        val path = Path().apply {
            moveTo(pts[0].x * w, pts[0].y * h)
            for (i in 1 until pts.size) lineTo(pts[i].x * w, pts[i].y * h)
            close()
        }
        canvas.drawPath(path, line)

        val textSize = maxOf(18f, w / 30f)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); this.textSize = textSize * 0.8f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        if (numbered) {
            val r = textSize * 0.75f
            pts.forEachIndexed { i, p ->
                canvas.drawCircle(p.x * w, p.y * h, r, dot)
                canvas.drawText((i + 1).toString(), p.x * w, p.y * h + textSize * 0.28f, num)
            }
        }
        // 文字标注:放在第 1 个角的内侧附近,带同色底,深浅背景下都看得清
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); this.textSize = textSize; isFakeBoldText = true }
        val tx = (pts[0].x * w + textSize * 0.6f).coerceIn(0f, w - tag.measureText(label) - 12f)
        val ty = (pts[0].y * h + textSize * (if (numbered) 2.4f else 1.6f)).coerceIn(textSize * 1.2f, h - 6f)
        canvas.drawRect(tx - 6f, ty - textSize * 1.05f, tx + tag.measureText(label) + 6f, ty + textSize * 0.35f, dot)
        canvas.drawText(label, tx, ty, tag)
    }

    /**
     * 返回带位置的视觉行(Line 粒度)。Line 是视觉行:一条弹幕折成两行就是两个 Line,
     * 所以后面要用位置和文字特征把它们合回一条(LineMerger)。
     */
    private fun recognizeLocally(bmp: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bmp, 0)
        val result = Tasks.await(recognizer.process(image), 8, TimeUnit.SECONDS)
        var fallbackTop = 0
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val b = line.boundingBox
            if (b != null) OcrLine(text, b.left, b.top, b.right, b.bottom)
            else { fallbackTop += 1000; OcrLine(text, 0, fallbackTop, 10, fallbackTop + 10) }   // 没有位置(理论上不会):互相隔开,不合并
        }
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
