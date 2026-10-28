package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 把 OCR 视觉行合回"弹幕条"。几何数据来自 2026-10-15 抖音游戏直播间录屏、真机 ML Kit 实测:行框高约 20px,同条内空隙约 10px、相邻两条约 20px。 */
class LineMergerTest {
    private val H = 20
    private val IMG_H = 600
    private val FULL = 380           // 折行的那一行写到的右边界

    private fun line(text: String, top: Int, right: Int = FULL, left: Int = 10) = OcrLine(text, left, top, right, top + H)

    private fun texts(items: List<LineMerger.Item>) = items.map { it.text }

    // ── 昵称(0.6.1):只有读得准的才带上,云端念"XX老板" ──
    @Test fun nickOf_colonReadCorrectly_givesTheNickname() {
        assertEquals("小明", LineMerger.nickOf("小明:能洗车吗"))
        assertEquals("小明", LineMerger.nickOf("2小明:能洗车吗"))                 // 粉丝徽章数字去掉
        assertEquals("小明", LineMerger.nickOf("15:00 小明:能洗车吗"))           // 行首计时器
        assertEquals("月岛出逃指南。", LineMerger.nickOf("月岛出逃指南。：介绍一下1号链接"))
    }

    @Test fun nickOf_unreliableOrAnonymous_isNull() {
        assertEquals(null, LineMerger.nickOf("能洗车吗"))                         // 没有冒号
        assertEquals(null, LineMerger.nickOf("抖音用户:能洗车吗"))                // 匿名
        assertEquals(null, LineMerger.nickOf("00:55抖音用户,能洗车吗"))
        assertEquals(null, LineMerger.nickOf("今天15:00还营业吗"))                // 时间里的冒号
        assertEquals(null, LineMerger.nickOf("何武器 来了"))                      // 进场提示:名字由云端从整行抠
        assertEquals(null, LineMerger.nickOf("小明:"))                            // 冒号后面没内容
    }

    @Test fun nickOf_colonLost_usesTheRememberedNickname() {
        val m = NickMemory().apply { learn("月岛出逃指南。") }
        assertEquals("月岛出逃指南。", LineMerger.nickOf("月乌出逃指南。固驰怎么样", m))
        assertEquals(null, LineMerger.nickOf("月岛出逃指南。固驰怎么样"), )       // 没记过:不猜
    }

    @Test fun mergedItemsCarryTheNickname() {
        val items = LineMerger.merge(listOf(line("小明:能洗车吗", 0, right = 200), line("固驰怎么样", 40, right = 200)), IMG_H)
        assertEquals(listOf("小明", null), items.map { it.nick })
        assertEquals(listOf("能洗车吗", "固驰怎么样"), items.map { it.content })
    }

    // ── 核心场景:录屏里的一屏弹幕(含换行) ──
    @Test fun realScreen_wrappedBubblesMergedIntoOneItemEach() {
        val lines = listOf(
            line("旮旯火影高手:主播可以放那首我是gay吗", 100, right = 370),
            line("哥真神:满火2奶我给你纯转圈", 140),
            line("过老头", 170, right = 120),                 // 续行:空隙 10px(≈0.5 倍行高)
            line("惆:主播你真的觉得这个布洛", 210),
            line("芬不卡充能吗", 240, right = 200),
            line("食不食油猫饼:咱风系啥时候能出", 280),
            line("个大哥啊", 310, right = 140),
            line("何武器 来了", 350, right = 150),
        )
        val items = LineMerger.merge(lines, IMG_H)
        assertEquals(
            listOf(
                "旮旯火影高手:主播可以放那首我是gay吗",
                "哥真神:满火2奶我给你纯转圈过老头",
                "惆:主播你真的觉得这个布洛芬不卡充能吗",
                "食不食油猫饼:咱风系啥时候能出个大哥啊",
                "何武器 来了",
            ),
            texts(items),
        )
        assertEquals("满火2奶我给你纯转圈过老头", items[1].content)       // 上传的是去掉昵称的内容
        assertEquals("何武器 来了", items[4].content)                       // 系统提示没有冒号,原样
    }

    @Test fun sameRealScreen_inputOrderDoesNotMatter() {
        val lines = listOf(
            line("哥真神:满火2奶我给你纯转圈", 140),
            line("过老头", 164, right = 120),
            line("旮旯火影高手:主播可以放那首我是gay吗", 100, right = 370),
        )
        assertEquals(2, LineMerger.merge(lines.reversed(), IMG_H).size)
        assertEquals(2, LineMerger.merge(lines.shuffled(java.util.Random(7)), IMG_H).size)
    }

