package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传队列的纯逻辑(契约 04「手机端上传队列」,ADR-0041):先进先出、一批 ≤ 25 条并可合并几帧、45 秒过期、
 * 200 条溢出丢最旧、冻结批沿用 batch_id、退避 2/4/8/15/15 与成功清零、429 的 Retry-After、最小间隔、同一时刻只有一批在途。
 * 假时钟(毫秒)与假批次号驱动,不需要真的等。
 */
class UploadQueueTest {
    private var now = 1_000_000L
    private var idSeq = 0
    private val q = UploadQueue({ now }, { "b${++idSeq}" })

    private fun item(i: Int) = LineMerger.Item(text = "昵称$i:弹幕$i", content = "弹幕$i", atEdge = false)
    private fun items(from: Int, n: Int) = (from until from + n).map(::item)
    private fun advance(ms: Long) { now += ms }
    private fun contents(es: List<UploadQueue.Entry>) = es.map { it.content }
    private fun lines(from: Int, n: Int) = (from until from + n).map { "弹幕$it" }

    /** 失败 / 限流之后,恰好要等 ms 才取得到批次:到点前 1 毫秒还是 null,到点就有。返回取到的批次。 */
    private fun takeAfter(ms: Long): UploadQueue.Batch {
        advance(ms - 1)
        assertNull("还没到点($ms 毫秒)不该取到", q.take())
        advance(1)
        return q.take() ?: error("到点了应该取到")
    }

    // ── 先进先出与分批 ──
    @Test fun take_isFifo_andBatchGetsAnId() {
        q.enqueue(items(0, 3))
        val b = q.take()!!
        assertEquals(lines(0, 3), b.lines)
        assertEquals("b1", b.id)
    }

    @Test fun emptyQueue_takeIsNull_andNothingToWaitFor() {
        assertNull(q.take())
        assertNull(q.nextWakeMs())
        assertEquals(0, q.size)
    }

    @Test fun enqueueEmptyList_isANoOp() {
        assertTrue(q.enqueue(emptyList()).isEmpty())
        assertEquals(0, q.size)
    }

    @Test fun entriesCarryItemTagAndEnqueueTime() {
        val it0 = item(0)
        q.enqueue(listOf(it0), tag = 42L)
        advance(500)
        q.enqueue(listOf(item(1)), tag = 43L)
        val e = q.take()!!.entries
        assertSame("去重器要认得原条目", it0, e[0].item)
        assertEquals("弹幕0", e[0].content)
        assertEquals(42L, e[0].tag)
        assertEquals(1_000_000L, e[0].enqueuedAt)
        assertEquals(43L, e[1].tag)
        assertEquals(1_000_500L, e[1].enqueuedAt)
    }

    @Test fun batch_isAtMost25_mergesSeveralFrames_inOrder() {
        q.enqueue(items(0, 10), tag = 1L)
        q.enqueue(items(10, 20), tag = 2L)              // 两帧共 30 条
        val first = q.take()!!
        assertEquals("一批最多 25 条,第二帧只带走一部分", lines(0, 25), first.lines)
        assertEquals(List(10) { 1L } + List(15) { 2L }, first.entries.map { it.tag })
        q.ack(first)
        val second = takeAfter(UploadQueue.MIN_GAP_MS)
        assertEquals(lines(25, 5), second.lines)
        assertEquals("新的一批新的 id", listOf("b1", "b2"), listOf(first.id, second.id))
    }

    @Test fun severalSmallFrames_mergeIntoOneBatch() {
        q.enqueue(items(0, 2), tag = 1L)
        q.enqueue(items(2, 3), tag = 2L)
        q.enqueue(items(5, 1), tag = 3L)
        assertEquals(lines(0, 6), q.take()!!.lines)
    }

    // ── 同一时刻一个在途、最小间隔 ──
    @Test fun onlyOneRequestInFlight() {
        q.enqueue(items(0, 3))
        assertNotNull(q.take())
        q.enqueue(items(3, 3))
        advance(60_000)
        assertNull("上一批还没回结果,不能取下一批", q.take())
    }

    @Test fun minGap_isMeasuredBetweenRequestStarts() {
        q.enqueue(items(0, 1))
        val b = q.take()!!                              // 请求在 t0 开始
        advance(300)
        q.ack(b)                                        // 300 毫秒就回来了
        q.enqueue(items(1, 1))
        advance(699)                                    // t0 + 999
        assertNull(q.take())
        advance(1)                                      // t0 + 1000
        assertEquals(lines(1, 1), q.take()!!.lines)
    }

