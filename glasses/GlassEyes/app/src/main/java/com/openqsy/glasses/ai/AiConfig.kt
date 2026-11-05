package com.openqsy.glasses.ai

import android.content.Context
import android.util.Log

/**
 * 第三方大模型的接入配置。
 *
 * 只面向「OpenAI 兼容」的服务：`GET {baseUrl}/models` 列模型、`POST {baseUrl}/chat/completions` 问答。
 * 各家官方端点以及绝大多数中转服务都是这个形状，所以只要填 baseUrl + API Key 就够。
 *
 * 配置的录入不靠眼镜本身——眼镜没有键盘，输不了几十位的 Key。改配置请用手机/电脑浏览器打开
 * [com.openqsy.glasses.transfer.QBankUploadServer] 的 `/ai` 页（地址在调试页能看到）。
 *
 * 这份配置是**可选**的：三项没填齐就完全不走网络，识别与题库匹配照旧全程离线。
 */
data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    /** 三项齐全才算可用。 */
    val enabled: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 拼端点：baseUrl 末尾的斜杠统一去掉，避免 `//models`。 */
    fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    /** 给界面/状态页看的脱敏形式。 */
    fun describe(): String = when {
        !enabled -> "未配置"
        else -> "$model @ ${baseUrl.trimEnd('/')}"
    }

    companion object {
        private const val TAG = "AiConfig"
        private const val PREFS = "ai_config"
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "api_key"
        private const val KEY_MODEL = "model"

        fun load(context: Context): AiConfig = runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            AiConfig(
                baseUrl = p.getString(KEY_BASE, "").orEmpty(),
                apiKey = p.getString(KEY_TOKEN, "").orEmpty(),
                model = p.getString(KEY_MODEL, "").orEmpty(),
            )
        }.getOrElse {
            Log.w(TAG, "读配置失败", it)
            AiConfig()
        }

        fun save(context: Context, config: AiConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BASE, config.baseUrl.trim())
                .putString(KEY_TOKEN, config.apiKey.trim())
                .putString(KEY_MODEL, config.model.trim())
                .apply()
        }
    }
}
