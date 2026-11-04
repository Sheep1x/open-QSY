package com.openqsy.glasses.quiz

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/** 一条题库命中结果。 */
data class QuizHit(
    val docId: Int,
    val score: Float,
    val stem: String,
    val answer: String,
    val subject: String,
)

/**
 * 离线题库索引读取 + 检索（纯 JVM/Android，无第三方依赖，无需 SQLite）。
 *
 * 文件格式见 `tools/build_qbank.py` 顶部注释；这里是与之一一对应的读取实现。
 * 6622 题实测：索引 0.91 MB，查询 1~3 ms（眼镜端 CPU 约慢 5~10 倍，仍在 20 ms 内）。
 *
 * 打分：
 *   score1 = Σ idf(命中项) / Σ idf(全部查询项)      ← IDF 加权查询覆盖率
 *   score2 = |题干bigram ∩ 查询bigram| / |题干bigram|  ← 题干包含度
 *   final  = (1 - alpha) * score1 + alpha * score2,  alpha = 0.6
 */
class QBankStore private constructor(private val bytes: ByteArray) {

    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val version: Int
    val docCount: Int
    val termCount: Int
    private val offTerms: Int
    private val offTermPost: Int
    private val offPostings: Int
    private val offTexts: Int
    private val offDocs: Int
    private val docStride: Int

    init {
        require(bytes.size >= HEADER_SIZE) { "qbank.idx 太小，可能未打包进 assets" }
        require(
            bytes[0] == 'O'.code.toByte() && bytes[1] == 'Q'.code.toByte() &&
                bytes[2] == 'S'.code.toByte() && bytes[3] == 'Y'.code.toByte()
        ) { "qbank.idx 魔数不匹配（不是本工程编译出的索引）" }
        version = i32(4)
        require(version == 1) { "不支持的索引版本: $version" }
        docCount = i32(8)
        termCount = i32(12)
        offTerms = i32(24)
        offTermPost = i32(28)
        offPostings = i32(32)
        offTexts = i32(36)
        offDocs = i32(40)
        docStride = i32(44)
        require(docStride == 28) { "doc 记录长度不符: $docStride" }
    }

    // ------------------------------------------------------------------ 检索

