package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 客户端模糊去重。文本样本来自 2026-10-15 游戏直播间真机截图里同一条弹幕被 OCR 认成的各种变体。 */
class DanmakuDeduperTest {
    private fun item(text: String, edge: Boolean = false) =
        LineMerger.Item(text = text, content = LineMerger.splitNickname(text), atEdge = edge)

    private fun DanmakuDeduper.upload(now: Long, vararg texts: String): List<String> {
        val fresh = select(texts.map { item(it) }, now)
        commit(fresh, now)
        return fresh.map { it.text }
    }

    // ── 基本 ──
    @Test fun sameItemSeenAgain_isSuppressed() {
        val d = DanmakuDeduper()
        assertEquals(listOf("甲:贴膜多少钱一平"), d.upload(0, "甲:贴膜多少钱一平"))
        assertTrue(d.upload(2_000, "甲:贴膜多少钱一平").isEmpty())
    }

    @Test fun differentQuestions_bothPass() {
        val d = DanmakuDeduper()
        assertEquals(2, d.upload(0, "甲:车衣能不能放二手车上", "乙:黑车贴车衣容易有胶纹吗").size)
    }

    @Test fun punctuationSpacesAndBarNoise_ignored() {
        val d = DanmakuDeduper()
        d.upload(0, "漆带下来了吗")
        assertTrue(d.upload(2_000, "|漆带下来了吗").isEmpty())      // 被切掉的上一行边缘被认成竖线
        assertTrue(d.upload(4_000, "漆带 下来了吗。").isEmpty())
    }

    // ── 截图里的真实 OCR 变体 ──
    @Test fun ocrVariantsOfTheSameDanmaku_askedOnlyOnce() {
        val d = DanmakuDeduper()
        val variants = listOf(
            "旮旯火影高手:主播可以放那首我是gay吗",
            "查興火樂影高手:生播可以放那首我9y吗",
            "對咒火戰高手生指可以放那首我9ay門",
            "查党火影群手:主進可以放那首動是gay吗",
            "查見火影葡手:主撫可以放那首是ey吗",
        )
        val asked = variants.sumOf { d.upload(variants.indexOf(it) * 2_000L, it).size }
        // 第 3 个变体冒号丢了、昵称也认成乱码,剥不掉昵称,相似度只有 0.52。阈值不为它放宽:
        // "白车贴车衣会发黄吗"与"黑车贴车衣容易有胶纹吗"这两条不同的真问题相似度也是 0.5,会被误吞。拿不准就多问一次。
        assertTrue("5 个变体应最多问 2 次,实际 $asked", asked <= 2)
    }

    @Test fun lostLastCharacter_treatedAsSame() {
        val d = DanmakuDeduper()
        d.upload(0, "安云沚:6法2尼")
        assertTrue(d.upload(2_000, "持開是(淋缃、:6法2").isEmpty())    // 昵称认成乱码、"尼" 被切掉
    }

    // ── 不能误吞 ──
    @Test fun shortDifferentQuestions_areNotMerged() {
        val d = DanmakuDeduper()
        assertEquals(1, d.upload(0, "甲:多少钱").size)
        assertEquals(1, d.upload(2_000, "乙:多久钱").size)              // ≤3 字走更严的阈值
    }

    @Test fun unrelatedLongItems_notMerged() {
        assertTrue(DanmakuDeduper.similarity(
            DanmakuDeduper.normalize("车衣会景影响原车漆颜色吗"), DanmakuDeduper.normalize("白车贴车衣会发黄吗")) < DanmakuDeduper.SIMILARITY)
    }

    @Test fun twoItemsInOneBatch_duplicatesCollapsed() {
        val d = DanmakuDeduper()
        assertEquals(1, d.select(listOf(item("甲:贴膜多少钱一平"), item("乙:贴膜多少钱一平呢")), 0).size)
    }

    // ── 时间窗与上传失败 ──
    @Test fun afterWindowExpires_sameTextCanBeAskedAgain() {
        val d = DanmakuDeduper(windowMs = 60_000)
        d.upload(0, "甲:贴膜多少钱一平")
        assertEquals(1, d.upload(61_000, "甲:贴膜多少钱一平").size)
    }

    @Test fun keepsSeeingItWhileOnScreen_neverExpires() {
        val d = DanmakuDeduper(windowMs = 60_000)
        d.upload(0, "甲:贴膜多少钱一平")
        var t = 0L
        repeat(50) { t += 2_000; assertTrue("t=$t", d.upload(t, "甲:贴膜多少钱一平").isEmpty()) }   // 画面上停留 100 秒,每帧都看到
    }

    @Test fun notCommitted_isRetriedNextFrame() {
        val d = DanmakuDeduper()
        assertEquals(1, d.select(listOf(item("甲:贴膜多少钱一平")), 0).size)       // 选中了,但上传失败,没 commit
        assertEquals(1, d.select(listOf(item("甲:贴膜多少钱一平")), 2_000).size)
    }

    @Test fun maxSeen_oldestEvicted() {
        val d = DanmakuDeduper(maxSeen = 3)
        val qs = listOf("甲:贴膜多少钱一平方", "乙:车衣会不会影响雷达", "丙:原厂漆能不能保留", "丁:晚上几点下播啊", "戊:店在什么位置呢")
        qs.forEachIndexed { i, q -> d.upload(i * 100L, q) }
        assertEquals(1, d.upload(600, qs[0]).size)                                 // 最老的已被挤出,又能问了
        assertTrue(d.upload(700, qs[4]).isEmpty())                                 // 最新的还在
    }

