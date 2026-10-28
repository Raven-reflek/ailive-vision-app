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
 * 一帧的完整处理流水线:透视纠正 → 裁剪弹幕区域 → 温和预处理 → 识别文字 → 行合并 / 去重 → **入上传队列**。
 * 每次调用在CaptureService的单线程Analyzer回调里跑,天然串行,不需要额外加锁。
 * 0.6.0 起(ADR-0041)这里**不再上传、不等网络**:挑出来的弹幕交给 [UploadEngine] 排队,由它的专用线程去传;
 * 以前在这里同步上传(最坏 30 秒),期间一帧都不处理。
 *
 * 2026-09-23:识别这一步暂时改成本地(ML Kit)识别、传文字给 /api/vision/text——云端Ark视觉大模型
 * 账号那边卡在"模型不存在或无权限"没排查出来,先用本地OCR把真机联调这条路跑通。本地识别没有大模型
 * 的语义过滤能力,清洗/过滤噪音交给服务端 vision_ocr.ingest_local_ocr() 的启发式规则去兜底。
 * 等Ark那边打通了,把下面 process() 换回"编码JPEG + Api.visionFrame()"即可,其它步骤都不用动。
 */
object FrameProcessor {
    private const val TAG = "FrameProcessor"

    /** 这一帧有没有处理成功。0.6.0 起上传不在这里,限流 / 令牌失效不再经过识别这一路(见 [UploadEngine.Listener])。 */
    enum class Result { OK, ERROR }

    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /**
     * 最近入过队的弹幕(模糊判重)。select / commit 在 CaptureService 的单线程 Analyzer 里用,
     * [forget] 由上传线程 / 主线程调(去重器的方法自带锁);进程内常驻,窗口过期后自然清空。
     */
    private val deduper = DanmakuDeduper()

    /** 读对过冒号的昵称(同一位观众后面冒号被 OCR 读丢时据此剥昵称)。同样只在 Analyzer 单线程里用,见 [NickMemory]。 */
    private val nicks = NickMemory()

    /** 这些条目没能传出去(排队过期 / 被挤出队列 / 采集停了):撤销去重器的记账,它们如果还在屏幕上,下一帧能重新被选中。 */
    fun forget(items: List<LineMerger.Item>) = deduper.forget(items)

