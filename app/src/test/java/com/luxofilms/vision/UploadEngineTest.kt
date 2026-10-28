package com.luxofilms.vision

import com.luxofilms.vision.UploadEngine.SendResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 上传引擎(ADR-0041)。核心 [UploadEngine.step] 用假时钟 + 假 Sender 一步步推,不起线程;
 * 最后几个用真时钟验证线程壳:Sender 卡住时入队不被拖住、令牌失效只报一次并收工、stop 能收掉线程。
 */
class UploadEngineTest {
    private class Call(val lines: List<String>, val batchId: String, val nicks: List<String?> = emptyList())

    private class FakeSender : UploadEngine.Sender {
        val calls = CopyOnWriteArrayList<Call>()
        val script = ArrayDeque<SendResult>()            // 按顺序回;用完了回 Ok
        @Volatile var hook: () -> Unit = {}
        override fun send(lines: List<String>, batchId: String, nicks: List<String?>): SendResult {
            calls.add(Call(lines, batchId, nicks))
            hook()
            return script.removeFirstOrNull() ?: SendResult.Ok()
        }
    }

    private class Recorder : UploadEngine.Listener {
        class Uploaded(val entries: List<UploadQueue.Entry>, val ignored: Boolean, val source: String?)
        val uploaded = CopyOnWriteArrayList<Uploaded>()
        val expired = CopyOnWriteArrayList<List<UploadQueue.Entry>>()
        val dropped = CopyOnWriteArrayList<List<UploadQueue.Entry>>()
        val tokenInvalid = CopyOnWriteArrayList<List<UploadQueue.Entry>>()
        @Volatile var throwOn: String? = null
        @Volatile var onUploaded: () -> Unit = {}
        @Volatile var onTokenInvalid: () -> Unit = {}

        private fun maybeThrow(what: String) { if (throwOn == what) throw IllegalStateException("回调 $what 故意抛") }
        override fun uploaded(entries: List<UploadQueue.Entry>, ignored: Boolean, danmakuSource: String?) {
            uploaded.add(Uploaded(entries, ignored, danmakuSource)); maybeThrow("uploaded"); onUploaded()
        }
        override fun expired(entries: List<UploadQueue.Entry>) { expired.add(entries); maybeThrow("expired") }
        override fun dropped(entries: List<UploadQueue.Entry>) { dropped.add(entries); maybeThrow("dropped") }
        override fun tokenInvalid(cleared: List<UploadQueue.Entry>) { tokenInvalid.add(cleared); onTokenInvalid() }
    }

    private var now = 5_000_000L
    private var idSeq = 0
    private val sender = FakeSender()
    private val listener = Recorder()
    private val logs = CopyOnWriteArrayList<String>()
    private val engine = UploadEngine(sender, listener, { now }, { "b${++idSeq}" }, { logs.add(it) })

    private fun item(i: Int) = LineMerger.Item(text = "昵称$i:弹幕$i", content = "弹幕$i", atEdge = false, nick = if (i % 2 == 0) "昵称$i" else null)
    private fun items(from: Int, n: Int) = (from until from + n).map(::item)
    private fun lines(from: Int, n: Int) = (from until from + n).map { "弹幕$it" }
    private fun contents(es: List<UploadQueue.Entry>) = es.map { it.content }
    private fun advance(ms: Long) { now += ms }

    // ── 成功 ──
    @Test fun success_reportsUploaded_andQueueEmpties() {
        engine.enqueue(items(0, 2), tag = 7L)
        val wait = engine.step()
        assertEquals(1, sender.calls.size)
        assertEquals(lines(0, 2), sender.calls[0].lines)
        assertEquals("昵称与内容一一对应,读不准的是 null", listOf("昵称0", null), sender.calls[0].nicks)
        assertEquals("b1", sender.calls[0].batchId)
        val u = listener.uploaded.single()
        assertEquals(lines(0, 2), contents(u.entries))
        assertEquals(listOf(7L, 7L), u.entries.map { it.tag })
        assertFalse(u.ignored)
        assertNull(u.source)
        assertEquals(0, engine.queued)
        assertEquals("没有后续:等新条目", UploadEngine.IDLE, wait)
        assertTrue(listener.expired.isEmpty() && listener.dropped.isEmpty() && listener.tokenInvalid.isEmpty())
    }

    @Test fun idle_stepDoesNothing() {
        assertEquals(UploadEngine.IDLE, engine.step())
        assertTrue(sender.calls.isEmpty())
    }

