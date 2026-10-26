package com.luxofilms.vision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳载荷:字段名 / 形状与契约 04 一字不差;"从未发生"为 null;电量、网络、摄像头状态的映射;stopped。
 * 载荷里只有数字与状态——这里也核对没有多余字段(图片、文字之类)混进去。
 */
class HeartbeatPayloadTest {
    private fun snap(
        frameAgo: Int? = 1, ocrAgo: Int? = 2, textAgo: Int? = 3, uploadOkAgo: Int? = 4,
        streak: Int = 0, uptime: Int? = 600,
    ) = CaptureStats.Snapshot(frameAgo, ocrAgo, textAgo, uploadOkAgo, streak, uptime)

    private fun build(
        state: String = Heartbeat.STATE_CAPTURING, battery: Int? = 87, charging: Boolean? = true, net: String? = "wifi",
        cameraPermission: Boolean = true, stats: CaptureStats.Snapshot = snap(),
    ) = Heartbeat.build(state, 14, battery, charging, net, cameraPermission, stats)

    // ── 形状 ──
    @Test fun json_hasExactlyTheContractFields() {
        val o = build().toJson()
        assertEquals(
            setOf(
                "v", "state", "app", "battery", "charging", "net", "camera",
                "frame_ago", "ocr_ago", "text_ago", "upload_ok_ago", "upload_fail_streak", "uptime",
            ),
            o.keySet(),
        )
    }

    @Test fun json_values() {
        val o = build(stats = snap(frameAgo = 5, ocrAgo = 6, textAgo = 7, uploadOkAgo = 20, streak = 2, uptime = 3600)).toJson()
        assertEquals(1, o.getInt("v"))
        assertEquals("capturing", o.getString("state"))
        assertEquals(14, o.getInt("app"))
        assertEquals(87, o.getInt("battery"))
        assertTrue(o.getBoolean("charging"))
        assertEquals("wifi", o.getString("net"))
        assertEquals("ok", o.getString("camera"))
        assertEquals(5, o.getInt("frame_ago"))
        assertEquals(6, o.getInt("ocr_ago"))
        assertEquals(7, o.getInt("text_ago"))
        assertEquals(20, o.getInt("upload_ok_ago"))
        assertEquals(2, o.getInt("upload_fail_streak"))
        assertEquals(3600, o.getInt("uptime"))
    }

    @Test fun json_roundTripsThroughText() {
        // 实际发出去的是字符串:解析回来字段不变,null 仍是 JSON null
        val parsed = JSONObject(build(stats = snap(textAgo = null)).toJson().toString())
        assertTrue(parsed.has("text_ago")); assertTrue(parsed.isNull("text_ago"))
        assertEquals(1, parsed.getInt("frame_ago"))
    }

    // ── 从未发生 = null(不是 0,也不是缺省) ──
    @Test fun neverHappened_isExplicitJsonNull_notZeroNotMissing() {
        val o = build(stats = snap(frameAgo = null, ocrAgo = null, textAgo = null, uploadOkAgo = null, uptime = 3)).toJson()
        for (k in listOf("frame_ago", "ocr_ago", "text_ago", "upload_ok_ago")) {
            assertTrue("$k 应存在", o.has(k))
            assertTrue("$k 应为 JSON null", o.isNull(k))
        }
        assertEquals(0, o.getInt("upload_fail_streak"))
    }

    @Test fun zeroAgo_isZero_notNull() {
        val o = build(stats = snap(frameAgo = 0, ocrAgo = 0, textAgo = 0, uploadOkAgo = 0)).toJson()
        for (k in listOf("frame_ago", "ocr_ago", "text_ago", "upload_ok_ago")) assertEquals(0, o.getInt(k))
    }

    @Test fun unreadableDeviceInfo_isOmitted() {
        val o = build(battery = null, charging = null, net = null).toJson()
        assertFalse(o.has("battery")); assertFalse(o.has("charging")); assertFalse(o.has("net"))
        assertTrue(o.has("camera"))       // 摄像头状态总是有的
    }

    @Test fun noUptime_isOmitted() {
        assertFalse(build(stats = snap(uptime = null)).toJson().has("uptime"))
    }

    // ── 采集计数直通载荷 ──
    @Test fun statsFlowIntoPayload() {
        val p = build(stats = snap(frameAgo = 9, ocrAgo = 8, textAgo = null, uploadOkAgo = 7, streak = 4, uptime = 100))
        assertEquals(9, p.frameAgo); assertEquals(8, p.ocrAgo); assertNull(p.textAgo); assertEquals(7, p.uploadOkAgo)
        assertEquals(4, p.uploadFailStreak); assertEquals(100, p.uptime)
    }

    // ── state ──
    @Test fun state_capturing_andStopped() {
        assertEquals("capturing", build(state = Heartbeat.STATE_CAPTURING).toJson().getString("state"))
        assertEquals("stopped", build(state = Heartbeat.STATE_STOPPED).toJson().getString("state"))
    }

