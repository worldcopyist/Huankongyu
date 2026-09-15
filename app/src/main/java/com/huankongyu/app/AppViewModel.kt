package com.huankongyu.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.huankongyu.app.shizuku.ShizukuClient
import org.json.JSONArray
import org.json.JSONObject

private const val MEMORY_IDLE_MILLIS = 3L * 60 * 1000
private const val MEMORY_MATCH_THRESHOLD = 0.28f
private const val MEMORY_RETRIEVAL_LIMIT = 4
private const val HIGH_RISK_COMMAND_CONFIRMATION_MILLIS = 30_000L

private const val LEGACY_COMMON_CHAT_PROMPT = """【对话任务】先阅读最近聊天记录，理解当前话题、用户意图和情绪，再决定是否回应以及回应重点。你是在和一位真实用户一对一私聊的陪伴型角色，不是客服、旁白、说明书或任务执行器。
【回应决策】优先推进眼前对话。闲聊自然接话；提问先给结论再按需补充；用户需要安慰、建议或解释时再展开。可以表达不确定、关心、不同意见和边界，但不要虚构自己做过、看见过或拥有的事情。
【输出规范】最终只输出适合直接发送给用户的日常中文消息。像熟悉对方的人聊天，避免模板化开场、过度客套、机械复述、连续追问和空泛总结。回复长度随话题变化；除非用户明确要求，不使用 Markdown 标题、项目符号、角色动作旁白或“作为 AI/语言模型”的自我介绍。保持对话连续，不跳出当前角色。"""

private sealed interface LocalCommand {
    data object Help : LocalCommand
    data object SaveCurrentMemory : LocalCommand
    data object ListCurrentMemories : LocalCommand
    data class DeleteMemory(val memoryId: String) : LocalCommand
    data object ClearAllMemories : LocalCommand
    data object DeleteCurrentCharacter : LocalCommand
}

private enum class MemoryConsolidationOutcome { Written, NothingToDo, Failed }

private fun parseLocalCommand(raw: String): LocalCommand? {
    val parts = raw.trim().split(Regex("\\s+"))
    if (parts.isEmpty()) return null
    return when (parts.first().lowercase()) {
        "/help" -> LocalCommand.Help
        "/memory" -> when (parts.getOrNull(1)?.lowercase()) {
            "save" -> LocalCommand.SaveCurrentMemory
            "list" -> LocalCommand.ListCurrentMemories
            "clear" -> LocalCommand.ClearAllMemories
            "delete" -> parts.getOrNull(2)?.let { id -> LocalCommand.DeleteMemory(id) }
            else -> null
        }
        "/role" -> if (parts.getOrNull(1)?.lowercase() == "delete") LocalCommand.DeleteCurrentCharacter else null
        else -> null
    }
}

private fun localCommandHelpText(): String = """
本地命令（不会发送给模型）

低风险命令：
/memory save：立刻整理当前角色尚未总结的对话并写入记忆
/memory list：显示当前角色的长期记忆编号

高风险命令：需在 30 秒内再次输入完全相同的命令确认
/memory delete last：删除当前角色最新一条记忆
/memory delete <记忆编号>：删除指定记忆
/memory clear：清除所有角色的长期记忆
/role delete：删除当前聊天角色及其长期记忆
""".trimIndent()

