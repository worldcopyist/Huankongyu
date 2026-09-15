package com.huankongyu.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Sampling knobs for OpenAI-compatible chat completions. */
internal data class ChatSampling(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    /** Ask the provider to enable its built-in web search when supported. */
    val enableModelWebSearch: Boolean = false
)

/** Planner: tighter sampling so decisions stay structured. */
internal fun plannerSampling(enableModelWebSearch: Boolean = false) =
    ChatSampling(temperature = 0.2f, topP = 0.9f, maxTokens = 512, enableModelWebSearch = enableModelWebSearch)

/** Reply: more natural variation for companion dialogue. */
internal fun replySampling(enableModelWebSearch: Boolean = false) =
    ChatSampling(temperature = 0.85f, topP = 0.95f, maxTokens = 1024, enableModelWebSearch = enableModelWebSearch)

/** Memory summarizer: factual, low variance. */
internal fun memorySampling() = ChatSampling(temperature = 0.3f, topP = 0.9f, maxTokens = 1024)

/** OpenAI-compatible HTTP helpers shared by chat, embeddings, and model management. */
internal object OpenAiClient {
    fun fetchModels(endpoint: String, apiKey: String): List<String> {
        val connection = (URL(endpoint.trimEnd('/') + "/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) error("服务商返回 $responseCode：${body.take(80)}")
            val data = JSONObject(body).optJSONArray("data") ?: error("返回中没有 data 模型列表")
            buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }.distinct().sorted().ifEmpty { error("服务商没有返回可选模型") }
        } finally {
            connection.disconnect()
        }
    }

