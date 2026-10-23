package com.luxofilms.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包(ADR-0038):显式开启后,采集服务把**最近 [MAX_FRAMES] 帧**的识别用小图和 ML Kit 的识别细节记在内存里,[ACTIVE_MS] 后自动停止;
 * 使用者在预览页点"导出诊断包"才生成 zip 并调起系统分享。**不自动上传、不写别处**;关闭 / 过期 / 进程结束即清空。
 *
 * 这是本 APP 第一个会把直播画面落盘的功能(此前预览页明确"只放内存,不落盘、不上传"),所以缺省关、限时、仅在导出时才写缓存文件,
 * 关闭或过期时删掉缓存里的 zip。图里是直播间的画面(含观众昵称与弹幕),导出的文件由使用者自行保管。
 */
object Diagnostics {
    const val MAX_FRAMES = 12
    const val ACTIVE_MS = 15 * 60_000L
    private const val JPEG_QUALITY = 90

    @Volatile private var until = 0L
    private val ring = ArrayDeque<DiagFrame>()

    val enabled: Boolean get() = System.currentTimeMillis() < until

    fun remainingMs(): Long = maxOf(0L, until - System.currentTimeMillis())

    fun enable() {
        until = System.currentTimeMillis() + ACTIVE_MS
    }

    /** 关闭:停止记录、清空内存里的帧、删掉缓存里导出过的 zip(ctx 为 null 时只清内存)。 */
    fun disable(ctx: Context?) {
        until = 0L
        synchronized(ring) { ring.clear() }
        ctx?.let { dir(it).listFiles()?.forEach { f -> f.delete() } }
    }

    /** 页面刷新时调用:过期了就当关闭处理。 */
    fun tick(ctx: Context?) {
        if (!enabled && count() > 0) disable(ctx)
    }

    fun count(): Int = synchronized(ring) { ring.size }

    fun record(frame: DiagFrame) {
        if (!enabled) return
        synchronized(ring) {
            ring.addLast(frame)
            while (ring.size > MAX_FRAMES) ring.removeFirst()
        }
    }

    fun jpeg(bmp: Bitmap): ByteArray = ByteArrayOutputStream(64 * 1024).also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    /**
     * 一个词框里的"墨迹颜色":框内亮度最高的约 1/4 像素的平均色(文字比气泡底色亮)。粗略,只用来离线评估
     * 昵称(淡蓝紫)/ 正文(白)/ 平台提示(青)能不能靠颜色区分,**不参与任何实时判断**。返回 `#rrggbb`,框无效返回 null。
     */
    fun sampleInk(bmp: Bitmap, b: Rect?): String? {
        if (b == null) return null
        val l = b.left.coerceIn(0, bmp.width - 1)
        val r = b.right.coerceIn(0, bmp.width)
        val t = b.top.coerceIn(0, bmp.height - 1)
        val bt = b.bottom.coerceIn(0, bmp.height)
        if (r <= l || bt <= t) return null
        val stepX = maxOf(1, (r - l) / 24)
        val stepY = maxOf(1, (bt - t) / 12)
        val px = ArrayList<Int>()
        var y = t
        while (y < bt) {
            var x = l
            while (x < r) { px += bmp.getPixel(x, y); x += stepX }
            y += stepY
        }
        if (px.isEmpty()) return null
        fun lum(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        val top = px.sortedByDescending { lum(it) }.take(maxOf(3, px.size / 4))
        val rr = top.sumOf { Color.red(it) } / top.size
        val gg = top.sumOf { Color.green(it) } / top.size
        val bb = top.sumOf { Color.blue(it) } / top.size
        return String.format("#%02x%02x%02x", rr, gg, bb)
    }

    /** 生成 zip(frames.json + 每帧一张 jpg + README.txt),写到 cacheDir/diag/。没有帧返回 null。 */
    fun exportZip(ctx: Context): File? {
        val frames = synchronized(ring) { ring.toList() }
        if (frames.isEmpty()) return null
        val d = dir(ctx)
        d.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(d, "vision-diag-$stamp.zip")
        val names = frames.mapIndexed { i, f -> if (f.jpeg != null) String.format(Locale.US, "frame_%02d.jpg", i + 1) else null }
        val meta = linkedMapOf(
            "app" to "vision ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "exportedAt" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            "recognizer" to "ML Kit Chinese text recognition (on-device)",
            "note" to "box = [left, top, right, bottom] in pixels of the frame image; ink = brightest-quarter average colour inside the box (rough)",
        )
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("frames.json"))
            zip.write(DiagJson.build(frames, names, meta).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            frames.forEachIndexed { i, f ->
                val name = names[i] ?: return@forEachIndexed
                zip.putNextEntry(ZipEntry(name))
                zip.write(f.jpeg!!)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(README.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "diag").also { it.mkdirs() }

    private const val README = "视觉采集 APP 诊断包\n\n" +
        "frames.json:最近若干帧的识别细节(每个视觉行 / 每个词的文字、框、取样颜色,合并后的弹幕条,拟上传的内容)。\n" +
        "frame_NN.jpg:对应那一帧送去文字识别的图(弹幕区裁剪 + 对比度增强后),与 frames.json 里的框坐标同一坐标系。\n" +
        "图里是直播间画面,含观众昵称和弹幕,请妥善保管,不要公开传播。\n"
}
