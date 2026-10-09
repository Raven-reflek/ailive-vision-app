package com.luxofilms.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.luxofilms.vision.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 首页:绑定/标定/采集状态一览,不需要一直盯着看(采集是后台服务),主要给店员排查问题用。 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    // 采集状态以服务自己为准(CaptureService.running),不在 Activity 里存一份:开机自启、Activity 重建、
    // 标定完成后服务都在跑,本地变量却是 false,界面就会错误地显示"已停止"
    private val capturing: Boolean get() = CaptureService.running

    // 安卓 13+ 通知要运行时授权;没授权的话前台服务的通知和所有告警都看不到,等于采集出了问题也没人知道
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (!Prefs.bound) {
            startActivity(Intent(this, BindActivity::class.java))
            finish()
            return
        }

        b.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CameraAlignActivity::class.java))
        }
        b.btnCheckUpdate.setOnClickListener { UpdateChecker.check(this, silent = false) }
        askNotificationPermission()
        b.btnToggleCapture.setOnClickListener {
            if (!Prefs.calibrated) {
                startActivity(Intent(this, CameraAlignActivity::class.java))
                return@setOnClickListener
            }
            if (capturing) CaptureService.stop(this) else CaptureService.start(this)
            // 服务启停是异步的:稍等一下再刷新,不然刚点完界面还是旧状态
            ui.postDelayed({ render() }, 400)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.post(refresh)
        // 静默检查更新:这个APP只有一个 MainActivity(不像门店APP底部四个tab互相重建导致
        // onCreate反复触发),onResume 已经够用——每次回到前台查一次,同一版本每次进程只弹一次弹窗
        // (UpdateChecker.handledCode),不会来回打扰。以前压根没有这个检查,装上就是那个版本。
        UpdateChecker.check(this, silent = true)
        maybeAskBatteryWhitelist()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 长期无人值守挂机,系统的省电策略会把后台服务冻住/杀掉。引导店员加入电池优化白名单,只问一次。 */
    private fun maybeAskBatteryWhitelist() {
        if (Prefs.batteryAsked || !Prefs.calibrated) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { Prefs.batteryAsked = true; return }
        Prefs.batteryAsked = true
        AlertDialog.Builder(this)
            .setTitle("允许后台持续运行?")
            .setMessage("弹幕采集要长时间在后台拍摄。请允许本应用忽略电池优化,否则手机可能在几小时后把采集悄悄停掉。下一屏点“允许”即可。")
            .setPositiveButton("去设置") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        b.storeInfo.text = "门店:${Prefs.storeName.ifEmpty { Prefs.storeId }} · ${Prefs.server}"

        val cal = CalibrationStore.load()
        b.statusCalibration.text = if (cal != null) {
            val ago = (System.currentTimeMillis() - cal.calibratedAt) / 60000
            "标定状态:已完成(${ago}分钟前)"
        } else {
            "标定状态:尚未标定"
        }

        val silentSec = (System.currentTimeMillis() - CaptureService.lastFrameAt) / 1000
        b.statusCapture.text = when {
            !capturing -> "采集状态:已停止"
            silentSec > 45 -> "采集状态:⚠ 运行中,但摄像头 ${silentSec} 秒没有画面(正在自动重试)"
            else -> "采集状态:运行中"
        }
        b.btnToggleCapture.text = if (capturing) "停止采集" else "开始采集"

        renderRecognized()
    }

    /** 把最近识别到的几条弹幕画出来:装机/调试时肉眼核对"摄像头认出了什么",
     * 比对着实际弹幕能直接判断标定框有没有歪、识别准不准。 */
    private fun renderRecognized() {
        val list = RecognizedLog.recent()
        b.recognizedList.removeAllViews()
        b.recognizedEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dp10 = (10 * resources.displayMetrics.density).toInt()
        for (e in list) {
            val head = SpannableStringBuilder(fmt.format(Date(e.ts))).append(" · ")
            val statusStart = head.length
            head.append(if (e.uploaded) "已上传" else "上传失败,这几行已丢弃")
            head.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, if (e.uploaded) R.color.ok else R.color.bad)),
                statusStart, head.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val headView = TextView(this).apply {
                text = head; textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            }
            val bodyView = TextView(this).apply {
                text = e.lines.joinToString("  /  ")
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                setPadding(0, 2, 0, dp10)
            }
            b.recognizedList.addView(headView)
            b.recognizedList.addView(bodyView)
        }
    }
}