    fun search(queryText: String, topK: Int = 40, alpha: Float = ALPHA): List<QuizHit> {
        val keys = TextNormalizer.bigramKeys(TextNormalizer.normalize(queryText))
        if (keys.isEmpty() || docCount == 0) return emptyList()

        val scores = FloatArray(docCount)
        var totalIdf = 0f

        // 去重查询项，避免重复 bigram 重复加权
        val seen = HashSet<Int>(keys.size * 2)
        for (key in keys) {
            if (!seen.add(key)) continue
            val ti = termOffset(key)
            if (ti < 0) continue

            val start = i32(offTermPost + ti * 4)
            val end = i32(offTermPost + (ti + 1) * 4)
            val from = offPostings + start
            val to = offPostings + end
            if (from >= to) continue

            // 第一遍：数 df（postings 用 varint 差值编码，必须顺序解码）
            var df = 0
            var pos = from
            while (pos < to) {
                pos = skipVarint(pos)
                df++
            }
            if (df == 0) continue

            val idf = ln(1.0 + docCount.toDouble() / df).toFloat()
            totalIdf += idf

            // 第二遍：累加分数
            pos = from
            var docId = 0
            var first = true
            while (pos < to) {
                var shift = 0
                var delta = 0
                while (true) {
                    val b = bytes[pos].toInt() and 0xFF
                    pos++
                    delta = delta or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                docId = if (first) delta else docId + delta
                first = false
                if (docId in 0 until docCount) scores[docId] += idf
            }
        }

        if (totalIdf <= 0f) return emptyList()

        // 取 topK 候选（线性选择，避免对 6000+ 文档全排序）
        val work = scores.copyOf()
        val limit = minOf(topK, docCount)
        val picked = ArrayList<QuizHit>(limit)
        for (n in 0 until limit) {
            var bestId = -1
            var bestVal = 0f
            for (i in work.indices) {
                val v = work[i]
                if (v > bestVal) {
                    bestVal = v
                    bestId = i
                }
            }
            if (bestId < 0 || bestVal <= 0f) break
            work[bestId] = 0f

            val stem = docString(bestId, FIELD_STEM)
            val stemSet = HashSet<Int>()
            for (k in TextNormalizer.bigramKeys(TextNormalizer.normalize(stem))) stemSet.add(k)
            var cover = 0f
            if (stemSet.isNotEmpty()) {
                var hit = 0
                for (k in stemSet) if (seen.contains(k)) hit++
                cover = hit.toFloat() / stemSet.size
            }
            val final = (1f - alpha) * (bestVal / totalIdf) + alpha * cover
            picked.add(
                QuizHit(
                    docId = bestId,
                    score = final,
                    stem = stem,
                    answer = docString(bestId, FIELD_ANSWER),
                    subject = docString(bestId, FIELD_SUBJECT),
                )
            )
        }
        picked.sortByDescending { it.score }
        return picked
    }

    // ------------------------------------------------------------------ 内部

    private fun i32(offset: Int): Int = buf.getInt(offset)

    /**
     * 二分查找 bigram key，返回 term 序号，未命中返回 -1。
     *
     * **必须用无符号比较**：bigram key = `(c1 shl 16) or c2`，当 `c1 >= 0x8000`（约 19% 的汉字，
     * 如「一」0x4E00 不会触发、但 0x8000 以上的字会）时结果落进负数区间。
     * 编译端（Python）按无符号升序排列 term 表，若这里按有符号比较，二分会在负数段整体错位，
     * 表现为「识别到文字但永远匹配不上题库」。实测有符号比较的查找成功率只有 67%。
     */
    private fun termOffset(key: Int): Int {
        var lo = 0
        var hi = termCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = i32(offTerms + mid * 4)
            val cmp = compareUnsigned(v, key)
            when {
                cmp == 0 -> return mid
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    private fun compareUnsigned(a: Int, b: Int): Int =
        (a.toLong() and 0xFFFFFFFFL).compareTo(b.toLong() and 0xFFFFFFFFL)

    private fun skipVarint(pos: Int): Int {
        var p = pos
        while (true) {
            val b = bytes[p].toInt() and 0xFF
            p++
            if (b and 0x80 == 0) return p
        }
    }

    private fun docString(docId: Int, field: Int): String {
        val base = offDocs + docId * docStride + field * 8
        val off = i32(base)
        val len = i32(base + 4)
        if (len <= 0 || off < 0) return ""
        return String(bytes, offTexts + off, len, Charsets.UTF_8)
    }

    /**
     * 启动自检：拿第 0 条题目的题干原文去检索，应当命中 docId 0。
     *
     * 这是给真机用的"体检"，专门抓三类问题：
     * 词表排序/比较规则不一致、归一化两侧不一致、索引文件与代码版本不匹配。
     * 一旦不通过，调试页会直接显示「自检 失败」，省得对着「有字数但没结果」猜。
     */
    fun selfCheck(): SelfCheckResult {
        if (docCount == 0) return SelfCheckResult(false, 0f, -1, "题库为空")
        val stem = docString(0, FIELD_STEM)
        if (stem.isBlank()) return SelfCheckResult(false, 0f, -1, "第 0 条题干为空")
        val top = search(stem, topK = 3).firstOrNull()
            ?: return SelfCheckResult(false, 0f, -1, "查询无任何候选")
        val ok = top.docId == 0
        return SelfCheckResult(
            ok = ok,
            topScore = top.score,
            topDocId = top.docId,
            message = if (ok) "通过" else "失败：题干检索不到自身（排序/归一化不一致）",
        )
    }

    /** 便于调试：索引统计信息。 */
    fun statsText(): String = "索引 v$version · $docCount 题 · $termCount 词组 · " +
        "${bytes.size / 1024} KB"

    companion object {
        const val ALPHA = 0.6f
        private const val HEADER_SIZE = 64
        private const val FIELD_STEM = 0
        private const val FIELD_ANSWER = 1
        private const val FIELD_SUBJECT = 2

        fun fromBytes(bytes: ByteArray): QBankStore = QBankStore(bytes)

        /** 从 APK assets 加载（主线程勿调用，1 MB 读取约 10~30 ms）。 */
        fun loadFromAssets(context: Context, assetName: String = "qbank.idx"): QBankStore {
            val data = context.assets.open(assetName).use { it.readBytes() }
            return QBankStore(data)
        }

        /** 从外置文件加载（调试时可用 adb push 覆盖题库，免重新安装 APK）。 */
        fun loadFromFile(file: File): QBankStore = QBankStore(file.readBytes())
    }
}

/** 索引自检结果。 */
data class SelfCheckResult(
    val ok: Boolean,
    val topScore: Float,
    val topDocId: Int,
    val message: String,
)
