package com.openqsy.glasses.quiz

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 题库 JSON → `qbank.idx` 的**设备端**编译器。
 *
 * 与 PC 端 `tools/build_qbank.py` 的 compile_bank() 逐字节等价：
 * 同一份 JSON 编译出的字节必须 md5 相同，否则 [QBankStore] 读不了。
 * 验收方式见 `app/src/test/.../IndexBuilderTest.kt`（比对 Python 产物的 md5）。
 *
 * 为什么要搬到设备上：换题库时用户手里通常只有 JSON，让眼镜自己编译，
 * 就不必先找台电脑装 Python 再 adb push。
 *
 * 6622 题实测（PC/JVM）：解析 + 建索引约 1.2 s，产物 0.91 MB。
 * 眼镜 CPU 慢 5~10 倍，预计 6~12 s，所以调用方必须放在后台线程并给进度提示。
 *
 * 文件格式（全部 little-endian）：
 * ```
 * header 64B:
 *    0  char[4] magic   = "OQSY"
 *    4  u32 version     = 1
 *    8  u32 nQuestions
 *   12  u32 nTerms
 *   16  u32 postingBytes
 *   20  u32 textBytes
 *   24  u32 offTerms      -> u32[nTerms]      已排序的 bigram key = (c1<<16)|c2
 *   28  u32 offTermPost   -> u32[nTerms+1]    每个 term 在 postings 区的起止偏移
 *   32  u32 offPostings   -> postings 字节块  varint(docId 差值) 序列
 *   36  u32 offTexts      -> 文本池(UTF-8)    stem/answer/subject 共用
 *   40  u32 offDocs       -> doc[nQuestions] * 28B
 *   44  u32 docStride     = 28
 *   48..63 reserved
 *
 * doc 记录 28B:
 *    0  u32 stemOff    4  u32 stemLen
 *    8  u32 ansOff    12  u32 ansLen
 *   16  u32 subjOff   20  u32 subjLen
 *   24  u8  typeCode  25..27 pad
 * ```
 */
object IndexBuilder {

    const val HEADER_SIZE = 64
    const val DOC_STRIDE = 28
    private const val VERSION = 1
    private const val MAGIC = "OQSY"

    private val TYPE_CODES = mapOf(
        "单选题" to 1, "单选" to 1,
        "多选题" to 2, "多选" to 2,
        "判断题" to 3, "判断" to 3,
        "填空题" to 4, "填空" to 4,
        "简答题" to 5, "简答" to 5,
    )

    data class Stats(
        val title: String,
        val questions: Int,
        val terms: Int,
        val postingsBytes: Int,
        val textBytes: Int,
        val docsBytes: Int,
        val totalBytes: Int,
        val skippedEmptyStem: Int,
    ) {
        fun summary(): String = buildString {
            if (title.isNotBlank()) append(title).append(" · ")
            append(questions).append(" 题 · ")
            append(terms).append(" 词条 · ")
            append("%.2f".format(totalBytes / 1048576f)).append(" MB")
            if (skippedEmptyStem > 0) append(" · 空题干 ").append(skippedEmptyStem)
        }
    }

    class BuildException(message: String) : Exception(message)

    /**
     * @param jsonText 题库 JSON 全文。支持 `{"questions":[...]}` 与裸数组 `[...]` 两种形态。
     * @return 索引字节 + 统计信息
     * @throws BuildException 结构不对、无题目、题目不是对象
     */
    fun build(jsonText: String): Pair<ByteArray, Stats> {
        val text = jsonText.trim()
        if (text.isEmpty()) throw BuildException("文件是空的")
        if (!text.startsWith("[") && !text.startsWith("{")) {
            throw BuildException("不是 JSON（开头应为 [ 或 {）")
        }

        val title: String
        val questions: JSONArray
        if (text.startsWith("[")) {
            title = ""
            questions = runCatching { JSONArray(text) }
                .getOrElse { throw BuildException("JSON 数组解析失败：${it.message}") }
        } else {
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw BuildException("JSON 对象解析失败：${it.message}") }
            title = root.optJSONObject("meta")?.optString("title").orEmpty()
            questions = root.optJSONArray("questions")
                ?: throw BuildException("JSON 里找不到 questions 数组")
        }
        if (questions.length() == 0) throw BuildException("题库里没有题目")

        // ---------------- 逐题写入文本池与 doc 记录 ----------------
        val texts = ByteArrayOutputStream(1 shl 20)
        val docs = ByteArrayOutputStream(questions.length() * DOC_STRIDE)
        val postings = HashMap<Int, MutableList<Int>>(1 shl 15)
        var skippedEmptyStem = 0

