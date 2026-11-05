package com.openqsy.glasses.quiz

/**
 * 极简**流式** JSON 扫描器，只为题库编译服务。
 *
 * 为什么不用 `org.json.JSONObject`：它会把整份文档建成对象树，每个字符串都复制一份，
 * 5 万题的题库（约 40 MB）解析峰值能到 400 MB 以上，眼镜上直接被系统杀掉。
 * 这里只做一遍字符扫描，命中需要的字段就取走、其余原地跳过，峰值只剩「原文本 + 文本池」。
 *
 * 语义对齐 `org.json`：
 * - 缺字段 / `null` → 空串；
 * - 同一 key 出现多次 → 以最后一次为准；
 * - 数字按「有没有小数点/指数」分别规范成整数或浮点文本（对齐 Java 的 `toString`）；
 * - 字符串转义（`\" \\ \/ \b \f \n \r \t \uXXXX`）完整解码。
 *
 * 只支持 UTF-16 的 `CharSequence`（调用方已把文件读成 `String`）。
 */
internal object JsonLite {

    /** 一道题里我们关心的字段。复用同一个实例，避免每题都分配一个 Map。 */
    class Fields {
        var stem: String = ""
        var answerText: String = ""
        var answer: String = ""
        var subject: String = ""
        var type: String = ""

        fun reset() {
            stem = ""
            answerText = ""
            answer = ""
            subject = ""
            type = ""
        }
    }

    data class Result(val title: String, val count: Int)

    class ScanException(message: String) : Exception(message)

    /**
     * 扫描整份 JSON，每遇到一道题回调一次 [onQuestion]。
     *
     * @param text 题库 JSON 全文，支持 `{"questions":[...]}` 与裸数组 `[...]`
     * @return 题库名（取自 `meta.title`，没有则为空串）与题目数量
     */
    fun scan(text: CharSequence, onQuestion: (Fields) -> Unit): Result =
        Scanner(text).run(onQuestion)

    private class Scanner(private val s: CharSequence) {

        private val n = s.length
        private var pos = 0
        private val fields = Fields()

        fun run(onQuestion: (Fields) -> Unit): Result {
            skipWs()
            if (pos >= n) throw ScanException("文件是空的")
            var title = ""
            var count = 0
            when (s[pos]) {
                '[' -> count = scanQuestions(onQuestion)
                '{' -> {
                    pos++
                    var sawQuestions = false
                    while (true) {
                        skipWs()
                        if (pos >= n) throw ScanException("JSON 对象没有闭合")
                        when (s[pos]) {
                            '}' -> { pos++; break }
                            ',' -> { pos++; continue }
                        }
                        if (s[pos] != '"') throw ScanException("JSON 的 key 必须是字符串")
                        val key = readString()
                        skipWs()
                        expect(':')
                        skipWs()
                        if (pos >= n) throw ScanException("key「$key」缺少值")
                        when {
                            key == "questions" && s[pos] == '[' -> {
                                count = scanQuestions(onQuestion)
                                sawQuestions = true
                            }
                            key == "meta" && s[pos] == '{' -> title = scanMetaTitle()
                            else -> skipValue()
                        }
                    }
                    if (!sawQuestions) throw ScanException("JSON 里找不到 questions 数组")
                }
                else -> throw ScanException("不是 JSON（开头应为 [ 或 {）")
            }
            return Result(title, count)
        }

        // ---------------------------------------------------------- 具体结构

        /** 读 `{"title": "..."}` 里的 title，其余字段跳过。 */
        private fun scanMetaTitle(): String {
            pos++ // {
            var title = ""
            while (true) {
                skipWs()
                if (pos >= n) throw ScanException("meta 对象没有闭合")
                when (s[pos]) {
                    '}' -> { pos++; return title }
                    ',' -> { pos++; continue }
                }
                if (s[pos] != '"') throw ScanException("meta 的 key 必须是字符串")
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                if (key == "title") {
                    title = readValueAsString()
                } else {
                    skipValue()
                }
            }
        }

        /** 遍历 questions 数组，逐个回调。返回题目数量。 */
        private fun scanQuestions(onQuestion: (Fields) -> Unit): Int {
            pos++ // [
            var count = 0
            while (true) {
                skipWs()
                if (pos >= n) throw ScanException("questions 数组没有闭合")
                when (s[pos]) {
                    ']' -> { pos++; return count }
                    ',' -> { pos++; continue }
                }
                if (s[pos] != '{') throw ScanException("第 ${count + 1} 题不是 JSON 对象")
                fields.reset()
                readQuestion()
                onQuestion(fields)
                count++
            }
        }

