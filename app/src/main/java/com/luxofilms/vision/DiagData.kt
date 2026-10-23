package com.luxofilms.vision

/**
 * 诊断包的数据结构与 JSON 序列化(ADR-0038)。不依赖 android.*,可单元测试。
 *
 * 为什么要有诊断包:预览页禁止截屏、也不落盘,出问题时没有任何原始画面可看,阈值只能照直播截图目测。
 * 诊断包把最近几帧的"识别用小图 + ML Kit 每一行 / 每个词的文字、框、取样颜色 + 合并结果"一起导出,以后可以脱敏后进仓库做回放用例。
 */
data class DiagElement(val text: String, val box: List<Int>?, val ink: String?)

data class DiagLine(val text: String, val box: List<Int>?, val elements: List<DiagElement>)

/** 一帧的诊断记录。jpeg 是识别用小图(增强后的弹幕区);items 是合并后的结果;uploaded 是这一帧拟上传的内容(去重之后)。 */
class DiagFrame(
    val ts: Long,
    val width: Int,
    val height: Int,
    val jpeg: ByteArray?,
    val lines: List<DiagLine>,
    val items: List<LineMerger.Item> = emptyList(),
    val uploaded: List<String> = emptyList(),
) {
    fun withResult(items: List<LineMerger.Item>, uploaded: List<String>) = DiagFrame(ts, width, height, jpeg, lines, items, uploaded)
}

object DiagJson {
    /** frames 对应的图片文件名由调用方给(与 zip 里一致);meta 是几条说明性的键值(版本、机型、时间…)。 */
    fun build(frames: List<DiagFrame>, imageNames: List<String?>, meta: Map<String, String>): String {
        val sb = StringBuilder(4096)
        sb.append("{\n  \"meta\": {")
        meta.entries.forEachIndexed { i, (k, v) -> sb.append(if (i == 0) "" else ", ").append(str(k)).append(": ").append(str(v)) }
        sb.append("},\n  \"frames\": [")
        frames.forEachIndexed { i, f ->
            sb.append(if (i == 0) "\n" else ",\n")
            sb.append("    {\"ts\": ").append(f.ts).append(", \"width\": ").append(f.width).append(", \"height\": ").append(f.height)
            sb.append(", \"image\": ").append(imageNames.getOrNull(i)?.let(::str) ?: "null")
            sb.append(",\n     \"lines\": [")
            f.lines.forEachIndexed { j, l ->
                sb.append(if (j == 0) "\n" else ",\n")
                sb.append("       {\"text\": ").append(str(l.text)).append(", \"box\": ").append(box(l.box)).append(", \"elements\": [")
                l.elements.forEachIndexed { k, e ->
                    sb.append(if (k == 0) "" else ", ")
                    sb.append("{\"text\": ").append(str(e.text)).append(", \"box\": ").append(box(e.box)).append(", \"ink\": ").append(e.ink?.let(::str) ?: "null").append("}")
                }
                sb.append("]}")
            }
            sb.append(if (f.lines.isEmpty()) "]" else "\n     ]")
            sb.append(",\n     \"items\": [")
            f.items.forEachIndexed { j, it ->
                sb.append(if (j == 0) "" else ", ")
                sb.append("{\"text\": ").append(str(it.text)).append(", \"content\": ").append(str(it.content))
                    .append(", \"atEdge\": ").append(it.atEdge).append(", \"rows\": ").append(it.rows).append("}")
            }
            sb.append("],\n     \"uploaded\": [").append(f.uploaded.joinToString(", ") { str(it) }).append("]}")
        }
        sb.append(if (frames.isEmpty()) "]\n}\n" else "\n  ]\n}\n")
        return sb.toString()
    }

    private fun box(b: List<Int>?) = if (b == null || b.size != 4) "null" else "[" + b.joinToString(", ") + "]"

    /** JSON 字符串(带引号):转义引号、反斜杠和控制字符。 */
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }
}
