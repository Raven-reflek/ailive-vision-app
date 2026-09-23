package com.luxofilms.vision

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.luxofilms.vision.databinding.ActivityCameraAlignBinding
import java.util.concurrent.Executors

/**
 * 两步标定引导:
 * Step1(QUAD) —— 实时预览里拖4个角点框住被摄手机屏幕
 * Step2(RECT) —— 用Step1的四边形对当前帧做透视纠正,在摆正后的静态参考图上拖矩形框住弹幕区域
 * 完成后写入 Prefs 并拉起 CaptureService。
 */
class CameraAlignActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraAlignBinding
    private val io = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisSize = Pair(0, 0)

    @Volatile private var latestFrame: Bitmap? = null   // ImageAnalysis 持续更新的最新一帧(已按屏幕方向摆正)
    private var referenceFrame: Bitmap? = null           // Step1点"下一步"那一刻定格的帧,Step2一直用它,不再刷新
    private var correctedBitmap: Bitmap? = null           // referenceFrame 经透视纠正后的"手机正视图"

    private var step = 1

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

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(b.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(io) { proxy -> onFrame(proxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
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
            }
        } catch (_: Exception) {
            // 单帧转换失败不致命,丢这一帧就好,下一帧还会来
        } finally {
            proxy.close()
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

        // 切到 Step2 UI:隐藏预览、显示静态参考图,解绑相机(标定阶段不需要再持续取流)
        cameraProvider?.unbindAll()
        b.previewView.visibility = android.view.View.GONE
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

    private fun retakeReference() {
        // 回Step1重新框(不重启Activity,省事):恢复相机、切回QUAD模式
        b.refImage.visibility = android.view.View.GONE
        b.previewView.visibility = android.view.View.VISIBLE
        b.overlay.mode = AlignOverlayView.Mode.QUAD
        b.stepTitle.setText(R.string.align_step1_title)
        b.stepHint.setText(R.string.align_step1_hint)
        b.btnNext.setText(R.string.align_next)
        b.btnRetake.visibility = android.view.View.GONE
        step = 1
        startCamera()
    }

    /** 用四点透视变换,把 quad 框住的那部分画面"摆正"成一张矩形正视图,给Step2用。 */
    private fun buildCorrectedPreview(src: Bitmap, quad: List<PointF>) {
        // 纠正后画面尺寸:固定一个够用的分辨率即可(标定预览用,不是正式采集的输出尺寸)
        val out = PerspectiveUtils.correctToFrontView(src, quad.map { it.toRatioPoint() }, outW = 900, outH = 1400)
        correctedBitmap = out
        b.refImage.setImageBitmap(out)
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
        io.shutdownNow()
        super.onDestroy()
    }
}
