package com.luxofilms.vision

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
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

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification("正在采集弹幕…")
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        // 真正的"加载标定+绑定摄像头"统一放到 onStartCommand() 里做(见 refreshAndBind()),
        // 不在 onCreate 单独写一份——否则"服务已经在跑,只是想让它重新读一次标定/重新抓一次摄像头"这个
        // 场景没有入口:startForegroundService() 对已存在的服务只会触发 onStartCommand,不会重新走 onCreate。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
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
                if (cal == null) { proxy.close(); return@setAnalyzer }
                val now = System.currentTimeMillis()
                val fps = Prefs.sampleFps.coerceIn(0.2f, 5f)
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
                stopSelf()
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
        cameraProvider?.unbindAll()
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2001
        private const val RATE_LIMIT_COOLDOWN_MS = 65_000L   // 服务端限流窗口是 60 秒滑动窗口,多留 5 秒余量

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
