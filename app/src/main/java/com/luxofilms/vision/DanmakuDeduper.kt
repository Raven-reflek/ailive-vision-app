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
            val threshold = if (minLen <= SHORT_LEN) SHORT_SIMILARITY else SIMILARITY
            return similarity(a, b) >= threshold
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
