package com.luxofilms.vision

/**
 * 最近识别到的几条弹幕,给首页展示用——店员/装机人员靠肉眼核对"摄像头到底认出了什么",
 * 比对着弹幕区域实际内容,能直接判断标定框有没有歪、识别准不准,不用连电脑看后台日志。
 *
 * 只留在内存里(进程重启就清空),不追求持久化:这是实时核对用的,不是历史记录。
 * 0.6.0 起识别与上传分开(ADR-0041):识别线程先以 [State.QUEUED] 记一条并拿到它的 id,上传线程传完 / 放弃后按 id 更新状态。
 * 读写不在同一线程(分析器线程、上传线程、主线程),用 synchronized 简单互斥。
 */
object RecognizedLog {
    /** 一条识别结果的去向。IGNORED:上传成功了,但云端按本店"弹幕来源=协议采集"的设置没有使用它(DanmakuSourceState)。 */
    enum class State { QUEUED, UPLOADED, IGNORED, EXPIRED }

    /** id:进程内唯一、递增。首页重建列表时靠它认出"店员正在看的那一条",新条目插在上面也不会把它顶走。 */
    data class Entry(val id: Long, val ts: Long, val lines: List<String>, val state: State)

    private const val MAX = 20
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var version = 0
    private var nextId = 1L

    /** 记一条;返回它的 id(之后用 [update] 改状态)。lines 为空什么都不记,返回 0(没有这个 id,[update] 对它无效)。 */
    fun add(lines: List<String>, state: State = State.QUEUED): Long {
        if (lines.isEmpty()) return 0L
        synchronized(lock) {
            val id = nextId++
            entries.addFirst(Entry(id, System.currentTimeMillis(), lines, state))
            while (entries.size > MAX) entries.removeLast()
            version++
            return id
        }
    }

    /**
     * 按 id 改状态(上传线程调)。这条已经被挤出 20 条上限 / 被清空、或状态没变,什么都不做(也不动 version,首页不重建)。
     * 一帧的几条被拆进两批(极少见)时,各批的结果先后到达,以最后到的为准。
     */
    fun update(id: Long, state: State) {
        synchronized(lock) {
            val i = entries.indexOfFirst { it.id == id }
            if (i < 0 || entries[i].state == state) return
            entries[i] = entries[i].copy(state = state)
            version++
        }
    }

    /** 清空列表(首页"清空"按钮)。 */
    fun clear() = synchronized(lock) {
        if (entries.isNotEmpty()) { entries.clear(); version++ }
    }

    /** 最新的在前面 */
    fun recent(): List<Entry> = synchronized(lock) { entries.toList() }

    /** 每次内容变化(新增 / 改状态 / 清空)加一。首页据此判断要不要重建列表:
     * 内容没变就不重建,否则每 3 秒一次的刷新会把店员正在翻看的滚动位置重置回顶部。 */
    fun version(): Int = synchronized(lock) { version }
}
