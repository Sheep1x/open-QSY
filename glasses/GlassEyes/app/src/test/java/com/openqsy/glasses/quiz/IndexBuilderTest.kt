package com.openqsy.glasses.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 验收标准：**设备端 IndexBuilder 与 PC 端 build_qbank.py 编译出的字节完全相同**。
 *
 * 只要 md5 对得上，就说明索引格式、term 排序（u32 无符号）、varint、文本池偏移、
 * doc 记录布局全部一致，QBankStore 在眼镜上必然读得出来。
 *
 * 题库 JSON 路径通过系统属性 `qbank.json` 传入（build.gradle.kts 里已配好），
 * 文件不存在时跳过而不是失败——CI 上没有题库也能跑通其余测试。
 */
class IndexBuilderTest {

    private val jsonFile: File
        get() = File(System.getProperty("qbank.json") ?: "../../../题库合并.json")

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `编译结果与 Python 产物逐字节一致`() {
        assumeTrue("题库 JSON 不存在，跳过：${jsonFile.absolutePath}", jsonFile.exists())

        val json = jsonFile.readText()
        val (blob, stats) = IndexBuilder.build(json)

        // Python 侧同一份 JSON 的产物
        assertEquals("total bytes 不一致", 955_004, blob.size)
        assertEquals("题数不一致", 6_622, stats.questions)
        assertEquals("词条数不一致", 13_616, stats.terms)
        assertEquals("空题干数不一致", 0, stats.skippedEmptyStem)
        assertEquals(
            "md5 不一致 —— Kotlin 与 Python 的编译结果不同，设备端会读不出索引",
            "210aeb0b1dc7e43eae7287497ae3ebad",
            md5(blob),
        )
    }

    @Test
    fun `编译产物能被 QBankStore 加载并自检通过`() {
        assumeTrue("题库 JSON 不存在，跳过", jsonFile.exists())

        val (blob, _) = IndexBuilder.build(jsonFile.readText())
        val store = QBankStore.fromBytes(blob)

        assertEquals(6_622, store.docCount)
        val check = store.selfCheck()
        assertTrue("自检失败：${check.message}", check.ok)
    }

    @Test
    fun `裸数组形态的 JSON 也能编译`() {
        val json = """[
            {"stem":"一小球沿斜面下滑，下列说法正确的是","answer":"B","subject":"物理","type":"单选题"},
            {"stem":"完全弹性碰撞后两球速度如何变化","answer":"交换","subject":"物理","type":"单选题"}
        ]"""
        val (blob, stats) = IndexBuilder.build(json)
        assertEquals(2, stats.questions)
        assertTrue("应生成词条", stats.terms > 0)

        val store = QBankStore.fromBytes(blob)
        val hits = store.search("一小球沿斜面下滑下列说法正确的是")
        assertTrue("应命中第 1 题", hits.isNotEmpty())
        assertEquals(0, hits[0].docId)
    }

    @Test
    fun `结构不对时给出可读的错误`() {
        val cases = listOf(
            "" to "空",
            "not json" to "不是 JSON",
            """{"foo":1}""" to "找不到 questions",
            """{"questions":[]}""" to "没有题目",
        )
        for ((input, expect) in cases) {
            val msg = runCatching { IndexBuilder.build(input) }
                .exceptionOrNull()?.message ?: ""
            assertTrue("输入 [$input] 的报错应包含「$expect」，实际是「$msg」", msg.contains(expect))
        }
    }
}
