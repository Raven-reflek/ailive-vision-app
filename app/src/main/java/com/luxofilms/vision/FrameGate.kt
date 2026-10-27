package com.luxofilms.vision

/**
 * 每一帧该做什么:做 OCR(识别后入上传队列),还是只出一张预览图,还是直接丢掉。
 *
 * 从 CaptureService 的分析器里抽出来,是为了能单元测试——"采集预览"页打开时需要比 OCR 更高的取帧频率,
 * 但**绝不能因此改变 OCR 的节奏**。所以两路节流各算各的:
 *  - OCR:距上次 OCR 不足采样间隔就不做。0.6.0 起(ADR-0041)不再有"429 冷却"——上传在独立线程里排队,
 *    被限流只延后上传、不影响识别,所以这里不再认识限流;
 *  - 预览:仅当预览页打开时才出图,且按 PREVIEW_INTERVAL_MS 节流。
 */
object FrameGate {
    /** 预览出图的最小间隔(上限约 2.5 帧/秒)。实测受识别线程占用等影响约 2 帧/秒:调支架时够顺,又不会让识别线程忙不过来。 */
    const val PREVIEW_INTERVAL_MS = 400L

    data class Decision(val ocr: Boolean, val preview: Boolean) {
        /** 既不识别也不出预览:这一帧直接关掉。 */
        val skip: Boolean get() = !ocr && !preview
    }

    fun decide(
        now: Long,
        lastOcrAt: Long,
        ocrIntervalMs: Long,
        previewActive: Boolean,
        lastPreviewAt: Long,
        previewIntervalMs: Long = PREVIEW_INTERVAL_MS,
    ): Decision {
        val ocrDue = now - lastOcrAt >= ocrIntervalMs
        val previewDue = previewActive &&
            (lastPreviewAt == 0L || now < lastPreviewAt || now - lastPreviewAt >= previewIntervalMs)
        return Decision(ocr = ocrDue, preview = previewDue)
    }
}