    // ── 贴边(半截)条目 ──
    @Test fun edgeFragment_ofAlreadySeenItem_isDropped() {
        val d = DanmakuDeduper()
        d.upload(0, "哥真神:满火2奶我给你纯转圈过老头")
        // 它滚到上沿被切掉了前半:只剩 "过老头"
        assertTrue(d.select(listOf(item("过老头", edge = true)), 2_000).isEmpty())
    }

    @Test fun edgeFragmentWithBarNoise_ofSeenItem_isDropped() {
        val d = DanmakuDeduper()
        d.upload(0, "车衣会不会把补漆带下来")
        assertTrue(d.select(listOf(item("|漆带下来", edge = true)), 2_000).isEmpty())   // 第一次复现里被单独问了两次的那个
    }

    @Test fun edgeFragment_headOfSeenItem_isDropped() {
        val d = DanmakuDeduper()
        d.upload(0, "哥真神:满火2奶我给你纯转圈过老头")
        assertTrue(d.select(listOf(item("哥真神:满火2奶我给你", edge = true)), 2_000).isEmpty())   // 被下沿切掉了后半
    }

    @Test fun edgeUnknown_isHeldThenReleasedOnThirdSighting() {
        val d = DanmakuDeduper()
        val e = item("甲:标定框正好切在最新这一条上", edge = true)
        assertTrue(d.select(listOf(e), 0).isEmpty())
        assertTrue(d.select(listOf(e), 2_000).isEmpty())
        assertEquals(1, d.select(listOf(e), 4_000).size)               // 连续 3 帧都在 → 不是一晃而过,放行
    }

    @Test fun edgeUnknown_scrollingFragmentsNeverReleased() {
        val d = DanmakuDeduper()
        // 一晃而过:每帧露出的半截不一样,且中间隔得久,不能累计成"稳定"
        assertTrue(d.select(listOf(item("半截甲甲甲", edge = true)), 0).isEmpty())
        assertTrue(d.select(listOf(item("完全不同的半截乙乙乙", edge = true)), 2_000).isEmpty())
        assertTrue(d.select(listOf(item("又一个无关的半截丙丙丙丙", edge = true)), 4_000).isEmpty())
    }

    @Test fun edgeUnknown_pendingExpires() {
        val d = DanmakuDeduper()
        val e = item("甲:标定框正好切在最新这一条上", edge = true)
        d.select(listOf(e), 0); d.select(listOf(e), 2_000)
        assertTrue(d.select(listOf(e), 20_000).isEmpty())              // 隔了太久,计数从头来
    }

    @Test fun completeVersion_afterHeldFragment_isUploadedImmediately() {
        val d = DanmakuDeduper()
        assertTrue(d.select(listOf(item("甲:贴膜多少钱一", edge = true)), 0).isEmpty())
        assertEquals(1, d.select(listOf(item("甲:贴膜多少钱一平"), ), 2_000).size)   // 看全了(不贴边),直接放行
    }

    // ── 太短的不要 ──
    @Test fun tooShortAfterNormalize_dropped() {
        val d = DanmakuDeduper()
        assertTrue(d.select(listOf(item("甲:!!"), item("乙:|")), 0).isEmpty())
    }

    // ── 整体回放:2026-10-15 游戏直播间真机截图,5 次采样共 21 行,画面里实际约 8 条不同的 ──
    @Test fun replayRealPhoneScreenshot_askedFarFewerTimesThan21() {
        val frames = listOf(
            listOf("持開是(淋缃、:6法2", ":好像有点制", "APP: P晚上閱料時我是说奥啊", "查興火樂影高手:生播可以放那首我9y吗"),
            listOf("業:好像有点劃", "APP:@晚上刷料當我是说啊", "對咒火戰高手生指可以放那首我9ay門", "參哥真:满火2奶我给你纯转圈过者头", "北業了"),
            listOf("查党火影群手:主進可以放那首動是gay吗", "哥真菩:滌火2奶我给你纯转國过老头。", "情:主播你真的觉得这个布芬不卡充,吗", "CCT供惯子 来了"),
            listOf("查見火影葡手:主撫可以放那首是ey吗", "參哥真棒:溝2奶我给你纯转圖过老头。", "●C: 主樂你真的觉得这个布濟芬不卡充能吗"),
            listOf("憫:主播你真的觉得这个布海", "珠不食油猫饼:咱风系隂时候能出", "个太期", "Valerie (原神)業了", "原報"),
        )
        val d = DanmakuDeduper()
        val asked = ArrayList<String>()
        frames.forEachIndexed { i, f -> asked += d.upload(i * 2_000L, *f.toTypedArray()) }
        println("回放:上传 ${asked.size} 条(原来 21 条)→ $asked")
        // 剩下的 13 条里:3 条进场提示;"个太期""原報"是折行半截(这份回放没有位置信息,真实流程里 LineMerger 会并回上一条)
        assertTrue("应明显少于 21,实际 ${asked.size}: $asked", asked.size <= 13)
        // 同一条"主播可以放那首…gay吗"的 5 个变体最多问 2 次(见上面变体用例)
        assertTrue(asked.count { it.contains("放那首") } <= 2)
    }

    // ── 工具函数 ──
    @Test fun similarity_properties() {
        assertEquals(1f, DanmakuDeduper.similarity("abc", "abc"), 1e-6f)
        assertEquals(0f, DanmakuDeduper.similarity("abc", "xyz"), 1e-6f)
        assertEquals(0f, DanmakuDeduper.similarity("", "abc"), 1e-6f)
        assertEquals(DanmakuDeduper.similarity("abcde", "abxde"), DanmakuDeduper.similarity("abxde", "abcde"), 1e-6f)
        assertFalse(DanmakuDeduper.same("贴膜多少钱", "完全无关的一句话", edge = false))
    }
}
