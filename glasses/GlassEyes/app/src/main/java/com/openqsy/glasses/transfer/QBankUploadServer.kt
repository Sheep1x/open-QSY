package com.openqsy.glasses.transfer

import android.util.Log
import com.openqsy.glasses.ai.AiClient
import com.openqsy.glasses.ai.AiConfig
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网题库上传服务 —— 手机浏览器直接把 `qbank.idx` / `题库.json` 传进眼镜。
 *
 * 为什么不用 MTP：眼镜的 USB 只暴露 `adb` 功能（`sys.usb.config=adb`），
 * 资源管理器里根本看不到存储，只能靠命令行 adb push。
 * 而眼镜本身能连 WiFi，于是起一个极小的 HTTP 服务，用手机浏览器当文件选择器，
 * 传完立刻热切换题库，不用连线、不用敲命令、不用重启应用。
 *
 * 只监听局域网，不连公网；服务只在用户主动打开上传模式时启动，退出即关闭。
 *
 * 协议：
 * - `GET  /`          上传页（HTML）
 * - `GET  /status`    当前题库信息（JSON，页面轮询用）
 * - `POST /upload`    multipart/form-data，字段名 `file`
 * - `GET  /ai`        AI 配置页（HTML；眼镜没有键盘，第三方 API 的 baseUrl/Key 在这里填）
 * - `GET  /ai/status` 当前 AI 配置（脱敏）
 * - `POST /ai/models` `{"baseUrl","apiKey"}` → 拉取可用模型列表
 * - `POST /ai/save`   `{"baseUrl","apiKey","model"}` → 保存配置
 * - `POST /ai/clear`  清空配置，回到纯本地（题库没答案时就只显示「没找到」）
 */