    @Test fun ack_removesTheBatch_andLeavesTheRest() {
        q.enqueue(items(0, 30))
        val b = q.take()!!
        assertEquals(30, q.size)
        q.ack(b)
        assertEquals(5, q.size)
    }

    @Test fun ack_emptiesQueue_nothingLeftToWaitFor() {
        q.enqueue(items(0, 2))
        q.ack(q.take()!!)
        assertEquals(0, q.size)
        assertNull(q.nextWakeMs())
        advance(10_000)
        assertNull(q.take())
    }

    // ── 过期(45 秒,从入队算) ──
    @Test fun ttl_expiresExactlyAt45Seconds_andIsReported() {
        val es = q.enqueue(items(0, 2), tag = 9L)
        assertTrue(es.isEmpty())
        advance(UploadQueue.TTL_MS - 1)
        assertTrue(q.expire().isEmpty())
        advance(1)
        val gone = q.expire()
        assertEquals(lines(0, 2), contents(gone))
        assertEquals(listOf(9L, 9L), gone.map { it.tag })
        assertEquals(0, q.size)
        assertNull(q.take())
    }

    @Test fun ttl_onlyOldEntriesExpire() {
        q.enqueue(items(0, 2))
        advance(30_000)
        q.enqueue(items(2, 2))
        advance(15_000)                                  // 老的 45 秒,新的 15 秒
        assertEquals(lines(0, 2), contents(q.expire()))
        assertEquals(lines(2, 2), q.take()!!.lines)
    }

    @Test fun ttl_expiredItemsAreNeverSent() {
        q.enqueue(items(0, 1))
        advance(UploadQueue.TTL_MS)
        q.expire()
        assertNull(q.take())
    }

    @Test fun ttl_doesNotTouchTheInFlightBatch_untilItComesBack() {
        q.enqueue(items(0, 1))
        val b = q.take()!!                               // 在途
        advance(44_000)
        q.enqueue(items(1, 1))
        advance(2_000)                                   // 在途那条已排了 46 秒,新的 2 秒
        assertTrue("在途的批次已经发出去了,不能抽走", q.expire().isEmpty())
        q.fail(b)
        assertEquals("回来失败了,下一轮才按过期处理", lines(0, 1), contents(q.expire()))
    }

    // ── 溢出:200 条,丢最旧的 ──
    @Test fun capacity_overflowDropsOldest_andReportsThem() {
        val dropped = q.enqueue(items(0, 205))
        assertEquals(lines(0, 5), contents(dropped))
        assertEquals(UploadQueue.CAPACITY, q.size)
        assertEquals("留下的是最新的", lines(5, 25), q.take()!!.lines)
    }

    @Test fun capacity_acrossSeveralEnqueues_dropsTheOldestOnes() {
        assertTrue(q.enqueue(items(0, 150)).isEmpty())
        assertTrue(q.enqueue(items(150, 50)).isEmpty())   // 正好 200
        assertEquals(lines(0, 10), contents(q.enqueue(items(200, 10))))
        assertEquals(200, q.size)
    }

    @Test fun capacity_waitingFrozenBatchIsTheOldest_soItLosesItsHeadFirst_andGetsANewId() {
        q.enqueue(items(0, 200))
        val b = q.take()!!                               // 0..24 冻结
        q.fail(b)                                        // 等退避,没有在途
        val dropped = q.enqueue(items(200, 5))
        assertEquals(lines(0, 5), contents(dropped))
        val again = takeAfter(2_000)
        assertEquals(lines(5, 20), again.lines)
        assertNotEquals("组成变了,换新 id", b.id, again.id)
    }

    @Test fun capacity_inFlightBatchIsNotTouched_theOldestWaitingOnesAreDropped() {
        q.enqueue(items(0, 200))
        val b = q.take()!!                               // 0..24 在途
        val dropped = q.enqueue(items(200, 5))
        assertEquals("在途的不动,从待发队列的队首丢", lines(25, 5), contents(dropped))
        q.ack(b)
        assertEquals(175, q.size)
    }

