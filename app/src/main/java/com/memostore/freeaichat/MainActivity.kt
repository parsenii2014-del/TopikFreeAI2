package com.memostore.freeaichat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─── Colors ─────────────────────────────────────────────────────────────────
private val Bg = Color(0xFF121211)
private val Panel = Color(0xFF1E1E1B)
private val Panel2 = Color(0xFF2A2A26)
private val Accent = Color(0xFFE8B88A)
private val Fg = Color(0xFFF5F3EE)
private val Muted = Color(0xFF9A9690)
private val UserBubble = Color(0xFF3D322B)
private val ErrorRed = Color(0xFFFF8A8A)
private val CodeBg = Color(0xFF161614)

// ─── Data ───────────────────────────────────────────────────────────────────
private data class Msg(val role: String, val text: String, val id: Long = System.currentTimeMillis())

private data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<Msg>,
    val updated: Long = System.currentTimeMillis()
)

private val Specialties = listOf(
    "Универсальный эксперт", "Программирование", "Наука и исследования",
    "Обучение", "Копирайтинг", "Бизнес", "Финансы",
    "Дизайн и творчество", "Аналитика", "Маркетинг"
)

private val Modes = listOf("Чат", "Code", "Agents", "Tools", "Специализации")

private val ToolsList = listOf(
    "Анализ файлов", "Поиск в интернете", "Работа с кодом",
    "Математика и вычисления", "Структурирование данных", "Планирование задач"
)

// ─── Serialization helpers ──────────────────────────────────────────────────
private fun messagesToJson(msgs: List<Msg>): String {
    val arr = JSONArray()
    msgs.takeLast(80).forEach { m ->
        arr.put(JSONObject().put("role", m.role).put("text", m.text).put("id", m.id))
    }
    return arr.toString()
}

private fun messagesFromJson(raw: String?): List<Msg> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role")
                val text = o.optString("text")
                if (role.isNotBlank() && text.isNotBlank())
                    add(Msg(role, text, o.optLong("id", System.currentTimeMillis())))
            }
        }
    } catch (_: Exception) { emptyList() }
}

private fun sessionsToJson(sessions: List<ChatSession>): String {
    val arr = JSONArray()
    sessions.take(30).forEach { s ->
        val msgs = JSONArray()
        s.messages.takeLast(60).forEach { m ->
            msgs.put(JSONObject().put("role", m.role).put("text", m.text).put("id", m.id))
        }
        arr.put(
            JSONObject()
                .put("id", s.id)
                .put("title", s.title)
                .put("updated", s.updated)
                .put("messages", msgs)
        )
    }
    return arr.toString()
}

private fun sessionsFromJson(raw: String?): List<ChatSession> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val title = o.optString("title", "Чат")
                val updated = o.optLong("updated", System.currentTimeMillis())
                val msgs = messagesFromJson(o.optJSONArray("messages")?.toString())
                if (id.isNotBlank()) add(ChatSession(id, title, msgs, updated))
            }
        }.sortedByDescending { it.updated }
    } catch (_: Exception) { emptyList() }
}