    // ── 不该合并的 ──
    @Test fun entranceLineIsNeverContinuation_evenWhenCloseAndPrevIsFull() {
        val lines = listOf(
            line("凝:好像有点刮好像有点刮好像有点", 100),
            line("绘绯 来了", 130, right = 150),                      // 紧挨着,但是进场提示
        )
        assertEquals(2, LineMerger.merge(lines, IMG_H).size)
    }

    @Test fun ocrMisreadEntrance_業了_alsoTreatedAsSystemLine() {
        assertTrue(LineMerger.isSystemLine("北業了"))
        assertTrue(LineMerger.isSystemLine("Valerie (原神)業了"))
        assertTrue(LineMerger.isSystemLine("i5 来了 "))
        assertFalse(LineMerger.isSystemLine("主播什么时候来了解一下"))
    }

    @Test fun lineAfterEntrance_isNotMergedIntoIt() {
        val lines = listOf(
            line("绘绯 来了", 100, right = FULL),                     // 即便"写满",系统提示后面也不会有续行
            line("车衣能不能贴", 130, right = 200),
        )
        assertEquals(2, LineMerger.merge(lines, IMG_H).size)
    }

    @Test fun shortPrevLine_cannotHaveContinuation() {
        // 上一行只写了一半宽(右边缘远小于本帧最宽的一行)——说明它没折行,下一行是另一条
        val lines = listOf(
            line("宽宽宽宽宽宽宽宽宽宽宽宽宽", 100, right = FULL),
            line("贴膜多少钱", 160, right = 150),
            line("能贴吗", 190, right = 110),                          // 间距小,但上一行没写满
        )
        assertEquals(3, LineMerger.merge(lines, IMG_H).size)
    }

    @Test fun largeGap_isNewItem_evenIfPrevIsFull() {
        val lines = listOf(
            line("黑车贴车衣容易", 100, right = FULL),
            line("有胶纹吗", 100 + H + 20, right = 150),              // 条间空隙 20px ≈ 1.0 倍行高
        )
        assertEquals(2, LineMerger.merge(lines, IMG_H).size)
    }

    @Test fun smallGap_noColonLayout_isMerged() {
        // 没有 "昵称:" 的版式(只拍到内容):靠"上一行写满 + 间距小"合并
        val lines = listOf(
            line("白车贴车衣会发", 100, right = FULL),
            line("黄吗", 100 + H + 10, right = 90),                   // 同条内空隙 10px ≈ 0.5 倍行高
        )
        val items = LineMerger.merge(lines, IMG_H)
        assertEquals(listOf("白车贴车衣会发黄吗"), texts(items))
    }

    @Test fun nicknameLine_afterWrappedItem_startsNewItem() {
        val lines = listOf(
            line("哥真神:满火2奶我给你纯转圈", 100),
            line("过老头", 130, right = 120),
            line("惆:主播你真的觉得这个布洛", 170),                    // 间距小,但带昵称 → 新的一条
        )
        assertEquals(2, LineMerger.merge(lines, IMG_H).size)
    }

    // ── 同一行被切成并排片段 ──
    @Test fun sideBySideFragmentsOnOneRow_joinedInLeftToRightOrder() {
        val lines = listOf(
            OcrLine("昵称:内容内容", 90, 100, 380, 122),
            OcrLine("18", 10, 102, 40, 120),                            // 等级徽章数字,在最左
        )
        val items = LineMerger.merge(lines, IMG_H)
        assertEquals(1, items.size)
        assertEquals("18昵称:内容内容", items[0].text)
    }

    @Test fun englishWordsAcrossFragments_getASpace() {
        val items = LineMerger.merge(listOf(OcrLine("I", 10, 100, 20, 122), OcrLine("love you", 30, 100, 200, 122)), IMG_H)
        assertEquals("I love you", items[0].text)
    }

    // ── 昵称拆分 ──
    @Test fun splitNickname_basics() {
        assertEquals("主播可以放那首我是gay吗", LineMerger.splitNickname("旮旯火影高手:主播可以放那首我是gay吗"))
        assertEquals("好看", LineMerger.splitNickname("某某:好看"))
        assertEquals("好看", LineMerger.splitNickname("某某：好看"))            // 全角冒号
        assertEquals("好像有点制", LineMerger.splitNickname(":好像有点制"))      // 昵称丢了,只剩冒号
        assertEquals("好像有点制", LineMerger.splitNickname("●C: 好像有点制"))
    }

    @Test fun splitNickname_leavesTextAloneWhenNotANickname() {
        assertEquals("3:30 开始", LineMerger.splitNickname("3:30 开始"))           // 前面只有数字
        assertEquals("没有冒号的弹幕", LineMerger.splitNickname("没有冒号的弹幕"))
        assertEquals("这是一句真的非常非常长很长很长的话呀:冒号在后面", LineMerger.splitNickname("这是一句真的非常非常长很长很长的话呀:冒号在后面"))
        assertEquals("某某:", LineMerger.splitNickname("某某:"))                   // 冒号后没有内容,不剥空
    }

