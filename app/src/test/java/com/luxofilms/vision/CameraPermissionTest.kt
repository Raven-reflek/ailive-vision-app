package com.luxofilms.vision

import com.luxofilms.vision.CameraPermission.Outcome
import com.luxofilms.vision.CameraPermission.StartFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 摄像头权限(P1-21):授权结果怎么分类、被拒后的引导文案、采集起不来时告警按真实原因写。 */
class CameraPermissionTest {

    // ── 授权窗口返回后的三种结果 ──

    @Test fun granted_isGranted_regardlessOfRationale() {
        assertEquals(Outcome.GRANTED, CameraPermission.outcome(granted = true, shouldShowRationale = false))
        assertEquals(Outcome.GRANTED, CameraPermission.outcome(granted = true, shouldShowRationale = true))
    }

    @Test fun deniedOnce_canAskAgain() {
        // 第一次拒绝:系统仍愿意再弹窗(rationale 为 true)
        assertEquals(Outcome.DENIED_CAN_ASK_AGAIN, CameraPermission.outcome(granted = false, shouldShowRationale = true))
    }

    @Test fun deniedWithDontAskAgain_isPermanent() {
        // 选了"不再询问"/第二次拒绝/策略禁用:拒绝后 rationale 仍为 false,弹不出窗,只能去系统设置
        assertEquals(Outcome.DENIED_PERMANENT, CameraPermission.outcome(granted = false, shouldShowRationale = false))
    }

    // ── 引导弹窗 ──

    @Test fun permanentDenied_dialogPointsToSettings() {
        assertEquals("去设置", CameraPermission.dialogPositive(Outcome.DENIED_PERMANENT))
        val msg = CameraPermission.dialogMessage(Outcome.DENIED_PERMANENT)
        assertTrue(msg, msg.contains("系统设置"))
        assertTrue(msg, msg.contains("相机"))
    }

    @Test fun retryableDenied_dialogOffersReauthorize_andAdvisesWhileInUse() {
        assertEquals("重新授权", CameraPermission.dialogPositive(Outcome.DENIED_CAN_ASK_AGAIN))
        val msg = CameraPermission.dialogMessage(Outcome.DENIED_CAN_ASK_AGAIN)
        assertTrue(msg, msg.contains("使用应用时允许"))
        assertFalse("还能再问时不该让店员去设置", msg.contains("系统设置"))
    }

    @Test fun dialogTexts_differByOutcome() {
        assertNotEquals(
            CameraPermission.dialogMessage(Outcome.DENIED_CAN_ASK_AGAIN),
            CameraPermission.dialogMessage(Outcome.DENIED_PERMANENT),
        )
    }

    // ── 采集起不来:按真实原因分 ──

    @Test fun startFailure_withoutCameraPermission_isPermissionProblem() {
        assertEquals(StartFailure.CAMERA_PERMISSION, CameraPermission.startFailure(cameraGranted = false))
    }

    @Test fun startFailure_withCameraPermission_isBackgroundRestriction() {
        assertEquals(StartFailure.BACKGROUND_RESTRICTED, CameraPermission.startFailure(cameraGranted = true))
    }

    @Test fun permissionAlert_tellsToEnableCamera_notAboutReboot() {
        val (title, text) = CameraPermission.alertContent(StartFailure.CAMERA_PERMISSION, null)
        assertTrue(title, title.contains("摄像头权限"))
        assertFalse("权限被关不该说成重启后没能恢复:$title", title.contains("重启"))
        assertTrue(text, text.contains("相机"))
        assertTrue(text, text.contains("开始采集"))
    }

    @Test fun backgroundAlert_keepsOriginalWording_andAppendsReason() {
        val (title, text) = CameraPermission.alertContent(StartFailure.BACKGROUND_RESTRICTED, "SecurityException")
        assertEquals("手机重启后弹幕采集没能自动恢复", title)
        assertEquals("点这里打开 APP,再点“开始采集”。(SecurityException)", text)
        assertEquals("点这里打开 APP,再点“开始采集”。", CameraPermission.alertContent(StartFailure.BACKGROUND_RESTRICTED, null).second)
    }

    @Test fun twoAlerts_areDistinguishable() {
        assertNotEquals(
            CameraPermission.alertContent(StartFailure.CAMERA_PERMISSION, null).first,
            CameraPermission.alertContent(StartFailure.BACKGROUND_RESTRICTED, null).first,
        )
    }
}
