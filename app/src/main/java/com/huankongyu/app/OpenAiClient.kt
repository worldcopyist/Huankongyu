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
    val maxTokens: Int? = null
)

/** Planner: tighter sampling so decisions stay structured. */
internal fun plannerSampling() = ChatSampling(temperature = 0.2f, topP = 0.9f, maxTokens = 512)

/** Reply: more natural variation for companion dialogue. */
internal fun replySampling() = ChatSampling(temperature = 0.85f, topP = 0.95f, maxTokens = 1024)

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
            if (responseCode !in 200..299) error("服务商返回 $responseCode：${body.take(160)}")
            val message = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: error("返回中没有 choices[0].message")
            message.optString("content").trim().ifBlank { error("模型没有返回可显示的文本") }
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
        val connection = openChatConnection(provider, buildChatPayload(model, messages, sampling, stream = true))
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("服务商返回 $responseCode：${err.take(160)}")
            }
            val full = StringBuilder()
            connection.inputStream?.bufferedReader()?.use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (Thread.currentThread().isInterrupted) error("请求已取消")
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (data.isNotEmpty()) {
                            val delta = runCatching {
                                JSONObject(data)
                                    .optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("delta")
                                    ?.optString("content")
                                    .orEmpty()
                            }.getOrDefault("")
                            if (delta.isNotEmpty()) {
                                full.append(delta)
                                onDelta(delta)
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
            full.toString().trim().ifBlank { error("模型没有返回可显示的文本") }
        } finally {
            connection.disconnect()
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
