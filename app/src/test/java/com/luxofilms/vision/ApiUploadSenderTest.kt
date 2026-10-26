package com.luxofilms.vision

import com.luxofilms.vision.UploadEngine.SendResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 上传结果的归类(契约 04「错误语义」)与心跳的上传计数口径。
 * 重点:200(含 replayed / inflight)= 成功;429 = 只延后;`403 + store_disabled` = 普通失败(不解绑);只有 `401/410 + need_bind` 才是令牌失效。
 */
class ApiUploadSenderTest {
    private fun err(code: Int, body: String = "{}", retryAfterHeader: Int? = null) =
        Api.ApiError(code, "HTTP $code", JSONObject(body), retryAfterHeader)

    // ── 200 ──
    @Test fun response_normal_isOk() =
        assertEquals(SendResult.Ok(ignored = false, danmakuSource = null),
            ApiUploadSender.fromResponse(JSONObject("""{"accepted":2,"events":[],"pending":0}""")))

    @Test fun response_ignoredBySource_isOkAndCarriesTheSource() =
        assertEquals(SendResult.Ok(ignored = true, danmakuSource = "dycast"),
            ApiUploadSender.fromResponse(JSONObject("""{"accepted":0,"events":[],"ignored":"source","danmaku_source":"dycast"}""")))

    @Test fun response_replayed_isOk() =
        assertEquals(SendResult.Ok(ignored = false, danmakuSource = null),
            ApiUploadSender.fromResponse(JSONObject("""{"accepted":3,"pending":0,"dropped":1,"entrances":0,"echoed":0,"shadowed":0,"filtered":0,"events":[],"replayed":true}""")))

    @Test fun response_inflight_isOk() =
        assertEquals(SendResult.Ok(ignored = false, danmakuSource = null),
            ApiUploadSender.fromResponse(JSONObject("""{"accepted":0,"events":[],"pending":0,"replayed":true,"inflight":true}""")))

    @Test fun response_oldCloud_emptyBody_isOk() =
        assertEquals(SendResult.Ok(), ApiUploadSender.fromResponse(JSONObject()))

    @Test fun response_emptySourceString_isNull() =
        assertNull(ApiUploadSender.fromResponse(JSONObject("""{"danmaku_source":""}""")).danmakuSource)

    // ── 429 ──
    @Test fun rateLimited_prefersTheRetryAfterHeader() =
        assertEquals(SendResult.RateLimited(30), ApiUploadSender.fromError(err(429, """{"error":"慢点","retry_after":45}""", retryAfterHeader = 30)))

    @Test fun rateLimited_fallsBackToTheBodyRetryAfter() =
        assertEquals(SendResult.RateLimited(45), ApiUploadSender.fromError(err(429, """{"error":"慢点","retry_after":45}""")))

    @Test fun rateLimited_headerThatIsNotAPositiveNumber_fallsBackToBody() {
        assertEquals(SendResult.RateLimited(45), ApiUploadSender.fromError(err(429, """{"retry_after":45}""", retryAfterHeader = 0)))
        assertEquals(SendResult.RateLimited(45), ApiUploadSender.fromError(err(429, """{"retry_after":45}""", retryAfterHeader = -3)))
    }

    @Test fun rateLimited_oldCloudWithoutAnyHint_isNull_soTheQueueUsesItsDefault() {
        assertEquals(SendResult.RateLimited(null), ApiUploadSender.fromError(err(429)))
        assertEquals(SendResult.RateLimited(null), ApiUploadSender.fromError(err(429, """{"retry_after":"soon"}""")))
        assertEquals(SendResult.RateLimited(null), ApiUploadSender.fromError(err(429, """{"retry_after":0}""")))
    }

    // ── 令牌失效只认 need_bind ──
    @Test fun tokenInvalid_401and410WithNeedBind() {
        assertEquals(SendResult.TokenInvalid, ApiUploadSender.fromError(err(401, """{"need_bind":true}""")))
        assertEquals(SendResult.TokenInvalid, ApiUploadSender.fromError(err(410, """{"need_bind":true}""")))
    }

    @Test fun storeDisabled403_isAnOrdinaryFailure_notTokenInvalid() =
        assertEquals(SendResult.Failed(403), ApiUploadSender.fromError(err(403, """{"store_disabled":true}""")))

    @Test fun errorsWithoutTheNeedBindMark_areOrdinaryFailures() {
        assertEquals("反向代理自己的 401", SendResult.Failed(401), ApiUploadSender.fromError(err(401)))
        assertEquals(SendResult.Failed(410), ApiUploadSender.fromError(err(410)))
        assertEquals(SendResult.Failed(500), ApiUploadSender.fromError(err(500)))
        assertEquals(SendResult.Failed(502), ApiUploadSender.fromError(err(502)))
        assertEquals(SendResult.Failed(404), ApiUploadSender.fromError(err(404)))
        assertEquals(SendResult.Failed(400), ApiUploadSender.fromError(err(400, """{"error":"bad"}""")))
    }

    @Test fun apiError_retryAfterSec_readsHeaderThenBody() {
        assertEquals(7, err(429, "{}", 7).retryAfterSec)
        assertEquals(9, err(429, """{"retry_after":9}""").retryAfterSec)
        assertEquals(7, err(429, """{"retry_after":9}""", 7).retryAfterSec)
        assertNull(err(429).retryAfterSec)
    }

    // ── 心跳的上传计数口径(CaptureStats):200 含 ignored 算成功;429 既不成功也不失败;其余失败 ──
    private var clockNow = 1_000_000L
    private val originalClock = CaptureStats.clock

    @Before fun setUp() {
        CaptureStats.clock = { clockNow }
        CaptureStats.reset()
    }

    @After fun tearDown() { CaptureStats.clock = originalClock }

    @Test fun stats_okIncludingIgnoredAndReplayed_countsAsSuccess_andClearsTheStreak() {
        ApiUploadSender.noteStats(SendResult.Failed(500)); ApiUploadSender.noteStats(SendResult.Failed(null))
        clockNow += 4_000
        ApiUploadSender.noteStats(ApiUploadSender.fromResponse(JSONObject("""{"ignored":"source","danmaku_source":"dycast"}""")))
        clockNow += 2_000
        val s = CaptureStats.snapshot()
        assertEquals(2, s.uploadOkAgo)
        assertEquals(0, s.uploadFailStreak)
        ApiUploadSender.noteStats(ApiUploadSender.fromResponse(JSONObject("""{"replayed":true,"inflight":true}""")))
        assertEquals(0, CaptureStats.snapshot().uploadOkAgo)
    }

    @Test fun stats_429_isNeitherSuccessNorFailure() {
        ApiUploadSender.noteStats(SendResult.Failed(503))
        ApiUploadSender.noteStats(SendResult.RateLimited(30))
        ApiUploadSender.noteStats(SendResult.RateLimited(null))
        val s = CaptureStats.snapshot()
        assertEquals("429 不增加连续失败", 1, s.uploadFailStreak)
        assertNull("429 也不算上传成功", s.uploadOkAgo)
    }

    @Test fun stats_otherErrors_countAsFailures() {
        ApiUploadSender.noteStats(SendResult.Failed(403))      // store_disabled
        ApiUploadSender.noteStats(SendResult.Failed(502))
        ApiUploadSender.noteStats(SendResult.Failed(null))     // 断网 / 超时
        ApiUploadSender.noteStats(SendResult.TokenInvalid)
        assertEquals(4, CaptureStats.snapshot().uploadFailStreak)
        assertNull(CaptureStats.snapshot().uploadOkAgo)
    }
}
