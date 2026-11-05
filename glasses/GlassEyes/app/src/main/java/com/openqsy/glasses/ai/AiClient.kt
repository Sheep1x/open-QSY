package com.openqsy.glasses.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容端点的最小客户端：列模型 + 单轮问答。
 *
 * 刻意不引 OkHttp/Retrofit——APK 已经 72 MB，这是两条 HTTP 请求的事。
 * 也刻意不用流式：眼镜上只有一块小屏，答案要完整一段才好排版，`stream=false` 更省事。
 */
object AiClient {
    private const val TAG = "AiClient"
    private const val LIST_TIMEOUT_MS = 12_000
    private const val CHAT_TIMEOUT_MS = 45_000
    private const val MAX_RESPONSE_BYTES = 512 * 1024

    /** 答案最多留几行——屏上答案区就七八行。 */
    private const val MAX_ANSWER_LINES = 8

    /** 带着用户能看懂的原因抛出去，界面直接展示 message。 */
    class AiException(message: String) : Exception(message)

    private val SYSTEM_PROMPT = """
        你是考试答题助手。用户用摄像头拍了一道题，OCR 出来的文字可能有错字、漏字、串行。

        输出格式（严格遵守，第 6 行之后的内容会被程序直接丢弃）：
        第 1 行：只写答案。选择题写「字母. 选项内容」，多选按字母顺序一行一个。
        第 2~5 行：说明理由，最多两句话。

        正确输出示例（照这个样子写）：
        B. 第二产业
        建筑业是物质生产部门，直接从事建筑安装工程，因此属于第二产业。

        铁律：
        - 不要复述题目，不要逐个列举选项，不要描述你是如何还原文字的，不要展开推导步骤。
        - 不要在末尾再重复一遍答案。
        - 如果题目信息不足（缺图、缺数据、错漏太多以至于选不出答案），
          只输出一行「题目信息不足」，不要再解释原因。
    """.trimIndent()

