package www.cetool.com

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GravityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import www.cetool.com.importer.STChatLogImporter
import www.cetool.com.importer.TavernCardImporter
import www.cetool.com.importer.WorldInfoImporter
import www.cetool.com.manager.CharacterManager
import www.cetool.com.manager.MemoryArchiver
import com.google.gson.Gson
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.SettingsKeys
import www.cetool.com.model.ApiConfig
import www.cetool.com.model.CharacterFields
import www.cetool.com.model.Conversation
import www.cetool.com.model.Message
import www.cetool.com.model.Message.Companion.ATTACH_TYPE_IMAGE
import www.cetool.com.model.Message.Companion.ATTACH_TYPE_TEXT
import java.io.ByteArrayOutputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var fabDrawer: FloatingActionButton
    private lateinit var ibMenu: ImageButton
    private lateinit var tvChatTitle: TextView
    private lateinit var messagesComposeView: androidx.compose.ui.platform.ComposeView
    private lateinit var adventureChipBar: android.widget.LinearLayout
    private lateinit var chipLanguage: com.google.android.material.chip.Chip
    private lateinit var chipAction: com.google.android.material.chip.Chip
    private lateinit var chipPlot: com.google.android.material.chip.Chip
    private lateinit var etInput: TextInputEditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnAttach: ImageButton
    private lateinit var inputCard: MaterialCardView
    private lateinit var streamingProgress: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var tvModelInfo: TextView

    // ─── 消息列表状态（Compose，1.3 迁移） ───
    private val messagesState = androidx.compose.runtime.mutableStateOf<List<Message>>(emptyList())
    private val characterAvatarState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val userAvatarState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val aiNameState = androidx.compose.runtime.mutableStateOf("AI")
    private val fontSizeState = androidx.compose.runtime.mutableStateOf(15)
    private val systemPromptState = androidx.compose.runtime.mutableStateOf("")
    private val bindingCharacterState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val bindingWorldState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val scrollTargetState = androidx.compose.runtime.mutableStateOf<Int?>(null)

    private lateinit var apiConfig: ApiConfig
    private lateinit var rootLayout: ConstraintLayout

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

    private var activeNavId = R.id.nav_new_chat
    private val navIds = listOf(
        R.id.nav_new_chat, R.id.nav_roleplay, R.id.nav_adventure, R.id.nav_import, R.id.nav_manage, R.id.nav_create,
        R.id.nav_world_info, R.id.nav_manage_world, R.id.nav_import_world, R.id.nav_create_world,
        R.id.nav_bond, R.id.nav_settings, R.id.nav_about, R.id.nav_crash_test
    )

    private lateinit var rvConversations: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        drawerLayout = findViewById(R.id.drawerLayout)
        fabDrawer = findViewById(R.id.fabDrawer)
        ibMenu = findViewById(R.id.ibMenu)
        tvChatTitle = findViewById(R.id.tvChatTitle)
        rootLayout = findViewById(R.id.rootLayout)
        messagesComposeView = findViewById(R.id.messagesComposeView)
        adventureChipBar = findViewById(R.id.adventureChipBar)
        chipLanguage = findViewById(R.id.chipLanguage)
        chipAction = findViewById(R.id.chipAction)
        chipPlot = findViewById(R.id.chipPlot)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        inputCard = findViewById(R.id.inputCard)
        streamingProgress = findViewById(R.id.streamingProgress)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvModelInfo = findViewById(R.id.tvModelInfo)

        // 状态栏适配：顶栏整体增高（56dp + 状态栏高），内容区保持完整，标题与状态栏不重叠
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drawerLayout)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topBar = view.findViewById<View>(R.id.topBar)
            val lp = topBar.layoutParams as ConstraintLayout.LayoutParams
            lp.height = (resources.displayMetrics.density.toInt() * 56) + systemBars.top
            topBar.layoutParams = lp
            topBar.updatePadding(top = systemBars.top)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val params = inputCard.layoutParams as ConstraintLayout.LayoutParams
            params.bottomMargin = imeHeight + resources.displayMetrics.density.toInt() * 8
            inputCard.layoutParams = params
            insets
        }

        ConversationManager.init(this)
        loadConfig()
        setupDrawer()

        rvConversations = findViewById(R.id.rvConversations)
        rvConversations.layoutManager = LinearLayoutManager(this)
        conversationAdapter = ConversationAdapter()
        rvConversations.adapter = conversationAdapter
        refreshDrawerConversations()

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                refreshDrawerConversations()
            }
        })

        // 消息列表（Compose，1.3）：替代 RecyclerView + MessageAdapter
        messagesComposeView.setContent {
            www.cetool.com.ui.theme.SAChatTheme {
                www.cetool.com.ui.components.MessageList(
                    messages = messagesState.value,
                    characterAvatar = characterAvatarState.value,
                    userAvatar = userAvatarState.value,
                    aiName = aiNameState.value,
                    fontSize = fontSizeState.value,
                    systemPromptPreview = systemPromptState.value,
                    bindingCharacterName = bindingCharacterState.value,
                    bindingWorldName = bindingWorldState.value,
                    scrollTarget = scrollTargetState.value,
                    onClearScrollTarget = { scrollTargetState.value = null },
                    onCharacterBindingClick = {
                        characterListLauncher.launch(Intent(this, CharacterListActivity::class.java))
                    },
                    onWorldBindingClick = {
                        worldInfoListLauncher.launch(Intent(this, WorldInfoListActivity::class.java))
                    },
                    onCardClick = { cardId ->
                        // 聊天内角色卡标记：打开角色编辑查看
                        val intent = Intent(this, CharacterEditActivity::class.java)
                        intent.putExtra("character_id", cardId)
                        startActivity(intent)
                    }
                )
            }
        }

        btnSend.setOnClickListener { sendMessage() }
        btnAttach.setOnClickListener { showFilePicker() }
        setupAdventureChips()
        fabDrawer.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        ibMenu.setOnClickListener { showPopupMenu() }

        ConversationManager.onConversationsChanged = {
            runOnUiThread {
                refreshCurrentConversation()
                refreshDrawerConversations()
            }
        }
        ConversationManager.onCurrentMessageUpdated = { convId ->
            runOnUiThread {
                if (convId == currentConversation?.id) {
                    currentConversation?.let { conv ->
                        messagesState.value = conv.messages.toList()
                    }
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
        // Free Gateway: 进入页面不显示模型信息，发送消息后再显示（tvModelInfo 默认隐藏）
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
        applyBackground()
    }

    private fun setupDrawer() {
        // 分组默认收起：角色卡、世界书、记录；其他默认展开
        setupDrawerGroup(R.id.headerRoleplay, R.id.groupRoleplay, R.id.chevRoleplay, expanded = false)
        setupDrawerGroup(R.id.headerWorld, R.id.groupWorld, R.id.chevWorld, expanded = false)
        setupDrawerGroup(R.id.headerRecords, R.id.groupRecords, R.id.chevRecords, expanded = false)
        setupDrawerGroup(R.id.headerOther, R.id.groupOther, R.id.chevOther, expanded = true)

        for (id in navIds) {
            findViewById<View>(id).setOnClickListener {
                setNavItemActive(id)
                drawerLayout.closeDrawers()
                handleNavAction(id)
            }
        }
        setNavItemActive(activeNavId)
    }

    /** MD3 风格：选中项显示 secondaryContainer 药丸高亮，图标与文字变为 onSecondaryContainer */
    private fun setNavItemActive(id: Int) {
        activeNavId = id
        val pill = activePill()
        val ripple = rippleDrawable()
        val activeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
        val inactiveColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        for (navId in navIds) {
            val row = findViewById<View>(navId) as ViewGroup
            val isActive = navId == id
            row.background = if (isActive) pill else null
            row.foreground = ripple
            val tint = if (isActive) activeColor else inactiveColor
            (row.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(tint)
            (row.getChildAt(1) as TextView).setTextColor(tint)
        }
    }

    private fun activePill(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (resources.displayMetrics.density * 24).toInt().toFloat()
            setColor(MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorSecondaryContainer, Color.TRANSPARENT))
        }
    }

    private fun rippleDrawable(): android.graphics.drawable.Drawable? {
        val tv = TypedValue()
        if (!theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) return null
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    /** 侧边栏历史对话列表：点击标题切换对话；超过 5 条时列表自身滚动 */
    private fun refreshDrawerConversations() {
        if (!::conversationAdapter.isInitialized) return
        conversationAdapter.conversations = ConversationManager.all
        conversationAdapter.currentId = ConversationManager.current.id
        conversationAdapter.notifyDataSetChanged()
        val rowHeight = dp(44)
        val maxHeight = rowHeight * 5
        val target = (conversationAdapter.itemCount * rowHeight).coerceAtMost(maxHeight)
        val lp = rvConversations.layoutParams
        lp.height = target
        rvConversations.layoutParams = lp
    }

    private inner class ConversationAdapter : RecyclerView.Adapter<ConversationAdapter.VH>() {
        var conversations: List<Conversation> = emptyList()
        var currentId: String = ""

        inner class VH(val row: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(this@ChatActivity).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setPadding(dp(24), dp(12), dp(16), dp(12))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val conv = conversations[position]
            holder.row.text = conv.title
            val active = conv.id == currentId
            holder.row.background = if (active) activePill() else null
            holder.row.foreground = rippleDrawable()
            holder.row.setTextColor(
                if (active) MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
                else MaterialColors.getColor(this@ChatActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
            )
            holder.row.setOnClickListener {
                ConversationManager.switchTo(conv.id)
                refreshCurrentConversation()
                drawerLayout.closeDrawers()
            }
        }

        override fun getItemCount(): Int = conversations.size
    }

    private fun setupDrawerGroup(headerId: Int, groupId: Int, chevronId: Int, expanded: Boolean) {
        val header = findViewById<View>(headerId)
        val group = findViewById<View>(groupId)
        val chevron = findViewById<ImageView>(chevronId)
        group.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.setImageResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        header.setOnClickListener {
            val isExpanded = group.visibility == View.VISIBLE
            group.visibility = if (isExpanded) View.GONE else View.VISIBLE
            chevron.setImageResource(if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        }
    }

    private fun handleNavAction(itemId: Int): Boolean {
        return when (itemId) {
            R.id.nav_new_chat -> {
                startNewConversation()
                true
            }
            R.id.nav_roleplay -> {
                // 角色扮演：Compose 角色卡片列表选择
                characterListLauncher.launch(Intent(this, CharacterListActivity::class.java))
                true
            }
            R.id.nav_adventure -> {
                // 文字冒险（2.2）：配置页 → DM 对话
                adventureLauncher.launch(Intent(this, AdventureActivity::class.java))
                true
            }
            R.id.nav_world_info -> {
                // 世界书：Compose 卡片列表选择
                worldInfoListLauncher.launch(Intent(this, WorldInfoListActivity::class.java))
                true
            }
            R.id.nav_manage_world -> {
                worldInfoListLauncher.launch(
                    Intent(this, WorldInfoListActivity::class.java).putExtra("manage_mode", true)
                )
                true
            }
            R.id.nav_create_world -> {
                selectWorldOnReturn = true
                worldInfoEditLauncher.launch(Intent(this, WorldInfoEditActivity::class.java))
                true
            }
            R.id.nav_import_world -> {
                worldInfoImportLauncher.launch(arrayOf("application/json", "*/*"))
                true
            }
            R.id.nav_import -> {
                jsonImportLauncher.launch(arrayOf("application/json", "*/*"))
                true
            }
            R.id.nav_manage -> {
                characterListLauncher.launch(
                    Intent(this, CharacterListActivity::class.java).putExtra("manage_mode", true)
                )
                true
            }
            R.id.nav_create -> {
                characterEditLauncher.launch(Intent(this, CharacterEditActivity::class.java))
                true
            }
            R.id.nav_bond -> {
                startActivity(Intent(this, BondActivity::class.java))
                true
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.nav_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            R.id.nav_crash_test -> {
                throw NullPointerException("这是测试崩溃，验证上报流程")
            }
            else -> false
        }
    }

    private var selectWorldOnReturn = false

    private val worldInfoListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val wid = result.data?.getStringExtra("world_info_id")
            if (wid != null) {
                selectWorldInfo(wid)
            }
        }
    }

    private val worldInfoEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val wid = result.data?.getStringExtra("world_info_id")
            if (wid != null && selectWorldOnReturn) {
                selectWorldInfo(wid)
            }
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
            if (characterId != null) {
                startCharacterConversation(characterId)
            }
        }
    }

    // ─── 文字冒险（2.2） ──────────────────────────────────────────

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

    private fun startAdventureConversation(worldId: String?, roleIds: List<String>, opening: String) {
        if (roleIds.isEmpty()) return
        val worldName = worldId?.let { WorldInfoManager.load(this, it)?.name } ?: ""
        val conv = ConversationManager.createNew()
        conv.title = if (worldName.isNotBlank()) "冒险: $worldName" else "文字冒险"
        conv.worldInfoId = worldId
        conv.adventureRoleIds = roleIds
        conv.systemPrompt = ConversationManager.ADVENTURE_DM_PROMPT
        ConversationManager.save()
        refreshCurrentConversation()
        if (opening.isNotBlank()) {
            etInput.setText(opening)
            sendMessage()
        }
    }

    private fun setupAdventureChips() {
        chipLanguage.setOnClickListener { insertAdventurePrefix("[语言] ") }
        chipAction.setOnClickListener { insertAdventurePrefix("[行为] ") }
        chipPlot.setOnClickListener { insertAdventurePrefix("[剧情] ") }
    }

    private fun insertAdventurePrefix(prefix: String) {
        val text = etInput.text?.toString().orEmpty()
        val newText = if (text.isBlank()) prefix else "$text\n$prefix"
        etInput.setText(newText)
        etInput.setSelection(newText.length)
        etInput.requestFocus()
    }

    private fun updateAdventureChipBar() {
        val isAdventure = currentConversation?.adventureRoleIds?.isNotEmpty() == true
        adventureChipBar.visibility = if (isAdventure) View.VISIBLE else View.GONE
    }

    private val characterEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val characterId = result.data?.getStringExtra("character_id")
            val isEdit = result.data?.getBooleanExtra("is_edit", false) ?: false
            if (characterId != null && !isEdit) {
                startCharacterConversation(characterId)
            }
        }
    }

    // ─── 侧边栏动作 ──────────────────────────────────────────────

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
            val name = tavernCard.data.name
            val desc = tavernCard.data.description.take(200)

            MaterialAlertDialogBuilder(this)
                .setTitle("导入角色卡")
                .setMessage("名称：$name\n\n${desc.let { if (it.isNotBlank()) "简介：$it" else "" }}")
                .setPositiveButton("导入并开始聊天") { _, _ ->
                    val id = CharacterManager.save(this, json)
                    if (id != null) {
                        Toast.makeText(this, "角色「$name」已导入", Toast.LENGTH_SHORT).show()
                        startCharacterConversation(id)
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("仅导入") { _, _ ->
                    val id = CharacterManager.save(this, json)
                    if (id != null) {
                        Toast.makeText(this, "角色「$name」已导入", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── 世界书 ──────────────────────────────────────────────────

    private fun selectWorldInfo(id: String) {
        val conv = currentConversation ?: return
        conv.worldInfoId = id
        ConversationManager.save()
        updateWorldInfoIndicator()
        Toast.makeText(this, "世界书已启用", Toast.LENGTH_SHORT).show()
    }

    private fun updateWorldInfoIndicator() {
        val conv = currentConversation ?: return
        if (conv.worldInfoId != null) {
            val info = WorldInfoManager.load(this, conv.worldInfoId!!)
            supportActionBar?.subtitle = "[世界书] ${info?.name ?: ""}"
        } else {
            supportActionBar?.subtitle = null
        }
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
                    // 用屏幕尺寸而非 rootLayout 尺寸（onCreate 阶段尚未测量，旧实现取到 0 导致背景失效）
                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels.coerceAtLeast(1)
                    val screenH = dm.heightPixels.coerceAtLeast(1)
                    val scale = sp.getString(SettingsKeys.KEY_BG_SCALE, "fit") ?: "fit"
                    if (scale == "fit") {
                        // 适应：等比缩放裁剪填满屏幕（cover），不变形不拉伸
                        rootLayout.background = BitmapDrawable(resources, centerCrop(bitmap, screenW, screenH))
                    } else {
                        // 保持原比例：不足屏宽则等比放大到屏宽，再平铺（避免原图过小）
                        val factor = if (bitmap.width < screenW) screenW.toFloat() / bitmap.width else 1f
                        val scaled = Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * factor).toInt().coerceAtLeast(1),
                            (bitmap.height * factor).toInt().coerceAtLeast(1),
                            true
                        )
                        BitmapDrawable(resources, scaled).apply {
                            tileModeX = android.graphics.Shader.TileMode.REPEAT
                            tileModeY = android.graphics.Shader.TileMode.REPEAT
                        }.let { rootLayout.background = it }
                    }
                } catch (_: Exception) {}
            }
        } else {
            // 自动适应：跟随系统深浅色主题
            rootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.md_theme_surface))
        }
    }

    /** 等比缩放 + 居中裁剪（cover） */
    private fun centerCrop(src: android.graphics.Bitmap, targetW: Int, targetH: Int): android.graphics.Bitmap {
        val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = ((scaledW - targetW) / 2).coerceAtLeast(0)
        val y = ((scaledH - targetH) / 2).coerceAtLeast(0)
        val w = minOf(targetW, scaledW - x)
        val h = minOf(targetH, scaledH - y)
        return android.graphics.Bitmap.createBitmap(scaled, x, y, w, h)
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
                        MaterialAlertDialogBuilder(this)
                            .setTitle("导入完成（部分条目被跳过）")
                            .setMessage(result.warnings.take(5).joinToString("\n"))
                            .setPositiveButton("知道了", null)
                            .show()
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

    // ─── 对话管理 ──────────────────────────────────────────────

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

    private fun showPopupMenu() {
        val popup = PopupMenu(this, ibMenu)
        popup.menuInflater.inflate(R.menu.chat_menu, popup.menu)
        if (!webSearchConfigured) {
            popup.menu.removeItem(R.id.action_toggle_web_search)
        } else {
            popup.menu.findItem(R.id.action_toggle_web_search)?.title =
                if (webSearchEnabled) "关闭联网搜索" else "开启联网搜索"
        }
        popup.menu.findItem(R.id.action_thinking)?.title =
            if (thinkingEnabled) "深度思考：$thinkingLevel" else "深度思考：关闭"
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_switch_conversation -> showConversationSwitcher()
                R.id.action_new_conversation -> startNewConversation()
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_retry -> retryLastMessage()
                R.id.action_archive_memory -> handleArchiveMemory()
                R.id.action_import_chat_log -> {
                    chatLogImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
                R.id.action_toggle_web_search -> {
                    webSearchEnabled = !webSearchEnabled
                    getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(SettingsKeys.KEY_WEB_SEARCH_ENABLED, webSearchEnabled).apply()
                    Toast.makeText(this, if (webSearchEnabled) "联网搜索已开启" else "联网搜索已关闭", Toast.LENGTH_SHORT).show()
                }
                R.id.action_thinking -> {
                    if (thinkingEnabled) {
                        thinkingEnabled = false
                        getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(SettingsKeys.KEY_THINKING_ENABLED, false).apply()
                        Toast.makeText(this, "深度思考已关闭", Toast.LENGTH_SHORT).show()
                    } else {
                        showThinkingLevelDialog()
                    }
                }
                R.id.action_search -> showSearchDialog()
                R.id.action_edit_system_prompt -> showEditSystemPromptDialog()
                R.id.action_clear -> {
                    currentConversation?.messages?.clear()
                    messagesState.value = emptyList()
                    ConversationManager.save()
                }
                R.id.action_delete_conversation -> showDeleteConversationDialog()
            }
            true
        }
        popup.show()
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

        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("正在分析对话，提炼角色记忆…")
            setCancelable(false)
        }
        progressDialog.show()

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
                progressDialog.dismiss()
                result.fold(
                    onSuccess = { archive -> showArchiveConfirmDialog(conv, archive) },
                    onFailure = { e ->
                        Toast.makeText(this, "记忆分析失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }.start()
    }

    private fun showArchiveConfirmDialog(
        conv: Conversation,
        archive: MemoryArchiver.ArchiveResult
    ) {
        val currentCard = CharacterManager.loadCard(this, conv.characterId!!)
        if (currentCard == null) {
            Toast.makeText(this, "角色卡加载失败，无法封存", Toast.LENGTH_LONG).show()
            return
        }
        val fields = currentCard.data.getCharacterFields()

        val view = layoutInflater.inflate(R.layout.dialog_archive_confirm, null)
        val etTitle = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etArchiveTitle)
        val etRelationType = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etArchiveRelationType)
        val etInteraction = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etArchiveInteractionModel)
        val etBottomLine = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etArchiveBottomLine)
        val etKeyEvents = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etArchiveKeyEvents)

        etTitle.setText(archive.sessionTitle.ifBlank { conv.title })
        etRelationType.setText(archive.userRelationType.ifBlank { fields.userRelationType })
        etInteraction.setText(archive.userInteractionModel.ifBlank { fields.userInteractionModel })
        etBottomLine.setText(archive.userRelationBottomLine.ifBlank { fields.userRelationBottomLine })
        // 关键事件：新事件在前 + 已有事件追加在后
        val mergedEvents = listOf(archive.keyEvents.trim(), fields.keyEvents.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
        etKeyEvents.setText(mergedEvents)

        MaterialAlertDialogBuilder(this)
            .setTitle("记忆封存确认")
            .setView(view)
            .setPositiveButton("确认写入") { _, _ ->
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
                    userRelationType = etRelationType.text?.toString()?.trim() ?: "",
                    userInteractionModel = etInteraction.text?.toString()?.trim() ?: "",
                    userRelationBottomLine = etBottomLine.text?.toString()?.trim() ?: "",
                    keyEvents = etKeyEvents.text?.toString()?.trim() ?: ""
                )
                applyArchive(conv, currentCard, updated, etTitle.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyArchive(
        conv: Conversation,
        card: www.cetool.com.model.TavernCard,
        fields: www.cetool.com.model.CharacterFields,
        newTitle: String
    ) {
        val newData = card.data.withCharacterFields(fields)
        val newCard = www.cetool.com.model.TavernCard(
            spec = card.spec,
            spec_version = card.spec_version,
            data = newData,
            avatarBase64 = card.avatarBase64
        )
        val json = com.google.gson.Gson().toJson(newCard)
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

    private fun showThinkingLevelDialog() {
        val levels = arrayOf("low", "medium", "high", "xhigh", "max")
        MaterialAlertDialogBuilder(this)
            .setTitle("选择思考强度")
            .setSingleChoiceItems(levels, levels.indexOf(thinkingLevel).coerceAtLeast(0)) { _, which ->
                thinkingEnabled = true
                thinkingLevel = levels[which]
                getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(SettingsKeys.KEY_THINKING_ENABLED, true)
                    .putString(SettingsKeys.KEY_THINKING_LEVEL, levels[which])
                    .apply()
                Toast.makeText(this, "深度思考已开启（${levels[which]}）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    // ─── 对话管理 ──────────────────────────────────────────────

    private fun refreshCurrentConversation() {
        currentConversation = ConversationManager.current
        val conv = currentConversation ?: return
        streamingProgress.visibility = if (conv.isStreaming) View.VISIBLE else View.GONE
        tvChatTitle.text = conv.title

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

        bindingWorldState.value = conv.worldInfoId?.let { WorldInfoManager.load(this, it)?.name }
        systemPromptState.value = conv.systemPrompt
        updateAdventureChipBar()

        val isEmpty = conv.messages.isEmpty()
        tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        messagesComposeView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (!isEmpty) {
            messagesState.value = conv.messages.toList()
        }
    }

    private fun showConversationSwitcher() {
        val allConversations = ConversationManager.all
        if (allConversations.isEmpty()) return
        val titles = allConversations.mapIndexed { index, conv ->
            val mark = if (conv.isStreaming) " ..." else ""
            val active = if (index == ConversationManager.currentIndex_) " *" else ""
            "${conv.title}$mark$active"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.switch_conversation)
            .setItems(titles) { _, which ->
                val targetConv = allConversations[which]
                ConversationManager.switchTo(targetConv.id)
                refreshCurrentConversation()
            }
            .setNeutralButton(R.string.new_conversation) { _, _ ->
                startNewConversation()
            }
            .setPositiveButton(R.string.cancel, null)
            .show()
    }

    private fun showEditSystemPromptDialog() {
        val conv = currentConversation ?: return
        val input = TextInputEditText(this)
        input.setText(conv.systemPrompt)
        input.setHint(R.string.hint_system_prompt)
        input.minHeight = 120
        input.gravity = android.view.Gravity.TOP

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.system_prompt_for_conv)
            .setMessage(R.string.hint_system_prompt_vars)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = input.text?.toString()?.trim() ?: ""
                conv.systemPrompt = text
                ConversationManager.save()
                Toast.makeText(this, R.string.toast_system_prompt_updated, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConversationDialog() {
        val conv = currentConversation ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.confirm_delete_msg, conv.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                ConversationManager.delete(conv.id)
                refreshCurrentConversation()
                Toast.makeText(this, R.string.toast_conversation_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── 搜索 ──────────────────────────────────────────────────

    private fun showSearchDialog() {
        val conv = currentConversation ?: return
        if (conv.messages.isEmpty()) {
            Toast.makeText(this, "没有可搜索的消息", Toast.LENGTH_SHORT).show()
            return
        }

        val input = TextInputEditText(this)
        input.setHint("输入关键词")

        MaterialAlertDialogBuilder(this)
            .setTitle("搜索聊天记录")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val keyword = input.text?.toString()?.trim() ?: ""
                if (keyword.isBlank()) return@setPositiveButton

                val matches = conv.messages.mapIndexedNotNull { index, msg ->
                    if (msg.content.contains(keyword, ignoreCase = true)) {
                        val preview = msg.content.take(80).replace("\n", " ")
                        val role = if (msg.role == Message.ROLE_USER) "用户" else "AI"
                        "$role: $preview" to index
                    } else null
                }

                if (matches.isEmpty()) {
                    Toast.makeText(this, "未找到匹配结果", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val labels = matches.map { it.first }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("找到 ${matches.size} 条匹配")
                    .setItems(labels) { _, which ->
                        val position = matches[which].second
                        scrollTargetState.value = position
                    }
                    .setPositiveButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── 发送消息 ──────────────────────────────────────────────

    private fun sendMessage() {
        val text = etInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty() && pendingAttachmentData == null) return
        performSend(text)
    }

    private fun performSend(text: String) {
        val conv = currentConversation ?: return
        if (conv.isStreaming) {
            Toast.makeText(this, R.string.toast_sending, Toast.LENGTH_SHORT).show()
            return
        }

        etInput.text?.clear()

        // Free Gateway: 发送时统一显示"正在获取模型"，收到响应后更新
        tvModelInfo.visibility = View.VISIBLE
        tvModelInfo.text = "模型：正在获取模型…"

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
                    if (model == null) {
                        // 响应中未返回 model 字段
                        tvModelInfo.text = "模型：获取失败"
                    } else {
                        val label = if (provider.isNullOrBlank()) model else "$provider · $model"
                        tvModelInfo.text = "模型：$label"
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
        etInput.setHint(getString(R.string.hint_input))
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
            etInput.setText(lastUserText)
            etInput.setSelection(lastUserText.length)
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
                    etInput.setHint("[TXT] $fileName | ${getString(R.string.hint_input)}")
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
                    etInput.setHint("[IMG] $fileName | ${getString(R.string.hint_input)}")
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
