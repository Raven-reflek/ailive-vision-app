package com.luxofilms.vision

import com.luxofilms.vision.RecognizedLog.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首页"最近识别到的弹幕"的数据层:条数上限、新的在前、清空,用来判断"要不要重建列表"的 version,
 * 以及 0.6.0 起识别与上传分开后的状态(排队中 / 已上传 / 未使用 / 超时丢弃)与"按 id 回写状态"。
 */
class RecognizedLogTest {

    @Before fun setUp() = RecognizedLog.clear()

    @Test fun add_putsNewestFirst() {
        RecognizedLog.add(listOf("第一条"), State.UPLOADED)
        RecognizedLog.add(listOf("第二条"), State.EXPIRED)
        val r = RecognizedLog.recent()
        assertEquals(listOf("第二条"), r[0].lines)
        assertEquals(listOf("第一条"), r[1].lines)
        assertEquals(State.EXPIRED, r[0].state)
    }

    @Test fun add_defaultIsQueued_uploaderUpdatesItLater() {
        val id = RecognizedLog.add(listOf("刚识别出来"))
        assertEquals(State.QUEUED, RecognizedLog.recent().single().state)
        RecognizedLog.update(id, State.UPLOADED)
        assertEquals(State.UPLOADED, RecognizedLog.recent().single().state)
    }

    @Test fun update_ignoredBySource_isNeitherUploadedNorExpired() {
        // 上传成功但云端按"弹幕来源=协议采集"没使用:首页要标"未使用",不能当成"已上传",也不能当成"超时丢弃"
        val id = RecognizedLog.add(listOf("被忽略的一条"))
        RecognizedLog.update(id, State.IGNORED)
        assertEquals(State.IGNORED, RecognizedLog.recent().single().state)
    }

    @Test fun update_expired() {
        val id = RecognizedLog.add(listOf("排了 45 秒也没传出去"))
        RecognizedLog.update(id, State.EXPIRED)
        assertEquals(State.EXPIRED, RecognizedLog.recent().single().state)
    }

    @Test fun update_touchesOnlyTheEntryWithThatId_andKeepsOrderIdAndLines() {
        val a = RecognizedLog.add(listOf("甲"))
        val b = RecognizedLog.add(listOf("乙", "丙"))
        val before = RecognizedLog.recent()
        RecognizedLog.update(a, State.UPLOADED)
        val after = RecognizedLog.recent()
        assertEquals(listOf(b, a), after.map { it.id })                    // 顺序不变,新的在前
        assertEquals(State.QUEUED, after[0].state)                          // b 没动
        assertEquals(State.UPLOADED, after[1].state)
        assertEquals(before.map { it.lines }, after.map { it.lines })
        assertEquals(before.map { it.ts }, after.map { it.ts })
    }

    @Test fun update_bumpsVersion_butSameStateOrUnknownIdDoesNot() {
        val id = RecognizedLog.add(listOf("x"))
        val v0 = RecognizedLog.version()
        RecognizedLog.update(id, State.UPLOADED)
        val v1 = RecognizedLog.version()
        assertNotEquals("状态变了首页要重建", v0, v1)
        RecognizedLog.update(id, State.UPLOADED)                            // 没变
        RecognizedLog.update(id + 100, State.EXPIRED)                       // 没有这个 id(被挤出 / 已清空)
        RecognizedLog.update(0L, State.EXPIRED)                             // add 空列表返回的 0
        assertEquals(v1, RecognizedLog.version())
    }

    @Test fun update_afterEntryPushedOutOf20_isANoOp() {
        val old = RecognizedLog.add(listOf("最老的"))
        repeat(20) { RecognizedLog.add(listOf("行$it")) }                   // 把它挤出 20 条上限
        val v = RecognizedLog.version()
        RecognizedLog.update(old, State.UPLOADED)
        assertEquals(20, RecognizedLog.recent().size)
        assertTrue(RecognizedLog.recent().all { it.state == State.QUEUED })
        assertEquals(v, RecognizedLog.version())
    }

    @Test fun update_afterClear_isANoOp() {
        val id = RecognizedLog.add(listOf("x"))
        RecognizedLog.clear()
        val v = RecognizedLog.version()
        RecognizedLog.update(id, State.UPLOADED)
        assertTrue(RecognizedLog.recent().isEmpty())
        assertEquals(v, RecognizedLog.version())
    }

    @Test fun entryIds_areUniqueAndIncreasing_soUiCanAnchorOnThem() {
        repeat(5) { RecognizedLog.add(listOf("行$it"), State.UPLOADED) }
        val ids = RecognizedLog.recent().map { it.id }          // 新的在前
        assertEquals("id 不重复", ids.size, ids.toSet().size)
        assertEquals("越新 id 越大", ids.sortedDescending(), ids)
    }

    @Test fun entryIds_staySameAcrossLaterAddsAndUpdates() {
        val idOfA = RecognizedLog.add(listOf("a"))
        RecognizedLog.add(listOf("b"), State.UPLOADED)
        RecognizedLog.update(idOfA, State.UPLOADED)
        assertEquals("同一条在后续新增 / 改状态后 id 不变,界面才能认出它", idOfA, RecognizedLog.recent().last().id)
    }

    @Test fun add_returnsTheIdOfTheNewEntry() {
        val id = RecognizedLog.add(listOf("a"))
        assertEquals(id, RecognizedLog.recent().first().id)
    }

    @Test fun add_keepsAtMost20_dropsOldest() {
        repeat(25) { RecognizedLog.add(listOf("行$it"), State.UPLOADED) }
        val r = RecognizedLog.recent()
        assertEquals(20, r.size)
        assertEquals(listOf("行24"), r.first().lines)     // 最新的
        assertEquals(listOf("行5"), r.last().lines)       // 最老的 5 条(0~4)已被挤掉
    }

    @Test fun add_emptyLines_isIgnored_returnsZero_andDoesNotBumpVersion() {
        val v = RecognizedLog.version()
        assertEquals(0L, RecognizedLog.add(emptyList()))
        assertEquals(v, RecognizedLog.version())
        assertTrue(RecognizedLog.recent().isEmpty())
    }

    @Test fun add_bumpsVersion() {
        val v = RecognizedLog.version()
        RecognizedLog.add(listOf("x"))
        assertNotEquals(v, RecognizedLog.version())
    }

    @Test fun clear_emptiesList_andBumpsVersion() {
        RecognizedLog.add(listOf("x"))
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
        RecognizedLog.add(listOf("x"))
        val v = RecognizedLog.version()
        RecognizedLog.recent()
        RecognizedLog.recent()
        assertEquals(v, RecognizedLog.version())
    }
}
