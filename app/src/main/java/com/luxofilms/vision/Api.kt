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

    /** 应用内更新检查:{min_code, latest_code, latest_name, apk_url, notes}。
     * ?app=vision 区分是这个 APP 在查——云服务同时挂着门店APP(缺省 zhubo)和这个视觉采集APP,
     * 两边版本号完全独立,不传 app 参数会被当成在查门店APP的版本(见云服务 devices.py 的迁移注释)。 */
    fun version(): JSONObject = call(req("/api/app/version?app=vision"))

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
     *
     * 不传 nickname:本地OCR压根认不出弹幕昵称(小字号+彩色+打码,2026-09-23离线测试就证实过),
     * 与其自己编一个固定假昵称,不如让服务端按"没昵称"处理——vision_ocr._clean_items() 会兜底填
     * 一个能被识别成匿名观众的占位符(2026-09-28 改的;之前客户端固定写死"直播间观众",这个词不在
     * 匿名名单里,会被当成一个真实昵称,导致每句回答都以同一句"直播间观众老板,"开头)。
     */
    fun visionText(lines: List<String>): JSONObject {
        val items = JSONArray()
        for (line in lines) items.put(JSONObject().put("content", line))
        return call(req("/api/vision/text", JSONObject().put("items", items)))
    }
}
