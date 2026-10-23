package com.luxofilms.vision

/**
 * 昵称记忆(ADR-0038):只要某一行的"昵称:"冒号读对了,就把冒号前的昵称记下;之后同一位观众的弹幕冒号被 OCR 读成 `、` `,` 或丢掉时,
 * 行首仍是这个昵称(允许前面有 1~2 位粉丝等级徽章数字 `2` / `O2` / `18`、昵称自己有 1~2 个字的 OCR 差异),就把昵称和后面的分隔符剥掉。
 *
 * 只对**读对过一次**的昵称生效,不凭空猜昵称的边界(2026-10-23 真机:昵称 `月岛出逃指南。` 的冒号几乎读不出来,昵称留在了内容里)。
 * 昵称至少 3 个字(归一化后):太短的昵称和正文开头撞上的概率太高。状态只在内存里,最多 [MAX] 个、[TTL_MS] 毫秒过期;
 * 只在 CaptureService 的单线程 Analyzer 里用,不加锁。不依赖 android.*,可单元测试。
 */
class NickMemory(
    private val ttlMs: Long = TTL_MS,
    private val max: Int = MAX,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(val norm: String, var at: Long)

    private val entries = ArrayList<Entry>()      // 最近用过的在后面

    /** 记下一个冒号读对了的昵称(冒号前面那一段原文,可以带徽章数字)。匿名昵称、太短 / 太长的不记。 */
    fun learn(rawNick: String) {
        val norm = DanmakuDeduper.normalize(rawNick.trim().replace(BADGE, ""))
        if (norm.length < MIN_NICK || norm.length > MAX_NICK) return
        val now = clock()
        expire(now)
        val hit = entries.firstOrNull { it.norm == norm }
        if (hit != null) { hit.at = now; entries.remove(hit); entries.add(hit) } else entries.add(Entry(norm, now))
        while (entries.size > max) entries.removeAt(0)
    }

    /** 行首是记过的昵称就返回剥掉昵称与分隔符后的内容;不是、或剥完什么都不剩返回 null。 */
    fun strip(text: String): String? {
        if (entries.isEmpty()) return null
        expire(clock())
        val body = text.trim().replace(BADGE, "")
        val tn = DanmakuDeduper.normalize(body)
        var bestLen = -1
        var bestSim = 0f
        for (e in entries.asReversed()) {
            for (len in (e.norm.length - 1)..(e.norm.length + 1)) {
                if (len < MIN_NICK || len > tn.length - 1) continue            // 昵称后面还得剩内容
                val sim = DanmakuDeduper.similarity(tn.substring(0, len), e.norm)
                if (sim >= SIMILARITY && sim > bestSim) { bestSim = sim; bestLen = len }
            }
        }
        if (bestLen < 0) return null
        val rest = consume(body, bestLen)
        // 昵称后面至少还得剩 2 个字的内容:剩 1 个字多半是昵称自己少读了一位("月岛出逃指南。" 被当成 "月岛出逃指" + "南")
        return if (DanmakuDeduper.normalize(rest).length < MIN_REST) null else rest
    }

    fun matches(text: String): Boolean = strip(text) != null

    fun size(): Int = entries.size

    private fun expire(now: Long) {
        entries.removeAll { now - it.at > ttlMs }
    }

    /** 从原文里吃掉 n 个"归一化字符"(字母 / 数字 / 汉字),再跳过紧跟的分隔符与标点,返回剩下的原文。 */
    private fun consume(body: String, n: Int): String {
        var seen = 0
        var i = 0
        while (i < body.length && seen < n) {
            if (body[i].isLetterOrDigit()) seen++
            i++
        }
        while (i < body.length && !body[i].isLetterOrDigit()) i++
        return body.substring(i).trim()
    }

    companion object {
        const val TTL_MS = 30 * 60_000L
        const val MAX = 64
        const val MIN_NICK = 3
        const val MAX_NICK = 15
        const val MIN_REST = 2
        const val SIMILARITY = 0.75f

        /** 行首粉丝等级徽章的数字:`2`、`O2`、`18`(后面必须紧跟非数字的字,否则整行就是数字)。 */
        private val BADGE = Regex("^\\s*[Oo０]?[0-9０-９]{1,2}(?=[^0-9０-９\\s])")
    }
}
