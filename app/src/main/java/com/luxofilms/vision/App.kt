package com.luxofilms.vision

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, "弹幕采集运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "摄像头采集常驻通知:显示标定与上报状态"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "弹幕采集告警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "画面疑似被移动、长时间无新弹幕等需要店员处理的情况"
                enableVibration(true)
            }
        )
        Prefs.init(this)
    }

    companion object {
        const val CH_CAPTURE = "capture"
        const val CH_ALERT = "alert"
    }
}
