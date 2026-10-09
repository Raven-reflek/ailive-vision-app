package com.luxofilms.vision

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 持续摄像头采集的前台服务:绑定 ImageAnalysis,按 Prefs.sampleFps 节流取帧,
 * 每一帧交给 FrameProcessor 处理(透视纠正+裁剪+预处理+编码+上传云端)。
 * 不绑定 Preview,标定完成后可以完全无界面运行。
 */
class CaptureService : LifecycleService() {

    private val io = Executors.newSingleThreadExecutor()
    private val lastProcessedAt = AtomicLong(0L)
    private val rateLimitedUntil = AtomicLong(0L)   // 服务端返回 429 后,在这个时刻之前不再尝试上传
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var calibration: Calibration? = null
    @Volatile private var fps = 0.5f                    // 缓存采样频率:Prefs 是加密存储,每帧读一次要解密一次
    private val main = Handler(Looper.getMainLooper())
    private var foregroundOk = false
    private var stalledRounds = 0                       // 看门狗连续发现"没有画面"的轮数
    private var stallAlerted = false

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("正在采集弹幕…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            foregroundOk = true
            running = true
        } catch (e: Exception) {
            // 后台启动(开机自启)的前台服务,安卓 14 起不允许是 camera 类型,会抛 SecurityException:
            // 以前这里没接住,整个 APP 直接崩;现在留一条可见告警,让店员点进来手动开始(前台启动一定行)
            Alerts.bootResumeFailed(this, e.javaClass.simpleName)
            stopSelf()
            return
        }
        // 真正的"加载标定+绑定摄像头"统一放到 onStartCommand() 里做(见 refreshAndBind()),
        // 不在 onCreate 单独写一份——否则"服务已经在跑,只是想让它重新读一次标定/重新抓一次摄像头"这个
        // 场景没有入口:startForegroundService() 对已存在的服务只会触发 onStartCommand,不会重新走 onCreate。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!foregroundOk) return START_NOT_STICKY
        refreshAndBind()
        return START_STICKY
    }

    /** (重新)加载标定数据、(重新)绑定摄像头。幂等——不管是服务刚起来的第一次调用,还是"重新标定"完成后
     * 想让已经在跑的服务把新数据接上,调这一个方法就够。
     *
     * 2026-09-28 修的硬伤:以前 onCreate 只在服务刚创建时做一次这套事,重新标定时 CameraAlignActivity
     * 会把进程级共享的 ProcessCameraProvider 整体 unbindAll()(拆掉这个服务正绑着的摄像头),完了只调
     * CaptureService.start()——服务已经在跑,Android 不会再走一次 onCreate,而 onStartCommand 原来什么
     * 都不做,于是摄像头就这么悄悄没了,通知却还显示"正在采集"。现在 onStartCommand 每次都会重新走一遍
     * 这里,不管服务是刚起还是已经在跑,摄像头和标定数据都会跟着刷新一次。 */
    private fun refreshAndBind() {
        cameraProvider?.unbindAll()
        calibration = CalibrationStore.load()
        if (calibration == null) {
            // 没标定过没法采集(理论上不该发生,BootReceiver/MainActivity都会先检查)
            stopSelf()
            return
        }
        fps = Prefs.sampleFps.coerceIn(0.2f, 5f)
        lastFrameAt = System.currentTimeMillis()
        stalledRounds = 0
        stallAlerted = false
        startCamera()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** 看门狗:摄像头被别的应用占用、被系统回收、开机后台启动拿不到相机……这些情况下 CameraX 不一定报错,
     * 只是不再出帧,而通知栏仍写着"正在采集"。以前完全没人发现。这里每 15 秒看一次最近一帧的时间:
     * 超过 45 秒没帧就重新绑定摄像头,连续 3 轮(约 1 分钟)还不行就发告警让店员处理。 */
    private val watchdog = object : Runnable {
        override fun run() {
            fps = Prefs.sampleFps.coerceIn(0.2f, 5f)
            val silentMs = System.currentTimeMillis() - lastFrameAt
            if (silentMs > STALL_MS) {
                stalledRounds++
                setStatus("⚠ 摄像头 ${silentMs / 1000} 秒没有画面,正在重试")
                if (stalledRounds >= 3 && !stallAlerted) {
                    stallAlerted = true
                    Alerts.cameraStalled(this@CaptureService, "摄像头连续约一分钟没有画面,可能被别的应用占用或被系统限制。点这里打开 APP,重新点“开始采集”。")
                }
                startCamera()
            } else if (stalledRounds > 0) {
                stalledRounds = 0
                stallAlerted = false
                setStatus("正在采集弹幕…")
                Alerts.clearCamera(this@CaptureService)
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun setStatus(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                setStatus("⚠ 摄像头启动失败,正在重试")   // 不 stopSelf:看门狗会继续重试并在持续失败时告警
                return@addListener
            }
            cameraProvider = provider

            // 跟标定引导页用同一套分辨率策略(CameraTuning)——分辨率一致,比例坐标才对得上同一个地方;
            // 分辨率本身也不能太低,弹幕区域两次裁剪后文字容易小到认不出(2026-09-28 修的硬伤)。
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(CameraTuning.resolutionSelector())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(io) { proxy ->
                val cal = calibration
                val now = System.currentTimeMillis()
                lastFrameAt = now                       // 不管后面是否丢帧,"摄像头在出帧"本身就是看门狗要的信号
                if (cal == null) { proxy.close(); return@setAnalyzer }
                val minIntervalMs = (1000f / fps).toLong()
                val last = lastProcessedAt.get()
                if (now < rateLimitedUntil.get() || now - last < minIntervalMs) {
                    proxy.close()   // 还没到下一次采样时间,或者刚被服务端限流、还在冷却期:丢帧
                                     // (STRATEGY_KEEP_ONLY_LATEST 保证不会堆积,不是排队重试)
                    return@setAnalyzer
                }
                lastProcessedAt.set(now)
                try {
                    if (FrameProcessor.process(proxy, cal) == FrameProcessor.Result.RATE_LIMITED) {
                        // 服务端 40次/60秒 的窗口按设备算,不是按这一帧算:被拒一次,后面短时间内再试
                        // 大概率还是被拒,干脆整个冷却窗口内都不再打(2026-09-28 修的硬伤:以前默认
                        // 采样频率本身就超过服务端限流,约三分之一请求被拒且没有任何退避,白白浪费请求)。
                        rateLimitedUntil.set(now + RATE_LIMIT_COOLDOWN_MS)
                    }
                } finally {
                    proxy.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                setStatus("⚠ 摄像头启动失败,正在重试")   // 同上:交给看门狗重试,而不是悄悄 stopSelf 后通知栏还留着旧状态
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CH_CAPTURE)
            .setContentTitle("AI直播弹幕采集")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        running = false
        Alerts.clearCamera(this)
        cameraProvider?.unbindAll()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2001
        private const val RATE_LIMIT_COOLDOWN_MS = 65_000L   // 服务端限流窗口是 60 秒滑动窗口,多留 5 秒余量
        private const val WATCHDOG_MS = 15_000L
        private const val STALL_MS = 45_000L

        /** 服务是否在跑(首页据此显示状态,不再用 Activity 里一个会随重建丢失的本地变量) */
        @Volatile var running = false
            private set
        /** 摄像头最近一次出帧的时刻(毫秒);首页用来提示"N 秒没有画面" */
        @Volatile var lastFrameAt = 0L
            private set

        /** 店员的明确意图:开始采集(含标定完成)。记下"想要采集",重启后才会自动恢复。 */
        fun start(context: Context) {
            Prefs.captureWanted = true
            launch(context)
        }

        /** 自动恢复(开机、标定页退出):不改变店员的意图 —— 主动停掉的,不会被这里偷偷再打开 */
        fun resume(context: Context) = launch(context)

        fun stop(context: Context) {
            Prefs.captureWanted = false
            context.stopService(Intent(context, CaptureService::class.java))
        }

        private fun launch(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }
    }
}
