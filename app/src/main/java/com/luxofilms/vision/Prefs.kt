package com.luxofilms.vision

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 设备绑定信息 + 标定数据。token 加密存储(EncryptedSharedPreferences)。 */
object Prefs {
    private lateinit var sp: SharedPreferences

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

    /** 采样频率(fps),真机联调后按云端识别的延迟/成本校准,默认先给一个保守值 */
    var sampleFps: Float
        get() = sp.getFloat("sample_fps", 1.0f)
        set(v) = sp.edit().putFloat("sample_fps", v).apply()

    fun clear() = sp.edit().clear().apply()
}
