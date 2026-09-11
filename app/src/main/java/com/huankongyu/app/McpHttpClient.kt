package com.huankongyu.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Minimal Streamable HTTP MCP client. Local stdio servers cannot run inside an Android app. */
internal class McpHttpClient(private val server: McpServer) {
    private var sessionId: String? = null
    private var initialized = false
    private var requestId = 1
    private var protocolVersion = "2025-11-25"

    fun listTools(): List<McpTool> {
        initialize()
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(5) {
            val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            val array = result.optJSONArray("tools") ?: return@repeat
            for (index in 0 until array.length()) {
                val tool = array.optJSONObject(index) ?: continue
                val name = tool.optString("name").trim()
                if (name.isNotBlank()) {
                    tools += McpTool(
                        server.id,
                        name,
                        tool.optString("description").trim(),
                        tool.optJSONObject("inputSchema")?.toString() ?: "{}"
                    )
                }
            }
            cursor = result.optString("nextCursor").trim().takeIf { it.isNotBlank() }
            if (cursor == null) return tools.distinctBy { it.name }
        }
        return tools.distinctBy { it.name }
    }

    fun callTool(name: String, argumentsJson: String): String {
        initialize()
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        val result = request(
            "tools/call",
            JSONObject().apply { put("name", name); put("arguments", arguments) },
            toolName = name
        )
        val content = result.optJSONArray("content")
        val text = buildList {
            if (content != null) for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> item.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                    "image" -> add("[工具返回了一张图片]")
                    "resource" -> add(item.optJSONObject("resource")?.optString("text") ?: "[工具返回了资源]")
                }
            }
        }.joinToString("\n").trim()
        if (result.optBoolean("isError", false)) error(text.ifBlank { "MCP 工具返回错误" })
        return text.ifBlank { result.toString() }.take(6_000)
    }

    private fun initialize() {
        if (initialized) return
        var lastError: Throwable? = null
        listOf("2025-11-25", "2025-03-26").forEach { version ->
            protocolVersion = version
            val result = runCatching {
                request(
                    "initialize",
                    JSONObject().apply {
                        put("protocolVersion", protocolVersion)
                        put("capabilities", JSONObject())
                        put("clientInfo", JSONObject().apply {
                            put("name", "Huankongyu"); put("version", "1.0")
                        })
                    }
                )
            }.onFailure { lastError = it }.getOrNull() ?: return@forEach
            if (result.optString("protocolVersion").isNotBlank()) {
                notification("notifications/initialized")
                initialized = true
                return
            }
            lastError = IllegalStateException("MCP 服务器没有完成初始化")
        }
        throw lastError ?: IllegalStateException("MCP 初始化失败")
    }

    private fun notification(method: String) {
        post(
            JSONObject().apply {
                put("jsonrpc", "2.0"); put("method", method); put("params", JSONObject())
            },
            method,
            null,
            expectResponse = false
        )
    }

    private fun request(method: String, params: JSONObject, toolName: String? = null): JSONObject {
        val response = post(
            JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", requestId++); put("method", method); put("params", params)
            },
            method,
            toolName,
            expectResponse = true
        )
        val json = parseJsonRpcBody(response)
        json.optJSONObject("error")?.let { error ->
            error("MCP 错误 ${error.optInt("code")}：${error.optString("message")}")
        }
        return json.optJSONObject("result") ?: error("MCP 响应中没有 result")
    }

    private fun post(body: JSONObject, method: String, toolName: String?, expectResponse: Boolean): String {
        val connection = (URL(server.endpoint.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", protocolVersion)
            setRequestProperty("Mcp-Method", method)
            if (toolName != null) setRequestProperty("Mcp-Name", toolName)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            if (server.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${server.apiKey}")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.trim()?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("MCP 服务器返回 $code：${response.take(180)}")
            if (expectResponse && response.isBlank()) throw IOException("MCP 服务器没有返回内容")
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonRpcBody(body: String): JSONObject {
        val direct = runCatching { JSONObject(body.trim()) }.getOrNull()
        if (direct != null) return direct
        val eventData = Regex("(?m)^data:\\s*(.+)$").findAll(body).map { it.groupValues[1] }.lastOrNull()
        return eventData?.let { JSONObject(it) } ?: error("无法解析 MCP 响应")
    }
}
