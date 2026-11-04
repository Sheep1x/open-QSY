package com.openqsy.glasses.quiz

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从二维码里的地址把题库拉下来。
 *
 * 用 [HttpURLConnection] 而不是额外引 OkHttp：需求只有「GET 一个文件 + 报进度」，
 * 系统栈够用，也别再往已经 57 MB 的 APK 里塞东西。
 *
 * 局域网 http://192.168.x.x:8000/xxx.json 和公网 https://... 走同一条路；
 * 局域网是明文 http，需要在 Manifest 打开 usesCleartextTraffic。
 */
object BankDownloader {

    /** 题库再大也不会超过这个数；超了必然是扫错了链接（比如扫到视频）。 */
    const val MAX_BYTES: Long = 40L * 1024 * 1024

    private const val TIMEOUT_MS = 20_000

    /** 进度回调的最小间隔，避免每读 64 KB 就刷一次界面。 */
    private const val PROGRESS_INTERVAL_MS = 150L

    /**
     * 阻塞下载，调用方自己放到 IO 线程。
     *
     * @param onProgress (已收字节, 总字节)；服务端没给 Content-Length 时总字节为 -1。
     * @return 完整文件内容。
     */
    fun download(
        url: String,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json,application/octet-stream,*/*")
            setRequestProperty("User-Agent", "GlassEyes/1.0")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code" + (conn.responseMessage?.let { " $it" } ?: ""))
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            if (total > MAX_BYTES) {
                throw IOException("文件过大：${total / 1024 / 1024} MB")
            }
            val out = ByteArrayOutputStream(if (total in 1..Int.MAX_VALUE.toLong()) total.toInt() else 1 shl 16)
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var received = 0L
                var lastTick = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    received += n
                    if (received > MAX_BYTES) throw IOException("文件过大，已中止")
                    out.write(buf, 0, n)
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                        lastTick = now
                        onProgress(received, total)
                    }
                }
                onProgress(received, total)
            }
            return out.toByteArray()
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
