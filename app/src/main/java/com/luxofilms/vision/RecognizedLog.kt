package com.luxofilms.vision

/**
 * 最近识别到的几条弹幕,给首页展示用——店员/装机人员靠肉眼核对"摄像头到底认出了什么",
 * 比对着弹幕区域实际内容,能直接判断标定框有没有歪、识别准不准,不用连电脑看后台日志。
 *
 * 只留在内存里(进程重启就清空),不追求持久化:这是实时核对用的,不是历史记录。
 * FrameProcessor 在 CaptureService 的单线程 Analyzer 回调里写,MainActivity 在主线程定时读,
 * 两边不是同一线程,用 synchronized 简单互斥。
 */
object RecognizedLog {
    data class Entry(val ts: Long, val lines: List<String>, val uploaded: Boolean)

    private const val MAX = 20
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun add(lines: List<String>, uploaded: Boolean) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            entries.addFirst(Entry(System.currentTimeMillis(), lines, uploaded))
            while (entries.size > MAX) entries.removeLast()
        }
    }

    /** 最新的在前面 */
    fun recent(): List<Entry> = synchronized(lock) { entries.toList() }
}
