package com.huankongyu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huankongyu.app.ui.theme.HuankongyuTheme
import com.huankongyu.app.ui.theme.IslandBlue
import com.huankongyu.app.ui.theme.IslandBubble
import com.huankongyu.app.ui.theme.IslandMuted
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HuankongyuTheme { HuankongyuApp() }
        }
    }
}

enum class Destination { Home, Chat, Settings, CreateCharacter }

data class Character(
    val id: String,
    val name: String,
    val relationship: String,
    val trait: String,
    val color: Color,
    val preview: String,
    val time: String
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val content: String,
    val time: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
)

data class ApiProfile(
    val name: String,
    val endpoint: String,
    val chatModel: String,
    val embeddingModel: String
)

class AppViewModel : ViewModel() {
    var destination by mutableStateOf(Destination.Home)
    var selectedCharacterId by mutableStateOf("lan")
    val characters = mutableStateListOf(
        Character(
            id = "lan",
            name = "澜",
            relationship = "安静的陪伴者",
            trait = "温柔、敏锐、会认真听你说话",
            color = Color(0xFF3A79F7),
            preview = "今天想从哪里开始聊？",
            time = "现在"
        ),
        Character(
            id = "xinye",
            name = "星野",
            relationship = "灵感搭档",
            trait = "好奇、轻快、喜欢收集小发现",
            color = Color(0xFF8A60E8),
            preview = "我在整理今天的小确幸。",
            time = "09:42"
        )
    )
    private val threads = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage>>()
    val apiProfiles = mutableStateListOf<ApiProfile>()

    init {
        threads["lan"] = mutableStateListOf(
            ChatMessage(fromUser = false, content = "你好，我是澜。这里是幻空屿的第一座小岛。")
        )
        threads["xinye"] = mutableStateListOf(
            ChatMessage(fromUser = false, content = "今天也一起收集一点轻盈的灵感吧。")
        )
    }

    fun selectedCharacter(): Character = characters.first { it.id == selectedCharacterId }

    fun messagesFor(characterId: String): androidx.compose.runtime.snapshots.SnapshotStateList<ChatMessage> =
        threads.getOrPut(characterId) { mutableStateListOf() }

    fun openChat(characterId: String) {
        selectedCharacterId = characterId
        destination = Destination.Chat
    }

    fun createCharacter(name: String, relationship: String, trait: String) {
        val trimmedName = name.trim().ifEmpty { "新角色" }
        val id = UUID.randomUUID().toString()
        val character = Character(
            id = id,
            name = trimmedName,
            relationship = relationship.trim().ifEmpty { "我的 AI 伙伴" },
            trait = trait.trim().ifEmpty { "温柔、真诚、愿意倾听" },
            color = listOf(Color(0xFF3A79F7), Color(0xFF8A60E8), Color(0xFFEA6B8B), Color(0xFF16A779)).random(),
            preview = "新的对话，从一句问候开始。",
            time = "现在"
        )
        characters.add(0, character)
        threads[id] = mutableStateListOf(
            ChatMessage(fromUser = false, content = "你好，我是$trimmedName。很高兴在幻空屿遇见你。")
        )
        openChat(id)
    }

    fun sendMessage(content: String) {
        val value = content.trim()
        if (value.isEmpty()) return
        val thread = messagesFor(selectedCharacterId)
        thread.add(ChatMessage(fromUser = true, content = value))
        val character = selectedCharacter()
        val reply = if (apiProfiles.isEmpty()) {
            "我收到了。现在是本地体验模式：添加模型配置后，我会用真正的 AI 回应你。"
        } else {
            "我已经记下这句话。模型连接将在下一阶段接入；现在先把这段对话留在岛上。"
        }
        thread.add(ChatMessage(fromUser = false, content = reply))
        val index = characters.indexOfFirst { it.id == selectedCharacterId }
        if (index >= 0) characters[index] = character.copy(preview = reply, time = "现在")
    }

    fun addApiProfile(name: String, endpoint: String, chat: String, embedding: String) {
        apiProfiles.add(
            ApiProfile(
                name = name.trim().ifEmpty { "我的模型服务" },
                endpoint = endpoint.trim().ifEmpty { "https://api.example.com/v1" },
                chatModel = chat.trim().ifEmpty { "chat-model" },
                embeddingModel = embedding.trim().ifEmpty { "embedding-model" }
            )
        )
    }
}

