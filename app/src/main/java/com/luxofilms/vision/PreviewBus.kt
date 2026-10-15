package com.luxofilms.vision

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger

/**
 * "采集预览"页和采集服务之间的进程内信箱。
 *
 * 预览页**不自己打开摄像头**:CameraX 的 ProcessCameraProvider 是进程级共享的,再绑一次会和 CaptureService
 * 互相抢,采集会悄悄断掉(标定页的 unbindAll 就出过这个问题)。预览页只读采集服务已经处理出来的图:
 * 页面打开时 active=true,服务才会额外缩小并发布一份;关闭后停止发布并清掉,没有额外开销。
 *
 * 画面只放在内存里:不落盘、不上传。预览页另外开了 FLAG_SECURE 禁止截屏/录屏。
 */
object PreviewBus {

    /** 一组预览图(已缩小):raw=摄像头原画面,上面叠了屏幕框(橙)和弹幕区框(蓝),可直接显示;
     * crop=实际送去 OCR 的那一块,**只有预览页展开了"识别用的画面"(cropWanted)才会生成**,否则为 null。 */
    class Snapshot(val raw: Bitmap, val crop: Bitmap?, val ts: Long)

    @Volatile var active: Boolean = false
        private set
    /** 预览页是否展开了"识别用的画面"。没展开就不做摆正/裁剪/增强这一整套,预览帧更轻。 */
    @Volatile var cropWanted: Boolean = false
        private set
    @Volatile private var latest: Snapshot? = null
    private val version = AtomicInteger(0)
    @Volatile private var lines: List<String> = emptyList()
    @Volatile private var linesAt: Long = 0L

    /** 预览页打开 / 关闭。关闭时清掉所有数据,既释放内存,也避免下次打开先闪出旧画面。 */
    fun setActive(v: Boolean) {
        active = v
        if (!v) { cropWanted = false; clear() }
    }

    fun setCropWanted(v: Boolean) {
        cropWanted = v
    }

    fun publish(s: Snapshot) {
        latest = s
        version.incrementAndGet()
    }

    fun latest(): Snapshot? = latest

    /** 每次发布 / 清空加一,页面据此判断要不要刷新图片。 */
    fun version(): Int = version.get()

    /** 最近一次 OCR 识别出的文字(识别频率低于预览频率,所以单独存,页面一起显示)。 */
    fun noteLines(l: List<String>, at: Long) {
        lines = l
        linesAt = at
    }

    fun lines(): List<String> = lines
    fun linesAt(): Long = linesAt

    fun clear() {
        latest = null
        lines = emptyList()
        linesAt = 0L
        version.incrementAndGet()
    }

    /** 缩小到不超过 maxW 宽,保持比例;本来就不宽的不放大。 */
    fun scaledSize(w: Int, h: Int, maxW: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0 || maxW <= 0) return Pair(1, 1)
        if (w <= maxW) return Pair(w, h)
        return Pair(maxW, maxOf(1, Math.round(h.toFloat() * maxW / w)))
    }
}
