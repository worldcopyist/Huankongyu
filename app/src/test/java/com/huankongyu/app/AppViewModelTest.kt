package com.huankongyu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelTest {
    @Test
    fun creatingCharacterStartsWithAnEmptyConversation() {
        val viewModel = AppViewModel()

        viewModel.createCharacter("初夏", "旅行搭档", "开朗")

        assertEquals("初夏", viewModel.selectedCharacter().name)
        assertTrue(viewModel.messagesFor(viewModel.selectedCharacterId).isEmpty())
    }

    @Test
    fun sendingMessageWithoutAChatModelExplainsHowToEnableRealChat() {
        val viewModel = AppViewModel()

        viewModel.sendMessage("你好")

        val messages = viewModel.messagesFor("lan")
        assertEquals(2, messages.size)
        assertTrue(messages.last().content.contains("选择一个聊天模型"))
    }

    @Test
    fun configuredUserNameIsRetainedWhenStartingAConversation() {
        val viewModel = AppViewModel()

        viewModel.updateUserName("Azure")
        viewModel.sendMessage("你好")

        assertEquals("Azure", viewModel.userName)
        assertEquals("你好", viewModel.messagesFor("lan").first().content)
    }

    @Test
    fun signatureIsLimitedToTheHomeHeaderCapacity() {
        val viewModel = AppViewModel()

        viewModel.updateUserSignature("a".repeat(MAX_USER_SIGNATURE_LENGTH + 1))

        assertEquals(MAX_USER_SIGNATURE_LENGTH, viewModel.userSignature.length)
    }

    @Test
    fun pinningAndDeletingCharactersUpdatesTheChatList() {
        val viewModel = AppViewModel()
        viewModel.createCharacter("初夏", "旅行搭档", "开朗")
        val createdId = viewModel.selectedCharacterId

        viewModel.togglePinned(createdId)
        assertTrue(viewModel.characters.first().pinned)
        assertEquals(createdId, viewModel.characters.first().id)

        viewModel.deleteCharacter(createdId)
        assertEquals(1, viewModel.characters.size)
        assertEquals("lan", viewModel.characters.single().id)
    }

    @Test
    fun selectedModelsKeepsEachCapabilityIndependent() {
        val models = SelectedModels()
            .withModel(ModelType.Chat, "chat-model")
            .withModel(ModelType.Embedding, "embedding-model")
            .withModel(ModelType.Vision, "vision-model")
            .withModel(ModelType.Voice, "voice-model")

        assertEquals("chat-model", models.modelFor(ModelType.Chat))
        assertEquals("embedding-model", models.modelFor(ModelType.Embedding))
        assertEquals("vision-model", models.modelFor(ModelType.Vision))
        assertEquals("voice-model", models.modelFor(ModelType.Voice))
    }

    @Test
    fun chatModelAndProviderEnableChatWithoutVisionOrEmbedding() {
        val viewModel = AppViewModel()
        val provider = ApiProvider(name = "测试提供商", endpoint = "https://api.example.com/v1", apiKey = "key")
        viewModel.apiProviders.add(provider)
        viewModel.activeProviderId = provider.id
        viewModel.selectedModels = SelectedModels(chat = "chat-model")

        assertTrue(viewModel.isRemoteChatReady())
        assertEquals(null, viewModel.selectedModels.vision)
        assertEquals(null, viewModel.selectedModels.embedding)
    }

    @Test
    fun replyStageUsesXuantianPersonaAndThePlanResult() {
        val prompt = buildChatReplySystemPrompt(
            xuantianCharacter(), "Azure", "本机时间：2026年8月28日",
            ChatPlan(true, "回应近况", "简短关心", "short")
        )

        assertTrue(prompt.contains("简短关心"))
        assertTrue(prompt.contains("猫娘玄天") || prompt.contains("你是猫娘玄天"))
        assertTrue(prompt.contains("妹妹是蓝天"))
        assertTrue(prompt.contains("冷漠") || prompt.contains("性格"))
        assertTrue(prompt.contains("本机时间：2026年8月28日"))
        // Lean plan block (B2) — no long “执行计划” checklist.
        assertFalse(prompt.contains("【执行计划】"))
    }

    @Test
    fun globalCoreMemoryIsAlwaysPlacedInTheRequiredReplyContext() {
        val prompt = buildChatReplySystemPrompt(
            xuantianCharacter(), "Azure", "无设备信息",
            ChatPlan(true, "回答用户资料", "直接回答", "short"),
            globalCoreMemoryContext = "全局核心档案 1：女儿：蓝天"
        )

        assertTrue(prompt.contains("【用户档案】") || prompt.contains("用户档案"))
        assertTrue(prompt.contains("女儿：蓝天"))
    }

    @Test
    fun rolePersonaCanBeEditedByTheUser() {
        val viewModel = AppViewModel()
        viewModel.createCharacter("测试角色", "朋友", "最初设定")
        val characterId = viewModel.selectedCharacterId

        viewModel.updateCharacter(characterId, "测试角色", "旅伴", "用户自定义的表达方式")

        assertEquals("旅伴", viewModel.selectedCharacter().relationship)
        assertEquals("用户自定义的表达方式", viewModel.selectedCharacter().trait)
    }

    @Test
    fun plannerUsesBehaviorStyleForActionsWithoutReadingRolePersona() {
        val customPrompt = "先听懂用户的情绪，再用自然口语回复"
        val behaviorStyle = "先观察用户情绪，只有能推进对话时才主动参与"
        val prompt = buildChatPlanSystemPrompt("测试角色", "Azure", behaviorStyle, "无设备信息", customPrompt)

        assertTrue(prompt.contains(customPrompt))
        assertTrue(prompt.contains("测试角色"))
        assertTrue(prompt.contains(behaviorStyle))
        assertTrue(!prompt.contains("身份设定："))
        assertTrue(!prompt.contains("性格设定："))
        assertTrue(!prompt.contains("表达方式："))
        assertTrue(prompt.contains("web_search(query)"))
        assertTrue(prompt.contains("web_search_query"))
        assertTrue(prompt.contains("memory_read"))
    }

    @Test
    fun webSearchQueryIsNormalizedForThePlannerTool() {
        assertEquals("今天 北京 天气", normalizeWebSearchQuery("""  今天
            北京   天气  """.trimIndent()))
        assertEquals(null, normalizeWebSearchQuery("null"))
        assertEquals(null, normalizeWebSearchQuery("无"))
    }

    @Test
    fun replySplitterKeepsShortReplyAsOneMessageAndHonorsParagraphs() {
        assertEquals(listOf("我在"), splitAssistantReply("我在"))
        assertEquals(
            listOf("我先想一下", "等会儿告诉你结果"),
            splitAssistantReply("我先想一下\n\n等会儿告诉你结果")
        )
        assertEquals(
            listOf("我先想一下 等会儿告诉你结果"),
            splitAssistantReply("我先想一下\n\n等会儿告诉你结果", ReplySplitterSettings(mode = ReplySplitMode.Length))
        )
    }

    @Test
    fun replySplitterBreaksLongReplyAtNaturalBoundariesWithoutLosingText() {
        val reply = "第一件事我们先把你最在意的问题说清楚，不需要急着下结论。第二件事可以先看看哪些信息已经确认，哪些还需要慢慢补充。第三件事如果你愿意，我们再一起决定下一步怎么做。第四件事今天先照顾好自己的感受，不必立刻解决全部问题。"

        val segments = splitAssistantReply(reply)

        assertTrue(segments.size in 2..3)
        assertEquals(reply, segments.joinToString(separator = ""))
    }

    @Test
    fun replySplitterSettingsAreBoundedAndCanRoundTripThroughStorage() {
        val settings = ReplySplitterSettings(ReplySplitMode.Length, 9, 2, 3).normalized()

        assertEquals(ReplySplitMode.Length, settings.mode)
        assertEquals(5, settings.maxSegments)
        assertEquals(8, settings.minSegmentLength)
        assertEquals(16, settings.maxSegmentLength)
        assertEquals(settings, ReplySplitterSettings.fromStorageValue(settings.toStorageValue()))
    }

    @Test
    fun chatMessageKeepsAnAbsoluteTimestampForMemoryIdleDetection() {
        val message = ChatMessage(fromUser = true, content = "请记住这件事")

        assertTrue(message.createdAt > 0L)
    }
}
