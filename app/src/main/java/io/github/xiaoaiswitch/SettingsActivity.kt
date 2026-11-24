package io.github.xiaoaiswitch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaoaiswitch.core.SettingsConfig
import io.github.xiaoaiswitch.core.CodexImages
import io.github.xiaoaiswitch.core.AiProviders
import io.github.xiaoaiswitch.core.ModelClient
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { SettingsScreen() } }
    }
}

private val supportedRoutes = listOf(0, 4, 2)
private val routeTitles = mapOf(
    0 to "文本对话",
    2 to "绘图",
    4 to "识图"
)
private val routeDescriptions = mapOf(
    0 to "日常问答与多轮对话，回复显示在小布原生对话卡片中",
    2 to "文生图与原图编辑，生成结果回填到小布原生对话卡片中",
    4 to "在小布中发送图片或带图提问时，调用自定义多模态识图模型"
)
private val routeSwitchSummaries = mapOf(
    0 to "接管小布纯文本问答",
    2 to "接管小布绘图与原图编辑",
    4 to "接管小布图片输入与识图问答"
)
private val providerNames = listOf("API Key / OpenAI 兼容源", "Codex 订阅")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val assistant = remember(context) { runCatching { context.packageManager.getPackageInfo("com.heytap.speechassist", 0) }.getOrNull() }
    val preferences = remember { context.getSharedPreferences("switch", 0) }
    val initial = remember { runCatching { AiProviders.normalize(JSONObject(preferences.getString("config", "{}") ?: "{}")).toString() }.getOrDefault("{}") }
    var draft by rememberSaveable { mutableStateOf(initial) }
    var saved by rememberSaveable { mutableStateOf(initial) }
    var selected by rememberSaveable { mutableStateOf(0) }
    var page by rememberSaveable { mutableStateOf("home") }
    val config = remember(draft) { JSONObject(draft) }
    val thinkingSelected = false
    val normalChannel = config.optJSONObject(SettingsConfig.ROUTES[selected]) ?: JSONObject()
    val channel = if (thinkingSelected) normalChannel.optJSONObject("thinking") ?: JSONObject() else normalChannel
    val providers = remember(draft) { AiProviders.entries(config) }
    val selectedProvider = providers.firstOrNull { it.id() == channel.optString("providerId") }
    val subscription = selectedProvider?.provider() == "codex_subscription"
    val focus = LocalFocusManager.current
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var modelRoute by remember { mutableStateOf(0) }
    var modelThinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val feedback = remember { SnackbarHostState() }
    var feedbackError by remember { mutableStateOf(false) }
    var providerReturn by rememberSaveable { mutableStateOf("home") }
    var editingProvider by rememberSaveable { mutableStateOf("") }
    var providerDraft by rememberSaveable { mutableStateOf("{}") }
    var deleteProvider by remember { mutableStateOf(false) }
    var hook by remember { mutableStateOf(preferences.getString("status", "等待 LSPosed 加载") ?: "") }
    var seen by remember { mutableStateOf(preferences.getLong("seen", 0)) }
    var activeVersion by remember { mutableStateOf(preferences.getInt("activeVersion", 0)) }
    val active = activeVersion == BuildConfig.VERSION_CODE
    fun parent(screen: String) = when (screen) {
        "home" -> null
        "provider_edit" -> "providers"
        "providers" -> providerReturn
        else -> "home"
    }
    fun back() {
        val previous = parent(page) ?: return
        focus.clearFocus()
        page = previous
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key ->
            when (key) {
                "status" -> hook = store.getString("status", "等待 LSPosed 加载") ?: ""
                "seen" -> seen = store.getLong("seen", 0)
                "activeVersion" -> activeVersion = store.getInt("activeVersion", 0)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun updateChannel(index: Int, key: String, value: Any, mode: Boolean = thinkingSelected) {
        val next = JSONObject(draft)
        val name = SettingsConfig.ROUTES[index]
        val normal = next.optJSONObject(name) ?: JSONObject()
        if (index < 2 && mode) normal.put("thinking", (normal.optJSONObject("thinking") ?: JSONObject()).put(key, value))
        else normal.put(key, value)
        next.put(name, normal)
        draft = next.toString()
        message = null
    }

    fun notifySave(text: String, error: Boolean) {
        feedbackError = error
        scope.launch {
            feedback.newestSnackbarData()?.dismiss()
            feedback.showSnackbar(text, actionLabel = "知道了", duration = if (error) SnackbarDuration.Long else SnackbarDuration.Custom(6000))
        }
    }

    fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            notifySave("没有可打开此链接的应用", true)
        }
    }

    fun save() {
        focus.clearFocus()
        try {
            val result = SettingsConfig.save(JSONObject(draft), System.currentTimeMillis()).toString()
            preferences.edit().putString("config", result).apply()
            draft = result
            saved = result
            failed = false
            message = "已保存，设置立即生效"
            notifySave("✓ 设置已保存，已立即应用", false)
        } catch (error: IllegalArgumentException) {
            failed = true
            message = error.message
            notifySave("保存失败：${error.message}", true)
        }
    }

    fun setMaster(enabled: Boolean) {
        val next = JSONObject(saved).put("enabled", enabled).put("revision", System.currentTimeMillis())
        preferences.edit().putString("config", next.toString()).apply()
        saved = next.toString()
        draft = JSONObject(draft).put("enabled", enabled).put("revision", next.getLong("revision")).toString()
        failed = false
        message = if (enabled) "模块接管已开启" else "模块接管已关闭"
    }

    fun editProvider(id: String) {
        editingProvider = id
        providerDraft = providers.firstOrNull { it.id() == id }?.json()?.toString()
            ?: JSONObject().put("name", "").put("provider", "openai_compatible").toString()
        message = null
        page = "provider_edit"
    }

    fun saveProvider(removing: Boolean = false) {
        focus.clearFocus()
        try {
            val nextSaved = if (removing) AiProviders.remove(JSONObject(saved), editingProvider)
                else AiProviders.put(JSONObject(saved), editingProvider, JSONObject(providerDraft))
            val nextDraft = if (removing) AiProviders.remove(JSONObject(draft), editingProvider)
                else AiProviders.put(JSONObject(draft), editingProvider, JSONObject(providerDraft))
            val revision = System.currentTimeMillis()
            saved = nextSaved.put("revision", revision).toString()
            draft = nextDraft.put("revision", revision).toString()
            preferences.edit().putString("config", saved).apply()
            failed = false
            message = if (removing) "Provider 已删除" else "Provider 已保存，引用它的配置已同步"
            page = "providers"
            deleteProvider = false
            notifySave("✓ ${message}", false)
        } catch (error: IllegalArgumentException) {
            failed = true
            message = error.message
            notifySave("保存失败：${error.message}", true)
        }
    }

    val global: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Section("适配范围") {
                Text("当前版本针对 ColorOS 16.0.9.400（CN01）和小布 13.4.3（130403）适配。支持文本问答、多轮上下文、图片输入/识图以及文生图与原图编辑（支持自然语言自动调用绘图或 /draw 指令），结果使用小布原生对话卡片与图片卡片渲染。手机控制和语音播报暂未接入。",
                    fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    val editor: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Section("模型配置") {
                Text(routeDescriptions[selected].orEmpty(), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                SwitchRow(if (thinkingSelected) "单独配置思考模式" else "使用自定义服务",
                    if (thinkingSelected) "关闭后沿用默认配置" else routeSwitchSummaries[selected] ?: "接管该通道", channel.optBoolean("enabled"), "channel_switch") {
                    updateChannel(selected, "enabled", it)
                }
                ChoiceRow("AI Provider", selectedProvider?.name() ?: "选择已添加的 Provider", "provider_selector") {
                    if (providers.isEmpty()) { providerReturn = "channel"; page = "providers" }
                    else chooser = "provider"
                }
                TextButton("管理 AI Provider", { providerReturn = "channel"; page = "providers" }, modifier = Modifier.fillMaxWidth().testTag("manage_providers"))
                Field(if (selected == 2) "绘图模型名称" else "模型名称", channel.optString("model"), "model_field") { updateChannel(selected, "model", it) }
                if (subscription && selected == 2) {
                    TextButton("使用默认绘图模型 ${CodexImages.DEFAULT_MODEL}", { updateChannel(selected, "model", CodexImages.DEFAULT_MODEL) },
                        modifier = Modifier.fillMaxWidth().testTag("codex_image_model"))
                }
                if (selectedProvider != null && !(subscription && selected == 2)) {
                    TextButton(if (loading) "正在获取模型…" else "从 Provider 选择模型", {
                        modelRoute = selected
                        modelThinking = thinkingSelected
                        loading = true
                        scope.launch {
                            try {
                                models = withContext(Dispatchers.IO) {
                                    if (selectedProvider.provider() == "codex_subscription") {
                                        val items = CodexAccounts.get(context).models().getJSONArray("models")
                                        (0 until items.length()).map { items.getJSONObject(it).optString("slug") }.filter { it.isNotBlank() }.distinct()
                                    } else ModelClient.models(selectedProvider.transport())
                                }
                                if (models.isEmpty()) throw IllegalStateException("Provider 未返回可用模型")
                                chooser = "models"
                            } catch (error: Exception) {
                                message = "模型列表：${error.message}"
                                failed = true
                            } finally { loading = false }
                        }
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("select_model"))
                }
                Field(if (selected == 2) "绘图 Prompt 前缀" else "System Prompt", channel.optString("systemPrompt"), "prompt_field", prompt = true) {
                    updateChannel(selected, "systemPrompt", it)
                }
                Text(when (selected) {
                    2 -> if (subscription) "手机直连 Codex 原生绘图，使用订阅额度；支持文生图与原图编辑，无需另填 API Key。"
                        else "图片 API 使用 prompt 字段；修改原图时自动请求 images/edits。"
                    else -> if (subscription) "系统指令映射到 Codex instructions；登录凭据仅保存在手机。" else "作为独立的 system 消息发送，不拼接到用户问题。"
                }, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    SettingsMotion(page, ::parent, chooser == null && !deleteProvider, ::back) { screen ->
        Scaffold(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            snackbarHost = { if (screen == page) SaveFeedbackHost(feedback, feedbackError) },
            topBar = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (screen == "home") {
                    TopAppBar(title = appName, largeTitle = appName,
                        modifier = Modifier.widthIn(max = 768.dp).fillMaxWidth(), actions = {
                        if (draft != saved) TextButton("保存", { save() }, modifier = Modifier.testTag("home_save"))
                    })
                } else {
                    SmallTopAppBar(title = when (screen) { "channel" -> routeTitles[selected] ?: "模型配置"; "general" -> "适配范围"; "providers" -> "AI Provider";
                        "provider_edit" -> if (providers.any { it.id() == editingProvider }) "编辑 Provider" else "添加 Provider"; else -> "运行状态" },
                        modifier = Modifier.widthIn(max = 768.dp).fillMaxWidth(),
                        navigationIcon = { BackButton("settings_back", ::back) })
                }
                }
            },
            bottomBar = {
                if (screen == "channel" || screen == "provider_edit") {
                    Box(modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).imePadding(), contentAlignment = Alignment.Center) {
                        Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message ?: if (draft != saved) "有未保存的更改" else "配置已同步", fontSize = 12.sp,
                                color = if (message != null && failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.testTag("save_status"))
                            if (screen == "provider_edit") PrimaryButton("保存 Provider", "save_provider") { saveProvider() }
                            else PrimaryButton(if (!failed && draft == saved && message != null) "✓ 已保存" else "保存并立即应用", "save_settings") { save() }
                        }
                    }
                }
            }) { padding ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 840.dp
                Column(modifier = Modifier.fillMaxSize().testTag(if (wide) "wide_layout" else "compact_layout")
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                        .testTag(when (screen) { "home" -> "home_screen"; "general" -> "general_screen"; "channel" -> "channel_screen";
                            "providers" -> "providers_screen"; "provider_edit" -> "provider_editor"; else -> "status_screen" }),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        when (screen) {
                            "home" -> {
                                ActivationCard(active, BuildConfig.VERSION_NAME) { page = "status" }
                                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                                    SwitchRow("模块接管", "立即生效，关闭后恢复原厂服务", config.optBoolean("enabled"), "master_switch") { setMaster(it) }
                                }
                                if (message != null) Text(message.orEmpty(), fontSize = 13.sp,
                                    color = if (failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.testTag("save_status"))
                                Section("模型配置") {
                                    supportedRoutes.forEach { index ->
                                        val name = routeTitles[index] ?: SettingsConfig.ROUTES[index]
                                        val profile = config.optJSONObject(SettingsConfig.ROUTES[index]) ?: JSONObject()
                                        val source = providers.firstOrNull { it.id() == profile.optString("providerId") }?.name() ?: "未选择 Provider"
                                        val thought = profile.optJSONObject("thinking")
                                        val summary = if (index < 2 && thought?.optBoolean("enabled") == true)
                                            "默认 ${if (profile.optBoolean("enabled")) profile.optString("model") else "原厂"} · 思考 ${thought.optString("model") }"
                                            else if (profile.optBoolean("enabled")) "$source · ${profile.optString("model")}" else "未启用"
                                        ChoiceRow(name, summary, "menu_${SettingsConfig.ROUTES[index]}") {
                                            selected = index; page = "channel"; message = null
                                        }
                                    }
                                }
                                Section("设置") {
                                    ChoiceRow("适配范围", "当前支持的 ColorOS 与小布版本", "menu_general") { page = "general"; message = null }
                                    ChoiceRow("AI Provider", "${providers.size} 个 · 统一管理服务与登录", "menu_providers") {
                                        providerReturn = "home"; page = "providers"; message = null
                                    }
                                }
                                Section("项目与致谢") {
                                    ChoiceRow("项目仓库", "hekinya/XiaoBuSwitch", "project_repository") {
                                        openLink("https://github.com/hekinya/XiaoBuSwitch")
                                    }
                                    ChoiceRow("上游项目", "基于 SeimoDev/XiaoAiSwitch v0.10.12 (GPL-3.0)", "project_upstream") {
                                        openLink("https://github.com/SeimoDev/XiaoAiSwitch")
                                    }
                                }
                            }
                            "general" -> global()
                            "channel" -> editor()
                            "providers" -> {
                                PrimaryButton("添加 AI Provider", "add_provider") { editProvider(UUID.randomUUID().toString()) }
                                if (providers.isNotEmpty()) Section("已添加") {
                                    providers.forEach { provider ->
                                        ChoiceRow(provider.name(), if (provider.provider() == "codex_subscription") "Codex 订阅 · 手机直连" else "OpenAI 兼容 API",
                                            "provider_${provider.id()}") { editProvider(provider.id()) }
                                    }
                                }
                            }
                            "provider_edit" -> {
                                ProviderEditor(JSONObject(providerDraft), { key, value ->
                                    providerDraft = JSONObject(providerDraft).put(key, value).toString()
                                    message = null
                                }, { chooser = "provider_kind" })
                                if (providers.any { it.id() == editingProvider }) {
                                    val used = AiProviders.used(config, editingProvider) || AiProviders.used(JSONObject(saved), editingProvider)
                                    TextButton("删除 Provider", { deleteProvider = true }, enabled = !used,
                                        modifier = Modifier.fillMaxWidth().testTag("delete_provider"))
                                    if (used) Text("已被模型配置引用，切换这些配置后可删除。", fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                            "status" -> Section("Hook 状态") {
                                Text("模块版本 · ${BuildConfig.VERSION_NAME}", modifier = Modifier.testTag("diagnostic_version"))
                                Text("安装的小布 · ${assistant?.versionName ?: "未安装"}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                SelectionContainer { Text(hook, fontSize = 14.sp, modifier = Modifier.testTag("hook_status")) }
                                if (seen != 0L) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seen)),
                                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                TextButton("刷新状态", {
                                    hook = preferences.getString("status", "等待 LSPosed 加载") ?: ""
                                    seen = preferences.getLong("seen", 0)
                                    activeVersion = preferences.getInt("activeVersion", 0)
                                }, modifier = Modifier.fillMaxWidth().testTag("refresh_status"))
                            }
                        }
                    }
                }
            }
        }
    }
    when (chooser) {
        "provider" -> ChoiceDialog("选择 AI Provider", providers.map { it.name() }, selected = selectedProvider?.name(),
            onDismiss = { chooser = null }) {
                val provider = providers.first { provider -> provider.name() == it }
                updateChannel(selected, "providerId", provider.id())
                if (selected == 2 && provider.provider() == "codex_subscription" && channel.optString("model").isBlank()) updateChannel(selected, "model", CodexImages.DEFAULT_MODEL)
            }
        "provider_kind" -> ChoiceDialog("Provider 类型", providerNames,
            selected = providerNames[if (JSONObject(providerDraft).optString("provider") == "codex_subscription") 1 else 0],
            onDismiss = { chooser = null }) {
                providerDraft = JSONObject(providerDraft).put("provider", if (it == providerNames[1]) "codex_subscription" else "openai_compatible").toString()
            }
        "models" -> ChoiceDialog("选择模型", models, selected = channel.optString("model"),
            onDismiss = { chooser = null }) { updateChannel(modelRoute, "model", it, modelThinking) }
    }
    if (deleteProvider) WindowDialog(show = true, title = "删除 Provider？", summary = "未启用的引用会清除，模型名称和提示词保留。",
        modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { deleteProvider = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("删除", "confirm_delete_provider") { saveProvider(true) }
            TextButton("取消", { deleteProvider = false }, modifier = Modifier.fillMaxWidth().testTag("cancel_delete_provider"))
        }
    }
}
