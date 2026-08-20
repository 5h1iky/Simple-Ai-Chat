package www.cetool.com

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import www.cetool.com.manager.CharacterManager
import www.cetool.com.ConversationManager
import www.cetool.com.model.CharacterFields
import www.cetool.com.ui.components.SectionCard
import www.cetool.com.ui.theme.SAChatTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 羁绊档案聚合数据 */
data class BondCharacter(
    val characterId: String,
    val name: String,
    val avatarBase64: String?,
    val fields: CharacterFields,
    val archivedAt: Long,
    val sessionCount: Int
)

/**
 * 羁绊档案页（Compose，2.1）：
 * 角色列表 → 关系概览卡片 + 关键事件时间线 + 会话足迹。
 */
class BondActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 聚合：按角色分组封存过的会话（组合外计算一次）
        val bonds = ConversationManager.all
            .filter { it.isArchived && it.characterId != null }
            .groupBy { it.characterId!! }
            .mapNotNull { (characterId, convs) ->
                val card = CharacterManager.loadCard(this, characterId) ?: return@mapNotNull null
                BondCharacter(
                    characterId = characterId,
                    name = card.data.name,
                    avatarBase64 = card.avatarBase64,
                    fields = card.data.getCharacterFields(),
                    archivedAt = convs.maxOf { it.updatedAt },
                    sessionCount = convs.size
                )
            }
            .sortedByDescending { it.archivedAt }

        var selected by mutableStateOf<BondCharacter?>(null)

        setContent {
            SAChatTheme {
                if (selected == null) {
                    BondListScreen(
                        bonds = bonds,
                        onBack = { finish() },
                        onSelect = { selected = it }
                    )
                } else {
                    BondDetailScreen(
                        bond = selected!!,
                        sessions = ConversationManager.all.filter {
                            it.isArchived && it.characterId == selected!!.characterId
                        },
                        onBack = { selected = null }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BondListScreen(
    bonds: List<BondCharacter>,
    onBack: () -> Unit,
    onSelect: (BondCharacter) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("羁绊档案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (bonds.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有羁绊记录。\n与角色对话后使用「封存记忆」，关系与事件会沉淀在这里。",
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
                items(bonds, key = { it.characterId }) { bond ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(bond) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            CharacterAvatar(bond.avatarBase64, Modifier.width(48.dp).height(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bond.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = buildString {
                                        append("${bond.sessionCount} 段封存记忆")
                                        if (bond.fields.userRelationType.isNotBlank()) {
                                            append(" | ${bond.fields.userRelationType}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BondDetailScreen(
    bond: BondCharacter,
    sessions: List<www.cetool.com.model.Conversation>,
    onBack: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val events = remember(bond.fields.keyEvents) {
        bond.fields.keyEvents.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bond.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard("关系概览") {
                    DetailRow("关系类型", bond.fields.userRelationType)
                    DetailRow("相处模式", bond.fields.userInteractionModel)
                    DetailRow("关系底线", bond.fields.userRelationBottomLine)
                }
            }

            item {
                SectionCard("关键事件时间线") {
                    if (events.isEmpty()) {
                        Text(
                            "暂无记录，封存对话后关键事件会出现在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        events.forEachIndexed { index, event ->
                            Row {
                                Text("◆", color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = event,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (index < events.size - 1) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            item {
                SectionCard("会话足迹（${sessions.size}）") {
                    sessions.sortedByDescending { it.updatedAt }.forEach { conv ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = conv.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${timeFormat.format(Date(conv.updatedAt))} · ${conv.messages.count { it.role == www.cetool.com.model.Message.ROLE_USER }} 条消息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
