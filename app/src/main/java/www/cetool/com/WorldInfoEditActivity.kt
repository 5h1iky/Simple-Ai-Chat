package www.cetool.com

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import www.cetool.com.manager.BROKEN_ENTRY_NAME
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import www.cetool.com.ui.components.FoldableSection
import www.cetool.com.ui.components.SectionCard
import www.cetool.com.ui.components.TagRow
import www.cetool.com.ui.theme.SAChatTheme
import java.util.UUID

private val POSITION_OPTIONS = listOf(
    "before_char_defs",
    "after_system_prompt",
    "before_example_messages",
    "after_example_messages",
    "at_depth"
)

/** 注入位置显示名（跟随 UI 语言） */
private fun positionLabel(context: android.content.Context, position: String): String {
    val res = when (position) {
        "before_char_defs" -> R.string.pos_before_char_defs
        "after_system_prompt" -> R.string.pos_after_system_prompt
        "before_example_messages" -> R.string.pos_before_examples
        "after_example_messages" -> R.string.pos_after_examples
        "at_depth" -> R.string.pos_at_depth
        else -> return position
    }
    return context.getString(res)
}

/**
 * 世界书编辑（Compose）：头部设置 + 条目卡片列表 + 全屏条目编辑。
 */
class WorldInfoEditActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    private var editId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editId = intent.getStringExtra("world_info_id")

        var name by mutableStateOf("")
        var description by mutableStateOf("")
        var enabled by mutableStateOf(true)
        // SnapshotStateList 可在组合外创建，组合内读取即响应
        val entries = mutableStateListOf<WorldEntry>()

        editId?.let { id ->
            WorldInfoManager.load(this, id)?.let { info ->
                name = info.name
                description = info.description
                enabled = info.enabled
                entries.clear()
                entries.addAll(info.entries)
            } ?: run {
                // P2：文件存在但解析失败时绝不能按「空书」打开让用户保存 ——
                // 那会把可能只是一处损坏的原文件覆盖成 0 条目（不可逆数据丢失）。
                if (WorldInfoManager.rawJson(this, id) != null) {
                    Toast.makeText(this, getString(R.string.world_edit_load_failed), Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
            }
        }

        setContent {
            SAChatTheme {
                WorldInfoEditScreen(
                    name = name,
                    description = description,
                    enabled = enabled,
                    onEnabledChange = { enabled = it },
                    entries = entries,
                    onNameChange = { name = it },
                    onDescriptionChange = { description = it },
                    onAddEntry = { entries.add(WorldEntry(id = UUID.randomUUID().toString().take(8))) },
                    onUpdateEntry = { index, entry -> if (index in entries.indices) entries[index] = entry },
                    onDeleteEntry = { index -> if (index in entries.indices) entries.removeAt(index) },
                    onBack = { finish() },
                    onSave = {
                        val info = WorldInfo(
                            id = editId ?: UUID.randomUUID().toString().take(8),
                            name = name.trim(),
                            description = description.trim(),
                            // B4：书级 enabled 由本页「启用本书」开关写入（引擎 WorldInfoEngine.scan 会消费）
                            enabled = enabled,
                            entries = entries.toMutableList()
                        )
                        if (info.name.isBlank()) {
                            Toast.makeText(this, getString(R.string.world_edit_name_empty), Toast.LENGTH_SHORT).show()
                            return@WorldInfoEditScreen
                        }
                        // M7：必须检查写盘结果 —— 旧实现忽略返回值后无条件提示「已保存」
                        // 并 finish()，磁盘满/IO 异常时用户以为保存成功，内容其实全丢。
                        // 对照 CharacterEditActivity 的 C4 修复风格：整体兜异常，
                        // 失败明确报错且**不 finish**，保住正在编辑的内容。
                        val savedId = try {
                            if (editId != null) {
                                if (WorldInfoManager.overwrite(this, editId!!, info)) editId else null
                            } else {
                                WorldInfoManager.saveNewOrNull(this, info)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                        if (savedId == null) {
                            Toast.makeText(this, "保存失败：写入文件出错", Toast.LENGTH_SHORT).show()
                            return@WorldInfoEditScreen
                        }
                        Toast.makeText(this, getString(R.string.world_edit_saved, info.name), Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK, Intent().putExtra("world_info_id", savedId))
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldInfoEditScreen(
    name: String,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    entries: List<WorldEntry>,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAddEntry: () -> Unit,
    onUpdateEntry: (Int, WorldEntry) -> Unit,
    onDeleteEntry: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    // B23：长按条目不再立即删除，先弹出确认框（可滚动列表里长按极易误触）
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.world_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onSave) { Text(stringResource(R.string.btn_save_short)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(stringResource(R.string.world_edit_basic)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.world_edit_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.world_edit_desc)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                // B4：书级启用开关（WorldInfo.enabled；停用的书引擎直接跳过）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.world_edit_enabled_book),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (!enabled) {
                    Text(
                        text = stringResource(R.string.world_edit_book_disabled_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            FoldableSection(
                title = stringResource(R.string.world_edit_tips_title),
                preview = stringResource(R.string.world_edit_tips_preview),
                initiallyExpanded = false
            ) {
                Text(
                    text = stringResource(R.string.world_edit_tips_content),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(stringResource(R.string.world_edit_entries, entries.size)) {
                val listContext = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.world_edit_entry_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onAddEntry) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.world_edit_add_entry))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.world_edit_add))
                    }
                }
                entries.forEachIndexed { index, entry ->
                    EntryCard(
                        entry = entry,
                        onClick = {
                            // B26：损坏条目不能当作真实数据编辑（写回会覆盖原始 JSON 节点），
                            // 只允许长按删除；点按时给出提示。
                            if (isBrokenEntry(entry)) {
                                Toast.makeText(
                                    listContext,
                                    listContext.getString(R.string.world_edit_broken_not_editable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                editingIndex = index
                            }
                        },
                        // B23：长按只请求删除，确认后再真正删除，避免滚动时误触
                        onLongClick = { pendingDeleteIndex = index }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    editingIndex?.let { index ->
        // B26：损坏条目永远不进编辑弹窗（写回会用哨兵字段顶掉原始 JSON 节点）
        if (index in entries.indices && !isBrokenEntry(entries[index])) {
            EntryEditDialog(
                entry = entries[index],
                onDismiss = { editingIndex = null },
                onSave = { updated ->
                    onUpdateEntry(index, updated)
                    editingIndex = null
                }
            )
        }
    }

    pendingDeleteIndex?.let { index ->
        val entry = entries.getOrNull(index)
        AlertDialog(
            onDismissRequest = { pendingDeleteIndex = null },
            title = { Text(stringResource(R.string.world_edit_delete_entry_title)) },
            text = {
                val ctx = LocalContext.current
                val displayName = if (entry == null) {
                    ""
                } else if (isBrokenEntry(entry)) {
                    ctx.getString(R.string.world_edit_broken_badge)
                } else {
                    entryDisplayName(ctx, entry)
                }
                Text(stringResource(R.string.confirm_delete_msg, displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(index)
                    // 同时关掉可能正开着的条目编辑弹窗，避免下标错位后保存到别的条目
                    if (editingIndex == index) editingIndex = null
                    pendingDeleteIndex = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIndex = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/**
 * 条目显示名：C5 —— 本地化的「未命名条目」只用于显示，绝不写回数据，
 * 数据里空名就保持空串（否则切语言后残留旧语言假名，且用户永远清不掉）。
 */
private fun entryDisplayName(context: android.content.Context, entry: WorldEntry): String =
    entry.name.trim().ifBlank { context.getString(R.string.world_edit_unnamed_entry) }

/**
 * B26：判断是否为「解析失败」的哨兵条目（WorldInfoManager.parseEntries 生成）。
 * 哨兵条目只承载错误原因（content）与原始 JSON 节点（comment），不是真实数据。
 */
private fun isBrokenEntry(entry: WorldEntry): Boolean = entry.name == BROKEN_ENTRY_NAME

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(entry: WorldEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    // B26：损坏条目用独立的错误态显示，避免用户把它当成可编辑的真实条目
    val broken = isBrokenEntry(entry)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        color = if (broken) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else if (entry.enabled) {
            // B4：条目停用 → 卡片整体压暗，和启用条目区分开
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (broken) {
                        context.getString(R.string.world_edit_broken_badge)
                    } else {
                        entryDisplayName(context, entry)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (broken) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!broken) {
                    Text(
                        text = context.getString(R.string.world_edit_entry_meta, positionLabel(context, entry.position), entry.priority),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (broken) {
                // 失败原因写在 content 里，告诉用户原始数据仍会保留
                Spacer(Modifier.height(4.dp))
                Text(
                    text = context.getString(
                        R.string.world_edit_broken_note,
                        entry.content.ifBlank { BROKEN_ENTRY_NAME }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                // B4：停用条目标识（酒馆 disable:true 导入的条目此前无处可见）
                if (!entry.enabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = context.getString(R.string.world_edit_disabled_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (entry.keywords.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TagRow(tags = entry.keywords.take(6))
                }
                if (entry.content.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 全屏条目编辑（字段太多，用全屏 Dialog 承载，内容区固定可滚动） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditDialog(
    entry: WorldEntry,
    onDismiss: () -> Unit,
    onSave: (WorldEntry) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(entry.name) }
    var keywords by remember { mutableStateOf(joinKeywords(entry.keywords, entry.useRegex)) }
    var secondaryKeys by remember { mutableStateOf(joinKeywords(entry.secondaryKeys, entry.useRegex)) }
    var content by remember { mutableStateOf(entry.content) }
    var position by remember { mutableStateOf(entry.position) }
    var injectDepth by remember { mutableStateOf(entry.injectDepth.toString()) }
    var priority by remember { mutableStateOf(entry.priority.toString()) }
    var probability by remember { mutableStateOf(entry.probability.toString()) }
    var scanDepth by remember { mutableStateOf(entry.scanDepth.toString()) }
    var roleFilter by remember { mutableStateOf(entry.role ?: "") }
    var groupName by remember { mutableStateOf(entry.groupName) }
    // B31：groupWeight 此前没有任何输入入口，而引擎现在用它决定同组胜者
    // （同组互斥，权重高者被选中）。没有入口的话这个字段永远停在默认 100。
    var groupWeight by remember { mutableStateOf(entry.groupWeight.toString()) }
    var enabled by remember { mutableStateOf(entry.enabled) }
    var constantActive by remember { mutableStateOf(entry.constantActive) }
    var useRegex by remember { mutableStateOf(entry.useRegex) }
    var caseSensitive by remember { mutableStateOf(entry.caseSensitive) }
    var wholeWords by remember { mutableStateOf(entry.wholeWords) }
    var selective by remember { mutableStateOf(entry.selective) }
    var useProbability by remember { mutableStateOf(entry.useProbability) }
    var showPositionPicker by remember { mutableStateOf(false) }

    fun save() {
        // M20：数值字段不再「非法输入静默重置默认值」——旧实现 `toIntOrNull() ?: 默认值`
        // 会把 "12 "、"0.5"、清空、含汉字都悄悄改写成默认值并持久化，用户无感知。
        // 现在先 trim 再解析，非法输入 Toast 指出字段并中止保存。
        val injectDepthVal = injectDepth.trim().toIntOrNull()
        if (injectDepthVal == null) {
            Toast.makeText(context, "注入深度无效：请输入整数", Toast.LENGTH_SHORT).show()
            return
        }
        val priorityVal = priority.trim().toIntOrNull()
        if (priorityVal == null) {
            Toast.makeText(context, "优先级无效：请输入整数", Toast.LENGTH_SHORT).show()
            return
        }
        val probabilityVal = probability.trim().toIntOrNull()
        if (probabilityVal == null) {
            Toast.makeText(context, "触发概率无效：请输入整数", Toast.LENGTH_SHORT).show()
            return
        }
        // M20：概率越界明确报错并中止（0..100；比静默钳制更可感知）
        if (probabilityVal !in 0..100) {
            Toast.makeText(context, "触发概率无效：请输入 0-100 的整数", Toast.LENGTH_SHORT).show()
            return
        }
        val scanDepthVal = scanDepth.trim().toIntOrNull()
        if (scanDepthVal == null) {
            Toast.makeText(context, "扫描深度无效：请输入整数", Toast.LENGTH_SHORT).show()
            return
        }
        // M20：注入/扫描深度按引擎实际消费范围做合理钳制（引擎侧 injectDepth≥0、
        // scanDepth 使用时 coerceAtLeast(1)），越界钳制后给出提示，不再无声改写。
        val injectDepthFinal = injectDepthVal.coerceIn(0, 10)
        val scanDepthFinal = scanDepthVal.coerceIn(0, 10)
        if (injectDepthFinal != injectDepthVal || scanDepthFinal != scanDepthVal) {
            Toast.makeText(
                context,
                "注入/扫描深度超出 0-10，已调整为 $injectDepthFinal / $scanDepthFinal",
                Toast.LENGTH_SHORT
            ).show()
        }
        onSave(
            entry.copy(
                // C5：空名就存空串，本地化的「未命名条目」只在显示层兜底
                name = name.trim(),
                // B4：条目级启用开关
                enabled = enabled,
                // B22：正则关键词可含逗号（a{1,3}、\d{2,4}），正则模式下只按换行切
                keywords = splitKeywords(keywords, useRegex),
                secondaryKeys = splitKeywords(secondaryKeys, useRegex),
                content = content,
                position = position,
                injectDepth = injectDepthFinal,
                priority = priorityVal,
                probability = probabilityVal,
                scanDepth = scanDepthFinal,
                role = roleFilter.trim().ifBlank { null },
                groupName = groupName.trim(),
                // B31：这里的区间刻意放宽到 0..9999。
                // 酒馆世界书里常见 >100 的相对权重（如 100/200 表示优先级差），
                // 早先 coerceIn(0,100) 会在「打开条目再保存」时把这些值静默改写，
                // 进而影响刚实现的同组胜者选择。
                groupWeight = (groupWeight.toIntOrNull() ?: 100).coerceAtLeast(0),
                constantActive = constantActive,
                useRegex = useRegex,
                caseSensitive = caseSensitive,
                wholeWords = wholeWords,
                selective = selective,
                useProbability = useProbability
            )
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // 顶栏：取消 / 标题 / 保存
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(context.getString(R.string.cancel)) }
                    Text(
                        text = context.getString(R.string.world_edit_entry_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    TextButton(onClick = { save() }) { Text(context.getString(R.string.btn_save_short)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 内容区：固定剩余高度，内部滚动
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(context.getString(R.string.world_edit_name)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = { keywords = it },
                        label = { Text(context.getString(if (useRegex) R.string.world_edit_keywords_regex else R.string.world_edit_keywords)) },
                        minLines = if (useRegex) 3 else 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (useRegex) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = context.getString(R.string.world_edit_keywords_regex_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = secondaryKeys, onValueChange = { secondaryKeys = it }, label = { Text(context.getString(R.string.world_edit_secondary_keys)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text(context.getString(R.string.world_edit_content)) }, minLines = 4, modifier = Modifier.fillMaxWidth())

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(context.getString(R.string.world_edit_position), modifier = Modifier.weight(1f))
                        TextButton(onClick = { showPositionPicker = true }) {
                            Text(positionLabel(context, position))
                        }
                    }

                    // M20：四个数值框与 groupWeight 同款数字过滤（只允许数字、限长），
                    // 从入口减少空格/小数点/汉字等非法输入
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = priority, onValueChange = { input -> priority = input.filter { it.isDigit() }.take(5) }, label = { Text(context.getString(R.string.world_edit_priority)) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = probability, onValueChange = { input -> probability = input.filter { it.isDigit() }.take(5) }, label = { Text(context.getString(R.string.world_edit_probability)) }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = injectDepth, onValueChange = { input -> injectDepth = input.filter { it.isDigit() }.take(5) }, label = { Text(context.getString(R.string.world_edit_inject_depth)) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = scanDepth, onValueChange = { input -> scanDepth = input.filter { it.isDigit() }.take(5) }, label = { Text(context.getString(R.string.world_edit_scan_depth)) }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = roleFilter, onValueChange = { roleFilter = it }, label = { Text(context.getString(R.string.world_edit_role_filter)) }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = groupName, onValueChange = { groupName = it }, label = { Text(context.getString(R.string.world_edit_group)) }, modifier = Modifier.weight(1f))
                    }
                    // B31：填充分组名后才会用到权重（同组互斥，权重高者胜出）
                    OutlinedTextField(
                        value = groupWeight,
                        onValueChange = { input ->
                            groupWeight = input.filter { it.isDigit() }.take(5)
                        },
                        label = { Text(context.getString(R.string.world_edit_group_weight)) },
                        supportingText = { Text(context.getString(R.string.world_edit_group_weight_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // B22：切换正则开关时同步重排关键词文本，避免旧分隔符残留导致往返损坏
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Switch(checked = useRegex, onCheckedChange = { next ->
                                keywords = reformatKeywords(keywords, useRegex, next)
                                secondaryKeys = reformatKeywords(secondaryKeys, useRegex, next)
                                useRegex = next
                            })
                            Text(context.getString(R.string.world_edit_regex), style = MaterialTheme.typography.labelMedium)
                        }
                        ToggleLabel(context.getString(R.string.world_edit_case), caseSensitive, { caseSensitive = it }, Modifier.weight(1f))
                        ToggleLabel(context.getString(R.string.world_edit_selective), selective, { selective = it }, Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToggleLabel(context.getString(R.string.world_edit_whole_words), wholeWords, { wholeWords = it }, Modifier.weight(1f))
                        ToggleLabel(context.getString(R.string.world_edit_const), constantActive, { constantActive = it }, Modifier.weight(1f))
                        ToggleLabel(context.getString(R.string.world_edit_use_prob), useProbability, { useProbability = it }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    // B4：条目级启用开关（WorldEntry.enabled；停用的条目引擎直接跳过）
                    Column {
                        ToggleLabel(context.getString(R.string.world_edit_enabled_entry), enabled, { enabled = it }, Modifier.fillMaxWidth())
                        if (!enabled) {
                            Text(
                                text = context.getString(R.string.world_edit_entry_disabled_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // 注入位置选择（叠在全屏 Dialog 之上的常规弹窗）
    if (showPositionPicker) {
        AlertDialog(
            onDismissRequest = { showPositionPicker = false },
            title = { Text(context.getString(R.string.world_edit_pick_position)) },
            text = {
                Column {
                    POSITION_OPTIONS.forEach { value ->
                        TextButton(onClick = {
                            position = value
                            showPositionPicker = false
                        }) { Text(positionLabel(context, value)) }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ToggleLabel(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 关键词 → 编辑框文本。
 *
 * B22：正则模式下关键词里允许出现逗号（`a{1,3}`、`\d{2,4}`、`(foo|bar){2,3}`），
 * 若按逗号 join 再按逗号 split，一条键会被永久切成两条。所以正则模式一律「一行一个」。
 *
 * P2：非正则模式同病 —— 含逗号的字面键（`Hello, world`）「打开编辑器点保存」就会被
 * 切碎（join(", ") → split(',', ...)）。逗号/顿号不是无歧义的分隔符，**两种模式统一
 * 一行一个**，从结构上杜绝往返改写。导入侧（WorldInfoImporter.stripRegexDelimiters）
 * 已剥掉 /.../ 定界符，这里不再加回，保证 显示 → 保存 → 再显示 完全等幂。
 */
private fun joinKeywords(list: List<String>, regexMode: Boolean): String =
    list.joinToString("\n")

/**
 * 切换正则开关时把编辑框里的关键词文本按新模式重排（先按旧模式切，再按新模式拼）。
 * 两种模式均为「一行一个」，此函数仅做逐行 trim 归一。
 */
private fun reformatKeywords(text: String, fromRegex: Boolean, toRegex: Boolean): String {
    if (fromRegex == toRegex) return text
    return joinKeywords(splitKeywords(text, fromRegex), toRegex)
}

/**
 * 编辑框文本 → 关键词列表。
 * 两种模式都只按换行切，逐行 trim 后去空行，行内逗号/顿号原样保留（P2）。
 */
private fun splitKeywords(text: String, regexMode: Boolean): MutableList<String> {
    val raw = text.split('\n')
    return raw.map { it.trim() }
        .filter { it.isNotBlank() }
        .toMutableList()
}
