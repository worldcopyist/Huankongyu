package com.huankongyu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAgentTest {

    @Test
    fun parseChatPlanAcceptsValidJson() {
        val plan = ChatAgent.parseChatPlan(
            """{"should_reply":true,"reply_focus":"接住情绪","reply_strategy":"先共情再问","target_length":"short","memory_read":true,"web_search_query":null,"mcp_tool_call":null}"""
        )
        assertNotNull(plan)
        assertTrue(plan!!.shouldReply)
        assertEquals("接住情绪", plan.replyFocus)
        assertEquals("short", plan.targetLength)
        assertTrue(plan.shouldReadMemory)
        assertNull(plan.webSearchQuery)
        assertNull(plan.mcpToolCall)
    }

    @Test
    fun parseChatPlanRejectsProseWithoutPlanFields() {
        assertNull(ChatAgent.parseChatPlan("你好呀，今天过得怎么样？"))
    }

    @Test
    fun parseChatPlanToleratesMarkdownFence() {
        val plan = ChatAgent.parseChatPlan(
            """```json
{"should_reply":false,"reply_focus":"x","reply_strategy":"y","target_length":"medium"}
```"""
        )
        assertNotNull(plan)
        assertFalse(plan!!.shouldReply)
    }

    @Test
    fun shortBanterSkipsPlanner() {
        assertTrue(ChatAgent.shouldSkipPlanner("嗯"))
        assertTrue(ChatAgent.shouldSkipPlanner("好的"))
        assertFalse(ChatAgent.shouldSkipPlanner("今天天气怎么样？"))
        assertFalse(ChatAgent.shouldSkipPlanner("帮我查一下明天的日程"))
    }

    @Test
    fun trimConversationKeepsNewestUnderBudget() {
        val messages = (1..30).map { i ->
            ChatMessage(fromUser = i % 2 == 0, content = "消息${i}内容" + "x".repeat(50))
        }
        val trimmed = ChatAgent.trimConversation(messages, maxChars = 400, maxMessages = 30)
        assertTrue(trimmed.isNotEmpty())
        assertTrue(trimmed.size < messages.size)
        assertEquals(messages.last().id, trimmed.last().id)
    }

    @Test
    fun offlineFallbackStaysInCharacter() {
        val text = ChatAgent.offlineFallbackReply("澜")
        assertFalse(text.contains("异常"))
        assertFalse(text.contains("stack"))
        assertTrue(text.contains("网络"))
    }

    @Test
    fun detectsCustomerServiceVoice() {
        assertTrue(ChatAgent.looksOutOfCharacter("您好，请问有什么可以帮您？"))
        assertTrue(ChatAgent.looksOutOfCharacter("作为AI助手，我建议…"))
        assertFalse(ChatAgent.looksOutOfCharacter("嗯，我在。说吧"))
    }

    @Test
    fun deviceContextHintsAreSelective() {
        val timeOnly = ChatAgent.deviceContextHint("你在吗")
        assertTrue(timeOnly.contains("time"))
        assertFalse(timeOnly.contains("location"))

        val withPlace = ChatAgent.deviceContextHint("我在哪附近")
        assertTrue(withPlace.contains("location"))

        val withCalendar = ChatAgent.deviceContextHint("今天有什么日程")
        assertTrue(withCalendar.contains("calendar"))
    }

    @Test
    fun defaultDirectPlanIsShortAndReplies() {
        val plan = ChatAgent.defaultDirectPlan()
        assertTrue(plan.shouldReply)
        assertEquals("short", plan.targetLength)
    }

    @Test
    fun samplingProfilesDifferAcrossStages() {
        val planner = plannerSampling()
        val reply = replySampling()
        val memory = memorySampling()
        assertTrue(planner.temperature!! < reply.temperature!!)
        assertTrue(memory.temperature!! < reply.temperature!!)
        assertNotNull(planner.maxTokens)
        assertNotNull(reply.maxTokens)
    }

    @Test
    fun splitterSplitsLongSceneParagraphs() {
        val long = "这句话很长。" + "继续说下去。".repeat(20)
        val segments = splitAssistantReply(long, ReplySplitterSettings())
        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it.isNotBlank() })
    }

    @Test
    fun splitterKeepsShortReplyAsOneMessage() {
        val segments = splitAssistantReply("嗯，我在。", ReplySplitterSettings())
        assertEquals(1, segments.size)
    }

    @Test
    fun splitterDropsLiteralNullSegments() {
        val segments = splitAssistantReply("null\n\nnull\n\n你好", ReplySplitterSettings())
        assertEquals(listOf("你好"), segments)
    }

    @Test
    fun deviceToolsParseCallsFromJson() {
        val calls = DeviceTools.parseCalls(
            """[{"name":"device_get_location","arguments":{}},{"name":"evil","arguments":{}}]"""
        )
        assertEquals(1, calls.size)
        assertEquals("device_get_location", calls.first().name)
        assertTrue(DeviceTools.parseCalls("null").isEmpty())
        assertTrue(DeviceTools.parseCalls(null).isEmpty())
    }

    @Test
    fun planParserReadsDeviceToolCalls() {
        val plan = ChatAgent.parseChatPlan(
            """{"should_reply":true,"reply_focus":"x","reply_strategy":"y","target_length":"short","device_tool_calls":[{"name":"device_get_battery","arguments":{}}]}"""
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.deviceToolCalls.size)
        assertEquals("device_get_battery", plan.deviceToolCalls.first().name)
    }
}