    // ── 冻结批:沿用 batch_id ──
    @Test fun frozenBatch_keepsItsIdAndContentAcrossRetries() {
        q.enqueue(items(0, 3))
        val first = q.take()!!
        q.fail(first)
        val second = takeAfter(2_000)
        q.fail(second)
        val third = takeAfter(4_000)
        assertEquals(listOf("b1", "b1", "b1"), listOf(first.id, second.id, third.id))
        assertEquals(lines(0, 3), third.lines)
        assertEquals("没有因为重试多生成批次号", 1, idSeq)
    }

    @Test fun frozenBatch_isNotToppedUpWithNewItems() {
        q.enqueue(items(0, 3))
        val b = q.take()!!
        q.fail(b)
        q.enqueue(items(3, 2))                           // 重试等待期间又来了新弹幕
        val retry = takeAfter(2_000)
        assertEquals("b1", retry.id)
        assertEquals("原样重发,不掺新条目", lines(0, 3), retry.lines)
        q.ack(retry)
        assertEquals(lines(3, 2), takeAfter(UploadQueue.MIN_GAP_MS).lines)
    }

    @Test fun expiry_thatChangesTheComposition_givesTheFrozenBatchANewId() {
        q.enqueue(items(0, 2), tag = 1L)                 // 在 t0 入队
        advance(20_000)
        q.enqueue(items(2, 2), tag = 2L)                 // 在 t0+20s 入队
        val b = q.take()!!                               // 4 条合成一批
        assertEquals(lines(0, 4), b.lines)
        q.fail(b)
        advance(25_000)                                  // t0+45s:前两条到期,后两条还在
        val gone = q.expire()
        assertEquals(lines(0, 2), contents(gone))
        val retry = q.take()!!
        assertEquals(lines(2, 2), retry.lines)
        assertNotEquals("组成变了,换新 id", b.id, retry.id)
        assertEquals("b2", retry.id)
    }

    @Test fun expiry_thatRemovesNothingFromTheFrozenBatch_keepsItsId() {
        q.enqueue(items(0, 2))
        val b = q.take()!!
        q.fail(b)
        advance(2_000)
        assertTrue(q.expire().isEmpty())
        assertEquals("b1", q.take()!!.id)
    }

    @Test fun expiry_ofEverythingInTheFrozenBatch_leavesNothing() {
        q.enqueue(items(0, 2))
        q.fail(q.take()!!)
        advance(UploadQueue.TTL_MS)
        assertEquals(2, q.expire().size)
        assertEquals(0, q.size)
        assertNull(q.take())
        assertNull(q.nextWakeMs())
    }

