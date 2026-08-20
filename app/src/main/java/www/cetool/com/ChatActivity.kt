package www.cetool.com

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.gson.Gson
import kotlinx.coroutines.launch
import www.cetool.com.importer.STChatLogImporter
import www.cetool.com.importer.TavernCardImporter
import www.cetool.com.importer.WorldInfoImporter
import www.cetool.com.manager.CharacterManager
import www.cetool.com.manager.MemoryArchiver
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.model.ApiConfig
import www.cetool.com.model.CharacterFields
import www.cetool.com.model.Conversation
import www.cetool.com.model.Message
import www.cetool.com.model.Message.Companion.ATTACH_TYPE_IMAGE
import www.cetool.com.model.Message.Companion.ATTACH_TYPE_TEXT
import www.cetool.com.model.TavernCard
import www.cetool.com.ui.components.MessageList
import www.cetool.com.ui.theme.SAChatTheme
import java.io.ByteArrayOutputStream

/**
 * 聊天主界面（Compose 版）
 * 全新布局：圆角输入栏 / 分组抽屉 / 空态插画 / 消息区（ChatComponents.MessageList）。
 * 业务逻辑与旧版一致（对话管理 / 导入 / 记忆封存 / 附件 / 背景），仅 UI 层迁移。
 */
class ChatActivity : ComponentActivity() {

    // ─── 消息列表状态（1.3 迁移保留） ───
    private val messagesState = androidx.compose.runtime.mutableStateOf<List<Message>>(emptyList())
    private val characterAvatarState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val userAvatarState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val aiNameState = androidx.compose.runtime.mutableStateOf("AI")
    private val fontSizeState = androidx.compose.runtime.mutableStateOf(15)
    private val systemPromptState = androidx.compose.runtime.mutableStateOf("")
    private val bindingCharacterState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val bindingWorldState = androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    private val scrollTargetState = androidx.compose.runtime.mutableStateOf<Int?>(null)

    // ─── 页面状态（Compose） ───
    private var inputText by mutableStateOf("")
    private var inputHint by mutableStateOf("输入消息…")
    private var chatTitle by mutableStateOf("SAChat")
    private var modelInfoText by mutableStateOf<String?>(null)
    private var isStreaming by mutableStateOf(false)
    private var isEmptyConversation by mutableStateOf(true)
    private var isAdventure by mutableStateOf(false)
    private var backgroundDrawable by mutableStateOf<Drawable?>(null)
    // 默认不高亮，点击导航项后才高亮（用户要求）
    private var activeNavId by mutableStateOf("")

    // 抽屉会话列表（在 refreshCurrentConversation 中刷新，保持最新）
    private var drawerConversations by mutableStateOf<List<Conversation>>(emptyList())
    private var drawerCurrentId by mutableStateOf("")

    // 版本号（动态读取，与 build.gradle 同步）
    private var appVersionText by mutableStateOf("")

    // 长按会话操作（删除/清空/重命名/世界书）
    private var actionConv by mutableStateOf<Conversation?>(null)
    private var showConvActions by mutableStateOf(false)
    private var showDeleteConvConfirm by mutableStateOf(false)
    private var showClearConvConfirm by mutableStateOf(false)
    private var showRenameDialog by mutableStateOf(false)
    private var renameText by mutableStateOf("")
    private var showWorldSelect by mutableStateOf(false)

    // 抽屉分组展开状态（默认：角色卡/世界书/记录收起，其他展开）
    private val groupExpanded = mutableStateMapOf(
        "roleplay" to false, "world" to false, "records" to false, "other" to true
    )

    // 顶栏菜单
    private var showPopupMenu by mutableStateOf(false)

    // 对话框状态
    private var showArchiving by mutableStateOf(false)
    private var archiveConv: Conversation? = null
    private var archiveCard: TavernCard? = null
    private var archiveBaseFields: CharacterFields? = null
    private var showArchiveConfirm by mutableStateOf(false)
    private var archiveTitleText by mutableStateOf("")
    private var archiveRelationText by mutableStateOf("")
    private var archiveInteractionText by mutableStateOf("")
    private var archiveBottomLineText by mutableStateOf("")
    private var archiveKeyEventsText by mutableStateOf("")
    private var showThinkingLevel by mutableStateOf(false)
    private var showEditSystemPrompt by mutableStateOf(false)
    private var editSystemPromptText by mutableStateOf("")
    private var showDeleteConfirm by mutableStateOf(false)
    private var showSearch by mutableStateOf(false)
    private var searchKeyword by mutableStateOf("")
    private var searchResults by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    private var showSearchResults by mutableStateOf(false)
    private var importPendingName by mutableStateOf("")
    private var importPendingDesc by mutableStateOf("")
    private var importPendingJson by mutableStateOf("")
    private var showImportConfirm by mutableStateOf(false)
    private var worldImportWarnings by mutableStateOf<List<String>>(emptyList())
    private var showWorldImportWarnings by mutableStateOf(false)

    private lateinit var apiConfig: ApiConfig
    private var currentConversation: Conversation? = null
    private var webSearchConfigured = false
    private var webSearchEnabled = false
    private var thinkingEnabled = false
    private var thinkingLevel = "medium"
    // Free Gateway Integration: 预设服务商模式
    private var providerMode = SettingsKeys.PROVIDER_CUSTOM

    private var pendingAttachmentType: String? = null
    private var pendingAttachmentData: String? = null
    private var pendingAttachmentName: String? = null

    private var selectWorldOnReturn = false

