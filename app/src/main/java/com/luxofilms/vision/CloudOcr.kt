package com.luxofilms.vision

import org.json.JSONObject

/**
 * 云端识别(0.7.0,云服务 vision_clean.cloud_ocr):门店开着时,评论区出现新弹幕就把裁好的评论区截图传给云端,
 * 由云端视觉大模型认字(比本机 ML Kit 准得多);本机 ML Kit 只负责"发现评论区有新弹幕",认出的文字一起带上当兜底。
 *
 * 开没开由云端说了算:`/api/vision/text`、`/api/vision/frame`、心跳的响应里带 `cloud_ocr`(true / false)和
 * `cloud_min_interval`(两次传图至少隔几秒)。老云服务没有这两个字段 → 一直是关,行为同 0.6.x。
 */
object CloudOcrState {
    const val DEFAULT_MIN_INTERVAL_MS = 2_000L
    private const val MIN_MS = 1_000L
    private const val MAX_MS = 60_000L

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
        private set

    /** 读一次云端响应:带了 cloud_ocr 字段才改(没带 = 老云服务 / 别的接口,保持原样)。 */
    fun noteResponse(resp: JSONObject) {
        if (!resp.has("cloud_ocr")) return
        enabled = resp.optBoolean("cloud_ocr", false)
        val s = resp.optDouble("cloud_min_interval", Double.NaN)
        if (!s.isNaN()) minIntervalMs = (s * 1000).toLong().coerceIn(MIN_MS, MAX_MS)
    }

    /** 单测 / 解绑后复位。 */
    fun reset() { enabled = false; minIntervalMs = DEFAULT_MIN_INTERVAL_MS }
}

/**
 * 待传的那一张评论区截图(纯逻辑,可单元测试)。同一时刻最多一张:新弹幕不断出现时只留**最新的**截图
 * (它包含了还挂在屏幕上的旧弹幕),本机认出的新行和首页识别列表的 id 累加起来,一并带上 / 一并改状态。
 * 两次发送之间至少隔 minGapMs(云端下发);发出去之前一直在这里等,不另排队。
 */
class FrameSlot(private val clock: () -> Long) {
    /** entries:本机认出的新行 + 它所属的首页识别列表 id(兜底改走文字上传时按 id 分组入队)。 */
    class Job(val jpeg: ByteArray, val entries: List<Pair<LineMerger.Item, Long>>) {
        val items: List<LineMerger.Item> get() = entries.map { it.first }
        val tags: List<Long> get() = entries.map { it.second }.distinct()
    }

    private var jpeg: ByteArray? = null
    private val entries = ArrayList<Pair<LineMerger.Item, Long>>()
    private var lastSentAt = Long.MIN_VALUE / 2

    /** 放进一张新截图(替换还没发的旧截图),本机认出的新行累加。返回因超过 [MAX_ITEMS] 被挤掉的最旧几行。 */
    @Synchronized
    fun offer(image: ByteArray, newItems: List<LineMerger.Item>, tag: Long): List<Pair<LineMerger.Item, Long>> {
        jpeg = image
        newItems.forEach { entries.add(it to tag) }
        val dropped = ArrayList<Pair<LineMerger.Item, Long>>()
        while (entries.size > MAX_ITEMS) dropped.add(entries.removeAt(0))
        return dropped
    }

    /** 到点了就取走(并记下发送时刻),没到点 / 没东西返回 null。 */
    @Synchronized
    fun take(minGapMs: Long): Job? {
        val img = jpeg ?: return null
        val now = clock()
        if (now - lastSentAt < minGapMs) return null
        lastSentAt = now
        return drain(img)
    }

    /** 距离可以发下一张还要等多少毫秒(没东西待发返回 -1)。 */
    @Synchronized
    fun waitMs(minGapMs: Long): Long = if (jpeg == null) -1 else maxOf(0L, lastSentAt + minGapMs - clock())

    /** 清空(采集停止)。返回被丢掉的那一张,调用方撤销去重记账、改首页状态。 */
    @Synchronized
    fun clear(): Job? = jpeg?.let { drain(it) }

    private fun drain(img: ByteArray): Job {
        val job = Job(img, entries.toList())
        jpeg = null
        entries.clear()
        return job
    }

    companion object { const val MAX_ITEMS = 25 }
}

/**
 * 截图发送线程:从 [FrameSlot] 按间隔取一张发给云端;发失败(断网 / 超时 / 429 / 5xx)就把本机认出的那几行交回文字上传通道(兜底,不丢弹幕)。
 * send / fallback / onSent 由调用方注入(测试不出网)。只起一个线程;[stop] 后把没发的那张交给 onDropped。
 */
class CloudFrameSender(
    private val slot: FrameSlot,
    private val send: (FrameSlot.Job) -> JSONObject,
    private val onSent: (FrameSlot.Job, JSONObject) -> Unit,
    private val fallback: (FrameSlot.Job) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val wake = java.util.concurrent.Semaphore(0)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun offer(image: ByteArray, items: List<LineMerger.Item>, tag: Long): List<Pair<LineMerger.Item, Long>> {
        val dropped = slot.offer(image, items, tag)
        wake.release()
        return dropped
    }

    /** 做一轮:到点就发一张,返回下次最多等多少毫秒。测试直接调它,不起线程。 */
    fun step(): Long {
        val gap = CloudOcrState.minIntervalMs
        val w = slot.waitMs(gap)
        if (w != 0L) return if (w < 0) IDLE_MS else w
        val job = slot.take(gap) ?: return 0L
        try {
            onSent(job, send(job))
        } catch (e: Exception) {
            log("截图上传失败,改走文字上传:${e.message}")
            try { fallback(job) } catch (e2: Exception) { log("兜底入队失败:${e2.message}") }
        }
        return 0L
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                val wait = try { step() } catch (e: Throwable) { log("截图发送线程异常:${e.message}"); IDLE_MS }
                if (wait > 0) try { wake.tryAcquire(wait, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { break }
            }
        }, "vision-cloud-frame").apply { isDaemon = true; start() }
    }

    /** 停线程,返回没发出去的那一张(可能为 null)。 */
    @Synchronized
    fun stop(): FrameSlot.Job? {
        running = false
        thread?.interrupt()
        thread = null
        return slot.clear()
    }

    companion object { const val IDLE_MS = 1_000L }
}
