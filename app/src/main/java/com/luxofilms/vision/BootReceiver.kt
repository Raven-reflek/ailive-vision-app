package com.luxofilms.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后自动拉起采集服务(已绑定门店且已完成标定时)。BOOT_COMPLETED 是系统允许后台启动前台服务的例外之一。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(context)
        // 没标定过没法采集(不知道拍哪块区域),开机自启只在"绑定+标定都完成"时才有意义
        if (Prefs.bound && Prefs.calibrated) CaptureService.start(context)
    }
}