@Composable
private fun HuankongyuApp(viewModel: AppViewModel = viewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    BackHandler(enabled = viewModel.destination != Destination.Home) {
        viewModel.destination = Destination.Home
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (viewModel.destination) {
                Destination.Home -> HomeScreen(
                    characters = viewModel.characters,
                    onOpenSettings = { viewModel.destination = Destination.Settings },
                    onCreateCharacter = { viewModel.destination = Destination.CreateCharacter },
                    onOpenChat = viewModel::openChat
                )
                Destination.Chat -> ChatScreen(
                    character = viewModel.selectedCharacter(),
                    messages = viewModel.messagesFor(viewModel.selectedCharacterId),
                    configured = viewModel.apiProfiles.isNotEmpty(),
                    onBack = { viewModel.destination = Destination.Home },
                    onSend = viewModel::sendMessage,
                    onAttachment = { message ->
                        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                    }
                )
                Destination.Settings -> SettingsScreen(
                    profiles = viewModel.apiProfiles,
                    onBack = { viewModel.destination = Destination.Home },
                    onAddProfile = viewModel::addApiProfile
                )
                Destination.CreateCharacter -> CreateCharacterScreen(
                    onBack = { viewModel.destination = Destination.Home },
                    onSave = viewModel::createCharacter
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    characters: List<Character>,
    onOpenSettings: () -> Unit,
    onCreateCharacter: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("幻空屿", fontWeight = FontWeight.Bold)
                    Text("你的本地 AI 陪伴空间", style = MaterialTheme.typography.labelSmall, color = IslandMuted)
                }
            },
            navigationIcon = {
                TextButton(onClick = onOpenSettings) { Text("我的") }
            },
            actions = {
                TextButton(onClick = onCreateCharacter) { Text("＋ 新角色", color = IslandBlue) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        AssistChip(
            onClick = {},
            label = { Text("数据只保存在这台设备") },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(characters, key = { it.id }) { character ->
                CharacterRow(character = character, onClick = { onOpenChat(character.id) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), modifier = Modifier.padding(start = 84.dp))
            }
        }
    }
}

@Composable
private fun CharacterRow(character: Character, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = character.name, color = character.color, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(character.relationship, style = MaterialTheme.typography.labelSmall, color = IslandMuted, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(character.preview, style = MaterialTheme.typography.bodyMedium, color = IslandMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(character.time, style = MaterialTheme.typography.labelSmall, color = IslandMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    character: Character,
    messages: List<ChatMessage>,
    configured: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onAttachment: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(character.name, fontWeight = FontWeight.Bold)
                    Text(character.relationship, style = MaterialTheme.typography.labelSmall, color = IslandMuted)
                }
            },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            actions = { TextButton(onClick = { onAttachment("角色设置将在下一阶段开放") }) { Text("•••") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (!configured) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = IslandBubble),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    "本地体验模式 · 在“我的”添加兼容 API 后，可启用真实 AI 对话和长期记忆。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = IslandMuted
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message, character) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(onClick = { onAttachment("图片、文档与表情包导入将在下一阶段接入") }) { Text("＋") }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("和${character.name}说点什么…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend(draft); draft = "" },
                enabled = draft.isNotBlank(),
                shape = RoundedCornerShape(18.dp)
            ) { Text("发送") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, character: Character) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.fromUser) {
            Avatar(character.name, character.color, 34.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start) {
            Surface(
                color = if (message.fromUser) IslandBlue else MaterialTheme.colorScheme.surface,
                contentColor = if (message.fromUser) Color.White else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(18.dp),
                tonalElevation = if (message.fromUser) 0.dp else 1.dp
            ) {
                Text(message.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Text(message.time, modifier = Modifier.padding(top = 3.dp), style = MaterialTheme.typography.labelSmall, color = IslandMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(profiles: List<ApiProfile>, onBack: () -> Unit, onAddProfile: (String, String, String, String) -> Unit) {
    var showForm by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("我的", fontWeight = FontWeight.Bold) },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = IslandBubble)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("本地优先", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("聊天、角色和长期记忆将保存在设备加密空间中。", color = IslandMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("模型配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("支持 OpenAI 兼容接口", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { showForm = true }) { Text("添加") }
                }
            }
            if (profiles.isEmpty()) {
                item { Text("还没有模型配置。添加后可在下一阶段连接真实 AI。", color = IslandMuted) }
            } else {
                items(profiles) { profile ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold)
                            Text(profile.chatModel + " · " + profile.embeddingModel, color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                            Text(profile.endpoint, color = IslandMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("后续功能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("加密备份 · 应用锁 · 主动陪伴 · 语音聊天 · 生图", color = IslandMuted)
            }
        }
    }
    if (showForm) ApiProfileDialog(onDismiss = { showForm = false }, onSave = { name, endpoint, chat, embedding ->
        onAddProfile(name, endpoint, chat, embedding)
        showForm = false
    })
}

@Composable
private fun CreateCharacterScreen(onBack: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var trait by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("创建角色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("先用几句话描述 TA，幻空屿会在后续自动整理成人格提示词。", color = IslandMuted)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("角色名字") }, placeholder = { Text("例如：澜") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(relationship, { relationship = it }, Modifier.fillMaxWidth(), label = { Text("你们的关系") }, placeholder = { Text("例如：安静的陪伴者") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(trait, { trait = it }, Modifier.fillMaxWidth(), label = { Text("性格与说话方式") }, placeholder = { Text("例如：温柔、会认真倾听、不说教") }, minLines = 4)
        Spacer(Modifier.weight(1f))
        Button(onClick = { onSave(name, relationship, trait) }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), enabled = name.isNotBlank()) { Text("创建并开始聊天") }
    }
}

@Composable
private fun ApiProfileDialog(onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("https://api.example.com/v1") }
    var chat by remember { mutableStateOf("") }
    var embedding by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(22.dp)) {
                Text("添加模型配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("配置名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("API 地址") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(chat, { chat = it }, Modifier.fillMaxWidth(), label = { Text("聊天模型") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(embedding, { embedding = it }, Modifier.fillMaxWidth(), label = { Text("记忆嵌入模型") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("本阶段不会保存 API 密钥，也不会发送数据。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onSave(name, endpoint, chat, embedding) }) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHuankongyu() {
    HuankongyuTheme { HuankongyuApp() }
}
