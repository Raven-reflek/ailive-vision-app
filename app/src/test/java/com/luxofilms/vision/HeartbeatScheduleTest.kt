package com.luxofilms.vision

import com.luxofilms.vision.BindingGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 心跳发送节奏:成功按服务端建议(夹 30~300)、失败 60→120→300 退避、404 慢速、令牌失效不重试。 */
class HeartbeatScheduleTest {
    private val s = HeartbeatSchedule()

    // ── 成功:服务端建议的间隔 ──
    @Test fun success_defaultsTo60_whenServerGivesNoInterval() = assertEquals(60, s.onSuccess(null))

    @Test fun success_followsServerSuggestion() {
        assertEquals(45, s.onSuccess(45))
        assertEquals(120, s.onSuccess(120))
    }

    @Test fun success_intervalClampedTo30to300() {
        assertEquals(30, s.onSuccess(30))
        assertEquals(30, s.onSuccess(5))
        assertEquals(300, s.onSuccess(300))
        assertEquals(300, s.onSuccess(3600))
    }

    @Test fun success_nonPositiveInterval_isTreatedAsMissing() {
        assertEquals(60, s.onSuccess(0))                 // optInt 对缺省 / 脏值给 0
        assertEquals(60, s.onSuccess(-10))
    }

    // ── 一般失败:退避 ──
    @Test fun failure_backoff_60_120_300_thenCapped() {
        assertEquals(60, s.onFailure())
        assertEquals(120, s.onFailure())
        assertEquals(300, s.onFailure())
        assertEquals(300, s.onFailure())
        assertEquals(300, s.onFailure())
    }

    @Test fun success_resetsBackoff() {
        s.onFailure(); s.onFailure(); s.onFailure()
        assertEquals(60, s.onSuccess(null))
        assertEquals("退避从头来", 60, s.onFailure())
        assertEquals(120, s.onFailure())
    }

    @Test fun backoff_isPerInstance() {
        s.onFailure(); s.onFailure()
        assertEquals("服务重启后新建一份节奏,不继承旧的退避", 60, HeartbeatSchedule().onFailure())
    }

    // ── 按错误应答分流 ──
    @Test fun error_404_slowMode10Minutes() {
        assertEquals(600, s.onError(404, Verdict.OTHER))
        assertEquals("一直 404 就一直 10 分钟,不再往上加", 600, s.onError(404, Verdict.OTHER))
    }

    @Test fun error_404_doesNotAccumulateBackoff_andSuccessAfterUpgradeGoesBackToNormal() {
        s.onError(404, Verdict.OTHER); s.onError(404, Verdict.OTHER)
        assertEquals("云服务升级后第一次成功回到正常节奏", 60, s.onSuccess(60))
        assertEquals(60, s.onFailure())
    }

    @Test fun error_404_resetsAnEarlierGenericBackoff() {
        s.onFailure(); s.onFailure()
        s.onError(404, Verdict.OTHER)
        assertEquals(60, s.onFailure())
    }

    @Test fun error_tokenInvalid_isNotRetried() {
        assertNull(s.onError(401, Verdict.TOKEN_INVALID))
        assertNull(s.onError(410, Verdict.TOKEN_INVALID))
    }

    @Test fun error_storeDisabled_keepsNormalSchedule_noBackoff() {
        s.onFailure(); s.onFailure()
        assertEquals(60, s.onError(403, Verdict.STORE_DISABLED))
        assertEquals("停用不算失败,退避清零", 60, s.onFailure())
    }

    @Test fun error_otherCodes_backOff() {
        assertEquals(60, s.onError(500, Verdict.OTHER))
        assertEquals(120, s.onError(502, Verdict.OTHER))
        assertEquals(300, s.onError(429, Verdict.OTHER))      // 429:这一次没发成,照一般失败处理
    }

    @Test fun error_realApiErrors_viaBindingGuard() {
        val revoked = Api.ApiError(401, "令牌失效", org.json.JSONObject("""{"need_bind":true}"""))
        val disabled = Api.ApiError(403, "已停用", org.json.JSONObject("""{"store_disabled":true}"""))
        val oldCloud = Api.ApiError(404, "HTTP 404")
        val proxy401 = Api.ApiError(401, "HTTP 401")           // 没有 need_bind 的 401(反向代理):不当成令牌失效
        assertNull(s.onError(revoked.code, BindingGuard.classify(revoked)))
        assertEquals(60, s.onError(disabled.code, BindingGuard.classify(disabled)))
        assertEquals(600, s.onError(oldCloud.code, BindingGuard.classify(oldCloud)))
        assertEquals(60, s.onError(proxy401.code, BindingGuard.classify(proxy401)))
    }

    // ── 常量与契约一致 ──
    @Test fun constants_matchContract() {
        assertEquals(5, HeartbeatSchedule.INITIAL_DELAY_S)
        assertEquals(60, HeartbeatSchedule.DEFAULT_INTERVAL_S)
        assertEquals(30, HeartbeatSchedule.MIN_INTERVAL_S)
        assertEquals(300, HeartbeatSchedule.MAX_INTERVAL_S)
        assertEquals(600, HeartbeatSchedule.SLOW_INTERVAL_S)
    }
}
