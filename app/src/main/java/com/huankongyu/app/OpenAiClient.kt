package com.huankongyu.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

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

    fun requestCompletion(provider: ApiProvider, model: String, messages: JSONArray): String {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }
        val connection = (URL(provider.endpoint.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (provider.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }
        return try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
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
