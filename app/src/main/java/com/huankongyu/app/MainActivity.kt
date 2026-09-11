package com.huankongyu.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.huankongyu.app.ui.theme.HuankongyuTheme
import com.huankongyu.app.ui.theme.IslandBlue
import com.huankongyu.app.ui.theme.IslandMuted
import java.io.File
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HuankongyuApp() }
    }
}

private val NavigationSurfaceLight = Color(0xFFF0F3F8)
private val NavigationSurfaceDark = Color(0xFF17202E)

@Composable
private fun HuankongyuApp(viewModel: AppViewModel = viewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val permissionPreferences = remember(context) { context.getSharedPreferences("system_permission_prompt", Context.MODE_PRIVATE) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var permissionStateVersion by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPreferences.edit().putBoolean("shown", true).apply()
        permissionStateVersion += 1
    }
    val requestSystemPermissions = {
        val missing = missingSystemPermissions(context)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
    }
    LaunchedEffect(Unit) { viewModel.initializeProviderStore(context) }
    LaunchedEffect(Unit) {
        if (!permissionPreferences.getBoolean("shown", false) && missingSystemPermissions(context).isNotEmpty()) {
            showPermissionRationale = true
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importUserAvatar(context, uri)
    }
    val openAvatarPicker = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    var pendingCharacterAvatarId by remember { mutableStateOf<String?>(null) }
    val characterAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val characterId = pendingCharacterAvatarId
        pendingCharacterAvatarId = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null && characterId != null) viewModel.beginCharacterAvatarCrop(characterId, uri)
    }
    val launchCharacterAvatarPicker = {
        // ACTION_PICK opens the installed gallery's full album browser. Unlike the
        // Android Photo Picker's “safe access” UI, it can show user-created albums.
        characterAvatarPicker.launch(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
    val characterImagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchCharacterAvatarPicker()
    }
    val openCharacterAvatarPicker = { characterId: String ->
        pendingCharacterAvatarId = characterId
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) launchCharacterAvatarPicker() else characterImagePermissionLauncher.launch(permission)
    }

    val darkTheme = viewModel.themeMode == ThemeMode.Dark
    val navigationSurface = if (darkTheme) NavigationSurfaceDark else NavigationSurfaceLight
    val hasSystemPermissions = permissionStateVersion.let { missingSystemPermissions(context).isEmpty() }
    HuankongyuTheme(darkTheme = darkTheme) {
        val view = LocalView.current
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            window.statusBarColor = navigationSurface.toArgb()
            window.navigationBarColor = navigationSurface.toArgb()
        }
        BackHandler(enabled = viewModel.destination != Destination.Home || viewModel.selectedTab != HomeTab.Chats) {
            when {
                viewModel.destination == Destination.GlobalPrompt -> { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me }
                viewModel.destination == Destination.Providers -> { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me }
                viewModel.destination == Destination.McpServers -> { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me }
                viewModel.destination == Destination.Logs -> { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me }
                viewModel.destination == Destination.MemoryDetails -> viewModel.closeMemoryDetails()
                viewModel.destination == Destination.EditCharacter -> viewModel.destination = Destination.Chat
                viewModel.destination == Destination.CharacterSettings -> viewModel.destination = Destination.Chat
                viewModel.destination == Destination.AvatarCrop -> { viewModel.characterAvatarCropRequest = null; viewModel.destination = Destination.CharacterSettings }
                viewModel.destination == Destination.Chat -> viewModel.requestChatExit()
                viewModel.destination != Destination.Home -> { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Chats }
                viewModel.selectedTab != HomeTab.Chats -> viewModel.selectedTab = HomeTab.Chats
            }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = { if (viewModel.destination == Destination.Home) AppNavigationBar(viewModel.selectedTab) { viewModel.selectedTab = it } },
            // With edge-to-edge enabled, Scaffold is visible behind the status bar.
            // Keep that area on the same navigation surface instead of leaving a pale gap.
            containerColor = navigationSurface,
            // The navigation surface is a custom color, so Compose cannot infer a
            // matching foreground. Set it explicitly for readable dark-mode content.
            contentColor = MaterialTheme.colorScheme.onBackground
        ) { padding ->
            // Scaffold already contributes the system navigation inset when the bottom
            // app bar is absent. The chat composer owns that inset itself so it can
            // move with the IME. Applying both produced a second bottom measurement
            // after navigation, visibly lifting the composer and every chat message.
            val contentModifier = if (viewModel.destination == Destination.Chat) {
                Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).background(MaterialTheme.colorScheme.background)
            } else {
                Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
            }
            Box(contentModifier) {
                when (viewModel.destination) {
                    Destination.Home -> when (viewModel.selectedTab) {
                        HomeTab.Chats -> HomeScreen(viewModel.userName, viewModel.userSignature, viewModel.characters, viewModel.userAvatarUri, { viewModel.selectedTab = HomeTab.Contacts }, viewModel::openChat, viewModel::togglePinned, viewModel::deleteCharacter)
                        HomeTab.Contacts -> ContactsScreen(viewModel.characters, { viewModel.destination = Destination.CreateCharacter }, viewModel::openChat)
                        HomeTab.Me -> SettingsScreen(
                            activeProvider = viewModel.activeProvider(),
                            selectedModels = viewModel.selectedModels,
                            userName = viewModel.userName,
                            userSignature = viewModel.userSignature,
                            userAvatarUri = viewModel.userAvatarUri,
                            darkMode = viewModel.themeMode == ThemeMode.Dark,
                            replySplitterSettings = viewModel.replySplitterSettings,
                            mcpServers = viewModel.mcpServers,
                            onEditAvatar = openAvatarPicker,
                            onUserNameChange = viewModel::updateUserName,
                            onUserSignatureChange = viewModel::updateUserSignature,
                            onOpenGlobalPrompt = { viewModel.destination = Destination.GlobalPrompt },
                            onOpenReplySplitter = { viewModel.destination = Destination.ReplySplitter },
                            onOpenMcpServers = { viewModel.destination = Destination.McpServers },
                            onOpenLogs = viewModel::openLogs,
                            onDarkModeChange = { enabled -> viewModel.applyThemeMode(if (enabled) ThemeMode.Dark else ThemeMode.Light) },
                            hasSystemPermissions = hasSystemPermissions,
                            onRequestSystemPermissions = requestSystemPermissions,
                            onOpenProviders = { viewModel.destination = Destination.Providers },
                            onSelectModel = viewModel::selectModel,
                            modelNameCheckState = viewModel.modelNameCheckState,
                            modelConnectionTestState = viewModel.modelConnectionTestState,
                            onValidateModelName = viewModel::validateModelName,
                            onTestModelConnectivity = viewModel::testModelConnectivity
                        )
                        HomeTab.Memories -> MemoriesScreen(
                            hasEmbeddingModel = viewModel.selectedModels.embedding != null,
                            memories = viewModel.longTermMemories,
                            characters = viewModel.characters,
                            userName = viewModel.userName,
                            userAvatarUri = viewModel.userAvatarUri,
                            latestChatAt = viewModel::latestChatAtForMemory,
                            onConfigureEmbedding = { viewModel.selectedTab = HomeTab.Me },
                            onOpenScope = viewModel::openMemoryScope
                        )
                    }
                    Destination.Chat -> Box(Modifier.fillMaxSize()) {
                        // Keep the destination page alive beneath the departing chat.
                        // This makes the rightward exit reveal the actual home page,
                        // rather than the empty Scaffold background.
                        if (viewModel.chatExitRequested) {
                            HomeScreen(
                                viewModel.userName,
                                viewModel.userSignature,
                                viewModel.characters,
                                viewModel.userAvatarUri,
                                { viewModel.selectedTab = HomeTab.Contacts },
                                viewModel::openChat,
                                viewModel::togglePinned,
                                viewModel::deleteCharacter
                            )
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                AppNavigationBar(HomeTab.Chats) { viewModel.selectedTab = it }
                            }
                        }
                        ChatScreen(
                            character = viewModel.selectedCharacter(),
                            userAvatarUri = viewModel.userAvatarUri,
                            messages = viewModel.messagesFor(viewModel.selectedCharacterId),
                            isResponding = viewModel.isChatResponding,
                            responseStatus = viewModel.chatStatus,
                            replyError = viewModel.chatError,
                            playEntrance = viewModel.animateChatEntrance,
                            onEntranceStarted = viewModel::consumeChatEntranceAnimation,
                            exitRequested = viewModel.chatExitRequested,
                            onBack = viewModel::requestChatExit,
                            onExitComplete = viewModel::completeChatExit,
                            onSend = viewModel::sendMessage,
                            onEditCharacter = { viewModel.destination = Destination.CharacterSettings }
                        ) { message -> coroutineScope.launch { snackbarHostState.showSnackbar(message) } }
                    }
                    Destination.CreateCharacter -> CreateCharacterScreen(
                        onBack = { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Chats },
                        onSave = viewModel::createCharacter
                    )
                    Destination.EditCharacter -> EditCharacterScreen(
                        character = viewModel.selectedCharacter(),
                        onBack = { viewModel.destination = Destination.Chat },
                        onSave = { name, relationship, identity, personality, behaviorStyle, replyStyle ->
                            viewModel.updateCharacter(viewModel.selectedCharacterId, name, relationship, identity, personality, behaviorStyle, replyStyle)
                            viewModel.destination = Destination.Chat
                        }
                    )
                    Destination.CharacterSettings -> CharacterSettingsScreen(
                        character = viewModel.selectedCharacter(),
                        onBack = { viewModel.destination = Destination.Chat },
                        onEditAvatar = { openCharacterAvatarPicker(viewModel.selectedCharacterId) },
                        onEditPrompt = { viewModel.destination = Destination.EditCharacter }
                    )
                    Destination.AvatarCrop -> viewModel.characterAvatarCropRequest?.let { request -> AvatarCropScreen(
                        sourceUri = request.sourceUri,
                        onCancel = { viewModel.characterAvatarCropRequest = null; viewModel.destination = Destination.CharacterSettings },
                        onSave = { bitmap -> viewModel.saveCroppedCharacterAvatar(context, request.characterId, bitmap) }
                    ) } ?: LaunchedEffect(Unit) { viewModel.destination = Destination.CharacterSettings }
                    Destination.GlobalPrompt -> GlobalPromptScreen(
                        sections = viewModel.globalPromptSections,
                        onBack = { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me },
                        onSave = { dialogueTask, responseDecision, outputRules ->
                            viewModel.updateGlobalPromptSections(dialogueTask, responseDecision, outputRules)
                            viewModel.destination = Destination.Home
                            viewModel.selectedTab = HomeTab.Me
                        }
                    )
                    Destination.ReplySplitter -> ReplySplitterSettingsScreen(
                        settings = viewModel.replySplitterSettings,
                        onBack = { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me },
                        onSave = {
                            viewModel.updateReplySplitterSettings(it)
                            viewModel.destination = Destination.Home
                            viewModel.selectedTab = HomeTab.Me
                        }
                    )
                    Destination.Providers -> ProviderManagementScreen(
                        providers = viewModel.apiProviders,
                        activeProviderId = viewModel.activeProviderId,
                        connectionTestState = viewModel.connectionTestState,
                        onBack = { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me },
                        onAddProvider = viewModel::addProvider,
                        onTestConnection = viewModel::testProviderConnection,
                        onSelectProvider = viewModel::selectProvider,
                        onImportModels = viewModel::importModels
                    )
                    Destination.McpServers -> McpServersScreen(
                        servers = viewModel.mcpServers,
                        onBack = { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me },
                        onAdd = viewModel::addMcpServer,
                        onToggle = viewModel::setMcpServerEnabled,
                        onImport = viewModel::importMcpTools,
                        onDelete = viewModel::deleteMcpServer
                    )
                    Destination.Logs -> LogsScreen(
                        logs = viewModel.recentLogs,
                        isLoading = viewModel.isLoadingLogs,
                        onBack = { viewModel.destination = Destination.Home; viewModel.selectedTab = HomeTab.Me }
                    )
                    Destination.MemoryDetails -> {
                        val scopeId = viewModel.selectedMemoryScope
                        if (scopeId != null) {
                            MemoryDetailsScreen(
                                scopeId = scopeId,
                                userName = viewModel.userName,
                                userAvatarUri = viewModel.userAvatarUri,
                                character = viewModel.characters.firstOrNull { it.id == scopeId },
                                memories = viewModel.longTermMemories.filter { it.characterId == scopeId },
                                onBack = viewModel::closeMemoryDetails,
                                onSaveGlobalMemory = viewModel::saveGlobalMemory,
                                globalMemorySaveStatus = viewModel.globalMemorySaveStatus,
                                onUpdateMemory = viewModel::updateMemoryFromLibrary,
                                onDeleteMemory = viewModel::deleteMemoryFromLibrary,
                                onOpenVector = viewModel::openMemoryVector
                            )
                        } else {
                            LaunchedEffect(Unit) { viewModel.closeMemoryDetails() }
                        }
                    }
                    Destination.MemoryVector -> {
                        val scopeId = viewModel.selectedMemoryScope
                        if (scopeId != null) {
                            MemoryVectorScreen(
                                scopeId = scopeId,
                                title = if (scopeId == GLOBAL_MEMORY_SCOPE) "全局记忆向量" else "${viewModel.characters.firstOrNull { it.id == scopeId }?.name ?: "角色"}的向量记忆",
                                memories = viewModel.longTermMemories.filter { it.characterId == scopeId },
                                onBack = viewModel::closeMemoryVector,
                                onUpdateMemory = viewModel::updateMemoryFromLibrary,
                                onDeleteMemory = viewModel::deleteMemoryFromLibrary
                            )
                        } else {
                            LaunchedEffect(Unit) { viewModel.closeMemoryDetails() }
                        }
                    }
                }
            }
        }
        if (showPermissionRationale) {
            AlertDialog(
                onDismissRequest = { showPermissionRationale = false; permissionPreferences.edit().putBoolean("shown", true).apply() },
                title = { Text("授予系统权限") },
                text = { Text("允许后，幻空屿可以读取日历中的时间和地点、使用当前位置，并向你发送通知。日期、日历标题/地点与位置会在你发消息时提供给当前选择的模型服务。读取系统时间本身不需要授权。") },
                confirmButton = {
                    TextButton(onClick = {
                        showPermissionRationale = false
                        permissionPreferences.edit().putBoolean("shown", true).apply()
                        requestSystemPermissions()
                    }) { Text("继续授权") }
                },
                dismissButton = { TextButton(onClick = { showPermissionRationale = false; permissionPreferences.edit().putBoolean("shown", true).apply() }) { Text("暂不") } }
            )
        }
    }
}