        private fun readQuestion() {
            pos++ // {
            while (true) {
                skipWs()
                if (pos >= n) throw ScanException("题目对象没有闭合")
                when (s[pos]) {
                    '}' -> { pos++; return }
                    ',' -> { pos++; continue }
                }
                if (s[pos] != '"') throw ScanException("题目的 key 必须是字符串")
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                if (pos >= n) throw ScanException("题目字段「$key」缺少值")
                when (key) {
                    "stem" -> fields.stem = readValueAsString()
                    "answerText" -> fields.answerText = readValueAsString()
                    "answer" -> fields.answer = readValueAsString()
                    "subject" -> fields.subject = readValueAsString()
                    "type" -> fields.type = readValueAsString()
                    else -> skipValue()
                }
            }
        }

        // ---------------------------------------------------------- 基础读取

        private fun skipWs() {
            while (pos < n) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun expect(c: Char) {
            if (pos >= n || s[pos] != c) throw ScanException("JSON 格式错误：这里应该是 '$c'")
            pos++
        }

        /** 跳过任意值（字符串、对象、数组、数字、字面量）。 */
        private fun skipValue() {
            skipWs()
            if (pos >= n) throw ScanException("JSON 值缺失")
            when (s[pos]) {
                '"' -> readString()
                '{' -> skipObject()
                '[' -> skipArray()
                else -> readScalar()
            }
        }

        private fun skipObject() {
            pos++ // {
            while (true) {
                skipWs()
                if (pos >= n) throw ScanException("JSON 对象没有闭合")
                when (s[pos]) {
                    '}' -> { pos++; return }
                    ',' -> { pos++; continue }
                }
                if (s[pos] != '"') throw ScanException("JSON 的 key 必须是字符串")
                readString()
                skipWs()
                expect(':')
                skipValue()
            }
        }

        private fun skipArray() {
            pos++ // [
            while (true) {
                skipWs()
                if (pos >= n) throw ScanException("JSON 数组没有闭合")
                when (s[pos]) {
                    ']' -> { pos++; return }
                    ',' -> { pos++; continue }
                }
                skipValue()
            }
        }

        /** 读一个值当字符串用：字符串取内容，`null` 当空串，数字/布尔取字面量。 */
        private fun readValueAsString(): String {
            skipWs()
            if (pos >= n) throw ScanException("JSON 值缺失")
            if (s[pos] == '"') return readString()
            val raw = readScalar()
            return when {
                raw == "null" -> ""
                raw == "true" || raw == "false" -> raw
                else -> normalizeNumber(raw)
            }
        }

        /** 数字对齐 Java 的 `Long.toString` / `Double.toString`（org.json 里就是这么存的）。 */
        private fun normalizeNumber(raw: String): String {
            val isFloat = raw.any { it == '.' || it == 'e' || it == 'E' }
            return if (isFloat) raw.toDoubleOrNull()?.toString() ?: raw
            else raw.toLongOrNull()?.toString() ?: raw
        }

        /** 读数字/字面量，返回原文。 */
        private fun readScalar(): String {
            val start = pos
            while (pos < n) {
                when (s[pos]) {
                    ',', ']', '}', ' ', '\t', '\n', '\r' -> break
                    else -> pos++
                }
            }
            if (pos == start) throw ScanException("JSON 值缺失")
            return s.subSequence(start, pos).toString()
        }

        /** 读字符串字面量。无转义时只做一次 `toString`，有转义才逐字符解码。 */
        private fun readString(): String {
            pos++ // "
            val start = pos
            var i = pos
            var escaped = false
            while (i < n) {
                when (s[i]) {
                    '\\' -> { escaped = true; i += 2; continue }
                    '"' -> break
                    else -> i++
                }
            }
            if (i >= n) throw ScanException("字符串没有闭合")
            if (!escaped) {
                val out = s.subSequence(start, i).toString()
                pos = i + 1
                return out
            }

            pos = start
            val sb = StringBuilder(i - start + 16)
            while (pos < n) {
                val c = s[pos]
                if (c == '"') {
                    pos++
                    return sb.toString()
                }
                if (c != '\\') {
                    sb.append(c)
                    pos++
                    continue
                }
                pos++
                if (pos >= n) throw ScanException("转义不完整")
                when (val e = s[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 >= n) throw ScanException("\\u 转义不完整")
                        val hex = s.subSequence(pos + 1, pos + 5).toString()
                        val code = hex.toIntOrNull(16) ?: throw ScanException("\\u 转义非法：$hex")
                        sb.append(code.toChar())
                        pos += 4
                    }
                    else -> throw ScanException("未知转义 \\$e")
                }
                pos++
            }
            throw ScanException("字符串没有闭合")
        }
    }
}
