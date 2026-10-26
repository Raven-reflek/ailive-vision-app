package com.luxofilms.vision

import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 上传引擎(ADR-0041):把 [UploadQueue] 里的批次交给 [Sender] 发出去,把结果报给 [Listener]。
 * 识别线程只调 [enqueue],**永远不等网络**;发送在一个专用的上传线程里(不是 CameraX 的分析器线程,也不是心跳线程),
 * 所以网络卡住、被限流、整批失败重试,都不会让识别少处理一帧。
 *
 * 分两层,和 Heartbeat / HeartbeatSchedule 一样便于单测:
 * - **核心** [step]:一轮只做一件事(报过期 → 取一批 → 发一次 → 处理结果),不碰线程,时钟与 Sender 都可注入,
 *   单元测试用假时钟 + 假 Sender 一步步推;
 * - **线程壳** [start] / [stop]:上传线程循环调 [step],空闲时睡到有新条目或到点(信号量,入队时唤醒)。
 *
 * 结果怎么处理(契约 04):
 * - [SendResult.Ok]:这一批出队、退避清零,报 [Listener.uploaded]。200 一律算成功——含 `replayed` / `inflight`(云端已有这个 batch_id,
 *   重放当时的结果,对手机就是"这批已经传到了");
 * - [SendResult.RateLimited]:这一批留着,等 `Retry-After` 再发,**只延后上传**,识别照常;
 * - [SendResult.Failed]:断网 / 超时 / 5xx / `403 store_disabled`……留着,按 2 / 4 / 8 / 15 秒退避、沿用同一个 batch_id 重发,直到条目过期([Listener.expired]);
 *   `store_disabled` **不解绑**,和其它失败一样退避(首页的停用提示来自 /api/me 探测);
 * - [SendResult.TokenInvalid]:令牌已作废(401/410 + need_bind),重试没有意义:引擎关闭、清空队列、**只**调一次 [Listener.tokenInvalid]。
 *
 * 回调线程:[Listener.dropped](入队时挤掉最旧的)在调 [enqueue] 的线程;其余在上传线程。回调里抛异常会被接住记日志,不影响引擎。
 */
