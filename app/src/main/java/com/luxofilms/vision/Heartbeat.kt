package com.luxofilms.vision

import org.json.JSONObject

/**
 * 采集手机心跳(契约 04「视觉采集心跳与"采集手机"健康灯」):采集服务运行期间每 60 秒发一次 `POST /api/vision/heartbeat`,
 * 不管有没有新弹幕。云端靠它区分"直播间安静"和"采集手机没电 / 断网 / 被碰歪 / 应用被关"。
 *
 * 这里只放**纯逻辑**(组装载荷、电量 / 网络 / 摄像头状态的判定、发送节奏),不碰 Android 类型,可单元测试;
 * 读电量、查网络、起线程发请求在 CaptureService 里。载荷**只有数字与状态,没有图片、没有任何弹幕文字**。
 */
object Heartbeat {
    /** 心跳格式版本(契约字段 `v`) */
    const val FORMAT_VERSION = 1

    const val STATE_CAPTURING = "capturing"
    /** 店员主动停止采集时尽力补发一次 */
    const val STATE_STOPPED = "stopped"

    const val CAMERA_OK = "ok"
    const val CAMERA_STALLED = "stalled"
    const val CAMERA_NO_PERMISSION = "no_permission"

    const val NET_WIFI = "wifi"
    const val NET_CELL = "cell"
    const val NET_ETH = "eth"
    const val NET_NONE = "none"
    const val NET_OTHER = "other"

    /** 摄像头超过这么多秒没出帧就报 stalled(与 CaptureService 看门狗的 STALL_MS 同一个数,契约 04:45 秒) */
    const val CAMERA_STALL_SECONDS = 45

    /** BatteryManager.BATTERY_STATUS_CHARGING / BATTERY_STATUS_FULL(这里不引用 Android 类,保持可单元测试) */
    private const val BATTERY_STATUS_CHARGING = 2
    private const val BATTERY_STATUS_FULL = 5

    /**
     * 电量百分比 0~100(四舍五入)。level / scale 取自 ACTION_BATTERY_CHANGED 的粘性广播;
     * 读不到时系统给 -1,这类无效输入(level < 0、scale ≤ 0、level > scale)一律返回 null,不上报。
     */
    fun batteryPercent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0 || level > scale) return null
        return ((level * 100L + scale / 2) / scale).toInt()
    }

    /**
     * 是否在充电。EXTRA_PLUGGED 非 0(插着 AC / USB / 无线)或状态为充电中 / 已充满都算:
     * 云端要的是"没电了有没有人管"——插着电的手机即使系统报"未在充电"(电池保护)也不会掉电。
     */
    fun isCharging(status: Int, plugged: Int): Boolean =
        plugged > 0 || status == BATTERY_STATUS_CHARGING || status == BATTERY_STATUS_FULL

    /** 当前网络类型。没有可用网络 → none;有网但不是 wifi / 蜂窝 / 以太网(VPN、蓝牙共享……)→ other。 */
    fun netKind(connected: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean): String = when {
        !connected -> NET_NONE
        wifi -> NET_WIFI
        cellular -> NET_CELL
        ethernet -> NET_ETH
        else -> NET_OTHER
    }

    /**
     * 摄像头状态:没有相机权限 → no_permission;否则"距上次出帧的秒数"超过 45 → stalled。
     * 从没出过帧(frameAgo 为 null)时拿服务已运行的秒数顶替:刚启动的几秒还没出帧不算,启动 45 秒仍没出帧才算。
     */
    fun cameraState(permissionGranted: Boolean, frameAgo: Int?, uptime: Int?): String = when {
        !permissionGranted -> CAMERA_NO_PERMISSION
        (frameAgo ?: uptime ?: 0) > CAMERA_STALL_SECONDS -> CAMERA_STALLED
        else -> CAMERA_OK
    }

    /** 用设备信息 + 采集计数快照组装载荷。battery / charging / net 读不到传 null(载荷里省略)。 */
    fun build(
        state: String,
        appCode: Int,
        battery: Int?,
        charging: Boolean?,
        net: String?,
        cameraPermission: Boolean,
        stats: CaptureStats.Snapshot,
    ): HeartbeatPayload = HeartbeatPayload(
        state = state,
        app = appCode,
        battery = battery,
        charging = charging,
        net = net,
        camera = cameraState(cameraPermission, stats.frameAgo, stats.uptime),
        frameAgo = stats.frameAgo,
        ocrAgo = stats.ocrAgo,
        textAgo = stats.textAgo,
        uploadOkAgo = stats.uploadOkAgo,
        uploadFailStreak = stats.uploadFailStreak,
        uptime = stats.uptime,
    )
}

