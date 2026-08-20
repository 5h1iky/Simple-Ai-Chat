package www.cetool.com

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import www.cetool.com.importer.WorldInfoImporter
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.manager.WorldInfoManager.WorldInfoSummary
import www.cetool.com.ui.components.TagRow
import www.cetool.com.ui.theme.SAChatTheme

/**
 * 世界书列表（Compose）：
 * - SELECT 模式（默认）：点按返回 world_info_id
 * - MANAGE 模式：点按 → 编辑，长按菜单 → 编辑/删除
 */
class WorldInfoListActivity : ComponentActivity() {

    private var isManageMode = false
    // Compose 可观察刷新计数（修复：remember 快照导致编辑返回后列表不刷新）
    private val refreshTick = mutableIntStateOf(0)
    private var exportPendingJson: String? = null

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) refreshTick.intValue++
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) handleImport(uri)
    }

    /** 导出：打开系统文件管理器让用户选择保存位置 */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = exportPendingJson
        exportPendingJson = null
        if (uri != null && json != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "世界书已导出", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onExport(worldInfoId: String, name: String) {
        val json = WorldInfoManager.rawJson(this, worldInfoId)
        if (json == null) {
            Toast.makeText(this, "导出失败：无法读取世界书", Toast.LENGTH_SHORT).show()
            return
        }
        exportPendingJson = json
        exportLauncher.launch("${sanitizeFileName(name)}.json")
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "worldinfo" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isManageMode = intent.getBooleanExtra("manage_mode", false)
        ConversationManager.init(this)

        setContent {
            SAChatTheme {
                WorldInfoListScreen(
                    loadWorldInfos = { WorldInfoManager.list(this) },
                    tick = refreshTick.intValue,
                    onBack = { finish() },
                    onPick = { id ->
                        if (isManageMode) {
                            openEditor(id)
                        } else {
                            setResult(RESULT_OK, Intent().putExtra("world_info_id", id))
                            finish()
                        }
                    },
                    onEdit = { id -> openEditor(id) },
                    onDelete = { id ->
                        WorldInfoManager.delete(this, id)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        refreshTick.intValue++
                    },
                    onExport = { info -> onExport(info.id, info.name) },
                    onNew = { openEditor(null) },
                    onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    isManageMode = isManageMode
                )
            }
        }
    }

    private fun openEditor(worldInfoId: String?) {
        val intent = Intent(this, WorldInfoEditActivity::class.java)
        if (worldInfoId != null) intent.putExtra("world_info_id", worldInfoId)
        editLauncher.launch(intent)
    }

    private fun handleImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val json = inputStream.bufferedReader().use { it.readText() }
            when (val result = WorldInfoImporter.parse(json)) {
                is WorldInfoImporter.ImportResult.Success -> {
                    WorldInfoManager.saveNew(this, result.info)
                    val warnText = if (result.warnings.isNotEmpty()) "（${result.warnings.size} 条警告）" else ""
                    Toast.makeText(this, "世界书「${result.info.name}」已导入$warnText", Toast.LENGTH_SHORT).show()
                    refreshTick.intValue++
                    if (!isManageMode) {
                        setResult(RESULT_OK, Intent().putExtra("world_info_id", result.info.id))
                        finish()
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WorldInfoListScreen(
    loadWorldInfos: () -> List<WorldInfoSummary>,
    tick: Int,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (WorldInfoSummary) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    isManageMode: Boolean
) {
    val worldInfos = remember(tick) { loadWorldInfos() }
    // 各世界书被哪些对话启用（长按对话 → 世界书设置 可自由配置，支持多本）
    val convTitlesByWorld = remember(tick) {
        val map = mutableMapOf<String, MutableList<String>>()
        ConversationManager.all.forEach { conv ->
            conv.boundWorldIds().forEach { wid ->
                map.getOrPut(wid) { mutableListOf() }.add(conv.title)
            }
        }
        map.mapValues { it.value.toList() }
    }
    var pendingDelete by remember { mutableStateOf<WorldInfoSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isManageMode) "世界书管理" else "选择世界书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onImport) { Text("导入") }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Filled.Add, contentDescription = "新建世界书")
                    }
                }
            )
        }
    ) { padding ->
        if (worldInfos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无世界书，点击右上角 + 新建或导入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(worldInfos, key = { it.id }) { info ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPick(info.id) },
                                onLongClick = { pendingDelete = info }
                            )
                            .clip(RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = info.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                // 世界书不在本页启用：提示用户在对话侧（长按对话 → 世界书设置）启用
                                Text(
                                    text = "长按对话启用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${info.entryCount} 条目",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 提示启用它的对话（长按左侧对话 → 世界书设置），一本世界书也显示
                            Spacer(Modifier.height(4.dp))
                            val titles = convTitlesByWorld[info.id] ?: emptyList()
                            Text(
                                text = if (titles.isEmpty()) {
                                    "暂无对话启用"
                                } else {
                                    "启用对话：${titles.joinToString("、")}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (titles.isEmpty()) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (info.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = info.description,
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
        }
    }

    pendingDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(info.name) },
            text = {
                Column {
                    TextButton(onClick = {
                        onEdit(info.id)
                        pendingDelete = null
                    }) { Text("编辑世界书") }
                    HorizontalDivider()
                    TextButton(onClick = {
                        onExport(info)
                        pendingDelete = null
                    }) { Text("导出世界书") }
                    HorizontalDivider()
                    TextButton(onClick = {
                        onDelete(info.id)
                        pendingDelete = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}
