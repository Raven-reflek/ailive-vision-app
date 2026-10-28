package com.luxofilms.vision

import java.util.UUID

/**
 * 上传队列(契约 04「手机端上传队列」,ADR-0041):识别线程只把弹幕**入队**,由独立的上传线程([UploadEngine])按先后一批一批取走。
 * 以前识别线程同步上传(最坏 30 秒,期间一帧都不处理;收到 429 还停 65 秒;失败的只靠"下一帧它还在屏幕上"才重试,滚出屏幕就永久丢了),
 * 现在识别与上传互不等待,失败的批次按退避重试到条目过期为止。
 *
 * 这里只放**纯逻辑**(不碰 Android 类型,可单元测试):先进先出、批次冻结、过期 / 溢出、退避与限流等待。**只在内存里**——
 * 采集服务停止 / 进程被杀即清空(弹幕过期很快,不落盘)。时钟(单调毫秒)与批次号由构造参数注入,测试用假的。
 *
 * - 条目最多留 [TTL_MS](从入队算),过期就丢;队列最多 [CAPACITY] 条,满了丢**最旧**的(最新的弹幕比积压的旧弹幕更值得回答)。
 * - 一批最多 [MAX_BATCH] 条,可以合并几帧的内容;同一时刻只有一批在途;两次请求**开始**之间至少隔 [MIN_GAP_MS]。
 * - 取走的批次被**冻结**:带着自己的 batch_id,重试时原样再发(同一个 id,云端据此幂等)。只有过期 / 溢出让它少了几条(组成变了)才换新 id。
 *   在途的批次(请求已经发出去了)不会被过期 / 溢出动它,等结果回来再说。
 * - 失败(断网 / 超时 / 5xx / 403 store_disabled)按 [BACKOFF_MS] 退避,一次成功清零;429 只按 `Retry-After` 等
 *   (夹在 [RETRY_AFTER_MIN_S]~[RETRY_AFTER_MAX_S] 秒,没给就 [RETRY_AFTER_DEFAULT_S] 秒),不算失败、不动退避的档位。
 *
 * 线程:识别线程调 [enqueue],上传线程调 [expire] / [take] / [ack] / [fail] / [rateLimited],店员停止采集时主线程调 [close];方法都加了锁。
 * 给调用方的回调(报过期、报丢弃)由 [UploadEngine] 在锁外做,这里只返回"哪些条目被拿掉了"。
 */