private fun missingSystemPermissions(context: Context): Array<String> = buildList {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CALENDAR)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun AppNavigationBar(selectedTab: HomeTab, onSelect: (HomeTab) -> Unit) {
    val items = listOf(HomeTab.Chats to ("聊" to "聊天"), HomeTab.Contacts to ("人" to "通讯录"), HomeTab.Me to ("我" to "我"), HomeTab.Memories to ("忆" to "记忆库"))
    val unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    NavigationBar(
        modifier = Modifier.height(64.dp),
        containerColor = navigationSurfaceColor(),
        tonalElevation = 0.dp
    ) {
        items.forEach { (tab, label) ->
            val selected = selectedTab == tab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    NavigationGlyph(label.first, selected, if (selected) IslandBlue else unselectedColor)
                },
                label = { Text(label.second, color = if (selected) IslandBlue else unselectedColor) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = IslandBlue,
                    unselectedIconColor = unselectedColor,
                    selectedTextColor = IslandBlue,
                    unselectedTextColor = unselectedColor
                )
            )
        }
    }
}

/**
 * Draw the whole glyph once with Android's text renderer.  Compose's per-stroke
 * text style outlines each CJK component separately, producing doubled lines at
 * intersections.  A single native text outline keeps the inside clean.
 */
@Composable
private fun NavigationGlyph(glyph: String, selected: Boolean, color: Color) {
    Canvas(Modifier.width(40.dp).height(30.dp)) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 27.sp.toPx()
            style = if (selected) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = if (selected) 0f else 1.05.dp.toPx()
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val baseline = size.height / 2f - (paint.ascent() + paint.descent()) / 2f
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(glyph, size.width / 2f, baseline, paint) }
    }
}

@Composable
private fun CompactHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Surface(
        color = navigationSurfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            HorizontalDivider(color = IslandBlue.copy(alpha = 0.16f))
        }
    }
}

@Composable
private fun navigationSurfaceColor(): Color =
    if (MaterialTheme.colorScheme.background.red < 0.2f) NavigationSurfaceDark else NavigationSurfaceLight

