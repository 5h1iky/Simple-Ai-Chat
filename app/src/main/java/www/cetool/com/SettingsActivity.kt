package www.cetool.com

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import www.cetool.com.manager.WorldInfoEngine
import www.cetool.com.model.ApiConfig
import www.cetool.com.model.Conversation
import www.cetool.com.ui.theme.SAChatTheme
import java.io.ByteArrayOutputStream

/**
 * 设置页（Compose 版）
 * 分组卡片布局：API 配置 / 免费模型 / 字体大小 / 提示词管理 / 联网搜索 / 背景图片 / 聊天昵称。
 * 业务逻辑与旧版一致（仅 UI 层迁移，SharedPreferences 字段名不变）。
 */
class SettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    private val gson = Gson()

    // ─── UI 状态（Compose 可观察） ───
    private val apiEntries = mutableStateListOf<SettingsKeys.ApiEntry>()
    private var activeApiIndex by mutableIntStateOf(0)

    // Free Gateway: 预设服务商
    private var providerMode by mutableStateOf(SettingsKeys.PROVIDER_CUSTOM)
    private var kiloModelOption by mutableStateOf(SettingsKeys.KILO_MODEL)
    private var zenModelOption by mutableStateOf(SettingsKeys.ZEN_MODEL_DEFAULT)
    private var manualEntriesSnapshot: List<SettingsKeys.ApiEntry>? = null
    private var manualActiveIndexSnapshot = 0
    private var autoCreatedByPreset = false

    // 输入字段
    private var fontSizeText by mutableStateOf("15")
    private var systemPromptText by mutableStateOf("")
    private var maxHistoryText by mutableStateOf("20")
    private var worldBudgetText by mutableStateOf("1500")
    private var webSearchUrlText by mutableStateOf("")

    // 背景
    private var bgMode by mutableStateOf("color")
    private var bgScale by mutableStateOf("fit")
    private var bgImageBase64 by mutableStateOf<String?>(null)
    private var bgImageName by mutableStateOf<String?>(null)

    // 昵称与头像
    private var userNameText by mutableStateOf("用户")
    private var aiNameText by mutableStateOf("AI")
    private var userAvatarBase64 by mutableStateOf<String?>(null)
    private var aiAvatarBase64 by mutableStateOf<String?>(null)

    // 对话框状态
    private var showApiDialog by mutableStateOf(false)
    private var apiDialogIndex by mutableStateOf<Int?>(null)
    private var showModelOptions by mutableStateOf(false)
    private var showModelTip by mutableStateOf(false)
    private var showProviderSelect by mutableStateOf(false)
    private var showLanguageSelect by mutableStateOf(false)

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleBgImagePicked(uri)
    }

    private val userAvatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleUserAvatarPicked(uri, isAi = false)
    }

    private val aiAvatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleUserAvatarPicked(uri, isAi = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConversationManager.init(this)
        loadSettings()
        loadProviderMode()

        setContent {
            SAChatTheme {
                SettingsScreen()
            }
        }
    }

    // ───────────────────────── 业务逻辑（迁移自旧版） ─────────────────────────

    private fun loadSettings() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        val apiJson = sp.getString(SettingsKeys.KEY_API_CONFIGS, "[]") ?: "[]"
        val listType = object : TypeToken<List<SettingsKeys.ApiEntry>>() {}.type
        val loaded: List<SettingsKeys.ApiEntry> = gson.fromJson(apiJson, listType)
        apiEntries.clear()
        apiEntries.addAll(loaded)
        activeApiIndex = sp.getInt(SettingsKeys.KEY_ACTIVE_API, 0).coerceIn(0, (apiEntries.size - 1).coerceAtLeast(0))

        fontSizeText = sp.getInt(SettingsKeys.KEY_FONT_SIZE, 15).toString()

        bgImageBase64 = sp.getString(SettingsKeys.KEY_BG_IMAGE, null)
        bgImageName = sp.getString("bg_image_name", null)

        bgMode = sp.getString(SettingsKeys.KEY_BG_MODE, "color") ?: "color"
        bgScale = sp.getString(SettingsKeys.KEY_BG_SCALE, "fit") ?: "fit"

        systemPromptText = ConversationManager.getDefaultSystemPrompt()
        maxHistoryText = sp.getInt(SettingsKeys.KEY_MAX_HISTORY, Conversation.MAX_HISTORY).toString()
        worldBudgetText = sp.getInt(SettingsKeys.KEY_WORLDINFO_BUDGET, WorldInfoEngine.DEFAULT_BUDGET).toString()
        webSearchUrlText = sp.getString(SettingsKeys.KEY_WEB_SEARCH_URL, "") ?: ""

        userNameText = sp.getString(SettingsKeys.KEY_USER_NAME, "用户") ?: "用户"
        aiNameText = sp.getString(SettingsKeys.KEY_AI_NAME, "AI") ?: "AI"
        userAvatarBase64 = sp.getString(SettingsKeys.KEY_USER_AVATAR, null)
        aiAvatarBase64 = sp.getString(SettingsKeys.KEY_AI_AVATAR, null)
    }

    // ─── Free Gateway Integration: 预设服务商 ──────────────────────

    private val providerModes = arrayOf(
        SettingsKeys.PROVIDER_CUSTOM,
        SettingsKeys.PROVIDER_OPEN_CODE_ZEN,
        SettingsKeys.PROVIDER_OPEN_KILO
    )

    private val providerModeLabels = arrayOf(
        R.string.provider_custom,
        R.string.provider_open_code_zen,
        R.string.provider_open_kilo
    )

    /** 启动时从 SharedPreferences 恢复预设模式与手动输入快照 */
    private fun loadProviderMode() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        providerMode = sp.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM

        // Free Gateway Integration: 已不存在的预设模式重置为自定义
        if (providerMode !in providerModes) {
            providerMode = SettingsKeys.PROVIDER_CUSTOM
            sp.edit()
                .remove(SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT)
                .remove(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX)
                .remove(SettingsKeys.KEY_PROVIDER_AUTO_CREATED)
                .apply()
        }

        if (providerMode == SettingsKeys.PROVIDER_CUSTOM) return

        // Free Gateway: OpenKilo / OpenCode Zen 免费模式下恢复模型选择
        if (providerMode == SettingsKeys.PROVIDER_OPEN_KILO) {
            kiloModelOption = sp.getString(SettingsKeys.KEY_KILO_MODEL, SettingsKeys.KILO_MODEL)
                ?: SettingsKeys.KILO_MODEL
            // 兼容旧版保存的 "auto" 值
            if (kiloModelOption == SettingsKeys.KILO_MODEL_AUTO) kiloModelOption = SettingsKeys.KILO_MODEL
        } else if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            zenModelOption = sp.getString(SettingsKeys.KEY_ZEN_MODEL, SettingsKeys.ZEN_MODEL_DEFAULT)
                ?: SettingsKeys.ZEN_MODEL_DEFAULT
        }
        val snapshotJson = sp.getString(SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT, null)
        if (!snapshotJson.isNullOrBlank()) {
            val type = object : TypeToken<List<SettingsKeys.ApiEntry>>() {}.type
            manualEntriesSnapshot = gson.fromJson(snapshotJson, type)
            manualActiveIndexSnapshot = sp.getInt(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX, 0)
            autoCreatedByPreset = sp.getBoolean(SettingsKeys.KEY_PROVIDER_AUTO_CREATED, false)
        }
    }

    /** 切换预设模式：自动填充 API 地址 / 模型 / Key，或恢复手动输入 */
    private fun applyProviderMode(mode: String) {
        when (mode) {
            SettingsKeys.PROVIDER_OPEN_KILO -> {
                fillPresetIntoActiveEntry(
                    label = getString(R.string.provider_open_kilo),
                    url = SettingsKeys.KILO_BASE_URL,
                    model = kiloModelOption,
                    key = ""
                )
            }
            SettingsKeys.PROVIDER_OPEN_CODE_ZEN -> {
                fillPresetIntoActiveEntry(
                    label = getString(R.string.provider_open_code_zen),
                    url = SettingsKeys.ZEN_BASE_URL,
                    model = zenModelOption,
                    key = SettingsKeys.ZEN_PUBLIC_KEY
                )
            }
            else -> {
                restoreManualState()
            }
        }
        persistProviderState()
    }

    // ─── Free Gateway: OpenKilo / OpenCode Zen 模型选择 ────────────

    private val kiloModelOptions = arrayOf(
        SettingsKeys.KILO_MODEL,
        SettingsKeys.KILO_MODEL_NEMOTRON_ULTRA,
        SettingsKeys.KILO_MODEL_STEPFUN,
        SettingsKeys.KILO_MODEL_LING,
        SettingsKeys.KILO_MODEL_NEMOTRON_SUPER,
        SettingsKeys.KILO_MODEL_LAGUNA
    )

    private val kiloModelOptionLabels = arrayOf(
        R.string.model_auto,
        R.string.model_nemotron_ultra,
        R.string.model_stepfun,
        R.string.model_ling,
        R.string.model_nemotron_super,
        R.string.model_laguna
    )

    private val zenModelOptions = arrayOf(
        SettingsKeys.ZEN_MODEL_MIMO,
        SettingsKeys.ZEN_MODEL_LING,
        SettingsKeys.ZEN_MODEL_DEEPSEEK,
        SettingsKeys.ZEN_MODEL_NEMOTRON,
        SettingsKeys.ZEN_MODEL_NORTH,
        SettingsKeys.ZEN_MODEL_LAGUNA
    )

    private val zenModelOptionLabels = arrayOf(
        R.string.model_zen_mimo,
        R.string.model_zen_ling,
        R.string.model_zen_deepseek,
        R.string.model_zen_nemotron,
        R.string.model_zen_north,
        R.string.model_zen_laguna
    )

    /** 当前模式对应的模型选项列表 */
    private fun currentModelOptions(): Pair<Array<String>, Array<Int>> {
        return if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            zenModelOptions to zenModelOptionLabels
        } else {
            kiloModelOptions to kiloModelOptionLabels
        }
    }

    /** 当前模式选中的模型值 */
    private fun currentModelValue(): String {
        return if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) zenModelOption else kiloModelOption
    }

    /** 当前模型显示名 */
    private fun currentModelLabel(): String {
        val (options, labels) = currentModelOptions()
        val idx = options.indexOf(currentModelValue())
        return if (idx >= 0) getString(labels[idx]) else currentModelValue()
    }

    private fun onModelSelected(value: String) {
        if (providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            zenModelOption = value
        } else {
            kiloModelOption = value
        }
        persistProviderState()
    }

    /** 将预设的 Base URL / 模型 / Key 填入当前激活的 API 条目 */
    private fun fillPresetIntoActiveEntry(label: String, url: String, model: String, key: String) {
        if (manualEntriesSnapshot == null) {
            manualEntriesSnapshot = apiEntries.toList()
            manualActiveIndexSnapshot = activeApiIndex
        }

        if (apiEntries.isEmpty()) {
            apiEntries.add(SettingsKeys.ApiEntry(label = label, url = url, key = key, model = model))
            activeApiIndex = 0
            autoCreatedByPreset = true
        } else {
            val entry = apiEntries[activeApiIndex]
            apiEntries[activeApiIndex] = entry.copy(url = url, key = key, model = model)
        }
    }

    /** 切回自定义：恢复手动输入的内容；若此前无手动输入则恢复为空白 */
    private fun restoreManualState() {
        val snapshot = manualEntriesSnapshot
        when {
            snapshot != null && snapshot.isNotEmpty() -> {
                apiEntries.clear()
                apiEntries.addAll(snapshot)
                activeApiIndex = manualActiveIndexSnapshot.coerceIn(0, (apiEntries.size - 1).coerceAtLeast(0))
            }
            autoCreatedByPreset && apiEntries.isNotEmpty() -> {
                apiEntries.removeAt(activeApiIndex)
                activeApiIndex = (apiEntries.size - 1).coerceAtLeast(0)
            }
            snapshot != null -> {
                apiEntries.clear()
                activeApiIndex = 0
            }
        }
        autoCreatedByPreset = false
        manualEntriesSnapshot = null
    }

    /** 持久化预设模式、API 列表及手动输入快照 */
    private fun persistProviderState() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(SettingsKeys.KEY_PROVIDER_MODE, providerMode)
            .putString(SettingsKeys.KEY_API_CONFIGS, gson.toJson(apiEntries))
            .putInt(SettingsKeys.KEY_ACTIVE_API, activeApiIndex)
            .apply()
        if (providerMode != SettingsKeys.PROVIDER_CUSTOM) {
            sp.edit()
                .putString(SettingsKeys.KEY_KILO_MODEL, kiloModelOption)
                .putString(SettingsKeys.KEY_ZEN_MODEL, zenModelOption)
                .putString(
                    SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT,
                    gson.toJson(manualEntriesSnapshot ?: emptyList<SettingsKeys.ApiEntry>())
                )
                .putInt(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX, manualActiveIndexSnapshot)
                .putBoolean(SettingsKeys.KEY_PROVIDER_AUTO_CREATED, autoCreatedByPreset)
                .apply()
        } else {
            sp.edit()
                .remove(SettingsKeys.KEY_PROVIDER_MANUAL_SNAPSHOT)
                .remove(SettingsKeys.KEY_PROVIDER_MANUAL_ACTIVE_INDEX)
                .remove(SettingsKeys.KEY_PROVIDER_AUTO_CREATED)
                .apply()
        }
    }

    // ─── 图片处理（与旧版一致：缩放 + Base64） ───

    private fun handleBgImagePicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) { Toast.makeText(this, getString(R.string.settings_image_invalid), Toast.LENGTH_SHORT).show(); return }

            val maxSize = 1024
            val scale = Math.min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            bgImageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) bgImageName = it.getString(idx)
                }
            }
            Toast.makeText(this, getString(R.string.settings_bg_picked), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_image_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUserAvatarPicked(uri: Uri, isAi: Boolean) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) { Toast.makeText(this, getString(R.string.settings_image_invalid), Toast.LENGTH_SHORT).show(); return }

            val maxSize = 256
            val scale = Math.min(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            outputStream.close()

            if (isAi) {
                aiAvatarBase64 = base64
            } else {
                userAvatarBase64 = base64
            }
            Toast.makeText(this, getString(R.string.settings_avatar_picked, if (isAi) "AI " else "我的"), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_avatar_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putString(SettingsKeys.KEY_API_CONFIGS, gson.toJson(apiEntries))
            .putInt(SettingsKeys.KEY_ACTIVE_API, activeApiIndex)
            // Free Gateway Integration: 保存预设服务商模式
            .putString(SettingsKeys.KEY_PROVIDER_MODE, providerMode)
            .putString(SettingsKeys.KEY_KILO_MODEL, kiloModelOption)
            .putString(SettingsKeys.KEY_ZEN_MODEL, zenModelOption)
            .putInt(SettingsKeys.KEY_FONT_SIZE, fontSizeText.toIntOrNull() ?: 15)
            // 记忆历史消息轮数（全局生效），钳制在 5~100 范围内
            .putInt(
                SettingsKeys.KEY_MAX_HISTORY,
                (maxHistoryText.toIntOrNull() ?: Conversation.MAX_HISTORY)
                    .coerceIn(SettingsKeys.MAX_HISTORY_MIN, SettingsKeys.MAX_HISTORY_MAX)
            )
            .putInt(
                SettingsKeys.KEY_WORLDINFO_BUDGET,
                (worldBudgetText.toIntOrNull() ?: WorldInfoEngine.DEFAULT_BUDGET)
                    .coerceIn(200, 20000)
            )
            .putString(SettingsKeys.KEY_BG_MODE, bgMode)
            .putString(SettingsKeys.KEY_BG_IMAGE, bgImageBase64)
            .putString("bg_image_name", bgImageName)
            .putString(SettingsKeys.KEY_BG_SCALE, bgScale)
            .putString(SettingsKeys.KEY_WEB_SEARCH_URL, webSearchUrlText.trim())
            .putString(SettingsKeys.KEY_USER_NAME, userNameText.trim().ifBlank { "用户" })
            .putString(SettingsKeys.KEY_AI_NAME, aiNameText.trim().ifBlank { "AI" })
            .putString(SettingsKeys.KEY_USER_AVATAR, userAvatarBase64)
            .putString(SettingsKeys.KEY_AI_AVATAR, aiAvatarBase64)
            .apply()

        ConversationManager.init(this)
        ConversationManager.setDefaultSystemPrompt(systemPromptText.trim())

        if (apiEntries.isNotEmpty() && activeApiIndex < apiEntries.size) {
            val active = apiEntries[activeApiIndex]
            getSharedPreferences(ApiConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(ApiConfig.KEY_URL, active.url)
                .putString(ApiConfig.KEY_KEY, active.key)
                .putString(ApiConfig.KEY_MODEL, active.model)
                .apply()
        }

        Toast.makeText(this, getString(R.string.settings_saved_toast), Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }

    // ───────────────────────── Compose UI ─────────────────────────

    private val isFreeMode: Boolean
        get() = providerMode != SettingsKeys.PROVIDER_CUSTOM

    @Composable
    private fun SettingsScreen() {
        val activity = this
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            // 页面大标题
            Text(
                text = getString(R.string.settings_page_title),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
            Text(
                text = getString(R.string.settings_page_subtitle),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // ── 分组 1：API 配置 ──
            SettingsCard(title = getString(R.string.settings_group_api)) {
                // 免费模式（OpenKilo/OpenCode Zen）隐藏 API 列表与添加按钮（与旧版一致）
                if (!isFreeMode) {
                    // API 条目列表
                    apiEntries.forEachIndexed { index, entry ->
                        ApiEntryCard(
                            entry = entry,
                            isActive = index == activeApiIndex,
                            onClick = { activeApiIndex = index },
                            onDelete = {
                                apiEntries.removeAt(index)
                                if (activeApiIndex >= apiEntries.size) {
                                    activeApiIndex = (apiEntries.size - 1).coerceAtLeast(0)
                                }
                            }
                        )
                    }
                    // 添加按钮
                    TextButton(onClick = {
                        apiDialogIndex = null
                        showApiDialog = true
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(getString(R.string.settings_add_api))
                    }
                }
                // 预设服务商
                SettingsRow(
                    icon = Icons.Filled.Bolt,
                    title = stringResource(R.string.provider_preset),
                    value = providerModeLabel(),
                    onClick = { showProviderSelect = true },
                    modifier = Modifier.fillMaxWidth()
                )
                // 选择模型按钮（免费模式显示）
                AnimatedVisibility(
                    visible = isFreeMode,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        OutlinedButton(
                            onClick = { showModelTipOrOptions() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("${stringResource(R.string.select_model)}：${currentModelLabel()}")
                        }
                        Text(
                            text = stringResource(R.string.provider_free_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 分组 2：字体大小 ──
            SettingsCard(title = getString(R.string.settings_group_font)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = fontSizeText,
                        onValueChange = { fontSizeText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text(getString(R.string.settings_font_size_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = getString(R.string.settings_font_preview),
                        fontSize = (fontSizeText.toIntOrNull() ?: 15).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 分组 3：提示词管理 ──
            SettingsCard(title = getString(R.string.settings_group_prompt)) {
                OutlinedTextField(
                    value = systemPromptText,
                    onValueChange = { systemPromptText = it },
                    label = { Text(getString(R.string.settings_system_prompt_label)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                HintText(getString(R.string.settings_system_prompt_note))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = maxHistoryText,
                    onValueChange = { maxHistoryText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(getString(R.string.label_max_history)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                HintText(getString(R.string.max_history_note))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = worldBudgetText,
                    onValueChange = { worldBudgetText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text(getString(R.string.settings_world_budget_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                HintText(getString(R.string.settings_world_budget_note))
            }

            Spacer(Modifier.height(16.dp))

            // ── 分组 4：联网搜索配置 ──
            SettingsCard(title = getString(R.string.settings_group_search)) {
                OutlinedTextField(
                    value = webSearchUrlText,
                    onValueChange = { webSearchUrlText = it },
                    label = { Text(getString(R.string.settings_search_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                HintText(getString(R.string.settings_search_note))
            }

            Spacer(Modifier.height(16.dp))

            // ── 分组 5：背景图片 ──
            SettingsCard(title = getString(R.string.settings_group_bg)) {
                Row {
                    BackgroundModeChip(
                        label = getString(R.string.settings_bg_color),
                        selected = bgMode != "image",
                        onClick = { bgMode = "color" }
                    )
                    Spacer(Modifier.width(8.dp))
                    BackgroundModeChip(
                        label = getString(R.string.settings_bg_image),
                        selected = bgMode == "image",
                        onClick = { bgMode = "image" }
                    )
                }
                AnimatedVisibility(
                    visible = bgMode == "image",
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch(arrayOf("image/png")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(getString(R.string.settings_pick_png)) }
                        Text(
                            text = bgImageName ?: getString(R.string.settings_no_image),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            BackgroundModeChip(
                                label = getString(R.string.settings_bg_fit),
                                selected = bgScale != "fill",
                                onClick = { bgScale = "fit" }
                            )
                            Spacer(Modifier.width(8.dp))
                            BackgroundModeChip(
                                label = getString(R.string.settings_bg_fill),
                                selected = bgScale == "fill",
                                onClick = { bgScale = "fill" }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 分组 6：聊天昵称 ──
            SettingsCard(title = getString(R.string.settings_group_nickname)) {
                // 语言切换（中英文，默认跟随系统）
                SettingsRow(
                    icon = Icons.Filled.Language,
                    title = getString(R.string.settings_language),
                    value = LocaleHelper.languageLabel(this@SettingsActivity),
                    onClick = { showLanguageSelect = true },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userNameText,
                    onValueChange = { userNameText = it },
                    label = { Text(stringResource(R.string.label_user_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = aiNameText,
                    onValueChange = { aiNameText = it },
                    label = { Text(stringResource(R.string.label_ai_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { userAvatarPickerLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (userAvatarBase64 != null) getString(R.string.settings_avatar_picked, "我的") else getString(R.string.settings_pick_user_avatar)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { aiAvatarPickerLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (aiAvatarBase64 != null) getString(R.string.settings_avatar_picked, "AI ") else getString(R.string.settings_pick_ai_avatar)) }
            }

            Spacer(Modifier.height(24.dp))

            // ── 保存按钮 ──
            Button(
                onClick = { activity.saveSettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(getString(R.string.settings_save), fontSize = 16.sp)
            }
            Spacer(Modifier.height(24.dp))
        }

        // ── 对话框 ──
        if (showApiDialog) {
            ApiEntryDialog(
                index = apiDialogIndex,
                initialEntry = apiDialogIndex?.let { apiEntries.getOrNull(it) } ?: SettingsKeys.ApiEntry(),
                keyEnabled = !isFreeMode,
                onConfirm = { label, url, key, model ->
                    val newEntry = SettingsKeys.ApiEntry(label = label, url = url, key = key, model = model)
                    val idx = apiDialogIndex
                    if (idx != null && idx < apiEntries.size) apiEntries[idx] = newEntry else apiEntries.add(newEntry)
                    showApiDialog = false
                },
                onDismiss = { showApiDialog = false }
            )
        }
        if (showProviderSelect) {
            AlertDialog(
                onDismissRequest = { showProviderSelect = false },
                title = { Text(stringResource(R.string.provider_preset)) },
                text = {
                    Column {
                        providerModes.forEachIndexed { index, mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showProviderSelect = false
                                        if (mode != providerMode) {
                                            providerMode = mode
                                            applyProviderMode(mode)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = mode == providerMode,
                                    onClick = null
                                )
                                Text(
                                    text = getString(providerModeLabels[index]),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProviderSelect = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
        if (showModelTip) {
            AlertDialog(
                onDismissRequest = { showModelTip = false; showModelOptions = true },
                title = { Text(stringResource(R.string.kilo_model_tip_title)) },
                text = { Text(stringResource(R.string.kilo_model_tip_message)) },
                confirmButton = {
                    TextButton(onClick = { showModelTip = false; showModelOptions = true }) {
                        Text(stringResource(R.string.got_it))
                    }
                }
            )
        }
        if (showModelOptions) {
            ModelSelectDialog(
                title = stringResource(R.string.select_model),
                options = currentModelOptions().first.toList(),
                labels = currentModelOptions().second.map { getString(it) },
                selected = currentModelValue(),
                onSelect = { value ->
                    onModelSelected(value)
                    showModelOptions = false
                },
                onDismiss = { showModelOptions = false }
            )
        }
        if (showLanguageSelect) {
            LanguageSelectDialog()
        }
    }

    /** 语言选择：跟随系统（默认）/ 中文 / English，切换后立即生效（清栈回聊天页） */
    @Composable
    private fun LanguageSelectDialog() {
        val ctx = this@SettingsActivity
        val current = LocaleHelper.getLanguagePref(ctx)
        val options = listOf(
            SettingsKeys.LANGUAGE_SYSTEM to getString(R.string.settings_language_system),
            SettingsKeys.LANGUAGE_ZH to "中文",
            SettingsKeys.LANGUAGE_EN to "English"
        )
        AlertDialog(
            onDismissRequest = { showLanguageSelect = false },
            title = { Text(getString(R.string.settings_language)) },
            text = {
                Column {
                    Text(
                        text = getString(R.string.settings_language_tip),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ctx.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                                        .edit().putString(SettingsKeys.KEY_LANGUAGE, value).apply()
                                    showLanguageSelect = false
                                    // 清任务栈重建：所有页面按新语言重新创建
                                    val intent = Intent(ctx, ChatActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    }
                                    ctx.startActivity(intent)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = value == current, onClick = null)
                            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageSelect = false }) { Text(getString(R.string.cancel)) }
            }
        )
    }

    private fun providerModeLabel(): String {
        val idx = providerModes.indexOf(providerMode).coerceAtLeast(0)
        return getString(providerModeLabels[idx])
    }

    private fun showModelTipOrOptions() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        // Free Gateway Integration: 每次启动首次点击时弹提示，之后本次启动内不再弹
        if (!sp.getBoolean(SettingsKeys.KEY_KILO_MODEL_TIP_SHOWN, false)) {
            sp.edit().putBoolean(SettingsKeys.KEY_KILO_MODEL_TIP_SHOWN, true).apply()
            showModelTip = true
        } else {
            showModelOptions = true
        }
    }

    @Composable
    private fun SettingsCard(title: String, content: @Composable () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun SettingsRow(
        icon: ImageVector,
        title: String,
        value: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }

    @Composable
    private fun ApiEntryCard(
        entry: SettingsKeys.ApiEntry,
        isActive: Boolean,
        onClick: () -> Unit,
        onDelete: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(14.dp),
            color = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isActive) getString(R.string.settings_in_use) else entry.model.ifBlank { getString(R.string.settings_not_configured) },
                        fontSize = 12.sp,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(getString(R.string.settings_delete), color = MaterialTheme.colorScheme.error)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    @Composable
    private fun BackgroundModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.clickable(onClick = onClick)
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }

    @Composable
    private fun HintText(text: String) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ───────────────────────── 对话框组件 ─────────────────────────

@Composable
private fun ApiEntryDialog(
    index: Int?,
    initialEntry: SettingsKeys.ApiEntry,
    keyEnabled: Boolean,
    onConfirm: (label: String, url: String, key: String, model: String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf(initialEntry.label) }
    var url by remember { mutableStateOf(initialEntry.url) }
    var key by remember { mutableStateOf(initialEntry.key) }
    var model by remember { mutableStateOf(initialEntry.model) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (index != null) context.getString(R.string.settings_edit_api) else context.getString(R.string.settings_add_api_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(context.getString(R.string.settings_label_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(context.getString(R.string.label_api_url)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(context.getString(R.string.label_api_key)) },
                    singleLine = true,
                    enabled = keyEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                if (!keyEnabled) {
                    Text(
                        text = context.getString(R.string.provider_free_hint),
                        fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(context.getString(R.string.label_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (label.isBlank()) {
                    Toast.makeText(context, context.getString(R.string.settings_label_empty), Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(label.trim(), url.trim(), key.trim(), model.trim())
                }
            }) { Text(context.getString(R.string.btn_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) }
        }
    )
}

@Composable
private fun ModelSelectDialog(
    title: String,
    options: List<String>,
    labels: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = null
                        )
                        Text(
                            text = labels.getOrElse(index) { option },
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.cancel)) }
        }
    )
}