class QBankUploadServer(
    private val port: Int = DEFAULT_PORT,
    private val statusProvider: () -> String,
    private val onUpload: (fileName: String, bytes: ByteArray) -> UploadOutcome,
    private val aiConfigProvider: () -> AiConfig = { AiConfig() },
    private val onAiConfigSave: (AiConfig) -> Unit = {},
) {

    sealed interface UploadOutcome {
        /** 已接受，[message] 给用户看，[detail] 是补充信息（题数、体积等）。 */
        data class Ok(val message: String, val detail: String = "") : UploadOutcome
        data class Err(val message: String) : UploadOutcome
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "qbank-upload").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    /** 给界面显示的访问地址。 */
    fun url(): String = "http://${localIpv4() ?: "设备IP"}:$port"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 16)
            serverSocket = ss
            pool.execute { acceptLoop(ss) }
            Log.i(TAG, "上传服务已启动 ${url()}")
        } catch (t: Throwable) {
            running.set(false)
            Log.e(TAG, "上传服务启动失败", t)
            throw t
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        Log.i(TAG, "上传服务已停止")
    }

    fun shutdown() {
        stop()
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------ 连接

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "accept 失败", t)
                break
            }
            runCatching { pool.execute { handle(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = SOCKET_TIMEOUT_MS
                val input = s.getInputStream()
                val head = readHead(input) ?: return@use
                val requestLine = head.lineSequence().firstOrNull().orEmpty()
                val parts = requestLine.split(' ')
                if (parts.size < 2) return@use
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')

                val headers = parseHeaders(head)
                val output = BufferedOutputStream(s.getOutputStream())

                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        respond(output, 200, "text/html; charset=utf-8", PAGE_HTML)

                    method == "GET" && path == "/status" ->
                        respond(output, 200, "application/json; charset=utf-8", statusProvider())

                    method == "POST" && path == "/upload" -> {
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        if (length <= 0L) {
                            respondJson(output, 400, err("没有收到文件内容"))
                            return@use
                        }
                        if (length > MAX_UPLOAD_BYTES) {
                            respondJson(output, 413, err("文件太大（上限 ${MAX_UPLOAD_BYTES / 1048576} MB）"))
                            return@use
                        }
                        val body = readExactly(input, length.toInt())
                        val boundary = boundaryOf(headers["content-type"].orEmpty())
                        if (boundary == null) {
                            respondJson(output, 400, err("不是 multipart/form-data 请求"))
                            return@use
                        }
                        val part = Multipart.parse(body, boundary)
                        if (part == null || part.data.isEmpty()) {
                            respondJson(output, 400, err("没解析到文件，请重新选择"))
                            return@use
                        }
                        val outcome = runCatching { onUpload(part.fileName, part.data) }
                            .getOrElse { UploadOutcome.Err("处理失败：${it.message ?: it.javaClass.simpleName}") }
                        when (outcome) {
                            is UploadOutcome.Ok -> respondJson(
                                output, 200,
                                """{"ok":true,"message":${quote(outcome.message)},"detail":${quote(outcome.detail)}}""",
                            )

                            is UploadOutcome.Err -> respondJson(output, 200, err(outcome.message))
                        }
                    }

                    method == "GET" && path == "/ai" ->
                        respond(output, 200, "text/html; charset=utf-8", AI_HTML)

                    method == "GET" && path == "/ai/status" -> {
                        val cfg = aiConfigProvider()
                        respondJson(
                            output, 200,
                            """{"ok":true,"configured":${cfg.enabled},"baseUrl":${quote(cfg.baseUrl)},""" +
                                """"model":${quote(cfg.model)},"hasKey":${cfg.apiKey.isNotBlank()},""" +
                                """"online":${localIpv4() != null}}""",
                        )
                    }

                    method == "POST" && path == "/ai/models" -> {
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        if (length <= 0L || length > MAX_JSON_BODY_BYTES) {
                            respondJson(output, 400, err("请求体为空或太大"))
                            return@use
                        }
                        val json = runCatching { JSONObject(String(readExactly(input, length.toInt()), Charsets.UTF_8)) }
                            .getOrNull()
                        if (json == null) {
                            respondJson(output, 400, err("请求体不是 JSON"))
                            return@use
                        }
                        val baseUrl = json.optString("baseUrl").trim()
                        // Key 留空 = 沿用已存的那份，省得每次都重新粘一遍
                        val apiKey = json.optString("apiKey").trim().ifEmpty { aiConfigProvider().apiKey }
                        runCatching { AiClient.listModels(baseUrl, apiKey) }
                            .onSuccess { models ->
                                val arr = models.joinToString(",") { quote(it) }
                                respondJson(output, 200, """{"ok":true,"models":[$arr]}""")
                            }
                            .onFailure { e ->
                                respondJson(output, 200, err(e.message ?: "拉取模型失败"))
                            }
                    }

                    method == "POST" && path == "/ai/save" -> {
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        if (length <= 0L || length > MAX_JSON_BODY_BYTES) {
                            respondJson(output, 400, err("请求体为空或太大"))
                            return@use
                        }
                        val json = runCatching { JSONObject(String(readExactly(input, length.toInt()), Charsets.UTF_8)) }
                            .getOrNull()
                        if (json == null) {
                            respondJson(output, 400, err("请求体不是 JSON"))
                            return@use
                        }
                        // 页面上 Key 留空表示「不修改」，沿用已存的那一份
                        val savedKey = aiConfigProvider().apiKey
                        val inputKey = json.optString("apiKey").trim()
                        val cfg = AiConfig(
                            baseUrl = json.optString("baseUrl").trim(),
                            apiKey = inputKey.ifEmpty { savedKey },
                            model = json.optString("model").trim(),
                        )
                        if (!cfg.enabled) {
                            respondJson(output, 200, err("baseUrl、API Key、模型 三项都要填"))
                            return@use
                        }
                        runCatching { onAiConfigSave(cfg) }
                            .onFailure {
                                Log.w(TAG, "保存 AI 配置失败", it)
                                respondJson(output, 200, err("保存失败：${it.message ?: "未知原因"}"))
                            }
                            .onSuccess {
                                Log.i(TAG, "AI 配置已保存：${cfg.describe()}")
                                respondJson(
                                    output, 200,
                                    """{"ok":true,"message":"已保存","detail":${quote(cfg.describe())}}""",
                                )
                            }
                    }

                    method == "POST" && path == "/ai/clear" -> {
                        runCatching { onAiConfigSave(AiConfig()) }
                            .onFailure {
                                Log.w(TAG, "清空 AI 配置失败", it)
                                respondJson(output, 200, err("清空失败：${it.message ?: "未知原因"}"))
                            }
                            .onSuccess {
                                Log.i(TAG, "AI 配置已清空（回到纯本地）")
                                respondJson(output, 200, """{"ok":true,"message":"已关闭 AI，识别回到纯本地"}""")
                            }
                    }

                    method == "POST" && path == "/ai/test" -> {
                        val length = headers["content-length"]?.toLongOrNull() ?: 0L
                        if (length <= 0L || length > MAX_JSON_BODY_BYTES) {
                            respondJson(output, 400, err("请求体为空或太大"))
                            return@use
                        }
                        val json = runCatching { JSONObject(String(readExactly(input, length.toInt()), Charsets.UTF_8)) }
                            .getOrNull()
                        if (json == null) {
                            respondJson(output, 400, err("请求体不是 JSON"))
                            return@use
                        }
                        val cfg = aiConfigProvider()
                        if (!cfg.enabled) {
                            respondJson(output, 200, err("还没配置 AI"))
                            return@use
                        }
                        val question = json.optString("question").trim().ifBlank { DEFAULT_AI_TEST_QUESTION }
                        val t0 = System.currentTimeMillis()
                        runCatching { AiClient.ask(cfg, question) }
                            .onSuccess { answer ->
                                val ms = System.currentTimeMillis() - t0
                                Log.i(TAG, "AI 自测成功 ${answer.length}字/${ms}ms")
                                respondJson(
                                    output, 200,
                                    """{"ok":true,"model":${quote(cfg.model)},"ms":$ms,"answer":${quote(answer)}}""",
                                )
                            }
                            .onFailure { e ->
                                val ms = System.currentTimeMillis() - t0
                                Log.w(TAG, "AI 自测失败：${e.message}")
                                respondJson(
                                    output, 200,
                                    """{"ok":false,"model":${quote(cfg.model)},"ms":$ms,"message":${quote(e.message ?: "未知原因")}}""",
                                )
                            }
                    }

                    else -> respond(output, 404, "text/plain; charset=utf-8", "not found")
                }
            }.onFailure { Log.w(TAG, "处理连接失败", it) }
        }
    }

    // ------------------------------------------------------------------ HTTP 工具

    /** 读到空行为止，返回 "请求行 + headers" 的原始文本。 */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream(1024)
        var last4 = 0
        while (buf.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == CRLFCRLF) return buf.toString("UTF-8")
        }
        return null
    }

    private fun parseHeaders(head: String): Map<String, String> = buildMap {
        head.lineSequence().drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                put(line.substring(0, idx).trim().lowercase(), line.substring(idx + 1).trim())
            }
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append(CRLF)
            append("Content-Type: ").append(type).append(CRLF)
            append("Content-Length: ").append(bytes.size).append(CRLF)
            append("Cache-Control: no-store").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun respondJson(out: BufferedOutputStream, code: Int, json: String) =
        respond(out, code, "application/json; charset=utf-8", json)

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "OK"
    }

    private fun err(message: String) =
        """{"ok":false,"message":${quote(message)}}"""

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun boundaryOf(contentType: String): String? {
        if (!contentType.contains("multipart/form-data", ignoreCase = true)) return null
        val marker = "boundary="
        val idx = contentType.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        return contentType.substring(idx + marker.length)
            .substringBefore(';')
            .trim()
            .trim('"')
            .ifEmpty { null }
    }

    // ------------------------------------------------------------------ multipart

    private class Part(val fileName: String, val data: ByteArray)

    private object Multipart {

        fun parse(body: ByteArray, boundary: String): Part? {
            val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
            var start = indexOf(body, delimiter, 0) ?: return null
            while (true) {
                start += delimiter.size
                // 结束标记 "--boundary--"
                if (start + 1 < body.size && body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()) {
                    return null
                }
                // 跳过 CRLF
                if (start + 1 < body.size && body[start] == 13.toByte() && body[start + 1] == 10.toByte()) start += 2

                val headerEnd = indexOf(body, CRLFCRLF_BYTES, start) ?: return null
                val headerText = String(body, start, headerEnd - start, Charsets.UTF_8)
                val dataStart = headerEnd + 4

                val next = indexOf(body, delimiter, dataStart) ?: return null
                // 去掉分隔符前的 CRLF
                var dataEnd = next
                if (dataEnd >= 2 && body[dataEnd - 2] == 13.toByte() && body[dataEnd - 1] == 10.toByte()) {
                    dataEnd -= 2
                }

                val fileName = fileNameOf(headerText)
                val name = nameOf(headerText)
                if (fileName != null || name == "file") {
                    val data = body.copyOfRange(dataStart, dataEnd)
                    if (data.isNotEmpty()) return Part(fileName ?: "upload.bin", data)
                }
                start = next
            }
        }

        private fun fileNameOf(headers: String): String? {
            // Content-Disposition: form-data; name="file"; filename="x.json"
            val idx = headers.indexOf("filename=", ignoreCase = true)
            if (idx < 0) return null
            var v = headers.substring(idx + 9)
            v = v.substringBefore('\r').substringBefore('\n').trim()
            if (v.startsWith("\"")) {
                v = v.substring(1).substringBefore('"')
            } else {
                v = v.substringBefore(';').trim()
            }
            return v.ifEmpty { null }
        }

        private fun nameOf(headers: String): String? {
            val idx = headers.indexOf("name=", ignoreCase = true)
            if (idx < 0) return null
            var v = headers.substring(idx + 5).substringBefore('\r').substringBefore('\n').trim()
            if (v.startsWith("\"")) v = v.substring(1).substringBefore('"') else v = v.substringBefore(';').trim()
            return v.ifEmpty { null }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
            if (needle.isEmpty() || from >= haystack.size) return null
            val last = haystack.size - needle.size
            var i = from
            outer@ while (i <= last) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) {
                        i++
                        continue@outer
                    }
                }
                return i
            }
            return null
        }
    }

    companion object {
        private const val TAG = "QBankUpload"
        const val DEFAULT_PORT = 8080
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** 与 [com.openqsy.glasses.quiz.BankDownloader] 的上限保持一致：题库 json 最大 64 MB。 */
        private const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024

        /** 给 `ai` 这几条接口用：请求体只是几个短字段，给多了没意义。 */
        private const val MAX_JSON_BODY_BYTES = 64L * 1024

        /** `/ai/test` 不带题目时用这一道：通道通不通、模型能不能答，一眼能看出来。 */
        private const val DEFAULT_AI_TEST_QUESTION = "建筑业是物质生产部门，属于第（　）产业。\n" +
            "A. 第一产业\nB. 第二产业\nC. 第三产业\nD. 以上都不是"
        private const val CRLF = "\r\n"
        private const val CRLFCRLF = 0x0D0A0D0A
        private val CRLFCRLF_BYTES = byteArrayOf(13, 10, 13, 10)

        /** 取本机局域网 IPv4，优先 wlan0。 */
        fun localIpv4(): String? = runCatching {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            val ordered = candidates.sortedByDescending { it.name.startsWith("wlan") }
            ordered.asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

        private val PAGE_HTML: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>题眼 · 题库上传</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 0; padding: 20px 16px 40px;
    background: #0a0a0a; color: #d8ffd8;
    font: 15px/1.6 -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  }
  h1 { font-size: 19px; margin: 0 0 4px; color: #7dff7d; letter-spacing: .5px; }
  .sub { color: #6a8f6a; font-size: 13px; margin-bottom: 20px; }
  .card {
    border: 1px solid #1f4d1f; border-radius: 12px;
    background: #0f1a0f; padding: 16px; margin-bottom: 14px;
  }
  .card h2 { font-size: 14px; margin: 0 0 10px; color: #9dff9d; font-weight: 600; }
  #drop {
    border: 2px dashed #2f6b2f; border-radius: 12px;
    padding: 32px 16px; text-align: center; color: #8fbf8f;
    transition: .15s; cursor: pointer;
  }
  #drop.active { border-color: #4dff4d; background: #12240f; color: #bfffff; }
  #drop b { color: #7dff7d; }
  input[type=file] { display: none; }
  .btn {
    display: block; width: 100%; margin-top: 14px;
    padding: 14px; border-radius: 10px; border: 0;
    background: #1f8a1f; color: #fff; font-size: 16px; font-weight: 600;
  }
  .btn:disabled { background: #24401f; color: #5a7a5a; }
  .row { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; padding: 5px 0; }
  .row span:first-child { color: #6a8f6a; }
  .row span:last-child { color: #c8ffc8; text-align: right; word-break: break-all; }
  #log { margin-top: 14px; font-size: 13px; white-space: pre-wrap; word-break: break-all; }
  .ok { color: #7dff7d; } .bad { color: #ff8080; } .info { color: #8fbf8f; }
  .bar { height: 4px; background: #1a2f1a; border-radius: 2px; overflow: hidden; margin-top: 12px; display: none; }
  .bar > i { display: block; height: 100%; width: 0; background: #4dff4d; transition: width .2s; }
  .tip { font-size: 12px; color: #5f7f5f; margin-top: 10px; line-height: 1.7; }
  code { background: #14240f; padding: 1px 5px; border-radius: 4px; color: #9dff9d; }
</style>
</head>
<body>
<h1>题眼 · 题库上传</h1>
<div class="sub">手机与眼镜连同一个 WiFi，把文件传进去即可，传完自动生效</div>

<div class="card">
  <h2>当前题库</h2>
  <div id="status"><div class="row"><span>读取中</span><span>…</span></div></div>
</div>

<div class="card">
  <h2>上传文件</h2>
  <div id="drop">
    点击选择文件<br>或把文件拖到这里<br><br>
    <b>支持 .idx 和 .json</b>
  </div>
  <input type="file" id="file" accept=".idx,.json,application/json,application/octet-stream">
  <button class="btn" id="send" disabled>上传并启用</button>
  <div class="bar" id="bar"><i></i></div>
  <div id="log"></div>
  <div class="tip">
    <code>.idx</code> 是电脑上 <code>build_qbank.py</code> 编译好的索引，直接生效；<br>
    <code>.json</code> 是原始题库，眼镜会自己现场编译，约 10 秒。
  </div>
</div>

<script>
const drop = document.getElementById('drop');
const input = document.getElementById('file');
const send = document.getElementById('send');
const log = document.getElementById('log');
const bar = document.getElementById('bar');
let picked = null;

function say(text, cls) { log.innerHTML = '<span class="' + cls + '">' + text + '</span>'; }
function esc(s) { return String(s).replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }

function pick(f) {
  if (!f) return;
  const name = f.name.toLowerCase();
  if (!name.endsWith('.idx') && !name.endsWith('.json')) {
    say('只接受 .idx 或 .json 文件', 'bad'); picked = null; send.disabled = true; return;
  }
  picked = f;
  drop.innerHTML = '<b>' + esc(f.name) + '</b><br>' + (f.size / 1048576).toFixed(2) + ' MB';
  send.disabled = false;
  say('已选择，点击上方按钮上传', 'info');
}

drop.onclick = () => input.click();
input.onchange = () => pick(input.files[0]);
['dragenter','dragover'].forEach(e => drop.addEventListener(e, ev => { ev.preventDefault(); drop.classList.add('active'); }));
['dragleave','drop'].forEach(e => drop.addEventListener(e, ev => { ev.preventDefault(); drop.classList.remove('active'); }));
drop.addEventListener('drop', ev => { if (ev.dataTransfer.files.length) pick(ev.dataTransfer.files[0]); });

send.onclick = () => {
  if (!picked) return;
  send.disabled = true;
  bar.style.display = 'block';
  bar.firstElementChild.style.width = '0%';
  say('上传中…', 'info');

  const fd = new FormData();
  fd.append('file', picked, picked.name);

  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload');
  xhr.upload.onprogress = e => {
    if (e.lengthComputable) bar.firstElementChild.style.width = (e.loaded / e.total * 100).toFixed(0) + '%';
  };
  xhr.onload = () => {
    bar.firstElementChild.style.width = '100%';
    let r = {};
    try { r = JSON.parse(xhr.responseText); } catch (e) {}
    if (r.ok) {
      say('✓ ' + esc(r.message) + (r.detail ? '\n' + esc(r.detail) : ''), 'ok');
      loadStatus();
    } else {
      say('✗ ' + esc(r.message || '上传失败'), 'bad');
      send.disabled = false;
    }
  };
  xhr.onerror = () => { say('✗ 网络错误，确认手机和眼镜在同一 WiFi', 'bad'); send.disabled = false; };
  xhr.send(fd);
};

function loadStatus() {
  fetch('/status').then(r => r.json()).then(s => {
    const rows = [
      ['题库', s.title || '—'],
      ['题量', s.questions ? s.questions + ' 题' : '—'],
      ['词条', s.terms ? s.terms + ' 个' : '—'],
      ['体积', s.bytes ? (s.bytes / 1048576).toFixed(2) + ' MB' : '—'],
      ['来源', s.source || '—'],
      ['自检', s.selfCheck || '—'],
    ];
    document.getElementById('status').innerHTML = rows
      .map(([k, v]) => '<div class="row"><span>' + k + '</span><span>' + esc(v) + '</span></div>')
      .join('');
  }).catch(() => {
    document.getElementById('status').innerHTML = '<div class="row"><span>读取失败</span><span>—</span></div>';
  });
}

loadStatus();
setInterval(loadStatus, 5000);
</script>
</body>
</html>
"""

    /**
     * AI 配置页。
     *
     * 眼镜没有键盘，几十位的 API Key 不可能在眼镜上敲，所以配置一律走手机/电脑浏览器：
     * 眼镜起这个 HTTP 服务，手机连同一个 WiFi 打开 `http://<眼镜IP>:8080/ai` 填表即可。
     */
    private val AI_HTML: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AI 设置</title>
<style>
 body{font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif;margin:0;padding:20px;background:#0f1216;color:#e6edf3}
 h1{font-size:18px;margin:0 0 8px}
 p.note{font-size:13px;line-height:1.7;color:#8b949e;margin:0 0 6px}
 code{background:#161b22;padding:1px 4px;border-radius:4px;font-size:12px}
 label{display:block;font-size:13px;margin:16px 0 6px;color:#c9d1d9}
 input,select{width:100%;box-sizing:border-box;padding:10px;font-size:14px;border-radius:8px;border:1px solid #30363d;background:#161b22;color:#e6edf3}
 input:focus,select:focus{outline:none;border-color:#238636}
 .row{display:flex;gap:10px;margin-top:20px}
 button{flex:1;padding:12px;font-size:15px;border-radius:8px;border:0;background:#238636;color:#fff}
 button.ghost{background:#21262d;border:1px solid #30363d}
 button:disabled{opacity:.45}
 #msg{margin-top:16px;font-size:14px;line-height:1.6;min-height:22px}
 .ok{color:#3fb950}.err{color:#f85149}.dim{color:#8b949e}
</style>
</head>
<body>
<h1>AI 设置</h1>
<p class="note">只在题库里<strong>没找到</strong>这道题时才调用；题库命中时一律走本地答案——不联网、更快、更准。</p>
<p class="note">要求兼容 OpenAI 接口：<code>GET {baseUrl}/models</code>、<code>POST {baseUrl}/chat/completions</code>。</p>
<p class="note">三项都空着 = 完全离线，和以前一样。</p>

<label for="base">baseUrl</label>
<input id="base" placeholder="https://api.example.com/v1" autocomplete="off" spellcheck="false">

<label for="key">API Key</label>
<input id="key" type="password" placeholder="sk-..." autocomplete="off" spellcheck="false">

<label for="model">模型</label>
<select id="model"><option value="">— 先点「拉取模型」 —</option></select>
<input id="modelText" placeholder="也可以直接手填模型名" autocomplete="off" spellcheck="false" style="margin-top:8px">

<div class="row">
 <button class="ghost" id="pull">拉取模型</button>
 <button id="save">保存</button>
</div>
<div class="row" style="margin-top:10px">
 <button class="ghost" id="test">测一次（示例题）</button>
 <button class="ghost" id="clear">关闭 AI（清空配置）</button>
</div>
<div id="msg" class="dim"></div>

<script>
var q = function (id) { return document.getElementById(id); };
function say(t, c) { var m = q('msg'); m.className = c || 'dim'; m.textContent = t; }

function fillModels(models, current) {
  var sel = q('model');
  sel.innerHTML = '';
  var ph = document.createElement('option');
  ph.value = ''; ph.textContent = '— 选择模型 —';
  sel.appendChild(ph);
  models.forEach(function (m) {
    var o = document.createElement('option');
    o.value = m; o.textContent = m;
    if (m === current) o.selected = true;
    sel.appendChild(o);
  });
}

function loadStatus() {
  fetch('/ai/status').then(function (r) { return r.json(); }).then(function (r) {
    if (r.baseUrl) q('base').value = r.baseUrl;
    if (r.hasKey) q('key').placeholder = '已保存（留空 = 不修改）';
    if (r.model) { fillModels([r.model], r.model); q('modelText').value = r.model; }
    if (!r.online) {
      say('⚠ 眼镜当前没有联网（WiFi 未连）：拉模型和问 AI 都会失败，先让眼镜连上 WiFi', 'err');
    } else {
      say(r.configured ? '当前生效：' + r.model + ' @ ' + r.baseUrl : '当前未配置 AI（完全离线）');
    }
  }).catch(function (e) { say('读取状态失败：' + e, 'err'); });
}

q('pull').onclick = function () {
  var baseUrl = q('base').value.trim(), apiKey = q('key').value.trim();
  if (!baseUrl) { say('请先填 baseUrl', 'err'); return; }
  q('pull').disabled = true;
  say('正在拉取模型…');
  fetch('/ai/models', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({baseUrl: baseUrl, apiKey: apiKey})
  }).then(function (r) { return r.json(); }).then(function (r) {
    if (!r.ok) { say(r.message || '拉取失败', 'err'); return; }
    fillModels(r.models, q('modelText').value);
    say('拿到 ' + r.models.length + ' 个模型，选一个再保存', 'ok');
  }).catch(function (e) { say('拉取失败：' + e, 'err'); })
    .finally(function () { q('pull').disabled = false; });
};

q('save').onclick = function () {
  var baseUrl = q('base').value.trim();
  var apiKey = q('key').value.trim();
  var model = (q('model').value || q('modelText').value).trim();
  if (!baseUrl || !model) { say('baseUrl 和模型都要填', 'err'); return; }
  q('save').disabled = true;
  fetch('/ai/save', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({baseUrl: baseUrl, apiKey: apiKey, model: model})
  }).then(function (r) { return r.json(); }).then(function (r) {
    if (!r.ok) { say(r.message || '保存失败', 'err'); return; }
    say('已保存：' + r.detail, 'ok');
    q('modelText').value = model;
  }).catch(function (e) { say('保存失败：' + e, 'err'); })
    .finally(function () { q('save').disabled = false; });
};

q('test').onclick = function () {
  q('test').disabled = true;
  say('正在问模型，可能要十几秒…');
  fetch('/ai/test', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'})
    .then(function (r) { return r.json(); }).then(function (r) {
      if (!r.ok) { say('失败：' + r.message, 'err'); return; }
      say('成功（' + r.model + ' · ' + r.ms + 'ms）\n' + r.answer, 'ok');
    }).catch(function (e) { say('请求失败：' + e, 'err'); })
    .finally(function () { q('test').disabled = false; });
};

q('clear').onclick = function () {
  if (!confirm('清空后，题库里没答案时就只显示「没找到」，不再调用 AI。确定？')) return;
  q('clear').disabled = true;
  fetch('/ai/clear', {method: 'POST'})
    .then(function (r) { return r.json(); }).then(function (r) {
      if (!r.ok) { say(r.message || '清空失败', 'err'); return; }
      q('base').value = ''; q('key').value = ''; q('modelText').value = '';
      q('key').placeholder = 'sk-...';
      fillModels([], '');
      say(r.message, 'ok');
    }).catch(function (e) { say('清空失败：' + e, 'err'); })
    .finally(function () { q('clear').disabled = false; });
};

q('model').onchange = function () { if (q('model').value) q('modelText').value = q('model').value; };
loadStatus();
</script>
</body>
</html>
"""
    }
}