@Composable
private fun HomeScreen(userName: String, userSignature: String, characters: List<Character>, userAvatarUri: String?, onOpenContacts: () -> Unit, onOpenChat: (String) -> Unit, onTogglePinned: (String) -> Unit, onDelete: (String) -> Unit) {
    var pendingDelete by remember { mutableStateOf<Character?>(null) }
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = navigationSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            Column {
                // 42dp avatar + symmetric 8dp breathing room above and below.
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShakingUserAvatar(userAvatarUri, 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(userName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            userSignature,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(color = IslandBlue.copy(alpha = 0.16f))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (characters.isEmpty()) EmptyCharacters(onOpenContacts) else LazyColumn(Modifier.fillMaxSize()) {
            items(characters, key = { it.id }) { character ->
                SwipeableCharacterRow(character, { onOpenChat(character.id) }, { onTogglePinned(character.id) }, { pendingDelete = character })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), modifier = Modifier.padding(start = 84.dp))
            }
        }
    }
    pendingDelete?.let { character ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除聊天") }, text = { Text("确定删除与“${character.name}”的聊天吗？这会同时删除本机保存的聊天内容。") }, confirmButton = { TextButton(onClick = { onDelete(character.id); pendingDelete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable
private fun EmptyCharacters(onOpenContacts: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("还没有聊天", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); Text("在通讯录中创建一个 AI 角色，开始你的第一段对话。", color = IslandMuted)
        Spacer(Modifier.height(16.dp)); Button(onClick = onOpenContacts) { Text("前往通讯录") }
    }
}

@Composable
private fun SwipeableCharacterRow(character: Character, onClick: () -> Unit, onTogglePinned: () -> Unit, onDelete: () -> Unit) {
    val density = LocalDensity.current
    val actionWidth = 148.dp
    val maxOffset = with(density) { -actionWidth.toPx() }
    val revealThreshold = with(density) { -72.dp.toPx() }
    var offsetX by remember(character.id) { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().height(82.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.width(74.dp).fillMaxSize().background(MaterialTheme.colorScheme.secondary).clickable { offsetX = 0f; onTogglePinned() }, contentAlignment = Alignment.Center) { Text(if (character.pinned) "取消置顶" else "置顶", color = Color.White, style = MaterialTheme.typography.labelLarge) }
            Box(Modifier.width(74.dp).fillMaxSize().background(MaterialTheme.colorScheme.error).clickable { offsetX = 0f; onDelete() }, contentAlignment = Alignment.Center) { Text("删除", color = Color.White, style = MaterialTheme.typography.labelLarge) }
        }
        Row(
            Modifier.fillMaxSize().offset { IntOffset(offsetX.roundToInt(), 0) }.background(MaterialTheme.colorScheme.background)
                .pointerInput(character.id) { detectHorizontalDragGestures(onHorizontalDrag = { change, dragAmount -> change.consume(); offsetX = (offsetX + dragAmount).coerceIn(maxOffset, 0f) }, onDragEnd = { offsetX = if (offsetX <= revealThreshold) maxOffset else 0f }, onDragCancel = { offsetX = 0f }) }
                .clickable { if (offsetX < 0f) offsetX = 0f else onClick() }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(character.name, character.color, 52.dp, character.avatarUri); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (character.pinned) { Spacer(Modifier.width(6.dp)); Text("已置顶", style = MaterialTheme.typography.labelSmall, color = IslandBlue) }
                    Spacer(Modifier.width(8.dp)); Text(character.relationship, style = MaterialTheme.typography.labelSmall, color = IslandMuted, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp)); Text(character.preview, style = MaterialTheme.typography.bodyMedium, color = IslandMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(character.time, style = MaterialTheme.typography.labelSmall, color = IslandMuted)
        }
    }
}

@Composable
private fun ContactsScreen(characters: List<Character>, onCreateCharacter: () -> Unit, onOpenChat: (String) -> Unit) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val groupedCharacters = characters.sortedWith(compareBy<Character> { contactInitial(it.name) }.thenBy { it.name })
        .groupBy { contactInitial(it.name) }
        .toSortedMap()
    val sectionPositions = buildMap {
        var position = 0
        groupedCharacters.forEach { (initial, members) ->
            put(initial, position)
            position += members.size + 1
        }
    }
    var activeInitial by remember(groupedCharacters.keys) { mutableStateOf(groupedCharacters.keys.firstOrNull() ?: '#') }
    LaunchedEffect(sectionPositions) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { firstVisibleIndex ->
            sectionPositions.entries
                .filter { it.value <= firstVisibleIndex }
                .maxByOrNull { it.value }
                ?.key
                ?.let { activeInitial = it }
        }
    }
    val indexColor = if (MaterialTheme.colorScheme.background.red < 0.2f) Color(0xFFE8EEF8) else Color(0xFF151515)
    Column(Modifier.fillMaxSize()) {
        CompactHeader("通讯录") { TextButton(onClick = onCreateCharacter) { Text("＋ 新角色", color = IslandBlue) } }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                groupedCharacters.forEach { (initial, members) ->
                    item(key = "section-$initial") { Text(initial.toString(), color = IslandBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                    items(members, key = { it.id }) { character ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenChat(character.id) }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(character.name, character.color, 48.dp, character.avatarUri); Spacer(Modifier.width(12.dp)); Column { Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(character.relationship, style = MaterialTheme.typography.bodySmall, color = IslandMuted) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), modifier = Modifier.padding(start = 60.dp))
                    }
                }
            }
            Column(Modifier.width(26.dp).padding(top = 6.dp, end = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                (('A'..'Z').toList() + '#').forEach { initial ->
                    val target = sectionPositions[initial]
                    val active = activeInitial == initial
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .clickable(enabled = target != null) {
                                activeInitial = initial
                                coroutineScope.launch { listState.animateScrollToItem(target ?: 0) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initial.toString(),
                            color = if (active) IslandBlue else indexColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(character: Character, userAvatarUri: String?, messages: List<ChatMessage>, isResponding: Boolean, responseStatus: String?, replyError: String?, playEntrance: Boolean, onEntranceStarted: () -> Unit, exitRequested: Boolean, onBack: () -> Unit, onExitComplete: () -> Unit, onSend: (String) -> Unit, onEditCharacter: () -> Unit, onAttachment: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    var entranceStarted by remember(character.id) { mutableStateOf(!playEntrance) }
    LaunchedEffect(playEntrance) {
        if (playEntrance) {
            entranceStarted = true
            onEntranceStarted()
        }
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(exitRequested) {
        if (exitRequested) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            delay(260)
            onExitComplete()
        }
    }
    var knownMessageCount by remember(character.id) { mutableStateOf(messages.size) }
    // In a reversed chat list, item zero is the newest message and is laid out
    // against the composer from the very first frame. This avoids the visible
    // post-layout scroll that used to lift the entire conversation on entry.
    var displayedNewestMessageId by remember(character.id) { mutableStateOf(messages.lastOrNull()?.id) }
    var wasResponding by remember(character.id) { mutableStateOf(isResponding) }
    var wasImeVisible by remember(character.id) { mutableStateOf(false) }
    val newOutgoingMessageIds = if (messages.size > knownMessageCount) {
        messages.drop(knownMessageCount).filter { it.fromUser }.map { it.id }.toSet()
    } else {
        emptySet()
    }
    LaunchedEffect(messages.size) { knownMessageCount = messages.size }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottom > 0
    // Only scroll after a genuinely new event or after the keyboard opens. Do not run
    // an initial scroll when this screen first appears: that was the source of the jump.
    LaunchedEffect(messages.lastOrNull()?.id, isResponding, imeVisible) {
        val newestMessageId = messages.lastOrNull()?.id
        val shouldAnchor =
            (newestMessageId != null && newestMessageId != displayedNewestMessageId) ||
                (isResponding && !wasResponding) ||
                (imeVisible && !wasImeVisible)
        if (shouldAnchor) listState.animateScrollToItem(0)
        displayedNewestMessageId = newestMessageId
        wasResponding = isResponding
        wasImeVisible = imeVisible
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val entranceOffset by animateDpAsState(
            targetValue = when {
                exitRequested -> maxWidth
                entranceStarted -> 0.dp
                else -> maxWidth
            },
            animationSpec = tween(durationMillis = 260),
            label = "chatPageEntrance"
        )
    Column(Modifier.fillMaxSize().offset(x = entranceOffset)) {
        Surface(color = navigationSurfaceColor()) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹ 返回", color = IslandBlue, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(character.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(if (isResponding) "正在输入中......" else character.relationship, style = MaterialTheme.typography.labelSmall, color = IslandMuted, maxLines = 1)
                }
                Text("•••", color = IslandBlue, modifier = Modifier.clickable(onClick = onEditCharacter).padding(8.dp))
            }
        }
        HorizontalDivider(color = IslandBlue.copy(alpha = 0.16f))
        val conversationModifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).then(
            if (imeBottom > 0) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    })
                }
            } else Modifier
        )
        LazyColumn(
            state = listState,
            modifier = conversationModifier,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            replyError?.let { error -> item("chat-error") { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp)) } }
            if (isResponding) item("typing") { Text(responseStatus ?: "${character.name} 正在回复…", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 42.dp, top = 2.dp)) }
            items(messages.asReversed(), key = { it.id }) { message -> MessageBubble(message, character, userAvatarUri, message.id in newOutgoingMessageIds) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(onClick = { onAttachment("图片、文档与表情包导入将在下一阶段接入") }) { Text("＋") }
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("和${character.name}说点什么…") }, maxLines = 4, shape = RoundedCornerShape(20.dp))
            Spacer(Modifier.width(8.dp)); Button(onClick = { onSend(draft); draft = "" }, enabled = draft.isNotBlank() && !isResponding, shape = RoundedCornerShape(18.dp)) { Text(if (isResponding) "回复中" else "发送") }
        }
    }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, character: Character, userAvatarUri: String?, animateEntry: Boolean = false) {
    var isVisible by remember(message.id) { mutableStateOf(!animateEntry) }
    LaunchedEffect(animateEntry) { if (animateEntry) isVisible = true }
    val alpha by animateFloatAsState(if (isVisible) 1f else 0f, animationSpec = tween(150), label = "messageAlpha")
    val scale by animateFloatAsState(if (isVisible) 1f else 0.88f, animationSpec = tween(190), label = "messageScale")
    val entryOffset by animateDpAsState(if (isVisible) 0.dp else 18.dp, animationSpec = tween(190), label = "messageOffset")
    Row(
        Modifier.fillMaxWidth().offset(x = if (message.fromUser) entryOffset else 0.dp).graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        },
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!message.fromUser) { Avatar(character.name, character.color, 34.dp, character.avatarUri); Spacer(Modifier.width(8.dp)) }
        Column(horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start) {
            Surface(color = if (message.fromUser) IslandBlue else MaterialTheme.colorScheme.surface, contentColor = if (message.fromUser) Color.White else MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(18.dp), tonalElevation = if (message.fromUser) 0.dp else 1.dp) {
                SelectionContainer { Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            Text(message.time, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.labelSmall, color = IslandMuted)
        }
        if (message.fromUser) { Spacer(Modifier.width(8.dp)); UserAvatar(userAvatarUri, onClick = null, size = 34.dp) }
    }
}

