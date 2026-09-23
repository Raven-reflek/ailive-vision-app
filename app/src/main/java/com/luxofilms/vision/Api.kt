package com.luxofilms.vision

import android.os.Build
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 与云服务的 HTTP 交互(全部同步方法,调用方放到后台线程)。
 * 鉴权:请求头 X-Device-Token(云服务按设备 token 解析门店并限定范围)。
 */
object Api {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 上传一帧图片:服务端要调Ark视觉大模型识别,比普通接口慢,读超时放宽 */
    private val slow: OkHttpClient = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    class ApiError(val code: Int, msg: String, val body: JSONObject = JSONObject()) : Exception(msg) {
        val storeDisabled: Boolean get() = body.optBoolean("store_disabled")
        val needBind: Boolean get() = body.optBoolean("need_bind")
    }

    private fun req(path: String, body: JSONObject? = null, server: String = Prefs.server, token: String = Prefs.token): Request {
        val b = Request.Builder().url(server + path)
            .header("User-Agent", "ZhuboVision/${BuildConfig.VERSION_NAME} Android/${Build.VERSION.SDK_INT}")
        if (token.isNotEmpty()) b.header("X-Device-Token", token)
        if (body != null) b.post(body.toString().toRequestBody(JSON))
        return b.build()
    }

    private fun call(r: Request, client: OkHttpClient = http): JSONObject {
        client.newCall(r).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (!resp.isSuccessful) throw ApiError(resp.code, json.optString("error", "HTTP ${resp.code}"), json)
            return json
        }
    }

    /** 扫码绑定:一次性绑定码 → 长效设备 token。二维码内容 {server, store, code} */
    fun bind(server: String, store: String, code: String): JSONObject {
        val body = JSONObject()
            .put("store", store).put("code", code)
            .put("device", JSONObject()
                .put("model", "${Build.BRAND} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("app_code", BuildConfig.VERSION_CODE))
        return call(req("/api/app/bind", body, server, ""))
    }

    /**
     * 上传一帧裁剪好的弹幕区域图片(JPEG字节)。服务端负责调Ark视觉大模型识别、清洗、触发AI回答——
     * 客户端不接触识别结果,只管拍照上传(见方案文档"1.3 识别引擎"一节的安全考虑:Ark的Key不能进APK)。
     */
    fun visionFrame(jpegBytes: ByteArray): JSONObject {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return call(req("/api/vision/frame", JSONObject().put("image_base64", b64)), slow)
    }

    /**
     * 本地OCR兜底通道(2026-09-23):Ark视觉大模型账号权限问题排查未解时,设备本地(ML Kit)识别出的
     * 文字行直接传文字上来,不传图片,不经过Ark。清洗/去重在服务端 vision_ocr.ingest_local_ocr()。
     */
    fun visionText(lines: List<String>): JSONObject {
        val items = JSONArray()
        for (line in lines) items.put(JSONObject().put("nickname", "直播间观众").put("content", line))
        return call(req("/api/vision/text", JSONObject().put("items", items)))
    }
}
