package com.luxofilms.vision

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.luxofilms.vision.databinding.ActivityBindBinding
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 扫码绑定:总部二维码 → {server, store, code} → POST /api/app/bind → 设备 token。
 * 二维码内容两种形式都认:JSON 文本,或 URL(https://服务器/bind?store=..&code=..)。也可手工输入。
 * 二维码和这台采集设备是同一台时,摄像头没法对着自己屏幕扫——所以另留一个"选图片"入口。
 */
class BindActivity : AppCompatActivity() {

    private lateinit var b: ActivityBindBinding
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { onQr(it) }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { decodeQrFromImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityBindBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnScan.setOnClickListener {
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准总部给的绑定二维码").setBeepEnabled(false).setOrientationLocked(true))
        }
        b.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        b.btnBind.setOnClickListener {
            bind(b.inServer.text.toString().trim(), b.inStore.text.toString().trim(), b.inCode.text.toString().trim())
        }
        if (BuildConfig.DEFAULT_SERVER.isNotEmpty()) b.inServer.setText(BuildConfig.DEFAULT_SERVER)
    }

    private fun decodeQrFromImage(uri: Uri) {
        b.msg.text = "识别中…"
        io.execute {
            val text = try {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("图片打不开")
                val w = bmp.width; val h = bmp.height
                val pixels = IntArray(w * h)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                val source = RGBLuminanceSource(w, h, pixels)
                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (text != null) onQr(text) else b.msg.text = "这张图片里没识别出二维码,换一张试试"
            }
        }
    }

    private fun onQr(text: String) {
        try {
            if (text.startsWith("{")) {
                val j = JSONObject(text)
                bind(j.optString("server"), j.optString("store"), j.optString("code"))
            } else {
                val u = Uri.parse(text)
                val server = (u.scheme ?: "https") + "://" + (u.host ?: "") + (if (u.port > 0) ":" + u.port else "")
                bind(server, u.getQueryParameter("store") ?: "", u.getQueryParameter("code") ?: "")
            }
        } catch (e: Exception) {
            b.msg.text = "二维码内容无法识别:${e.message}"
        }
    }

    private fun bind(server: String, store: String, code: String) {
        if (server.isEmpty() || store.isEmpty() || code.isEmpty()) {
            b.msg.text = "服务器地址、门店 ID、绑定码都不能为空"
            return
        }
        b.msg.text = "绑定中…"
        b.btnBind.isEnabled = false
        io.execute {
            try {
                val r = Api.bind(server.trimEnd('/'), store, code)
                val token = r.optString("token")
                if (token.isEmpty()) throw IllegalStateException(r.optString("error", "服务器未返回 token"))
                Prefs.server = server
                Prefs.storeId = r.optString("store", store)
                Prefs.storeName = r.optString("store_name", "")
                Prefs.token = token
                ui.post {
                    // 绑定完必须先标定(不知道拍哪块区域没法采集),没标定过就直接进标定流程
                    val next = if (Prefs.calibrated) MainActivity::class.java else CameraAlignActivity::class.java
                    startActivity(Intent(this, next))
                    finish()
                }
            } catch (e: Exception) {
                ui.post {
                    b.msg.text = "绑定失败:${e.message}"
                    b.btnBind.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
