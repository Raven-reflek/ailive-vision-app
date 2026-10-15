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
import java.util.concurrent.Executors

/** 首页:绑定/标定/采集状态一览,不需要一直盯着看(采集是后台服务),主要给店员排查问题用。 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastTokenCheckAt = 0L          // 上次令牌探测的时刻,配合 BindingGuard.shouldCheck 限频
    private var redirectedToBind = false       // 已经因为"不再是绑定状态"跳去绑定页,别重复跳
    private var renderedRecognizedVersion = -1 // 上次画"最近识别到的弹幕"时 RecognizedLog 的版本;没变就不重建
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
        // "最近识别到的弹幕":固定高度、区域内滚动,可展开 / 收起;点标题行或右侧文字切换
        applyRecognizedExpanded(Prefs.recognizedExpanded)
        val toggleRecognized = View.OnClickListener {
            val expanded = !Prefs.recognizedExpanded
            Prefs.recognizedExpanded = expanded
            applyRecognizedExpanded(expanded)
        }
        b.btnToggleRecognized.setOnClickListener(toggleRecognized)
        b.recognizedTitleArea.setOnClickListener(toggleRecognized)
        b.btnClearRecognized.setOnClickListener {
            RecognizedLog.clear()
            renderRecognized()
        }
        b.btnUnbind.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("解除绑定?")
                .setMessage("将停止采集并清除本机的登录信息,需要重新扫码绑定才能继续。标定数据会保留,不用重新标定。")
                .setPositiveButton("解除绑定") { _, _ ->
                    BindingGuard.clearBindingAndStop(this, BindingGuard.NOTICE_UNBOUND, alert = false)
                    render()          // 已不是绑定状态 → render() 会跳去绑定页
                }
                .setNegativeButton("取消", null)
                .show()
        }
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
        checkToken()
    }

    /**
     * 回到前台时探测一次令牌是否还有效(限频,见 BindingGuard.shouldCheck)。
     * 上传路径只有"弹幕区域里真的识别出文字"才会触发,采集空闲时撤销了设备是发现不了的;这里补上这个入口。
     * 只有服务端明确回 401/410 + need_bind 才解绑;网络错误、超时、5xx 都当作"这次没探测成",
     * 不动绑定(店员手机一时没信号不该被清掉绑定),下次回前台再试。
     */
    private fun checkToken() {
        if (!Prefs.bound) return
        val now = System.currentTimeMillis()
        if (!BindingGuard.shouldCheck(now, lastTokenCheckAt)) return
        lastTokenCheckAt = now
        io.execute {
            try {
                Api.me()
                ui.post { showStoreWarn(null) }
            } catch (e: Api.ApiError) {
                when (BindingGuard.classify(e)) {
                    BindingGuard.Verdict.TOKEN_INVALID -> ui.post {
                        BindingGuard.clearBindingAndStop(this, BindingGuard.NOTICE_INVALID, alert = true)
                        render()
                    }
                    BindingGuard.Verdict.STORE_DISABLED -> ui.post { showStoreWarn(BindingGuard.WARN_STORE_DISABLED) }
                    BindingGuard.Verdict.OTHER -> lastTokenCheckAt = 0L      // 没探测成,下次回前台可以再试
                }
            } catch (e: Exception) {
                lastTokenCheckAt = 0L                                        // 网络问题:同上,不动绑定
            }
        }
    }

    private fun showStoreWarn(text: String?) {
        b.statusWarn.text = text ?: ""
        b.statusWarn.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
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

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        if (!Prefs.bound) {
            // 绑定已被清掉(令牌失效 / 店员解除绑定,可能发生在采集服务里,首页每 3 秒会走到这里):回绑定页
            if (!redirectedToBind) {
                redirectedToBind = true
                startActivity(Intent(this, BindActivity::class.java))
                finish()
            }
            return
        }
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
        // 内容没变就不重建:render() 每 3 秒跑一次,每次都清空重画会把店员正在翻看的滚动位置重置回顶部
        val version = RecognizedLog.version()
        if (version == renderedRecognizedVersion) return
        renderedRecognizedVersion = version

        // 重建前记住"店员正在看的那一条"(第一条还没滚出视口的)和它相对视口顶部的偏移。
        // 新条目插在最上面时,如果只恢复数值相同的 scrollY,内容会被顶下去、正在看的那条跳走;
        // 按条目 id 重新定位,就能让它留在原地。本来就在最顶上(scrollY==0)的不锚定,继续跟着看最新。
        val oldScrollY = b.recognizedScroll.scrollY
        var anchorId = -1L
        var anchorOffset = 0
        if (oldScrollY > 0) {
            for (i in 0 until b.recognizedList.childCount - 1 step 2) {
                val head = b.recognizedList.getChildAt(i)
                val body = b.recognizedList.getChildAt(i + 1)
                if (body.bottom > oldScrollY) {
                    anchorId = (head.tag as? Long) ?: -1L
                    anchorOffset = oldScrollY - head.top
                    break
                }
            }
        }

        val list = RecognizedLog.recent()
        b.recognizedList.removeAllViews()
        b.recognizedEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.btnClearRecognized.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
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
                tag = e.id
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
        // 重建后等布局完成再恢复位置:锚定的那一条还在就让它回到原来的偏移;
        // 已经被挤出 20 条上限(或清空了)就退回原来的数值位置,系统会自动夹到合法范围
        b.recognizedScroll.post {
            if (oldScrollY <= 0) { b.recognizedScroll.scrollTo(0, 0); return@post }
            var target = oldScrollY
            for (i in 0 until b.recognizedList.childCount step 2) {
                val head = b.recognizedList.getChildAt(i)
                if (head.tag == anchorId) { target = head.top + anchorOffset; break }
            }
            b.recognizedScroll.scrollTo(0, target)
        }
    }

    /**
     * 展开 / 收起"最近识别到的弹幕"区域:改的是固定高度,区域内部始终可滚动,
     * 所以不管条目有多少,卡片高度恒定、下面的"重新标定"等按钮不会被挤到很远。
     * 收起 150dp(约 2~3 条),展开 400dp,且最多不超过屏幕高度的一半(小屏手机不至于顶掉按钮)。
     */
    private fun applyRecognizedExpanded(expanded: Boolean) {
        val density = resources.displayMetrics.density
        val collapsedPx = (RECOGNIZED_COLLAPSED_DP * density).toInt()
        val expandedPx = minOf((RECOGNIZED_EXPANDED_DP * density).toInt(), resources.displayMetrics.heightPixels / 2)
        b.recognizedScroll.layoutParams = b.recognizedScroll.layoutParams.apply {
            height = if (expanded) maxOf(expandedPx, collapsedPx) else collapsedPx
        }
        b.btnToggleRecognized.text = if (expanded) "收起 ▴" else "展开 ▾"
        b.btnToggleRecognized.contentDescription = if (expanded) "收起最近识别到的弹幕区域" else "展开最近识别到的弹幕区域"
    }

    private companion object {
        const val RECOGNIZED_COLLAPSED_DP = 150
        const val RECOGNIZED_EXPANDED_DP = 400
    }
}