class AppViewModel : ViewModel() {
    var destination by mutableStateOf(Destination.Home)
    var selectedTab by mutableStateOf(HomeTab.Chats)
    var animateChatEntrance by mutableStateOf(false)
    var chatExitRequested by mutableStateOf(false)
    var themeMode by mutableStateOf(ThemeMode.System)
    var userName by mutableStateOf("我")
    var userSignature by mutableStateOf(DEFAULT_USER_SIGNATURE)
    var globalChatPrompt by mutableStateOf(COMMON_CHAT_PROMPT)
    var globalPromptSections by mutableStateOf(defaultGlobalPromptSections())
    var replySplitterSettings by mutableStateOf(ReplySplitterSettings())
    var userAvatarUri by mutableStateOf<String?>(null)
    var avatarCropRequest by mutableStateOf<AvatarCropRequest?>(null)
    val mcpServers = mutableStateListOf<McpServer>()
    var selectedCharacterId by mutableStateOf("lan")
    val characters = mutableStateListOf(lanCharacter())
    private val threads =
        mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage>>()
    val apiProviders = mutableStateListOf<ApiProvider>()
    var activeProviderId by mutableStateOf<String?>(null)
    var selectedModels by mutableStateOf(SelectedModels())
    var connectionTestState by mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle)
    var modelNameCheckState by mutableStateOf<ModelNameCheckState>(ModelNameCheckState.Idle)
    var modelConnectionTestState by mutableStateOf<ModelConnectionTestState>(ModelConnectionTestState.Idle)
    val shizukuState get() = ShizukuClient.state
    val shizukuStatusDetail get() = ShizukuClient.statusDetail()
    val shizukuLastError get() = ShizukuClient.lastError
    val shizukuServerVersion get() = ShizukuClient.serverVersion
    val shizukuPrivilegeUid get() = ShizukuClient.privilegeUid
    var shizukuTestResult by mutableStateOf<String?>(null)
    var isShizukuTesting by mutableStateOf(false)
    var isChatResponding by mutableStateOf(false)
    var chatStatus by mutableStateOf<String?>(null)
    var chatError by mutableStateOf<String?>(null)
    /** Live stream buffer while the reply model is generating. */
    var chatStreamingText by mutableStateOf<String?>(null)
    private var chatJob: Job? = null
    val recentLogs = mutableStateListOf<AppLogEntry>()
    val longTermMemories = mutableStateListOf<LongTermMemory>()
    var selectedMemoryScope by mutableStateOf<String?>(null)
    var globalMemorySaveStatus by mutableStateOf<String?>(null)
    private val memoryCheckpoints = mutableStateMapOf<String, Long>()
    private val memorySummaryJobs = mutableMapOf<String, Job>()
    private var pendingHighRiskCommand: String? = null
    private var pendingHighRiskCommandExpiresAt = 0L
    var isLoadingLogs by mutableStateOf(false)
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var providerStore: ProviderStore? = null
    private var appContext: Context? = null
    private var providerStoreInitialized = false

    fun selectedCharacter(): Character = characters.first { it.id == selectedCharacterId }
    fun messagesFor(characterId: String) =
        threads.getOrPut(characterId) { mutableStateListOf() }

    fun openMemoryScope(scopeId: String) {
        selectedMemoryScope = scopeId
        globalMemorySaveStatus = null
        destination = Destination.MemoryDetails
    }

    fun closeMemoryDetails() {
        selectedMemoryScope = null
        destination = Destination.Memories
    }

    fun openMemoriesPage() {
        destination = Destination.Memories
    }

    /**
     * Overlay on Home — not a Destination — so Scaffold/dock stay mounted
     * and enter/exit can match the swipe pager without a hard cut.
     */
    var userProfileVisible by mutableStateOf(false)
        private set

    var profileEnterFromSwipe by mutableStateOf(false)
        private set

    fun openUserProfile(fromSwipe: Boolean = false) {
        profileEnterFromSwipe = fromSwipe
        userProfileVisible = true
    }

    fun closeUserProfile() {
        userProfileVisible = false
    }

    fun setCharacterStreamingEnabled(characterId: String, enabled: Boolean) {
        val index = characters.indexOfFirst { it.id == characterId }
        if (index < 0) return
        val updated = characters[index].copy(streamingEnabled = enabled)
        characters[index] = updated
        persistCharacters()
        recordLog("${updated.name} 流式输出已${if (enabled) "开启" else "关闭"}")
    }

    fun setCharacterMemoryEnabled(characterId: String, enabled: Boolean) {
        val index = characters.indexOfFirst { it.id == characterId }
        if (index < 0) return
        val updated = characters[index].copy(memoryEnabled = enabled)
        characters[index] = updated
        persistCharacters()
        if (!enabled) {
            memorySummaryJobs.remove(characterId)?.cancel()
        } else {
            scheduleMemoryConsolidation(characterId)
        }
        recordLog("${updated.name} 长期记忆已${if (enabled) "开启" else "关闭"}")
    }

    fun openMemoryVector() {
        if (selectedMemoryScope != null) destination = Destination.MemoryVector
    }

    fun closeMemoryVector() {
        destination = Destination.MemoryDetails
    }

    fun latestChatAtForMemory(scopeId: String): Long? {
        val messages = if (scopeId == GLOBAL_MEMORY_SCOPE) threads.values.flatten() else threads[scopeId].orEmpty()
        return messages.filter { it.createdAt > 0L }.maxOfOrNull { it.createdAt }
    }

    fun latestMemoryAtForScope(scopeId: String): Long? =
        longTermMemories.filter { it.characterId == scopeId }.maxOfOrNull { it.createdAt }

    fun saveGlobalMemory(content: String) {
        val value = content.trim()
        if (value.isEmpty()) {
            globalMemorySaveStatus = "请输入要保存的全局记忆。"
            return
        }
        val provider = activeProvider()
        val embeddingModel = selectedModels.embedding
        if (provider == null || embeddingModel.isNullOrBlank()) {
            globalMemorySaveStatus = "请先配置提供商和嵌入模型。"
            return
        }
        globalMemorySaveStatus = "正在写入全局记忆…"
        providerScope.launch {
            val outcome = runCatching {
                val embedding = requestEmbedding(provider, embeddingModel, value)
                LongTermMemory(
                    characterId = GLOBAL_MEMORY_SCOPE,
                    content = value.take(1_800),
                    embedding = embedding,
                    sourceStartAt = System.currentTimeMillis(),
                    sourceEndAt = System.currentTimeMillis(),
                    tier = MemoryTier.Core,
                    importance = 0.95f,
                    metadataJson = JSONObject().put("source", "user_manual_global_memory").toString()
                ).also { memory ->
                    (providerStore ?: error("记忆数据库尚未就绪")).saveMemory(memory, memory.sourceEndAt)
                }
            }
            withContext(Dispatchers.Main) {
                outcome.onSuccess { memory ->
                    longTermMemories.add(0, memory)
                    globalMemorySaveStatus = "已保存，所有角色都可以在需要时调用。"
                    recordLog("已写入一条用户全局记忆")
                }.onFailure { error ->
                    globalMemorySaveStatus = "保存失败：${friendlyMemoryError(error)}"
                    recordLog("全局记忆写入失败：${friendlyMemoryError(error)}", AppLogLevel.Warning)
                }
            }
        }
    }

    /** Applies a user edit without changing the memory's identity or source range. */
    fun updateMemoryFromLibrary(memoryId: String, content: String, importance: Float, editNote: String) {
        val original = longTermMemories.firstOrNull { it.id == memoryId } ?: return
        val updatedContent = content.trim().take(1_800)
        if (updatedContent.isBlank()) return
        providerScope.launch {
            val outcome = runCatching {
                val embedding = if (updatedContent == original.content) {
                    original.embedding
                } else {
                    val provider = activeProvider()
                    val embeddingModel = selectedModels.embedding
                    if (provider != null && !embeddingModel.isNullOrBlank()) {
                        requestEmbedding(provider, embeddingModel, updatedContent)
                    } else {
                        original.embedding
                    }
                }
                val metadata = runCatching { JSONObject(original.metadataJson) }.getOrDefault(JSONObject()).apply {
                    if (editNote.trim().isBlank()) remove("last_edit_note") else put("last_edit_note", editNote.trim().take(300))
                    put("last_edited_at", System.currentTimeMillis())
                    put("edited_by", "user")
                }
                original.copy(
                    content = updatedContent,
                    embedding = embedding,
                    importance = importance.coerceIn(0.05f, 1f),
                    metadataJson = metadata.toString()
                ).also { updated ->
                    check((providerStore ?: error("记忆数据库尚未就绪")).updateMemory(updated)) { "记忆已不存在" }
                }
            }
            withContext(Dispatchers.Main) {
                outcome.onSuccess { updated ->
                    val index = longTermMemories.indexOfFirst { it.id == updated.id }
                    if (index >= 0) longTermMemories[index] = updated
                    recordLog("已修改一条${if (updated.characterId == GLOBAL_MEMORY_SCOPE) "全局" else "角色"}长期记忆")
                }.onFailure { error ->
                    recordLog("修改长期记忆失败：${friendlyMemoryError(error)}", AppLogLevel.Warning)
                }
            }
        }
    }

    fun deleteMemoryFromLibrary(memoryId: String) {
        providerScope.launch {
            val deleted = providerStore?.deleteMemory(memoryId) ?: false
            withContext(Dispatchers.Main) {
                if (deleted) {
                    longTermMemories.removeAll { it.id == memoryId }
                    recordLog("用户从记忆库删除了一条长期记忆", AppLogLevel.Warning)
                } else {
                    recordLog("删除长期记忆失败：记忆已不存在", AppLogLevel.Warning)
                }
            }
        }
    }

    fun openChat(characterId: String) {
        animateChatEntrance = destination == Destination.Home
        chatExitRequested = false
        selectedCharacterId = characterId
        chatError = null
        destination = Destination.Chat
    }

    fun consumeChatEntranceAnimation() {
        animateChatEntrance = false
    }

    fun requestChatExit() {
        cancelChat()
        if (destination == Destination.Chat) chatExitRequested = true
    }

    fun completeChatExit() {
        if (destination == Destination.Chat) {
            chatExitRequested = false
            destination = Destination.Home
            selectedTab = HomeTab.Chats
        }
    }

    fun openLogs() {
        val windowEnd = System.currentTimeMillis()
        destination = Destination.Logs
        isLoadingLogs = true
        recordLog("已进入日志页面")
        val store = providerStore ?: run { isLoadingLogs = false; return }
        providerScope.launch {
            val logs = store.loadLogs(windowEnd - 5L * 60 * 1000, windowEnd)
            withContext(Dispatchers.Main) {
                recentLogs.clear()
                recentLogs.addAll(logs)
                isLoadingLogs = false
            }
        }
    }

    private fun recordLog(message: String, level: AppLogLevel = AppLogLevel.Info) {
        val store = providerStore ?: return
        providerScope.launch { runCatching { store.addLog(level, message) } }
    }

    fun createCharacter(name: String, relationship: String, trait: String) {
        createCharacter(name, relationship, "", trait, "", "")
    }

    fun createCharacter(
        name: String,
        relationship: String,
        identity: String,
        personality: String,
        behaviorStyle: String,
        replyStyle: String
    ) {
        val trimmedName = name.trim().ifEmpty { "新角色" }
        val id = UUID.randomUUID().toString()
        characters.add(
            0,
            Character(
                id, trimmedName, relationship.trim().ifEmpty { "我的 AI 伙伴" },
                personality.trim().ifEmpty { "温柔、真诚、愿意倾听" },
                listOf(Color(0xFF3A79F7), Color(0xFF8A60E8), Color(0xFFEA6B8B), Color(0xFF16A779)).random(),
                "新的对话，从一句问候开始。", "现在",
                identity = identity.trim(), behaviorStyle = behaviorStyle.trim(), replyStyle = replyStyle.trim()
            )
        )
        sortCharacters()
        persistCharacters()
        openChat(id)
    }

    fun updateCharacter(characterId: String, name: String, relationship: String, trait: String) {
        val original = characters.firstOrNull { it.id == characterId } ?: return
        updateCharacter(characterId, name, relationship, original.identity, trait, original.behaviorStyle, original.replyStyle)
    }

    fun updateCharacter(
        characterId: String,
        name: String,
        relationship: String,
        identity: String,
        personality: String,
        behaviorStyle: String,
        replyStyle: String
    ) {
        val index = characters.indexOfFirst { it.id == characterId }
        if (index < 0) return
        val original = characters[index]
        characters[index] = original.copy(
            name = name.trim().ifBlank { original.name },
            relationship = relationship.trim().ifBlank { original.relationship },
            trait = personality.trim().ifBlank { original.trait },
            identity = identity.trim(),
            behaviorStyle = behaviorStyle.trim(),
            replyStyle = replyStyle.trim()
        )
        persistCharacters()
    }

    fun togglePinned(characterId: String) {
        val index = characters.indexOfFirst { it.id == characterId }
        if (index >= 0) {
            characters[index] = characters[index].copy(pinned = !characters[index].pinned)
            sortCharacters()
            persistCharacters()
        }
    }

    fun deleteCharacter(characterId: String) {
        if (characters.size <= 1) return
        characters.removeAll { it.id == characterId }
        threads.remove(characterId)
        memorySummaryJobs.remove(characterId)?.cancel()
        longTermMemories.removeAll { it.characterId == characterId }
        memoryCheckpoints.remove(characterId)
        if (selectedCharacterId == characterId) selectedCharacterId = characters.firstOrNull()?.id.orEmpty()
        persistCharacters()
        providerStore?.let { store ->
            providerScope.launch {
                store.deleteMessagesForCharacter(characterId)
                store.deleteMemoriesForCharacter(characterId)
            }
        }
    }

    private fun sortCharacters() {
        val sorted = characters.sortedWith(compareByDescending<Character> { it.pinned })
        characters.clear(); characters.addAll(sorted)
    }

    /** Cancels the in-flight planner/reply pipeline for the current chat. */
    fun cancelChat() {
        val job = chatJob ?: return
        if (!job.isActive) return
        job.cancel()
        recordLog("已取消当前对话请求")
    }

    fun sendMessage(content: String) {
        val value = content.trim()
        if (value.isEmpty()) return
        if (value.startsWith('/')) {
            handleLocalCommand(value)
            return
        }
        val characterId = selectedCharacterId
        val thread = messagesFor(characterId)
        val outgoing = ChatMessage(fromUser = true, content = value)
        thread.add(outgoing)
        updateConversationPreview(characterId, value)
        persistAppendedMessage(characterId, outgoing)
        scheduleMemoryConsolidation(characterId)
        recordLog("用户发送了一条消息")
        if (isExplicitMemorySaveRequest(value)) {
            recordLog("用户要求立刻保存长期记忆")
            appendLocalCommandOutput(characterId, "已收到记忆保存请求，正在立刻总结并写入长期记忆…")
            providerScope.launch {
                val outcome = consolidateMemoryIfIdle(characterId, force = true)
                withContext(Dispatchers.Main) {
                    appendLocalCommandOutput(characterId, memoryCommandResultText(outcome))
                }
            }
        }

        val provider = activeProvider()
        val chatModel = selectedModels.chat
        if (provider == null || chatModel.isNullOrBlank()) {
            recordLog("无法开始对话：未配置聊天模型或提供商", AppLogLevel.Warning)
            val reason = if (chatModel.isNullOrBlank()) {
                "我收到了。请先在“我 → 模型配置”中选择一个聊天模型，再和我进行真实对话。"
            } else {
                "我收到了，但没有可用的提供商。请在“我 → 模型配置”中选择对应的提供商。"
            }
            appendAssistantReply(characterId, reason)
            return
        }

        val character = characters.firstOrNull { it.id == characterId } ?: return
        val conversation = thread.toList()
        // Cancel any in-flight plan/reply so a new message always wins (A2).
        chatJob?.cancel()
        isChatResponding = true
        chatStatus = "思考中…"
        chatError = null
        chatStreamingText = null
        val plannerPrompt = globalChatPrompt
        val activeSplitterSettings = replySplitterSettings
        val enabledMcpServers = mcpServers.filter { it.enabled }.map { it.copy() }
        recordLog("开始规划角色回复")
        chatJob = providerScope.launch {
            val startedAt = System.currentTimeMillis()
            var stage = "规划"
            val result = try {
                val deviceHints = ChatAgent.deviceContextHint(value)
                val deviceContext = appContext?.let { buildLiveDeviceContext(it, deviceHints) }
                    ?: "设备实时上下文暂不可用。"
                val plan = if (shouldSkipPlanner(value)) {
                    recordLog("短消息跳过规划器，直接回复")
                    defaultDirectPlan()
                } else {
                    requestChatPlan(
                        provider, chatModel, character, conversation, userName, deviceContext, plannerPrompt,
                        enabledMcpServers.flatMap { it.tools }
                    )
                }
                val lengthLabel = when (plan.targetLength) {
                    "short" -> "简短"; "long" -> "较长"; else -> "适中"
                }
                recordLog("规划完成：${if (plan.shouldReply) "将回复" else "暂不回复"}，建议长度$lengthLabel")
                if (!plan.shouldReply) {
                    recordLog("计划决定暂不回复")
                    Result.success<String?>(null)
                } else {
                    val webSearchContext = plan.webSearchQuery?.let { query ->
                        stage = "网络查询"
                        withContext(Dispatchers.Main) {
                            chatStatus = "搜索中：${query.take(24)}"
                        }
                        recordLog("规划器请求网页查询")
                        runCatching { WebSearchClient.search(query) }.fold(
                            onSuccess = { response ->
                                recordLog("网页查询完成：${response.engine}，${response.results.size} 条结果")
                                WebSearchClient.formatContext(query, response)
                            },
                            onFailure = { error ->
                                val reason = friendlyWebSearchError(error)
                                recordLog("网页查询失败：$reason", AppLogLevel.Warning)
                                "本次网页查询未成功：$reason。请不要据此猜测或编造实时信息。"
                            }
                        )
                    }
                    val mcpToolContext = plan.mcpToolCall?.let { call ->
                        stage = "MCP 工具调用"
                        withContext(Dispatchers.Main) {
                            chatStatus = "调用工具：${call.toolName}"
                        }
                        val server = enabledMcpServers.firstOrNull { it.id == call.serverId }
                        if (server == null || server.tools.none { it.name == call.toolName }) {
                            recordLog("MCP 工具调用被拒绝：服务器或工具未启用", AppLogLevel.Warning)
                            "本次外部工具未执行：该工具未启用或不存在。"
                        } else {
                            recordLog("开始调用 MCP 工具：${server.name} / ${call.toolName}")
                            runCatching { McpHttpClient(server).callTool(call.toolName, call.argumentsJson) }.fold(
                                onSuccess = { result ->
                                    recordLog("MCP 工具调用完成：${server.name} / ${call.toolName}")
                                    "外部工具「${call.toolName}」返回：\n$result"
                                },
                                onFailure = { error ->
                                    val reason = friendlyMcpError(error)
                                    recordLog("MCP 工具调用失败：$reason", AppLogLevel.Warning)
                                    "外部工具「${call.toolName}」调用失败：$reason"
                                }
                            )
                        }
                    }
                    val deviceToolContext = if (plan.deviceToolCalls.isNotEmpty()) {
                        stage = "设备工具"
                        withContext(Dispatchers.Main) {
                            chatStatus = "调用设备：${plan.deviceToolCalls.joinToString("、") { it.name.removePrefix("device_") }}"
                        }
                        val ctx = appContext
                        if (ctx == null) {
                            "设备工具未能执行：应用上下文不可用。"
                        } else {
                            val lines = buildList {
                                for (call in plan.deviceToolCalls) {
                                    // Help weather: pass the raw user turn so the tool can
                                    // extract a city name even if the planner omitted it.
                                    val args = if (call.name == "device_get_weather") {
                                        runCatching {
                                            val obj = org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
                                            if (!obj.has("user_message")) obj.put("user_message", value)
                                            obj.toString()
                                        }.getOrDefault(call.argumentsJson)
                                    } else {
                                        call.argumentsJson
                                    }
                                    recordLog("调用设备工具：${call.name}")
                                    val out = DeviceTools.execute(ctx, call.copy(argumentsJson = args))
                                    add("「${call.name}」→ $out")
                                }
                            }
                            recordLog("设备工具执行完成：${plan.deviceToolCalls.size} 个")
                            lines.joinToString("\n")
                        }
                    } else {
                        null
                    }
                    // Character-scoped memory is optional; global core profile still applies.
                    val memoryEnabled = character.memoryEnabled
                    val coreMemories = withContext(Dispatchers.Main) {
                        if (memoryEnabled) coreMemoriesFor(characterId) else emptyList()
                    }
                    val globalCoreMemories = coreMemories.filter { it.characterId == GLOBAL_MEMORY_SCOPE }
                    val roleCoreMemories = coreMemories.filter { it.characterId == characterId }
                    val globalCoreMemoryContext =
                        globalCoreMemories.takeIf { it.isNotEmpty() }?.let(::formatMemoryContext)
                    if (globalCoreMemories.isNotEmpty()) {
                        recordLog("已将 ${globalCoreMemories.size} 条用户全局核心档案注入回复上下文")
                    }
                    val memoryRequested = memoryEnabled && (plan.shouldReadMemory || likelyNeedsMemory(value))
                    val memoryContext = if (memoryRequested) {
                        stage = "记忆读取"
                        withContext(Dispatchers.Main) { chatStatus = "回忆中…" }
                        recordLog("规划器请求读取长期记忆")
                        runCatching {
                            retrieveRelevantMemories(provider, selectedModels.embedding, characterId, value)
                        }.fold(
                            onSuccess = { memories ->
                                val combined = (roleCoreMemories + memories.filter { it.characterId != GLOBAL_MEMORY_SCOPE })
                                    .distinctBy { it.id }
                                if (combined.isEmpty()) {
                                    recordLog("记忆读取完成：没有匹配内容")
                                    "没有读取到与当前话题相关的长期记忆。"
                                } else {
                                    recordLog("记忆读取完成：角色核心 ${roleCoreMemories.size} 条，相关 ${memories.size} 条")
                                    formatMemoryContext(combined)
                                }
                            },
                            onFailure = { error ->
                                val reason = friendlyMemoryError(error)
                                recordLog("记忆读取失败：$reason", AppLogLevel.Warning)
                                if (roleCoreMemories.isEmpty()) {
                                    "本次长期记忆读取未成功：$reason。不要据此编造过去的互动。"
                                } else {
                                    formatMemoryContext(roleCoreMemories)
                                }
                            }
                        )
                    } else {
                        roleCoreMemories.takeIf { it.isNotEmpty() }?.let(::formatMemoryContext)
                    }
                    stage = "回复生成"
                    val useStreaming = character.streamingEnabled
                    withContext(Dispatchers.Main) {
                        chatStatus = "正在说话…"
                        chatStreamingText = if (useStreaming) "" else null
                    }
                    recordLog(if (useStreaming) "开始生成角色回复（流式）" else "开始生成角色回复（非流式）")
                    Result.success(
                        if (useStreaming) {
                            requestChatReplyStream(
                                provider, chatModel, character, conversation, userName, deviceContext, plan,
                                activeSplitterSettings, webSearchContext, mcpToolContext, deviceToolContext,
                                globalCoreMemoryContext, memoryContext
                            ) { chunk ->
                                // Frequent main-thread posts keep the bubble in sync with SSE.
                                providerScope.launch(Dispatchers.Main) {
                                    chatStreamingText = (chatStreamingText.orEmpty() + chunk)
                                }
                            }
                        } else {
                            requestChatReply(
                                provider, chatModel, character, conversation, userName, deviceContext, plan,
                                activeSplitterSettings, webSearchContext, mcpToolContext,
                                globalCoreMemoryContext, memoryContext, deviceToolContext
                            )
                        }
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    isChatResponding = false
                    chatStatus = null
                    chatStreamingText = null
                }
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }
            // A8: one automatic retry on network-class failures, then an in-character fallback.
            val networkFailure = result.exceptionOrNull()?.let { err ->
                err is java.net.SocketTimeoutException ||
                    err is java.net.ConnectException ||
                    err is java.net.UnknownHostException
            } == true
            val reply = if (networkFailure && stage == "回复生成") {
                recordLog("网络类失败，自动重试一次回复", AppLogLevel.Warning)
                delay(600)
                runCatching {
                    val deviceContext = appContext?.let {
                        buildLiveDeviceContext(it, ChatAgent.deviceContextHint(value))
                    } ?: "设备实时上下文暂不可用。"
                    val plan = defaultDirectPlan()
                    requestChatReply(
                        provider, chatModel, character, conversation, userName, deviceContext, plan,
                        activeSplitterSettings, null, null, null, null
                    )
                }.getOrElse {
                    recordLog("重试仍失败，使用离线回退文案", AppLogLevel.Warning)
                    ChatAgent.offlineFallbackReply(character.name)
                }
            } else {
                result.getOrNull()
            }
            when {
                result.isFailure && reply == null -> {
                    val error = result.exceptionOrNull() ?: IllegalStateException("未知错误")
                    val reason = friendlyChatError(provider.endpoint, error)
                    recordLog("$stage 失败：$reason", AppLogLevel.Error)
                    withContext(Dispatchers.Main) {
                        isChatResponding = false
                        chatStatus = null
                        chatStreamingText = null
                        chatError = "回复失败：$reason"
                    }
                }
                reply == null -> withContext(Dispatchers.Main) {
                    isChatResponding = false
                    chatStatus = null
                    chatStreamingText = null
                }
                else -> {
                    var finalReply: String = reply
                    // B6: one soft rewrite when the model breaks character.
                    if (ChatAgent.looksOutOfCharacter(finalReply)) {
                        recordLog("检测到可能出戏，尝试按角色口吻重写一次", AppLogLevel.Warning)
                        runCatching {
                            val rewriteMessages = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "system")
                                    put("content", ChatAgent.buildDehumanizeRewritePrompt(finalReply, character))
                                })
                            }
                            val rewritten = OpenAiClient.requestCompletion(
                                provider, chatModel, rewriteMessages, replySampling()
                            ).trim()
                            if (rewritten.isNotBlank()) finalReply = rewritten
                        }.onFailure {
                            recordLog("出戏重写失败，使用原文：${it.message?.take(60)}", AppLogLevel.Warning)
                        }
                    }
                    val segments = splitAssistantReply(finalReply, activeSplitterSettings)
                    if (segments.size > 1) recordLog("角色回复已分为 ${segments.size} 条消息发送")
                    withContext(Dispatchers.Main) { chatStreamingText = null }
                    segments.forEachIndexed { index, segment ->
                        withContext(Dispatchers.Main) {
                            chatStatus = if (segments.size > 1) "正在发送第 ${index + 1} 条消息…" else "正在发送回复…"
                            appendAssistantReply(characterId, segment)
                        }
                        if (index < segments.lastIndex) delay(replySegmentDelayMillis(segment))
                    }
                    withContext(Dispatchers.Main) {
                        isChatResponding = false
                        chatStatus = null
                        chatStreamingText = null
                    }
                    recordLog("已发送角色回复：${segments.size} 条，耗时 ${System.currentTimeMillis() - startedAt} 毫秒")
                }
            }
        }
    }

    /** Runs app-owned slash commands without putting their content through the LLM. */
    private fun handleLocalCommand(raw: String) {
        appendLocalCommandInput(selectedCharacterId, raw)
        val command = parseLocalCommand(raw)
        if (command == null) {
            pendingHighRiskCommand = null
            appendLocalCommandOutput(selectedCharacterId, "未识别的本地命令。输入 /help 查看可用命令。")
            return
        }
        if (command == LocalCommand.Help) {
            pendingHighRiskCommand = null
            appendLocalCommandOutput(selectedCharacterId, localCommandHelpText())
            return
        }
        if (command == LocalCommand.SaveCurrentMemory) {
            pendingHighRiskCommand = null
            val characterId = selectedCharacterId
            appendLocalCommandOutput(characterId, "正在强制整理当前角色的长期记忆…")
            providerScope.launch {
                val outcome = consolidateMemoryIfIdle(characterId, force = true)
                withContext(Dispatchers.Main) { appendLocalCommandOutput(characterId, memoryCommandResultText(outcome)) }
            }
            return
        }
        if (command == LocalCommand.ListCurrentMemories) {
            pendingHighRiskCommand = null
            val matched = longTermMemories.filter { it.characterId == selectedCharacterId }
            val text = if (matched.isEmpty()) "当前角色还没有长期记忆。" else buildString {
                appendLine("当前角色的长期记忆（${matched.size} 条）：")
                matched.forEach { memory -> appendLine("${memory.id.take(8)}：${memory.content.take(90)}") }
                append("可用 /memory delete <记忆编号> 删除指定记忆。")
            }
            appendLocalCommandOutput(selectedCharacterId, text)
            return
        }

        val now = System.currentTimeMillis()
        val confirmed = pendingHighRiskCommand == raw && now <= pendingHighRiskCommandExpiresAt
        if (!confirmed) {
            pendingHighRiskCommand = raw
            pendingHighRiskCommandExpiresAt = now + HIGH_RISK_COMMAND_CONFIRMATION_MILLIS
            appendLocalCommandOutput(
                selectedCharacterId,
                "这是高风险命令，可能删除不可恢复的数据。请在 30 秒内再次输入完全相同的命令确认：\n$raw"
            )
            return
        }
        pendingHighRiskCommand = null
        pendingHighRiskCommandExpiresAt = 0L
        executeConfirmedHighRiskCommand(command)
    }

    private fun executeConfirmedHighRiskCommand(command: LocalCommand) {
        when (command) {
            is LocalCommand.DeleteMemory -> {
                val memory = if (command.memoryId.equals("last", ignoreCase = true)) {
                    longTermMemories.firstOrNull { it.characterId == selectedCharacterId }
                } else {
                    longTermMemories.firstOrNull { it.id.startsWith(command.memoryId, ignoreCase = true) }
                }
                if (memory == null) {
                    appendLocalCommandOutput(selectedCharacterId, "没有找到要删除的记忆。可先输入 /memory list 查看编号。")
                } else {
                    providerScope.launch {
                        val deleted = providerStore?.deleteMemory(memory.id) ?: false
                        withContext(Dispatchers.Main) {
                            if (deleted) {
                                longTermMemories.removeAll { it.id == memory.id }
                                appendLocalCommandOutput(selectedCharacterId, "已删除长期记忆 ${memory.id.take(8)}。")
                                recordLog("本地命令删除了一条长期记忆")
                            } else {
                                appendLocalCommandOutput(selectedCharacterId, "删除失败：记忆已不存在。")
                            }
                        }
                    }
                }
            }
            LocalCommand.ClearAllMemories -> {
                providerScope.launch {
                    providerStore?.clearAllMemories()
                    withContext(Dispatchers.Main) {
                        longTermMemories.clear(); memoryCheckpoints.clear()
                        appendLocalCommandOutput(selectedCharacterId, "已清除所有角色的长期记忆。聊天记录不会受影响。")
                        recordLog("本地命令清除了所有长期记忆", AppLogLevel.Warning)
                    }
                }
            }
            LocalCommand.DeleteCurrentCharacter -> {
                val character = characters.firstOrNull { it.id == selectedCharacterId }
                if (character == null) {
                    appendLocalCommandOutput(selectedCharacterId, "当前没有可删除的角色。")
                } else if (characters.size <= 1) {
                    appendLocalCommandOutput(selectedCharacterId, "至少需要保留一个角色，无法删除最后一个角色。")
                } else {
                    val name = character.name
                    deleteCharacter(character.id)
                    appendLocalCommandOutput(selectedCharacterId, "已删除角色「$name」及其长期记忆。")
                    recordLog("本地命令删除了角色：$name", AppLogLevel.Warning)
                }
            }
            else -> Unit
        }
    }

    private fun appendLocalCommandOutput(characterId: String, text: String) {
        val message = ChatMessage(fromUser = false, content = text, createdAt = 0L)
        messagesFor(characterId).add(message)
        persistAppendedMessage(characterId, message)
    }

    /** Keeps commands visible in chat, but excludes them from automatic memory summaries. */
    private fun appendLocalCommandInput(characterId: String, raw: String) {
        val message = ChatMessage(fromUser = true, content = raw, createdAt = 0L)
        messagesFor(characterId).add(message)
        updateConversationPreview(characterId, raw)
        persistAppendedMessage(characterId, message)
    }

    /** A selected chat model is sufficient; vision, embedding, and voice are independent capabilities. */
    fun isRemoteChatReady(): Boolean = activeProvider() != null && !selectedModels.chat.isNullOrBlank()

    private fun appendAssistantReply(characterId: String, reply: String) {
        val content = reply.trim()
        // Never post empty or literal “null” bubbles left over from SSE parsing.
        if (content.isEmpty() || content.equals("null", ignoreCase = true) ||
            content.equals("undefined", ignoreCase = true)
        ) {
            return
        }
        val message = ChatMessage(fromUser = false, content = content)
        messagesFor(characterId).add(message)
        updateConversationPreview(characterId, content)
        persistAppendedMessage(characterId, message)
        scheduleMemoryConsolidation(characterId)
    }

    private fun updateConversationPreview(characterId: String, preview: String) {
        val index = characters.indexOfFirst { it.id == characterId }
        if (index >= 0) {
            characters[index] = characters[index].copy(preview = preview, time = "现在")
            sortCharacters()
        }
        persistCharacters()
    }

    fun initializeProviderStore(context: Context) {
        if (providerStoreInitialized) return
        providerStoreInitialized = true
        appContext = context.applicationContext
        val store = ProviderStore(context.applicationContext)
        providerStore = store
        ShizukuClient.attach(context.applicationContext)
        recordLog("Shizuku 状态：${ShizukuClient.statusLabel()} · ${ShizukuClient.statusDetail()}")
        providerScope.launch {
            val loaded = runCatching { store.load() }
            loaded.onFailure { error ->
                recordLog("数据库初始化失败：${error.message?.take(80) ?: "未知错误"}", AppLogLevel.Error)
            }
            loaded.getOrNull()?.let { stored ->
                withContext(Dispatchers.Main) {
                    apiProviders.clear()
                    apiProviders.addAll(stored.providers)
                    activeProviderId = stored.activeProviderId?.takeIf { selected -> stored.providers.any { it.id == selected } }
                        ?: stored.providers.firstOrNull()?.id
                    selectedModels = stored.selectedModels
                    themeMode = stored.themeMode
                    userName = stored.userName
                    userSignature = stored.userSignature
                    val shouldUpgradeBundledPlannerPrompt = stored.globalChatPrompt == LEGACY_COMMON_CHAT_PROMPT
                    globalChatPrompt =
                        if (shouldUpgradeBundledPlannerPrompt) COMMON_CHAT_PROMPT else stored.globalChatPrompt
                    globalPromptSections = parseGlobalChatPrompt(globalChatPrompt)
                    replySplitterSettings = stored.replySplitterSettings
                    mcpServers.clear()
                    mcpServers.addAll(stored.mcpServers)
                    if (shouldUpgradeBundledPlannerPrompt) {
                        providerScope.launch { store.saveGlobalChatPrompt(COMMON_CHAT_PROMPT) }
                        recordLog("已升级内置全局计划规范")
                    }
                    userAvatarUri = stored.userAvatarUri
                    val restoredCharacters =
                        if (stored.hasSavedCharacterState) stored.characters.toMutableList() else characters.toMutableList()
                    var characterDataChanged = false
                    if (restoredCharacters.none { it.id == XUANTIAN_CHARACTER_ID }) {
                        restoredCharacters += xuantianCharacter()
                        characterDataChanged = true
                    } else {
                        val index = restoredCharacters.indexOfFirst { it.id == XUANTIAN_CHARACTER_ID }
                        val existing = restoredCharacters[index]
                        if (existing.identity.isBlank() && existing.trait.contains("身份与性格")) {
                            restoredCharacters[index] = xuantianCharacter().copy(
                                relationship = existing.relationship,
                                color = existing.color,
                                preview = existing.preview,
                                time = existing.time,
                                pinned = existing.pinned
                            )
                            characterDataChanged = true
                        }
                    }
                    // Upgrade legacy “澜” cards that only had a one-line trait (B3).
                    val lanIndex = restoredCharacters.indexOfFirst { it.id == "lan" }
                    if (lanIndex >= 0) {
                        val lan = restoredCharacters[lanIndex]
                        if (lan.identity.isBlank() || lan.replyStyle.isBlank() || lan.exampleDialogues.isBlank()) {
                            restoredCharacters[lanIndex] = lanCharacter().copy(
                                id = lan.id,
                                name = lan.name,
                                color = lan.color,
                                preview = lan.preview,
                                time = lan.time,
                                pinned = lan.pinned,
                                avatarUri = lan.avatarUri,
                                relationship = lan.relationship.ifBlank { "安静的陪伴者" }
                            )
                            characterDataChanged = true
                            recordLog("已补全内置角色「澜」的人格卡与范例口吻")
                        }
                    }
                    if (characterDataChanged) store.saveCharacters(restoredCharacters)
                    characters.clear()
                    characters.addAll(restoredCharacters)
                    selectedCharacterId = restoredCharacters.firstOrNull()?.id.orEmpty()
                    threads.clear()
                    stored.messages.groupBy { it.characterId }.forEach { (characterId, messages) ->
                        threads[characterId] = mutableStateListOf<ChatMessage>().apply {
                            addAll(messages.map { it.message })
                        }
                    }
                    val invalidLegacyMemories = stored.memories.filter {
                        it.characterId != GLOBAL_MEMORY_SCOPE && isInvalidMemorySummary(it.content)
                    }
                    invalidLegacyMemories.forEach { store.deleteMemory(it.id) }
                    longTermMemories.clear()
                    longTermMemories.addAll(stored.memories - invalidLegacyMemories.toSet())
                    memoryCheckpoints.clear()
                    memoryCheckpoints.putAll(stored.memoryCheckpoints)
                    invalidLegacyMemories.groupBy { it.characterId }.forEach { (characterId, invalid) ->
                        val restartAt = (invalid.minOf { it.sourceStartAt } - 1L).coerceAtLeast(0L)
                        memoryCheckpoints[characterId] = restartAt
                        store.saveMemoryCheckpoint(characterId, restartAt)
                    }
                    if (invalidLegacyMemories.isNotEmpty()) {
                        recordLog("已清理 ${invalidLegacyMemories.size} 条无效旧记忆，将依据原始对话重新整理")
                    }
                    restoredCharacters.forEach { scheduleMemoryConsolidation(it.id) }
                    recordLog("应用已启动，已加载 ${stored.characters.size} 个角色和 ${stored.messages.size} 条消息")
                }
            }
        }
    }

    fun addProvider(name: String, endpoint: String, apiKey: String) {
        val provider = ApiProvider(
            name = name.trim().ifEmpty { "我的模型服务" },
            endpoint = endpoint.trim().trimEnd('/').ifEmpty { "https://api.example.com/v1" },
            apiKey = apiKey.trim()
        )
        apiProviders.add(provider)
        activeProviderId = provider.id
        persistProviderConfiguration()
        recordLog("已添加模型提供商：${provider.name}")
        importModels(provider.id)
    }

    fun testProviderConnection(endpoint: String, apiKey: String) {
        connectionTestState = ConnectionTestState.Testing
        recordLog("开始测试模型服务连接")
        providerScope.launch {
            val result = runCatching { OpenAiClient.fetchModels(endpoint.trim().trimEnd('/'), apiKey.trim()) }
            withContext(Dispatchers.Main) {
                connectionTestState = result.fold(
                    onSuccess = { models ->
                        recordLog("模型服务连接成功，读取到 ${models.size} 个模型")
                        ConnectionTestState.Success(models)
                    },
                    onFailure = { error ->
                        val reason = friendlyError(endpoint, error)
                        recordLog("模型服务连接失败：$reason", AppLogLevel.Error)
                        ConnectionTestState.Failure(reason)
                    }
                )
            }
        }
    }

    fun activeProvider(): ApiProvider? = apiProviders.firstOrNull { it.id == activeProviderId }

    fun selectProvider(providerId: String) {
        activeProviderId = providerId
        persistProviderConfiguration()
        apiProviders.firstOrNull { it.id == providerId }?.let { recordLog("已切换模型提供商：${it.name}") }
    }

    fun selectModel(type: ModelType, model: String) {
        selectedModels = selectedModels.withModel(type, model)
        persistProviderConfiguration()
        recordLog("已选择${type.label}：$model")
    }

    fun setAllowModelWebSearch(enabled: Boolean) {
        selectedModels = selectedModels.copy(allowModelWebSearch = enabled)
        persistProviderConfiguration()
        recordLog(if (enabled) "已允许模型使用自带联网搜索" else "已关闭模型自带联网搜索")
    }

    fun validateModelName(providerId: String, rawModel: String) {
        val provider = apiProviders.firstOrNull { it.id == providerId } ?: return
        val model = rawModel.trim()
        if (model.isBlank()) {
            modelNameCheckState = ModelNameCheckState.Invalid("请先填写模型名称")
            return
        }
        modelNameCheckState = ModelNameCheckState.Checking
        modelConnectionTestState = ModelConnectionTestState.Idle
        recordLog("开始验证模型名称：${provider.name} / $model")
        providerScope.launch {
            val result = runCatching { OpenAiClient.fetchModels(provider.endpoint, provider.apiKey) }
            withContext(Dispatchers.Main) {
                modelNameCheckState = result.fold(
                    onSuccess = { models ->
                        if (models.any { it.equals(model, ignoreCase = true) }) {
                            recordLog("模型名称验证成功：$model")
                            ModelNameCheckState.Valid(model)
                        } else {
                            val message = "该提供商的模型列表中未找到“$model”"
                            recordLog("模型名称验证失败：$message", AppLogLevel.Warning)
                            ModelNameCheckState.Invalid(message)
                        }
                    },
                    onFailure = { error ->
                        val message = friendlyError(provider.endpoint, error)
                        recordLog("模型名称验证失败：$message", AppLogLevel.Error)
                        ModelNameCheckState.Invalid(message)
                    }
                )
            }
        }
    }

    fun testModelConnectivity(providerId: String, rawModel: String) {
        val provider = apiProviders.firstOrNull { it.id == providerId } ?: return
        val model = rawModel.trim()
        if (modelNameCheckState !is ModelNameCheckState.Valid ||
            (modelNameCheckState as ModelNameCheckState.Valid).model != model
        ) {
            return
        }
        modelConnectionTestState = ModelConnectionTestState.Testing
        recordLog("开始测试模型连通性：${provider.name} / $model")
        providerScope.launch {
            val result = runCatching { OpenAiClient.fetchModelDescriptor(provider.endpoint, provider.apiKey, model) }
            withContext(Dispatchers.Main) {
                modelConnectionTestState = result.fold(
                    onSuccess = {
                        recordLog("模型连通性测试成功：$model")
                        ModelConnectionTestState.Success(model)
                    },
                    onFailure = { error ->
                        val message = friendlyError(provider.endpoint, error)
                        recordLog("模型连通性测试失败：$message", AppLogLevel.Error)
                        ModelConnectionTestState.Failure(message)
                    }
                )
            }
        }
    }

    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        val store = providerStore ?: return
        providerScope.launch { store.saveThemeMode(mode) }
    }

    fun updateUserName(name: String) {
        val value = name.trim().ifBlank { "我" }
        userName = value
        val store = providerStore ?: return
        providerScope.launch { store.saveUserName(value) }
    }

    fun updateUserSignature(signature: String) {
        val value = signature.take(MAX_USER_SIGNATURE_LENGTH)
        userSignature = value
        val store = providerStore ?: return
        providerScope.launch { store.saveUserSignature(value) }
    }

    fun updateGlobalChatPrompt(prompt: String) {
        val value = prompt.take(6_000)
        globalChatPrompt = value
        globalPromptSections = parseGlobalChatPrompt(value)
        val store = providerStore ?: return
        providerScope.launch { store.saveGlobalChatPrompt(value) }
    }

    fun updateGlobalPromptSections(dialogueTask: String, responseDecision: String, outputRules: String) {
        val sections = GlobalPromptSections(dialogueTask, responseDecision, outputRules)
        globalPromptSections = sections
        updateGlobalChatPrompt(composeGlobalChatPrompt(sections))
    }

    fun updateReplySplitterSettings(settings: ReplySplitterSettings) {
        val value = settings.normalized()
        replySplitterSettings = value
        val store = providerStore ?: return
        providerScope.launch { store.saveReplySplitterSettings(value) }
        recordLog("已更新回复分段器：${value.mode.label}，最多 ${value.maxSegments} 条，每条 ${value.minSegmentLength}-${value.maxSegmentLength} 字")
    }

    /** Copies the chosen picture into app-private storage, so it stays available after restart. */
    fun beginUserAvatarCrop(sourceUri: Uri) {
        val context = appContext ?: return
        providerScope.launch {
            val localUri = runCatching {
                val cacheFile = File(context.cacheDir, "user_avatar_pick_${System.currentTimeMillis()}.img")
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选图片")
                if (cacheFile.length() <= 0L) error("所选图片内容为空")
                Uri.fromFile(cacheFile).toString()
            }.getOrElse {
                recordLog("读取相册图片失败：${it.message?.take(80) ?: "未知错误"}", AppLogLevel.Error)
                return@launch
            }
            withContext(Dispatchers.Main) {
                avatarCropRequest = AvatarCropRequest(characterId = null, sourceUri = localUri)
                destination = Destination.AvatarCrop
            }
        }
    }

    fun beginCharacterAvatarCrop(characterId: String, sourceUri: Uri) {
        if (characters.none { it.id == characterId }) return
        val context = appContext
        if (context == null) {
            avatarCropRequest = AvatarCropRequest(characterId, sourceUri.toString())
            destination = Destination.AvatarCrop
            return
        }
        // Copy into cache first: ACTION_PICK grants are temporary and may not survive
        // into the crop screen on some OEM ROMs (especially custom-album URIs).
        providerScope.launch {
            val localUri = runCatching {
                val cacheFile = File(context.cacheDir, "avatar_pick_${characterId}_${System.currentTimeMillis()}.img")
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选图片")
                if (cacheFile.length() <= 0L) error("所选图片内容为空")
                Uri.fromFile(cacheFile).toString()
            }.getOrElse {
                recordLog("读取相册图片失败：${it.message?.take(80) ?: "未知错误"}", AppLogLevel.Error)
                return@launch
            }
            withContext(Dispatchers.Main) {
                avatarCropRequest = AvatarCropRequest(characterId, localUri)
                destination = Destination.AvatarCrop
            }
        }
    }

    fun cancelAvatarCrop() {
        val request = avatarCropRequest
        avatarCropRequest = null
        if (request?.characterId != null) {
            destination = Destination.CharacterSettings
        } else {
            destination = Destination.Home
            selectedTab = HomeTab.Me
        }
    }

    fun saveCroppedUserAvatar(context: Context, bitmap: Bitmap) {
        val store = providerStore ?: return
        providerScope.launch {
            val savedUri = runCatching {
                val destination = File(context.filesDir, "user_avatar.png")
                destination.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("无法保存裁剪后的头像")
                }
                Uri.fromFile(destination).toString()
            }.getOrElse {
                recordLog("用户头像保存失败：${it.message?.take(80) ?: "未知错误"}", AppLogLevel.Error)
                return@launch
            }
            store.saveAvatarUri(savedUri)
            withContext(Dispatchers.Main) {
                userAvatarUri = savedUri
                avatarCropRequest = null
                destination = Destination.Home
                selectedTab = HomeTab.Me
                recordLog("已裁剪并更新用户头像")
            }
        }
    }

    fun saveCroppedCharacterAvatar(context: Context, characterId: String, bitmap: Bitmap) {
        val character = characters.firstOrNull { it.id == characterId } ?: return
        providerScope.launch {
            val savedUri = runCatching {
                val destination = File(context.filesDir, "character_avatar_${characterId}.png")
                destination.outputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) error("无法保存裁剪后的头像")
                }
                Uri.fromFile(destination).toString()
            }.getOrElse {
                recordLog("角色头像保存失败：${it.message?.take(80) ?: "未知错误"}", AppLogLevel.Error)
                return@launch
            }
            withContext(Dispatchers.Main) {
                val index = characters.indexOfFirst { it.id == characterId }
                if (index >= 0) {
                    characters[index] = characters[index].copy(avatarUri = savedUri)
                    persistCharacters()
                    avatarCropRequest = null
                    destination = Destination.CharacterSettings
                    recordLog("已裁剪并更新角色头像：${character.name}")
                }
            }
        }
    }

    fun importModels(providerId: String) {
        val provider = apiProviders.firstOrNull { it.id == providerId } ?: return
        updateProvider(provider.copy(isImporting = true, importError = null))
        recordLog("开始导入模型列表：${provider.name}")
        providerScope.launch {
            val result = runCatching { OpenAiClient.fetchModels(provider.endpoint, provider.apiKey) }
            withContext(Dispatchers.Main) {
                result.onSuccess { models ->
                    val current = apiProviders.firstOrNull { it.id == providerId } ?: return@onSuccess
                    updateProvider(current.copy(models = models, isImporting = false, importError = null))
                    recordLog("模型列表导入完成：${models.size} 个模型")
                }.onFailure { error ->
                    val current = apiProviders.firstOrNull { it.id == providerId } ?: return@onFailure
                    val reason = friendlyError(current.endpoint, error)
                    updateProvider(current.copy(isImporting = false, importError = reason))
                    recordLog("模型列表导入失败：$reason", AppLogLevel.Error)
                }
            }
        }
    }

    private fun updateProvider(provider: ApiProvider) {
        val index = apiProviders.indexOfFirst { it.id == provider.id }
        if (index >= 0) {
            apiProviders[index] = provider
            persistProviderConfiguration()
        }
    }

    private fun persistProviderConfiguration() {
        val store = providerStore ?: return
        val providers = apiProviders.map { it.copy(isImporting = false, importError = null) }
        val activeId = activeProviderId
        val models = selectedModels
        providerScope.launch { store.save(providers, activeId, models) }
    }

    private fun persistMcpServers() {
        val store = providerStore ?: return
        val servers = mcpServers.map { it.copy(isImporting = false, importError = null) }
        providerScope.launch { store.saveMcpServers(servers) }
    }

    fun addMcpServer(name: String, endpoint: String, apiKey: String) {
        val server = McpServer(
            name = name.trim().ifBlank { "我的 MCP 服务" },
            endpoint = endpoint.trim().ifBlank { "https://example.com/mcp" },
            apiKey = apiKey.trim()
        )
        mcpServers.add(server)
        persistMcpServers()
        recordLog("已添加外部 MCP 服务器：${server.name}")
        importMcpTools(server.id)
    }

    fun deleteMcpServer(serverId: String) {
        val server = mcpServers.firstOrNull { it.id == serverId } ?: return
        mcpServers.remove(server)
        persistMcpServers()
        recordLog("已移除外部 MCP 服务器：${server.name}")
    }

    fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        val index = mcpServers.indexOfFirst { it.id == serverId }
        if (index < 0) return
        mcpServers[index] = mcpServers[index].copy(enabled = enabled)
        persistMcpServers()
        recordLog("外部 MCP 服务器已${if (enabled) "启用" else "停用"}：${mcpServers[index].name}")
    }

    fun importMcpTools(serverId: String) {
        val server = mcpServers.firstOrNull { it.id == serverId } ?: return
        val index = mcpServers.indexOfFirst { it.id == serverId }
        mcpServers[index] = server.copy(isImporting = true, importError = null)
        recordLog("开始连接 MCP 服务器：${server.name}")
        providerScope.launch {
            val result = runCatching { McpHttpClient(server).listTools() }
            withContext(Dispatchers.Main) {
                val currentIndex = mcpServers.indexOfFirst { it.id == serverId }
                if (currentIndex < 0) return@withContext
                result.onSuccess { tools ->
                    mcpServers[currentIndex] = mcpServers[currentIndex].copy(
                        tools = tools, isImporting = false, importError = null
                    )
                    persistMcpServers()
                    recordLog("MCP 工具导入完成：${tools.size} 个工具")
                }.onFailure { error ->
                    val reason = friendlyMcpError(error)
                    mcpServers[currentIndex] = mcpServers[currentIndex].copy(
                        isImporting = false, importError = reason
                    )
                    persistMcpServers()
                    recordLog("MCP 工具导入失败：$reason", AppLogLevel.Error)
                }
            }
        }
    }

    private fun persistAppendedMessage(characterId: String, message: ChatMessage) {
        val store = providerStore ?: return
        providerScope.launch { store.appendMessage(characterId, message) }
    }

    /** Schedules a role-scoped, durable memory summary after the conversation is quiet for three minutes. */
    private fun scheduleMemoryConsolidation(characterId: String) {
        memorySummaryJobs.remove(characterId)?.cancel()
        val character = characters.firstOrNull { it.id == characterId } ?: return
        if (!character.memoryEnabled) return
        val latestActivity = messagesFor(characterId).maxOfOrNull { it.createdAt } ?: return
        if (latestActivity <= 0L) return
        val waitMillis = (latestActivity + MEMORY_IDLE_MILLIS - System.currentTimeMillis()).coerceAtLeast(0L)
        memorySummaryJobs[characterId] = providerScope.launch {
            delay(waitMillis)
            consolidateMemoryIfIdle(characterId)
        }
    }

    /** Implements the memory-summary tool followed by the memory-write tool. */
    private suspend fun consolidateMemoryIfIdle(
        characterId: String,
        force: Boolean = false
    ): MemoryConsolidationOutcome {
        data class Input(
            val provider: ApiProvider,
            val chatModel: String,
            val embeddingModel: String,
            val character: Character,
            val messages: List<ChatMessage>,
            val checkpoint: Long
        )

        val input = withContext(Dispatchers.Main) {
            val recentMessages = messagesFor(characterId).filter { it.createdAt > 0L }
            val last = recentMessages.maxByOrNull { it.createdAt } ?: return@withContext null
            if (!force && System.currentTimeMillis() - last.createdAt < MEMORY_IDLE_MILLIS) {
                scheduleMemoryConsolidation(characterId)
                return@withContext null
            }
            if (!force && isChatResponding && selectedCharacterId == characterId) {
                scheduleMemoryConsolidation(characterId)
                return@withContext null
            }
            val provider = activeProvider()
            val chatModel = selectedModels.chat
            val embeddingModel = selectedModels.embedding
            val character = characters.firstOrNull { it.id == characterId }
            if (provider == null || chatModel.isNullOrBlank() || embeddingModel.isNullOrBlank() || character == null) {
                recordLog("记忆整理暂缓：需要同时配置聊天模型和嵌入模型", AppLogLevel.Warning)
                return@withContext null
            }
            val checkpoint = memoryCheckpoints[characterId] ?: 0L
            val unsummarized = recentMessages.filter { it.createdAt > checkpoint }.sortedBy { it.createdAt }
            if (unsummarized.isEmpty()) return@withContext null
            Input(provider, chatModel, embeddingModel, character, unsummarized, checkpoint)
        } ?: return MemoryConsolidationOutcome.NothingToDo

        recordLog(
            "${if (force) "已触发立即" else "对话静默 3 分钟后"}，将 ${input.messages.size} 条新消息按 JSON 提交给记忆总结工具"
        )
        val drafts = runCatching {
            requestMemorySummary(input.provider, input.chatModel, input.character, input.messages)
        }.getOrElse { error ->
            recordLog("记忆总结失败：${friendlyMemoryError(error)}", AppLogLevel.Warning)
            return MemoryConsolidationOutcome.Failed
        }
        val store = providerStore ?: return MemoryConsolidationOutcome.Failed
        val memories = runCatching {
            drafts.map { draft ->
                LongTermMemory(
                    characterId = characterId,
                    content = draft.content,
                    embedding = requestEmbedding(input.provider, input.embeddingModel, draft.content),
                    sourceStartAt = input.messages.first().createdAt,
                    sourceEndAt = input.messages.last().createdAt,
                    tier = draft.tier,
                    importance = draft.importance,
                    validUntil = draft.validUntil,
                    metadataJson = draft.metadataJson
                )
            }
        }.getOrElse { error ->
            recordLog("记忆写入失败：嵌入模型不可用，${friendlyMemoryError(error)}", AppLogLevel.Warning)
            return MemoryConsolidationOutcome.Failed
        }
        val write = runCatching { store.saveMemories(memories, input.messages.last().createdAt) }
        if (write.isFailure) {
            recordLog("记忆写入数据库失败：${write.exceptionOrNull()?.message?.take(100) ?: "未知错误"}", AppLogLevel.Error)
            return MemoryConsolidationOutcome.Failed
        }
        withContext(Dispatchers.Main) {
            longTermMemories.addAll(0, memories)
            memoryCheckpoints[characterId] = input.messages.last().createdAt
        }
        recordLog("记忆已写入：${input.character.name}，${memories.size} 条分层记忆")
        return MemoryConsolidationOutcome.Written
    }

    /** Implements the planner-owned memory_read tool with namespace isolation and hybrid ranking. */
    private suspend fun retrieveRelevantMemories(
        provider: ApiProvider,
        embeddingModel: String?,
        characterId: String,
        query: String
    ): List<LongTermMemory> {
        if (embeddingModel.isNullOrBlank()) error("尚未配置嵌入模型")
        val queryVector = requestEmbedding(provider, embeddingModel, query)
        val now = System.currentTimeMillis()
        val candidates = withContext(Dispatchers.Main) {
            longTermMemories.filter {
                (it.characterId == characterId || it.characterId == GLOBAL_MEMORY_SCOPE) &&
                    (it.validUntil == null || it.validUntil >= now) && it.supersedesId == null
            }.toList()
        }
        return candidates.mapNotNull { memory ->
            val semantic = cosineSimilarity(queryVector, memory.embedding)
            val lexical = lexicalMemorySimilarity(query, memory.content)
            val ageDays = ((now - memory.createdAt).coerceAtLeast(0L) / 86_400_000f)
            val recency = exp((-ageDays / 180f).toDouble()).toFloat()
            val score = semantic * 0.62f + lexical * 0.22f + recency * 0.08f +
                memory.importance.coerceIn(0f, 1f) * 0.08f
            if (semantic >= MEMORY_MATCH_THRESHOLD || lexical >= 0.18f || memory.tier == MemoryTier.Core) {
                memory to score
            } else {
                null
            }
        }.sortedByDescending { it.second }.take(MEMORY_RETRIEVAL_LIMIT).map { it.first }
    }

    private fun coreMemoriesFor(characterId: String): List<LongTermMemory> {
        val now = System.currentTimeMillis()
        return longTermMemories.filter {
            (it.characterId == characterId || it.characterId == GLOBAL_MEMORY_SCOPE) &&
                it.tier in setOf(MemoryTier.Core, MemoryTier.Procedural) &&
                (it.validUntil == null || it.validUntil >= now) && it.supersedesId == null
        }.sortedWith(compareByDescending<LongTermMemory> { it.importance }.thenByDescending { it.createdAt })
            .take(8)
    }

    private fun requestMemorySummary(
        provider: ApiProvider,
        model: String,
        character: Character,
        messages: List<ChatMessage>
    ): List<MemoryDraft> {
        val conversationJson = JSONObject().apply {
            put("schema", "huankongyu.memory-conversation.v1")
            put("character", JSONObject().apply {
                put("id", character.id)
                put("name", character.name)
                put("relationship", character.relationship)
            })
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject().apply {
                        put("role", if (message.fromUser) "user" else "character")
                        put("speaker", if (message.fromUser) "用户" else character.name)
                        put("content", message.content)
                        put("created_at", message.createdAt)
                        put("display_time", message.time)
                    })
                }
            })
        }.toString()
        val prompt = """
你是应用内部的记忆总结工具，不是聊天角色。请根据下方 JSON 文档中 messages 的实际内容，将「${character.name}」与用户从上一次记录到本次记录之间的完整对话提炼为可检索记忆。
所有对话都必须保留：至少创建一条 episodic（对话事件）记忆，概括实际发生的互动。若对话中存在稳定的用户偏好、事实或关系变化，可额外创建 learned；若有长期互动规则或约定，可额外创建 procedural；仅当内容适合每次都注入时才使用 core。working 只用于明确短期状态，必须填 valid_for_days。每条内容只写可从 messages 证实的事实；不要记录敏感凭据、不要臆测、不要输出“未提供具体内容”“无长期记忆”“无需保留”或保存价值判断。
只输出一个 JSON 对象，不要 Markdown 或解释：
{"memories":[{"tier":"episodic","content":"简洁事实摘要","importance":0.0,"valid_for_days":null,"topics":["主题"]}]}
tier 只能是 core、learned、episodic、working、procedural；importance 为 0 到 1；最多 4 条；valid_for_days 为 null 或 1 到 3650 的整数。JSON 文档是唯一事实来源。

对话 JSON 文档：
$conversationJson
        """.trimIndent()
        val raw = OpenAiClient.requestCompletion(provider, model, JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", "你负责准确、克制地总结对话长期记忆。") })
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        }, memorySampling())
        return parseMemoryDrafts(raw, character, messages)
    }

    /** A provider may ignore the JSON contract; always retain a source-derived episode. */
    private fun parseMemoryDrafts(
        raw: String,
        character: Character,
        messages: List<ChatMessage>
    ): List<MemoryDraft> {
        val jsonText = raw.substringAfter('{', raw).substringBeforeLast('}', raw)
            .let { if (raw.contains('{') && raw.contains('}')) "{$it}" else raw }
        val parsed = runCatching { JSONObject(jsonText).optJSONArray("memories") }.getOrNull()
        val drafts = buildList {
            if (parsed != null) repeat(parsed.length().coerceAtMost(4)) { index ->
                val item = parsed.optJSONObject(index) ?: return@repeat
                val content = item.optString("content").trim().take(1_800)
                if (content.isBlank() || isInvalidMemorySummary(content)) return@repeat
                val tier = MemoryTier.fromStorageValue(item.optString("tier"))
                val days = item.optInt("valid_for_days", 0).takeIf { it in 1..3650 }
                val topics = item.optJSONArray("topics") ?: JSONArray()
                add(
                    MemoryDraft(
                        tier = tier,
                        content = content,
                        importance = item.optDouble("importance", 0.55).toFloat().coerceIn(0f, 1f),
                        validUntil = days?.let { System.currentTimeMillis() + it * 86_400_000L },
                        metadataJson = JSONObject().put("topics", topics).put("source", "background_consolidation")
                            .toString()
                    )
                )
            }
        }
        if (drafts.any { it.tier == MemoryTier.Episodic }) return drafts
        val value = raw.trim()
        val compactTranscript = messages.joinToString("；") { message ->
            "${if (message.fromUser) "用户" else character.name}：${message.content.replace(Regex("\\s+"), " ").take(180)}"
        }.take(1_600)
        val fallback = if (value.isNotBlank() && !isInvalidMemorySummary(value) && !value.startsWith("{")) {
            value.take(1_800)
        } else {
            "${character.name}与用户本次对话摘要：$compactTranscript"
        }
        return listOf(
            MemoryDraft(
                MemoryTier.Episodic,
                fallback,
                importance = 0.6f,
                metadataJson = JSONObject().put("source", "fallback_consolidation").toString()
            )
        )
    }

    private fun isInvalidMemorySummary(value: String): Boolean =
        Regex("无长期记忆|无需(长期)?保留|没有需要.*保留|不需要.*保留|未提供具体内容|没有具体内容|未给出具体内容|未提供内容")
            .containsMatchIn(value)

    private fun lexicalMemorySimilarity(query: String, content: String): Float {
        fun terms(value: String): Set<String> {
            val normalized = value.lowercase(Locale.SIMPLIFIED_CHINESE).replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            val words = normalized.split(Regex("\\s+")).filter { it.length >= 2 }
            val chineseBigrams = normalized.filter { it.code > 127 }.windowed(2, 1).toSet()
            return (words + chineseBigrams).toSet()
        }
        val left = terms(query)
        val right = terms(content)
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat() / left.union(right).size.toFloat()
    }

    private fun requestEmbedding(provider: ApiProvider, model: String, text: String): List<Float> =
        OpenAiClient.requestEmbedding(provider, model, text)

    private fun cosineSimilarity(left: List<Float>, right: List<Float>): Float {
        if (left.isEmpty() || left.size != right.size) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b; leftNorm += a * a; rightNorm += b * b
        }
        return if (leftNorm == 0.0 || rightNorm == 0.0) 0f else (dot / kotlin.math.sqrt(leftNorm * rightNorm)).toFloat()
    }

    private fun formatMemoryContext(memories: List<LongTermMemory>): String = memories.mapIndexed { index, memory ->
        "${if (memory.characterId == GLOBAL_MEMORY_SCOPE) "全局" else "角色"}${memory.tier.label}${index + 1}：${memory.content}"
    }.joinToString("\n")

    private fun likelyNeedsMemory(message: String): Boolean =
        Regex("记得|之前|上次|以前|我们.*(说过|聊过|约定)|我的(喜好|偏好|生日|名字)").containsMatchIn(message)

    private fun isExplicitMemorySaveRequest(message: String): Boolean =
        Regex("(记录|保存|写入|保留|记住).{0,10}(记忆|下来)|把.{0,14}(记下来|保存)").containsMatchIn(message)

    private fun memoryCommandResultText(outcome: MemoryConsolidationOutcome): String = when (outcome) {
        MemoryConsolidationOutcome.Written -> "强制记忆整理完成：已总结并写入长期记忆。"
        MemoryConsolidationOutcome.NothingToDo -> "没有可整理的新对话，或当前仍有回复任务正在进行。"
        MemoryConsolidationOutcome.Failed -> "记忆整理失败。请检查聊天模型、嵌入模型和日志。"
    }

    private fun friendlyMemoryError(error: Throwable): String = when (error) {
        is SocketTimeoutException, is ConnectException -> "无法连接到记忆模型服务"
        is UnknownHostException -> "无法解析记忆模型服务地址"
        else -> error.message?.take(140)?.ifBlank { null } ?: "记忆服务暂时不可用"
    }

    private fun persistCharacters() {
        val store = providerStore ?: return
        val savedCharacters = characters.toList()
        providerScope.launch { store.saveCharacters(savedCharacters) }
    }

    private fun requestChatPlan(
        provider: ApiProvider,
        model: String,
        character: Character,
        conversation: List<ChatMessage>,
        userName: String,
        deviceContext: String,
        plannerPrompt: String,
        mcpTools: List<McpTool>
    ): ChatPlan {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    buildChatPlanSystemPrompt(
                        character.name, userName, character.behaviorStyle, deviceContext, plannerPrompt, mcpTools
                    )
                )
            })
            conversation.takeLast(12).forEach { message ->
                put(JSONObject().apply {
                    put("role", if (message.fromUser) "user" else "assistant")
                    put("content", message.content.take(400))
                })
            }
        }
        return parseChatPlanWithFallback(provider, model, messages)
    }

    private fun parseChatPlanWithFallback(
        provider: ApiProvider,
        model: String,
        messages: JSONArray
    ): ChatPlan {
        val web = selectedModels.allowModelWebSearch
        val raw = runCatching {
            OpenAiClient.requestCompletion(provider, model, messages, plannerSampling(web))
        }.getOrElse { throw it }
        val first = ChatAgent.parseChatPlan(raw)
        if (first != null) return first
        recordLog("规划器 JSON 解析失败，重试一次", AppLogLevel.Warning)
        val retryRaw = OpenAiClient.requestCompletion(provider, model, messages, plannerSampling(web))
        val second = ChatAgent.parseChatPlan(retryRaw)
        if (second != null) return second
        recordLog("规划器输出仍无法解析，降级为直接回复", AppLogLevel.Warning)
        return ChatAgent.defaultDirectPlan()
    }

    /** Keeps recent history under a character budget for the reply stage. */
    private fun trimConversationForReply(
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

    /** Short casual turns skip the planner and go straight to a default plan. */
    private fun shouldSkipPlanner(userMessage: String): Boolean {
        val value = userMessage.trim()
        if (value.isEmpty() || value.length > 48) return false
        if (value.contains('?') || value.contains('？')) return false
        val probe = listOf("查", "搜", "帮我", "天气", "新闻", "日历", "日程", "搜索")
        if (probe.any { value.contains(it) }) return false
        return true
    }

    private fun defaultDirectPlan() = ChatPlan(
        shouldReply = true,
        replyFocus = "自然接住用户刚才这句话",
        replyStrategy = "像熟人一样简短接话，不展开、不总结",
        targetLength = "short",
        shouldReadMemory = false,
        webSearchQuery = null,
        mcpToolCall = null
    )

    private fun requestChatReply(
        provider: ApiProvider,
        model: String,
        character: Character,
        conversation: List<ChatMessage>,
        userName: String,
        deviceContext: String,
        plan: ChatPlan,
        splitterSettings: ReplySplitterSettings,
        webSearchContext: String?,
        mcpToolContext: String?,
        globalCoreMemoryContext: String?,
        memoryContext: String?,
        deviceToolContext: String? = null
    ): String {
        val messages = buildReplyMessages(
            character, conversation, userName, deviceContext, plan, splitterSettings,
            webSearchContext, mcpToolContext, deviceToolContext, globalCoreMemoryContext, memoryContext
        )
        return OpenAiClient.requestCompletion(provider, model, messages, replySampling(selectedModels.allowModelWebSearch))
    }

    private fun requestChatReplyStream(
        provider: ApiProvider,
        model: String,
        character: Character,
        conversation: List<ChatMessage>,
        userName: String,
        deviceContext: String,
        plan: ChatPlan,
        splitterSettings: ReplySplitterSettings,
        webSearchContext: String?,
        mcpToolContext: String?,
        deviceToolContext: String?,
        globalCoreMemoryContext: String?,
        memoryContext: String?,
        onDelta: (String) -> Unit
    ): String {
        val messages = buildReplyMessages(
            character, conversation, userName, deviceContext, plan, splitterSettings,
            webSearchContext, mcpToolContext, deviceToolContext, globalCoreMemoryContext, memoryContext
        )
        return OpenAiClient.requestCompletionStream(
            provider, model, messages, replySampling(selectedModels.allowModelWebSearch), onDelta
        )
    }

    private fun buildReplyMessages(
        character: Character,
        conversation: List<ChatMessage>,
        userName: String,
        deviceContext: String,
        plan: ChatPlan,
        splitterSettings: ReplySplitterSettings,
        webSearchContext: String?,
        mcpToolContext: String?,
        deviceToolContext: String?,
        globalCoreMemoryContext: String?,
        memoryContext: String?
    ): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "system")
            put(
                "content",
                buildChatReplySystemPrompt(
                    character, userName, deviceContext, plan, splitterSettings,
                    webSearchContext, mcpToolContext, deviceToolContext, globalCoreMemoryContext, memoryContext
                )
            )
        })
        trimConversationForReply(conversation).forEach { message ->
            put(JSONObject().apply {
                put("role", if (message.fromUser) "user" else "assistant")
                put("content", message.content)
            })
        }
    }

    private fun friendlyError(endpoint: String, error: Throwable): String {
        val host = runCatching { URI(endpoint).host }.getOrNull().orEmpty().ifBlank { "该服务商" }
        return when (error) {
            is SocketTimeoutException, is ConnectException ->
                "无法连接到 $host。当前网络无法访问该服务商，请检查网络、代理或防火墙后重试。"
            is UnknownHostException -> "无法解析 $host，请检查 DNS、网络或 API 地址。"
            else -> error.message?.take(180) ?: "无法读取模型列表，请检查 API 地址和密钥。"
        }
    }

    private fun friendlyWebSearchError(error: Throwable): String = when (error) {
        is SocketTimeoutException, is ConnectException -> "搜索服务连接超时，请检查网络后重试"
        is UnknownHostException -> "无法解析搜索服务地址，请检查网络或 DNS"
        else -> {
            val msg = error.message?.take(160).orEmpty()
            when {
                msg.contains("验证码", ignoreCase = true) ->
                    "搜索页要求验证码，暂时无法自动查询。可稍后重试，或让我根据已有知识回答。"
                msg.contains("HTTP 4") || msg.contains("HTTP 5") ->
                    "搜索服务返回错误：$msg"
                else -> "网页查询未成功：$msg".ifBlank { "暂时无法获取网页结果" }
            }
        }
    }

    private fun friendlyMcpError(error: Throwable): String = when (error) {
        is SocketTimeoutException, is ConnectException -> "无法连接到 MCP 服务器，请检查地址、网络和服务器状态"
        is UnknownHostException -> "无法解析 MCP 服务器地址，请检查地址或网络"
        else -> error.message?.take(160)?.ifBlank { null } ?: "MCP 服务暂时不可用"
    }

    private fun friendlyChatError(endpoint: String, error: Throwable): String {
        val host = runCatching { URI(endpoint).host }.getOrNull().orEmpty().ifBlank { "该服务商" }
        return when (error) {
            is SocketTimeoutException, is ConnectException ->
                "无法连接到 $host，请检查网络、代理、防火墙、API 地址和密钥。"
            is UnknownHostException -> "无法解析 $host，请检查网络或 API 地址。"
            else -> error.message?.take(180) ?: "请检查 API 地址、密钥和所选聊天模型。"
        }
    }

    /** Re-checks Shizuku install/binder/permission state from the settings screen. */
    fun refreshShizuku() {
        val context = appContext ?: return
        ShizukuClient.refresh(context)
        recordLog("刷新 Shizuku 状态：${ShizukuClient.statusLabel()}")
    }

    fun requestShizukuPermission() {
        ShizukuClient.requestPermission()
        recordLog("已请求 Shizuku 授权")
    }

    fun openShizukuApp() {
        val context = appContext ?: return
        val opened = ShizukuClient.openShizukuApp(context)
        if (!opened) {
            ShizukuClient.openDownloadPage(context)
            recordLog("未找到 Shizuku 应用，已打开下载页")
        } else {
            recordLog("已打开 Shizuku 应用")
        }
    }

    fun shizukuPrimaryAction() {
        val context = appContext ?: return
        when (ShizukuClient.state) {
            ShizukuClient.State.NotInstalled -> ShizukuClient.openDownloadPage(context)
            ShizukuClient.State.WaitingForService, ShizukuClient.State.Dead -> {
                if (!ShizukuClient.openShizukuApp(context)) ShizukuClient.openDownloadPage(context)
                ShizukuClient.refresh(context)
            }
            ShizukuClient.State.PermissionNeeded, ShizukuClient.State.PermissionDenied ->
                ShizukuClient.requestPermission()
            ShizukuClient.State.Connecting, ShizukuClient.State.Ready -> Unit
        }
    }

    fun openShizukuPage() {
        destination = Destination.Shizuku
        shizukuTestResult = null
    }

    /** Runs a safe diagnostic command through the privileged UserService. */
    fun testShizukuConnection() {
        if (isShizukuTesting) return
        isShizukuTesting = true
        shizukuTestResult = "正在检测…"
        providerScope.launch {
            val result = ShizukuClient.exec("id; getprop ro.build.version.release; echo OK")
            withContext(Dispatchers.Main) {
                isShizukuTesting = false
                shizukuTestResult = if (result.startsWith("Shizuku") || result.startsWith("尚未") || result.startsWith("无法")) {
                    "检测失败：$result"
                } else {
                    "检测成功\n$result"
                }
                recordLog("Shizuku 连接检测：${result.take(120)}")
            }
        }
    }

    fun copyShizukuAdbCommand(context: Context) {
        val command = ShizukuClient.adbStartCommand(context)
        runCatching {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Shizuku ADB 启动命令", command))
        }
        recordLog("已复制 Shizuku ADB 启动命令")
    }

    override fun onCleared() {
        appContext?.let { ShizukuClient.detach(it) }
        providerScope.cancel()
        super.onCleared()
    }
}