    /** 处理一帧:识别 → 合并 / 去重 → 把没见过的弹幕**入上传队列**就返回(不等网络);识别 / 入队出错都不抛到调用方之外炸掉采集循环,内部吞掉异常只记日志。
     *
     * 入队时就在去重器里记账(commit):同一条弹幕要在屏幕上停留十几秒,每 2 秒拍一次,不能每次都再入队。
     * 排队过期没传出去的,由 [UploadEngine.Listener] 那边调 [forget] 撤销。
     *
     * 识别出的内容记进 RecognizedLog(先标"排队中",上传线程传完 / 放弃后按 id 改状态),首页拿它实时展示"摄像头认出了什么"——
     * 装机/调试时用肉眼比对实际弹幕,比连电脑翻日志方便太多(2026-09-28 加)。 */
    fun process(proxy: ImageProxy, calibration: Calibration, uploader: UploadEngine, publishPreview: Boolean = false,
                cloud: CloudFrameSender? = null): Result {
        var diag: DiagFrame? = null            // 诊断包开着时,这一帧的识别细节(ADR-0038);缺省关,开着也只放内存
        var crop: Bitmap? = null               // 识别用的评论区小图;开了云端识别时,有新弹幕就把它传上去(0.7.0)
        val items: List<LineMerger.Item> = try {
            val f = prepare(proxy, calibration)
            crop = f.enhanced
            // 预览页打开时顺手发布一份(缩小的拷贝,不影响下面送去识别的原图);发布失败不能影响识别
            if (publishPreview) publishPreview(f.upright, if (PreviewBus.cropWanted) f.enhanced else null, calibration)
            val wantDiag = Diagnostics.enabled
            val rec = recognizeLocally(f.enhanced, wantDiag)
            CaptureStats.noteOcrDone()                                  // 心跳:OCR 还在跑(超时 / 抛异常到不了这里)
            if (rec.lines.isNotEmpty()) CaptureStats.noteText()         // 心跳:眼前有字(原始识别结果,不管后面是否被合并 / 去重)
            if (wantDiag) diag = try {
                DiagFrame(System.currentTimeMillis(), f.enhanced.width, f.enhanced.height, Diagnostics.jpeg(f.enhanced), rec.detail)
            } catch (e: Throwable) { Log.w(TAG, "诊断帧记录失败:${e.message}"); null }       // 诊断是锦上添花,绝不能连累识别
            // 视觉行 → 弹幕条:折成两行的弹幕合回一条,不再各问一次(2026-10-15 修);昵称冒号丢了时用读对过的昵称剥(ADR-0038)
            LineMerger.merge(rec.lines, f.enhanced.height, nicks)
        } catch (e: Exception) {
            Log.w(TAG, "这一帧处理/识别失败,丢弃:${e.message}")
            return Result.ERROR
        }
        val now = System.currentTimeMillis()
        if (PreviewBus.active) PreviewBus.noteLines(items.map { it.text }, now)
        // 同一条弹幕会在画面上停留很多帧、每帧的 OCR 错字还不一样:只入队没见过的,识别日志也只记这些
        val fresh = if (items.isEmpty()) emptyList() else deduper.select(items, now)
        diag?.let { Diagnostics.record(it.withResult(items, fresh.map { i -> i.content })) }
        if (items.isEmpty()) return Result.OK   // 这一帧弹幕区域没识别到文字,不算失败,也没什么好上传的
        if (fresh.isEmpty()) return Result.OK
        return try {
            val logId = RecognizedLog.add(fresh.map { it.text }, RecognizedLog.State.QUEUED)
            deduper.commit(fresh, now)           // 入队就记账,别让它在屏幕上的这十几秒里每帧都再入队一次
            val jpeg = if (cloud != null && CloudOcrState.enabled) crop?.let { encodeJpeg(it) } else null
            if (jpeg != null) {
                // 0.7.0 云端识别:本机认出有新弹幕 → 传这一帧的评论区截图,云端大模型认字;本机认的字一起带上当兜底
                val dropped = cloud!!.offer(jpeg, fresh, logId)
                if (dropped.isNotEmpty()) uploader.enqueue(dropped.map { it.first }, dropped.first().second)   // 积压太多挤掉的走文字通道
            } else {
                uploader.enqueue(fresh, logId)   // 传内容("昵称:" 前缀去掉)+ 读得准的昵称(0.6.1,见 LineMerger.nickOf);怎么传、传没传成见 UploadEngine
            }
            Result.OK
        } catch (e: Exception) {
            Log.w(TAG, "入上传队列失败:${e.message}")
            Result.ERROR
        }
    }

    /** 评论区小图 → JPEG(宽超过 [CLOUD_MAX_W] 先等比缩小;云端按像素计费,评论区字不小,960 宽足够)。 */
    private fun encodeJpeg(src: Bitmap): ByteArray? = try {
        val bmp = if (src.width > CLOUD_MAX_W) scaledCopy(src, CLOUD_MAX_W) else src
        java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    } catch (e: Throwable) {
        Log.w(TAG, "截图编码失败,这次改传文字:${e.message}")
        null
    }

    private const val CLOUD_MAX_W = 960

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
    private class Recognized(val lines: List<OcrLine>, val detail: List<DiagLine>)

    private fun recognizeLocally(bmp: Bitmap, detail: Boolean = false): Recognized {
        val image = InputImage.fromBitmap(bmp, 0)
        val result = Tasks.await(recognizer.process(image), 8, TimeUnit.SECONDS)
        var fallbackTop = 0
        val rawLines = result.textBlocks.flatMap { it.lines }
        val lines = rawLines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val b = line.boundingBox
            if (b != null) OcrLine(text, b.left, b.top, b.right, b.bottom)
            else { fallbackTop += 1000; OcrLine(text, 0, fallbackTop, 10, fallbackTop + 10) }   // 没有位置(理论上不会):互相隔开,不合并
        }
        // 诊断包开着时再收每个词的框和取样颜色(平时一点额外开销都没有)
        val diag = if (!detail) emptyList() else rawLines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val b = line.boundingBox
            DiagLine(text, b?.let { listOf(it.left, it.top, it.right, it.bottom) }, line.elements.map { e ->
                val eb = e.boundingBox
                DiagElement(e.text, eb?.let { listOf(it.left, it.top, it.right, it.bottom) }, Diagnostics.sampleInk(bmp, eb))
            })
        }
        return Recognized(lines, diag)
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
