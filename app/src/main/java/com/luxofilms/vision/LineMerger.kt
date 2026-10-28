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
 * 判断依据(两个直播间的实测,抖音气泡式弹幕):
 * - 2026-10-15 游戏直播间(ML Kit 行框高约 18~20px):同条内续行的空隙 0.43~0.54 倍行高,相邻两条 1.0~1.2 倍。
 * - 2026-10-23 车膜直播间(按截图目测,字高≈40px):续行≈0.55 倍,相邻两条≈**0.75** 倍——只差 0.2,旧阈值 0.75 正好落在新弹幕上,
 *   而昵称后的冒号常被 OCR 读成 `、` `,` 或丢掉,"起始行"漏判后几条弹幕就并成了一条(ADR-0038)。
 * 所以以文字 / 版式线索为主、间距为辅:
 * 1. 起始行线索(任一):带 "昵称:" 前缀;"xx 来了"以及带地点 / 粉丝文字的进场提示;计时器 / 匿名昵称前缀;
 *    行首是读对过一次的昵称(冒号丢了也认,见 [NickMemory]);比同帧最靠左的行右缩 ≥ 1.5 倍行高(带粉丝徽章的起始行,续行与平台提示贴着气泡左缘);
 * 2. 续行(都要满足):不是起始行 + 上一行不是系统提示 + 上一行写满了(折行必然写到右边界)+ 空隙 ≤ 0.9 倍行高 +
 *    (比这一条的起始行外缩 ≥ 1.5 倍行高,**或** 空隙 ≤ 0.6 倍);
 * 3. 落单的短续行(≤ 4 个字、同帧明显有右缩的起始行、自己贴着左缘、没并进任何一条:`么`、`是什么`、`常看你`)不上传;
 * 4. 拿不准一律不合并——多问一次的代价,小于把两条不同的弹幕拼成一句。
 */
object LineMerger {
    /**
     * 合并后的一条弹幕。
     * text:整条原文(含昵称,首页/预览核对用);content:去掉"昵称:"前缀后的内容(上传用);
     * atEdge:贴着弹幕区上沿或下沿,这一条很可能被裁掉了一截(不完整);rows:合并了几个 OCR 视觉行(诊断用)。
     * nick:读得准的昵称(2026-10-28,云端念"XX老板"用)——"昵称:"冒号读对了,或行首对上了读对过的昵称才有;
     * 匿名昵称、系统提示、拿不准的都是 null(云端照旧按匿名处理)。云端还会再查一遍能不能念。
     */
    data class Item(val text: String, val content: String, val atEdge: Boolean, val rows: Int = 1, val nick: String? = null)

    /**
     * 没有"外缩"证据时,行间空隙 ÷ 行高 ≤ 此值才算同一条。2026-10-15 游戏直播间同条内 0.43~0.54、条与条之间 1.0~1.2;
     * 2026-10-23 车膜直播间续行≈0.55、条与条≈0.75(目测)。原来的 0.75 落在后者的新弹幕上,收紧到 0.6(ADR-0038)。
     */
    const val MAX_GAP_RATIO = 0.6f

    /** 有"外缩"证据(续行贴着左缘、起始行带徽章右缩)时,空隙放宽到这个值,只防两行隔得太远。 */
    const val SANE_GAP_RATIO = 0.9f

    /** 比同帧最靠左的行右缩这么多倍行高,算带徽章的起始行;续行比它的起始行外缩这么多倍行高,算"贴着左缘"的正证据。 */
    const val OUTDENT_RATIO = 1.5f

    /** 落单的续行(没并进任何一条、没有起始线索)归一化后不超过这么多个字就不上传。 */
    const val ORPHAN_MAX_CHARS = 4

    /**
     * 上一行右边缘 ≥ 评论气泡最右边缘的这个比例,才算"写满了"(折行的前提)。
     * "评论气泡最右边缘"只用带 "昵称:" 的行来算:系统公告("点点关注点点赞…")的容器比评论宽得多,
     * 拿整帧最宽的行当基准,会让所有评论的折行都被判成"没写满"(2026-10-15 真机实测踩到)。
     */
    const val FULL_LINE_RATIO = 0.85f

