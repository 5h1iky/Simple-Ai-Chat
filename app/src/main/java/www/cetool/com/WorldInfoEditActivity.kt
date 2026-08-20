package www.cetool.com

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import www.cetool.com.ui.components.FoldableSection
import www.cetool.com.ui.components.SectionCard
import www.cetool.com.ui.components.TagRow
import www.cetool.com.ui.theme.SAChatTheme
import java.util.UUID

private val POSITION_OPTIONS = listOf(
    "before_char_defs" to "角色定义前",
    "after_system_prompt" to "角色定义后",
    "before_example_messages" to "示例对话前",
    "after_example_messages" to "示例对话后",
    "at_depth" to "@ 深度注入"
)

private fun positionLabel(position: String): String {
    return POSITION_OPTIONS.firstOrNull { it.first == position }?.second ?: position
}

/**
 * 世界书编辑（Compose）：头部设置 + 条目卡片列表 + 全屏条目编辑。
 */
class WorldInfoEditActivity : ComponentActivity() {

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
            }
        }

        setContent {
            SAChatTheme {
                WorldInfoEditScreen(
                    name = name,
                    description = description,
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
                            // 启用状态由对话侧（长按对话 → 世界书设置）控制，编辑页不再提供开关
                            enabled = enabled,
                            entries = entries.toMutableList()
                        )
                        if (info.name.isBlank()) {
                            Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                            return@WorldInfoEditScreen
                        }
                        if (editId != null) {
                            WorldInfoManager.overwrite(this, editId!!, info)
                        } else {
                            WorldInfoManager.saveNew(this, info)
                        }
                        Toast.makeText(this, "世界书「${info.name}」已保存", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK, Intent().putExtra("world_info_id", info.id))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑世界书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onSave) { Text("保存") }
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
            SectionCard("基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FoldableSection(
                title = "提示",
                preview = "世界书行为对标酒馆（SillyTavern）",
                initiallyExpanded = false
            ) {
                Text(
                    text = "关键词触发（支持正则 /.../、whole words 整词匹配、次级关键词过滤、触发概率、常驻、@深度注入）。token 预算可在「设置」中调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("条目（${entries.size}）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("点击条目编辑，长按删除", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onAddEntry) {
                        Icon(Icons.Filled.Add, contentDescription = "添加条目")
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
                entries.forEachIndexed { index, entry ->
                    EntryCard(
                        entry = entry,
                        onClick = { editingIndex = index },
                        onLongClick = { onDeleteEntry(index) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    editingIndex?.let { index ->
        if (index in entries.indices) {
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(entry: WorldEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name.ifBlank { "未命名条目" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${positionLabel(entry.position)} | 优先级 ${entry.priority}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

/** 全屏条目编辑（字段太多，用全屏 Dialog 承载，内容区固定可滚动） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditDialog(
    entry: WorldEntry,
    onDismiss: () -> Unit,
    onSave: (WorldEntry) -> Unit
) {
    var name by remember { mutableStateOf(entry.name) }
    var keywords by remember { mutableStateOf(entry.keywords.joinToString(", ")) }
    var secondaryKeys by remember { mutableStateOf(entry.secondaryKeys.joinToString(", ")) }
    var content by remember { mutableStateOf(entry.content) }
    var position by remember { mutableStateOf(entry.position) }
    var injectDepth by remember { mutableStateOf(entry.injectDepth.toString()) }
    var priority by remember { mutableStateOf(entry.priority.toString()) }
    var probability by remember { mutableStateOf(entry.probability.toString()) }
    var scanDepth by remember { mutableStateOf(entry.scanDepth.toString()) }
    var roleFilter by remember { mutableStateOf(entry.role ?: "") }
    var groupName by remember { mutableStateOf(entry.groupName) }
    var constantActive by remember { mutableStateOf(entry.constantActive) }
    var useRegex by remember { mutableStateOf(entry.useRegex) }
    var caseSensitive by remember { mutableStateOf(entry.caseSensitive) }
    var wholeWords by remember { mutableStateOf(entry.wholeWords) }
    var selective by remember { mutableStateOf(entry.selective) }
    var useProbability by remember { mutableStateOf(entry.useProbability) }
    var showPositionPicker by remember { mutableStateOf(false) }

    fun save() {
        onSave(
            entry.copy(
                name = name.trim().ifBlank { "未命名条目" },
                keywords = splitKeywords(keywords),
                secondaryKeys = splitKeywords(secondaryKeys),
                content = content,
                position = position,
                injectDepth = injectDepth.toIntOrNull() ?: 4,
                priority = priority.toIntOrNull() ?: 1000,
                probability = probability.toIntOrNull() ?: 100,
                scanDepth = scanDepth.toIntOrNull() ?: 4,
                role = roleFilter.trim().ifBlank { null },
                groupName = groupName.trim(),
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
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(
                        text = "编辑条目",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    TextButton(onClick = { save() }) { Text("保存") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 内容区：固定剩余高度，内部滚动
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("关键词（逗号分隔，/.../ 视为正则）") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = secondaryKeys, onValueChange = { secondaryKeys = it }, label = { Text("次级关键词（可选过滤）") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("内容") }, minLines = 4, modifier = Modifier.fillMaxWidth())

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("注入位置", modifier = Modifier.weight(1f))
                        TextButton(onClick = { showPositionPicker = true }) {
                            Text(positionLabel(position))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = priority, onValueChange = { priority = it }, label = { Text("优先级(Order)") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = probability, onValueChange = { probability = it }, label = { Text("概率%") }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = injectDepth, onValueChange = { injectDepth = it }, label = { Text("注入深度") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = scanDepth, onValueChange = { scanDepth = it }, label = { Text("扫描深度") }, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = roleFilter, onValueChange = { roleFilter = it }, label = { Text("角色过滤(user/assistant)") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = groupName, onValueChange = { groupName = it }, label = { Text("包含组") }, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToggleLabel("常驻", constantActive, { constantActive = it }, Modifier.weight(1f))
                        ToggleLabel("正则", useRegex, { useRegex = it }, Modifier.weight(1f))
                        ToggleLabel("大小写", caseSensitive, { caseSensitive = it }, Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToggleLabel("整词匹配", wholeWords, { wholeWords = it }, Modifier.weight(1f))
                        ToggleLabel("次级过滤", selective, { selective = it }, Modifier.weight(1f))
                        ToggleLabel("启用概率", useProbability, { useProbability = it }, Modifier.weight(1f))
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
            title = { Text("选择注入位置") },
            text = {
                Column {
                    POSITION_OPTIONS.forEach { (value, label) ->
                        TextButton(onClick = {
                            position = value
                            showPositionPicker = false
                        }) { Text(label) }
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

private fun splitKeywords(text: String): MutableList<String> {
    return text.split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toMutableList()
}
