package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断包的 JSON(ADR-0038)。没有 JSON 库(JVM 单测里 org.json 是桩),这里只验转义和结构的关键片段。 */
class DiagJsonTest {
    @Test fun stringEscaping() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", DiagJson.str("a\"b\\c\nd\te"))
        assertEquals("\"\\u0001\"", DiagJson.str("\u0001"))
        assertEquals("\"月岛出逃指南。:介绍一下1号链接\"", DiagJson.str("月岛出逃指南。:介绍一下1号链接"))
    }

    @Test fun framesAreSerializedWithLinesElementsAndResult() {
        val frame = DiagFrame(
            ts = 1_700_000_000_000L, width = 900, height = 840, jpeg = byteArrayOf(1, 2, 3),
            lines = listOf(DiagLine("2月岛出逃指南。:介绍一下1号链接", listOf(100, 10, 722, 50),
                listOf(DiagElement("2", listOf(100, 10, 120, 50), "#ffffff"), DiagElement("月岛出逃指南。", listOf(145, 10, 400, 50), "#9eb0ff")))),
        ).withResult(listOf(LineMerger.Item("2月岛出逃指南。:介绍一下1号链接", "介绍一下1号链接", false, 1)), listOf("介绍一下1号链接"))
        val json = DiagJson.build(listOf(frame), listOf("frame_01.jpg"), linkedMapOf("app" to "vision 0.5.7 (12)"))
        for (needle in listOf("\"app\": \"vision 0.5.7 (12)\"", "\"image\": \"frame_01.jpg\"", "\"box\": [100, 10, 722, 50]", "\"ink\": \"#9eb0ff\"",
            "\"content\": \"介绍一下1号链接\"", "\"atEdge\": false", "\"rows\": 1", "\"uploaded\": [\"介绍一下1号链接\"]", "\"width\": 900")) {
            assertTrue(needle, json.contains(needle))
        }
        assertEquals("花括号 / 方括号要配平", json.count { it == '{' }, json.count { it == '}' })
        assertEquals(json.count { it == '[' }, json.count { it == ']' })
    }

    @Test fun framesWithoutImageOrBoxesUseNull() {
        val frame = DiagFrame(1L, 10, 10, null, listOf(DiagLine("甲", null, listOf(DiagElement("甲", null, null)))))
        val json = DiagJson.build(listOf(frame), listOf(null), emptyMap())
        assertTrue(json.contains("\"image\": null"))
        assertTrue(json.contains("\"box\": null"))
        assertTrue(json.contains("\"ink\": null"))
        assertFalse(json.contains("\"image\": \"null\""))
    }

    @Test fun emptyFrameListIsStillValid() {
        val json = DiagJson.build(emptyList(), emptyList(), mapOf("k" to "v"))
        assertTrue(json.contains("\"frames\": []"))
    }
}
