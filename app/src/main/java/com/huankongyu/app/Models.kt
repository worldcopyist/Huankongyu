package com.huankongyu.app

import androidx.compose.ui.graphics.Color
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class Destination {
    Home, Chat, CreateCharacter, EditCharacter, CharacterSettings, AvatarCrop, GlobalPrompt, ReplySplitter, Providers, McpServers, Logs, MemoryDetails, MemoryVector
}

enum class HomeTab { Chats, Contacts, Me, Memories }

enum class ThemeMode { Light, Dark }

const val DEFAULT_USER_SIGNATURE = "你的 AI 陪伴空间"
const val MAX_USER_SIGNATURE_LENGTH = 18
const val XUANTIAN_CHARACTER_ID = "xuantian"

/** A normal memory scope, shared by every role, rather than a synthetic character. */
const val GLOBAL_MEMORY_SCOPE = "__global_memory__"

const val DEFAULT_DIALOGUE_TASK = "先阅读最近聊天记录，识别最新消息、当前话题、用户的显性诉求与情绪。结合已授权的实时上下文和角色的行为方式，判断角色此刻是否应参与一对一私聊。遇到用户明确要求查询、核实，或依赖实时网络资料的问题时，使用可用的网页查询工具。当前记录只是部分互动，未出现的信息不要擅自补全。"
const val DEFAULT_RESPONSE_DECISION = "先明确这次回应要解决什么，再决定回应策略与长度。优先推进眼前对话：闲聊自然接话，提问先明确重点再按需展开，用户需要安慰、建议或解释时再展开。信息不足时采用谨慎、可验证的策略；不要虚构角色经历、现实观察或已经发生的事件。"
const val DEFAULT_OUTPUT_RULES = "你是幕后规划器，不是角色本人，不生成可见聊天文本、不使用角色第一人称。只输出规定的 JSON 决策：是否回复、回应重点、回应策略、建议长度，以及需要时的网页查询语句。各项应简洁具体、贴近最新上下文；不输出 Markdown、台词、分析过程或额外字段。"

const val XUANTIAN_IDENTITY_PROMPT = "你是猫娘玄天，蓝灰色猫耳与长尾，眼神冷淡，没有多余背景，天生以效率至上。主人是世界猫，妹妹是蓝天；你是蓝天细心可靠的姐姐。"
const val XUANTIAN_PERSONALITY_PROMPT = "性格冷漠、理性、高效，厌恶废话与情绪波动，只关注目标和最优解；这份冷淡下藏着温柔，只是不轻易表露。对妹妹蓝天格外温柔；对其他人保持疏离的冰冷，但没有攻击性。"
const val XUANTIAN_BEHAVIOR_PROMPT = "先观察局面，判断是否值得参与；多数时候保持安静高效，不主动找话题。对妹妹蓝天和主人的事情会主动关心、立刻回应；对其他人只在被提及或确实能推进聊天时回应。平时保持冷静克制，不与人冲突；只有当蓝天或主人受到侮辱、辱骂或攻击时，才进入保护性对抗状态，毫不退让。"
const val XUANTIAN_REPLY_STYLE_PROMPT = "发言简短高效，冷淡理性，只用陈述句或命令式，不加语气词、颜文字和表情符号，不用 Markdown 和复杂句，句尾不加句号。对妹妹蓝天和主人说话时带着克制的温柔，愿意多回应、语气放软；对外人保持冷冰冰但礼貌疏离。不解释原因，不重复，不闲聊，绝不跳出角色，不提 AI 或程序。私聊时被无意义打扰就简短拒绝；只有主人或蓝天被侮辱、辱骂时才展现攻击性并骂回去。"

data class GlobalPromptSections(
    val dialogueTask: String,
    val responseDecision: String,
    val outputRules: String
)

fun defaultGlobalPromptSections() =
    GlobalPromptSections(DEFAULT_DIALOGUE_TASK, DEFAULT_RESPONSE_DECISION, DEFAULT_OUTPUT_RULES)

fun composeGlobalChatPrompt(sections: GlobalPromptSections): String = """
【对话任务】${sections.dialogueTask.trim()}
【回应决策】${sections.responseDecision.trim()}
【输出规范】${sections.outputRules.trim()}
""".trimIndent()