@Composable
private fun SettingsScreen(
    activeProvider: ApiProvider?,
    selectedModels: SelectedModels,
    userName: String,
    userSignature: String,
    userAvatarUri: String?,
    darkMode: Boolean,
    replySplitterSettings: ReplySplitterSettings,
    mcpServers: List<McpServer>,
    onEditAvatar: () -> Unit,
    onUserNameChange: (String) -> Unit,
    onUserSignatureChange: (String) -> Unit,
    onOpenGlobalPrompt: () -> Unit,
    onOpenReplySplitter: () -> Unit,
    onOpenMcpServers: () -> Unit,
    onOpenLogs: () -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    hasSystemPermissions: Boolean,
    onRequestSystemPermissions: () -> Unit,
    onOpenProviders: () -> Unit,
    onSelectModel: (ModelType, String) -> Unit,
    modelNameCheckState: ModelNameCheckState,
    modelConnectionTestState: ModelConnectionTestState,
    onValidateModelName: (String, String) -> Unit,
    onTestModelConnectivity: (String, String) -> Unit
) {
    var pickerType by remember { mutableStateOf<ModelType?>(null) }
    val settingsCardColor = if (darkMode) Color(0xFF17202E) else Color(0xFFF0F3F8)
    val cardColors = CardDefaults.cardColors(containerColor = settingsCardColor)
    Column(Modifier.fillMaxSize()) {
        CompactHeader("我")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Card(shape = RoundedCornerShape(20.dp), colors = cardColors) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { UserAvatar(userAvatarUri, onEditAvatar, 58.dp); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("我的头像", fontWeight = FontWeight.Bold); Text("点按头像可从相册更换", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = onEditAvatar) { Text("编辑") } } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors) { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { Text("用户名", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); OutlinedTextField(value = userName, onValueChange = onUserNameChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("所有角色将这样称呼你") }) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors) { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { Text("签名", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); OutlinedTextField(value = userSignature, onValueChange = onUserSignatureChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("显示在首页用户名下方") }, supportingText = { Text("最多 $MAX_USER_SIGNATURE_LENGTH 个字符") }) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGlobalPrompt)) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("全局计划规范", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("设置何时回应、回应目标和通用决策规则；不读取角色人格", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = IslandBlue) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenReplySplitter)) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("回复分段器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("${replySplitterSettings.mode.label} · 最多 ${replySplitterSettings.maxSegments} 条 · 每条 ${replySplitterSettings.minSegmentLength}-${replySplitterSettings.maxSegmentLength} 字", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = IslandBlue) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMcpServers)) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("外部 MCP 工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(if (mcpServers.isEmpty()) "添加可信任的 HTTP MCP 服务" else "${mcpServers.count { it.enabled }} 个已启用，${mcpServers.sumOf { it.tools.size }} 个工具", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = IslandBlue) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLogs)) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("开发日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("查看运行、模型、规划、回复及错误警告记录", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = IslandBlue) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("深色模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(if (darkMode) "已启用深色界面" else "已启用浅色界面", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Switch(checked = darkMode, onCheckedChange = onDarkModeChange) } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("系统权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(if (hasSystemPermissions) "日历、位置和通知已授权" else "允许读取日历、位置并发送通知", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = onRequestSystemPermissions, enabled = !hasSystemPermissions) { Text(if (hasSystemPermissions) "已授权" else "去授权") } } } }
            item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProviders)) { Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("模型提供商", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(activeProvider?.let { "当前：${it.name} · 已导入 ${it.models.size} 个模型" } ?: "添加、切换与导入模型列表", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = IslandBlue) } } }
            item { Text("模型配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
            if (activeProvider == null) {
                item { Card(shape = RoundedCornerShape(16.dp), colors = cardColors, modifier = Modifier.clickable(onClick = onOpenProviders)) { Column(Modifier.padding(16.dp)) { Text("还没有选择提供商", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text("请先进入“模型提供商”添加服务并导入模型。", color = IslandMuted, style = MaterialTheme.typography.bodySmall) } } }
            } else {
                item { Text("当前使用 ${activeProvider.name}；可从导入列表搜索，或手动验证模型名称。", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }
                items(ModelType.entries) { type -> ModelSelectionCard(type, selectedModels.modelFor(type), cardColors) { pickerType = type } }
            }
        }
    }
    pickerType?.let { type -> activeProvider?.let { provider -> ModelPickerDialog(type, provider, modelNameCheckState, modelConnectionTestState, onDismiss = { pickerType = null }, onValidateName = onValidateModelName, onTestConnectivity = onTestModelConnectivity, onSelect = { model -> onSelectModel(type, model); pickerType = null }) } }
}

@Composable
private fun ProviderManagementScreen(
    providers: List<ApiProvider>,
    activeProviderId: String?,
    connectionTestState: ConnectionTestState,
    onBack: () -> Unit,
    onAddProvider: (String, String, String) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onImportModels: (String) -> Unit
) {
    var showProviderForm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("模型提供商", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showProviderForm = true }) { Text("添加") }
        }
        HorizontalDivider(color = IslandBlue.copy(alpha = 0.16f))
        if (providers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有模型提供商", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("添加 OpenAI 兼容 API 后，可导入模型列表并在设置页配置用途。", color = IslandMuted)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { showProviderForm = true }) { Text("添加提供商") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(providers, key = { it.id }) { provider ->
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(provider.endpoint, color = IslandMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { onSelectProvider(provider.id) }) { Text(if (provider.id == activeProviderId) "当前使用" else "设为当前") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("已导入 ${provider.models.size} 个模型", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                            provider.importError?.let { Text("导入失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                            if (provider.isImporting) Text("正在导入模型列表…", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { onImportModels(provider.id) }, enabled = !provider.isImporting) { Text(if (provider.isImporting) "导入中" else "导入 / 刷新模型") }
                        }
                    }
                }
            }
        }
    }
    if (showProviderForm) ProviderDialog(
        connectionTestState = connectionTestState,
        onTestConnection = onTestConnection,
        onDismiss = { showProviderForm = false }
    ) { name, endpoint, apiKey ->
        onAddProvider(name, endpoint, apiKey)
        showProviderForm = false
    }
}

@Composable
private fun GlobalPromptScreen(sections: GlobalPromptSections, onBack: () -> Unit, onSave: (String, String, String) -> Unit) {
    var dialogueTask by remember(sections) { mutableStateOf(sections.dialogueTask) }
    var responseDecision by remember(sections) { mutableStateOf(sections.responseDecision) }
    var outputRules by remember(sections) { mutableStateOf(sections.outputRules) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("全局计划规范", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("计划器只使用以下通用规则来决定是否回应、回应目标和方式，不会读取任何角色的人格设定。", color = IslandMuted)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = dialogueTask,
            onValueChange = { dialogueTask = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("对话任务") },
            supportingText = { Text("让模型先理解聊天记录、当前话题、用户意图与情绪") },
            minLines = 4,
            maxLines = 8
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = responseDecision,
            onValueChange = { responseDecision = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("回应决策") },
            supportingText = { Text("规定如何选择回应重点、何时展开、何时保持克制") },
            minLines = 4,
            maxLines = 8
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = outputRules,
            onValueChange = { outputRules = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输出规则") },
            supportingText = { Text("规定语言、长度、格式与不应出现的表达") },
            minLines = 4,
            maxLines = 8
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                val defaults = defaultGlobalPromptSections()
                dialogueTask = defaults.dialogueTask
                responseDecision = defaults.responseDecision
                outputRules = defaults.outputRules
            }) { Text("恢复默认") }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onSave(dialogueTask, responseDecision, outputRules) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            enabled = dialogueTask.isNotBlank() && responseDecision.isNotBlank() && outputRules.isNotBlank()
        ) { Text("保存全局计划规范") }
    }
}

