package com.luxofilms.vision

/**
 * 客户端的弹幕去重:判断"这一条是不是最近已经上传过的那一条",同一条只问一次。
 *
 * 为什么服务端那层不够:服务端只认"归一化后完全相同"。可同一条弹幕在屏幕上要停留十几秒,每隔 2 秒被拍一次,
 * 每次 OCR 的错字都不一样(2026-10-15 真机:`旮旯火影高手:主播可以放那首我是gay吗` 被认成
 * `查見火影葡手:主撫可以放那首是ey吗`、`查党火影群手:主進可以放那首動是gay吗`……),每个变体都被当成新弹幕、各问一次。
 * 这里按"字符层面的相似度"判重,吸收 OCR 抖动;同时省掉每分钟 40 次上传的限流额度。
 *
 * 状态只在内存里。线程:只在 CaptureService 的单线程 Analyzer 里用,不加锁。
 */
class DanmakuDeduper(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSeen: Int = DEFAULT_MAX_SEEN,
) {
    private class Seen(val norm: String, var at: Long)
    private class Pending(var norm: String, var count: Int, var at: Long)

    private val seen = ArrayList<Seen>()
    private val pending = ArrayList<Pending>()

    /**
     * 从这一帧合并出的弹幕里,挑出真正要上传的(顺序保持)。**不记账**——上传成功后调用 [commit],
     * 上传失败的下一帧会重试,不会因为"已经试过"而丢掉。
     *
     * 贴着弹幕区边缘的条目(atEdge)很可能只露出一截,单独处理:
     * - 能和已见过的某条对上(含"是它的一段")→ 丢弃,这是已经问过的那条在滚出/滚入;
     * - 对不上 → 先不上传,同一条连续出现 [EDGE_RELEASE_SIGHTINGS] 帧(说明不是一晃而过的半截)才放行,
     *   避免"标定框恰好切在最新一条上"时它永远发不出去。
     */
    fun select(items: List<LineMerger.Item>, now: Long): List<LineMerger.Item> {
        expire(now)
        val out = ArrayList<LineMerger.Item>()
        val outNorm = ArrayList<String>()
        for (item in items) {
            val n = normalize(item.content)
            if (n.length < MIN_CHARS) continue
            val hit = seen.firstOrNull { same(it.norm, n, item.atEdge) }
            if (hit != null) { hit.at = now; continue }
            if (outNorm.any { same(it, n, item.atEdge) }) continue
            if (item.atEdge) {
                val p = pending.firstOrNull { same(it.norm, n, true) }
                if (p == null) { pending.add(Pending(n, 1, now)); continue }
                if (n.length > p.norm.length) p.norm = n   // 保留看到过的最完整的一版
                p.at = now; p.count++
                if (p.count < EDGE_RELEASE_SIGHTINGS) continue
                pending.remove(p)
            }
            out.add(item); outNorm.add(n)
        }
        return out
    }

    /** 这些条目已经成功上传,记入"已见过"。 */
    fun commit(items: List<LineMerger.Item>, now: Long) {
        for (item in items) {
            val n = normalize(item.content)
            if (n.length < MIN_CHARS) continue
            val hit = seen.firstOrNull { same(it.norm, n, false) }
            if (hit != null) hit.at = now else seen.add(Seen(n, now))
        }
        while (seen.size > maxSeen) seen.removeAt(0)
    }

    private fun expire(now: Long) {
        seen.removeAll { now - it.at > windowMs }
        pending.removeAll { now - it.at > PENDING_TTL_MS }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_MAX_SEEN = 200
        const val EDGE_RELEASE_SIGHTINGS = 3
        const val PENDING_TTL_MS = 8_000L
        const val MIN_CHARS = 2

        /** 相似度达到此值算同一条。OCR 变体之间实测约 0.7~0.9;两条不相干的弹幕通常低于 0.4。 */
        const val SIMILARITY = 0.6f

        /** 很短的文字(≤3 字)改用更严的阈值,免得"多少钱"和"多久钱"这类不同问题被当成同一条。 */
        const val SHORT_SIMILARITY = 0.8f
        private const val SHORT_LEN = 3

        /** 只留字母/数字/汉字并转小写:空格、标点、竖线、表情符号之类的 OCR 噪音全部忽略。 */
        fun normalize(text: String): String =
            buildString { for (c in text) if (c.isLetterOrDigit()) append(c.lowercaseChar()) }

        fun same(a: String, b: String, edge: Boolean): Boolean {
            if (a == b) return true
            val minLen = minOf(a.length, b.length)
            // 贴边的条目只是半截:它是某条的一段(头或尾)就算同一条
            if (edge && minLen >= MIN_CHARS && (a.contains(b) || b.contains(a))) return true
            if (numbersConflict(a, b) || subjectSwapped(a, b)) return false     // 差一个编号 / 换了主语:是两个问题,相似度再高也不能吞
            val threshold = if (minLen <= SHORT_LEN) SHORT_SIMILARITY else SIMILARITY
            return similarity(a, b) >= threshold
        }

        /** 共用的"问句模板"至少这么多字(头尾合计),才谈得上"同一个问法换了主语"。 */
        private const val TEMPLATE_MIN = 4

        /** 被换掉的那一段最多这么长(商品名 / 问法的后半句);更长的多半是折行或昵称混入。 */
        private const val SWAP_MAX = 5

        /**
         * 套同一个问句模板、只换了主语:`固驰是什么牌子` / `蓝晶灵是什么牌子`(相似度 0.67)、`固驰是什么牌子` / `固驰是什么材质`(0.71)
         * 都超过 0.6,按相似度会被当成同一条吞掉(2026-10-23)。判法:去掉共同的头和尾,剩下的两段各 2~5 个字、长度相差不超过 1、
         * **一个相同的字都没有**(换主语是"词换词",长短相近;折行 / 昵称混入造成的差异一长一短,不算)。
         * OCR 抖动改的是一两个字、且多半还会留下相同的字,不会被这条误判成两个问题;真错了两个字也只是多问一次(服务端有"同一回答冷却")。
         * 入参是 [normalize] 之后的。和服务端 `vision_clean.subject_swapped` 同一口径。
         */
        fun subjectSwapped(a: String, b: String): Boolean {
            val m = minOf(a.length, b.length)
            var p = 0
            while (p < m && a[p] == b[p]) p++
            var s = 0
            while (s < m - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
            if (p + s < TEMPLATE_MIN) return false
            val ca = a.substring(p, a.length - s)
            val cb = b.substring(p, b.length - s)
            return ca.length in 2..SWAP_MAX && cb.length in 2..SWAP_MAX && kotlin.math.abs(ca.length - cb.length) <= 1 && ca.none { it in cb }
        }

        private val CN_DIGIT = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

        /** 号码:阿拉伯数字,以及"X号""第X"里的中文数字(`介绍一下`的`一`后面是`下`,不算)。 */
        private val NUMBER_TOKEN = Regex("[0-9]+|[一二两三四五六七八九十]+(?=号)|(?<=第)[一二两三四五六七八九十]+")

        /** 中文数字 → 阿拉伯数字串(1~99:三→3、十二→12、二十→20);不认识的(三五)原样留着。 */
        private fun cnNumber(s: String): String {
            if (s.all { it in '0'..'9' }) return s.toBigInteger().toString()
            val ten = s.indexOf('十')
            if (ten >= 0) {
                val head = s.substring(0, ten)
                val tail = s.substring(ten + 1)
                if (head.length > 1 || tail.length > 1) return s
                val tens = if (head.isEmpty()) 1 else CN_DIGIT[head[0]] ?: return s
                val ones = if (tail.isEmpty()) 0 else CN_DIGIT[tail[0]] ?: return s
                return (tens * 10 + ones).toString()
            }
            return if (s.length == 1) CN_DIGIT[s[0]]?.toString() ?: s else s
        }

        /** 文字里的号码串(统一成阿拉伯数字):`2月岛出逃指南介绍十二号链接` → [2, 12]。入参是 [normalize] 之后的。 */
        fun numberTokens(n: String): List<String> = NUMBER_TOKEN.findAll(n).map { cnNumber(it.value) }.toList()

        /**
         * 两边都带号码、号码不一样(`介绍一下1号链接` / `介绍一下2号链接`)是两个不同的问题,不能当成同一条吞掉。
         * 宁可多问一次(OCR 偶尔认错数字,服务端还有"同一回答冷却"兜着),也别把另一条真提问当成重复丢了。
         * 较短一边的号码串必须是较长一边的结尾才算没冲突:前面多出来的是行首粉丝等级徽章的数字(`2月岛出逃指南…1号链接`);
         * 只一边有号码(OCR 丢了数字)不算冲突。和服务端 `vision_clean.numbers_conflict` 同一口径。
         */
        fun numbersConflict(a: String, b: String): Boolean {
            val x = numberTokens(a)
            val y = numberTokens(b)
            if (x.isEmpty() || y.isEmpty() || x == y) return false
            val (short, long) = if (x.size <= y.size) x to y else y to x
            return long.takeLast(short.size) != short
        }

        /** 2×最长公共子序列 ÷ (两串长度之和),0~1。对"个别字认错/丢字"很宽容,对整体不同的两句话给低分。 */
        fun similarity(a: String, b: String): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            var prev = IntArray(b.length + 1)
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
                }
                val t = prev; prev = cur; cur = t
            }
            return 2f * prev[b.length] / (a.length + b.length)
        }
    }
}