// ─── Activity ───────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, background = Bg, surface = Panel,
                    onBackground = Fg, onSurface = Fg, onPrimary = Color.Black
                )
            ) { FreeAiApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeAiApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val prefs = remember {
        val master = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "freeai_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── State ──
    var baseUrl by remember { mutableStateOf(prefs.getString("url", "http://127.0.0.1:3001/v1") ?: "http://127.0.0.1:3001/v1") }
    var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "auto") ?: "auto") }
    var models by remember { mutableStateOf(listOf("auto", "auto:fast", "auto:smart", "auto:reliable")) }
    var mode by remember { mutableStateOf("Чат") }
    var selectedSpecs by remember {
        mutableStateOf(prefs.getStringSet("specs", setOf("Универсальный эксперт"))?.toSet() ?: setOf("Универсальный эксперт"))
    }
    var enabledTools by remember {
        mutableStateOf(prefs.getStringSet("tools", setOf("Анализ файлов", "Поиск в интернете"))?.toSet()
            ?: setOf("Анализ файлов", "Поиск в интернете"))
    }

    var sessions by remember { mutableStateOf(sessionsFromJson(prefs.getString("sessions", null))) }
    var currentSessionId by remember { mutableStateOf(prefs.getString("current_session", null)) }
    var messages by remember {
        mutableStateOf(
            sessions.find { it.id == currentSessionId }?.messages
                ?: messagesFromJson(prefs.getString("chat_history", null))
        )
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var showChats by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    fun saveAll() {
        val sid = currentSessionId ?: java.util.UUID.randomUUID().toString().also { currentSessionId = it }
        val title = messages.firstOrNull { it.role == "user" }?.text?.take(40)?.replace("\n", " ") ?: "Новый чат"
        val updated = sessions.filter { it.id != sid } + ChatSession(sid, title, messages)
        sessions = updated.sortedByDescending { it.updated }
        prefs.edit()
            .putString("url", baseUrl)
            .putString("key", apiKey)
            .putString("model", model)
            .putStringSet("specs", selectedSpecs)
            .putStringSet("tools", enabledTools)
            .putString("sessions", sessionsToJson(sessions))
            .putString("current_session", sid)
            .putString("chat_history", messagesToJson(messages))
            .apply()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
            saveAll()
        }
    }

    LaunchedEffect(Unit) {
        if (apiKey.isNotBlank()) {
            scope.launch {
                try {
                    val found = withContext(Dispatchers.IO) {
                        val req = Request.Builder()
                            .url(baseUrl.trimEnd('/') + "/models")
                            .header("Authorization", "Bearer $apiKey")
                            .build()
                        client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) return@withContext null
                            val data = JSONObject(r.body?.string() ?: "{}").optJSONArray("data") ?: JSONArray()
                            buildList {
                                addAll(listOf("auto", "auto:fast", "auto:smart", "auto:reliable"))
                                for (i in 0 until data.length()) {
                                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                            }.distinct()
                        }
                    }
                    if (found != null) models = found
                } catch (_: Exception) {}
            }
        }
    }

    fun newChat() {
        currentSessionId = java.util.UUID.randomUUID().toString()
        messages = emptyList()
        error = ""
        saveAll()
    }

    fun loadSession(s: ChatSession) {
        currentSessionId = s.id
        messages = s.messages
        showChats = false
        error = ""
    }

    fun send() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        if (apiKey.isBlank()) {
            settings = true
            error = "Добавь API-ключ FreeLLMAPI в настройках"
            return
        }
        input = ""
        error = ""
        keyboard?.hide()

        val userMessages = messages + Msg("user", prompt)
        messages = userMessages + Msg("assistant", "Думаю…")
        busy = true

        val modeKey = when (mode) {
            "Code" -> "code"
            "Agents" -> "agents"
            "Tools" -> "tools"
            else -> "chat"
        }

        val specText = selectedSpecs.joinToString(", ")
        val toolText = enabledTools.joinToString(", ")

        // Client-side system is still useful as fallback; backend also has strong prompts
        val localSystem = buildString {
            append("Ты FreeAI. Режим: $mode. Специализации: $specText. Инструменты: $toolText. ")
            when (mode) {
                "Code" -> append("Ты элитный engineer. Пиши рабочий код, находи корневые причины багов, не выдумывай результаты.")
                "Agents" -> append("Ты агент. Планируй, выполняй шаги, используй инструменты, давай итоговый результат.")
                "Tools" -> append("Используй поиск и анализ. Не выдумывай данные.")
                else -> append("Будь точным, полезным и честным.")
            }
            append(" Отвечай на языке пользователя.")
        }

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val arr = JSONArray()
                    arr.put(JSONObject().put("role", "system").put("content", localSystem))
                    userMessages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.text)) }

                    val body = JSONObject()
                        .put("model", model)
                        .put("messages", arr)
                        .put("stream", false)
                        .put("mode", modeKey)
                        .put("enable_tools", true)

                    val req = Request.Builder()
                        .url(baseUrl.trimEnd('/') + "/chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(req).execute().use { r ->
                        val raw = r.body?.string() ?: ""
                        if (!r.isSuccessful) throw Exception("HTTP ${r.code}: ${raw.take(400)}")
                        val root = JSONObject(raw)
                        root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content")
                            ?.takeIf { it.isNotBlank() }
                            ?: raw
                    }
                }
                messages = userMessages + Msg("assistant", result)
            } catch (e: Exception) {
                messages = userMessages
                error = "Ошибка: ${e.message?.take(220) ?: "неизвестная"}"
            } finally {
                busy = false
            }
        }
    }

    fun copyText(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FreeAI", text))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }

    // ── UI ──
    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FreeAI", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            when (mode) {
                                "Code" -> "Элитный code assistant"
                                "Agents" -> "Автономный агент"
                                "Tools" -> "Инструменты + поиск"
                                else -> "Твой ИИ-пространство"
                            },
                            color = Muted, fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showChats = true }) {
                        Icon(Icons.Default.List, contentDescription = "Чаты")
                    }
                    IconButton(onClick = { newChat() }) {
                        Icon(Icons.Default.Add, contentDescription = "Новый чат")
                    }
                    IconButton(onClick = { settings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Modes.forEach { item ->
                    val icon: ImageVector = when (item) {
                        "Чат" -> Icons.Default.ChatBubbleOutline
                        "Code" -> Icons.Default.Code
                        "Agents" -> Icons.Default.AutoAwesome
                        "Tools" -> Icons.Default.Build
                        else -> Icons.Default.Tune
                    }
                    NavigationBarItem(
                        selected = mode == item,
                        onClick = { mode = item },
                        icon = { Icon(icon, contentDescription = item) },
                        label = { Text(item, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Accent,
                            selectedTextColor = Accent,
                            indicatorColor = Panel2,
                            unselectedTextColor = Muted,
                            unselectedIconColor = Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp)
        ) {
            // Model row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(
                        onClick = { modelMenu = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Dns, null, Modifier.size(15.dp), tint = Accent)
                        Spacer(Modifier.width(5.dp))
                        Text(model, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.take(60).forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text(m, color = if (m == model) Accent else Fg,
                                        fontWeight = if (m == model) FontWeight.SemiBold else FontWeight.Normal)
                                },
                                onClick = {
                                    model = m
                                    prefs.edit().putString("model", m).apply()
                                    modelMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(6.dp))
                    Text("Думаю…", fontSize = 11.sp, color = Muted)
                } else {
                    Text("${messages.size} сообщ.", fontSize = 11.sp, color = Muted)
                }
            }

            when (mode) {
                "Специализации" -> SpecsScreen(
                    selected = selectedSpecs,
                    onChange = {
                        selectedSpecs = it
                        prefs.edit().putStringSet("specs", it).apply()
                    },
                    modifier = Modifier.weight(1f)
                )
                "Tools" -> ToolsScreen(
                    enabled = enabledTools,
                    onChange = {
                        enabledTools = it
                        prefs.edit().putStringSet("tools", it).apply()
                    },
                    modifier = Modifier.weight(1f)
                )
                else -> {
                    if (messages.isEmpty()) {
                        EmptyState(
                            mode = mode,
                            specs = selectedSpecs,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            items(messages, key = { it.id }) { m ->
                                MessageBubble(m, onCopy = ::copyText)
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = error.isNotBlank()) {
                Text(
                    error,
                    color = ErrorRed,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { error = "" }
                )
            }

            if (mode != "Специализации" && mode != "Tools") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Panel, RoundedCornerShape(22.dp))
                        .padding(5.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when (mode) {
                                    "Code" -> "Код, ошибка, архитектура…"
                                    "Agents" -> "Сложная задача для агента…"
                                    else -> "Сообщение…"
                                },
                                color = Muted
                            )
                        },
                        maxLines = 7,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Accent
                        )
                    )
                    IconButton(
                        onClick = { send() },
                        enabled = !busy && input.isNotBlank(),
                        modifier = Modifier
                            .background(if (!busy && input.isNotBlank()) Accent else Panel2, CircleShape)
                            .size(42.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Отправить",
                            tint = if (!busy && input.isNotBlank()) Color.Black else Muted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    // Settings
    if (settings) {
        var urlDraft by remember { mutableStateOf(baseUrl) }
        var keyDraft by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { settings = false },
            containerColor = Panel,
            title = { Text("Подключение FreeAI Router", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("API URL", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        urlDraft, { urlDraft = it }, singleLine = true,
                        placeholder = { Text("http://127.0.0.1:3001/v1") },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
                    )
                    Text("API-ключ", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(
                        keyDraft, { keyDraft = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
                    )
                    Text(
                        "Ключ и история шифруются на устройстве. Backend даёт агентов, поиск и память.",
                        color = Muted, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    baseUrl = urlDraft.trim().trimEnd('/')
                    apiKey = keyDraft.trim()
                    saveAll()
                    settings = false
                }) { Text("Сохранить", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { settings = false }) { Text("Отмена", color = Muted) }
            }
        )
    }

    // Chat list
    if (showChats) {
        AlertDialog(
            onDismissRequest = { showChats = false },
            containerColor = Panel,
            title = { Text("История чатов", fontWeight = FontWeight.SemiBold) },
            text = {
                if (sessions.isEmpty()) {
                    Text("Пока нет сохранённых чатов", color = Muted)
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(sessions) { s ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { loadSession(s) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text("${s.messages.size} сообщений", fontSize = 11.sp, color = Muted)
                                }
                                IconButton(onClick = {
                                    sessions = sessions.filter { it.id != s.id }
                                    if (currentSessionId == s.id) newChat()
                                    saveAll()
                                }) {
                                    Icon(Icons.Default.Delete, null, tint = Muted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChats = false }) { Text("Закрыть", color = Accent) }
            }
        )
    }
}

// ─── Sub-screens ────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(mode: String, specs: Set<String>, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                when (mode) {
                    "Code" -> Icons.Default.Code
                    "Agents" -> Icons.Default.AutoAwesome
                    else -> Icons.Default.Psychology
                },
                null, tint = Accent, modifier = Modifier.size(52.dp)
            )
            Text(
                when (mode) {
                    "Code" -> "Код. Баги. Архитектура."
                    "Agents" -> "Сложная задача → агент"
                    else -> "Чем займёмся?"
                },
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold
            )
            Text("Режим: $mode · ${specs.take(2).joinToString()}", color = Muted, fontSize = 12.sp)
            if (mode == "Agents") {
                Text("Агент сам разобьёт задачу, может искать в интернете и соберёт результат.", color = Muted, fontSize = 12.sp)
            }
            if (mode == "Code") {
                Text("Пишу рабочий код, нахожу корневые причины, предлагаю тесты.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: Msg, onCopy: (String) -> Unit) {
    val isUser = msg.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) UserBubble else Panel,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 740.dp)
                .pointerInput(msg.text) {
                    detectTapGestures(onLongPress = { onCopy(msg.text) })
                }
        ) {
            // Very simple code-block awareness
            if (!isUser && msg.text.contains("```")) {
                CodeAwareText(msg.text)
            } else {
                SelectionContainer {
                    Text(msg.text, Modifier.padding(13.dp), fontSize = 14.sp, lineHeight = 21.sp, color = Fg)
                }
            }
        }
    }
}

@Composable
private fun CodeAwareText(text: String) {
    Column(Modifier.padding(12.dp)) {
        val parts = text.split("```")
        parts.forEachIndexed { idx, part ->
            if (idx % 2 == 1) {
                // code block
                val lines = part.lines()
                val lang = lines.firstOrNull()?.trim()?.takeIf { it.length < 20 } ?: ""
                val code = if (lang.isNotEmpty()) lines.drop(1).joinToString("\n") else part
                Surface(
                    color = CodeBg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    SelectionContainer {
                        Text(
                            code.trim(),
                            Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = Fg
                        )
                    }
                }
            } else if (part.isNotBlank()) {
                SelectionContainer {
                    Text(part.trim(), fontSize = 14.sp, lineHeight = 21.sp, color = Fg)
                }
            }
        }
    }
}

@Composable
private fun SpecsScreen(selected: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Text("Экспертиза", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        Text("Выбранные направления усиливают системный промпт.", color = Muted, fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(Specialties) { s ->
                val checked = s in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (checked) Panel2 else Panel)
                        .clickable { onChange(if (checked) selected - s else selected + s) }
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Checkbox(checked, {
                        onChange(if (it) selected + s else selected - s)
                    }, colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Color.Black))
                }
            }
        }
    }
}

@Composable
private fun ToolsScreen(enabled: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Text("Инструменты", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        Text("Backend умеет реальный web-search. Остальное усиливает промпт.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        ToolsList.forEach { t ->
            val checked = t in enabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (checked) Panel2 else Panel)
                    .clickable { onChange(if (checked) enabled - t else enabled + t) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(t, Modifier.weight(1f))
                Checkbox(checked, {
                    onChange(if (it) enabled + t else enabled - t)
                }, colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Color.Black))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
