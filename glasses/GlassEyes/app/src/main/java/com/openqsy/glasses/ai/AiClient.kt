package com.openqsy.glasses.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 端点最小客户端：列模型 + 单轮问答。
 *
 * 提问走 Responses 协议（`/responses`），服务端不支持时退回 `chat/completions`。
 * 刻意不引 OkHttp/Retrofit——APK 已经 72 MB，这是两条 HTTP 请求的事。
 * 也刻意不用流式：眼镜上只有一块小屏，答案要完整一段才好排版。
 */
object AiClient {
    private const val TAG = "AiClient"
    private const val LIST_TIMEOUT_MS = 12_000
    private const val CHAT_TIMEOUT_MS = 45_000
    private const val MAX_RESPONSE_BYTES = 512 * 1024

    /** 答案最多留几行——屏上答案区就七八行。 */
    private const val MAX_ANSWER_LINES = 16

    /** 带着用户能看懂的原因抛出去，界面直接展示 message。 */
    class AiException(message: String) : Exception(message)

    private val SYSTEM_PROMPT = """
        你是考试答题助手。用户戴着眼镜拍下眼前的试卷，你收到的是 OCR 出来的文字
        （可能有错字、漏字、串行）。

        按题型作答：

        一、选择题：只写选项字母，如 A、C、ABD。多选按字母顺序连写。
        二、判断题：只写 ✓ 或 ✕。
        三、应用题 / 计算题 / 简答题：写出详细步骤，一行一步，最后一行写「答案：结果」。

        示例（选择题，就写这么多，不要多写一个字）：
        B

        示例（应用题，照这个样子写）：
        1. 建筑面积 = 套内面积 + 分摊面积 = 120 + 18 = 138 ㎡
        2. 单价 = 总价 ÷ 建筑面积 = 690万 ÷ 138 = 5 万/㎡
        答案：5 万元/㎡

        铁律：
        - 选择题、判断题不要解析，不要展开推导，不要复述题目。
        - 应用题必须给出能跟着算下来的步骤，不能只给结论。
        - 不要描述你是如何还原文字的。
        - 题目信息不足（缺图、缺数据、错漏太多）时，只写「题目信息不足」。
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
     * 走 OpenAI 的 Responses 协议（`POST {base}/responses`）。服务端没有这个接口时
     * （404/405/501，例如只实现了 chat 兼容层的中转站）自动退回 `chat/completions`。
     *
     * 返回模型给的正文（已去掉首尾空白、已截到 [MAX_ANSWER_LINES] 行）。
     */
    fun ask(config: AiConfig, question: String): String {
        if (!config.enabled) throw AiException("没有配置 AI")
        val text = try {
            request(
                config.endpoint("/responses"),
                "POST",
                config.apiKey,
                buildResponsesPayload(config, question),
                CHAT_TIMEOUT_MS,
            )
        } catch (e: AiException) {
            val msg = e.message.orEmpty()
            if (msg.startsWith("HTTP 404") || msg.startsWith("HTTP 405") || msg.startsWith("HTTP 501")) {
                Log.i(TAG, "服务端没有 /responses，退回 /chat/completions：$msg")
                return askChat(config, question)
            }
            throw e
        }
        val root = jsonOf(text)
        errorMessageOf(root)?.let { throw AiException(it) }

        // output[] 里既有 reasoning 项也有 message 项，只取 message 里的 output_text
        val output = root.optJSONArray("output")
        if (output == null) {
            // 有些网关把 /responses 转发到 chat 实现，回的仍是 chat 格式
            if (root.has("choices")) {
                Log.i(TAG, "/responses 回的是 chat 格式，按 chat 解析")
                return parseChatRoot(root)
            }
            throw AiException("返回里没有 output")
        }
        val body = StringBuilder()
        var reasoningChars = 0
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "reasoning" -> reasoningChars += item.toString().length
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        val type = part.optString("type")
                        if (type == "output_text" || type == "text") body.append(part.optString("text"))
                    }
                }
            }
        }
        // 少数网关会把 SDK 的便利字段也一起吐出来
        var content = body.toString().trim()
        if (content.isEmpty()) content = root.optString("output_text").trim()

        if (content.isEmpty()) {
            Log.w(TAG, "空内容原始返回：${root.toString().take(900)}")
            throw AiException(
                describeEmptyResponse(
                    status = root.optString("status"),
                    incomplete = root.optJSONObject("incomplete_details")?.optString("reason").orEmpty(),
                    reasoningTokens = root.optJSONObject("usage")
                        ?.optJSONObject("output_tokens_details")
                        ?.optInt("reasoning_tokens", 0) ?: 0,
                    sawReasoning = reasoningChars > 0,
                ),
            )
        }
        Log.i(
            TAG,
            "responses ok: ${root.optString("model")} status=${root.optString("status")} " +
                "chars=${content.length} reasoning=$reasoningChars",
        )
        return limitLines(content, MAX_ANSWER_LINES)
    }

    /**
     * 退回路径：老式的 `chat/completions`。
     *
     * 先请求「关掉思考」。实测 deepseek 的推理型号默认会用五千多个思考 token（90 秒）
     * 才肯出正文，关掉之后 3.5~7 秒就答完，且正文更简明。
     */
    private fun askChat(config: AiConfig, question: String): String {
        val url = config.endpoint("/chat/completions")
        val text = try {
            request(url, "POST", config.apiKey, buildChatPayload(config, question, true), CHAT_TIMEOUT_MS)
        } catch (e: AiException) {
            // 不认这个字段的服务商会直接 400/422，此时退回不带它的原始请求
            val msg = e.message.orEmpty()
            if (msg.startsWith("HTTP 400") || msg.startsWith("HTTP 422")) {
                Log.i(TAG, "服务端不接受 thinking 字段，改用默认请求：$msg")
                request(url, "POST", config.apiKey, buildChatPayload(config, question, false), CHAT_TIMEOUT_MS)
            } else {
                throw e
            }
        }
        val root = jsonOf(text)
        errorMessageOf(root)?.let { throw AiException(it) }
        return parseChatRoot(root)
    }

    /** 解析 chat/completions 的返回体。抽出来是因为 `/responses` 也可能回这种格式。 */
    private fun parseChatRoot(root: JSONObject): String {
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
     * Responses 协议的请求体。
     *
     * `instructions` 对应 chat 里的 system 消息，`input` 对应 user 消息；
     * `store=false` 明说不要把这轮对话留在服务端。
     */
    private fun buildResponsesPayload(config: AiConfig, question: String): String =
        JSONObject().apply {
            put("model", config.model)
            put("instructions", SYSTEM_PROMPT)
            put("input", question.trim())
            put("temperature", 0)
            put("max_output_tokens", 2000)
            put("store", false)
        }.toString()

    /**
     * chat/completions 的请求体。[disableThinking] 为真时带上 `thinking: {"type":"disabled"}`。
     *
     * 这是 DeepSeek 系的开关（one-hub 之类的中转站会透传）；别的服务商多半忽略未知字段，
     * 个别严格的会报 400，调用方会去掉该字段重试一次。
     */
    private fun buildChatPayload(config: AiConfig, question: String, disableThinking: Boolean): String =
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

    private fun jsonOf(text: String): JSONObject = runCatching { JSONObject(text) }.getOrElse {
        throw AiException("返回的不是 JSON：${text.take(120)}")
    }

    private fun errorMessageOf(root: JSONObject): String? =
        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }

    /**
     * Responses 协议下正文为空时，按 `status` / `incomplete_details.reason` / 思考 token 数判断原因。
     *
     * 最典型的是推理型号：`status=incomplete` + `reason=max_output_tokens`，
     * 整个额度都花在 reasoning 项上，message 项里没有 output_text。
     */
    private fun describeEmptyResponse(
        status: String,
        incomplete: String,
        reasoningTokens: Int,
        sawReasoning: Boolean,
    ): String = when {
        incomplete == "max_output_tokens" || status == "incomplete" ->
            "模型没说完就被额度截断了（思考用了 $reasoningTokens token）——换一个非推理模型"
        sawReasoning || reasoningTokens > 0 ->
            "模型只回了思考过程，正文是空的——换一个非推理模型"
        else -> "模型返回了空内容（status=${status.ifBlank { "无" }}），详情见日志"
    }

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
