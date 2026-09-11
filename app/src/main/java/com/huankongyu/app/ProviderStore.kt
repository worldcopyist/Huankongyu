package com.huankongyu.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

data class PersistedProviderConfiguration(
    val providers: List<ApiProvider>,
    val activeProviderId: String?,
    val selectedModels: SelectedModels,
    val themeMode: ThemeMode,
    val userName: String,
    val userSignature: String,
    val globalChatPrompt: String,
    val replySplitterSettings: ReplySplitterSettings,
    val userAvatarUri: String?,
    val mcpServers: List<McpServer>,
    val characters: List<Character>,
    val hasSavedCharacterState: Boolean,
    val messages: List<PersistedChatMessage>,
    val memories: List<LongTermMemory>,
    val memoryCheckpoints: Map<String, Long>
)

data class PersistedChatMessage(val characterId: String, val message: ChatMessage)

/** Local SQLite storage for provider setup. API keys are encrypted with Android Keystore before storage. */
class ProviderStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {
    private val keyCipher = ApiKeyCipher()

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE providers (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                encrypted_api_key TEXT NOT NULL,
                models_json TEXT NOT NULL,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE provider_settings (
                settings_id INTEGER PRIMARY KEY CHECK (settings_id = 1),
                active_provider_id TEXT,
                chat_model TEXT,
                embedding_model TEXT,
                vision_model TEXT,
                voice_model TEXT
            )
            """.trimIndent()
        )
        createAppStateTables(database)
        createMcpServerTable(database)
        createMemoryTables(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAppStateTables(database)
            createMcpServerTable(database)
        } else {
            if (oldVersion < 3) {
                database.execSQL("ALTER TABLE app_settings ADD COLUMN avatar_uri TEXT")
            }
            if (oldVersion < 4) {
                database.execSQL("ALTER TABLE app_settings ADD COLUMN characters_initialized INTEGER NOT NULL DEFAULT 0")
                createCharacterTable(database)
            }
            if (oldVersion < 5) {
                database.execSQL("ALTER TABLE app_settings ADD COLUMN user_name TEXT NOT NULL DEFAULT '我'")
            }
            if (oldVersion < 6) {
                database.execSQL("ALTER TABLE app_settings ADD COLUMN user_signature TEXT NOT NULL DEFAULT '你的 AI 陪伴空间'")
            }
            if (oldVersion in 2..7) {
                database.execSQL("ALTER TABLE app_settings ADD COLUMN global_chat_prompt TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion in 4..6) {
                database.execSQL("ALTER TABLE characters ADD COLUMN identity TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE characters ADD COLUMN behavior_style TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE characters ADD COLUMN reply_style TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 9) {
                createLogTable(database)
            }
            if (oldVersion == 9) {
                database.execSQL("ALTER TABLE app_logs ADD COLUMN level TEXT NOT NULL DEFAULT 'Info'")
            }
            if (oldVersion < 11) {
                database.execSQL("ALTER TABLE app_settings ADD COLUMN reply_splitter_settings TEXT NOT NULL DEFAULT ''")
            }
            if (oldVersion < 12) createMcpServerTable(database)
            if (oldVersion < 13) database.execSQL("ALTER TABLE characters ADD COLUMN avatar_uri TEXT")
        }
        if (oldVersion < 14) {
            if (oldVersion >= 2) database.execSQL("ALTER TABLE chat_messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            createMemoryTables(database)
        }
        // Version 14 already has the old memory table; older databases receive the
        // latest schema directly through createMemoryTables above.
        if (oldVersion == 14) {
            database.execSQL("ALTER TABLE long_term_memories ADD COLUMN memory_tier TEXT NOT NULL DEFAULT 'episodic'")
            database.execSQL("ALTER TABLE long_term_memories ADD COLUMN importance REAL NOT NULL DEFAULT 0.55")
            database.execSQL("ALTER TABLE long_term_memories ADD COLUMN valid_until INTEGER")
            database.execSQL("ALTER TABLE long_term_memories ADD COLUMN supersedes_id TEXT")
            database.execSQL("ALTER TABLE long_term_memories ADD COLUMN metadata_json TEXT NOT NULL DEFAULT '{}'")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_long_term_memories_scope_tier_created ON long_term_memories(character_id, memory_tier, created_at DESC)")
        }
    }

    fun load(): PersistedProviderConfiguration {
        val providerList = mutableListOf<ApiProvider>()
        readableDatabase.query(
            "providers",
            arrayOf("id", "name", "endpoint", "encrypted_api_key", "models_json"),
            null, null, null, null, "sort_order ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val models = runCatching {
                    val array = JSONArray(cursor.getString(4))
                    List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
                providerList += ApiProvider(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    endpoint = cursor.getString(2),
                    apiKey = keyCipher.decrypt(cursor.getString(3)),
                    models = models
                )
            }
        }
        var activeProviderId: String? = null
        var selectedModels = SelectedModels()
        readableDatabase.query(
            "provider_settings",
            arrayOf("active_provider_id", "chat_model", "embedding_model", "vision_model", "voice_model"),
            "settings_id = 1", null, null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                activeProviderId = cursor.getStringOrNull(0)
                selectedModels = SelectedModels(
                    chat = cursor.getStringOrNull(1),
                    embedding = cursor.getStringOrNull(2),
                    vision = cursor.getStringOrNull(3),
                    voice = cursor.getStringOrNull(4)
                )
            }
        }
        var themeMode = ThemeMode.Dark
        var userName = "我"
        var userSignature = DEFAULT_USER_SIGNATURE
        var globalChatPrompt = COMMON_CHAT_PROMPT
        var replySplitterSettings = ReplySplitterSettings()
        var userAvatarUri: String? = null
        var hasSavedCharacterState = false
        readableDatabase.query(
            "app_settings", arrayOf("theme_mode", "user_name", "user_signature", "avatar_uri", "characters_initialized", "global_chat_prompt", "reply_splitter_settings"), "settings_id = 1", null, null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                themeMode = runCatching { ThemeMode.valueOf(cursor.getString(0)) }.getOrDefault(ThemeMode.Dark)
                userName = cursor.getStringOrNull(1)?.trim().orEmpty().ifBlank { "我" }
                userSignature = cursor.getStringOrNull(2)?.take(MAX_USER_SIGNATURE_LENGTH) ?: DEFAULT_USER_SIGNATURE
                userAvatarUri = cursor.getStringOrNull(3)
                hasSavedCharacterState = cursor.getInt(4) == 1
                globalChatPrompt = cursor.getStringOrNull(5)?.trim().orEmpty().ifBlank { COMMON_CHAT_PROMPT }
                replySplitterSettings = ReplySplitterSettings.fromStorageValue(cursor.getStringOrNull(6))
            }
        }
        val mcpServers = mutableListOf<McpServer>()
        readableDatabase.query("mcp_servers", arrayOf("id", "name", "endpoint", "encrypted_api_key", "enabled", "tools_json"), null, null, null, null, "sort_order ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val serverId = cursor.getString(0)
                val tools = runCatching {
                    val array = JSONArray(cursor.getString(5))
                    List(array.length()) { index -> array.optJSONObject(index) }.mapNotNull { tool ->
                        tool?.optString("name")?.takeIf { it.isNotBlank() }?.let { name -> McpTool(serverId, name, tool.optString("description"), tool.optJSONObject("inputSchema")?.toString() ?: "{}") }
                    }
                }.getOrDefault(emptyList())
                mcpServers += McpServer(serverId, cursor.getString(1), cursor.getString(2), keyCipher.decrypt(cursor.getString(3)), cursor.getInt(4) == 1, tools)
            }
        }
        val characters = mutableListOf<Character>()
        readableDatabase.query(
            "characters", arrayOf("id", "name", "relationship", "trait", "identity", "behavior_style", "reply_style", "avatar_uri", "color_value", "preview", "message_time", "pinned"),
            null, null, null, null, "sort_order ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                characters += Character(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    relationship = cursor.getString(2),
                    trait = cursor.getString(3),
                    color = androidx.compose.ui.graphics.Color(cursor.getInt(8)),
                    preview = cursor.getString(9),
                    time = cursor.getString(10),
                    pinned = cursor.getInt(11) == 1,
                    identity = cursor.getString(4),
                    behaviorStyle = cursor.getString(5),
                    replyStyle = cursor.getString(6),
                    avatarUri = cursor.getStringOrNull(7)
                )
            }
        }
        val messages = mutableListOf<PersistedChatMessage>()
        readableDatabase.query(
            "chat_messages", arrayOf("id", "character_id", "from_user", "content", "message_time", "created_at"),
            null, null, null, null, "sort_order ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages += PersistedChatMessage(
                    characterId = cursor.getString(1),
                    message = ChatMessage(
                        id = cursor.getString(0),
                        fromUser = cursor.getInt(2) == 1,
                        content = cursor.getString(3),
                        time = cursor.getString(4),
                        createdAt = cursor.getLong(5)
                    )
                )
            }
        }
        val memories = mutableListOf<LongTermMemory>()
        readableDatabase.query(
            "long_term_memories", arrayOf("id", "character_id", "content", "embedding_json", "created_at", "source_start_at", "source_end_at", "memory_tier", "importance", "valid_until", "supersedes_id", "metadata_json"),
            null, null, null, null, "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val vector = runCatching {
                    val array = JSONArray(cursor.getString(3))
                    List(array.length()) { index -> array.optDouble(index).toFloat() }
                }.getOrDefault(emptyList())
                if (vector.isNotEmpty()) memories += LongTermMemory(
                    id = cursor.getString(0), characterId = cursor.getString(1), content = cursor.getString(2), embedding = vector,
                    createdAt = cursor.getLong(4), sourceStartAt = cursor.getLong(5), sourceEndAt = cursor.getLong(6),
                    tier = MemoryTier.fromStorageValue(cursor.getStringOrNull(7)),
                    importance = cursor.getFloat(8),
                    validUntil = cursor.getLong(9).takeIf { !cursor.isNull(9) },
                    supersedesId = cursor.getStringOrNull(10),
                    metadataJson = cursor.getStringOrNull(11) ?: "{}"
                )
            }
        }
        val checkpoints = mutableMapOf<String, Long>()
        readableDatabase.query("memory_checkpoints", arrayOf("character_id", "summarized_until"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) checkpoints[cursor.getString(0)] = cursor.getLong(1)
        }
        return PersistedProviderConfiguration(providerList, activeProviderId, selectedModels, themeMode, userName, userSignature, globalChatPrompt, replySplitterSettings, userAvatarUri, mcpServers, characters, hasSavedCharacterState, messages, memories, checkpoints)
    }

    fun save(providers: List<ApiProvider>, activeProviderId: String?, selectedModels: SelectedModels) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("providers", null, null)
            providers.forEachIndexed { index, provider ->
                writableDatabase.insertOrThrow("providers", null, ContentValues().apply {
                    put("id", provider.id)
                    put("name", provider.name)
                    put("endpoint", provider.endpoint)
                    put("encrypted_api_key", keyCipher.encrypt(provider.apiKey))
                    put("models_json", JSONArray(provider.models).toString())
                    put("sort_order", index)
                })
            }
            writableDatabase.insertWithOnConflict("provider_settings", null, ContentValues().apply {
                put("settings_id", 1)
                put("active_provider_id", activeProviderId)
                put("chat_model", selectedModels.chat)
                put("embedding_model", selectedModels.embedding)
                put("vision_model", selectedModels.vision)
                put("voice_model", selectedModels.voice)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveMcpServers(servers: List<McpServer>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("mcp_servers", null, null)
            servers.forEachIndexed { index, server ->
                val tools = JSONArray().apply { server.tools.forEach { tool -> put(org.json.JSONObject().apply { put("name", tool.name); put("description", tool.description); put("inputSchema", runCatching { org.json.JSONObject(tool.inputSchemaJson) }.getOrDefault(org.json.JSONObject())) }) } }
                writableDatabase.insertOrThrow("mcp_servers", null, ContentValues().apply {
                    put("id", server.id); put("name", server.name); put("endpoint", server.endpoint); put("encrypted_api_key", keyCipher.encrypt(server.apiKey)); put("enabled", if (server.enabled) 1 else 0); put("tools_json", tools.toString()); put("sort_order", index)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun saveThemeMode(themeMode: ThemeMode) {
        saveAppSetting(ContentValues().apply { put("theme_mode", themeMode.name) })
    }

    fun saveUserName(userName: String) {
        saveAppSetting(ContentValues().apply { put("user_name", userName) })
    }

    fun saveUserSignature(userSignature: String) {
        saveAppSetting(ContentValues().apply { put("user_signature", userSignature.take(MAX_USER_SIGNATURE_LENGTH)) })
    }

    fun saveGlobalChatPrompt(globalChatPrompt: String) {
        saveAppSetting(ContentValues().apply { put("global_chat_prompt", globalChatPrompt.take(6_000)) })
    }

    fun saveReplySplitterSettings(settings: ReplySplitterSettings) {
        saveAppSetting(ContentValues().apply { put("reply_splitter_settings", settings.toStorageValue()) })
    }

    fun saveAvatarUri(avatarUri: String?) {
        saveAppSetting(ContentValues().apply { put("avatar_uri", avatarUri) })
    }

    fun saveCharacters(characters: List<Character>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("characters", null, null)
            characters.forEachIndexed { index, character ->
                writableDatabase.insertOrThrow("characters", null, ContentValues().apply {
                    put("id", character.id)
                    put("name", character.name)
                    put("relationship", character.relationship)
                    put("trait", character.trait)
                    put("identity", character.identity)
                    put("behavior_style", character.behaviorStyle)
                    put("reply_style", character.replyStyle)
                    put("avatar_uri", character.avatarUri)
                    put("color_value", character.color.toArgb())
                    put("preview", character.preview)
                    put("message_time", character.time)
                    put("pinned", if (character.pinned) 1 else 0)
                    put("sort_order", index)
                })
            }
            saveAppSetting(ContentValues().apply { put("characters_initialized", 1) })
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveMessages(messages: List<PersistedChatMessage>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("chat_messages", null, null)
            messages.forEachIndexed { index, persisted ->
                writableDatabase.insertOrThrow("chat_messages", null, ContentValues().apply {
                    put("id", persisted.message.id)
                    put("character_id", persisted.characterId)
                    put("from_user", if (persisted.message.fromUser) 1 else 0)
                    put("content", persisted.message.content)
                    put("message_time", persisted.message.time)
                    put("created_at", persisted.message.createdAt)
                    put("sort_order", index)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveMemory(memory: LongTermMemory, summarizedUntil: Long) = saveMemories(listOf(memory), summarizedUntil)

    /** Writes extracted memory documents and their source checkpoint as one transaction. */
    fun saveMemories(memories: List<LongTermMemory>, summarizedUntil: Long) {
        if (memories.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            memories.forEach { memory ->
                writableDatabase.insertOrThrow("long_term_memories", null, ContentValues().apply {
                    put("id", memory.id); put("character_id", memory.characterId); put("content", memory.content)
                    put("embedding_json", JSONArray(memory.embedding).toString()); put("created_at", memory.createdAt)
                    put("source_start_at", memory.sourceStartAt); put("source_end_at", memory.sourceEndAt)
                    put("memory_tier", memory.tier.storageValue); put("importance", memory.importance)
                    put("valid_until", memory.validUntil); put("supersedes_id", memory.supersedesId); put("metadata_json", memory.metadataJson)
                })
            }
            writableDatabase.insertWithOnConflict("memory_checkpoints", null, ContentValues().apply {
                put("character_id", memories.first().characterId); put("summarized_until", summarizedUntil)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            // Keep the local vector store bounded without discarding a whole character's recent history.
            writableDatabase.execSQL("DELETE FROM long_term_memories WHERE id NOT IN (SELECT id FROM long_term_memories ORDER BY created_at DESC LIMIT 600)")
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun saveMemoryCheckpoint(characterId: String, summarizedUntil: Long) {
        writableDatabase.insertWithOnConflict("memory_checkpoints", null, ContentValues().apply {
            put("character_id", characterId); put("summarized_until", summarizedUntil)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteMemory(memoryId: String): Boolean =
        writableDatabase.delete("long_term_memories", "id = ?", arrayOf(memoryId)) > 0

    /** Updates a memory in place so its ID and audit/source range remain stable. */
    fun updateMemory(memory: LongTermMemory): Boolean =
        writableDatabase.update("long_term_memories", ContentValues().apply {
            put("content", memory.content)
            put("embedding_json", JSONArray(memory.embedding).toString())
            put("memory_tier", memory.tier.storageValue)
            put("importance", memory.importance)
            put("valid_until", memory.validUntil)
            put("supersedes_id", memory.supersedesId)
            put("metadata_json", memory.metadataJson)
        }, "id = ?", arrayOf(memory.id)) > 0

    fun deleteMemoriesForCharacter(characterId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("long_term_memories", "character_id = ?", arrayOf(characterId))
            writableDatabase.delete("memory_checkpoints", "character_id = ?", arrayOf(characterId))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun clearAllMemories() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("long_term_memories", null, null)
            writableDatabase.delete("memory_checkpoints", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun addLog(level: AppLogLevel, message: String, timestamp: Long = System.currentTimeMillis()) {
        writableDatabase.insertOrThrow("app_logs", null, ContentValues().apply {
            put("created_at", timestamp)
            put("level", level.name)
            put("message", message.take(300))
        })
        val expiry = timestamp - 7L * 24 * 60 * 60 * 1000
        writableDatabase.delete("app_logs", "created_at < ?", arrayOf(expiry.toString()))
        writableDatabase.execSQL("DELETE FROM app_logs WHERE log_id NOT IN (SELECT log_id FROM app_logs ORDER BY log_id DESC LIMIT 2000)")
    }

    fun loadLogs(startInclusive: Long, endInclusive: Long): List<AppLogEntry> = buildList {
        readableDatabase.query(
            "app_logs", arrayOf("created_at", "level", "message"), "created_at BETWEEN ? AND ?",
            arrayOf(startInclusive.toString(), endInclusive.toString()), null, null, "created_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) add(AppLogEntry(
                timestamp = cursor.getLong(0),
                level = runCatching { AppLogLevel.valueOf(cursor.getString(1)) }.getOrDefault(AppLogLevel.Info),
                message = cursor.getString(2)
            ))
        }
    }

    private fun createAppStateTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                settings_id INTEGER PRIMARY KEY CHECK (settings_id = 1),
                theme_mode TEXT NOT NULL,
                user_name TEXT NOT NULL DEFAULT '我',
                user_signature TEXT NOT NULL DEFAULT '你的 AI 陪伴空间',
                global_chat_prompt TEXT NOT NULL DEFAULT '',
                reply_splitter_settings TEXT NOT NULL DEFAULT '',
                avatar_uri TEXT,
                characters_initialized INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id TEXT PRIMARY KEY NOT NULL,
                character_id TEXT NOT NULL,
                from_user INTEGER NOT NULL,
                content TEXT NOT NULL,
                message_time TEXT NOT NULL,
                created_at INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createCharacterTable(database)
        createLogTable(database)
    }

    private fun createMcpServerTable(database: SQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS mcp_servers (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                endpoint TEXT NOT NULL,
                encrypted_api_key TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                tools_json TEXT NOT NULL DEFAULT '[]',
                sort_order INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun createMemoryTables(database: SQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS long_term_memories (
                id TEXT PRIMARY KEY NOT NULL,
                character_id TEXT NOT NULL,
                content TEXT NOT NULL,
                embedding_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                source_start_at INTEGER NOT NULL,
                source_end_at INTEGER NOT NULL,
                memory_tier TEXT NOT NULL DEFAULT 'episodic',
                importance REAL NOT NULL DEFAULT 0.55,
                valid_until INTEGER,
                supersedes_id TEXT,
                metadata_json TEXT NOT NULL DEFAULT '{}'
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_long_term_memories_character_created ON long_term_memories(character_id, created_at DESC)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_long_term_memories_scope_tier_created ON long_term_memories(character_id, memory_tier, created_at DESC)")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS memory_checkpoints (
                character_id TEXT PRIMARY KEY NOT NULL,
                summarized_until INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun createCharacterTable(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS characters (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                relationship TEXT NOT NULL,
                trait TEXT NOT NULL,
                identity TEXT NOT NULL DEFAULT '',
                behavior_style TEXT NOT NULL DEFAULT '',
                reply_style TEXT NOT NULL DEFAULT '',
                avatar_uri TEXT,
                color_value INTEGER NOT NULL,
                preview TEXT NOT NULL,
                message_time TEXT NOT NULL,
                pinned INTEGER NOT NULL,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createLogTable(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_logs (
                log_id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                level TEXT NOT NULL DEFAULT 'Info',
                message TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun saveAppSetting(values: ContentValues) {
        if (writableDatabase.update("app_settings", values, "settings_id = 1", null) == 0) {
            values.put("settings_id", 1)
            if (!values.containsKey("theme_mode")) values.put("theme_mode", ThemeMode.Dark.name)
            writableDatabase.insertOrThrow("app_settings", null, values)
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private companion object {
        const val DATABASE_NAME = "huankongyu.db"
        const val DATABASE_VERSION = 15
    }
}

private class ApiKeyCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packet = ByteBuffer.allocate(cipher.iv.size + encrypted.size).put(cipher.iv).put(encrypted).array()
        return Base64.encodeToString(packet, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val packet = Base64.decode(value, Base64.NO_WRAP)
            val iv = packet.copyOfRange(0, GCM_IV_SIZE)
            val encrypted = packet.copyOfRange(GCM_IV_SIZE, packet.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_SIZE, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "huankongyu_provider_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_SIZE = 128
    }
}
