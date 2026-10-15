package com.luxofilms.vision

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 设备绑定信息 + 标定数据。token 加密存储(EncryptedSharedPreferences)。 */
object Prefs {
    private lateinit var sp: SharedPreferences

    /** 仅供单元测试注入内存实现(真实代码一律走 init(ctx))。 */
    internal fun initForTest(prefs: SharedPreferences) { sp = prefs }

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = try {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, "vision_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 极少数机型密钥库异常:退回普通存储,保证能用(token 仍是可撤销的长效令牌)
            ctx.getSharedPreferences("vision_plain", Context.MODE_PRIVATE)
        }
    }

    /** 形如 https://store-001.example.com,无尾斜杠 */
    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trimEnd('/')).apply()

    var storeId: String
        get() = sp.getString("store", "") ?: ""
        set(v) = sp.edit().putString("store", v).apply()

    var storeName: String
        get() = sp.getString("store_name", "") ?: ""
        set(v) = sp.edit().putString("store_name", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    val bound: Boolean get() = server.isNotEmpty() && storeId.isNotEmpty() && token.isNotEmpty()

    /** 标定数据(JSON字符串,见 CalibrationStore.kt 的 Calibration 序列化格式);空字符串 = 未标定过 */
    var calibrationJson: String
        get() = sp.getString("calibration", "") ?: ""
        set(v) = sp.edit().putString("calibration", v).apply()

    val calibrated: Boolean get() = calibrationJson.isNotEmpty()

    /** 采样频率(fps)。云服务 /api/vision/text 限流是每设备 40次/60秒(server.py VISION_LIMIT),
     * 换算成持续频率上限约 0.67fps——默认 1.0fps 会稳定超限,约三分之一请求被拒且不重试
     * (2026-09-28 修的硬伤)。0.5fps(每2秒一次)留出安全余量,真机联调后再按识别延迟/需要的
     * 实时性校准,但不能超过约 0.6fps,否则持续运行必然触发限流。 */
    var sampleFps: Float
        get() = sp.getFloat("sample_fps", 0.5f)
        set(v) = sp.edit().putFloat("sample_fps", v).apply()

    /** 店员是不是想让它一直采集。点"开始采集"/标定完成 = true,点"停止采集" = false。
     * 开机自启和标定页退出时的自动拉起都要看它 —— 店员主动停掉的,重启后不能又自己把摄像头打开。
     * 默认 true:升级前已经在采集的手机,重启后照常恢复。 */
    var captureWanted: Boolean
        get() = sp.getBoolean("capture_wanted", true)
        set(v) = sp.edit().putBoolean("capture_wanted", v).apply()

    /** 首页"最近识别到的弹幕"区域是否展开(默认收起)。只是界面偏好,令牌失效/解除绑定时不动它。 */
    var recognizedExpanded: Boolean
        get() = sp.getBoolean("recognized_expanded", false)
        set(v) = sp.edit().putBoolean("recognized_expanded", v).apply()

    /** 电池优化白名单的引导弹窗只问一次,不反复打扰 */
    var batteryAsked: Boolean
        get() = sp.getBoolean("battery_asked", false)
        set(v) = sp.edit().putBoolean("battery_asked", v).apply()

    /** 绑定失效 / 解除绑定后留给绑定页显示的一句话(为什么回到了绑定页);绑定成功后清空。 */
    var bindNotice: String
        get() = sp.getString("bind_notice", "") ?: ""
        set(v) = sp.edit().putString("bind_notice", v).apply()

    /**
     * 只清绑定信息(服务器 / 门店 / 令牌),**保留标定**、采样频率等设置。
     * 令牌失效(被总部撤销、换新手机、门店被删)或店员主动解除绑定时用:标定是对着直播手机摆好摄像头后
     * 的一次性劳动,与门店和令牌无关,重新扫码绑定后应能直接继续采集,不该让人再标定一遍。
     * 同时把 captureWanted 置为 false:重新绑定(甚至换了一家店)后不能自己把摄像头打开,
     * 要店员再点一次"开始采集"。
     */
    fun clearBinding(notice: String) {
        sp.edit()
            .remove("server").remove("store").remove("store_name").remove("token")
            .putBoolean("capture_wanted", false)
            .putString("bind_notice", notice)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()
}
