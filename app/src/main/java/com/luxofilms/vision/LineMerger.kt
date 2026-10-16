package com.luxofilms.vision

/** OCR 识别出的一行文字及其位置(像素,相对被识别的那张图)。不依赖 android.graphics,可单元测试。 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/**
 * 把 OCR 的"视觉行"还原成"弹幕条"。
 *
 * 为什么需要:ML Kit 按视觉行返回文字,一条弹幕太长在屏幕上折成两行时,会变成两个条目;
 * 以前每行都当一条弹幕上传、各自触发一次提问(2026-10-15 真机:5 条弹幕被问了 12 次)。
 *
 * 判断依据(实测来自抖音游戏直播间,气泡式弹幕;ML Kit 行框高约 18~20px):
 * 同条内续行的空隙约 9~11px(≈0.43~0.54 倍行高),相邻两条之间约 20px 以上(≈1.0~1.2 倍)。录屏里按墨迹量到的行距差只有几个像素,
 * 受透视/缩放/框抖动影响,单靠间距不够稳,所以以文字特征为主、间距为辅:
 * 1. 起始行特征:出现 "昵称:" 前缀,或是"xx 来了"这类系统提示 → 一定是新的一条;
 * 2. 续行特征:不是起始行 + 上一行不是系统提示 + 上一行写满了(折行必然写到右边界)+ 与上一行间距小 → 并入上一条;
 * 3. 拿不准一律不合并——多问一次的代价,小于把两条不同的弹幕拼成一句。
 */
object LineMerger {
    /**
     * 合并后的一条弹幕。
     * text:整条原文(含昵称,首页/预览核对用);content:去掉"昵称:"前缀后的内容(上传用);
     * atEdge:贴着弹幕区上沿或下沿,这一条很可能被裁掉了一截(不完整)。
     */
    data class Item(val text: String, val content: String, val atEdge: Boolean)

    /** 行间空隙 ÷ 行高 ≤ 此值才算同一条。真机 ML Kit 行框实测:同条内 0.43~0.54、条与条之间 1.0~1.2,取中间偏小。 */
    const val MAX_GAP_RATIO = 0.75f

    /**
     * 上一行右边缘 ≥ 评论气泡最右边缘的这个比例,才算"写满了"(折行的前提)。
     * "评论气泡最右边缘"只用带 "昵称:" 的行来算:系统公告("点点关注点点赞…")的容器比评论宽得多,
     * 拿整帧最宽的行当基准,会让所有评论的折行都被判成"没写满"(2026-10-15 真机实测踩到)。
     */
    const val FULL_LINE_RATIO = 0.85f

    /** "昵称:" 的冒号必须出现在前这么多个字符之内,否则当作正文里的冒号。 */
    const val NICK_MAX_CHARS = 15

    fun merge(lines: List<OcrLine>, imageHeight: Int): List<Item> {
        val rows = groupRows(lines.filter { it.text.isNotBlank() })
        if (rows.isEmpty()) return emptyList()

        val nickRows = rows.filter { nickColon(it.text) >= 0 }
        val maxRight = (if (nickRows.isNotEmpty()) nickRows else rows).maxOf { it.right }
        val groups = ArrayList<MutableList<Row>>()
        for (row in rows) {
            val prev = groups.lastOrNull()?.last()
            if (prev != null && continues(prev, row, maxRight)) groups.last().add(row) else groups.add(mutableListOf(row))
        }

        val heights = rows.map { it.height }.sorted()
        val medianHeight = heights[heights.size / 2]
        val tol = maxOf(2f, medianHeight * 0.15f)
        return groups.map { g ->
            val text = g.map { it.text }.reduce(::joinText)
            Item(
                text = text,
                content = splitNickname(text),
                atEdge = g.first().top <= tol || g.last().bottom >= imageHeight - tol,
            )
        }
    }

    /** 去掉 "昵称:" 前缀只留内容;没有昵称前缀(或前缀只是数字,如 "3:30")就原样返回。昵称丢了只剩开头冒号(":好像有点制")也会去掉冒号。 */
    fun splitNickname(text: String): String {
        val idx = nickColon(text)
        if (idx < 0) return text
        val content = text.substring(idx + 1).trim()
        return content.ifEmpty { text }
    }

    /** 这一行是否一条新弹幕的开头:带 "昵称:" 前缀,或是系统提示。 */
    fun isStartLine(text: String): Boolean = nickColon(text) >= 0 || isSystemLine(text)

    /**
     * 进场等系统提示(没有冒号,单独成条),不会有续行。
     * "来" 也认 "業":真机上 ML Kit 常把"来了"认成"業了"(2026-10-15 截图里的 "北業了")。
     */
    fun isSystemLine(text: String): Boolean = SYSTEM_TAIL.containsMatchIn(text.trim())

    private val SYSTEM_TAIL = Regex("[来業]了[\\s!！。.~～]*$|送出了|关注了主播|加入了粉丝团")

    /** "昵称:" 冒号的位置;没有则 -1。冒号前若只有数字(时间 "3:30"、比分)不算昵称。 */
    private fun nickColon(text: String): Int {
        val idx = text.indexOfFirst { it == ':' || it == '：' }
        if (idx < 0 || idx > NICK_MAX_CHARS) return -1
        val prefix = text.substring(0, idx).trim()
        if (prefix.isNotEmpty() && prefix.all { it.isDigit() }) return -1
        return idx
    }

    private fun continues(prev: Row, row: Row, maxRight: Int): Boolean {
        if (isStartLine(row.text) || isSystemLine(prev.text)) return false
        if (prev.right < maxRight * FULL_LINE_RATIO) return false
        val gap = row.top - prev.bottom
        val h = maxOf(1, (prev.height + row.height) / 2)
        return gap <= h * MAX_GAP_RATIO
    }

    private class Row(val parts: List<OcrLine>) {
        val text: String = parts.sortedBy { it.left }.map { it.text }.reduce(::joinText)
        val top = parts.minOf { it.top }
        val bottom = parts.maxOf { it.bottom }
        val right = parts.maxOf { it.right }
        val height get() = bottom - top
    }

    /**
     * 同一水平位置的片段并成一行:弹幕前面的等级徽章数字、昵称和内容有时会被 ML Kit 切成并排的几段。
     * 纵向重叠达到较矮一段的一半以上,就认为在同一行。
     */
    private fun groupRows(lines: List<OcrLine>): List<Row> {
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val rows = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val cur = rows.lastOrNull()
            if (cur != null && sameRow(cur, l)) cur.add(l) else rows.add(mutableListOf(l))
        }
        return rows.map { Row(it) }
    }

    private fun sameRow(row: List<OcrLine>, l: OcrLine): Boolean {
        val top = row.minOf { it.top }; val bottom = row.maxOf { it.bottom }
        val overlap = minOf(bottom, l.bottom) - maxOf(top, l.top)
        val minH = maxOf(1, minOf(bottom - top, l.height))
        return overlap * 2 >= minH
    }

    /** 两段文字相接:两边都是英文/数字时补一个空格(避免 "I" + "love" 变成 "Ilove"),其余直接相连。 */
    private fun joinText(a: String, b: String): String {
        val x = a.trimEnd(); val y = b.trimStart()
        if (x.isEmpty() || y.isEmpty()) return x + y
        val asciiWord = { c: Char -> c.code < 128 && c.isLetterOrDigit() }
        return if (asciiWord(x.last()) && asciiWord(y.first())) "$x $y" else x + y
    }
}
