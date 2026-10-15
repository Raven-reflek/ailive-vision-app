package com.luxofilms.vision

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.luxofilms.vision.databinding.ActivityPreviewBinding

/**
 * 采集预览(只读):一张摄像头实时画面,上面叠着屏幕框(橙)和弹幕区框(蓝);另有默认收起的"实际送去识别的画面"。
 * 用来现场确认"标定有没有被碰歪",不用盲目重新标定。
 *
 * - **不自己打开摄像头**:只读 PreviewBus 里采集服务发布的图(原因见 PreviewBus)。所以只有采集在运行时才有画面。
 * - 页面可见期间 PreviewBus.active=true,采集服务才会按 FrameGate.PREVIEW_INTERVAL_MS 额外出预览图;
 *   离开页面立即停止发布并清空,不影响识别和上传的节奏。
 * - 画面是直播间内容:禁止截屏/录屏(FLAG_SECURE),只放内存,不落盘、不上传。调支架时需要常亮,所以保持亮屏。
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityPreviewBinding
    private val ui = Handler(Looper.getMainLooper())
    private var shownVersion = -1
    private var lastImageAt = 0L
    private var cropExpanded = false          // "实际送去识别的画面"是否展开(每次进页面默认收起)

    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        // 必须在 setContentView 之前设:禁止截屏 / 录屏 / 最近任务缩略图,并保持亮屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        b = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }          // 返回首页(预览页是从首页打开的)
        b.cropHeader.setOnClickListener { setCropExpanded(!cropExpanded) }
        setCropExpanded(false)
        b.btnPreviewStart.setOnClickListener {
            if (!Prefs.calibrated) startActivity(Intent(this, CameraAlignActivity::class.java))
            else CaptureService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        shownVersion = -1
        PreviewBus.setActive(true)
        PreviewBus.setCropWanted(cropExpanded)
        ui.post(tick)
    }

    override fun onPause() {
        // 离开页面:停止发布并清空(释放内存,也避免下次进来先闪出旧画面)
        ui.removeCallbacks(tick)
        PreviewBus.setActive(false)
        super.onPause()
    }

    private fun render() {
        if (!Prefs.bound) {
            // 预览期间绑定被清掉(令牌失效 / 解除绑定):回首页,首页会再转去绑定页
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (!CaptureService.running) {
            showEmpty(
                if (!Prefs.calibrated) "还没有标定,无法预览。请先完成标定。"
                else "采集未开始。预览页不会自己打开摄像头(会和采集抢摄像头),请先开始采集,再回来查看。"
            )
            b.btnPreviewStart.text = if (Prefs.calibrated) "开始采集" else "去标定"
            b.previewStatus.text = ""
            return
        }
        b.previewEmpty.visibility = View.GONE

        val now = System.currentTimeMillis()
        val snap = PreviewBus.latest()
        val version = PreviewBus.version()
        if (snap != null && version != shownVersion) {
            shownVersion = version
            lastImageAt = snap.ts
            b.imgMain.setImageBitmap(snap.raw)
            if (cropExpanded) updateCropImage(snap.crop)
            b.previewContent.visibility = View.VISIBLE
        }
        if (snap == null) {
            b.previewContent.visibility = View.GONE
            b.previewStatus.text = "等待第一帧画面…"
            return
        }

        val imageAgo = (now - lastImageAt) / 1000
        val cameraAgo = (now - CaptureService.lastFrameAt) / 1000
        b.previewStatus.text = when {
            cameraAgo > 5 -> "⚠ 摄像头 ${cameraAgo} 秒没有出帧(正在自动重试),画面停在 ${imageAgo} 秒前"
            imageAgo > 5 -> "⚠ 预览画面已 ${imageAgo} 秒没有更新"
            else -> "实时预览中 · 每秒约刷新 2 次"
        }

        val lines = PreviewBus.lines()
        val linesAt = PreviewBus.linesAt()
        b.previewLines.text = when {
            linesAt == 0L -> "识别结果:等待第一次识别(识别频率比预览低,约每 2 秒一次)"
            lines.isEmpty() -> "识别结果:${(now - linesAt) / 1000} 秒前这一帧没有识别出文字"
            else -> "识别结果(${(now - linesAt) / 1000} 秒前):\n" + lines.joinToString("\n")
        }
    }

    /** 展开 / 收起"实际送去识别的画面"。展开后采集服务才会额外生成这张图,收起则不生成(预览帧更轻)。 */
    private fun setCropExpanded(expanded: Boolean) {
        cropExpanded = expanded
        PreviewBus.setCropWanted(expanded)
        b.btnToggleCrop.text = if (expanded) "收起 ▴" else "展开 ▾"
        b.cropHint.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) updateCropImage(PreviewBus.latest()?.crop) else b.imgCrop.visibility = View.GONE
    }

    private fun updateCropImage(crop: android.graphics.Bitmap?) {
        if (crop != null) {
            b.imgCrop.setImageBitmap(crop)
            b.imgCrop.visibility = View.VISIBLE
            b.cropHint.text = "这是弹幕区裁剪、增强后真正交给文字识别的图。字太小、太糊或对比度低,识别就会差。"
        } else {
            b.imgCrop.visibility = View.GONE
            b.cropHint.text = "正在生成…"
        }
    }

    private fun showEmpty(text: String) {
        b.previewContent.visibility = View.GONE
        b.previewEmpty.visibility = View.VISIBLE
        b.previewEmptyText.text = text
    }

    private companion object {
        const val REFRESH_MS = 300L
    }
}
