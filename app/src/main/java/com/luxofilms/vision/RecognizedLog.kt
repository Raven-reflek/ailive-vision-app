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
    /** id:进程内唯一、递增。首页重建列表时靠它认出"店员正在看的那一条",新条目插在上面也不会把它顶走。 */
    data class Entry(val id: Long, val ts: Long, val lines: List<String>, val uploaded: Boolean)

    private const val MAX = 20
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var version = 0
    private var nextId = 1L

    fun add(lines: List<String>, uploaded: Boolean) {
        if (lines.isEmpty()) return
        synchronized(lock) {
            entries.addFirst(Entry(nextId++, System.currentTimeMillis(), lines, uploaded))
            while (entries.size > MAX) entries.removeLast()
            version++
        }
    }

    /** 清空列表(首页"清空"按钮)。 */
    fun clear() = synchronized(lock) {
        if (entries.isNotEmpty()) { entries.clear(); version++ }
    }

    /** 最新的在前面 */
    fun recent(): List<Entry> = synchronized(lock) { entries.toList() }

    /** 每次内容变化(新增 / 清空)加一。首页据此判断要不要重建列表:
     * 内容没变就不重建,否则每 3 秒一次的刷新会把店员正在翻看的滚动位置重置回顶部。 */
    fun version(): Int = synchronized(lock) { version }
}
