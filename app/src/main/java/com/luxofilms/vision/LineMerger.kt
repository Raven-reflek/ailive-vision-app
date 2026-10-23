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
        // 纯计时器行(PK 倒计时 `15:00` 之类的小部件)不是弹幕,在这里就丢掉
        val rows = groupRows(lines.filter { it.text.isNotBlank() && !TIMER_ONLY.matches(it.text) })
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

    /**
     * 去掉 "昵称:" 前缀只留内容;没有昵称前缀(或前缀只是数字,如 "3:30")就原样返回。昵称丢了只剩开头冒号(":好像有点制")也会去掉冒号。
     * 2026-10-22 起(ADR-0037)先剥行首的计时器 / 计数 / 匿名昵称"抖音用户"(见 [stripPrefix]):
     * 真实直播里 `15:00 小明:能洗车吗` 的第一个冒号在时间里,`00:55抖音用户,…` 的冒号被 OCR 读成了逗号,以前都拆不掉。
     */
    fun splitNickname(text: String): String {
        val (stripped, changed) = stripPrefix(text)
        if (changed) return stripped
        val idx = nickColon(text)
        if (idx < 0) return text
        val content = text.substring(idx + 1).trim()
        return content.ifEmpty { text }
    }

    /** 这一行是否一条新弹幕的开头:带 "昵称:" 前缀、带计时器 / 匿名昵称前缀,或是系统提示。 */
    fun isStartLine(text: String): Boolean = nickColon(text) >= 0 || isSystemLine(text) || stripPrefix(text).second

    /** 行首的计时器(`15:00`、`00:55`)和紧跟的计数(`15:0053`):PK 倒计时、在线人数这类小部件常被 ML Kit 并进弹幕那一行。 */
    private val TIMER_PREFIX = Regex("^\\s*\\d{1,2}[:：]\\d{2}(?:[:：]\\d{2})?\\s*(\\d{1,3}(?=\\D))?\\s*")
    private val TIMER_ONLY = Regex("^\\s*\\d{1,2}[:：]\\d{2}(?:[:：]\\d{2})?\\s*\\d{0,3}\\s*$")
    private val ANON_NICKS = listOf("抖音用户", "神秘人")
    private const val ANON_SEPS = " \t,，.。;；:：、"
    private const val ANON_NO_SEP_NOT_BEFORE = "的很都是也有在不就还又更最太"       // `抖音用户很多` 是正文,不是昵称 + 内容

    /**
     * 去掉行首的计时器 / 计数 / 匿名昵称"抖音用户"(含被 OCR 读错的分隔符),返回 (剩下的文字, 有没有改动)。规则与服务端 vision_clean.strip_prefix 一致。
     * - 计时器只在后面跟着计数、匿名昵称或 `昵称:` 时才剥("3:30到店可以吗"里的时间是正文);
     * - 匿名昵称后跟 `, . ; : 、` 或空格就剥;什么分隔符都没有时,剩下 ≥3 个字且不是"很多/都能…"这类连接词开头才剥;
     * - 剥完什么都不剩就原样返回。普通昵称后被读成逗号的情形分不清是不是正文逗号(`价格,多少钱`),**不处理**。
     */
    fun stripPrefix(text: String): Pair<String, Boolean> {
        val t = text.trim()
        var out = t
        val m = TIMER_PREFIX.find(out)
        if (m != null && m.range.last + 1 < out.length) {
            val rest = out.substring(m.range.last + 1)
            val idx = rest.indexOfFirst { it == ':' || it == '：' }
            val nickColon = idx in 1..NICK_MAX_CHARS && !rest.substring(0, idx).trim().all { it.isDigit() } && rest.substring(idx + 1).isNotBlank()
            if (m.groups[1] != null || ANON_NICKS.any { rest.startsWith(it) } || nickColon) {
                out = if (nickColon) rest.substring(idx + 1) else rest
            }
        }
        out = out.trim()
        for (nick in ANON_NICKS) {
            if (!out.startsWith(nick)) continue
            var rest = out.substring(nick.length)
            if (rest.isNotEmpty() && rest[0] in ANON_SEPS) {
                rest = rest.trimStart { it in ANON_SEPS }
                if (rest.length >= 2) out = rest
            } else if (rest.length >= 3 && rest[0] !in ANON_NO_SEP_NOT_BEFORE) {
                out = rest
            }
            break
        }
        out = out.trim()
        return if (out.isEmpty() || out == t) t to false else out to true
    }

    /**
     * 进场等系统提示(没有冒号,单独成条),不会有续行。
     * "来" 也认 "業":真机上 ML Kit 常把"来了"认成"業了"(2026-10-15 截图里的 "北業了")。
     */
    fun isSystemLine(text: String): Boolean = SYSTEM_TAIL.containsMatchIn(text.trim())

    private val SYSTEM_TAIL = Regex("[来業]了[\\s!！。.~～]*$|送出了|关注了主播|加入了粉丝团")

    /**
     * "昵称:" 冒号的位置;没有则 -1。冒号前若只有数字不算昵称;**两边都是数字的冒号(时间 `15:00`、比分 `3:2`)根本不是分隔符,跳过**:
     * `15:00 小明:能洗车吗` 要找到 `小明:` 那个冒号,`今天15:00还营业吗` 里的冒号也不能把"今天15"当昵称切掉(2026-10-22)。
     */
    private fun nickColon(text: String): Int {
        for (i in text.indices) {
            val c = text[i]
            if (c != ':' && c != '：') continue
            if (i > NICK_MAX_CHARS) return -1
            val timeLike = i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit()
            if (timeLike) continue
            val prefix = text.substring(0, i).trim()
            if (prefix.isNotEmpty() && prefix.all { it.isDigit() }) return -1
            return i
        }
        return -1
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
