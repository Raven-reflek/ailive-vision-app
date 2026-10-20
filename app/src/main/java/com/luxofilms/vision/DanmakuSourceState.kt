package com.luxofilms.vision

/**
 * 本店的"弹幕来源"设置(ADR-0033、契约 04「弹幕来源」)在这台采集手机上的已知状态,只用来决定要不要在首页提示。
 *
 * 云服务按门店设置强制:来源设为 dycast(协议采集)时,这台手机上传的文字弹幕不会被处理——
 * `/api/vision/text` 返回 200 + `{"ignored":"source"}`(用 200 是为了让旧版 APP 不把它当上传失败)。
 * 采集本身照常运行,这里只负责让店员知道"拍了也没用",免得对着一部不起作用的采集手机排查标定。
 *
 * 两个信息来源:① 上传回包里的 ignored;② 首页回到前台时的 `/api/me`(带 danmaku_source,限频,见 BindingGuard.shouldCheck)。
 * 采集线程写、主线程读,@Volatile 够用。纯 Kotlin,可单元测试。
 */
object DanmakuSourceState {
    const val DYCAST = "dycast"

    /** "dycast" / "vision" / "both";空串 = 还不知道(没探测过,或刚上传成功过) */
    @Volatile var source: String = ""
        private set

    /** 首页要不要提示"这台上传的弹幕不会被使用" */
    val ignoredByServer: Boolean get() = source == DYCAST

    /** `/api/me` 的 danmaku_source。老云服务没有这个字段 → 空串 → 不提示。 */
    fun noteFromMe(src: String?) { source = normalize(src) }

    /**
     * 一次文字上传的回包。ignored = 回包带 `ignored:"source"`。
     * 被忽略 → 一定是 dycast(回包里的值缺失时也按 dycast);被正常处理 → 来源肯定不是 dycast,
     * 之前记着 dycast 的清成"不知道"(不替云端断言成 both 还是 vision)。
     */
    fun noteUpload(ignored: Boolean, src: String?) {
        source = if (ignored) normalize(src).ifEmpty { DYCAST } else if (source == DYCAST) "" else source
    }

    /** 解除绑定 / 令牌失效后清掉:重新绑定到别的门店时不能带着上一家店的状态 */
    fun reset() { source = "" }

    private fun normalize(v: String?): String = when (val s = v?.trim()?.lowercase()) {
        "dycast", "vision", "both" -> s
        else -> ""
    }
}