class UploadQueue(
    private val clock: () -> Long,
    private val newBatchId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * 队里的一条。item:去重器认得的原条目(过期 / 被丢时要交给 [DanmakuDeduper.forget] 撤销记账);
     * tag:入队的调用方挂的标记(首页识别列表条目的 id),队列不解释它。
     */
    class Entry(val item: LineMerger.Item, val tag: Long, val enqueuedAt: Long) {
        val content: String get() = item.content
    }

    /** 冻结的一批:id 一经取走就不变(重试沿用),条目先进先出。 */
    class Batch(val id: String, val entries: List<Entry>) {
        val lines: List<String> get() = entries.map { it.content }
        /** 与 [lines] 一一对应的昵称(读不准的是 null),2026-10-28 起随内容一起上传。 */
        val nicks: List<String?> get() = entries.map { it.item.nick }
    }

    private val pending = ArrayDeque<Entry>()
    private var frozen: Batch? = null          // 已取走、还没传成功的那一批(在途,或在等退避后重发)
    private var inFlight = false
    private var closed = false
    private var nextSendAt = Long.MIN_VALUE     // 最早什么时候可以开始下一次请求(最小间隔 / 退避 / 限流等待的合并)
    private var failStreak = 0                  // 连续失败次数,决定退避的档位

    /** 队里现有的条数(含冻结的那一批)。 */
    val size: Int
        @Synchronized get() = (frozen?.entries?.size ?: 0) + pending.size

    /**
     * 入队(识别线程)。返回**被丢掉**的条目:队列满了挤出的最旧几条;队列已 [close] 时是传进来的全部。
     * 满了先从冻结批的队首丢(它最旧),在途的那一批不动,从待发队列的队首丢。
     */
    @Synchronized
    fun enqueue(items: List<LineMerger.Item>, tag: Long = 0L, bornAt: Long? = null): List<Entry> {
        if (items.isEmpty()) return emptyList()
        val now = clock()
        // bornAt:这几行最早认出来的时刻(截图通道失败转过来的,已经等过一阵),过期按它算,不从入队重新计时
        val added = items.map { Entry(it, tag, minOf(bornAt ?: now, now)) }
        if (closed) return added
        pending.addAll(added)
        var excess = size - CAPACITY
        if (excess <= 0) return emptyList()
        val dropped = ArrayList<Entry>(excess)
        val f = frozen
        if (f != null && !inFlight) {
            val cut = minOf(excess, f.entries.size)
            dropped.addAll(f.entries.subList(0, cut))
            excess -= cut
            refreeze(f.entries.subList(cut, f.entries.size))
        }
        while (excess > 0 && pending.isNotEmpty()) { dropped.add(pending.removeFirst()); excess-- }
        return dropped
    }

    /** 把超过 [TTL_MS] 的条目拿掉并返回(上传线程每轮先调)。在途的批次不动;冻结批少了几条就换新 id。 */
    @Synchronized
    fun expire(): List<Entry> {
        val now = clock()
        val gone = ArrayList<Entry>()
        val f = frozen
        if (f != null && !inFlight) {
            val (dead, alive) = f.entries.partition { now - it.enqueuedAt >= TTL_MS }
            if (dead.isNotEmpty()) { gone.addAll(dead); refreeze(alive) }
        }
        while (pending.isNotEmpty() && now - pending.first().enqueuedAt >= TTL_MS) gone.add(pending.removeFirst())
        return gone
    }

    /**
     * 取下一批去发。现在不该发(有请求在途、还在等退避 / 最小间隔、队列空、已关闭)返回 null。
     * 有冻结批(上次没传成功)就原样再发,沿用它的 id;否则从待发队列队首取最多 [MAX_BATCH] 条冻结成新的一批。
     * 取走即把它标成在途,调用方必须随后调 [ack] / [fail] / [rateLimited] 之一。
     */
    @Synchronized
    fun take(): Batch? {
        if (closed || inFlight) return null
        val now = clock()
        if (now < nextSendAt) return null
        var batch = frozen
        if (batch == null) {
            if (pending.isEmpty()) return null
            val entries = ArrayList<Entry>(MAX_BATCH)
            while (entries.size < MAX_BATCH && pending.isNotEmpty()) entries.add(pending.removeFirst())
            batch = Batch(newBatchId(), entries)
            frozen = batch
        }
        inFlight = true
        nextSendAt = now + MIN_GAP_MS
        return batch
    }

    /** 这一批传成功了:从队里拿掉,退避清零。队列已被 [close] 清过(batch 不再是冻结批)则忽略。 */
    @Synchronized
    fun ack(batch: Batch) {
        if (frozen !== batch) return
        frozen = null
        inFlight = false
        failStreak = 0
    }

    /** 这一批失败了(断网 / 超时 / 5xx / 403 store_disabled……):留着,按 2 / 4 / 8 / 15 / 15… 秒退避后原样重发。 */
    @Synchronized
    fun fail(batch: Batch) {
        if (frozen !== batch) return
        inFlight = false
        failStreak++
        nextSendAt = maxOf(nextSendAt, clock() + BACKOFF_MS[minOf(failStreak, BACKOFF_MS.size) - 1])
    }

    /** 云端 429:留着这一批,等 `Retry-After`(没给用缺省)再发;不算失败,不动退避档位。 */
    @Synchronized
    fun rateLimited(batch: Batch, retryAfterSec: Int?) {
        if (frozen !== batch) return
        inFlight = false
        val sec = (retryAfterSec ?: RETRY_AFTER_DEFAULT_S).coerceIn(RETRY_AFTER_MIN_S, RETRY_AFTER_MAX_S)
        nextSendAt = maxOf(nextSendAt, clock() + sec * 1000L)
    }

    /** 关闭并清空(令牌失效 / 采集服务停止):返回被清掉的全部条目;之后 [enqueue] 一律拒收。重复调用无害。 */
    @Synchronized
    fun close(): List<Entry> {
        closed = true
        val all = (frozen?.entries ?: emptyList()) + pending
        frozen = null
        pending.clear()
        inFlight = false
        return all
    }

    /**
     * 距离下次有事可做还有多少毫秒:取"能发了"与"最旧的条目到期"里更早的那个;队列空返回 null。
     * 退避期间也要在条目到期那一刻醒来,及时把它报成过期,不让首页的"排队中"挂过 [TTL_MS]。
     */
    @Synchronized
    fun nextWakeMs(): Long? {
        val oldest = frozen?.entries?.firstOrNull() ?: pending.firstOrNull() ?: return null
        val now = clock()
        val expireIn = maxOf(0L, oldest.enqueuedAt + TTL_MS - now)
        val sendIn = if (now >= nextSendAt) 0L else nextSendAt - now
        return minOf(sendIn, expireIn)
    }

    /** 冻结批的组成变了(过期 / 溢出拿掉了几条):剩下的换一个新 id;一条不剩就没有冻结批了。 */
    private fun refreeze(rest: List<Entry>) {
        frozen = if (rest.isEmpty()) null else Batch(newBatchId(), rest.toList())
    }

    companion object {
        /** 条目从入队起最多保留这么久,过期就丢(再答反而突兀,云端收件箱也是 20 秒的时效)。 */
        const val TTL_MS = 45_000L

        /** 队列最多这么多条,满了丢最旧的。 */
        const val CAPACITY = 200

        /** 一批最多这么多条(云端一次最多收 25 条,超出静默截断,契约 04)。 */
        const val MAX_BATCH = 25

        /** 两次请求开始之间至少隔这么久(云端限流每设备 40 次 / 60 秒)。 */
        const val MIN_GAP_MS = 1_000L

        /** 失败后的退避:第 1、2、3 次失败等 2、4、8 秒,之后一直 15 秒;一次成功就回到第一档。 */
        val BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)

        /** 429 的等待:按 Retry-After 秒数,夹在这个范围;没给就用缺省。 */
        const val RETRY_AFTER_MIN_S = 2
        const val RETRY_AFTER_MAX_S = 65
        const val RETRY_AFTER_DEFAULT_S = 20
    }
}
