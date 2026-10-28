package com.luxofilms.vision

import android.content.Context

/**
 * 绑定状态的守卫:判断"令牌是不是已经被服务端作废",并统一处理失效后的善后。
 *
 * 背景(2026-10 排查):总部在管理台"撤销设备 / 换新手机"后,云服务只会踢掉播放通道的 WebSocket;
 * 视觉采集 APP 只有 HTTP 上传、没有长连接,服务端无从通知。以前 APP 只看本地有没有存令牌(Prefs.bound),
 * 上传收到 401 也只记日志、标成"上传失败,这几行已丢弃",于是撤销后一直显示"已绑定"却什么都传不上去。
 * 现在有两处发现入口:① 上传收到 401/410 + need_bind(上传线程 UploadEngine → CaptureService);
 * ② 首页回到前台时主动探测(MainActivity.checkToken)。两处都走这里。
 */
object BindingGuard {

    enum class Verdict {
        /** 令牌作废:被撤销 / 换新手机 / 门店已删。必须重新扫码。 */
        TOKEN_INVALID,
        /** 门店被总部停用(总开关或合作期限到期):令牌仍有效,重新扫码没用,等总部恢复即可。不能解绑。 */
        STORE_DISABLED,
        /** 其它(网络错误、5xx、网关返回的非 JSON 401/403、限流……):一律不当成令牌失效,避免误解绑。 */
        OTHER,
    }

    /**
     * 只认服务端明确带 need_bind 标记的 401(设备令牌无效)和 410(门店不存在)。
     * 没有标记的 401(比如反向代理自己返回的)不算:误判的代价是店员的手机被清掉绑定、要重新扫码。
     */
    fun classify(httpCode: Int, needBind: Boolean, storeDisabled: Boolean): Verdict = when {
        (httpCode == 401 || httpCode == 410) && needBind -> Verdict.TOKEN_INVALID
        httpCode == 403 && storeDisabled -> Verdict.STORE_DISABLED
        else -> Verdict.OTHER
    }

    fun classify(e: Api.ApiError): Verdict = classify(e.code, e.needBind, e.storeDisabled)

    /** 回到前台时的令牌探测要不要发:距上次不足 minIntervalMs 就不发(避免频繁切换界面反复打服务端);
     * 系统时钟被往回拨时也放行。 */
    fun shouldCheck(now: Long, lastCheckAt: Long, minIntervalMs: Long = CHECK_MIN_INTERVAL_MS): Boolean =
        lastCheckAt == 0L || now < lastCheckAt || now - lastCheckAt >= minIntervalMs

    const val CHECK_MIN_INTERVAL_MS = 60_000L

    const val NOTICE_INVALID = "绑定已失效(这台设备被总部撤销,或门店的设备已更换)。标定数据已保留,请重新扫码绑定,绑定后点“开始采集”即可。"
    const val NOTICE_UNBOUND = "已解除绑定。标定数据已保留,重新扫码绑定后点“开始采集”即可。"
    const val WARN_STORE_DISABLED = "门店已被总部停用或合作期已到期,采集暂时不会生效。无需重新扫码,请联系总部。"

    /**
     * 清掉绑定(保留标定)并停止采集。幂等:已经不是绑定状态就什么都不做、返回 false,
     * 所以相机线程里连续几帧都收到 401、或者首页探测和上传同时发现,也只会处理并告警一次。
     * @param alert 是否发通知。令牌失效时发(店员可能不在 APP 里);店员自己点"解除绑定"时不发。
     */
    @Synchronized
    fun clearBindingAndStop(ctx: Context, notice: String, alert: Boolean): Boolean {
        if (!Prefs.bound) return false
        Prefs.clearBinding(notice)
        DanmakuSourceState.reset()          // 别带着上一家店的弹幕来源状态去绑定下一家
        CloudOcrState.reset()               // 云端识别开关同理:换绑到没开的店,第一批弹幕别再白传一张截图
        CaptureService.stop(ctx)
        if (alert) Alerts.bindingInvalid(ctx)
        return true
    }
}