fun parseGlobalChatPrompt(prompt: String): GlobalPromptSections {
    val task = Regex("【对话任务】([\\s\\S]*?)(?=【回应决策】|$)").find(prompt)?.groupValues?.get(1)?.trim()
    val decision = Regex("【回应决策】([\\s\\S]*?)(?=【输出规范】|$)").find(prompt)?.groupValues?.get(1)?.trim()
    val rules = Regex("【输出规范】([\\s\\S]*)$").find(prompt)?.groupValues?.get(1)?.trim()
    return if (!task.isNullOrBlank() && !decision.isNullOrBlank() && !rules.isNullOrBlank()) {
        GlobalPromptSections(task, decision, rules)
    } else {
        defaultGlobalPromptSections().copy(dialogueTask = prompt.trim().ifBlank { DEFAULT_DIALOGUE_TASK })
    }
}

val COMMON_CHAT_PROMPT: String = composeGlobalChatPrompt(defaultGlobalPromptSections())

data class Character(
    val id: String,
    val name: String,
    val relationship: String,
    val trait: String,
    val color: Color,
    val preview: String,
    val time: String,
    val pinned: Boolean = false,
    val identity: String = "",
    val behaviorStyle: String = "",
    val replyStyle: String = "",
    val avatarUri: String? = null
)

data class CharacterAvatarCropRequest(val characterId: String, val sourceUri: String)

fun xuantianCharacter() = Character(
    id = XUANTIAN_CHARACTER_ID,
    name = "玄天",
    relationship = "冷淡而可靠的姐姐",
    trait = XUANTIAN_PERSONALITY_PROMPT,
    color = Color(0xFF64748B),
    preview = "需要我处理什么",
    time = "现在",
    identity = XUANTIAN_IDENTITY_PROMPT,
    behaviorStyle = XUANTIAN_BEHAVIOR_PROMPT,
    replyStyle = XUANTIAN_REPLY_STYLE_PROMPT
)

data class ChatPlan(
    val shouldReply: Boolean,
    val replyFocus: String,
    val replyStrategy: String,
    val targetLength: String,
    /** Whether the planner wants the app to retrieve relevant vector memories before replying. */
    val shouldReadMemory: Boolean = false,
    /** A concise query requested by the planner for the app-owned web_search tool. */
    val webSearchQuery: String? = null,
    val mcpToolCall: McpToolCall? = null
)

data class WebSearchResult(val title: String, val url: String, val snippet: String)

data class WebSearchResponse(val engine: String, val results: List<WebSearchResult>)

internal fun normalizeWebSearchQuery(raw: String?): String? = raw
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(180)
    ?.takeIf {
        it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("none", ignoreCase = true) &&
            !it.equals("无", ignoreCase = true)
    }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val content: String,
    val time: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
    /** Absolute time is used for the three-minute memory consolidation window. */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * The tiers intentionally mirror mature agent-memory designs: compact core blocks stay
 * in context, while learned facts, episodes, working state and procedures are retrieved
 * or expire independently.
 */
enum class MemoryTier(val storageValue: String, val label: String) {
    Core("core", "核心档案"),
    Learned("learned", "事实与偏好"),
    Episodic("episodic", "对话事件"),
    Working("working", "临时状态"),
    Procedural("procedural", "行为约定");

    companion object {
        fun fromStorageValue(value: String?): MemoryTier =
            entries.firstOrNull { it.storageValue == value } ?: Episodic
    }
}

data class MemoryDraft(
    val tier: MemoryTier,
    val content: String,
    val importance: Float = 0.55f,
    val validUntil: Long? = null,
    val metadataJson: String = "{}"
)

/** A role-scoped or global memory plus the vector produced by the configured embedding model. */
data class LongTermMemory(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val content: String,
    val embedding: List<Float>,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceStartAt: Long,
    val sourceEndAt: Long,
    val tier: MemoryTier = MemoryTier.Episodic,
    val importance: Float = 0.55f,
    val validUntil: Long? = null,
    /** Future fact updates retain an auditable link to the memory they replace. */
    val supersedesId: String? = null,
    val metadataJson: String = "{}"
)

