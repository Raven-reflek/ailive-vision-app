package com.luxofilms.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自动拉起采集服务(已绑定门店、已完成标定、且店员没有主动停掉时)。
 *
 * 注意:BOOT_COMPLETED 属于"后台启动",安卓 11 起后台启动的前台服务拿不到摄像头,安卓 14 起甚至会直接抛
 * SecurityException。所以这里不能假设拉起一定成功 —— 失败要给店员一条可见提示(点进 APP 手动开始采集,
 * 前台启动是一定能拿到摄像头的),而不是悄悄没了。服务起来之后摄像头仍然没画面的情况,由 CaptureService
 * 自己的看门狗发现并告警。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(context)
        // 没标定过没法采集(不知道拍哪块区域);店员主动停掉过的不自己再开
        if (!(Prefs.bound && Prefs.calibrated && Prefs.captureWanted)) return
        // 摄像头权限被关了就不用硬拉服务(必然起不来):直接告诉店员去开权限
        if (!CameraPermission.has(context)) {
            Alerts.cameraPermissionLost(context)
            return
        }
        try {
            CaptureService.resume(context)
        } catch (e: Exception) {
            Alerts.bootResumeFailed(context, e.javaClass.simpleName)
        }
    }
}