    /** Verifies that the service accepts this exact model identifier without generating text. */
    fun fetchModelDescriptor(endpoint: String, apiKey: String, model: String) {
        val encodedModel = URLEncoder.encode(model, Charsets.UTF_8.name())
        val connection = (URL(endpoint.trimEnd('/') + "/models/$encodedModel").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            val responseCode = connection.responseCode
            val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) error("服务商返回 $responseCode：${body.take(100)}")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildChatPayload(
        model: String,
        messages: JSONArray,
        sampling: ChatSampling?,
        stream: Boolean
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", messages)
        sampling?.temperature?.let { put("temperature", it.toDouble()) }
        sampling?.topP?.let { put("top_p", it.toDouble()) }
        sampling?.maxTokens?.let { put("max_tokens", it) }
        if (stream) put("stream", true)
        // Provider-native browsing (OpenAI-compatible field). Ignored by providers
        // that do not implement it; the app-owned web_search tool still runs first.
        if (sampling?.enableModelWebSearch == true) {
            put("web_search_options", JSONObject())
        }
    }

    private fun openChatConnection(provider: ApiProvider, payload: JSONObject): HttpURLConnection {
        return (URL(provider.endpoint.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (provider.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            outputStream.use { output -> output.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
    }

    fun requestCompletion(
        provider: ApiProvider,
        model: String,
        messages: JSONArray,
        sampling: ChatSampling? = null
    ): String {
        val connection = openChatConnection(provider, buildChatPayload(model, messages, sampling, stream = false))
        return try {
            val responseCode = connection.responseCode
            val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) error("服务商返回 $responseCode：${body.take(200)}")
            val root = JSONObject(body)
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: error("返回中没有 choices[0].message")
            var content = contentFieldToText(message.opt("content")).trim()
            // Some reasoning models put the final answer under other keys when content is empty.
            if (content.isBlank()) {
                content = contentFieldToText(message.opt("text")).trim()
            }
            if (content.isBlank()) {
                content = contentFieldToText(message.opt("reasoning_content")).trim()
                    .takeIf { it.length >= 8 }
                    ?.let { reason ->
                        // Only use the tail of the reasoning as a last resort so the user
                        // still sees something; mark it so it is obvious this is degraded.
                        "（模型未输出正文，以下为思考摘要）\n" + reason.takeLast(300)
                    }.orEmpty()
            }
            if (content.isBlank()) {
                val finish = root.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
                val hasToolCall = message.optJSONArray("tool_calls") != null
                error(
                    buildString {
                        append("模型没有返回可显示的文本")
                        if (hasToolCall) append("；响应里只有 tool_calls")
                        finish?.takeIf { it.isNotBlank() }?.let { append("；finish_reason=$it") }
                        append("。body 片段：").append(body.take(120).replace('\n', ' '))
                    }
                )
            }
            content
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams chat completion deltas via SSE (`data: {...}`).
     * [onDelta] receives each text fragment; returns the full assembled text.
     */
    fun requestCompletionStream(
        provider: ApiProvider,
        model: String,
        messages: JSONArray,
        sampling: ChatSampling? = null,
        onDelta: (String) -> Unit
    ): String {
        val streamed = runCatching { streamOnce(provider, model, messages, sampling, onDelta) }
            .getOrElse { err ->
                // Empty body or broken SSE: fall back to a normal completion so the
                // user still gets a reply instead of “模型没有返回可显示的文本”.
                val fallback = runCatching { requestCompletion(provider, model, messages, sampling) }
                if (fallback.isSuccess) {
                    fallback.getOrThrow()
                } else {
                    throw err
                }
            }
        if (streamed.isNotBlank()) return streamed
        val fallback = requestCompletion(provider, model, messages, sampling)
        return fallback.ifBlank { error("模型没有返回可显示的文本") }
    }

    private fun streamOnce(
        provider: ApiProvider,
        model: String,
        messages: JSONArray,
        sampling: ChatSampling?,
        onDelta: (String) -> Unit
    ): String {
        val connection = openChatConnection(provider, buildChatPayload(model, messages, sampling, stream = true))
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("服务商返回 $responseCode：${err.take(200)}")
            }
            val full = StringBuilder()
            val reasoning = StringBuilder()
            connection.inputStream?.bufferedReader()?.use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (Thread.currentThread().isInterrupted) error("请求已取消")
                    val trimmed = line.trim()
                    val data = when {
                        trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                        trimmed.startsWith("{") && trimmed.contains("\"choices\"") -> trimmed
                        else -> ""
                    }
                    if (data == "[DONE]") break
                    if (data.isNotEmpty()) {
                        val parsed = runCatching { parseStreamDelta(data) }.getOrNull()
                        if (parsed != null) {
                            if (parsed.content.isNotEmpty()) {
                                full.append(parsed.content)
                                onDelta(parsed.content)
                            }
                            if (parsed.reasoning.isNotEmpty()) reasoning.append(parsed.reasoning)
                        }
                    }
                    line = reader.readLine()
                }
            }
            val text = full.toString().trim()
            if (text.isNotBlank()) return text
            // Reasoning-only stream: treat as empty so caller can fall back to non-stream.
            if (reasoning.isNotBlank()) {
                error("模型流式只返回了思考过程，没有正文")
            }
            ""
        } finally {
            connection.disconnect()
        }
    }

    private data class StreamDelta(val content: String, val reasoning: String)

    private fun parseStreamDelta(payload: String): StreamDelta {
        val root = JSONObject(payload)
        if (root.has("error")) {
            val message = root.optJSONObject("error")?.optString("message").orEmpty()
            if (message.isNotBlank()) error(message)
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return StreamDelta("", "")
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return StreamDelta("", "")
        return StreamDelta(
            content = contentFieldToText(delta.opt("content")),
            reasoning = contentFieldToText(delta.opt("reasoning_content") ?: delta.opt("reasoning"))
        )
    }

    /**
     * Pulls display text from one SSE payload.
     * Avoids `optString("content")` which can stringify JSON null as the literal "null"
     * on some providers / org.json builds — that produced multiple “null” chat bubbles.
     */
    internal fun extractStreamContent(payload: String): String {
        val root = JSONObject(payload)
        // Error events mid-stream
        if (root.has("error")) {
            val message = root.optJSONObject("error")?.optString("message").orEmpty()
            if (message.isNotBlank()) error(message)
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return ""
        // Prefer content; ignore reasoning_content / thinking so chain-of-thought never leaks.
        return contentFieldToText(delta.opt("content"))
    }

    private fun contentFieldToText(raw: Any?): String {
        return when {
            raw == null || raw === JSONObject.NULL -> ""
            raw is String -> raw
            raw is JSONArray -> buildString {
                for (i in 0 until raw.length()) {
                    when (val part = raw.opt(i)) {
                        is String -> append(part)
                        JSONObject.NULL, null -> Unit
                        else -> {
                            val text = part as? JSONObject
                            if (text != null) {
                                val t = text.optString("text")
                                if (t.isNotEmpty() && t != "null") append(t)
                            }
                        }
                    }
                }
            }
            else -> {
                val asText = raw.toString()
                if (asText.equals("null", ignoreCase = true)) "" else asText
            }
        }.also { text ->
            // Final guard: never surface the word "null" from a bad delta.
            if (text.equals("null", ignoreCase = true)) return ""
        }
    }

    fun requestEmbedding(provider: ApiProvider, model: String, text: String): List<Float> {
        val payload = JSONObject().apply { put("model", model); put("input", text.take(8_000)) }
        val connection = (URL(provider.endpoint.trimEnd('/') + "/embeddings").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (provider.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("嵌入服务返回 $code：${body.take(160)}")
            val vector = JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding")
                ?: error("嵌入服务没有返回向量")
            List(vector.length()) { index -> vector.optDouble(index).toFloat() }
                .takeIf { it.isNotEmpty() } ?: error("嵌入向量为空")
        } finally {
            connection.disconnect()
        }
    }
}
