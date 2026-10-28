package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 昵称记忆(ADR-0038):只对读对过一次的昵称生效。 */
class NickMemoryTest {
    @Test fun emptyMemoryNeverStrips() {
        assertNull(NickMemory().strip("月岛出逃指南。介绍一下1号链接"))
    }

    @Test fun learnedNickname_isStrippedWithSeparators() {
        val m = NickMemory().apply { learn("月岛出逃指南。") }
        assertEquals("介绍一下1号链接", m.strip("月岛出逃指南。介绍一下1号链接"))
        assertEquals("介绍一下1号链接", m.strip("月岛出逃指南。、介绍一下1号链接"))
        assertEquals("异地能买吗", m.strip("月岛出逃指南。,异地能买吗"))
        assertEquals("异地能买吗", m.strip("月岛出逃指南 ; 异地能买吗"))
    }

    @Test fun badgeDigitsInFrontAreIgnoredOnBothSides() {
        val m = NickMemory().apply { learn("O2月岛出逃指南。") }
        assertEquals("固驰怎么样", m.strip("2月岛出逃指南。固驰怎么样"))
        assertEquals("固驰怎么样", m.strip("18月岛出逃指南固驰怎么样"))
        assertEquals("固驰怎么样", m.strip("月岛出逃指南固驰怎么样"))
    }

    @Test fun ocrDriftInTheNicknameIsTolerated_butNotUnrelatedText() {
        val m = NickMemory().apply { learn("月岛出逃指南。") }
        assertEquals("固驰怎么样", m.strip("月乌出逃指南。固驰怎么样"))         // 岛→乌
        assertEquals("固驰怎么样", m.strip("月岛出挑指南固驰怎么样"))           // 逃→挑
        assertNull(m.strip("今天几号链接多少钱"))
        assertNull(m.strip("月岛游客问一下多少钱"))
    }

    @Test fun nickOf_returnsTheNicknameAsReadCorrectly_notThisFramesTypo() {
        val m = NickMemory().apply { learn("O2月岛出逃指南。") }
        assertEquals("月岛出逃指南。", m.nickOf("月乌出逃指南。固驰怎么样"))
        assertNull(m.nickOf("今天几号链接多少钱"))
        assertNull(NickMemory().nickOf("月岛出逃指南。固驰怎么样"))
    }

    @Test fun nothingLeftAfterTheNickname_isNotAMatch() {
        val m = NickMemory().apply { learn("月岛出逃指南。") }
        assertNull(m.strip("月岛出逃指南。"))
        assertNull(m.strip("月岛出逃指南。、"))
    }

    @Test fun tooShortAnonymousOrLongNicknamesAreNotLearned() {
        val m = NickMemory()
        m.learn("小明")
        m.learn("2号")
        m.learn("一二三四五六七八九十一二三四五六")
        m.learn("   ")
        assertEquals(0, m.size())
    }

    @Test fun expiresAfterTheTtl_andRefreshesWhenSeenAgain() {
        var now = 0L
        val m = NickMemory(ttlMs = 1_000, clock = { now }).apply { learn("月岛出逃指南。") }
        now = 900
        assertTrue(m.matches("月岛出逃指南。你好呀老板"))
        m.learn("月岛出逃指南。")                                              // 又读对了一次:续期
        now = 1_800
        assertTrue(m.matches("月岛出逃指南。你好呀老板"))
        now = 3_000
        assertFalse(m.matches("月岛出逃指南。你好呀老板"))
        assertEquals(0, m.size())
    }

    @Test fun capacityEvictsTheOldest() {
        val m = NickMemory(max = 2)
        m.learn("月岛出逃指南"); m.learn("南京固驰小王"); m.learn("老张爱洗车啊")     // 昵称差一个字就会互相认成同一个(OCR 容错的代价),所以这里用互不相像的
        assertEquals(2, m.size())
        assertNull(m.strip("月岛出逃指南你好呀"))
        assertEquals("你好呀", m.strip("老张爱洗车啊你好呀"))
    }

    @Test fun learningRepeatedlyDoesNotDuplicate() {
        val m = NickMemory()
        repeat(5) { m.learn("月岛出逃指南。") }
        assertEquals(1, m.size())
    }
}
