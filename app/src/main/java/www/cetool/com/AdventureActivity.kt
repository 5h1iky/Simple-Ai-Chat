package www.cetool.com

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import www.cetool.com.manager.CharacterManager
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.model.CharacterInfo
import www.cetool.com.manager.WorldInfoManager.WorldInfoSummary
import www.cetool.com.ui.components.SectionCard
import www.cetool.com.ui.components.TagRow
import www.cetool.com.ui.theme.SAChatTheme

/**
 * 文字冒险配置页（Compose，2.2）：
 * 世界书单选 + 角色卡多选 + 开场剧情 → 返回选择给 ChatActivity 开启 DM 对话。
 */
class AdventureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val worldInfos = WorldInfoManager.list(this)
        val characters = CharacterManager.list(this)

        var selectedWorldId by mutableStateOf<String?>(null)
        val selectedRoleIds = mutableStateListOf<String>()
        var opening by mutableStateOf("")

        setContent {
            SAChatTheme {
                AdventureConfigScreen(
                    worldInfos = worldInfos,
                    characters = characters,
                    selectedWorldId = selectedWorldId,
                    selectedRoleIds = selectedRoleIds,
                    opening = opening,
                    onWorldSelect = { selectedWorldId = it },
                    onRoleToggle = { id ->
                        if (id in selectedRoleIds) selectedRoleIds.remove(id) else selectedRoleIds.add(id)
                    },
                    onOpeningChange = { opening = it },
                    onBack = { finish() },
                    onStart = {
                        if (selectedRoleIds.isEmpty()) {
                            Toast.makeText(this, "请至少选择一张角色卡", Toast.LENGTH_SHORT).show()
                            return@AdventureConfigScreen
                        }
                        val intent = Intent().apply {
                            putExtra("world_info_id", selectedWorldId)
                            putExtra("role_ids", ArrayList(selectedRoleIds))
                            putExtra("opening", opening.trim())
                        }
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdventureConfigScreen(
    worldInfos: List<WorldInfoSummary>,
    characters: List<CharacterInfo>,
    selectedWorldId: String?,
    selectedRoleIds: List<String>,
    opening: String,
    onWorldSelect: (String) -> Unit,
    onRoleToggle: (String) -> Unit,
    onOpeningChange: (String) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字冒险") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onStart) { Text("开始冒险") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard("世界书（单选，可选）") {
                    if (worldInfos.isEmpty()) {
                        Text("暂无世界书", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    worldInfos.forEach { info ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onWorldSelect(info.id) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedWorldId == info.id,
                                onClick = { onWorldSelect(info.id) }
                            )
                            Text(
                                text = info.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item {
                SectionCard("角色卡（多选，至少 1 张）") {
                    if (characters.isEmpty()) {
                        Text("暂无角色卡，请先创建或导入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(characters, key = { it.id }) { char ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRoleToggle(char.id) }
                ) {
                    Checkbox(
                        checked = char.id in selectedRoleIds,
                        onCheckedChange = { onRoleToggle(char.id) }
                    )
                    CharacterAvatar(char.avatarBase64, Modifier.width(36.dp).height(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(char.name, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (char.tags.isNotEmpty()) {
                            TagRow(tags = char.tags.take(3))
                        }
                    }
                }
            }

            item {
                SectionCard("开场剧情") {
                    OutlinedTextField(
                        value = opening,
                        onValueChange = onOpeningChange,
                        label = { Text("输入故事开端（可选）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "冒险中可用 [语言] [行为] [剧情] 三种方式输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "已选 ${selectedRoleIds.size} 个角色" +
                            (selectedWorldId?.let { wid ->
                                worldInfos.firstOrNull { it.id == wid }?.let { " · ${it.name}" } ?: ""
                            } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