    @Test fun splitNickname_onlyFirstColonCounts() {
        assertEquals("比分 3:2 稳了", LineMerger.splitNickname("某某:比分 3:2 稳了"))
    }

    // ── 2026-10-22 真实直播测试(ADR-0037):计时器 / 计数 / 匿名昵称粘在行首 ──
    // 样例与云服务 tests/vision_field_cases.py 的 PREFIX_CASES / PREFIX_UNCHANGED 一一对应,两边规则必须保持一致
    private val prefixCases = listOf(
        "15:0053抖音用户主播PKT下,有助于提升人气和购" to "主播PKT下,有助于提升人气和购",
        "00:55抖音用户,弹总部都得先知道这改字,这个稍等主播为您详细解答" to "弹总部都得先知道这改字,这个稍等主播为您详细解答",
        "抖音用户,弹总部都得先知道" to "弹总部都得先知道",
        "抖音用户.价格多少" to "价格多少",
        "抖音用户;价格多少" to "价格多少",
        "抖音用户 价格多少" to "价格多少",
        "抖音用户价格多少" to "价格多少",
        "抖音用户:价格多少" to "价格多少",
        "抖音用户：价格多少" to "价格多少",
        "15:00 小明:能洗车吗" to "能洗车吗",
        "02:31小明:能洗车吗" to "能洗车吗",
    )
    private val prefixUnchanged = listOf(
        "价格:多少钱", "注意:施工前请验货", "抖音用户很多", "抖音用户都能看到吗", "3:30到店可以吗", "今天15:00还营业吗",
        "能洗车吗", "抖音用户", "抖音用户,", "小明,能洗车吗", "15:00", "00:55",
    )

    @Test fun stripPrefix_fieldCases() {
        for ((src, want) in prefixCases) assertEquals(src, want to true, LineMerger.stripPrefix(src))
    }

    @Test fun stripPrefix_leavesEverythingElseAlone() {
        for (t in prefixUnchanged) assertEquals(t, t to false, LineMerger.stripPrefix(t))
    }

    @Test fun splitNickname_stripsTimerAndAnonymousNickname() {
        for ((src, want) in prefixCases) assertEquals(src, want, LineMerger.splitNickname(src))
        assertEquals("好看", LineMerger.splitNickname("某某:好看"))            // 老行为不变
    }

    @Test fun splitNickname_timeInsideTheMessageIsKept() {
        assertEquals("3:30到店可以吗", LineMerger.splitNickname("3:30到店可以吗"))
        assertEquals("今天15:00还营业吗", LineMerger.splitNickname("今天15:00还营业吗"))
    }

    @Test fun timerOnlyRowsAreDroppedBeforeMerging() {
        val items = LineMerger.merge(listOf(line("15:00", 60, right = 90), line("甲:能洗车吗", 100), line("00:55 12", 140, right = 120)), IMG_H)
        assertEquals(listOf("甲:能洗车吗"), texts(items))
    }

    @Test fun timerPlusNicknameRowIsAStartLineAndNotMergedIntoThePreviousRow() {
        // 上一行写满、间距小,本来会被并成续行;但这一行带计时器 + 昵称,是新的一条
        val lines = listOf(line("甲:这是一条写满了的很长的弹幕内容呀呀呀呀", 100), line("15:00 小明:能洗车吗", 130))
        val items = LineMerger.merge(lines, IMG_H)
        assertEquals(2, items.size)
        assertEquals("能洗车吗", items[1].content)
    }

    @Test fun anonymousNicknameRowIsAStartLine() {
        val lines = listOf(line("甲:这是一条写满了的很长的弹幕内容呀呀呀呀", 100), line("抖音用户,价格多少", 130))
        val items = LineMerger.merge(lines, IMG_H)
        assertEquals(listOf("这是一条写满了的很长的弹幕内容呀呀呀呀", "价格多少"), items.map { it.content })
    }

    // ── 贴边标记 ──
    @Test fun atEdge_topAndBottomTouching() {
        val lines = listOf(
            line("尾巴被切掉的半条", 0, right = 200),                  // 贴着上沿
            line("甲:完整的一条", 100),
            line("乙:最后一条被下沿切掉", IMG_H - H),                  // 贴着下沿
        )
        val items = LineMerger.merge(lines, IMG_H)
        assertEquals(listOf(true, false, true), items.map { it.atEdge })
    }

