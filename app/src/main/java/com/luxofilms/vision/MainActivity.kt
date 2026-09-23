package com.luxofilms.vision

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.luxofilms.vision.databinding.ActivityMainBinding

/** 首页:绑定/标定/采集状态一览,不需要一直盯着看(采集是后台服务),主要给店员排查问题用。 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private var capturing = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (!Prefs.bound) {
            startActivity(Intent(this, BindActivity::class.java))
            finish()
            return
        }

        b.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CameraAlignActivity::class.java))
        }
        b.btnToggleCapture.setOnClickListener {
            if (!Prefs.calibrated) {
                startActivity(Intent(this, CameraAlignActivity::class.java))
                return@setOnClickListener
            }
            if (capturing) {
                CaptureService.stop(this)
            } else {
                CaptureService.start(this)
            }
            capturing = !capturing
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        b.storeInfo.text = "门店:${Prefs.storeName.ifEmpty { Prefs.storeId }} · ${Prefs.server}"

        val cal = CalibrationStore.load()
        b.statusCalibration.text = if (cal != null) {
            val ago = (System.currentTimeMillis() - cal.calibratedAt) / 60000
            "标定状态:已完成(${ago}分钟前)"
        } else {
            "标定状态:尚未标定"
        }

        b.statusCapture.text = if (capturing) "采集状态:运行中" else "采集状态:已停止"
        b.btnToggleCapture.text = if (capturing) "停止采集" else "开始采集"
    }
}
