package com.luxofilms.vision

import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * 标定引导页(CameraAlignActivity)和正式采集(CaptureService)的 ImageAnalysis 必须用同一套分辨率策略。
 *
 * 2026-09-28 审查发现的硬伤:两处此前都没设分辨率,CameraX 各自挑的默认档位很低(约 640x480),
 * 弹幕区域经过"摆正整台手机屏幕→再裁出弹幕条"两次裁剪后,文字往往只剩几个像素高,认不出来。
 * 更严重的是——标定存的是**比例坐标**,不是像素坐标:如果两处分辨率的长宽比不一致(哪怕都够高清),
 * 同一组比例框住的会是画面里不同的区域,标定等于白标。两处统一从这里取同一个 ResolutionSelector,
 * 不能各写各的。
 */
object CameraTuning {
    /** 1080p:够看清弹幕文字,又不至于让透视变换/OCR 在中低端机上跑不动。 */
    private val TARGET_RESOLUTION = Size(1920, 1080)

    fun resolutionSelector(): ResolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(TARGET_RESOLUTION, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()
}