    @Test fun atEdge_middleItemsAreNotEdge() {
        val items = LineMerger.merge(listOf(line("甲:一条", 100), line("乙:另一条", 140)), IMG_H)
        assertTrue(items.none { it.atEdge })
    }

    // ── 边界 ──
    @Test fun emptyAndBlankInput() {
        assertTrue(LineMerger.merge(emptyList(), IMG_H).isEmpty())
        assertTrue(LineMerger.merge(listOf(OcrLine("   ", 0, 0, 10, 10)), IMG_H).isEmpty())
    }

    @Test fun singleLine() {
        val items = LineMerger.merge(listOf(line("甲:你好呀", 100)), IMG_H)
        assertEquals(1, items.size); assertEquals("你好呀", items[0].content)
    }

    // ── 真机 ML Kit 实测框(2026-10-15,录屏第 14/15 帧,采集区图高 462) ──
    private fun r(text: String, l: Int, t: Int, rt: Int, b: Int) = OcrLine(text, l, t, rt, b)

    @Test fun realMlKitBoxes_frame14_wrappedBubblesMerged_andNothingElse() {
        val lines = listOf(
            r("令6", 57, 68, 98, 81), r(")水月菌:满命打这么艰难一", 109, 21, 478, 45),
            r("7安云让(琳娜、:6法2尼", 47, 106, 436, 126), r("给绯来了", 172, 66, 292, 84),
            r("07凝:好像有点刮", 49, 147, 323, 166), r("018APP:@晚上刷抖音我是说奥呵", 48, 185, 568, 209),
            r("音只火影高手:主播可以放那首我是gy吗", 48, 230, 589, 251),
            r("321", 56, 270, 106, 286), r("哥真神:滿火2奶我给你纯转圈", 209, 268, 601, 287),
            r("过老头0", 47, 296, 166, 319),                                   // 续行,框因表情变高(23px)
            r("20", 88, 339, 109, 357), r("v惘:主播你真的觉得这个布洛", 183, 339, 598, 357),
            r("芬不卡充能吗", 47, 368, 261, 391),                              // 空隙 11px,框高 23:修之前 0.45 的阈值合不上
            r("3叶来了", 56, 412, 243, 429),
        )
        val items = LineMerger.merge(lines, 462)
        assertEquals(9, items.size)
        assertTrue(items.any { it.content == "满火2奶我给你纯转圈过老头0".replace("满", "滿") })
        assertTrue(items.any { it.content.endsWith("这个布洛芬不卡充能吗") })
        assertEquals("拆开的行不应再单独出现", 0, items.count { it.text == "芬不卡充能吗" || it.text == "过老头0" })
    }

    @Test fun realMlKitBoxes_frame15_threeWrappedBubbles() {
        val lines = listOf(
            r("07云社(琳娜、:6法2尼", 49, 36, 436, 55), r("·2凝:好像有点刮", 49, 78, 323, 96),
            r("03APP:@晚上刷料4音我是说奧啊", 51, 115, 569, 138), r("音男火影高手:主播可以放那首我是gay吗", 48, 160, 589, 181),
            r("21", 90, 197, 108, 216), r("哥真神:满火2奶我给你纯转圈", 209, 199, 601, 217), r("过老头", 47, 226, 177, 252),
            r("920", 71, 267, 108, 287), r("惘:主播你真的觉得这个布者", 222, 269, 598, 287), r("芬不卡充能吗s", 47, 298, 261, 321),
            r("O30食不食油猫饼:咱风系啥时候能出", 49, 337, 606, 358), r("个天哥啊", 46, 365, 204, 391),
            r("·麦女孩的小火柴来了", 39, 407, 402, 433),
        )
        val items = LineMerger.merge(lines, 462)
        assertEquals(8, items.size)
        assertTrue(items.any { it.content.endsWith("纯转圈过老头") })
        assertTrue(items.any { it.content.endsWith("这个布者芬不卡充能吗s") })
        assertTrue(items.any { it.content.endsWith("能出个天哥啊") })    // 条 0:最后一条是进场提示,单独成条
    }

    @Test fun wideSystemAnnouncement_doesNotBreakCommentWrapping() {
        // 系统公告的容器比评论气泡宽(右边缘 714 vs 601)。若拿整帧最宽的行当"写满"基准,评论折行永远合不上。
        val lines = listOf(
            r("点点关注点点赞,深渊轻享会员排队预约,尊享插队", 48, 78, 714, 98),
            r("哥真神:满火2奶我给你纯转圈", 209, 199, 601, 217),
            r("过老头", 47, 226, 177, 252),
        )
        val items = LineMerger.merge(lines, 462)
        assertEquals(2, items.size)
        assertTrue(items[1].content.endsWith("纯转圈过老头"))
    }
}
