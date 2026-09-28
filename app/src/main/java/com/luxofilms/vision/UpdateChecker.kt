package com.luxofilms.vision

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 应用内更新(内部分发,无商店),照抄门店APP(zhubo)那一份同名类,逻辑完全一致——
 * 2026-09-28 之前这个APP压根没有更新检查,装上就是那个版本,只能手动换包。
 *
 * GET /api/app/version?app=vision → {min_code, latest_code, latest_name, apk_url, notes}。
 * 云服务同时挂着门店APP和这个视觉采集APP,两边版本号各自一份(见 Api.version() 注释),
 * 不能像最初设计那样共用同一个"当前版本"——那样这个APP会拿门店APP的版本号来比,牛头不对马嘴。
 *
 * latest_code > 本机 → 提示;min_code > 本机 → 强制(不可取消)。下载走系统 DownloadManager,
 * 完成后调系统安装器。这个APP目前只有一个 MainActivity(不像门店APP有底部四个tab互相重建),
 * 静默检查放在 onResume 里,同一版本本次进程只弹一次(handledCode),手动点「检查更新」不受限制。
 */
object UpdateChecker {
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var handledCode = 0

    fun check(act: Activity, silent: Boolean) {
        io.execute {
            try {
                val v = Api.version()
                val latest = v.optInt("latest_code", 0)
                val min = v.optInt("min_code", 0)
                val cur = BuildConfig.VERSION_CODE
                val force = min > cur
                act.runOnUiThread {
                    when {
                        latest > cur && (!silent || force || latest != handledCode) -> {
                            if (!force) handledCode = latest
                            prompt(act, v.optString("latest_name", latest.toString()), v.optString("notes", ""), v.optString("apk_url"), force = force)
                        }
                        !silent -> Toast.makeText(act, "已是最新版本 ${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) act.runOnUiThread { Toast.makeText(act, "检查更新失败:${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun prompt(act: Activity, name: String, notes: String, url: String, force: Boolean) {
        if (act.isFinishing || url.isEmpty()) return
        val b = MaterialAlertDialogBuilder(act).setTitle(if (force) "必须更新到 $name" else "有新版本 $name")
            .setMessage(notes.ifEmpty { "修复与改进" }).setCancelable(!force)
            .setPositiveButton("下载安装") { _, _ -> download(act, url, name) }
        if (!force) b.setNegativeButton("稍后", null)
        b.show()
    }

    private fun download(act: Context, url: String, name: String) {
        val ctx = act.applicationContext              // 广播接收器挂在应用上下文:界面被回收也能把安装弹出来
        val abs = if (url.startsWith("http")) url else Prefs.server + url
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // 上次下过同名包(装了一半、取消了)会让 DownloadManager 直接报"文件已存在",先清掉;
        // 文件名带 vision- 前缀,即便哪天两个APP装在同一台设备上(不是本意但也无妨)也不会撞名。
        try { java.io.File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "vision-$name.apk").delete() } catch (_: Exception) {}
        val req = DownloadManager.Request(Uri.parse(abs))
            .setTitle("弹幕采集 $name").setDescription("正在下载更新")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "vision-$name.apk")
            .setMimeType("application/vnd.android.package-archive")
        if (Prefs.token.isNotEmpty()) req.addRequestHeader("X-Device-Token", Prefs.token)
        val id = dm.enqueue(req)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                c.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id) ?: run { Toast.makeText(c, "下载失败", Toast.LENGTH_LONG).show(); return }
                c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        Toast.makeText(ctx, "开始下载,完成后会弹出安装", Toast.LENGTH_SHORT).show()
    }
}