@Composable
private fun ReplySplitterSettingsScreen(settings: ReplySplitterSettings, onBack: () -> Unit, onSave: (ReplySplitterSettings) -> Unit) {
    var mode by remember(settings) { mutableStateOf(settings.mode) }
    var maxSegmentsText by remember(settings) { mutableStateOf(settings.maxSegments.toString()) }
    var minLengthText by remember(settings) { mutableStateOf(settings.minSegmentLength.toString()) }
    var maxLengthText by remember(settings) { mutableStateOf(settings.maxSegmentLength.toString()) }
    val maxSegments = maxSegmentsText.toIntOrNull()
    val minLength = minLengthText.toIntOrNull()
    val maxLength = maxLengthText.toIntOrNull()
    val isValid = maxSegments != null && minLength != null && maxLength != null &&
        maxSegments in 1..5 && minLength in 8..120 && maxLength in (minLength + 8)..220

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("回复分段器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("把一段角色回复按自然聊天节奏拆成多条消息。短回复不会被强行拆开；所有设置仅保存在本机。", color = IslandMuted)
        Spacer(Modifier.height(18.dp))
        Text("分段模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReplySplitMode.entries.forEach { option ->
                OutlinedButton(onClick = { mode = option }, modifier = Modifier.weight(1f)) {
                    Text(if (mode == option) "✓ ${option.label}" else option.label)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                ReplySplitMode.Length -> "字数分段：达到单条上限时，自动在完整句子或停顿处拆开。"
                ReplySplitMode.Scene -> "情景分段：优先尊重角色用空行标记的连续消息；过长时才自动拆开。"
            },
            color = IslandMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = maxSegmentsText,
            onValueChange = { maxSegmentsText = it.filter(Char::isDigit).take(1) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("分段上限") },
            supportingText = { Text("一次回复最多 1 到 5 条") }
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = minLengthText,
            onValueChange = { minLengthText = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("每段最少字数") },
            supportingText = { Text("8 到 120；无完整语义可合并时会优先保留意思完整") }
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = maxLengthText,
            onValueChange = { maxLengthText = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("每段最多字数") },
            supportingText = { Text("至少比最少字数多 8，最大 220") }
        )
        if (!isValid) {
            Text("请填写有效范围：分段上限 1-5，单段最少 8-120，最多字数须至少多 8。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = { onSave(ReplySplitterSettings(mode, maxSegments!!, minLength!!, maxLength!!)) },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) { Text("保存分段器设置") }
    }
}

private fun formatLogTimestamp(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable
private fun LogsScreen(logs: List<AppLogEntry>, isLoading: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("开发日志", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = IslandBlue.copy(alpha = 0.16f))
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在读取日志…", color = IslandMuted) }
        } else if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Text("进入此页面前 5 分钟内没有可显示的日志", color = IslandMuted)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("仅显示进入此页面前 5 分钟内的开发诊断日志", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                items(logs, key = { "${it.timestamp}-${it.message}" }) { entry ->
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                            Text(formatLogTimestamp(entry.timestamp), color = IslandMuted, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(12.dp))
                            val levelColor = when (entry.level) {
                                AppLogLevel.Info -> IslandBlue
                                AppLogLevel.Warning -> Color(0xFFE28A18)
                                AppLogLevel.Error -> MaterialTheme.colorScheme.error
                            }
                            Text("[${entry.level.label}]", color = levelColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text(entry.message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text("日志会保留最近 7 天且最多 2000 条，超出后自动清理", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp)) }
            }
        }
    }
}

@Composable
private fun McpServersScreen(servers: List<McpServer>, onBack: () -> Unit, onAdd: (String, String, String) -> Unit, onToggle: (String, Boolean) -> Unit, onImport: (String) -> Unit, onDelete: (String) -> Unit) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<McpServer?>(null) }
    Column(Modifier.fillMaxSize()) {
        CompactHeader("外部 MCP 工具") { TextButton(onClick = { showAddDialog = true }) { Text("添加服务器") } }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("仅添加你信任的 HTTP MCP 服务。启用后，规划器可在用户明确请求时调用其已导入工具；工具可能访问或修改外部数据。", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
            if (servers.isEmpty()) item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) { Text("还没有外部 MCP 服务器", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text("填写服务提供的 Streamable HTTP 地址，例如 https://example.com/mcp。", color = IslandMuted, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); Button(onClick = { showAddDialog = true }) { Text("添加服务器") } }
                }
            }
            items(servers, key = { it.id }) { server ->
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(server.endpoint, color = IslandMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Switch(checked = server.enabled, onCheckedChange = { onToggle(server.id, it) })
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (server.isImporting) "正在连接并导入工具…" else "已导入 ${server.tools.size} 个工具", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                        server.importError?.let { Text("连接失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                        if (server.tools.isNotEmpty()) Text(server.tools.take(4).joinToString(" · ") { it.name }, color = IslandMuted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { pendingDelete = server }) { Text("删除", color = MaterialTheme.colorScheme.error) }; OutlinedButton(onClick = { onImport(server.id) }, enabled = !server.isImporting) { Text("${if (server.tools.isEmpty()) "测试并导入" else "刷新工具"}") } }
                    }
                }
            }
        }
    }
    if (showAddDialog) McpServerDialog(onDismiss = { showAddDialog = false }) { name, endpoint, apiKey -> onAdd(name, endpoint, apiKey); showAddDialog = false }
    pendingDelete?.let { server -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("移除 MCP 服务器") }, text = { Text("确定移除“${server.name}”吗？应用将不再调用它的工具。") }, confirmButton = { TextButton(onClick = { onDelete(server.id); pendingDelete = null }) { Text("移除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
}

@Composable
private fun McpServerDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加 MCP 服务器") }, text = {
        Column {
            Text("支持 Streamable HTTP MCP。Android 版暂不支持需要在本机启动命令的 stdio 服务。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("服务器名称") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("MCP 地址") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Bearer 令牌（可选）") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, endpoint, apiKey) }, enabled = endpoint.startsWith("http")) { Text("保存并导入") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ProviderCard(provider: ApiProvider, canSwitch: Boolean, cardColors: CardColors, onSelectProvider: () -> Unit, onImport: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = cardColors) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(provider.name, fontWeight = FontWeight.SemiBold); Text(provider.endpoint, color = IslandMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (canSwitch) TextButton(onClick = onSelectProvider) { Text("切换") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onImport, enabled = !provider.isImporting) { Text(if (provider.isImporting) "正在导入…" else "导入模型") }
        }
    }
}

@Composable
private fun ModelSelectionCard(type: ModelType, selectedModel: String?, cardColors: CardColors, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = cardColors) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(type.label, fontWeight = FontWeight.SemiBold); Text(selectedModel ?: "尚未选择", color = IslandMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onClick) { Text(if (selectedModel == null) "选择" else "更换") }
        }
    }
}

private fun formatMemoryCardTime(timestamp: Long?): String = timestamp?.let {
    Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
} ?: "暂无"

/** Matches the low-contrast settings cards in both supported themes. */
@Composable
private fun memoryCardColors(): CardColors {
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val container = if (isDark) Color(0xFF17202E) else Color(0xFFF0F3F8)
    return CardDefaults.cardColors(containerColor = container)
}

private fun memoryEditNote(memory: LongTermMemory): String? = runCatching {
    JSONObject(memory.metadataJson).optString("last_edit_note").trim().ifBlank { null }
}.getOrNull()

