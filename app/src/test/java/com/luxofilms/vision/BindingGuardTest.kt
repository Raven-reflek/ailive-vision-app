package com.luxofilms.vision

import com.luxofilms.vision.BindingGuard.Verdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 令牌失效判定:什么算"必须重新扫码",什么绝对不能误判成失效(误判 = 店员手机被清掉绑定)。 */
class BindingGuardTest {

    // ── 该解绑的 ─────────────────────────────────────────────
    @Test fun revokedToken_401WithNeedBind_isInvalid() =
        assertEquals(Verdict.TOKEN_INVALID, BindingGuard.classify(401, needBind = true, storeDisabled = false))

    @Test fun storeDeleted_410WithNeedBind_isInvalid() =
        assertEquals(Verdict.TOKEN_INVALID, BindingGuard.classify(410, needBind = true, storeDisabled = false))

    // ── 不能解绑的 ───────────────────────────────────────────
    @Test fun storeDisabled_403_isNotInvalid_keepBinding() =
        assertEquals(Verdict.STORE_DISABLED, BindingGuard.classify(403, needBind = false, storeDisabled = true))

    @Test fun proxy401WithoutNeedBind_isNotInvalid() =
        assertEquals(Verdict.OTHER, BindingGuard.classify(401, needBind = false, storeDisabled = false))

    @Test fun rateLimited429_isOther() =
        assertEquals(Verdict.OTHER, BindingGuard.classify(429, needBind = false, storeDisabled = false))

    @Test fun serverErrors_areOther() {
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals("HTTP $code", Verdict.OTHER, BindingGuard.classify(code, needBind = false, storeDisabled = false))
        }
    }

    @Test fun needBindFlagOnUnrelatedStatus_isOther() =
        // need_bind 只在 401/410 上有意义;别的状态码带了也不当成令牌失效
        assertEquals(Verdict.OTHER, BindingGuard.classify(500, needBind = true, storeDisabled = false))

    @Test fun storeDisabledFlagOn401_isNotDisabled() =
        assertEquals(Verdict.OTHER, BindingGuard.classify(401, needBind = false, storeDisabled = true))

    // ── 用真实的 ApiError(带服务端 JSON 体)走一遍,确认字段名与服务端一致 ──
    @Test fun apiError_fromServerBodies() {
        val revoked = Api.ApiError(401, "设备未绑定或令牌已失效", JSONObject("""{"error":"设备未绑定或令牌已失效","need_bind":true}"""))
        val gone = Api.ApiError(410, "门店不可用", JSONObject("""{"error":"门店不可用:x","need_bind":true}"""))
        val disabled = Api.ApiError(403, "已停用", JSONObject("""{"error":"已停用","store_disabled":true}"""))
        val rateLimited = Api.ApiError(429, "上报太快了,歇一下", JSONObject("""{"error":"上报太快了,歇一下"}"""))
        val bareProxy401 = Api.ApiError(401, "HTTP 401")      // 反向代理自己的 401:没有 JSON 体
        assertEquals(Verdict.TOKEN_INVALID, BindingGuard.classify(revoked))
        assertEquals(Verdict.TOKEN_INVALID, BindingGuard.classify(gone))
        assertEquals(Verdict.STORE_DISABLED, BindingGuard.classify(disabled))
        assertEquals(Verdict.OTHER, BindingGuard.classify(rateLimited))
        assertEquals(Verdict.OTHER, BindingGuard.classify(bareProxy401))
    }

    // ── 探测限频 ─────────────────────────────────────────────
    @Test fun shouldCheck_firstTime() = assertTrue(BindingGuard.shouldCheck(now = 1_000_000L, lastCheckAt = 0L))

    @Test fun shouldCheck_withinInterval_skips() =
        assertFalse(BindingGuard.shouldCheck(now = 1_000_000L + 59_999L, lastCheckAt = 1_000_000L))

    @Test fun shouldCheck_afterInterval_runs() =
        assertTrue(BindingGuard.shouldCheck(now = 1_000_000L + 60_000L, lastCheckAt = 1_000_000L))

    @Test fun shouldCheck_clockWentBackwards_runs() =
        assertTrue(BindingGuard.shouldCheck(now = 500_000L, lastCheckAt = 1_000_000L))
}
