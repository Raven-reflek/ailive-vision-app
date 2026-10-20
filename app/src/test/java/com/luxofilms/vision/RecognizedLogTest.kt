package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 首页"最近识别到的弹幕"的数据层:条数上限、新的在前、清空,以及用来判断"要不要重建列表"的 version。 */
class RecognizedLogTest {

    @Before fun setUp() = RecognizedLog.clear()

    @Test fun add_putsNewestFirst() {
        RecognizedLog.add(listOf("第一条"), uploaded = true)
        RecognizedLog.add(listOf("第二条"), uploaded = false)
        val r = RecognizedLog.recent()
        assertEquals(listOf("第二条"), r[0].lines)
        assertEquals(listOf("第一条"), r[1].lines)
        assertFalse(r[0].uploaded)
    }

    @Test fun add_ignoredBySource_isNeitherUploadedNorFailed() {
        // 上传成功但云端按"弹幕来源=协议采集"没使用:首页要标"未使用",不能当成"已上传",也不能当成"上传失败"
        RecognizedLog.add(listOf("被忽略的一条"), uploaded = true, ignored = true)
        val e = RecognizedLog.recent().single()
        assertTrue(e.ignored)
        assertFalse(e.uploaded)
    }

    @Test fun add_defaultIsNotIgnored() {
        RecognizedLog.add(listOf("正常"), uploaded = true)
        val e = RecognizedLog.recent().single()
        assertFalse(e.ignored)
        assertTrue(e.uploaded)
    }

    @Test fun entryIds_areUniqueAndIncreasing_soUiCanAnchorOnThem() {
        repeat(5) { RecognizedLog.add(listOf("行$it"), uploaded = true) }
        val ids = RecognizedLog.recent().map { it.id }          // 新的在前
        assertEquals("id 不重复", ids.size, ids.toSet().size)
        assertEquals("越新 id 越大", ids.sortedDescending(), ids)
    }

    @Test fun entryIds_staySameAcrossLaterAdds() {
        RecognizedLog.add(listOf("a"), uploaded = true)
        val idOfA = RecognizedLog.recent().first().id
        RecognizedLog.add(listOf("b"), uploaded = true)
        assertEquals("同一条在后续新增后 id 不变,界面才能认出它", idOfA, RecognizedLog.recent().last().id)
    }

    @Test fun add_keepsAtMost20_dropsOldest() {
        repeat(25) { RecognizedLog.add(listOf("行$it"), uploaded = true) }
        val r = RecognizedLog.recent()
        assertEquals(20, r.size)
        assertEquals(listOf("行24"), r.first().lines)     // 最新的
        assertEquals(listOf("行5"), r.last().lines)       // 最老的 5 条(0~4)已被挤掉
    }

    @Test fun add_emptyLines_isIgnored_andDoesNotBumpVersion() {
        val v = RecognizedLog.version()
        RecognizedLog.add(emptyList(), uploaded = true)
        assertEquals(v, RecognizedLog.version())
        assertTrue(RecognizedLog.recent().isEmpty())
    }

    @Test fun add_bumpsVersion() {
        val v = RecognizedLog.version()
        RecognizedLog.add(listOf("x"), uploaded = true)
        assertNotEquals(v, RecognizedLog.version())
    }

    @Test fun clear_emptiesList_andBumpsVersion() {
        RecognizedLog.add(listOf("x"), uploaded = true)
        val v = RecognizedLog.version()
        RecognizedLog.clear()
        assertTrue(RecognizedLog.recent().isEmpty())
        assertNotEquals("清空后首页应重建列表", v, RecognizedLog.version())
    }

    @Test fun clear_onAlreadyEmpty_doesNotBumpVersion() {
        val v = RecognizedLog.version()
        RecognizedLog.clear()
        assertEquals("本来就空,不该触发重建", v, RecognizedLog.version())
    }

    @Test fun version_unchangedWhenNothingHappens_soRefreshDoesNotRebuild() {
        RecognizedLog.add(listOf("x"), uploaded = true)
        val v = RecognizedLog.version()
        RecognizedLog.recent()
        RecognizedLog.recent()
        assertEquals(v, RecognizedLog.version())
    }
}