        fun addText(s: String): Long {
            // 高 32 位 = offset，低 32 位 = length（空串返回 0/0 且不写池，与 Python 一致）
            if (s.isEmpty()) return 0L
            val raw = s.toByteArray(Charsets.UTF_8)
            val off = texts.size()
            texts.write(raw, 0, raw.size)
            return (off.toLong() shl 32) or (raw.size.toLong() and 0xFFFFFFFFL)
        }

        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i)
                ?: throw BuildException("第 ${i + 1} 题不是 JSON 对象")

            val stem = rawString(q, "stem").trim()
            val answer = firstNonBlank(rawString(q, "answerText"), rawString(q, "answer")).trim()
            val subject = rawString(q, "subject").trim()
            val typeCode = TYPE_CODES[rawString(q, "type").trim()] ?: 0

            if (stem.isEmpty()) skippedEmptyStem++

            val stemPacked = addText(stem)
            val ansPacked = addText(answer)
            val subjPacked = addText(subject)

            putU32(docs, (stemPacked ushr 32).toInt())
            putU32(docs, stemPacked.toInt())
            putU32(docs, (ansPacked ushr 32).toInt())
            putU32(docs, ansPacked.toInt())
            putU32(docs, (subjPacked ushr 32).toInt())
            putU32(docs, subjPacked.toInt())
            docs.write(typeCode and 0xFF)
            docs.write(0)
            docs.write(0)
            docs.write(0)

            // 只对题干建倒排：题干最稳定，选项/答案会引入噪声
            val keys = TextNormalizer.bigramKeys(TextNormalizer.normalize(stem))
            if (keys.isNotEmpty()) {
                // 同一题内去重（Python 用 set）
                val uniq = HashSet<Int>(keys.size * 2)
                for (k in keys) uniq.add(k)
                for (k in uniq) {
                    postings.getOrPut(k) { ArrayList(4) }.add(i)
                }
            }
        }

        // ---------------- term 表 + postings ----------------
        // Python 的 sorted() 是数值升序；key 是 u32 位模式，Int 可能为负，必须按无符号比较
        val termKeys = postings.keys.sortedWith { a, b ->
            (a.toLong() and 0xFFFFFFFFL).compareTo(b.toLong() and 0xFFFFFFFFL)
        }

        val termPost = ByteArrayOutputStream(1 shl 17)
        val termPostOffsets = IntArray(termKeys.size + 1)
        for ((idx, key) in termKeys.withIndex()) {
            val ids = postings[key]!!
            var last = 0
            for ((j, docId) in ids.withIndex()) {
                writeVarint(termPost, if (j == 0) docId else docId - last)
                last = docId
            }
            termPostOffsets[idx + 1] = termPost.size()
        }

        // ---------------- 拼装 ----------------
        val offTerms = HEADER_SIZE
        val offTermPost = offTerms + 4 * termKeys.size
        val offPostings = offTermPost + 4 * termPostOffsets.size
        val offTexts = offPostings + termPost.size()
        val offDocs = offTexts + texts.size()
        val total = offDocs + docs.size()

        val out = ByteArray(total)
        out[0] = 'O'.code.toByte()
        out[1] = 'Q'.code.toByte()
        out[2] = 'S'.code.toByte()
        out[3] = 'Y'.code.toByte()
        putU32(out, 4, VERSION)
        putU32(out, 8, questions.length())
        putU32(out, 12, termKeys.size)
        putU32(out, 16, termPost.size())
        putU32(out, 20, texts.size())
        putU32(out, 24, offTerms)
        putU32(out, 28, offTermPost)
        putU32(out, 32, offPostings)
        putU32(out, 36, offTexts)
        putU32(out, 40, offDocs)
        putU32(out, 44, DOC_STRIDE)
        // 48..63 保持 0（reserved）

        var pos = offTerms
        for (k in termKeys) {
            putU32(out, pos, k)
            pos += 4
        }
        pos = offTermPost
        for (o in termPostOffsets) {
            putU32(out, pos, o)
            pos += 4
        }
        termPost.toByteArray().copyInto(out, offPostings)
        texts.toByteArray().copyInto(out, offTexts)
        docs.toByteArray().copyInto(out, offDocs)

        val stats = Stats(
            title = title,
            questions = questions.length(),
            terms = termKeys.size,
            postingsBytes = termPost.size(),
            textBytes = texts.size(),
            docsBytes = docs.size(),
            totalBytes = total,
            skippedEmptyStem = skippedEmptyStem,
        )
        return out to stats
    }

    // ------------------------------------------------------------------ 工具

    /** 取字符串字段：null / JSONObject.NULL 都当空串，数字/布尔转字符串（对齐 Python 的 str()）。 */
    private fun rawString(q: JSONObject, key: String): String {
        val v = q.opt(key) ?: return ""
        if (v == JSONObject.NULL) return ""
        return v as? String ?: v.toString()
    }

    private fun firstNonBlank(a: String, b: String): String = if (a.isNotBlank()) a else b

    private fun putU32(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        out[off + 2] = ((v ushr 16) and 0xFF).toByte()
        out[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putU32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v != 0) {
                out.write(b or 0x80)
            } else {
                out.write(b)
                return
            }
        }
    }
}
