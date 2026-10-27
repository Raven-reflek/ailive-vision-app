package com.luxofilms.vision

import com.luxofilms.vision.UploadEngine.SendResult
import org.json.JSONObject

/**
 * 上传引擎([UploadEngine])的正式发送方:包一层 [Api.visionText],把回包 / 异常归成 [SendResult],并按心跳口径记 [CaptureStats]。
 * 在上传线程里被同步调用。归类([fromResponse] / [fromError])和计数([noteStats])是纯函数,单元测试直接测它们。
 *
 * 归类规则(契约 04「错误语义」):
 * - 200 一律成功——含 `replayed` / `inflight`(云端已有这个 batch_id,重放当时的摘要;`events` 为空也无妨,手机端不读它);
 *   回包带 `ignored:"source"` 表示本店弹幕来源为协议采集、云端没使用,上传本身是通的,不是失败;
 * - 429 → [SendResult.RateLimited](带 `Retry-After` 秒数),只延后上传;
 * - `401/410 + need_bind` → [SendResult.TokenInvalid](判定统一走 [BindingGuard.classify],没有 need_bind 标记的 401 不算);
 * - 其余(网络错误、超时、5xx、`403 + store_disabled`、没有标记的 401/403 ……)→ [SendResult.Failed],一律退避重试、**不解绑**。
 */
object ApiUploadSender : UploadEngine.Sender {
    override fun send(lines: List<String>, batchId: String): SendResult {
        val result = try {
            fromResponse(Api.visionText(lines, batchId))
        } catch (e: Api.ApiError) {
            fromError(e)
        } catch (e: Exception) {
            SendResult.Failed()      // 断网、超时(整个调用 15 秒)、读回包出错……
        }
        noteStats(result)
        return result
    }

    fun fromResponse(resp: JSONObject): SendResult.Ok = SendResult.Ok(
        ignored = resp.optString("ignored") == "source",
        danmakuSource = resp.optString("danmaku_source").ifEmpty { null },
    )

    fun fromError(e: Api.ApiError): SendResult = when {
        e.code == 429 -> SendResult.RateLimited(e.retryAfterSec)
        BindingGuard.classify(e) == BindingGuard.Verdict.TOKEN_INVALID -> SendResult.TokenInvalid
        else -> SendResult.Failed(e.code)
    }

    /**
     * 心跳的上传计数口径(CaptureStats):200 算成功(含 ignored);429 既不算成功也不算失败(CaptureStats 里忽略);
     * 其余都算失败,下一次成功清零连续失败次数。
     */
    fun noteStats(result: SendResult) {
        when (result) {
            is SendResult.Ok -> CaptureStats.noteUploadOk()
            is SendResult.RateLimited -> CaptureStats.noteUploadFailed(429)
            is SendResult.Failed -> CaptureStats.noteUploadFailed(result.httpCode)
            SendResult.TokenInvalid -> CaptureStats.noteUploadFailed()
        }
    }
}
