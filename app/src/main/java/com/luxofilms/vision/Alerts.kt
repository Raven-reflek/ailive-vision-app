package com.luxofilms.vision

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 需要店员处理的告警(开机没能自动恢复采集、摄像头长时间没有画面……)。
 * 走高优先级的 CH_ALERT 渠道,点一下直接进首页。这个 APP 本来就是无人值守的后台服务,
 * "出了问题没人知道"是最坏的情况,所以任何失败路径都应该留一条这样的可见提示,而不是静默 stopSelf()。
 */
object Alerts {
    private const val ID_BOOT = 2101
    private const val ID_CAMERA = 2102

    fun bootResumeFailed(ctx: Context, reason: String?) =
        post(ctx, ID_BOOT, "手机重启后弹幕采集没能自动恢复", "点这里打开 APP,再点“开始采集”。" + (reason?.let { "($it)" } ?: ""))

    fun cameraStalled(ctx: Context, detail: String) =
        post(ctx, ID_CAMERA, "弹幕采集:摄像头没有画面", detail)

    fun clearCamera(ctx: Context) = runCatching { NotificationManagerCompat.from(ctx).cancel(ID_CAMERA) }

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, App.CH_ALERT)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(open).build()
        // 没有通知权限(安卓13+用户拒绝)时 notify 会静默失败或抛 SecurityException:告警发不出去也不能把服务带崩
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
