package com.luxofilms.vision

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 持续摄像头采集的前台服务:绑定 ImageAnalysis,按 Prefs.sampleFps 节流取帧,
 * 每一帧交给 FrameProcessor 处理(透视纠正+裁剪+预处理+编码+上传云端)。
 * 不绑定 Preview,标定完成后可以完全无界面运行。
 */
class CaptureService : LifecycleService() {

    private val io = Executors.newSingleThreadExecutor()
    private val lastProcessedAt = AtomicLong(0L)
    @Volatile private var lastPreviewAt = 0L        // 上次出预览图的时刻(只在分析器线程读写)
    private val rateLimitedUntil = AtomicLong(0L)   // 服务端返回 429 后,在这个时刻之前不再尝试上传
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var calibration: Calibration? = null
    @Volatile private var bindingInvalidated = false    // 已发现令牌作废:之后的帧直接丢弃,不再识别/上传
    @Volatile private var fps = 0.5f                    // 缓存采样频率:Prefs 是加密存储,每帧读一次要解密一次
    private val main = Handler(Looper.getMainLooper())
    private var foregroundOk = false
    private var stalledRounds = 0                       // 看门狗连续发现"没有画面"的轮数
    private var stallAlerted = false

    // 心跳(契约 04「视觉采集心跳」):单独一个线程,**不能**和 io(分析器线程)共用——分析器卡在 OCR / 上传时,
    // 心跳必须照发,卡住这件事正是要靠它的 frame_ago / ocr_ago 告诉云端。线程只由主线程创建 / 销毁,心跳任务自己在里面排下一次。
    private var hbExec: ScheduledExecutorService? = null
    @Volatile private var hbStopped = false             // 令牌失效 / 服务销毁:不再排下一次心跳

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("正在采集弹幕…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            foregroundOk = true
            running = true
            CaptureStats.reset()                // 采集服务真正起来了:心跳的计数与 uptime 从这里算起
            Alerts.clearStartFailure(this)
        } catch (e: Exception) {
            // 起不来有两类原因,告警要分开说(CameraPermission.startFailure):
            // ① 摄像头权限被关(设置里撤销会让系统杀掉进程、服务随后被重启,走到这里)——店员得去开权限;
            // ② 后台启动(开机自启)的前台服务,安卓 14 起不允许是 camera 类型,会抛 SecurityException——点进来手动开始(前台启动一定行)。
            // 以前这里没接住,整个 APP 直接崩;后来只留了②的文案,①也被说成"手机重启后没能自动恢复"
            Alerts.captureStartFailed(this, e)
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
        startHeartbeat()
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
                CaptureStats.noteFrame()                // 同一个信号也给心跳(单调时钟,不受手机对时影响)
                if (cal == null || bindingInvalidated) { proxy.close(); return@setAnalyzer }
                val minIntervalMs = (1000f / fps).toLong()
                // 这一帧该做什么由 FrameGate 决定:OCR 的节奏和限流冷却与以前完全一致;
                // 另外,"采集预览"页打开期间按更高频率出预览图(预览页自己不开摄像头,只读这里发布的图)
                val decision = FrameGate.decide(
                    now = now, lastOcrAt = lastProcessedAt.get(), ocrIntervalMs = minIntervalMs,
                    rateLimitedUntil = rateLimitedUntil.get(),
                    previewActive = PreviewBus.active, lastPreviewAt = lastPreviewAt,
                )
                if (decision.skip) {
                    proxy.close()   // 还没到下一次采样时间,或者刚被服务端限流、还在冷却期,且没人在看预览:丢帧
                                     // (STRATEGY_KEEP_ONLY_LATEST 保证不会堆积,不是排队重试)
                    return@setAnalyzer
                }
                if (decision.preview) lastPreviewAt = now
                if (!decision.ocr) {
                    // 只出预览图,不识别、不上传,也不动 OCR 的计时
                    try { FrameProcessor.preview(proxy, cal) } finally { proxy.close() }
                    return@setAnalyzer
                }
                lastProcessedAt.set(now)
                try {
                    when (FrameProcessor.process(proxy, cal, publishPreview = decision.preview)) {
                        FrameProcessor.Result.RATE_LIMITED -> {
                            // 服务端 40次/60秒 的窗口按设备算,不是按这一帧算:被拒一次,后面短时间内再试
                            // 大概率还是被拒,干脆整个冷却窗口内都不再打(2026-09-28 修的硬伤:以前默认
                            // 采样频率本身就超过服务端限流,约三分之一请求被拒且没有任何退避,白白浪费请求)。
                            rateLimitedUntil.set(now + RATE_LIMIT_COOLDOWN_MS)
                        }
                        FrameProcessor.Result.NEED_REBIND -> {
                            // 令牌已被服务端作废:重试没有意义。先置标志挡住后面还在排队的帧,
                            // 再到主线程清绑定(保留标定)+停服务+发告警(clearBindingAndStop 幂等)。
                            bindingInvalidated = true
                            main.post {
                                BindingGuard.clearBindingAndStop(this@CaptureService, BindingGuard.NOTICE_INVALID, alert = true)
                            }
                        }
                        else -> Unit
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

    /**
     * 启动心跳(幂等)。refreshAndBind() 每次都会走到这里:已经在发就不动它——重新标定不该把发送节奏打回 5 秒,也不该多起线程。
     * 第一次心跳在启动约 5 秒后(留给摄像头出第一帧)。之后每次发完再按 [HeartbeatSchedule] 排下一次。
     */
    private fun startHeartbeat() {
        if (hbExec?.isShutdown == false) return
        hbStopped = false
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vision-heartbeat").apply { isDaemon = true } }
        hbExec = exec
        scheduleBeat(exec, HeartbeatSchedule(), HeartbeatSchedule.INITIAL_DELAY_S)
    }

    private fun scheduleBeat(exec: ScheduledExecutorService, schedule: HeartbeatSchedule, delayS: Int) {
        if (hbStopped) return
        try {
            exec.schedule(Runnable { beat(exec, schedule) }, delayS.toLong(), TimeUnit.SECONDS)
        } catch (e: RejectedExecutionException) {
            // 服务正在销毁,线程池已关
        }
    }

    /** 发一次心跳并排好下一次。在心跳线程里跑,**不能让异常逃出去**(逃出去会让后续所有心跳悄悄停掉)。 */
    private fun beat(exec: ScheduledExecutorService, schedule: HeartbeatSchedule) {
        if (hbStopped || bindingInvalidated) return      // 服务在销毁,或上传路径已发现令牌作废、正在善后:不再发,也不再排
        var next: Int?
        try {
            val resp = Api.heartbeat(buildHeartbeat(this, Heartbeat.STATE_CAPTURING).toJson())
            next = schedule.onSuccess(resp.optInt("interval", 0))    // 以服务端建议的间隔为准(缺省 60 秒,夹到 30~300)
        } catch (e: Api.ApiError) {
            val verdict = BindingGuard.classify(e)
            Log.w(TAG, "心跳失败 HTTP ${e.code}:${e.message}")
            next = schedule.onError(e.code, verdict)                 // 令牌失效 → null(不重试);门店停用 → 照常节奏、不解绑;404 → 10 分钟;其余退避
            if (verdict == BindingGuard.Verdict.TOKEN_INVALID) {
                // 与上传路径一致:令牌已作废(总部撤销 / 换新手机 / 门店删除)→ 先挡住后面的帧,再到主线程清绑定(保留标定)+ 停服务 + 告警。
                // 采集空闲、没有上传的手机,靠这里也能在约 1 分钟内发现
                bindingInvalidated = true
                main.post {
                    BindingGuard.clearBindingAndStop(this@CaptureService, BindingGuard.NOTICE_INVALID, alert = true)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "心跳失败:${e.message}")                      // 断网、超时(callTimeout 8 秒)、被 shutdownNow 打断等
            next = schedule.onFailure()
        }
        if (next != null) scheduleBeat(exec, schedule, next)
    }

    /** 店员主动停止采集时,尽力补发一次 state=stopped,让云端说"已停止采集"而不是几分钟后报"失联"。 */
    private fun sendStoppedBeat() {
        val app = applicationContext
        val stats = CaptureStats.snapshot()      // 此刻的计数先取好:服务马上重新启动的话 CaptureStats 会被清零
        // 单独起一个一次性线程:onDestroy 在主线程,不能等网络;请求自带 8 秒总超时,发不出去就算了
        Thread({
            try {
                Api.heartbeat(buildHeartbeat(app, Heartbeat.STATE_STOPPED, stats).toJson())
            } catch (e: Throwable) {
                Log.w(TAG, "停止心跳没发出去:${e.message}")
            }
        }, "vision-heartbeat-stop").apply { isDaemon = true }.start()
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
        PreviewBus.clear()                  // 采集停了,预览页不该继续显示旧画面
        cameraProvider?.unbindAll()
        io.shutdownNow()
        val hadHeartbeat = hbExec != null
        hbStopped = true
        hbExec?.shutdownNow()
        hbExec = null
        // 只有"店员主动停止"才补发 stopped(CaptureService.stop 先把 captureWanted 置 false 再停服务)。
        // 系统回收 / 相机权限被撤(captureWanted 仍为 true)不发——云端靠"失联"判断;令牌作废 / 已解绑(bindingInvalidated、!bound)更没得发
        if (hadHeartbeat && !bindingInvalidated && !Prefs.captureWanted && Prefs.bound) sendStoppedBeat()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
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

        /**
         * 读设备状态(电量、充电、网络、相机权限)并和采集计数快照一起组装成心跳载荷。**不抛异常**:
         * 电量 / 网络读不到就留空(载荷里省略),不能因为这个让心跳发不出去。判定规则都在纯逻辑 [Heartbeat] 里。
         */
        private fun buildHeartbeat(ctx: Context, state: String, stats: CaptureStats.Snapshot = CaptureStats.snapshot()): HeartbeatPayload {
            var battery: Int? = null
            var charging: Boolean? = null
            try {
                // 粘性广播:receiver 传 null 不会注册任何接收器,直接返回系统缓存的最近一次电量状态
                val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (i != null) {
                    battery = Heartbeat.batteryPercent(
                        i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), i.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    )
                    charging = Heartbeat.isCharging(
                        i.getIntExtra(BatteryManager.EXTRA_STATUS, -1), i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "读电量失败:${e.message}")
            }
            val net: String? = try {
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                val network = cm?.activeNetwork
                val caps = if (cm != null && network != null) cm.getNetworkCapabilities(network) else null
                Heartbeat.netKind(
                    connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                    cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
                    ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
                )
            } catch (e: Exception) {
                Log.w(TAG, "读网络状态失败:${e.message}")
                null
            }
            return Heartbeat.build(state, BuildConfig.VERSION_CODE, battery, charging, net, CameraPermission.has(ctx), stats)
        }
    }
}