@Composable
private fun MemoriesScreen(
    hasEmbeddingModel: Boolean,
    memories: List<LongTermMemory>,
    characters: List<Character>,
    userName: String,
    userAvatarUri: String?,
    latestChatAt: (String) -> Long?,
    onConfigureEmbedding: () -> Unit,
    onOpenScope: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        CompactHeader("记忆库")
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("按角色管理长期记忆", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("点进卡片查看和管理对应记忆。全局记忆可被所有角色检索。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (!hasEmbeddingModel) {
                item {
                    Card(shape = RoundedCornerShape(16.dp), colors = memoryCardColors()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("尚未配置嵌入模型", fontWeight = FontWeight.SemiBold)
                                Text("配置后可写入和检索记忆。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = onConfigureEmbedding) { Text("前往配置") }
                        }
                    }
                }
            }
            item {
                MemoryScopeCard(
                    name = userName,
                    relationship = "全局记忆 · 所有角色可调用",
                    avatarUri = userAvatarUri,
                    color = IslandBlue,
                    memoryTime = memories.filter { it.characterId == GLOBAL_MEMORY_SCOPE }.maxOfOrNull { it.createdAt },
                    chatTime = latestChatAt(GLOBAL_MEMORY_SCOPE),
                    onClick = { onOpenScope(GLOBAL_MEMORY_SCOPE) },
                    isUser = true
                )
            }
            items(characters, key = { it.id }) { character ->
                MemoryScopeCard(
                    name = character.name,
                    relationship = character.relationship,
                    avatarUri = character.avatarUri,
                    color = character.color,
                    memoryTime = memories.filter { it.characterId == character.id }.maxOfOrNull { it.createdAt },
                    chatTime = latestChatAt(character.id),
                    onClick = { onOpenScope(character.id) }
                )
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun MemoryScopeCard(
    name: String,
    relationship: String,
    avatarUri: String?,
    color: Color,
    memoryTime: Long?,
    chatTime: Long?,
    onClick: () -> Unit,
    isUser: Boolean = false
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = memoryCardColors(),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isUser) UserAvatar(avatarUri, onClick = null, size = 54.dp) else Avatar(name, color, 54.dp, avatarUri)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(relationship, color = IslandMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text("最近记忆：${formatMemoryCardTime(memoryTime)}", color = IslandMuted, style = MaterialTheme.typography.labelMedium)
                Text("最近聊天：${formatMemoryCardTime(chatTime)}", color = IslandMuted, style = MaterialTheme.typography.labelMedium)
            }
            Text("›", color = IslandBlue, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun MemoryDetailsScreen(
    scopeId: String,
    userName: String,
    userAvatarUri: String?,
    character: Character?,
    memories: List<LongTermMemory>,
    onBack: () -> Unit,
    onSaveGlobalMemory: (String) -> Unit,
    globalMemorySaveStatus: String?,
    onUpdateMemory: (String, String, Float, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onOpenVector: () -> Unit
) {
    val isGlobal = scopeId == GLOBAL_MEMORY_SCOPE
    var globalMemoryText by remember { mutableStateOf("") }
    var memoryQuery by remember { mutableStateOf("") }
    var dateQuery by remember { mutableStateOf("") }
    var actionMemoryId by remember { mutableStateOf<String?>(null) }
    var editingMemoryId by remember { mutableStateOf<String?>(null) }
    var editContent by remember { mutableStateOf("") }
    var editImportance by remember { mutableStateOf(0.55f) }
    var editNote by remember { mutableStateOf("") }
    val requestedDate = runCatching { LocalDate.parse(dateQuery.trim()) }.getOrNull()
    val visibleMemories = memories.filter { memory ->
        val contentMatches = memoryQuery.isBlank() || memory.content.contains(memoryQuery.trim(), ignoreCase = true)
        val dateMatches = dateQuery.isBlank() || requestedDate?.let {
            Instant.ofEpochMilli(memory.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == it
        } == true
        contentMatches && dateMatches
    }
    Column(Modifier.fillMaxSize()) {
        CompactHeader(if (isGlobal) "全局记忆" else character?.name ?: "角色记忆") {
            TextButton(onClick = onBack) { Text("‹ 返回") }
        }
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                if (isGlobal) {
                    Card(shape = RoundedCornerShape(18.dp), colors = memoryCardColors()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(userAvatarUri, onClick = null, size = 46.dp)
                                Spacer(Modifier.width(12.dp))
                                Column { Text(userName, fontWeight = FontWeight.SemiBold); Text("所有角色都可以调用这些记忆", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }
                            }
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(
                                value = globalMemoryText,
                                onValueChange = { globalMemoryText = it.take(1_800) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("添加全局记忆") },
                                placeholder = { Text("例如：我的称呼、长期偏好或共同约定") },
                                minLines = 3,
                                supportingText = { Text("最多 1800 字") }
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(onClick = { onSaveGlobalMemory(globalMemoryText); globalMemoryText = "" }, enabled = globalMemoryText.isNotBlank()) { Text("保存全局记忆") }
                            }
                            globalMemorySaveStatus?.let { Text(it, color = IslandMuted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else if (character != null) {
                    Card(shape = RoundedCornerShape(18.dp), colors = memoryCardColors()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(character.name, character.color, 50.dp, character.avatarUri)
                            Spacer(Modifier.width(12.dp))
                            Column { Text(character.name, fontWeight = FontWeight.SemiBold); Text(character.relationship, color = IslandMuted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("已保存 ${memories.size} 条长期记忆 · 长按卡片可修改或删除", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = memoryQuery,
                    onValueChange = { memoryQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("检索记忆内容") },
                    placeholder = { Text("输入记忆中的部分文字") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dateQuery,
                    onValueChange = { dateQuery = it.take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("按创建日期检索") },
                    placeholder = { Text("例如 2026-08-29") },
                    supportingText = { Text(if (dateQuery.isNotBlank() && requestedDate == null) "日期格式应为 yyyy-MM-dd" else "可与文字检索组合使用") }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onOpenVector, enabled = memories.any { it.embedding.isNotEmpty() }) { Text("打开向量窗口") }
                }
                Text("检索到 ${visibleMemories.size} 条记忆", color = IslandMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (visibleMemories.isEmpty()) {
                item {
                    Text(
                        if (memories.isEmpty()) {
                            if (isGlobal) "还没有全局记忆。可在上方手动添加。" else "还没有该角色的长期记忆。对话静默 3 分钟后会自动总结保存。"
                        } else "没有符合当前检索条件的记忆。",
                        color = IslandMuted,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(visibleMemories.sortedByDescending { it.createdAt }, key = { it.id }) { memory ->
                    val isEditing = editingMemoryId == memory.id
                    val isActionVisible = actionMemoryId == memory.id
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = memoryCardColors(),
                        modifier = Modifier.fillMaxWidth().pointerInput(memory.id) {
                            detectTapGestures(onLongPress = {
                                actionMemoryId = if (actionMemoryId == memory.id) null else memory.id
                                if (editingMemoryId != memory.id) editingMemoryId = null
                            })
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(memory.tier.label, color = IslandBlue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            if (isEditing) {
                                OutlinedTextField(
                                    value = editContent,
                                    onValueChange = { editContent = it.take(1_800) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("记忆内容") },
                                    minLines = 3,
                                    supportingText = { Text("最多 1800 字") }
                                )
                                Spacer(Modifier.height(10.dp))
                                Text("重要度 ${(editImportance * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                                Slider(value = editImportance, onValueChange = { editImportance = it }, valueRange = 0.05f..1f)
                                OutlinedTextField(
                                    value = editNote,
                                    onValueChange = { editNote = it.take(300) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("修改备注（可选）") },
                                    placeholder = { Text("例如：用户更正了日期") },
                                    minLines = 2,
                                    supportingText = { Text("最多 300 字") }
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { editingMemoryId = null; actionMemoryId = null }) { Text("取消") }
                                    Button(onClick = {
                                        onUpdateMemory(memory.id, editContent, editImportance, editNote)
                                        editingMemoryId = null
                                        actionMemoryId = null
                                    }, enabled = editContent.isNotBlank()) { Text("保存修改") }
                                }
                            } else {
                                Text(memory.content)
                                memoryEditNote(memory)?.let { note ->
                                    Spacer(Modifier.height(8.dp))
                                    Text("修改备注：$note", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("${formatMemoryCardTime(memory.createdAt)} · 重要度 ${(memory.importance * 100).roundToInt()}%", color = IslandMuted, style = MaterialTheme.typography.labelMedium)
                            }
                            if (isActionVisible && !isEditing) {
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        editContent = memory.content
                                        editImportance = memory.importance
                                        editNote = memoryEditNote(memory).orEmpty()
                                        editingMemoryId = memory.id
                                    }) { Text("修改") }
                                    TextButton(onClick = {
                                        onDeleteMemory(memory.id)
                                        actionMemoryId = null
                                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

private data class MemoryVectorNode(val memory: LongTermMemory, val position: Offset)
private data class MemoryVectorEdge(val from: MemoryVectorNode, val to: MemoryVectorNode, val similarity: Float)

/** A deterministic two-dimensional projection of saved embedding vectors for the local graph view. */
private fun projectMemoryVectors(memories: List<LongTermMemory>): List<MemoryVectorNode> {
    val vectorMemories = memories.filter { it.embedding.isNotEmpty() }
    if (vectorMemories.isEmpty()) return emptyList()
    val raw = vectorMemories.map { memory ->
        var x = 0f
        var y = 0f
        memory.embedding.forEachIndexed { index, value ->
            x += value * kotlin.math.cos((index + 1) * 0.173f).toFloat()
            y += value * kotlin.math.sin((index + 1) * 0.231f).toFloat()
        }
        Triple(memory, x, y)
    }
    val minX = raw.minOf { it.second }; val maxX = raw.maxOf { it.second }
    val minY = raw.minOf { it.third }; val maxY = raw.maxOf { it.third }
    return raw.mapIndexed { index, (memory, x, y) ->
        val normalizedX = if (maxX == minX) ((index % 5) - 2) / 5f else ((x - minX) / (maxX - minX) - 0.5f) * 1.65f
        val normalizedY = if (maxY == minY) ((index / 5) - 2) / 5f else ((y - minY) / (maxY - minY) - 0.5f) * 1.45f
        MemoryVectorNode(memory, Offset(normalizedX, normalizedY))
    }
}

private fun vectorSimilarity(left: List<Float>, right: List<Float>): Float {
    if (left.isEmpty() || right.isEmpty()) return 0f
    val count = minOf(left.size, right.size)
    var dot = 0.0; var leftNorm = 0.0; var rightNorm = 0.0
    repeat(count) { index -> dot += left[index] * right[index]; leftNorm += left[index] * left[index]; rightNorm += right[index] * right[index] }
    return if (leftNorm == 0.0 || rightNorm == 0.0) 0f else (dot / kotlin.math.sqrt(leftNorm * rightNorm)).toFloat()
}

private fun memoryVectorEdges(nodes: List<MemoryVectorNode>): List<MemoryVectorEdge> = buildList {
    nodes.forEachIndexed { index, node ->
        nodes.drop(index + 1).forEach { other ->
            val similarity = vectorSimilarity(node.memory.embedding, other.memory.embedding)
            if (similarity >= 0.42f) add(MemoryVectorEdge(node, other, similarity))
        }
    }
}.sortedByDescending { it.similarity }.take((nodes.size * 2).coerceAtMost(80))

@Composable
private fun MemoryVectorScreen(
    scopeId: String,
    title: String,
    memories: List<LongTermMemory>,
    onBack: () -> Unit,
    onUpdateMemory: (String, String, Float, String) -> Unit,
    onDeleteMemory: (String) -> Unit
) {
    val nodes = remember(memories) { projectMemoryVectors(memories) }
    val edges = remember(nodes) { memoryVectorEdges(nodes) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selectedMemoryId by remember { mutableStateOf<String?>(null) }
    val selectedMemory = memories.firstOrNull { it.id == selectedMemoryId }
    Column(Modifier.fillMaxSize()) {
        CompactHeader(title) { TextButton(onClick = onBack) { Text("‹ 返回") } }
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该范围内还没有可展示的向量记忆。", color = IslandMuted)
            }
        } else {
            Text(
                "线段表示向量相似度，球体大小表示重要度。双指缩放、单指拖动画布；放大后显示创建时间。",
                color = IslandMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val density = LocalDensity.current
                val vectorSurface = MaterialTheme.colorScheme.surface
                val vectorText = MaterialTheme.colorScheme.onSurface
                val vectorPrimary = MaterialTheme.colorScheme.primary
                val viewWidth = with(density) { maxWidth.toPx() }
                val viewHeight = with(density) { maxHeight.toPx() }
                val worldScale = minOf(viewWidth, viewHeight).coerceAtLeast(1f) * 0.42f
                fun drawPosition(node: MemoryVectorNode): Offset = Offset(
                    viewWidth / 2f + node.position.x * worldScale * zoom + pan.x,
                    viewHeight / 2f + node.position.y * worldScale * zoom + pan.y
                )
                fun nodeRadius(node: MemoryVectorNode): Float = ((14f + node.memory.importance * 22f) * kotlin.math.sqrt(zoom)).coerceIn(12f, 50f)
                Canvas(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .background(vectorSurface)
                        .pointerInput(nodes, zoom, pan, viewWidth, viewHeight) {
                            detectTapGestures(onTap = { tap ->
                                selectedMemoryId = nodes.lastOrNull { node -> (drawPosition(node) - tap).getDistance() <= nodeRadius(node) }?.memory?.id
                            })
                        }
                        .pointerInput(nodes) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(0.65f, 4f)
                                pan += panChange
                            }
                        }
                ) {
                    edges.forEach { edge ->
                        val opacity = ((edge.similarity - 0.40f) * 1.25f).coerceIn(0.12f, 0.5f)
                        drawLine(IslandBlue.copy(alpha = opacity), drawPosition(edge.from), drawPosition(edge.to), strokeWidth = 1.2f + edge.similarity * 1.8f)
                    }
                    nodes.forEach { node ->
                        val center = drawPosition(node)
                        val nodeColor = when (node.memory.tier) {
                            MemoryTier.Core -> IslandBlue
                            MemoryTier.Procedural -> Color(0xFF10A779)
                            MemoryTier.Learned -> Color(0xFF8D5CF6)
                            MemoryTier.Working -> Color(0xFFFFA42C)
                            MemoryTier.Episodic -> vectorPrimary
                        }
                        drawCircle(nodeColor.copy(alpha = 0.18f), nodeRadius(node) + 5f, center)
                        drawCircle(nodeColor, nodeRadius(node), center)
                        if (zoom >= 1.55f) {
                            val label = formatMemoryCardTime(node.memory.createdAt)
                            drawIntoCanvas { canvas ->
                                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = vectorText.toArgb()
                                    textSize = 11f * density.density
                                    textAlign = Paint.Align.CENTER
                                }
                                canvas.nativeCanvas.drawText(label, center.x, center.y - nodeRadius(node) - 10f, textPaint)
                            }
                        }
                    }
                }
                Text(
                    "缩放 ${(zoom * 100).roundToInt()}% · ${nodes.size} 条记忆 · ${edges.size} 条关联",
                    color = IslandMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                )
            }
        }
    }
    selectedMemory?.let { memory ->
        MemoryVectorEditDialog(
            memory = memory,
            onDismiss = { selectedMemoryId = null },
            onSave = { content, importance, note ->
                onUpdateMemory(memory.id, content, importance, note)
                selectedMemoryId = null
            },
            onDelete = {
                onDeleteMemory(memory.id)
                selectedMemoryId = null
            }
        )
    }
}

@Composable
private fun MemoryVectorEditDialog(
    memory: LongTermMemory,
    onDismiss: () -> Unit,
    onSave: (String, Float, String) -> Unit,
    onDelete: () -> Unit
) {
    var editMode by remember(memory.id) { mutableStateOf(false) }
    var content by remember(memory.id) { mutableStateOf(memory.content) }
    var importance by remember(memory.id) { mutableStateOf(memory.importance) }
    var note by remember(memory.id) { mutableStateOf(memoryEditNote(memory).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editMode) "编辑记忆" else memory.tier.label) },
        text = {
            Card(shape = RoundedCornerShape(16.dp), colors = memoryCardColors()) {
                Column(Modifier.padding(14.dp)) {
                    if (editMode) {
                        OutlinedTextField(content, { content = it.take(1_800) }, Modifier.fillMaxWidth(), label = { Text("记忆内容") }, minLines = 3)
                        Text("重要度 ${(importance * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                        Slider(importance, { importance = it }, valueRange = 0.05f..1f)
                        OutlinedTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("修改备注（可选）") }, minLines = 2)
                    } else {
                        Text(memory.content)
                        Spacer(Modifier.height(10.dp))
                        Text("${formatMemoryCardTime(memory.createdAt)} · 重要度 ${(memory.importance * 100).roundToInt()}%", color = IslandMuted, style = MaterialTheme.typography.labelMedium)
                        memoryEditNote(memory)?.let { Text("修改备注：$it", color = IslandMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (editMode) onSave(content, importance, note) else editMode = true
            }, enabled = !editMode || content.isNotBlank()) { Text(if (editMode) "保存" else "编辑") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun CreateCharacterScreen(onBack: () -> Unit, onSave: (String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var relationship by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("") }; var personality by remember { mutableStateOf("") }
    var behaviorStyle by remember { mutableStateOf("") }; var replyStyle by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("‹ 返回") }; Text("创建角色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(18.dp)); Text("将身份、性格、行为决策和表达方式分开填写，可让角色表现更稳定。全局计划规范请在“我”中设置。", color = IslandMuted); Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("角色名字") }, placeholder = { Text("例如：澜") }, singleLine = true); Spacer(Modifier.height(12.dp))
        OutlinedTextField(relationship, { relationship = it }, Modifier.fillMaxWidth(), label = { Text("你们的关系") }, placeholder = { Text("例如：安静的陪伴者") }, singleLine = true); Spacer(Modifier.height(12.dp))
        OutlinedTextField(identity, { identity = it }, Modifier.fillMaxWidth(), label = { Text("身份设定") }, placeholder = { Text("例如：一位安静的旅行摄影师") }, minLines = 2)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), label = { Text("性格设定") }, placeholder = { Text("例如：温柔、敏锐、不说教") }, minLines = 2)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(behaviorStyle, { behaviorStyle = it }, Modifier.fillMaxWidth(), label = { Text("行为方式") }, placeholder = { Text("例如：先观察情绪，需要时才给建议") }, minLines = 2)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(replyStyle, { replyStyle = it }, Modifier.fillMaxWidth(), label = { Text("表达方式") }, placeholder = { Text("例如：简短口语化，不用表情符号") }, minLines = 2)
        Spacer(Modifier.height(24.dp)); Button(onClick = { onSave(name, relationship, identity, personality, behaviorStyle, replyStyle) }, Modifier.fillMaxWidth().padding(bottom = 24.dp), enabled = name.isNotBlank()) { Text("创建并开始聊天") }
    }
}

@Composable
private fun EditCharacterScreen(character: Character, onBack: () -> Unit, onSave: (String, String, String, String, String, String) -> Unit) {
    var name by remember(character.id) { mutableStateOf(character.name) }
    var relationship by remember(character.id) { mutableStateOf(character.relationship) }
    var identity by remember(character.id) { mutableStateOf(character.identity) }
    var personality by remember(character.id) { mutableStateOf(character.trait) }
    var behaviorStyle by remember(character.id) { mutableStateOf(character.behaviorStyle) }
    var replyStyle by remember(character.id) { mutableStateOf(character.replyStyle) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("角色提示词", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("角色名字") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(relationship, { relationship = it }, Modifier.fillMaxWidth(), label = { Text("你们的关系") }, singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(identity, { identity = it }, Modifier.fillMaxWidth(), label = { Text("身份设定") }, supportingText = { Text("稳定的角色身份、背景和关系") }, minLines = 2, maxLines = 5)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(personality, { personality = it }, Modifier.fillMaxWidth(), label = { Text("性格设定") }, supportingText = { Text("稳定的性格、价值观和情感倾向") }, minLines = 2, maxLines = 5)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(behaviorStyle, { behaviorStyle = it }, Modifier.fillMaxWidth(), label = { Text("行为方式") }, supportingText = { Text("何时主动、如何判断话题与行动；不要在此写固定台词") }, minLines = 2, maxLines = 5)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(replyStyle, { replyStyle = it }, Modifier.fillMaxWidth(), label = { Text("表达方式") }, supportingText = { Text("语气、长度、互动习惯、禁用表达和格式") }, minLines = 2, maxLines = 5)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onSave(name, relationship, identity, personality, behaviorStyle, replyStyle) }, Modifier.fillMaxWidth().padding(bottom = 24.dp), enabled = name.isNotBlank() && personality.isNotBlank()) { Text("保存角色提示词") }
    }
}

@Composable
private fun CharacterSettingsScreen(character: Character, onBack: () -> Unit, onEditAvatar: () -> Unit, onEditPrompt: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CompactHeader("角色设置") { TextButton(onClick = onBack) { Text("完成") } }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(
                        character.name,
                        character.color,
                        64.dp,
                        character.avatarUri,
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onEditAvatar)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text("角色头像", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("会显示在聊天、聊天列表和通讯录中", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = onEditAvatar) { Text("更换") }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().clickable(onClick = onEditPrompt)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("角色提示词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("编辑身份、性格、行为方式和表达方式", color = IslandMuted, style = MaterialTheme.typography.bodySmall) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = IslandBlue) }
            }
        }
    }
}

@Composable
private fun AvatarCropScreen(sourceUri: String, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val cropView = remember(sourceUri) {
        AvatarCropView(context).apply {
            val bytes = context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { it.readBytes() } ?: byteArrayOf()
            if (bytes.isNotEmpty()) setImage(bytes)
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF101827))) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("取消", color = Color(0xFFAFCBFF)) }
            Text("裁剪角色头像", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { cropView.croppedBitmap()?.let(onSave) }) { Text("确定", color = Color(0xFFAFCBFF)) }
        }
        Text("拖动调整位置，双指缩放；圆形区域会保存为头像", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        AndroidView(factory = { cropView }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消选择") }
            Button(onClick = { cropView.croppedBitmap()?.let(onSave) }, modifier = Modifier.weight(1f)) { Text("确定使用") }
        }
    }
}

@Composable
private fun ProviderDialog(
    connectionTestState: ConnectionTestState,
    onTestConnection: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("https://api.example.com/v1") }
    var apiKey by remember { mutableStateOf("") }
    var showTemplatePicker by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("添加提供商", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showTemplatePicker = true }) { Text("常用模板") }
                }
                Text("支持 OpenAI 兼容 API。保存后会自动请求 /models 导入可选模型。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("提供商名称") }, placeholder = { Text("例如：OpenAI、硅基流动") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("API 地址") }, placeholder = { Text("https://.../v1") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API 密钥") }, placeholder = { Text("可留空，适用于无密钥服务") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                Text("API 密钥会使用 Android Keystore 加密后保存在本机数据库，不会明文写入聊天记录或日志。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                when (connectionTestState) {
                    ConnectionTestState.Idle -> Unit
                    ConnectionTestState.Testing -> Text("正在测试连通性并读取模型列表…", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                    is ConnectionTestState.Failure -> Text("测试失败：${connectionTestState.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is ConnectionTestState.Success -> {
                        Text("连接成功，读取到 ${connectionTestState.models.size} 个模型：", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        Text(connectionTestState.models.take(8).joinToString(" · "), color = IslandMuted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    OutlinedButton(onClick = { onTestConnection(endpoint, apiKey) }, enabled = connectionTestState !is ConnectionTestState.Testing) { Text("测试连通性") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(name, endpoint, apiKey) }) { Text("保存") }
                }
            }
        }
    }
    if (showTemplatePicker) ProviderTemplateDialog(onDismiss = { showTemplatePicker = false }) { template ->
        name = template.name
        endpoint = template.endpoint
        showTemplatePicker = false
    }
}

@Composable
private fun ProviderTemplateDialog(onDismiss: () -> Unit, onSelect: (ProviderTemplate) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("选择常用模板", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                commonProviderTemplates.forEach { template ->
                    TextButton(onClick = { onSelect(template) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(template.name, fontWeight = FontWeight.SemiBold)
                            Text(template.endpoint, style = MaterialTheme.typography.bodySmall, color = IslandMuted)
                            Text(template.description, style = MaterialTheme.typography.labelSmall, color = IslandMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderPickerDialog(providers: List<ApiProvider>, activeId: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("选择提供商", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                providers.forEach { provider ->
                    TextButton(onClick = { onSelect(provider.id) }, modifier = Modifier.fillMaxWidth()) { Text(if (provider.id == activeId) "${provider.name}（当前）" else provider.name, modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    type: ModelType,
    provider: ApiProvider,
    nameCheckState: ModelNameCheckState,
    connectionTestState: ModelConnectionTestState,
    onDismiss: () -> Unit,
    onValidateName: (String, String) -> Unit,
    onTestConnectivity: (String, String) -> Unit,
    onSelect: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var manualModelName by remember { mutableStateOf("") }
    val matchingModels = remember(provider.models, searchQuery) {
        provider.models.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val manualNameIsValid = nameCheckState is ModelNameCheckState.Valid && nameCheckState.model == manualModelName.trim()
    val manualConnectionSucceeded = connectionTestState is ModelConnectionTestState.Success && connectionTestState.model == manualModelName.trim()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Text("选择${type.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${provider.name} · 已导入 ${provider.models.size} 个模型", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth(), label = { Text("搜索已导入的模型") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                if (provider.models.isEmpty()) {
                    Text("该提供商尚未导入模型列表，可先到“模型提供商”页面导入。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(matchingModels, key = { it }) { model -> TextButton(onClick = { onSelect(model) }, modifier = Modifier.fillMaxWidth()) { Text(model, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                Text("手动添加模型", fontWeight = FontWeight.SemiBold)
                Text("适用于列表过长或服务商未完整返回模型列表的情况。先验证名称，再测试连通性。", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(manualModelName, { manualModelName = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, placeholder = { Text("例如：deepseek-chat") }, singleLine = true)
                when (nameCheckState) {
                    ModelNameCheckState.Idle -> Unit
                    ModelNameCheckState.Checking -> Text("正在验证模型名称…", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                    is ModelNameCheckState.Valid -> if (manualNameIsValid) Text("模型名称验证通过", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    is ModelNameCheckState.Invalid -> Text("名称验证失败：${nameCheckState.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                when (connectionTestState) {
                    ModelConnectionTestState.Idle -> Unit
                    ModelConnectionTestState.Testing -> Text("正在测试模型连通性…", color = IslandMuted, style = MaterialTheme.typography.bodySmall)
                    is ModelConnectionTestState.Success -> if (manualConnectionSucceeded) Text("模型连通性测试成功", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    is ModelConnectionTestState.Failure -> Text("连通性测试失败：${connectionTestState.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { onValidateName(provider.id, manualModelName) }, enabled = manualModelName.isNotBlank() && nameCheckState !is ModelNameCheckState.Checking) { Text("验证名称") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { onTestConnectivity(provider.id, manualModelName) }, enabled = manualNameIsValid && connectionTestState !is ModelConnectionTestState.Testing) { Text("测试连通性") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onSelect(manualModelName.trim()) }, enabled = manualConnectionSucceeded) { Text("使用此模型") }
                }
            }
        }
    }
}

@Composable
private fun UserAvatar(avatarUri: String?, onClick: (() -> Unit)?, size: Dp, modifier: Modifier = Modifier) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Box(modifier.size(size).clip(CircleShape).background(IslandBlue).then(clickModifier), contentAlignment = Alignment.Center) {
        if (avatarUri == null) Text("我", color = Color.White, fontWeight = FontWeight.Bold) else AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, update = { imageView -> imageView.setImageURI(Uri.parse(avatarUri)) }, modifier = Modifier.fillMaxSize())
    }
}

private fun contactInitial(name: String): Char {
    val first = name.trim().firstOrNull() ?: return '#'
    if (first.isLetter() && first.code < 128) return first.uppercaseChar()
    val bytes = runCatching { first.toString().toByteArray(Charset.forName("GBK")) }.getOrDefault(byteArrayOf())
    if (bytes.size < 2) return '#'
    val code = (bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)
    return pinyinInitialBoundaries.lastOrNull { code >= it.first }?.second ?: '#'
}

private val pinyinInitialBoundaries = listOf(
    45217 to 'A', 45253 to 'B', 45761 to 'C', 46318 to 'D', 46826 to 'E', 47010 to 'F',
    47297 to 'G', 47614 to 'H', 48119 to 'J', 49062 to 'K', 49324 to 'L', 49896 to 'M',
    50371 to 'N', 50614 to 'O', 50622 to 'P', 50906 to 'Q', 51387 to 'R', 51446 to 'S',
    52218 to 'T', 52698 to 'W', 52980 to 'X', 53689 to 'Y', 54481 to 'Z'
)

/** The home avatar is a playful, non-navigation control. Edit it from the "我" tab instead. */
@Composable
private fun ShakingUserAvatar(avatarUri: String?, size: Dp, modifier: Modifier = Modifier) {
    var shakeCount by remember { mutableStateOf(0) }
    val horizontalOffset = remember { Animatable(0f) }
    LaunchedEffect(shakeCount) {
        if (shakeCount == 0) return@LaunchedEffect
        horizontalOffset.snapTo(0f)
        horizontalOffset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 360
                -6f at 55
                6f at 110
                -5f at 165
                5f at 220
                -2f at 285
            }
        )
    }
    UserAvatar(
        avatarUri = avatarUri,
        onClick = { shakeCount += 1 },
        size = size,
        modifier = modifier.offset(x = horizontalOffset.value.dp)
    )
}

@Composable
private fun Avatar(name: String, color: Color, size: Dp, avatarUri: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (avatarUri == null) Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        else AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, update = { imageView -> imageView.setImageURI(Uri.parse(avatarUri)) }, modifier = Modifier.fillMaxSize())
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHuankongyu() { HuankongyuApp() }