    /** "昵称:" 的冒号必须出现在前这么多个字符之内,否则当作正文里的冒号。 */
    const val NICK_MAX_CHARS = 15

    fun merge(lines: List<OcrLine>, imageHeight: Int, nicks: NickMemory? = null): List<Item> {
        // 纯计时器行(PK 倒计时 `15:00` 之类的小部件)和进场提示折行后的残片(`常看你`)不是弹幕,在这里就丢掉
        val rows = groupRows(lines.filter { it.text.isNotBlank() && !TIMER_ONLY.matches(it.text) && !ENTER_FRAGMENT.matches(it.text.trim()) })
        if (rows.isEmpty()) return emptyList()

        val heights = rows.map { it.height }.sorted()
        val medianHeight = heights[heights.size / 2]
        val minLeft = rows.minOf { it.left }
        val farRight = OUTDENT_RATIO * medianHeight
        // 起始行线索:文字特征(冒号 / 系统 / 前缀 / 读对过的昵称)或明显右缩(带粉丝徽章)
        val indented = rows.map { it.left - minLeft >= farRight }
        val remembered = rows.map { nicks?.matches(it.text) == true }
        val starts = rows.mapIndexed { i, r -> indented[i] || remembered[i] || isStartLine(r.text) }

        val bubbleRows = rows.filterIndexed { i, r -> nickColon(r.text) >= 0 || indented[i] || remembered[i] }
        val maxRight = (if (bubbleRows.isNotEmpty()) bubbleRows else rows).maxOf { it.right }
        val groups = ArrayList<MutableList<Row>>()
        for ((i, row) in rows.withIndex()) {
            val g = groups.lastOrNull()
            if (g != null && !starts[i] && continues(g, row, maxRight)) { g.add(row); continue }
            if (!starts[i] && isOrphan(row, rows, minLeft, farRight)) continue      // 落单的短续行:不上传
            groups.add(mutableListOf(row))
        }

        val tol = maxOf(2f, medianHeight * 0.15f)
        return groups.map { g ->
            val text = g.map { it.text }.reduce(::joinText)
            val first = g.first().text
            val colon = nickColon(first)
            if (nicks != null && colon > 0 && !ANON_NICKS.any { first.startsWith(it) }) nicks.learn(first.substring(0, colon))   // 冒号读对了:记住这个昵称
            Item(
                text = text,
                content = contentOf(text, nicks),
                atEdge = g.first().top <= tol || g.last().bottom >= imageHeight - tol,
                rows = g.size,
                nick = nickOf(text, nicks),
            )
        }
    }

    /**
     * 这一条读得准的昵称,读不准 / 匿名 / 系统提示返回 null。先剥行首计时器(`15:00 小明:能洗车吗`);
     * 冒号读对了取冒号前那段(去掉粉丝徽章数字);冒号没读对时,行首对上读对过的昵称就用记下的那个(不用这一帧可能读错的字)。
     */
    fun nickOf(text: String, nicks: NickMemory? = null): String? {
        val t = text.trim()
        if (isSystemLine(t)) return null
        var s = t
        val m = TIMER_PREFIX.find(s)
        if (m != null && m.range.last + 1 < s.length && nickColon(s.substring(m.range.last + 1)) > 0) s = s.substring(m.range.last + 1)
        val idx = nickColon(s)
        if (idx > 0) {
            val nick = s.substring(0, idx).trim().replace(NickMemory.BADGE, "").trim()
            if (nick.isEmpty() || ANON_NICKS.any { nick.startsWith(it) }) return null
            if (s.substring(idx + 1).isBlank()) return null
            return nick
        }
        if (stripPrefix(t).second) return null              // 匿名昵称 / 计时器剥掉了:没有可念的名字
        return nicks?.nickOf(t)
    }