    @Test fun okIgnoredBySource_isStillASuccess_andPassesTheSourceThrough() {
        sender.script.add(SendResult.Ok(ignored = true, danmakuSource = "dycast"))
        engine.enqueue(items(0, 1))
        engine.step()
        val u = listener.uploaded.single()
        assertTrue(u.ignored)
        assertEquals("dycast", u.source)
        assertEquals(0, engine.queued)
    }

    @Test fun severalFrames_goOutInOneRequest() {
        engine.enqueue(items(0, 3), tag = 1L)
        engine.enqueue(items(3, 4), tag = 2L)
        engine.step()
        assertEquals("两帧的内容合成一批", 1, sender.calls.size)
        assertEquals(lines(0, 7), sender.calls[0].lines)
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L, 2L, 2L), listener.uploaded.single().entries.map { it.tag })
    }

    @Test fun batchLimit25_andMinGapBetweenRequests() {
        engine.enqueue(items(0, 30))
        assertEquals("发完第一批,下一批要等最小间隔", UploadQueue.MIN_GAP_MS, engine.step())
        assertEquals(lines(0, 25), sender.calls[0].lines)
        assertEquals("间隔没到:不发", UploadQueue.MIN_GAP_MS, engine.step())
        assertEquals(1, sender.calls.size)
        advance(UploadQueue.MIN_GAP_MS)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals(lines(25, 5), sender.calls[1].lines)
        assertEquals(listOf("b1", "b2"), sender.calls.map { it.batchId })
    }

    // ── 失败:同一批、同一个 batch_id 退避重试 ──
    @Test fun failure_retriesTheSameBatchId_with2then4SecondBackoff() {
        sender.script.add(SendResult.Failed(500))
        sender.script.add(SendResult.Failed(null))
        engine.enqueue(items(0, 2))
        assertEquals(2_000L, engine.step())
        assertTrue("失败不报成功也不报过期", listener.uploaded.isEmpty() && listener.expired.isEmpty())
        assertEquals(2, engine.queued)
        advance(1_999)
        assertEquals("还差 1 毫秒", 1L, engine.step())
        assertEquals(1, sender.calls.size)
        advance(1)
        assertEquals(4_000L, engine.step())
        advance(4_000)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals(listOf("b1", "b1", "b1"), sender.calls.map { it.batchId })
        assertTrue(sender.calls.all { it.lines == lines(0, 2) })
        assertEquals(1, listener.uploaded.size)
        assertEquals(0, engine.queued)
        assertEquals("重试没有多生成批次号", 1, idSeq)
    }

    @Test fun storeDisabled_isAnOrdinaryFailureWithBackoff_notATokenProblem() {
        sender.script.add(SendResult.Failed(403))        // 403 + store_disabled 在 ApiUploadSender 里归成 Failed
        engine.enqueue(items(0, 1))
        assertEquals(2_000L, engine.step())
        assertTrue("不能解绑", listener.tokenInvalid.isEmpty())
        advance(2_000)
        engine.step()
        assertEquals(1, listener.uploaded.size)
        assertEquals(listOf("b1", "b1"), sender.calls.map { it.batchId })
    }

    @Test fun senderThrowing_isTreatedAsAFailure_andLogged() {
        var explode = true
        sender.hook = { if (explode) { explode = false; throw RuntimeException("boom") } }
        engine.enqueue(items(0, 1))
        assertEquals(2_000L, engine.step())
        assertTrue(logs.any { it.contains("boom") })
        advance(2_000)
        engine.step()
        assertEquals(1, listener.uploaded.size)
    }

    // ── 429:只延后上传 ──
    @Test fun rateLimited_delaysOnlyTheUpload_enqueueKeepsWorking() {
        sender.script.add(SendResult.RateLimited(30))
        engine.enqueue(items(0, 2))
        assertEquals(30_000L, engine.step())
        assertTrue("限流不是失败也不是成功", listener.uploaded.isEmpty() && listener.expired.isEmpty() && listener.dropped.isEmpty())
        engine.enqueue(items(2, 1))                      // 限流期间识别照常入队
        assertEquals(3, engine.queued)
        advance(29_999)
        assertEquals(1L, engine.step())
        assertEquals("没到点不发", 1, sender.calls.size)
        advance(1)
        engine.step()
        assertEquals(listOf("b1", "b1"), sender.calls.map { it.batchId })
        assertEquals("原样重发,不掺限流期间新来的", lines(0, 2), sender.calls[1].lines)
        assertEquals(1, listener.uploaded.size)
        advance(UploadQueue.MIN_GAP_MS)
        engine.step()
        assertEquals(lines(2, 1), sender.calls[2].lines)
        assertEquals("b2", sender.calls[2].batchId)
    }

    @Test fun rateLimited_withoutRetryAfter_waits20Seconds() {
        sender.script.add(SendResult.RateLimited(null))
        engine.enqueue(items(0, 1))
        assertEquals(20_000L, engine.step())
    }

    @Test fun rateLimited_longerThanTheTtl_wakesAtTheExpiry_toReportItOnTime() {
        sender.script.add(SendResult.RateLimited(65))
        engine.enqueue(items(0, 1))
        assertEquals("65 秒的等待,但条目 45 秒就过期:在那一刻醒来", UploadQueue.TTL_MS, engine.step())
        advance(UploadQueue.TTL_MS)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals(1, listener.expired.size)
        assertEquals(1, sender.calls.size)
    }

    // ── 过期与溢出 ──
    @Test fun expiry_isReportedOnce_andTheBatchIsNotRetriedAfterwards() {
        for (i in 0 until 10) sender.script.add(SendResult.Failed(null))
        engine.enqueue(items(0, 2), tag = 3L)
        engine.step()
        advance(UploadQueue.TTL_MS)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals(1, sender.calls.size)
        val gone = listener.expired.single()
        assertEquals(lines(0, 2), contents(gone))
        assertEquals(listOf(3L, 3L), gone.map { it.tag })
        assertEquals(0, engine.queued)
        assertEquals("再 step 不会重复报", UploadEngine.IDLE, engine.step())
        assertEquals(1, listener.expired.size)
    }

    @Test fun expiry_partOfAFrozenBatch_remainderIsResentWithANewId() {
        sender.script.add(SendResult.Failed(null))
        engine.enqueue(items(0, 2), tag = 1L)            // t0
        advance(20_000)
        engine.enqueue(items(2, 2), tag = 2L)            // t0+20s
        engine.step()                                    // 4 条一批,失败
        assertEquals(lines(0, 4), sender.calls[0].lines)
        advance(25_000)                                  // t0+45s:前两条到期,后两条还在
        engine.step()
        assertEquals(lines(0, 2), contents(listener.expired.single()))
        assertEquals(lines(2, 2), sender.calls[1].lines)
        assertEquals(listOf("b1", "b2"), sender.calls.map { it.batchId })
        assertEquals(lines(2, 2), contents(listener.uploaded.single().entries))
    }

    @Test fun overflow_isReportedDroppedImmediately_fromEnqueue() {
        engine.enqueue(items(0, 205), tag = 4L)
        val gone = listener.dropped.single()
        assertEquals(lines(0, 5), contents(gone))
        assertEquals(UploadQueue.CAPACITY, engine.queued)
    }

    // ── 令牌失效 ──
    @Test fun tokenInvalid_callsTheListenerOnce_clearsTheQueue_andNothingIsSentAfterwards() {
        sender.script.add(SendResult.TokenInvalid)
        engine.enqueue(items(0, 30), tag = 1L)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals("在途的 25 条和还没发的 5 条都清掉", lines(0, 30), contents(listener.tokenInvalid.single()))
        assertEquals(0, engine.queued)
        assertTrue(listener.uploaded.isEmpty() && listener.expired.isEmpty())
        advance(120_000)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals("不再发任何请求", 1, sender.calls.size)
        engine.enqueue(items(30, 2))                     // 善后期间还有帧在入队:拒收并报丢弃
        assertEquals(lines(30, 2), contents(listener.dropped.single()))
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals(1, sender.calls.size)
        assertEquals("整个生命周期只报一次", 1, listener.tokenInvalid.size)
    }

    // ── replayed / inflight 都算成功 ──
    @Test fun replayedResponse_countsAsSuccess() {
        sender.script.add(ApiUploadSender.fromResponse(JSONObject("""{"accepted":2,"pending":0,"dropped":0,"entrances":0,"echoed":0,"shadowed":0,"filtered":0,"events":[],"replayed":true}""")))
        engine.enqueue(items(0, 2))
        engine.step()
        assertEquals(1, listener.uploaded.size)
        assertEquals(0, engine.queued)
        assertTrue(listener.tokenInvalid.isEmpty() && listener.expired.isEmpty())
    }

    @Test fun inflightResponse_countsAsSuccess_theOriginalIsStillBeingHandledByTheCloud() {
        sender.script.add(ApiUploadSender.fromResponse(JSONObject("""{"accepted":0,"events":[],"pending":0,"replayed":true,"inflight":true}""")))
        engine.enqueue(items(0, 2))
        engine.step()
        assertEquals(1, listener.uploaded.size)
        assertEquals("不再重发这一批", 0, engine.queued)
        advance(60_000)
        engine.step()
        assertEquals(1, sender.calls.size)
    }

    // ── 停止 ──
    @Test fun stop_returnsWhatWasLeft_andNothingIsSentAfterwards() {
        engine.enqueue(items(0, 30))
        engine.step()                                    // 25 条传完,剩 5 条
        val left = engine.stop()
        assertEquals(lines(25, 5), contents(left))
        assertEquals(0, engine.queued)
        advance(10_000)
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals(1, sender.calls.size)
        engine.enqueue(items(30, 1))
        assertEquals("停了之后入队的拒收", lines(30, 1), contents(listener.dropped.single()))
        assertTrue(engine.stop().isEmpty())
    }

    @Test fun stopDuringASend_makesTheLateResultIrrelevant() {
        var left: List<UploadQueue.Entry> = emptyList()
        sender.hook = { left = engine.stop() }          // 请求发到一半,店员点了"停止采集"
        engine.enqueue(items(0, 2))
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals("在途的那批也算被丢掉", lines(0, 2), contents(left))
        assertTrue("迟到的结果不再处理", listener.uploaded.isEmpty())
    }

    // ── 回调出错不拖垮引擎 ──
    @Test fun listenerThrowing_doesNotBreakTheEngine() {
        listener.throwOn = "uploaded"
        engine.enqueue(items(0, 1))
        assertEquals(UploadEngine.IDLE, engine.step())
        assertEquals("回调抛了异常,这一批照样已出队,不会重发", 0, engine.queued)
        assertTrue(logs.any { it.contains("uploaded") })
    }

    // ── 线程壳(真时钟) ──
    @Test fun threaded_enqueueNeverBlocks_whileTheSenderIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uploaded = CountDownLatch(1)
        val senderThread = java.util.concurrent.atomic.AtomicReference<String>()
        val blocking = UploadEngine.Sender { _, _, _ ->
            senderThread.set(Thread.currentThread().name)
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            SendResult.Ok()
        }
        val rec = Recorder().apply { onUploaded = { uploaded.countDown() } }
        val real = UploadEngine(blocking, rec)
        real.start()
        val pool = Executors.newSingleThreadExecutor()
        try {
            real.enqueue(items(0, 1))
            assertTrue("上传线程应当取到第一批", entered.await(5, TimeUnit.SECONDS))
            // Sender 还卡着:分析器线程照样能入队(这里模拟 500 次),不等网络
            val f = pool.submit { for (i in 1..500) real.enqueue(items(i, 1)) }
            f.get(5, TimeUnit.SECONDS)                   // 被拖住的话这里会超时
            assertEquals("Sender 还没返回,不会有成功回调", 0, rec.uploaded.size)
            assertEquals("在途的 1 条 + 最多 199 条待发,其余 301 条按最旧优先被挤掉并报出来", 301, rec.dropped.sumOf { it.size })
            release.countDown()
            assertTrue("放开后这一批应当传完", uploaded.await(5, TimeUnit.SECONDS))
            assertEquals(lines(0, 1), contents(rec.uploaded[0].entries))
            assertEquals("发送在专用的上传线程里", "vision-upload", senderThread.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
            real.stop()
        }
    }

    @Test fun threaded_tokenInvalid_reportsOnce_andTheThreadEnds() {
        val reported = CountDownLatch(1)
        val rec = Recorder().apply { onTokenInvalid = { reported.countDown() } }
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val real = UploadEngine({ _, _, _ -> sends.incrementAndGet(); SendResult.TokenInvalid }, rec)
        real.start()
        try {
            real.enqueue(items(0, 3))
            assertTrue(reported.await(5, TimeUnit.SECONDS))
            assertEquals(3, rec.tokenInvalid.single().size)
            assertTrue("上传线程随之结束", waitUntilNoUploadThread())
            real.enqueue(items(3, 1))
            Thread.sleep(100)
            assertEquals("不再发请求", 1, sends.get())
            assertEquals(1, rec.tokenInvalid.size)
        } finally {
            real.stop()
        }
    }

    @Test fun threaded_stop_endsTheUploadThread_evenWhenIdle() {
        val real = UploadEngine({ _, _, _ -> SendResult.Ok() }, Recorder())
        real.start()
        real.start()                                     // 幂等
        assertTrue(Thread.getAllStackTraces().keys.any { it.name == "vision-upload" && it.isAlive })
        real.stop()
        assertTrue(waitUntilNoUploadThread())
    }

    private fun waitUntilNoUploadThread(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keys.none { it.name == "vision-upload" && it.isAlive }) return true
            Thread.sleep(20)
        }
        return false
    }
}
