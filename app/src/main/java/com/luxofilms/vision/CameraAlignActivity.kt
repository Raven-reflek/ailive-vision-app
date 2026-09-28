package com.luxofilms.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.luxofilms.vision.databinding.ActivityCameraAlignBinding
import java.util.concurrent.Executors

/**
 * 两步标定引导:
 * Step1(QUAD) —— 实时预览里拖4个角点框住被摄手机屏幕
 * Step2(RECT) —— 用Step1的四边形对当前帧做透视纠正,在摆正后的静态参考图上拖矩形框住弹幕区域
 * 完成后写入 Prefs 并拉起 CaptureService。
 *
 * 只绑定 ImageAnalysis,不绑定 CameraX 的 Preview/PreviewView——Step1 展示的就是 ImageAnalysis
 * 吐出来的原始帧(经过旋转摆正),跟 FrameProcessor 真正处理的是同一张图,不会出现"预览裁剪方式
 * 和分析帧不一致导致标定坐标对不上"的问题(2026-09-28 修的硬伤,详见 AlignOverlayView 注释)。
 */
class CameraAlignActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraAlignBinding
    private val io = Executors.newSingleThreadExecutor()
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisSize = Pair(0, 0)

    @Volatile private var latestFrame: Bitmap? = null   // ImageAnalysis 持续更新的最新一帧(已按屏幕方向摆正)
    @Volatile private var uiUpdatePending = false        // 粗糙的"合并连续帧"节流:UI线程处理不过来时不再堆积 Runnable
    private var referenceFrame: Bitmap? = null           // Step1点"下一步"那一刻定格的帧,Step2一直用它,不再刷新
    private var correctedBitmap: Bitmap? = null           // referenceFrame 经透视纠正后的"手机正视图"

    private var step = 1
    private var finishedNormally = false   // 是否走完 finishCalibration();false 时 onDestroy 还要兜底把采集接回去

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "需要摄像头权限才能标定", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityCameraAlignBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.overlay.mode = AlignOverlayView.Mode.QUAD

        b.btnNext.setOnClickListener { onNextClicked() }
        b.btnRetake.setOnClickListener { retakeReference() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            // 和 CaptureService 用同一套分辨率策略(CameraTuning),否则标定时看到的画面长宽比跟正式
            // 采集时不一样,存下来的比例坐标框住的会是画面里不同的区域(2026-09-28 修的硬伤)。
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(CameraTuning.resolutionSelector())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(io) { proxy -> onFrame(proxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "打开摄像头失败:${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(proxy: ImageProxy) {
        try {
            if (step == 1) {
                val rotation = proxy.imageInfo.rotationDegrees
                val bmp = proxy.toBitmap().rotated(rotation)
                analysisSize = Pair(bmp.width, bmp.height)
                latestFrame = bmp
                postFrameToUi()
            }
        } catch (_: Exception) {
            // 单帧转换失败不致命,丢这一帧就好,下一帧还会来
        } finally {
            proxy.close()
        }
    }

    /** 把最新一帧显示出来:合并连续到达的帧,UI线程还在处理上一帧时不再重复 post,
     * 避免标定页(不像 CaptureService 那样按 fps 节流取帧)在高帧率下把主线程堆爆。
     * Runnable 执行时才读 latestFrame(而不是在创建 Runnable 时就固定住某一帧),这样等待期间
     * 又来了更新的帧,最终显示的也是最新那一张,不会因为合并节流而"倒退"显示旧帧。 */
    private fun postFrameToUi() {
        if (uiUpdatePending) return
        uiUpdatePending = true
        ui.post {
            uiUpdatePending = false
            val frame = latestFrame
            if (step == 1 && !isFinishing && frame != null) {
                b.livePreview.setImageBitmap(frame)
                b.overlay.contentSize = Size(frame.width, frame.height)
            }
        }
    }

    private fun onNextClicked() {
        when (step) {
            1 -> goToStep2()
            2 -> finishCalibration()
        }
    }

    private fun goToStep2() {
        val frame = latestFrame
        if (frame == null) {
            Toast.makeText(this, "还没拿到画面,再等一下", Toast.LENGTH_SHORT).show()
            return
        }
        val quad = b.overlay.getQuad()
        val areaRatio = quadAreaRatio(quad)
        if (areaRatio < 0.5f) {
            Toast.makeText(this, getString(R.string.align_too_small), Toast.LENGTH_SHORT).show()
            return
        }
        if (areaRatio > 0.75f) {
            Toast.makeText(this, getString(R.string.align_too_large), Toast.LENGTH_SHORT).show()
            return
        }
        referenceFrame = frame
        buildCorrectedPreview(frame, quad)

        // 切到 Step2 UI:隐藏实时预览、显示静态参考图,解绑相机(标定阶段不需要再持续取流)
        cameraProvider?.unbindAll()
        b.livePreview.visibility = android.view.View.GONE
        b.refImage.visibility = android.view.View.VISIBLE
        b.overlay.mode = AlignOverlayView.Mode.RECT
        b.overlay.setRect(RectF(0.06f, 0.62f, 0.94f, 0.93f))
        b.stepTitle.setText(R.string.align_step2_title)
        b.stepHint.setText(R.string.align_step2_hint)
        b.sizeHint.text = ""
        b.btnNext.setText(R.string.align_finish)
        b.btnRetake.visibility = android.view.View.VISIBLE
        step = 2
    }

    /** 用四点透视变换,把 quad 框住的那部分画面"摆正"成一张矩形正视图,给Step2用。 */
    private fun buildCorrectedPreview(src: Bitmap, quad: List<PointF>) {
        // 纠正后画面尺寸固定,跟 FrameProcessor.process() 用的完全一致(见该处注释),
        // 标定时框的和正式识别时裁的必须是同一个坐标系。
        val out = PerspectiveUtils.correctToFrontView(src, quad.map { it.toRatioPoint() }, outW = 900, outH = 1400)
        correctedBitmap = out
        b.refImage.setImageBitmap(out)
        b.overlay.contentSize = Size(out.width, out.height)   // Step2 的内容尺寸是固定的纠正图尺寸,不是 View 尺寸
    }

    private fun retakeReference() {
        // 回Step1重新框(不重启Activity,省事):恢复相机、切回QUAD模式
        b.refImage.visibility = android.view.View.GONE
        b.livePreview.visibility = android.view.View.VISIBLE
        b.overlay.mode = AlignOverlayView.Mode.QUAD
        b.stepTitle.setText(R.string.align_step1_title)
        b.stepHint.setText(R.string.align_step1_hint)
        b.btnNext.setText(R.string.align_next)
        b.btnRetake.visibility = android.view.View.GONE
        step = 1
        // 先清空:这一刻 overlay 还留着 Step2 那张 900x1400 参考图的 contentSize,如果不清,下一帧真正的
        // 实时预览到达前会有一瞬间"按参考图长宽比给活预览画黑边"的观感错位。contentSize=null 时退化成
        // 整个 View(旧行为),等下面 startCamera() 重新出frame 就会自动纠正回真实分辨率,很快(通常<100ms)。
        b.overlay.contentSize = null
        startCamera()
    }

    private fun finishCalibration() {
        // overlay 的 quad/rect 是各自独立的字段,mode 只影响画什么/怎么拖,切到RECT模式后
        // getQuad() 依然拿得到 Step1 存下的那份四边形数据,不需要额外变量保存。
        val quad = b.overlay.getQuad()
        val rect = b.overlay.getRect()
        val cal = Calibration(
            calibratedAt = System.currentTimeMillis(),
            analysisWidth = analysisSize.first,
            analysisHeight = analysisSize.second,
            screenQuad = quad.map { it.toRatioPoint() },
            danmakuRegion = shrinkInset(rect, 0.04f).toRatioRect(),   // 整体向内收缩4%,减少标定漂移的边缘噪音(见设计文档1.2节)
        )
        CalibrationStore.save(cal)
        Toast.makeText(this, "标定完成", Toast.LENGTH_SHORT).show()
        finishedNormally = true
        // 让采集服务重新加载标定数据、重新绑定摄像头——上面 goToStep2() 的 unbindAll() 早把它(如果在跑的话)
        // 的摄像头拆掉了,服务本身没死、通知还在,但已经彻底不采集了;CaptureService.start() 现在是幂等的
        // "重新加载+重新绑定"语义,不管服务是刚起还是已经在跑都会正确接上(2026-09-28 修的硬伤)。
        CaptureService.start(this)
        finish()
    }

    private fun shrinkInset(r: RectF, frac: Float): RectF {
        val dx = (r.right - r.left) * frac
        val dy = (r.bottom - r.top) * frac
        return RectF(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
    }

    private fun quadAreaRatio(quad: List<PointF>): Float {
        // 鞋带公式算多边形面积(归一化坐标系里,画面本身就是1x1=面积1,所以直接就是占比)
        var area = 0f
        for (i in quad.indices) {
            val a = quad[i]; val b2 = quad[(i + 1) % quad.size]
            area += a.x * b2.y - b2.x * a.y
        }
        return kotlin.math.abs(area) / 2f
    }

    override fun onDestroy() {
        // 用户没点完"确认标定"就退出(比如按了返回键、或者中途切走):如果之前已经标定过,大概率是
        // 从"重新标定"入口点进来的,采集服务的摄像头这时已经被 goToStep2()/startCamera() 的
        // unbindAll() 拆掉了——不管本次有没有走到 finishCalibration(),都得让服务尝试拿回摄像头,
        // 不然它只是悄悄停在那,通知却还写着"正在采集"(2026-09-28 修的硬伤)。finishCalibration()
        // 里已经调用过一次的话,这里再调用是幂等的(重新读一遍标定、重新绑定一次),无害。
        if (!finishedNormally && CalibrationStore.load() != null) {
            CaptureService.start(this)
        }
        cameraProvider?.unbindAll()
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }
}
