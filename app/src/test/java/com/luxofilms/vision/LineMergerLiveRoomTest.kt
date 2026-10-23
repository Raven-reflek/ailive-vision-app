package com.luxofilms.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-23 车膜直播间(南京固驰全球尊享店)第二轮真机测试的排版(ADR-0038)。**没有原始画面**(预览页禁止截屏、不落盘),
 * 坐标按直播间截图(900×2000)目测:字高≈40px;单条弹幕行距≈70(空隙 0.75 倍)、折行续行行距≈62(0.55 倍);
 * 起始行带粉丝徽章,文字起点在 x≈100(徽章数字被读出来)或 x≈145(没读出来),续行与平台提示贴着气泡左缘 x≈50。
 * 昵称 `月岛出逃指南。` 后的冒号常被 OCR 读成 `、` `,` 或丢掉。阈值要等"诊断包"拿到真实帧再校准;这里把已知现象固化成用例。
 */
class LineMergerLiveRoomTest {
    private val H = 40
    private val NICK = "2月岛出逃指南。"           // 前面的 2 是粉丝等级徽章数字
    private val IMG_H = 840

    private fun ln(text: String, cy: Int, left: Int, right: Int) = OcrLine(text, left, cy - 1000 - H / 2, right, cy - 1000 + H / 2)

    /** 一屏:8 条弹幕 / 进场提示,其中三条折行、一条进场提示折行。seps 是 6 条观众弹幕里昵称后的分隔符(OCR 读出来的样子)。 */
    private fun screen(seps: List<String>, pitch: Int = 70, wrapPitch: Int = 62): List<OcrLine> {
        var y = 1048
        val out = ArrayList<OcrLine>()
        out += ln(NICK + seps[0] + "介绍一下1号链接", y, 145, 722); y += pitch
        out += ln(NICK + seps[1] + "介绍一下2号链接", y, 100, 728); y += pitch
        out += ln(NICK + seps[2] + "蓝晶灵BS7是什", y, 145, 700); y += wrapPitch
        out += ln("么", y, 50, 78); y += pitch
        out += ln(NICK + seps[3] + "蓝晶灵全车窗膜", y, 100, 705); y += wrapPitch
        out += ln("是什么", y, 50, 160); y += pitch
        out += ln("2月岛出逃指南。 来了·你的粉丝 经", y, 145, 710); y += wrapPitch
        out += ln("常看你", y, 50, 160); y += pitch
        out += ln(NICK + seps[4] + "我的车能贴吗,", y, 100, 685); y += wrapPitch
        out += ln("什么车型都能贴吗", y, 50, 345); y += pitch
        out += ln(NICK + seps[5] + "异地能买吗", y, 145, 628); y += pitch
        out += ln("荣耀剧场 来了·在上海市", y, 100, 548)
        return out
    }

    private val COLON = "："
    private fun contents(items: List<LineMerger.Item>) = items.map { it.content }

    @Test fun everyColonRead_eightSeparateItems_entranceNeverAbsorbsTheMessageAbove() {
        val items = LineMerger.merge(screen(List(6) { COLON }), IMG_H)
        assertEquals(
            listOf(
                "介绍一下1号链接", "介绍一下2号链接", "蓝晶灵BS7是什么", "蓝晶灵全车窗膜是什么",
                "2月岛出逃指南。 来了·你的粉丝 经",                       // 进场提示原样交给服务端
                "我的车能贴吗,什么车型都能贴吗", "异地能买吗", "荣耀剧场 来了·在上海市",
            ),
            contents(items),
        )
        assertEquals("折行的三条各合并了两个视觉行", listOf(1, 1, 2, 2, 1, 2, 1, 1), items.map { it.rows })
    }

    @Test fun colonLostEverywhere_stillEightItems_notThreeMessagesInOne() {
        // 总部大屏的 "…1号链接…2号链接…蓝晶灵BS7是什"(三条合一)就是这种情况:旧阈值 0.75 把行距 70(0.75 倍)的两条并了
        val items = LineMerger.merge(screen(List(6) { "" }), IMG_H)
        assertEquals(8, items.size)
        assertEquals(NICK + "介绍一下1号链接", items[0].text)
        assertEquals(NICK + "介绍一下2号链接", items[1].text)
        assertEquals(NICK + "蓝晶灵BS7是什么", items[2].text)
        assertEquals(NICK + "我的车能贴吗,什么车型都能贴吗", items[5].text)
        assertEquals("荣耀剧场 来了·在上海市", items[7].text)
        assertTrue("落单的 常看你 不上传", items.none { it.text.contains("常看你") && it.rows == 1 && !it.text.contains("来了") })
    }

    @Test fun mixedMisreadSeparators_sameEightItems() {
        val items = LineMerger.merge(screen(listOf("", "、", COLON, "", ",", ",")), IMG_H)
        assertEquals(8, items.size)
    }

    @Test fun nicknameLearnedOnce_isStrippedWhenTheColonIsLaterLost() {
        val nicks = NickMemory()
        LineMerger.merge(screen(List(6) { COLON }), IMG_H, nicks)                   // 这一帧冒号都读对了:记住昵称
        assertEquals(1, nicks.size())
        val items = LineMerger.merge(screen(listOf("", "、", ",", "", ".", "")), IMG_H, nicks)
        assertEquals(
            listOf("介绍一下1号链接", "介绍一下2号链接", "蓝晶灵BS7是什么", "蓝晶灵全车窗膜是什么",
                "2月岛出逃指南。 来了·你的粉丝 经", "我的车能贴吗,什么车型都能贴吗", "异地能买吗", "荣耀剧场 来了·在上海市"),
            contents(items),
        )
    }

