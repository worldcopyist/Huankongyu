package com.huankongyu.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure chat-agent helpers extracted from AppViewModel so they can be unit-tested (A5).
 * Network I/O stays in the ViewModel / OpenAiClient.
 */
internal object ChatAgent {

    /** Planner defaults; also used as the safe fallback plan. */
    fun defaultDirectPlan() = ChatPlan(
        shouldReply = true,
        replyFocus = "自然接住用户刚才这句话",
        replyStrategy = "像熟人一样简短接话，不展开、不总结",
        targetLength = "short",
        shouldReadMemory = false,
        webSearchQuery = null,
        mcpToolCall = null
    )

    /**
     * Short casual turns skip the planner (A7) and go straight to a default plan.
     * Questions and tool-ish keywords still go through the planner.
     */
    fun shouldSkipPlanner(userMessage: String): Boolean {
        val value = userMessage.trim()
        if (value.isEmpty() || value.length > 48) return false
        if (value.contains('?') || value.contains('？')) return false
        val probe = listOf(
            "查", "搜", "帮我", "天气", "新闻", "日历", "日程", "搜索",
            // Device tools must go through the planner so they can be requested.
            "在哪", "位置", "定位", "坐标", "附近", "电量", "电", "摄像头", "相机", "拍照", "机型", "手机型号",
            "气温", "下雨", "刮风", "湿度"
        )
        if (probe.any { value.contains(it) }) return false
        return true
    }

    /** Keeps recent history under a character budget for the reply stage (A4). */
    fun trimConversation(
        conversation: List<ChatMessage>,
        maxChars: Int = 4_000,
        maxMessages: Int = 24
    ): List<ChatMessage> {
        val recent = conversation.takeLast(maxMessages)
        var budget = maxChars
        val kept = ArrayDeque<ChatMessage>()
        for (message in recent.asReversed()) {
            val cost = message.content.length + 8
            if (cost > budget && kept.isNotEmpty()) break
            kept.addFirst(message)
            budget -= cost
        }
        return kept.toList()
    }

    /** Robust plan JSON parser. Returns null when the payload cannot be trusted (A6). */
    fun parseChatPlan(raw: String): ChatPlan? {
        val jsonText = raw.substringAfter('{', raw).substringBeforeLast('}', raw)
            .let { if (raw.contains('{') && raw.contains('}')) "{$it}" else raw }
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        // Require at least one recognizable plan field so free-form prose is not accepted.
        val hasPlanField = json.has("should_reply") || json.has("reply_focus") ||
            json.has("reply_strategy") || json.has("target_length")
        if (!hasPlanField) return null

        val shouldReply = when (val value = json.opt("should_reply")) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> true
        }
        val focus = json.optString("reply_focus")?.trim().orEmpty()
            .ifBlank { "回应用户刚刚表达的内容" }
        val strategy = json.optString("reply_strategy")?.trim().orEmpty()
            .ifBlank { "自然、直接地回应当前话题" }
        val length = json.optString("target_length")?.trim().orEmpty().lowercase()
            .takeIf { it in setOf("short", "medium", "long") } ?: "medium"
        val shouldReadMemory = when (val value = json.opt("memory_read")) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
        val webSearchQuery = normalizeWebSearchQuery(json.optString("web_search_query"))
        val call = json.optJSONObject("mcp_tool_call")?.let { tool ->
            val serverId = tool.optString("server_id").trim()
            val name = tool.optString("name").trim()
            val arguments = tool.optJSONObject("arguments")
            if (serverId.isBlank() || name.isBlank() || arguments == null) null
            else McpToolCall(serverId, name, arguments.toString())
        }
        val deviceCalls = DeviceTools.parseCalls(
            json.optJSONArray("device_tool_calls")?.toString()
                ?: json.optString("device_tool_calls").takeIf { it.isNotBlank() && it != "null" }
        )
        return ChatPlan(
            shouldReply, focus, strategy, length, shouldReadMemory, webSearchQuery, call, deviceCalls
        )
    }

    /**
     * Offline / weak-network fallback line (A8). Keeps the companion in character
     * instead of throwing a raw stack trace at the user.
     */
    fun offlineFallbackReply(characterName: String): String =
        "网络好像不太稳，我这边一时没接上。你先歇会儿，好了再跟我说。"

    /** Detects assistant-breaking “customer service / as an AI” patterns (B6). */
    fun looksOutOfCharacter(text: String): Boolean {
        val lower = text.lowercase()
        val markers = listOf(
            "作为ai", "作为 ai", "作为人工智能", "作为语言模型",
            "as an ai", "as a language model", "i'm an ai", "i am an ai",
            "作为助手", "客服", "您好，请问有什么可以帮您",
            "根据您的描述", "综上所述", "希望以上回答对您有帮助",
            "抱歉，我无法", "很抱歉，我不能"
        )
        return markers.any { lower.contains(it) }
    }

    /**
     * Soft rewrite request used when a reply breaks character (B6).
     * Facts are preserved; only tone is corrected.
     */
    fun buildDehumanizeRewritePrompt(original: String, character: Character): String = """
你是润色器。下面这段话可能出戏（像客服或 AI）。请只改口吻，保留事实与意思，改写成「${character.name}」的说话方式。
不要解释，只输出改写后的消息。

表达方式：
${character.replyStyle.ifBlank { "自然口语，简短" }}

原文：
$original
    """.trimIndent()

    /** Picks a compact device context slice based on the latest user message (B7). */
    fun deviceContextHint(userMessage: String): String {
        val value = userMessage
        val wantsTime = listOf("几点", "时间", "今天", "日期", "星期", "节日", "农历").any { value.contains(it) }
        val wantsPlace = listOf("在哪", "位置", "附近", "地图", "导航").any { value.contains(it) }
        val wantsCalendar = listOf("日程", "日历", "安排", "会议").any { value.contains(it) }
        return buildList {
            add("time")
            if (wantsTime) add("festival")
            if (wantsCalendar) add("calendar")
            if (wantsPlace) add("location")
            // Shizuku only when the user asks about device/system status.
            if (listOf("shizuku", "系统", "权限", "设备").any { value.contains(it) }) add("shizuku")
        }.joinToString(",")
    }
}