class UploadEngine(
    private val sender: Sender,
    private val listener: Listener,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    newBatchId: () -> String = { UUID.randomUUID().toString() },
    private val log: (String) -> Unit = {},
) {
    /** 一次发送的结果,由 [Sender] 归好类(HTTP 状态码、错误体的判读在 [ApiUploadSender],这里不认识 HTTP)。 */
    sealed class SendResult {
        /** 200(含 replayed / inflight)。ignored:本店弹幕来源为协议采集,云端没使用这些弹幕(上传本身是通的)。 */
        data class Ok(val ignored: Boolean = false, val danmakuSource: String? = null) : SendResult()

        /** 429。retryAfterSec:`Retry-After` 响应头 / 响应体 `retry_after` 的秒数,没有为 null。 */
        data class RateLimited(val retryAfterSec: Int? = null) : SendResult()

        /** 401 / 410 + need_bind:令牌作废。 */
        object TokenInvalid : SendResult()

        /** 其余一切失败。httpCode:有就带上(统计用),网络错误 / 超时为 null。 */
        data class Failed(val httpCode: Int? = null) : SendResult()
    }

    /** 真正发请求的一方;在上传线程里被同步调用,可以阻塞(整个调用自带 15 秒总超时)。抛出的异常按 `Failed()` 处理。 */
    fun interface Sender {
        fun send(lines: List<String>, batchId: String): SendResult
    }

    interface Listener {
        /** 这一批传成功了(含 replayed)。ignored / danmakuSource:云端按本店弹幕来源设置没使用它们。 */
        fun uploaded(entries: List<UploadQueue.Entry>, ignored: Boolean, danmakuSource: String?)

        /** 条目排了 [UploadQueue.TTL_MS] 还没传出去,已丢弃。调用方要撤销它们在去重器里的记账。 */
        fun expired(entries: List<UploadQueue.Entry>)

        /** 队列满了,最旧的几条被挤掉(或队列已关闭,新入队的被拒收)。调用方的处理同 [expired]。 */
        fun dropped(entries: List<UploadQueue.Entry>)

        /** 令牌作废。cleared:被清掉的全部条目。整个引擎生命周期里最多调一次。 */
        fun tokenInvalid(cleared: List<UploadQueue.Entry>)
    }

    private val queue = UploadQueue(clock, newBatchId)
    @Volatile private var closed = false
    private val wake = Semaphore(0)
    private var thread: Thread? = null

    /** 队列里还有几条(排队 + 等重试的那一批)。单测与诊断用。 */
    val queued: Int get() = queue.size

    /** 入队(识别线程调,不阻塞、不等网络)。tag 原样挂在条目上,回调时带回来。 */
    fun enqueue(items: List<LineMerger.Item>, tag: Long = 0L) {
        if (items.isEmpty()) return
        val dropped = queue.enqueue(items, tag)
        wake.release()
        if (dropped.isNotEmpty()) safely("dropped") { listener.dropped(dropped) }
    }

    /**
     * 做一轮,返回距离下一次该调用 [step] 还要等多少毫秒([IDLE] = 队列空,等新条目)。
     * 只能从一个线程调(生产里是上传线程,测试里是测试线程)。
     */
    fun step(): Long {
        if (closed) return IDLE
        val gone = queue.expire()
        if (gone.isNotEmpty()) safely("expired") { listener.expired(gone) }
        val batch = queue.take() ?: return waitOrIdle()
        val result = try {
            sender.send(batch.lines, batch.id)
        } catch (e: Exception) {
            log("上传抛异常,按失败处理:${e.message}")
            SendResult.Failed()
        }
        if (closed) return IDLE        // 发送期间服务停了:这一批已随队列清掉,结果作废
        when (result) {
            is SendResult.Ok -> {
                queue.ack(batch)
                safely("uploaded") { listener.uploaded(batch.entries, result.ignored, result.danmakuSource) }
            }
            is SendResult.RateLimited -> queue.rateLimited(batch, result.retryAfterSec)
            is SendResult.Failed -> queue.fail(batch)
            SendResult.TokenInvalid -> {
                closed = true
                val cleared = queue.close()
                safely("tokenInvalid") { listener.tokenInvalid(cleared) }
                return IDLE
            }
        }
        return waitOrIdle()
    }

    private fun waitOrIdle(): Long = queue.nextWakeMs() ?: IDLE

    /** 起上传线程(幂等;已 [stop] 的引擎不能再起,重新采集时新建一个)。 */
    @Synchronized
    fun start() {
        if (thread != null || closed) return
        thread = Thread({ loop() }, "vision-upload").apply { isDaemon = true; start() }
    }

    /**
     * 停止并丢掉队列里的全部内容(采集服务销毁时;队列只在内存里,停了就不留)。返回被丢掉的条目,
     * 调用方照 [Listener.expired] 的方式善后。发送中的请求让它自己结束(最多 15 秒),结果不再处理。幂等。
     */
    fun stop(): List<UploadQueue.Entry> {
        closed = true
        val left = queue.close()
        thread?.interrupt()
        wake.release()
        return left
    }

    private fun loop() {
        while (!closed) {
            val waitMs = try {
                step()
            } catch (e: Throwable) {
                log("上传线程这一轮出错,1 秒后再试:${e.message}")
                CRASH_RETRY_MS
            }
            if (closed) break
            try {
                wake.tryAcquire(minOf(waitMs, MAX_IDLE_MS), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            wake.drainPermits()
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { log("上传回调 $what 抛异常:${e.message}") }
    }

    companion object {
        /** [step] 的返回值:没有要等的事,等新条目入队。 */
        const val IDLE = Long.MAX_VALUE

        /** 线程壳最久睡这么久就醒一次看看(兜底,正常靠入队的信号与 step 给的等待时间)。 */
        private const val MAX_IDLE_MS = 30_000L
        private const val CRASH_RETRY_MS = 1_000L
    }
}
