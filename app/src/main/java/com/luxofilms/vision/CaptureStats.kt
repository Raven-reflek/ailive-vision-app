package com.luxofilms.vision

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 采集运行状况的进程级计数,供心跳(契约 04「视觉采集心跳」,见 [Heartbeat])上报。
 *
 * 背景:手机只在有新弹幕时才上传,云端分不清"直播间安静"和"采集手机出事了"。心跳要说的就是后面这几件事:
 * 摄像头还在出帧吗、OCR 还在跑吗、最近识别出过文字吗、上传是不是一直失败。这里只记"某件事上一次发生在什么时候"
 * (相对时间,不依赖手机时钟,也不记任何图片 / 文字内容)。
 *
 * 线程:分析器线程(CaptureService 的 io 线程)写,心跳线程读。每个量都是独立的 @Volatile / 原子量,
 * 读到的是"各自最新"的值,不要求跨字段严格一致——心跳每分钟一次,差几毫秒无所谓。
 *
 * 时钟用单调时钟(SystemClock.elapsedRealtime):店员对时、手机时区 / 自动校时都不会让"N 秒前"跳变或变成负数。
 * 单元测试通过 [clock] 注入假时钟。纯 Kotlin 逻辑,可单元测试。
 */
object CaptureStats {
    /** 当前时刻(毫秒,单调)。仅供单元测试替换。 */
    @Volatile internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** "从未发生"的哨兵(假时钟也可以从 0 起,所以不能用 0 / -1)。 */
    private const val NEVER = Long.MIN_VALUE

    @Volatile private var startedAt = NEVER
    @Volatile private var lastFrameAt = NEVER
    @Volatile private var lastOcrAt = NEVER
    @Volatile private var lastTextAt = NEVER
    @Volatile private var lastUploadOkAt = NEVER
    private val failStreak = AtomicInteger(0)

    /** 某一时刻的快照:各项都是"距今整秒数",从未发生为 null。 */
    data class Snapshot(
        val frameAgo: Int?,
        val ocrAgo: Int?,
        val textAgo: Int?,
        val uploadOkAgo: Int?,
        val uploadFailStreak: Int,
        /** 采集服务已运行的秒数;没有 reset 过(服务没起来)为 null */
        val uptime: Int?,
    )

    /** 采集服务(重新)启动时清零,并开始计 uptime。服务重新标定、重绑摄像头不算重启,不要在那里调。 */
    fun reset() {
        startedAt = clock()
        lastFrameAt = NEVER
        lastOcrAt = NEVER
        lastTextAt = NEVER
        lastUploadOkAt = NEVER
        failStreak.set(0)
    }

    /** 摄像头出了一帧(不管后面是否丢帧)。 */
    fun noteFrame() { lastFrameAt = clock() }

    /** 完成了一次识别(ML Kit 返回了结果;超时 / 抛异常的不算)。 */
    fun noteOcrDone() { lastOcrAt = clock() }

    /** 识别出了至少一行文字——**原始**识别结果,哪怕后面被合并、去重、丢弃也算(要看的是"眼前有没有字")。 */
    fun noteText() { lastTextAt = clock() }

    /** 一次上传被服务端以 200 回应(含 `ignored:"source"`:没被使用,但上传本身是通的)。连续失败次数清零。 */
    fun noteUploadOk() {
        lastUploadOkAt = clock()
        failStreak.set(0)
    }

    /**
     * 一次上传失败(网络错误、超时、5xx、令牌 / 门店问题等)。有 HTTP 状态码就带上:**429 限流既不算成功也不算失败**
     * (那是手机自己发得太快被服务端挡了,不代表网络或云端有问题),这里直接忽略,不增加连续失败次数。
     */
    fun noteUploadFailed(httpCode: Int? = null) {
        if (httpCode == 429) return
        failStreak.incrementAndGet()
    }

    fun snapshot(now: Long = clock()): Snapshot = Snapshot(
        frameAgo = ageSeconds(now, lastFrameAt),
        ocrAgo = ageSeconds(now, lastOcrAt),
        textAgo = ageSeconds(now, lastTextAt),
        uploadOkAgo = ageSeconds(now, lastUploadOkAt),
        uploadFailStreak = failStreak.get(),
        uptime = ageSeconds(now, startedAt),
    )

    /** 距 at 过去的整秒数;从未发生为 null;时钟倒退(单调时钟不应出现)按 0 算,不报负数。 */
    private fun ageSeconds(now: Long, at: Long): Int? =
        if (at == NEVER) null else ((now - at).coerceAtLeast(0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