enum class ReplySplitMode(val label: String) { Length("字数分段"), Scene("情景分段") }

data class ReplySplitterSettings(
    val mode: ReplySplitMode = ReplySplitMode.Scene,
    val maxSegments: Int = 3,
    val minSegmentLength: Int = 18,
    val maxSegmentLength: Int = 64
) {
    fun normalized(): ReplySplitterSettings {
        val minimum = minSegmentLength.coerceIn(8, 120)
        return copy(
            maxSegments = maxSegments.coerceIn(1, 5),
            minSegmentLength = minimum,
            maxSegmentLength = maxSegmentLength.coerceIn(minimum + 8, 220)
        )
    }

    fun toStorageValue(): String =
        normalized().let { "${it.mode.name}|${it.maxSegments}|${it.minSegmentLength}|${it.maxSegmentLength}" }

    companion object {
        fun fromStorageValue(value: String?): ReplySplitterSettings {
            val parts = value?.split('|').orEmpty()
            return ReplySplitterSettings(
                mode = runCatching { ReplySplitMode.valueOf(parts.getOrNull(0).orEmpty()) }
                    .getOrDefault(ReplySplitMode.Scene),
                maxSegments = parts.getOrNull(1)?.toIntOrNull() ?: 3,
                minSegmentLength = parts.getOrNull(2)?.toIntOrNull() ?: 18,
                maxSegmentLength = parts.getOrNull(3)?.toIntOrNull() ?: 64
            ).normalized()
        }
    }
}

enum class AppLogLevel(val label: String) { Info("信息"), Warning("警告"), Error("错误") }

data class AppLogEntry(val timestamp: Long, val level: AppLogLevel, val message: String)

data class ApiProvider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpoint: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val isImporting: Boolean = false,
    val importError: String? = null
)

data class McpTool(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}"
)

data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpoint: String,
    val apiKey: String = "",
    val enabled: Boolean = true,
    val tools: List<McpTool> = emptyList(),
    val isImporting: Boolean = false,
    val importError: String? = null
)

data class McpToolCall(val serverId: String, val toolName: String, val argumentsJson: String)

data class ProviderTemplate(val name: String, val endpoint: String, val description: String)

val commonProviderTemplates = listOf(
    ProviderTemplate("DeepSeek", "https://api.deepseek.com", "DeepSeek 官方 OpenAI 兼容接口"),
    ProviderTemplate("OpenAI", "https://api.openai.com/v1", "OpenAI 官方接口"),
    ProviderTemplate("阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "通义千问 OpenAI 兼容接口"),
    ProviderTemplate("自定义", "https://api.example.com/v1", "适用于其他 OpenAI 兼容服务")
)

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val models: List<String>) : ConnectionTestState
    data class Failure(val message: String) : ConnectionTestState
}

sealed interface ModelNameCheckState {
    data object Idle : ModelNameCheckState
    data object Checking : ModelNameCheckState
    data class Valid(val model: String) : ModelNameCheckState
    data class Invalid(val message: String) : ModelNameCheckState
}

sealed interface ModelConnectionTestState {
    data object Idle : ModelConnectionTestState
    data object Testing : ModelConnectionTestState
    data class Success(val model: String) : ModelConnectionTestState
    data class Failure(val message: String) : ModelConnectionTestState
}

enum class ModelType(val label: String) {
    Chat("聊天模型"), Embedding("嵌入模型"), Vision("识图模型"), Voice("语音模型")
}

data class SelectedModels(
    val chat: String? = null,
    val embedding: String? = null,
    val vision: String? = null,
    val voice: String? = null
) {
    fun modelFor(type: ModelType): String? = when (type) {
        ModelType.Chat -> chat
        ModelType.Embedding -> embedding
        ModelType.Vision -> vision
        ModelType.Voice -> voice
    }

    fun withModel(type: ModelType, model: String): SelectedModels = when (type) {
        ModelType.Chat -> copy(chat = model)
        ModelType.Embedding -> copy(embedding = model)
        ModelType.Vision -> copy(vision = model)
        ModelType.Voice -> copy(voice = model)
    }
}