/**
 * 心跳载荷(字段名与契约 04 一字不差)。
 *
 * 空值约定:四个 `*_ago` 字段"从未发生"按契约发**显式 JSON null**;电量 / 是否充电 / 网络 / 运行秒数这几项只有"这次没读到"
 * 才会为空,契约允许字段缺省,所以**省略**不发(云端把缺省与脏值一样当作"未提供")。
 */
data class HeartbeatPayload(
    val state: String,
    val app: Int,
    val battery: Int?,
    val charging: Boolean?,
    val net: String?,
    val camera: String,
    val frameAgo: Int?,
    val ocrAgo: Int?,
    val textAgo: Int?,
    val uploadOkAgo: Int?,
    val uploadFailStreak: Int,
    val uptime: Int?,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", Heartbeat.FORMAT_VERSION)
        o.put("state", state)
        o.put("app", app)
        if (battery != null) o.put("battery", battery)
        if (charging != null) o.put("charging", charging)
        if (net != null) o.put("net", net)
        o.put("camera", camera)
        o.put("frame_ago", frameAgo ?: JSONObject.NULL)
        o.put("ocr_ago", ocrAgo ?: JSONObject.NULL)
        o.put("text_ago", textAgo ?: JSONObject.NULL)
        o.put("upload_ok_ago", uploadOkAgo ?: JSONObject.NULL)
        o.put("upload_fail_streak", uploadFailStreak)
        if (uptime != null) o.put("uptime", uptime)
        return o
    }
}

/**
 * 心跳的发送节奏。只在心跳线程里用,状态(连续失败次数)是实例的,不是进程级的:服务每次启动新建一份。
 *
 *  - 成功:按服务端回包里建议的 `interval` 秒发下一次,夹到 30~300 秒;回包没给 / 不是正数 → 60 秒;
 *  - 一般失败(断网、超时、5xx、429……):60 → 120 → 300 秒退避,300 封顶;一次成功就从头来;
 *  - 404(老云服务没有这个接口):10 分钟一次,不影响上传;云服务升级后第一次成功就回到正常节奏;
 *  - 令牌失效([BindingGuard.Verdict.TOKEN_INVALID]):**不重试**,[onError] 返回 null,由调用方走统一善后(清绑定、停采集、告警);
 *  - 门店被停用([BindingGuard.Verdict.STORE_DISABLED]):令牌仍有效、只是总部停了这家店,保持正常节奏,不解绑。
 */
class HeartbeatSchedule {
    private var failures = 0

    /** 一次成功(或服务端明确应答)之后的下一次间隔(秒)。suggested 是回包的 `interval`。 */
    fun onSuccess(suggested: Int?): Int {
        failures = 0
        return if (suggested == null || suggested <= 0) DEFAULT_INTERVAL_S else suggested.coerceIn(MIN_INTERVAL_S, MAX_INTERVAL_S)
    }

    /** 一般失败后的下一次间隔(秒):连续第 1、2、3+ 次分别是 60、120、300。 */
    fun onFailure(): Int {
        failures++
        return BACKOFF_S[(failures - 1).coerceAtMost(BACKOFF_S.size - 1)]
    }

    /**
     * 服务端以错误应答之后的下一次间隔(秒);null = 不要再发了(令牌失效)。
     * code 是 HTTP 状态码,verdict 是 [BindingGuard.classify] 的结论。
     */
    fun onError(code: Int, verdict: BindingGuard.Verdict): Int? = when {
        verdict == BindingGuard.Verdict.TOKEN_INVALID -> null
        verdict == BindingGuard.Verdict.STORE_DISABLED -> onSuccess(null)
        code == 404 -> { failures = 0; SLOW_INTERVAL_S }
        else -> onFailure()
    }

    companion object {
        const val INITIAL_DELAY_S = 5          // 采集服务起来后第一次心跳
        const val DEFAULT_INTERVAL_S = 60
        const val MIN_INTERVAL_S = 30
        const val MAX_INTERVAL_S = 300
        const val SLOW_INTERVAL_S = 600
        private val BACKOFF_S = intArrayOf(60, 120, 300)
    }
}
