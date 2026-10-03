package www.cetool.com

import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import www.cetool.com.importer.WorldInfoImporter
import www.cetool.com.manager.BROKEN_ENTRY_NAME
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

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
                Toast.makeText(this, getString(R.string.world_list_exported), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.world_list_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onExport(worldInfoId: String, name: String) {
        // B27：原来导出的是**自有格式**（{version,type,data:{entries:[...]}} 数组 + camelCase），
        // 而酒馆世界书是 {entries:{uid:{keys:[...],insertion_order:...}}} 对象 + snake_case
        // → 编辑一次再导出，导回酒馆会失败/丢键。
        // 现在导出真正的酒馆标准格式，让文件能被酒馆直接导入。
        val json = WorldInfoManager.exportSillyTavern(this, worldInfoId)
            ?: WorldInfoManager.rawJson(this, worldInfoId)
        if (json == null) {
            Toast.makeText(this, getString(R.string.world_list_export_unreadable), Toast.LENGTH_SHORT).show()
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
                    loadBrokenCounts = { summaries ->
                        // B26：每本世界书的损坏条目数（load 会把解析失败的条目换成哨兵条目，数它即可）
                        summaries.associate { summary ->
                            val count = WorldInfoManager.load(this, summary.id)
                                ?.entries
                                ?.count { it.name == BROKEN_ENTRY_NAME }
                                ?: 0
                            summary.id to count
                        }
                    },
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
                        Toast.makeText(this, getString(R.string.char_list_deleted), Toast.LENGTH_SHORT).show()
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
                    val warnText = if (result.warnings.isNotEmpty()) {
                        getString(R.string.world_import_warn_suffix, result.warnings.size)
                    } else ""
                    Toast.makeText(this, getString(R.string.world_import_ok, result.info.name, warnText), Toast.LENGTH_SHORT).show()
                    refreshTick.intValue++
                    if (!isManageMode) {
                        setResult(RESULT_OK, Intent().putExtra("world_info_id", result.info.id))
                        finish()
                    }
                }
                is WorldInfoImporter.ImportResult.Failure -> {
                    Toast.makeText(this, getString(R.string.world_import_failed, result.message), Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.world_import_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WorldInfoListScreen(
    loadWorldInfos: () -> List<WorldInfoSummary>,
    loadBrokenCounts: (List<WorldInfoSummary>) -> Map<String, Int>,
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
    // D4：原实现在组合期直接调用 loadWorldInfos() / loadBrokenCounts()，两者都会逐本
    // 读盘 + 解析 JSON（brokenCounts 还会把每本书完整 load 一遍），世界书多了会卡首帧。
    // 改为在 IO 线程完成「列表 + 损坏计数」的加载后一次性发布。
    data class ListData(
        val infos: List<WorldInfoSummary>,
        val brokenCounts: Map<String, Int>
    )
    val listData by androidx.compose.runtime.produceState(
        initialValue = ListData(emptyList(), emptyMap()),
        tick
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val infos = loadWorldInfos()
                ListData(infos, loadBrokenCounts(infos))
            }.getOrDefault(ListData(emptyList(), emptyMap()))
        }
    }
    val worldInfos = listData.infos
    val brokenCountByWorld = listData.brokenCounts
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
                title = { Text(if (isManageMode) stringResource(R.string.world_list_title_manage) else stringResource(R.string.world_list_title_select)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onImport) { Text(stringResource(R.string.char_list_import)) }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.world_list_new))
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
                    text = stringResource(R.string.world_list_empty),
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        // C5：列表显示层兜底，不把本地化假名写进数据
                                        text = info.name.trim().ifBlank { stringResource(R.string.world_edit_unnamed_entry) },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        // B4：停用的世界书标题压暗，视觉上一眼可辨
                                        color = if (info.enabled) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        }
                                    )
                                    if (!info.enabled) {
                                        Text(
                                            text = stringResource(R.string.world_list_disabled),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                // B23：该行长弹出的实际是 编辑/导出/删除 菜单，文案随之纠正
                                Text(
                                    text = stringResource(R.string.world_list_longpress_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.world_list_entries, info.entryCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // B4：条目级禁用数量（`disable: true` 的酒馆条目导入后在此可见）
                                if (info.disabledEntryCount > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "· ${stringResource(R.string.world_list_disabled_entries, info.disabledEntryCount)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                // B26：解析失败的条目数量（编辑页会用错误态标出具体是哪几条）
                                val brokenCount = brokenCountByWorld[info.id] ?: 0
                                if (brokenCount > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "· ${stringResource(R.string.world_list_broken_entries, brokenCount)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            // 提示启用它的对话（长按左侧对话 → 世界书设置），一本世界书也显示
                            Spacer(Modifier.height(4.dp))
                            val titles = convTitlesByWorld[info.id] ?: emptyList()
                            Text(
                                text = if (titles.isEmpty()) {
                                    stringResource(R.string.world_list_no_conv)
                                } else {
                                    stringResource(R.string.world_list_used_by, titles.joinToString("、"))
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
            title = { Text(info.name.trim().ifBlank { stringResource(R.string.world_edit_unnamed_entry) }) },
            text = {
                Column {
                    TextButton(onClick = {
                        onEdit(info.id)
                        pendingDelete = null
                    }) { Text(stringResource(R.string.world_list_edit)) }
                    HorizontalDivider()
                    TextButton(onClick = {
                        onExport(info)
                        pendingDelete = null
                    }) { Text(stringResource(R.string.world_list_export)) }
                    HorizontalDivider()
                    TextButton(onClick = {
                        onDelete(info.id)
                        pendingDelete = null
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
