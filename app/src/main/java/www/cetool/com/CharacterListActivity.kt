package www.cetool.com

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import www.cetool.com.manager.CharacterManager
import www.cetool.com.model.CharacterInfo
import www.cetool.com.ui.components.TagRow
import www.cetool.com.ui.theme.SAChatTheme
import www.cetool.com.importer.TavernCardImporter

/**
 * 角色卡列表（Compose）：卡片式展示头像/名字/身份标签/描述。
 * - SELECT 模式（默认）：点按角色 → 返回 character_id 供聊天使用
 * - MANAGE 模式：点按 → 编辑，长按菜单 → 编辑/删除
 */
class CharacterListActivity : ComponentActivity() {

    private var isManageMode = false

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            refresh()
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) handleImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isManageMode = intent.getBooleanExtra("manage_mode", false)

        setContent {
            SAChatTheme {
                CharacterListScreen(
                    loadCharacters = { CharacterManager.list(this) },
                    onBack = { finish() },
                    onPick = { id ->
                        if (isManageMode) {
                            openEditor(id)
                        } else {
                            setResult(RESULT_OK, Intent().putExtra("character_id", id))
                            finish()
                        }
                    },
                    onEdit = { id -> openEditor(id) },
                    onDelete = { id ->
                        CharacterManager.delete(this, id)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    },
                    onNew = { openEditor(null) },
                    onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    isManageMode = isManageMode
                )
            }
        }
    }

    private fun openEditor(characterId: String?) {
        val intent = Intent(this, CharacterEditActivity::class.java)
        if (characterId != null) intent.putExtra("character_id", characterId)
        editLauncher.launch(intent)
    }

    private fun handleImport(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val json = inputStream.bufferedReader().use { it.readText() }
            val card = TavernCardImporter.parse(json).getOrNull()
            if (card == null) {
                Toast.makeText(this, "角色卡格式无效", Toast.LENGTH_LONG).show()
                return
            }
            val id = CharacterManager.save(this, json) ?: run {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "角色「${card.data.name}」已导入", Toast.LENGTH_SHORT).show()
            refresh()
            if (!isManageMode) {
                setResult(RESULT_OK, Intent().putExtra("character_id", id))
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        // 通过重建 UI 状态刷新：简单方式为 setContent 中的 remember 依赖一个可变计数器
        uiRefreshCount++
    }

    companion object {
        var uiRefreshCount = 0
            private set
        fun bumpRefresh() { uiRefreshCount++ }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterListScreen(
    loadCharacters: () -> List<CharacterInfo>,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    isManageMode: Boolean
) {
    var refreshTick by remember { mutableStateOf(CharacterListActivity.uiRefreshCount) }
    val characters = remember(refreshTick) { loadCharacters() }
    var pendingDelete by remember { mutableStateOf<CharacterInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isManageMode) "角色管理" else "选择角色") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onImport) { Text("导入") }
                    IconButton(onClick = onNew) {
                        Icon(Icons.Filled.Add, contentDescription = "新建角色卡")
                    }
                }
            )
        }
    ) { padding ->
        if (characters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无角色卡，点击右上角 + 新建或导入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(characters, key = { it.id }) { char ->
                    CharacterCard(
                        char = char,
                        onClick = { onPick(char.id) },
                        onLongClick = { pendingDelete = char }
                    )
                }
            }
        }
    }

    pendingDelete?.let { char ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(char.name) },
            text = {
                Column {
                    TextButton(onClick = {
                        onEdit(char.id)
                        pendingDelete = null
                    }) { Text("编辑角色卡") }
                    HorizontalDivider()
                    TextButton(onClick = {
                        onDelete(char.id)
                        pendingDelete = null
                        CharacterListActivity.bumpRefresh()
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

@Composable
private fun CharacterCard(
    char: CharacterInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CharacterAvatar(char.avatarBase64, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = char.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (char.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TagRow(tags = char.tags)
                }
                if (char.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = char.description,
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

@Composable
fun CharacterAvatar(avatarBase64: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64 == null) null
        else try {
            BitmapFactory.decodeByteArray(Base64.decode(avatarBase64, Base64.DEFAULT), 0, 0)
                ?.let { b ->
                    // 限制尺寸防止超大图
                    val max = 256
                    val scale = minOf(1f, max.toFloat() / b.width, max.toFloat() / b.height)
                    if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
                    } else b
                }
        } catch (_: Exception) { null }
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .then(if (bitmap == null) Modifier else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "🎭",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
