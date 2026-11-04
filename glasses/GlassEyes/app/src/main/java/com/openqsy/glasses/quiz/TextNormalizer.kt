package com.openqsy.glasses.quiz

import java.text.Normalizer

/**
 * 文本归一化 —— 必须与 PC 端 `tools/build_qbank.py` 的 normalize()/bigram_keys() **逐字节一致**，
 * 否则建索引与查询时用的 key 不同，检索会失效。
 *
 * 规则：NFKC → 小写 → 仅保留 Unicode 字母(CJK 汉字属于字母) 与 ASCII 数字 0-9。
 *
 * 遍历单位是 **code point 而不是 Char**：Python 的 `for ch in str` 走的是 code point，
 * 而 Kotlin 的 `for (ch in str)` 走 UTF-16 单元。若按 Char 遍历，BMP 外的字符（emoji、
 * 部分生僻字）会被拆成两个代理项，`isLetter` 都为 false 而被丢弃，bigram 边界也和 Python
 * 对不上，最终导致「PC 编出的索引在设备上查不到」。
 */
object TextNormalizer {

    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        val folded = Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            i += Character.charCount(cp)
            // 与 Python 严格等价：ch.isalpha() or ("0" <= ch <= "9")
            if (Character.isLetter(cp) || (cp >= '0'.code && cp <= '9'.code)) {
                sb.appendCodePoint(cp)
            }
            // 标点、空白、符号全部丢弃
        }
        return sb.toString()
    }

    /**
     * 汉字二元组 key：(c1 shl 16) or c2，返回 u32 空间的 Int 位模式（可能为负，排序/比较须按无符号）。
     *
     * 与 Python 一致：遇到 BMP 外字符（cp > 0xFFFF）时**断开** bigram（prev 重置为 -1），
     * 而不是把它当成一个普通字符参与组合。
     */
    fun bigramKeys(normalized: String): IntArray {
        if (normalized.length < 2) return EMPTY_INT
        // code point 数不会超过 UTF-16 长度，故 length 是安全上界
        val out = IntArray(normalized.length)
        var n = 0
        var prev = -1
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            if (cp > 0xFFFF) {
                prev = -1
                continue
            }
            if (prev >= 0) out[n++] = (prev shl 16) or cp
            prev = cp
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private val EMPTY_INT = IntArray(0)
}
