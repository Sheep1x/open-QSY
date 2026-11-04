package com.openqsy.glasses.transfer

import android.util.Log
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
 * - `GET  /`        上传页（HTML）
 * - `GET  /status`  当前题库信息（JSON，页面轮询用）
 * - `POST /upload`  multipart/form-data，字段名 `file`
 */
class QBankUploadServer(
    private val port: Int = DEFAULT_PORT,
    private val statusProvider: () -> String,
    private val onUpload: (fileName: String, bytes: ByteArray) -> UploadOutcome,
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
        private const val MAX_UPLOAD_BYTES = 32L * 1024 * 1024
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
    }
}
