package com.luxofilms.vision

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云端识别(0.7.0):开关跟着云端响应走;有新弹幕才传截图,只留最新一张、按间隔发;发失败交回文字通道。 */
class CloudOcrTest {
    private var now = 10_000L
    private fun item(s: String) = LineMerger.Item(text = "昵称:$s", content = s, atEdge = false, nick = "昵称")
    private fun img(b: Int) = byteArrayOf(b.toByte())

    @After fun tearDown() = CloudOcrState.reset()

    @Test fun stateFollowsCloudResponses_andOldServersLeaveItAlone() {
        assertFalse(CloudOcrState.enabled)
        CloudOcrState.noteResponse(JSONObject().put("accepted", 1))                 // 老云服务:没有这个字段
        assertFalse(CloudOcrState.enabled)
        CloudOcrState.noteResponse(JSONObject().put("cloud_ocr", true).put("cloud_min_interval", 3.0))
        assertTrue(CloudOcrState.enabled)
        assertEquals(3_000L, CloudOcrState.minIntervalMs)
        CloudOcrState.noteResponse(JSONObject().put("cloud_ocr", false).put("cloud_min_interval", JSONObject.NULL))
        assertFalse(CloudOcrState.enabled)
        CloudOcrState.noteResponse(JSONObject().put("cloud_ocr", true).put("cloud_min_interval", 0.01))
        assertEquals("夹到下限 1 秒", 1_000L, CloudOcrState.minIntervalMs)
    }

    @Test fun slot_keepsLatestImage_mergesItems_andRespectsTheGap() {
        val slot = FrameSlot { now }
        assertNull(slot.take(2_000))
        slot.offer(img(1), listOf(item("a")), 1)
        slot.offer(img(2), listOf(item("b")), 2)
        val first = slot.take(2_000)!!
        assertArrayEquals("只发最新的那张截图", img(2), first.jpeg)
        assertEquals(listOf("a", "b"), first.items.map { it.content })
        assertEquals(listOf(1L, 2L), first.tags)
        slot.offer(img(3), listOf(item("c")), 3)
        now += 1_000
        assertNull("没到间隔不发", slot.take(2_000))
        assertEquals(1_000L, slot.waitMs(2_000))
        now += 1_000
        assertArrayEquals(img(3), slot.take(2_000)!!.jpeg)
        assertEquals(-1L, slot.waitMs(2_000))
    }

    @Test fun slot_capsItems_andReturnsTheOverflow() {
        val slot = FrameSlot { now }
        val many = (0 until FrameSlot.MAX_ITEMS + 3).map { item("x$it") }
        val dropped = slot.offer(img(1), many, 7)
        assertEquals(listOf("x0", "x1", "x2"), dropped.map { it.first.content })
        assertEquals(FrameSlot.MAX_ITEMS, slot.clear()!!.items.size)
        assertNull(slot.clear())
    }

    @Test fun sender_sendsWhenDue_andFallsBackOnFailure() {
        CloudOcrState.noteResponse(JSONObject().put("cloud_ocr", true).put("cloud_min_interval", 2.0))
        val sent = ArrayList<FrameSlot.Job>()
        val fellBack = ArrayList<FrameSlot.Job>()
        var fail = false
        val sender = CloudFrameSender(FrameSlot { now },
            send = { job -> if (fail) throw RuntimeException("断网") else JSONObject().put("cloud", "cloud") },
            onSent = { job, _ -> sent.add(job) }, fallback = { fellBack.add(it) })
        assertEquals("没东西:空等", CloudFrameSender.IDLE_MS, sender.step())
        sender.offer(img(1), listOf(item("a")), 1)
        assertEquals(0L, sender.step())
        assertEquals(1, sent.size)
        sender.offer(img(2), listOf(item("b")), 2)
        assertEquals("刚发过:等间隔", 2_000L, sender.step())
        now += 2_000
        fail = true
        sender.step()
        assertEquals(listOf("b"), fellBack.single().items.map { it.content })
        assertEquals(1, sent.size)
    }
}
