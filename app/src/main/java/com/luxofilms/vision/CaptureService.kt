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
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var calibration: Calibration? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification("正在采集弹幕…")
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        calibration = CalibrationStore.load()
        if (calibration == null) {
            // 没标定过没法采集,直接停(理论上不该发生,BootReceiver/MainActivity都会先检查)
            stopSelf()
            return
        }
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(io) { proxy ->
                val cal = calibration
                if (cal == null) { proxy.close(); return@setAnalyzer }
                val fps = Prefs.sampleFps.coerceIn(0.2f, 5f)
                val minIntervalMs = (1000f / fps).toLong()
                val now = System.currentTimeMillis()
                val last = lastProcessedAt.get()
                if (now - last < minIntervalMs) {
                    proxy.close()   // 还没到下一次采样时间,丢帧(STRATEGY_KEEP_ONLY_LATEST保证不会堆积)
                    return@setAnalyzer
                }
                lastProcessedAt.set(now)
                try {
                    FrameProcessor.process(proxy, cal)
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

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