    @Test fun badgeDigitsReadDifferently_stillStripped() {
        val nicks = NickMemory().also { it.learn("2月岛出逃指南。") }
        for (text in listOf("O2月岛出逃指南。,异地能买吗", "月岛出逃指南。我的车能贴吗,", "18月岛出逃指南异地能买吗", "2月乌出逃指南。固驰怎么样")) {
            val content = LineMerger.merge(listOf(ln(text, 1100, 145, 700)), IMG_H, nicks).single().content
            assertFalse(text, content.contains("出逃指南") || content.contains("出逃指"))
        }
    }

    @Test fun startRowsAtTheGapOfARealNewMessage_neverMerge_acrossPitchNoise() {
        // 行距 66~90(空隙 0.65~1.25 倍)的相邻弹幕都不能并;旧阈值 0.75 在 ≤ 70 时全并
        for (pitch in listOf(66, 68, 70, 72, 76, 80, 90)) {
            val rows = listOf(
                ln(NICK + "介绍一下1号链接", 1048, 145, 722),
                ln(NICK + "介绍一下2号链接", 1048 + pitch, 145, 728),
                ln(NICK + "固驰是什么牌子", 1048 + 2 * pitch, 145, 700),
            )
            assertEquals("行距 $pitch", 3, LineMerger.merge(rows, IMG_H).size)
        }
    }

    @Test fun wrappedContinuation_mergesAcrossPitchNoise() {
        // 续行行距 58~64(空隙 0.45~0.6 倍):贴着左缘(外缩)或间距很小,都要并回上一条
        for (wrap in listOf(58, 60, 62, 64)) {
            val rows = listOf(
                ln(NICK + "蓝晶灵BS7是什", 1048, 145, 700),
                ln("么", 1048 + wrap, 50, 78),
            )
            assertEquals("续行行距 $wrap", listOf(NICK + "蓝晶灵BS7是什么"), LineMerger.merge(rows, IMG_H).map { it.text })
        }
    }

    @Test fun indentedStartRowIsNeverAContinuation_evenWhenTheGapIsTiny() {
        val rows = listOf(
            ln("么", 1000, 50, 78),                                    // 同帧里有贴着左缘的行,基准 x=50
            ln(NICK + "介绍一下1号链接", 1070, 145, 722),
            ln(NICK + "介绍一下2号链接", 1070 + 56, 145, 728),          // 空隙 0.4 倍,但右缩 95px ≥ 1.5 倍行高:带徽章的起始行
        )
        val items = LineMerger.merge(rows, IMG_H)
        assertEquals(listOf("介绍一下1号链接", "介绍一下2号链接"), items.map { it.content.removePrefix("2月岛出逃指南。") }.filter { it.startsWith("介绍") })
    }

    @Test fun orphanShortContinuation_isDropped_butOnlyWhenTheFrameShowsBadgeIndentation() {
        val withIndent = listOf(ln(NICK + "介绍一下1号链接", 1048, 145, 722), ln("是什么", 1300, 50, 160))
        assertEquals(1, LineMerger.merge(withIndent, IMG_H).size)
        val longOrphan = listOf(ln(NICK + "介绍一下1号链接", 1048, 145, 722), ln("什么车型都能贴吗", 1300, 50, 345))
        assertEquals("较长的落单行照常上传,交给服务端近似重复过滤", 2, LineMerger.merge(longOrphan, IMG_H).size)
        val noIndent = listOf(ln("能贴吗", 1048, 50, 110), ln("多少钱", 1200, 50, 110))
        assertEquals("没有缩进线索的版式里,短行可能就是一条短弹幕", 2, LineMerger.merge(noIndent, IMG_H).size)
    }

    @Test fun newEnterFormats_areSystemLines_butQuestionsAndPlainSpeechAreNot() {
        for (t in listOf("荣耀剧场 来了·在上海市", "荣耀剧场来了在上海市", "月岛出逃指南。 来了·你的粉丝 经", "2月岛出逃指南。 来了·你的粉丝 经常看你",
            "常看你", "经常看你", "三常看你。", "4荣耀剧场 来7·在上海市", "xx来了")) assertTrue(t, LineMerger.isSystemLine(t))
        for (t in listOf("我来了解北京市", "来了在北京市能贴吗", "主播什么时候来了解一下", "你的粉丝团怎么加入", "介绍一下1号链接",
            "我来了,你的粉丝团怎么加入吗", "我来了你的粉丝是谁?")) assertFalse(t, LineMerger.isSystemLine(t))       // 带问句形态:观众的话
    }

    @Test fun flushRowFarBelow_isNotAContinuation_evenWithOutdentEvidence() {
        // 外缩只是"可以并"的证据,空隙太大(这里 1.5 倍行高)仍然是两条:上一条后面隔了一大段,这一行是别的东西
        val rows = listOf(ln(NICK + "蓝晶灵BS7是什", 1048, 145, 700), ln("什么车型都能贴吗", 1048 + 100, 50, 345))
        assertEquals(2, LineMerger.merge(rows, IMG_H).size)
    }

    @Test fun entranceRowAfterAFullMessageRow_isAlwaysItsOwnItem() {
        val rows = listOf(ln(NICK + "异地能买吗", 1048, 145, 722), ln("荣耀剧场 来了·在上海市", 1048 + 62, 100, 548))
        assertEquals(2, LineMerger.merge(rows, IMG_H).size)
    }
}
