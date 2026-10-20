package com.luxofilms.vision

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * 摄像头权限:检查、被拒后的引导、采集起不来时的告警文案。
 *
 * 为什么要单独管:这个 APP 的核心就是摄像头。以前点"开始采集"前不查权限,权限被关掉(或装机后从没授权过)
 * 时前台服务直接起不来,店员收到的告警却写"手机重启后没能自动恢复",原因对不上;标定页被拒后只弹一下就退出,
 * 选了"不再询问"之后更是再也点不出授权窗口,也没有任何指引去系统设置。
 */
object CameraPermission {

    /** 授权窗口返回之后的三种结果。 */
    enum class Outcome { GRANTED, DENIED_CAN_ASK_AGAIN, DENIED_PERMANENT }

    /** 前台服务起不来的两类原因(决定告警怎么写)。 */
    enum class StartFailure { CAMERA_PERMISSION, BACKGROUND_RESTRICTED }

    /**
     * 授权窗口返回后判断结果。拒绝之后 shouldShowRequestPermissionRationale 仍为 false,
     * 说明系统不会再弹授权窗口了(选了"不再询问",或第二次拒绝,或被策略禁用),只能去系统设置里开。
     */
    fun outcome(granted: Boolean, shouldShowRationale: Boolean): Outcome = when {
        granted -> Outcome.GRANTED
        shouldShowRationale -> Outcome.DENIED_CAN_ASK_AGAIN
        else -> Outcome.DENIED_PERMANENT
    }

    /** 前台服务起不来时:没有摄像头权限就是权限的问题,否则按"后台启动被系统限制"处理(开机自启、升级后自启)。 */
    fun startFailure(cameraGranted: Boolean): StartFailure =
        if (cameraGranted) StartFailure.BACKGROUND_RESTRICTED else StartFailure.CAMERA_PERMISSION

    fun has(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 打开本应用的系统设置页(店员在那里把"相机"改成允许)。 */
    fun openSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ── 文案(纯字符串,单元测试直接核对)──────────────────────────────

    const val DIALOG_TITLE = "需要摄像头权限"

    fun dialogMessage(outcome: Outcome): String = when (outcome) {
        Outcome.DENIED_PERMANENT ->
            "摄像头权限已被关闭(或之前选了“不再询问”),系统不会再弹出授权窗口。" +
                "请到系统设置里把“相机”改成允许,回到本应用后会自动继续。"
        else ->
            "弹幕采集要用摄像头拍摄直播手机的屏幕,没有摄像头权限就无法采集。" +
                "授权时请选“使用应用时允许”,选“仅这一次”可能让采集中途停止。"
    }

    fun dialogPositive(outcome: Outcome): String = if (outcome == Outcome.DENIED_PERMANENT) "去设置" else "重新授权"

    /** 告警(通知)内容:标题 + 正文。 */
    fun alertContent(failure: StartFailure, reason: String?): Pair<String, String> = when (failure) {
        StartFailure.CAMERA_PERMISSION ->
            "弹幕采集已停止:摄像头权限被关闭" to
                "点这里打开 APP,点“开始采集”,按提示到系统设置里把“相机”改成允许。"
        StartFailure.BACKGROUND_RESTRICTED ->
            "手机重启后弹幕采集没能自动恢复" to
                ("点这里打开 APP,再点“开始采集”。" + (reason?.let { "($it)" } ?: ""))
    }

    /**
     * 授权被拒后的引导弹窗(首页"开始采集"和标定页共用,保证说法一致)。
     * 不可点外面取消:必须选一个出路,免得标定页被拒后店员面对一个黑屏不知道怎么办。
     */
    fun showDenied(
        ctx: Context,
        outcome: Outcome,
        onRetry: () -> Unit,
        onGoSettings: () -> Unit,
        onCancel: () -> Unit,
    ) {
        AlertDialog.Builder(ctx)
            .setTitle(DIALOG_TITLE)
            .setMessage(dialogMessage(outcome))
            .setCancelable(false)
            .setPositiveButton(dialogPositive(outcome)) { _, _ ->
                if (outcome == Outcome.DENIED_PERMANENT) onGoSettings() else onRetry()
            }
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .show()
    }
}