    /** 上传用的内容:先剥计时器 / 匿名昵称 / "昵称:";都没有剥掉时,行首是读对过的昵称(冒号丢了)也剥掉。 */
    private fun contentOf(text: String, nicks: NickMemory?): String {
        val s = splitNickname(text)
        if (s != text) return s
        if (isSystemLine(text)) return text                  // 进场提示原样交给服务端(它的规则认得出,也要靠昵称那一截认"xx 来了")
        return nicks?.strip(text) ?: text
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
     * - 剥完什么都不剩就原样返回。普通昵称后被读成逗号的情形分不清是不是正文逗号(`价格,多少钱`),**不处理**(读对过一次的昵称见 [NickMemory])。
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
     * 2026-10-23 起(ADR-0038)再认带地点 / 粉丝文字的新格式,以及它折行后只剩的 `常看你`;带问句形态的一律当观众的话。
     */
    fun isSystemLine(text: String): Boolean {
        val t = text.trim()
        if (SYSTEM_TAIL.containsMatchIn(t)) return true
        if (t.any { it in QUESTION_MARKS }) return false
        return ENTER_PLACE.containsMatchIn(t) || ENTER_FAN.containsMatchIn(t)
    }

    private val SYSTEM_TAIL = Regex("[来業]了[\\s!！。.~～]*$|送出了|关注了主播|加入了粉丝团")

    /** `荣耀剧场 来了·在上海市`;OCR 常把"了"读成 7 / 丁。必须有"在":`来了解北京市` 是一句话。 */
    private val ENTER_PLACE = Regex("[来來業][了7丁][·•・.．]?在[\\u4e00-\\u9fff]{1,8}[市省区县]\\s*$")

    /** `xx 来了·你的粉丝 经常看你`(折成两行,第二行只剩 `常看你`)。 */
    private val ENTER_FAN = Regex("[来來業][了7丁][·•・.．\\s]*.{0,6}(?:你的粉丝|经常看你|来自)|^[\\u4e00-\\u9fff]{0,2}常看你[。.!！]?$|经常看你\\s*$")
    private const val QUESTION_MARKS = "吗?？嘛"

    /** 进场提示折行后第二行只剩的残片(`常看你`、`经常看你`):单独不是任何一条弹幕,直接丢。 */
    private val ENTER_FRAGMENT = Regex("^[\\u4e00-\\u9fff]{0,2}常看你[。.!！]?$|^经常看你\\s*$")

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

    /**
     * 这一行(不是起始行)是不是上一条的续行。要满足:上一行不是系统提示、写满了、空隙不大;再加"外缩"或"间距很小"之一做正证据。
     * 外缩 = 比这一条的起始行靠左 ≥ 1.5 倍行高(续行贴着气泡左缘,起始行有粉丝徽章);版式没有这种缩进(游戏直播间)时,只认空隙 ≤ 0.6 倍。
     */
    private fun continues(group: List<Row>, row: Row, maxRight: Int): Boolean {
        val prev = group.last()
        if (isSystemLine(prev.text)) return false
        if (prev.right < maxRight * FULL_LINE_RATIO) return false
        val gap = row.top - prev.bottom
        val h = maxOf(1, (prev.height + row.height) / 2)
        if (gap > h * SANE_GAP_RATIO) return false
        val outdented = row.left <= group.first().left - OUTDENT_RATIO * h
        return outdented || gap <= h * MAX_GAP_RATIO
    }

    /**
     * 落单的短续行:没有起始线索、没并进任何一条,自己贴着左缘,而同帧里明显有右缩的起始行(版式上"续行贴左缘、起始行带徽章"成立),
     * 并且归一化后只有几个字(`么`、`是什么`、`常看你`)→ 是折行 / 进场提示 / 滚出屏幕一半的残片,不上传。
     * 没有缩进线索的版式(只拍到内容、没有昵称)里不做这个判断,短行可能就是一条短弹幕。
     */
    private fun isOrphan(row: Row, rows: List<Row>, minLeft: Int, farRight: Float): Boolean {
        if (row.left - minLeft >= farRight) return false
        if (rows.none { it.left - row.left >= farRight }) return false
        return DanmakuDeduper.normalize(row.text).length <= ORPHAN_MAX_CHARS
    }

    private class Row(val parts: List<OcrLine>) {
        val text: String = parts.sortedBy { it.left }.map { it.text }.reduce(::joinText)
        val left = parts.minOf { it.left }
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