    // ── 失败退避:2 / 4 / 8 / 15 / 15 …,成功清零 ──
    @Test fun backoff_2_4_8_15_15_thenStaysAt15() {
        q.enqueue(items(0, 1))
        var b = q.take()!!
        for (delay in listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L)) {
            q.fail(b)
            b = takeAfter(delay)
            assertEquals("重试沿用同一批", "b1", b.id)
        }
    }

    @Test fun backoff_resetsAfterASuccess() {
        q.enqueue(items(0, 1))
        var b = q.take()!!
        q.fail(b); b = takeAfter(2_000)
        q.fail(b); b = takeAfter(4_000)
        q.ack(b)                                         // 成功了
        q.enqueue(items(1, 1))
        b = takeAfter(UploadQueue.MIN_GAP_MS)
        q.fail(b)
        takeAfter(2_000)                                 // 又从 2 秒起,不是 8 秒
    }

    @Test fun backoff_isMeasuredFromTheFailure_notFromTheRequestStart() {
        q.enqueue(items(0, 1))
        val b = q.take()!!
        advance(10_000)                                  // 请求挂了 10 秒才失败
        q.fail(b)
        takeAfter(2_000)                                 // 失败之后再等 2 秒,而不是已经过了
    }

    // ── 429:Retry-After ──
    @Test fun rateLimited_waitsRetryAfterSeconds() {
        q.enqueue(items(0, 1))
        val b = q.take()!!
        q.rateLimited(b, 30)
        val retry = takeAfter(30_000)
        assertEquals("同一批,同一个 id", "b1", retry.id)
    }

    @Test fun rateLimited_isClampedTo2to65Seconds_defaultIs20WhenAbsent() {
        fun waitFor(retryAfter: Int?): Long {
            val q2 = UploadQueue({ now }, { "x" })
            q2.enqueue(items(0, 1))
            val b = q2.take()!!
            q2.rateLimited(b, retryAfter)
            val start = now
            var t = 0L
            while (q2.take() == null) { advance(500); t += 500; assertTrue("等太久了", t <= 70_000) }
            val waited = now - start
            now = start                                  // 复位,不影响下一组
            return waited
        }
        assertEquals("缺省 20 秒", 20_000L, waitFor(null))
        assertEquals(30_000L, waitFor(30))
        assertEquals("太小夹到 2 秒", 2_000L, waitFor(1))
        assertEquals("0 也夹到 2 秒", 2_000L, waitFor(0))
        assertEquals("负数也夹到 2 秒", 2_000L, waitFor(-5))
        assertEquals("太大夹到 65 秒", 65_000L, waitFor(3600))
        assertEquals(65_000L, waitFor(65))
        assertEquals(2_000L, waitFor(2))
    }

    @Test fun rateLimited_isNotAFailure_soItDoesNotAdvanceTheBackoff() {
        q.enqueue(items(0, 1))
        var b = q.take()!!
        q.rateLimited(b, 5); b = takeAfter(5_000)
        q.fail(b)                                        // 第一次失败:仍是 2 秒
        takeAfter(2_000)
    }

    @Test fun rateLimited_doesNotResetAnExistingFailureStreak() {
        q.enqueue(items(0, 1))
        var b = q.take()!!
        q.fail(b); b = takeAfter(2_000)
        q.fail(b); b = takeAfter(4_000)                  // 已经连续失败 2 次
        q.rateLimited(b, 5); b = takeAfter(5_000)
        q.fail(b)                                        // 第 3 次失败:8 秒,不是回到 2 秒
        takeAfter(8_000)
    }

    @Test fun rateLimited_keepsTheBatchWaitingAndUntouched() {
        q.enqueue(items(0, 2))
        val b = q.take()!!
        q.rateLimited(b, 20)
        q.enqueue(items(2, 1))                           // 限流期间识别照常入队
        assertEquals(3, q.size)
        assertEquals(lines(0, 2), takeAfter(20_000).lines)
    }

    // ── nextWakeMs:什么时候该醒 ──
    @Test fun nextWake_isTheRemainingWait_orTheExpiry_whicheverComesFirst() {
        q.enqueue(items(0, 1))
        assertEquals("能发就是现在", 0L, q.nextWakeMs())
        val b = q.take()!!
        q.fail(b)
        assertEquals(2_000L, q.nextWakeMs())
        advance(500)
        assertEquals(1_500L, q.nextWakeMs())
        val b2 = takeAfter(1_500)
        q.rateLimited(b2, 65)                            // 要等 65 秒,但条目 45 秒就过期
        assertEquals("到期早于能发:在到期那一刻醒来报过期", UploadQueue.TTL_MS - 2_000L, q.nextWakeMs())
    }

    // ── 关闭 ──
    @Test fun close_returnsEverything_andRefusesNewItems() {
        q.enqueue(items(0, 30))
        q.take()                                         // 25 条冻结在途 + 5 条待发
        val cleared = q.close()
        assertEquals(lines(0, 30), contents(cleared))
        assertEquals(0, q.size)
        val refused = q.enqueue(items(30, 2), tag = 5L)
        assertEquals("关闭后入队的全部原样退回", lines(30, 2), contents(refused))
        assertEquals(0, q.size)
        advance(60_000)
        assertNull(q.take())
        assertTrue(q.close().isEmpty())
    }

    @Test fun staleBatchResultsAfterClose_areIgnored() {
        q.enqueue(items(0, 2))
        val b = q.take()!!
        q.close()
        q.ack(b); q.fail(b); q.rateLimited(b, 5)         // 关闭前发出的请求,结果回来了
        assertEquals(0, q.size)
        assertNull(q.nextWakeMs())
    }

    @Test fun bornAt_keepsTheOriginalAge_soFallbackLinesStillExpireOnTime() {
        q.enqueue(items(0, 1), bornAt = now - (UploadQueue.TTL_MS - 5_000L))   // 截图通道已经等了 40 秒才转过来
        advance(5_000L)
        assertEquals("按最早认出的时刻算过期,不从入队重新计 45 秒", 1, q.expire().size)
    }
}