    @Test fun stoppedBeat_stillCarriesTheRestOfThePayload() {
        val o = build(state = Heartbeat.STATE_STOPPED, battery = 40, charging = false).toJson()
        assertEquals(40, o.getInt("battery")); assertFalse(o.getBoolean("charging"))
        assertEquals(14, o.getInt("app"))
    }

    // ── 电量 ──
    @Test fun battery_percentFromLevelAndScale() {
        assertEquals(87, Heartbeat.batteryPercent(87, 100))
        assertEquals(50, Heartbeat.batteryPercent(127, 254))
        assertEquals(0, Heartbeat.batteryPercent(0, 100))
        assertEquals(100, Heartbeat.batteryPercent(100, 100))
        assertEquals(33, Heartbeat.batteryPercent(1, 3))      // 四舍五入
        assertEquals(67, Heartbeat.batteryPercent(2, 3))
    }

    @Test fun battery_invalidInput_isNull() {
        assertNull(Heartbeat.batteryPercent(-1, 100))         // 系统读不到时给 -1
        assertNull(Heartbeat.batteryPercent(50, -1))
        assertNull(Heartbeat.batteryPercent(50, 0))           // 不能除以 0
        assertNull(Heartbeat.batteryPercent(101, 100))        // 超过满电:脏值,不上报
        assertNull(Heartbeat.batteryPercent(-1, -1))
    }

    @Test fun charging_pluggedOrChargingOrFull() {
        assertTrue(Heartbeat.isCharging(status = 2, plugged = 0))      // BATTERY_STATUS_CHARGING
        assertTrue(Heartbeat.isCharging(status = 5, plugged = 0))      // BATTERY_STATUS_FULL
        assertTrue(Heartbeat.isCharging(status = 4, plugged = 1))      // 插着 AC,系统说"未充电"(电池保护)也算在供电
        assertTrue(Heartbeat.isCharging(status = 3, plugged = 2))      // USB
        assertFalse(Heartbeat.isCharging(status = 3, plugged = 0))     // DISCHARGING
        assertFalse(Heartbeat.isCharging(status = -1, plugged = 0))
    }

    // ── 网络 ──
    @Test fun net_mapping() {
        assertEquals("wifi", Heartbeat.netKind(connected = true, wifi = true, cellular = false, ethernet = false))
        assertEquals("cell", Heartbeat.netKind(connected = true, wifi = false, cellular = true, ethernet = false))
        assertEquals("eth", Heartbeat.netKind(connected = true, wifi = false, cellular = false, ethernet = true))
        assertEquals("other", Heartbeat.netKind(connected = true, wifi = false, cellular = false, ethernet = false))
        assertEquals("none", Heartbeat.netKind(connected = false, wifi = false, cellular = false, ethernet = false))
    }

    @Test fun net_notConnected_isNone_evenIfTransportFlagsSet() =
        assertEquals("none", Heartbeat.netKind(connected = false, wifi = true, cellular = true, ethernet = true))

    @Test fun net_wifiBeatsCellular_whenBothReported() =
        assertEquals("wifi", Heartbeat.netKind(connected = true, wifi = true, cellular = true, ethernet = false))

    // ── 摄像头 ──
    @Test fun camera_noPermission_winsOverEverything() {
        assertEquals("no_permission", Heartbeat.cameraState(false, frameAgo = 0, uptime = 10))
        assertEquals("no_permission", Heartbeat.cameraState(false, frameAgo = 500, uptime = 1000))
    }

    @Test fun camera_ok_whenRecentFrame() =
        assertEquals("ok", Heartbeat.cameraState(true, frameAgo = 2, uptime = 3000))

    @Test fun camera_stalled_whenNoFrameFor45sPlus() {
        assertEquals("ok", Heartbeat.cameraState(true, frameAgo = 45, uptime = 3000))       // 恰好 45 秒还不算
        assertEquals("stalled", Heartbeat.cameraState(true, frameAgo = 46, uptime = 3000))
    }

    @Test fun camera_neverFramed_usesUptime() {
        assertEquals("ok", Heartbeat.cameraState(true, frameAgo = null, uptime = 5))          // 刚启动,还没出帧
        assertEquals("ok", Heartbeat.cameraState(true, frameAgo = null, uptime = 45))
        assertEquals("stalled", Heartbeat.cameraState(true, frameAgo = null, uptime = 46))    // 启动这么久一帧都没有
    }

    @Test fun camera_neverFramed_noUptime_isOk() =
        assertEquals("ok", Heartbeat.cameraState(true, frameAgo = null, uptime = null))

    @Test fun camera_stateEndsUpInPayload() {
        assertEquals("no_permission", build(cameraPermission = false).toJson().getString("camera"))
        assertEquals("stalled", build(stats = snap(frameAgo = 120)).toJson().getString("camera"))
        assertEquals("stalled", build(stats = snap(frameAgo = null, uptime = 120)).toJson().getString("camera"))
        assertEquals("ok", build(stats = snap(frameAgo = 3)).toJson().getString("camera"))
    }
}