    // ─── 文件/导入 launcher（保留） ───
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleFileSelected(uri)
    }

    private val jsonImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleCharacterImport(uri)
    }

    private val chatLogImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleChatLogImport(uri)
    }

    private val worldInfoListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val wid = result.data?.getStringExtra("world_info_id")
            if (wid != null) selectWorldInfo(wid)
        }
    }

    private val worldInfoEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val wid = result.data?.getStringExtra("world_info_id")
            if (wid != null && selectWorldOnReturn) selectWorldInfo(wid)
            selectWorldOnReturn = false
        }
    }

    private val worldInfoImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleWorldInfoImport(uri)
    }

    private val characterListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val characterId = result.data?.getStringExtra("character_id")
            if (characterId != null) startCharacterConversation(characterId)
        }
    }

    private val characterEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val characterId = result.data?.getStringExtra("character_id")
            val isEdit = result.data?.getBooleanExtra("is_edit", false) ?: false
            if (characterId != null && !isEdit) startCharacterConversation(characterId)
        }
    }

    private val adventureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val worldId = data.getStringExtra("world_info_id")
            val roleIds = data.getStringArrayListExtra("role_ids") ?: emptyList()
            val opening = data.getStringExtra("opening") ?: ""
            startAdventureConversation(worldId, roleIds, opening)
        }
    }

    // ───────────────────────── 生命周期 ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConversationManager.init(this)
        loadConfig()
        applyBackground()
        appVersionText = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }

        ConversationManager.onConversationsChanged = {
            runOnUiThread {
                refreshCurrentConversation()
            }
        }
        ConversationManager.onCurrentMessageUpdated = { convId ->
            runOnUiThread {
                if (convId == currentConversation?.id) {
                    currentConversation?.let { conv -> messagesState.value = conv.messages.toList() }
                }
            }
        }

        refreshCurrentConversation()

        // Free Gateway: OpenKilo / OpenCode Zen 免费模式无需 API Key，跳过配置校验
        val settingsSp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        providerMode = settingsSp.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM
        val configUsable = providerMode == SettingsKeys.PROVIDER_OPEN_KILO ||
            providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN ||
            apiConfig.isValid()
        if (!configUsable) {
            Toast.makeText(this, R.string.toast_please_config, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        webSearchConfigured = sp.getString(SettingsKeys.KEY_WEB_SEARCH_URL, "")?.isNotBlank() == true
        webSearchEnabled = sp.getBoolean(SettingsKeys.KEY_WEB_SEARCH_ENABLED, false)
        thinkingEnabled = sp.getBoolean(SettingsKeys.KEY_THINKING_ENABLED, false)
        thinkingLevel = sp.getString(SettingsKeys.KEY_THINKING_LEVEL, "medium") ?: "medium"

        setContent {
            SAChatTheme {
                ChatScreen()
            }
        }
    }

    override fun onDestroy() {
        ConversationManager.onConversationsChanged = null
        ConversationManager.onCurrentMessageUpdated = null
        ConversationManager.save()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        ConversationManager.save()
    }

    override fun onResume() {
        super.onResume()
        // 从其他页面返回（如世界书管理页删除/修改）后同步当前对话的绑定提示
        refreshCurrentConversation()
    }

    // ───────────────────────── Compose 页面 ─────────────────────────

    @Composable
    private fun ChatScreen() {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val ctx = this@ChatActivity

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    // 与原版一致：抽屉宽度 280dp
                    modifier = Modifier.width(280.dp)
                ) {
                    ChatDrawer(onClose = { scope.launch { drawerState.close() } })
                }
            }
        ) {
            val bgColor = MaterialTheme.colorScheme.surface
            val bgDrawable = backgroundDrawable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        // 背景：主题色打底 + 用户配置的背景图（cover / 平铺）
                        drawRect(bgColor)
                        bgDrawable?.let { drawable ->
                            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            drawable.draw(drawContext.canvas.nativeCanvas)
                        }
                    }
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    ChatTopBar(onMenuClick = { scope.launch { drawerState.open() } })
                if (isEmptyConversation) {
                    EmptyState(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                    MessageList(
                        messages = messagesState.value,
                        characterAvatar = characterAvatarState.value,
                        userAvatar = userAvatarState.value,
                        aiName = aiNameState.value,
                        fontSize = fontSizeState.value,
                        systemPromptPreview = systemPromptState.value,
                        bindingCharacterName = bindingCharacterState.value,
                        bindingWorldNames = bindingWorldState.value,
                        scrollTarget = scrollTargetState.value,
                        onClearScrollTarget = { scrollTargetState.value = null },
                        onCharacterBindingClick = {
                            characterListLauncher.launch(Intent(ctx, CharacterListActivity::class.java))
                        },
                        onWorldBindingClick = {
                            worldInfoListLauncher.launch(Intent(ctx, WorldInfoListActivity::class.java))
                        },
                        onCardClick = { cardId ->
                            val intent = Intent(ctx, CharacterEditActivity::class.java)
                            intent.putExtra("character_id", cardId)
                            startActivity(intent)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                AnimatedVisibility(
                    visible = isAdventure,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    AdventureChipBar()
                }
                ComposerBar()
                }
                // 悬浮 FAB（原版风格：左下角悬浮球，点击打开抽屉）
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = if (isAdventure) 140.dp else 100.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { scope.launch { drawerState.open() } },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 6.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.ChatBubble,
                            contentDescription = stringResource(R.string.nav_open),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }

        // ── 对话框 ──
        if (showArchiving) ArchivingDialog()
        if (showArchiveConfirm) ArchiveConfirmDialog()
        if (showThinkingLevel) ThinkingLevelDialog()
        if (showEditSystemPrompt) EditSystemPromptDialog()
        if (showDeleteConfirm) DeleteConfirmDialog()
        if (showSearch) SearchDialog()
        if (showSearchResults) SearchResultsDialog()
        if (showImportConfirm) ImportConfirmDialog()
        if (showWorldImportWarnings) WorldImportWarningsDialog()
        if (showConvActions) ConvActionsDialog()
        if (showDeleteConvConfirm) DeleteConvConfirmDialog()
        if (showClearConvConfirm) ClearConvConfirmDialog()
        if (showRenameDialog) RenameDialog()
        if (showWorldSelect) WorldSelectDialog()
    }

    /** 顶栏：抽屉按钮 + 标题/模型信息/流式指示 + 更多菜单（DropdownMenu 锚定在按钮下方） */
    @Composable
    private fun ChatTopBar(onMenuClick: () -> Unit) {
        val ctx = this@ChatActivity
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.nav_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = chatTitle,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (modelInfoText != null) {
                    Text(
                        text = modelInfoText!!,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 更多菜单：DropdownMenu 必须与锚点按钮在同一 Box 内，否则弹出位置错乱
            Box {
                IconButton(onClick = { showPopupMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "菜单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showPopupMenu,
                    onDismissRequest = { showPopupMenu = false }
                ) {
                    // 与左侧抽屉重合的入口（设置/新对话/切换对话/清空/删除）已移入左侧；
                    // 右侧仅保留左侧没有的快捷功能
                    if (webSearchConfigured) {
                        MenuItem(Icons.Filled.Search, if (webSearchEnabled) "关闭联网搜索" else "开启联网搜索") {
                            webSearchEnabled = !webSearchEnabled
                            getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(SettingsKeys.KEY_WEB_SEARCH_ENABLED, webSearchEnabled).apply()
                            Toast.makeText(ctx, if (webSearchEnabled) "联网搜索已开启" else "联网搜索已关闭", Toast.LENGTH_SHORT).show()
                        }
                    }
                    MenuItem(Icons.Filled.Tune, if (thinkingEnabled) "深度思考：$thinkingLevel" else "深度思考：关闭") {
                        if (thinkingEnabled) {
                            thinkingEnabled = false
                            getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(SettingsKeys.KEY_THINKING_ENABLED, false).apply()
                            Toast.makeText(ctx, "深度思考已关闭", Toast.LENGTH_SHORT).show()
                        } else {
                            showThinkingLevel = true
                        }
                    }
                    MenuItem(Icons.Filled.Refresh, stringResource(R.string.retry)) { retryLastMessage() }
                    MenuItem(Icons.Filled.Save, "封存记忆到角色卡") { handleArchiveMemory() }
                    MenuItem(Icons.Filled.FileOpen, "导入酒馆聊天记录") {
                        chatLogImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                    MenuItem(Icons.Filled.Search, "搜索") { showSearch = true }
                    MenuItem(Icons.Filled.Edit, stringResource(R.string.edit_system_prompt)) { showEditSystemPrompt = true }
                }
            }
            if (isStreaming) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }

    /** 空态：渐变圆底图标 + 引导文案 + 新建按钮 */
    @Composable
    private fun EmptyState(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "开始一段对话",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "与角色、世界书或冒险模式互动",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    /** 冒险模式输入快捷 chips（[语言] [行为] [剧情]） */
    @Composable
    private fun AdventureChipBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("语言", "行为", "剧情").forEach { label ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clickable { insertAdventurePrefix("[$label] ") }
                ) {
                    Text(
                        text = "[$label]",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }

    /** 底部输入栏（原版视觉：24dp 圆角卡片 + 无描边 + 投影 + 文字发送按钮） */
    @Composable
    private fun ComposerBar() {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = { showFilePicker() }) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.attach_file),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 120.dp),
                    placeholder = { Text(inputHint, fontSize = 14.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { sendMessage() }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { sendMessage() },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(stringResource(R.string.btn_send))
                }
            }
        }
    }

    /** 抽屉：渐变头部 + 新对话 + 历史对话 + 分组导航 */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ChatDrawer(onClose: () -> Unit) {
        val ctx = this@ChatActivity
        val conversations = drawerConversations
        val currentId = drawerCurrentId

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 可滚动内容区（分组全部展开时内容超出屏幕可下滑）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
            // 头部（原版风格：应用图标）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "SAChat",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 新对话（默认不高亮，点击后高亮）
            DrawerNavItem(
                icon = Icons.Filled.Add,
                label = "新对话",
                selected = activeNavId == "new_chat",
                indent = false,
                onClick = {
                    activeNavId = "new_chat"
                    onClose()
                    startNewConversation()
                }
            )

            // 历史对话（单击切换 / 长按弹出操作菜单；超过 5 条时列表内部可滑动 + 滚动条）
            Text(
                text = "历史对话",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
            )
            Text(
                text = "单击切换 · 长按编辑对话",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
            val convScrollState = rememberScrollState()
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
            ) {
                val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(convScrollState)
                    ) {
                        conversations.forEach { conv ->
                            Text(
                                text = conv.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (conv.id == currentId) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // clip 在 background 之前：让点击波纹跟随圆角形状
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        if (conv.id == currentId) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            ConversationManager.switchTo(conv.id)
                                            activeNavId = ""
                                            refreshCurrentConversation()
                                            onClose()
                                        },
                                        onLongClick = {
                                            actionConv = conv
                                            showConvActions = true
                                        }
                                    )
                                    .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                            )
                        }
                    }
                    // 内容超出容器高度（>5 条）时显示轻量滚动条
                    if (convScrollState.maxValue > 0) {
                        val density = LocalDensity.current
                        val contentHeightPx = convScrollState.maxValue + containerHeightPx
                        val thumbHeightPx =
                            (containerHeightPx * containerHeightPx / contentHeightPx).coerceAtLeast(32f)
                        val thumbOffsetPx = (containerHeightPx - thumbHeightPx) *
                            convScrollState.value / convScrollState.maxValue
                        val thumbHeight = with(density) { thumbHeightPx.toDp() }
                        val thumbOffset = with(density) { thumbOffsetPx.toDp() }
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(thumbHeight)
                                    .offset(y = thumbOffset)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 分组：角色卡
            DrawerGroupHeader("角色卡", "roleplay")
            if (groupExpanded["roleplay"] == true) {
                DrawerNavItem(Icons.Filled.Person, "角色扮演", false, true) { onClose(); characterListLauncher.launch(Intent(ctx, CharacterListActivity::class.java)) }
                DrawerNavItem(Icons.Filled.TravelExplore, "冒险", false, true) { onClose(); adventureLauncher.launch(Intent(ctx, AdventureActivity::class.java)) }
                DrawerNavItem(Icons.Filled.FileOpen, "导入", false, true) { onClose(); jsonImportLauncher.launch(arrayOf("application/json", "*/*")) }
                DrawerNavItem(Icons.Filled.Edit, "管理", false, true) {
                    onClose()
                    characterListLauncher.launch(Intent(ctx, CharacterListActivity::class.java).putExtra("manage_mode", true))
                }
                DrawerNavItem(Icons.Filled.Add, "创建", false, true) { onClose(); characterEditLauncher.launch(Intent(ctx, CharacterEditActivity::class.java)) }
            }

            // 分组：世界书
            DrawerGroupHeader("世界书", "world")
            if (groupExpanded["world"] == true) {
                DrawerNavItem(Icons.Filled.AutoStories, "选择", false, true) { onClose(); worldInfoListLauncher.launch(Intent(ctx, WorldInfoListActivity::class.java)) }
                DrawerNavItem(Icons.Filled.Edit, "管理", false, true) {
                    onClose()
                    worldInfoListLauncher.launch(Intent(ctx, WorldInfoListActivity::class.java).putExtra("manage_mode", true))
                }
                DrawerNavItem(Icons.Filled.FileOpen, "导入", false, true) { onClose(); worldInfoImportLauncher.launch(arrayOf("application/json", "*/*")) }
                DrawerNavItem(Icons.Filled.Add, "创建", false, true) {
                    onClose()
                    selectWorldOnReturn = true
                    worldInfoEditLauncher.launch(Intent(ctx, WorldInfoEditActivity::class.java))
                }
            }

            // 分组：记录
            DrawerGroupHeader("记录", "records")
            if (groupExpanded["records"] == true) {
                DrawerNavItem(Icons.Filled.Favorite, "羁绊档案", false, true) { onClose(); startActivity(Intent(ctx, BondActivity::class.java)) }
            }

            // 分组：其他
            DrawerGroupHeader("其他", "other")
            if (groupExpanded["other"] == true) {
                DrawerNavItem(Icons.Filled.Settings, "设置", false, true) { onClose(); startActivity(Intent(ctx, SettingsActivity::class.java)) }
                DrawerNavItem(Icons.Filled.Info, "关于软件", false, true) { onClose(); startActivity(Intent(ctx, AboutActivity::class.java)) }
                DrawerNavItem(Icons.Filled.MoreVert, "测试崩溃上报", false, true) {
                    onClose()
                    throw NullPointerException("这是测试崩溃，验证上报流程")
                }
            }

            } // ── 可滚动内容区结束 ──

            Text(
                text = appVersionText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
            )
        }
    }

    @Composable
    private fun DrawerGroupHeader(title: String, key: String) {
        val expanded = groupExpanded[key] == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { groupExpanded[key] = !expanded }
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @Composable
    private fun DrawerNavItem(
        icon: ImageVector,
        label: String,
        selected: Boolean,
        indent: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // clip 在 background 之前：点击波纹跟随圆角形状
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(start = if (indent) 24.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }

    @Composable
    private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
            onClick = {
                showPopupMenu = false
                onClick()
            }
        )
    }

    // ───────────────────────── 对话框 ─────────────────────────

    @Composable
    private fun ArchivingDialog() {
        Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("正在分析对话，提炼角色记忆…", fontSize = 14.sp)
                }
            }
        }
    }

    @Composable
    private fun ArchiveConfirmDialog() {
        AlertDialog(
            onDismissRequest = { showArchiveConfirm = false },
            title = { Text("记忆封存确认") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .padding(top = 4.dp)
                ) {
                    ArchiveField("本场对话标题", archiveTitleText, { archiveTitleText = it }, singleLine = true, hint = "会话标题")
                    ArchiveField("与用户关系类型", archiveRelationText, { archiveRelationText = it }, singleLine = true, hint = "例如：生死之交、欢喜冤家")
                    ArchiveField("与用户相处模式", archiveInteractionText, { archiveInteractionText = it }, hint = "角色如何与你互动")
                    ArchiveField("与用户关系底线", archiveBottomLineText, { archiveBottomLineText = it }, hint = "角色的底线")
                    ArchiveField("关键事件（追加到已有事件）", archiveKeyEventsText, { archiveKeyEventsText = it }, hint = "用 - 开头的列表项，每行一条", minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = { applyArchiveFromDialog() }) { Text("确认写入") }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirm = false }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun ArchiveField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        singleLine: Boolean = false,
        hint: String,
        minLines: Int = 1
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, fontSize = 13.sp) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else 8,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
    }

    @Composable
    private fun ThinkingLevelDialog() {
        val levels = arrayOf("low", "medium", "high", "xhigh", "max")
        AlertDialog(
            onDismissRequest = { showThinkingLevel = false },
            title = { Text("选择思考强度") },
            text = {
                Column {
                    levels.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    thinkingEnabled = true
                                    thinkingLevel = level
                                    getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                                        .edit()
                                        .putBoolean(SettingsKeys.KEY_THINKING_ENABLED, true)
                                        .putString(SettingsKeys.KEY_THINKING_LEVEL, level)
                                        .apply()
                                    Toast.makeText(this@ChatActivity, "深度思考已开启（$level）", Toast.LENGTH_SHORT).show()
                                    showThinkingLevel = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = level == thinkingLevel, onClick = null)
                            Text(level, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThinkingLevel = false }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun EditSystemPromptDialog() {
        AlertDialog(
            onDismissRequest = { showEditSystemPrompt = false },
            title = { Text(stringResource(R.string.system_prompt_for_conv)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.hint_system_prompt_vars),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editSystemPromptText,
                        onValueChange = { editSystemPromptText = it },
                        placeholder = { Text(stringResource(R.string.hint_system_prompt), fontSize = 13.sp) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val conv = currentConversation ?: return@TextButton
                    conv.systemPrompt = editSystemPromptText.trim()
                    ConversationManager.save()
                    Toast.makeText(this, R.string.toast_system_prompt_updated, Toast.LENGTH_SHORT).show()
                    showEditSystemPrompt = false
                }) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditSystemPrompt = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    private fun DeleteConfirmDialog() {
        val conv = currentConversation
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(getString(R.string.confirm_delete_msg, conv?.title ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    conv?.let {
                        ConversationManager.delete(it.id)
                        refreshCurrentConversation()
                        Toast.makeText(this, R.string.toast_conversation_deleted, Toast.LENGTH_SHORT).show()
                    }
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    private fun SearchDialog() {
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("搜索聊天记录") },
            text = {
                OutlinedTextField(
                    value = searchKeyword,
                    onValueChange = { searchKeyword = it },
                    placeholder = { Text("输入关键词", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val conv = currentConversation ?: return@TextButton
                    if (conv.messages.isEmpty()) {
                        Toast.makeText(this, "没有可搜索的消息", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val keyword = searchKeyword.trim()
                    if (keyword.isBlank()) return@TextButton
                    val matches = conv.messages.mapIndexedNotNull { index, msg ->
                        if (msg.content.contains(keyword, ignoreCase = true)) {
                            val preview = msg.content.take(80).replace("\n", " ")
                            val role = if (msg.role == Message.ROLE_USER) "用户" else "AI"
                            "$role: $preview" to index
                        } else null
                    }
                    if (matches.isEmpty()) {
                        Toast.makeText(this, "未找到匹配结果", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    searchResults = matches
                    showSearch = false
                    showSearchResults = true
                }) { Text("搜索") }
            },
            dismissButton = {
                TextButton(onClick = { showSearch = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    private fun SearchResultsDialog() {
        AlertDialog(
            onDismissRequest = { showSearchResults = false },
            title = { Text("找到 ${searchResults.size} 条匹配") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .padding(top = 4.dp)
                ) {
                    searchResults.forEach { (label, position) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scrollTargetState.value = position
                                    showSearchResults = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchResults = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    private fun ImportConfirmDialog() {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("导入角色卡") },
            text = {
                Column {
                    Text("名称：$importPendingName", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (importPendingDesc.isNotBlank()) {
                        Text(
                            text = "简介：$importPendingDesc",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = CharacterManager.save(this, importPendingJson)
                    if (id != null) {
                        Toast.makeText(this, "角色「$importPendingName」已导入", Toast.LENGTH_SHORT).show()
                        startCharacterConversation(id)
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                    showImportConfirm = false
                }) { Text("导入并开始聊天") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = CharacterManager.save(this, importPendingJson)
                    if (id != null) {
                        Toast.makeText(this, "角色「$importPendingName」已导入", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                    showImportConfirm = false
                }) { Text("仅导入") }
            }
        )
    }

    @Composable
    private fun WorldImportWarningsDialog() {
        AlertDialog(
            onDismissRequest = { showWorldImportWarnings = false },
            title = { Text("导入完成（部分条目被跳过）") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .padding(top = 4.dp)
                ) {
                    worldImportWarnings.take(5).forEach { warning ->
                        Text(
                            text = warning,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorldImportWarnings = false }) { Text("知道了") }
            }
        )
    }

    // ─── 长按会话操作（删除/清空/重命名/世界书） ──────────────────

    @Composable
    private fun ConvActionsDialog() {
        val conv = actionConv ?: return
        AlertDialog(
            onDismissRequest = { showConvActions = false },
            title = { Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    DialogActionRow(Icons.Filled.Delete, "删除对话", danger = true) {
                        showConvActions = false
                        showDeleteConvConfirm = true
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DialogActionRow(Icons.Filled.Delete, "清空对话") {
                        showConvActions = false
                        showClearConvConfirm = true
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DialogActionRow(Icons.Filled.Edit, "重命名对话") {
                        renameText = conv.title
                        showConvActions = false
                        showRenameDialog = true
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DialogActionRow(Icons.Filled.AutoStories, "世界书设置") {
                        showConvActions = false
                        showWorldSelect = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showConvActions = false }) { Text("取消") }
            }
        )
    }

    @Composable
    private fun DialogActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 15.sp,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }

    @Composable
    private fun DeleteConvConfirmDialog() {
        AlertDialog(
            onDismissRequest = { showDeleteConvConfirm = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(getString(R.string.confirm_delete_msg, actionConv?.title ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    actionConv?.let { conv ->
                        ConversationManager.delete(conv.id)
                        if (conv.id == currentConversation?.id) refreshCurrentConversation()
                    }
                    showDeleteConvConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConvConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    private fun ClearConvConfirmDialog() {
        AlertDialog(
            onDismissRequest = { showClearConvConfirm = false },
            title = { Text("确认清空") },
            text = { Text("确定要清空「${actionConv?.title ?: ""}」的所有消息吗？") },
            confirmButton = {
                TextButton(onClick = {
                    actionConv?.let { conv ->
                        conv.messages.clear()
                        ConversationManager.save()
                        if (conv.id == currentConversation?.id) {
                            messagesState.value = emptyList()
                            refreshCurrentConversation()
                        }
                    }
                    showClearConvConfirm = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConvConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    private fun RenameDialog() {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("新标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actionConv?.let { conv ->
                        val newTitle = renameText.trim()
                        if (newTitle.isNotBlank()) {
                            ConversationManager.rename(conv.id, newTitle)
                            if (conv.id == currentConversation?.id) refreshCurrentConversation()
                        }
                    }
                    showRenameDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    /** 世界书设置：多选（勾选即启用，可同时启用多本），自由决定当前对话启用哪些世界书 */
    @Composable
    private fun WorldSelectDialog() {
        val ctx = this@ChatActivity
        val worlds = remember { WorldInfoManager.list(ctx) }
        val conv = actionConv
        var selectedIds by remember {
            mutableStateOf(conv?.boundWorldIds()?.toSet() ?: emptySet())
        }
        AlertDialog(
            onDismissRequest = { showWorldSelect = false },
            title = { Text("世界书设置") },
            text = {
                Column {
                    Text(
                        text = if (selectedIds.isEmpty()) "未启用任何世界书" else "已启用 ${selectedIds.size} 本",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (worlds.isEmpty()) {
                        Text(
                            text = "还没有世界书，请先到左侧「世界书 → 创建/导入」添加",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    worlds.forEach { world ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (world.id in selectedIds) {
                                        selectedIds - world.id
                                    } else {
                                        selectedIds + world.id
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = world.id in selectedIds,
                                onCheckedChange = null
                            )
                            Text(
                                text = world.name,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { applyWorldSelection(selectedIds.toList()) }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showWorldSelect = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    private fun applyWorldSelection(ids: List<String>) {
        val conv = actionConv ?: return
        conv.setBoundWorlds(ids)
        ConversationManager.save()
        // 无论操作的是否当前对话，都同步刷新当前对话的绑定提示（关闭/切换后提示实时更新）
        refreshCurrentConversation()
        showWorldSelect = false
        Toast.makeText(
            this,
            if (ids.isEmpty()) "已禁用世界书" else "世界书已启用（${ids.size} 本）",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ───────────────────────── 业务逻辑（迁移自旧版） ─────────────────────────

    private fun handleChatLogImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val jsonl = inputStream.bufferedReader().use { it.readText() }
            val result = STChatLogImporter.parse(jsonl)
            result.fold(
                onSuccess = { messages ->
                    val conv = ConversationManager.createNew()
                    conv.title = "导入的对话"
                    conv.messages.clear()
                    conv.messages.addAll(messages)
                    conv.updatedAt = messages.lastOrNull()?.timestamp ?: System.currentTimeMillis()
                    ConversationManager.save()
                    refreshCurrentConversation()
                    Toast.makeText(this, "已导入 ${messages.size} 条消息", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCharacterImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val json = inputStream.bufferedReader().use { it.readText() }

            val card = TavernCardImporter.parse(json)
            if (card.isFailure) {
                Toast.makeText(this, "角色卡格式无效: ${card.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                return
            }

            val tavernCard = card.getOrNull() ?: return
            importPendingName = tavernCard.data.name
            importPendingDesc = tavernCard.data.description.take(200)
            importPendingJson = json
            showImportConfirm = true
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleWorldInfoImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val json = inputStream.bufferedReader().use { it.readText() }
            when (val result = WorldInfoImporter.parse(json)) {
                is WorldInfoImporter.ImportResult.Success -> {
                    WorldInfoManager.saveNew(this, result.info)
                    val warnText = if (result.warnings.isNotEmpty()) "（${result.warnings.size} 条警告）" else ""
                    Toast.makeText(this, "世界书「${result.info.name}」已导入$warnText", Toast.LENGTH_SHORT).show()
                    if (result.warnings.isNotEmpty()) {
                        worldImportWarnings = result.warnings
                        showWorldImportWarnings = true
                    }
                }
                is WorldInfoImporter.ImportResult.Failure -> {
                    Toast.makeText(this, "导入失败：${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAdventureConversation(worldId: String?, roleIds: List<String>, opening: String) {
        if (roleIds.isEmpty()) return
        val worldName = worldId?.let { WorldInfoManager.load(this, it)?.name } ?: ""
        val conv = ConversationManager.createNew()
        conv.title = if (worldName.isNotBlank()) "冒险: $worldName" else "文字冒险"
        conv.setBoundWorlds(listOfNotNull(worldId))
        conv.adventureRoleIds = roleIds
        conv.systemPrompt = ConversationManager.ADVENTURE_DM_PROMPT
        ConversationManager.save()
        refreshCurrentConversation()
        if (opening.isNotBlank()) {
            inputText = opening
            performSend(opening)
        }
    }

    private fun insertAdventurePrefix(prefix: String) {
        val text = inputText
        inputText = if (text.isBlank()) prefix else "$text\n$prefix"
    }

    private fun startNewConversation() {
        ConversationManager.createNew()
        refreshCurrentConversation()
        Toast.makeText(this, R.string.toast_conversation_created, Toast.LENGTH_SHORT).show()
    }

    private fun startCharacterConversation(characterId: String) {
        val card = CharacterManager.loadCard(this, characterId) ?: return

        val assembledPrompt = CharacterManager.assembleSystemPrompt(card)
        val managerConv = ConversationManager.createNew()
        managerConv.title = card.data.name
        managerConv.systemPrompt = assembledPrompt
        managerConv.characterId = characterId

        if (card.data.first_mes.isNotBlank()) {
            val firstMsg = Message(Message.ROLE_ASSISTANT, card.data.first_mes)
            managerConv.messages.add(firstMsg)
        }

        ConversationManager.save()
        refreshCurrentConversation()
    }

    /** 从世界书选择页返回：追加绑定（多本共存） */
    private fun selectWorldInfo(id: String) {
        val conv = currentConversation ?: return
        conv.setBoundWorlds(conv.boundWorldIds() + id)
        ConversationManager.save()
        refreshCurrentConversation()
        Toast.makeText(this, "世界书已启用", Toast.LENGTH_SHORT).show()
    }

    private fun applyBackground() {
        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = sp.getString(SettingsKeys.KEY_BG_MODE, "color") ?: "color"
        if (mode == "image") {
            val base64 = sp.getString(SettingsKeys.KEY_BG_IMAGE, null)
            if (base64 != null) {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels.coerceAtLeast(1)
                    val screenH = dm.heightPixels.coerceAtLeast(1)
                    val scale = sp.getString(SettingsKeys.KEY_BG_SCALE, "fit") ?: "fit"
                    if (scale == "fit") {
                        backgroundDrawable = android.graphics.drawable.BitmapDrawable(resources, centerCrop(bitmap, screenW, screenH))
                    } else {
                        val factor = if (bitmap.width < screenW) screenW.toFloat() / bitmap.width else 1f
                        val scaled = Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * factor).toInt().coerceAtLeast(1),
                            (bitmap.height * factor).toInt().coerceAtLeast(1),
                            true
                        )
                        backgroundDrawable = android.graphics.drawable.BitmapDrawable(resources, scaled).apply {
                            tileModeX = android.graphics.Shader.TileMode.REPEAT
                            tileModeY = android.graphics.Shader.TileMode.REPEAT
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            backgroundDrawable = null
        }
    }

    /** 等比缩放 + 居中裁剪（cover） */
    private fun centerCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = ((scaledW - targetW) / 2).coerceAtLeast(0)
        val y = ((scaledH - targetH) / 2).coerceAtLeast(0)
        val w = minOf(targetW, scaledW - x)
        val h = minOf(targetH, scaledH - y)
        return Bitmap.createBitmap(scaled, x, y, w, h)
    }

    private fun refreshCurrentConversation() {
        currentConversation = ConversationManager.current
        val conv = currentConversation ?: return
        isStreaming = conv.isStreaming
        chatTitle = conv.title

        // 抽屉会话列表保持最新（对应旧版 onDrawerOpened 刷新）
        drawerConversations = ConversationManager.all
        drawerCurrentId = conv.id

        val sp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

        if (conv.characterId != null) {
            val card = CharacterManager.loadCard(this, conv.characterId!!)
            characterAvatarState.value = card?.avatarBase64
            bindingCharacterState.value = card?.data?.name
        } else {
            characterAvatarState.value = sp.getString(SettingsKeys.KEY_AI_AVATAR, null)
            bindingCharacterState.value = null
        }

        userAvatarState.value = sp.getString(SettingsKeys.KEY_USER_AVATAR, null)
        aiNameState.value = sp.getString(SettingsKeys.KEY_AI_NAME, "AI") ?: "AI"
        fontSizeState.value = sp.getInt(SettingsKeys.KEY_FONT_SIZE, 15)

        bindingWorldState.value = conv.boundWorldIds().mapNotNull { WorldInfoManager.load(this, it)?.name }
        systemPromptState.value = conv.systemPrompt
        isAdventure = conv.adventureRoleIds.isNotEmpty()

        isEmptyConversation = conv.messages.isEmpty()
        if (!isEmptyConversation) {
            messagesState.value = conv.messages.toList()
        }
    }

    // ─── 记忆封存（1.4） ──────────────────────────────────────────

    private fun handleArchiveMemory() {
        val conv = currentConversation ?: return
        if (conv.isStreaming) {
            Toast.makeText(this, "等待回复完成后再封存", Toast.LENGTH_SHORT).show()
            return
        }
        if (conv.isArchived) {
            Toast.makeText(this, "本会话记忆已封存", Toast.LENGTH_SHORT).show()
            return
        }
        val characterId = conv.characterId
        if (characterId == null) {
            Toast.makeText(this, "请先绑定角色卡再封存记忆", Toast.LENGTH_SHORT).show()
            return
        }
        val card = CharacterManager.loadCard(this, characterId)
        if (card == null) {
            Toast.makeText(this, "角色卡不存在", Toast.LENGTH_SHORT).show()
            return
        }
        if (conv.messages.none { it.role == Message.ROLE_USER }) {
            Toast.makeText(this, "还没有可分析的对话内容", Toast.LENGTH_SHORT).show()
            return
        }

        showArchiving = true

        val prefs = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val providerMode = prefs.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM
        val kiloModel = prefs.getString(SettingsKeys.KEY_KILO_MODEL, SettingsKeys.KILO_MODEL)
            ?: SettingsKeys.KILO_MODEL
        val zenModel = prefs.getString(SettingsKeys.KEY_ZEN_MODEL, SettingsKeys.ZEN_MODEL_DEFAULT)
            ?: SettingsKeys.ZEN_MODEL_DEFAULT

        Thread {
            val result = MemoryArchiver.analyze(
                apiConfig = apiConfig,
                conversation = conv,
                card = card,
                thinkingLevel = if (thinkingEnabled) thinkingLevel else null,
                providerMode = providerMode,
                kiloModelOption = kiloModel,
                zenModelOption = zenModel
            )
            runOnUiThread {
                showArchiving = false
                result.fold(
                    onSuccess = { archive -> startArchiveConfirm(conv, archive) },
                    onFailure = { e ->
                        Toast.makeText(this, "记忆分析失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }.start()
    }

    private fun startArchiveConfirm(conv: Conversation, archive: MemoryArchiver.ArchiveResult) {
        val currentCard = CharacterManager.loadCard(this, conv.characterId!!)
        if (currentCard == null) {
            Toast.makeText(this, "角色卡加载失败，无法封存", Toast.LENGTH_LONG).show()
            return
        }
        val fields = currentCard.data.getCharacterFields()

        archiveConv = conv
        archiveCard = currentCard
        archiveBaseFields = fields
        archiveTitleText = archive.sessionTitle.ifBlank { conv.title }
        archiveRelationText = archive.userRelationType.ifBlank { fields.userRelationType }
        archiveInteractionText = archive.userInteractionModel.ifBlank { fields.userInteractionModel }
        archiveBottomLineText = archive.userRelationBottomLine.ifBlank { fields.userRelationBottomLine }
        // 关键事件：新事件在前 + 已有事件追加在后
        archiveKeyEventsText = listOf(archive.keyEvents.trim(), fields.keyEvents.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
        showArchiveConfirm = true
    }

    private fun applyArchiveFromDialog() {
        val conv = archiveConv ?: return
        val card = archiveCard ?: return
        val fields = archiveBaseFields ?: return

        val updated = CharacterFields(
            age = fields.age, gender = fields.gender, race = fields.race,
            birthplace = fields.birthplace, occupation = fields.occupation, socialClass = fields.socialClass,
            identityTags = fields.identityTags,
            heightBuild = fields.heightBuild, iconicFeatures = fields.iconicFeatures,
            clothingStyle = fields.clothingStyle, overallVibe = fields.overallVibe,
            externalPersonality = fields.externalPersonality, internalPersonality = fields.internalPersonality,
            coreDesire = fields.coreDesire, fearWeakness = fields.fearWeakness,
            moralValues = fields.moralValues, quirk = fields.quirk,
            skills = fields.skills, backgroundStory = fields.backgroundStory,
            relationships = fields.relationships, speakingStyle = fields.speakingStyle,
            typicalReactions = fields.typicalReactions,
            userRelationType = archiveRelationText.trim(),
            userInteractionModel = archiveInteractionText.trim(),
            userRelationBottomLine = archiveBottomLineText.trim(),
            keyEvents = archiveKeyEventsText.trim()
        )
        applyArchive(conv, card, updated, archiveTitleText.trim())
        showArchiveConfirm = false
    }

    private fun applyArchive(
        conv: Conversation,
        card: TavernCard,
        fields: CharacterFields,
        newTitle: String
    ) {
        val newData = card.data.withCharacterFields(fields)
        val newCard = TavernCard(
            spec = card.spec,
            spec_version = card.spec_version,
            data = newData,
            avatarBase64 = card.avatarBase64
        )
        val json = Gson().toJson(newCard)
        val ok = CharacterManager.overwrite(this, conv.characterId!!, json)

        if (ok) {
            conv.isArchived = true
            if (newTitle.isNotBlank()) conv.title = newTitle
            ConversationManager.save()
            refreshCurrentConversation()
            Toast.makeText(this, "记忆已封存到角色卡，会话已锁定", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "写回角色卡失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 发送消息 ──────────────────────────────────────────────

    private fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() && pendingAttachmentData == null) return
        performSend(text)
    }

    private fun performSend(text: String) {
        val conv = currentConversation ?: return
        if (conv.isStreaming) {
            Toast.makeText(this, R.string.toast_sending, Toast.LENGTH_SHORT).show()
            return
        }

        inputText = ""

        // Free Gateway: 发送时统一显示"正在获取模型"，收到响应后更新
        modelInfoText = "模型：正在获取模型…"

        ConversationManager.sendMessage(
            apiConfig = apiConfig,
            conversationId = conv.id,
            inputText = text,
            attachmentType = pendingAttachmentType,
            attachmentData = pendingAttachmentData,
            attachmentName = pendingAttachmentName,
            thinkingLevel = if (thinkingEnabled) thinkingLevel else null,
            onModelInfo = { model, provider ->
                runOnUiThread {
                    modelInfoText = if (model == null) {
                        "模型：获取失败"
                    } else {
                        val label = if (provider.isNullOrBlank()) model else "$provider · $model"
                        "模型：$label"
                    }
                }
            },
            onUiUpdate = {
                runOnUiThread { refreshCurrentConversation() }
            }
        )

        pendingAttachmentType = null
        pendingAttachmentData = null
        pendingAttachmentName = null
        inputHint = getString(R.string.hint_input)
    }

    private fun retryLastMessage() {
        val conv = currentConversation ?: return
        if (conv.isStreaming) {
            Toast.makeText(this, R.string.toast_sending, Toast.LENGTH_SHORT).show()
            return
        }

        val lastUserIdx = conv.messages.indexOfLast { it.role == Message.ROLE_USER }
        if (lastUserIdx < 0) {
            Toast.makeText(this, "没有可重试的消息", Toast.LENGTH_SHORT).show()
            return
        }

        val lastUserText = conv.messages[lastUserIdx].content

        val hasError = conv.messages.size > lastUserIdx + 1
            && conv.messages.last().role == Message.ROLE_ASSISTANT
            && conv.messages.last().content.contains("[错误:")

        if (!hasError) {
            inputText = lastUserText
            return
        }

        conv.messages.removeAt(conv.messages.size - 1)
        conv.messages.removeAt(lastUserIdx)
        messagesState.value = conv.messages.toList()
        performSend(lastUserText)
    }

    // ─── 文件上传 ──────────────────────────────────────────────

    private fun showFilePicker() {
        filePickerLauncher.launch(arrayOf("text/plain", "image/jpeg", "image/png", "image/webp"))
    }

    private fun handleFileSelected(uri: Uri) {
        val fileName = getFileName(uri) ?: "unknown"
        val mimeType = contentResolver.getType(uri) ?: ""

        Toast.makeText(this, R.string.toast_file_reading, Toast.LENGTH_SHORT).show()

        try {
            when {
                mimeType.startsWith("text/") || fileName.lowercase().endsWith(".txt") -> {
                    val inputStream = contentResolver.openInputStream(uri)
                    val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                    inputStream?.close()

                    if (text.length > 50000) {
                        Toast.makeText(this, R.string.file_too_large, Toast.LENGTH_SHORT).show()
                        return
                    }

                    pendingAttachmentType = ATTACH_TYPE_TEXT
                    pendingAttachmentData = text
                    pendingAttachmentName = fileName
                    inputHint = "[TXT] $fileName | ${getString(R.string.hint_input)}"
                    Toast.makeText(this, R.string.toast_file_attached, Toast.LENGTH_SHORT).show()
                }
                mimeType.startsWith("image/") -> {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (bitmap == null) {
                        Toast.makeText(this, R.string.file_unsupported, Toast.LENGTH_SHORT).show()
                        return
                    }

                    val maxSize = 2048
                    val scale = Math.min(
                        maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height
                    ).coerceAtMost(1f)
                    val scaledBitmap = if (scale < 1f) {
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                    } else bitmap

                    val outputStream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    val imageBytes = outputStream.toByteArray()
                    outputStream.close()

                    if (imageBytes.size > 20 * 1024 * 1024) {
                        Toast.makeText(this, R.string.file_too_large, Toast.LENGTH_SHORT).show()
                        return
                    }

                    val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    pendingAttachmentType = ATTACH_TYPE_IMAGE
                    pendingAttachmentData = base64
                    pendingAttachmentName = fileName
                    inputHint = "[IMG] $fileName | ${getString(R.string.hint_input)}"
                    Toast.makeText(this, R.string.toast_file_attached, Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(this, R.string.file_unsupported, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    // ─── 配置 ──────────────────────────────────────────────────

    private fun loadConfig() {
        val sp = getSharedPreferences(ApiConfig.PREFS_NAME, Context.MODE_PRIVATE)
        apiConfig = ApiConfig(
            apiUrl = sp.getString(ApiConfig.KEY_URL, "") ?: "",
            apiKey = sp.getString(ApiConfig.KEY_KEY, "") ?: "",
            model = sp.getString(ApiConfig.KEY_MODEL, "gpt-3.5-turbo") ?: "gpt-3.5-turbo"
        )
    }
}