    /** `GET {baseUrl}/models`，返回模型 id 列表。 */
    fun listModels(baseUrl: String, apiKey: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw AiException("baseUrl 是空的")
        if (!base.startsWith("http")) throw AiException("baseUrl 要以 http:// 或 https:// 开头")
        if (apiKey.isBlank()) throw AiException("API Key 是空的")

        val text = request("${base}/models", "GET", apiKey, null, LIST_TIMEOUT_MS)
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw AiException("返回的不是 JSON：${text.take(120)}")
        }
        val data = root.optJSONArray("data") ?: root.optJSONArray("models")
            ?: throw AiException("返回里没有 data/models 数组")
        val ids = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i)
            val id = item?.optString("id").orEmpty().ifBlank {
                data.optString(i, "")
            }
            if (id.isNotBlank()) ids.add(id)
        }
        if (ids.isEmpty()) throw AiException("这个地址没有返回任何模型")
        return ids.distinct().sorted()
    }

    /**
     * 问一道题。[question] 是 OCR 认到的原文。
     *
     * 返回模型给的正文（已去掉首尾空白）。
     */
    fun ask(config: AiConfig, question: String): String {
        if (!config.enabled) throw AiException("没有配置 AI")
        val url = config.endpoint("/chat/completions")
        // 先请求「关掉思考」。实测 deepseek 的推理型号默认会用五千多个思考 token（90 秒）
        // 才肯出正文，关掉之后 3.5~7 秒就答完，且正文更简明。
        val text = try {
            request(url, "POST", config.apiKey, buildPayload(config, question, true), CHAT_TIMEOUT_MS)
        } catch (e: AiException) {
            // 不认这个字段的服务商会直接 400/422，此时退回不带它的原始请求
            val msg = e.message.orEmpty()
            if (msg.startsWith("HTTP 400") || msg.startsWith("HTTP 422")) {
                Log.i(TAG, "服务端不接受 thinking 字段，改用默认请求：$msg")
                request(url, "POST", config.apiKey, buildPayload(config, question, false), CHAT_TIMEOUT_MS)
            } else {
                throw e
            }
        }
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw AiException("返回的不是 JSON：${text.take(120)}")
        }
        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let {
            throw AiException(it)
        }
        val choices = root.optJSONArray("choices")
            ?: throw AiException("返回里没有 choices")
        if (choices.length() == 0) throw AiException("模型没有返回内容")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException("choices[0] 里没有 message")
        // 有些兼容服务把 content 返回成 [{type:"text",text:"..."}] 的数组
        val content = when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) {
                    val part = c.optJSONObject(i) ?: continue
                    append(part.optString("text"))
                }
            }
            else -> ""
        }.trim()
        if (content.isEmpty()) {
            Log.w(TAG, "空内容原始返回：${root.toString().take(900)}")
            throw AiException(describeEmptyContent(choices.optJSONObject(0), message))
        }
        Log.i(
            TAG,
            "chat ok: ${root.optString("model")} " +
                "finish=${choices.optJSONObject(0)?.optString("finish_reason")} chars=${content.length}",
        )
        return limitLines(content, MAX_ANSWER_LINES)
    }

    /**
     * 兜一道保险：prompt 里已经要求最多 5 行，但模型经常不当回事（实测 deepseek 会写到 12 行）。
     * 屏幕上答案区一共就七八行，多的留着也只是被裁掉，不如在这里截断并标记。
     */
    private fun limitLines(text: String, max: Int): String {
        val lines = text.lines()
        if (lines.size <= max) return text
        return lines.take(max).joinToString("\n") + "…"
    }

    /**
     * 请求体。[disableThinking] 为真时带上 `thinking: {"type":"disabled"}`。
     *
     * 这是 DeepSeek 系的开关（one-hub 之类的中转站会透传）；别的服务商多半忽略未知字段，
     * 个别严格的会报 400，调用方会去掉该字段重试一次。
     */
    private fun buildPayload(config: AiConfig, question: String, disableThinking: Boolean): String =
        JSONObject().apply {
            put("model", config.model)
            put("temperature", 0)
            // 关掉思考后 2000 足够；万一是关不掉思考的推理模型，这个额度也够它先说几句
            put("max_tokens", 2000)
            put("stream", false)
            if (disableThinking) put("thinking", JSONObject().put("type", "disabled"))
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", question.trim()))
                },
            )
        }.toString()

    /**
     * 模型返回空内容时，尽量说清是哪一种空（界面上只有一行字，详情进日志）。
     *
     * 最常见的两种：推理模型把 max_tokens 全用在思考上（`finish_reason=length`、
     * 正文空、`reasoning_content` 有值），以及选错模型（选了 embedding / 图像模型）。
     */
    private fun describeEmptyContent(choice: JSONObject?, message: JSONObject?): String {
        val finish = choice?.optString("finish_reason").orEmpty()
        val reasoning = message?.optString("reasoning_content").orEmpty()
        return when {
            finish == "length" ->
                "模型没说完就被 max_tokens 截断了——多半选到了推理模型，换一个普通对话模型"
            reasoning.isNotBlank() ->
                "模型只回了思考过程，正文是空的——换一个非推理模型"
            finish == "content_filter" -> "内容被服务商的安全策略拦了"
            else -> "模型返回了空内容（finish_reason=${finish.ifBlank { "无" }}），详情见日志"
        }
    }

    private fun request(
        url: String,
        method: String,
        apiKey: String,
        body: String?,
        timeoutMs: Int,
    ): String {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrElse {
            throw AiException("地址不合法：$url")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw AiException("HTTP $code（没有响应体）")
            val text = stream.use { readText(it) }
            if (code !in 200..299) {
                val brief = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }
                    .getOrNull().orEmpty().ifBlank { text.take(200) }
                throw AiException("HTTP $code：$brief")
            }
            return text
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "请求失败 $url", e)
            throw AiException(describeNetworkError(e))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 把网络异常翻成人话。
     *
     * 眼镜最容易踩的是「WiFi 没连上」：此时域名直接解析不了，原始信息是
     * `Unable to resolve host "xxx": No address associated with hostname`，
     * 用户看到这行英文完全不知道该去干什么。
     */
    private fun describeNetworkError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException ->
            "解析不了域名（${e.message ?: "DNS 失败"}）——多半是眼镜没连 WiFi，也可能是域名写错了"
        is java.net.ConnectException ->
            "连不上服务器（${e.message ?: "拒绝连接"}）——检查地址和端口，或者眼镜没联网"
        is java.net.SocketTimeoutException ->
            "等太久没有响应（${e.message ?: "超时"}）——网络太慢或地址不通"
        is javax.net.ssl.SSLException ->
            "HTTPS 握手失败（${e.message ?: "SSL 错误"}）——确认端口是不是 https，或者证书有问题"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private fun readText(input: InputStream): String {
        val buf = BufferedInputStream(input)
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = buf.read(chunk)
            if (n <= 0) break
            out.write(chunk, 0, n)
            if (out.size() > MAX_RESPONSE_BYTES) throw AiException("响应太大（超过 512 KB）")
        }
        return out.toString("UTF-8")
    }
}
