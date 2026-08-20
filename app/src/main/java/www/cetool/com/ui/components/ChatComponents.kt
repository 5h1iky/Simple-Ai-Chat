package www.cetool.com.ui.components

import android.widget.TextView
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.BitmapFactory
import android.util.Base64
import io.noties.markwon.Markwon
import www.cetool.com.model.Message
import www.cetool.com.ui.theme.SAChatTheme

/** 聊天消息内容块：文本 / 角色卡标记（[[CARD:id]]） */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class CardMarker(val cardId: String) : ContentBlock()
}

/** 解析流式内容中的标记（标记协议：[[CARD:id]]，供聊天内 UI 卡片使用） */
fun parseContentBlocks(content: String): List<ContentBlock> {
    if (!content.contains("[[")) return listOf(ContentBlock.Text(content))
    val blocks = mutableListOf<ContentBlock>()
    val regex = Regex("\\[\\[CARD:([^\\]]+)\\]\\]")
    var last = 0
    for (match in regex.findAll(content)) {
        if (match.range.first > last) {
            blocks.add(ContentBlock.Text(content.substring(last, match.range.first)))
        }
        blocks.add(ContentBlock.CardMarker(match.groupValues[1]))
        last = match.range.last + 1
    }
    if (last < content.length) {
        blocks.add(ContentBlock.Text(content.substring(last)))
    }
    return blocks
}

/** 聊天消息列表（Compose 版，替代 MessageAdapter + item_message.xml） */
@Composable
fun MessageList(
    messages: List<Message>,
    characterAvatar: String?,
    userAvatar: String?,
    aiName: String,
    fontSize: Int,
    systemPromptPreview: String,
    bindingCharacterName: String?,
    bindingWorldName: String?,
    scrollTarget: Int?,
    onClearScrollTarget: () -> Unit,
    onCharacterBindingClick: () -> Unit,
    onWorldBindingClick: () -> Unit,
    onCardClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    // 新消息自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    // 搜索跳转
    LaunchedEffect(scrollTarget) {
        val target = scrollTarget
        if (target != null && target >= 0 && target < messages.size) {
            listState.animateScrollToItem(target)
            onClearScrollTarget()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BindingBar(
            characterName = bindingCharacterName,
            worldName = bindingWorldName,
            onCharacterClick = onCharacterBindingClick,
            onWorldClick = onWorldBindingClick
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp
            )
        ) {
            if (systemPromptPreview.isNotBlank()) {
                item(key = "system-prompt-fold") {
                    SystemPromptFold(systemPromptPreview)
                    Spacer(Modifier.height(8.dp))
                }
            }
            itemsIndexed(messages) { _, message ->
                ChatMessageRow(
                    message = message,
                    isUser = message.role == Message.ROLE_USER,
                    characterAvatar = characterAvatar,
                    userAvatar = userAvatar,
                    aiName = aiName,
                    fontSize = fontSize,
                    onCardClick = onCardClick
                )
            }
        }
    }
}

@Composable
private fun BindingBar(
    characterName: String?,
    worldName: String?,
    onCharacterClick: () -> Unit,
    onWorldClick: () -> Unit
) {
    if (characterName == null && worldName == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (characterName != null) {
            BindingChip(text = "👤 $characterName", onClick = onCharacterClick)
        }
        if (worldName != null) {
            BindingChip(text = "📖 $worldName", onClick = onWorldClick)
        }
    }
}

@Composable
private fun BindingChip(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SystemPromptFold(systemPrompt: String) {
    FoldableSection(
        title = "系统提示词（已融合设定）",
        preview = systemPrompt
    ) {
        ChatMarkdown(text = systemPrompt, fontSize = 13)
    }
}

@Composable
private fun ChatMessageRow(
    message: Message,
    isUser: Boolean,
    characterAvatar: String?,
    userAvatar: String?,
    aiName: String,
    fontSize: Int,
    onCardClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            ChatAvatar(characterAvatar, Modifier.size(32.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.78f else 0.85f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (!isUser) {
                Text(
                    text = aiName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
            ChatBubble(message = message, isUser = isUser, fontSize = fontSize, onCardClick = onCardClick)
        }
        if (isUser) {
            Spacer(Modifier.width(6.dp))
            ChatAvatar(userAvatar, Modifier.size(32.dp))
        }
    }
}

@Composable
private fun ChatAvatar(avatarBase64: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64 == null) null
        else try {
            BitmapFactory.decodeByteArray(Base64.decode(avatarBase64, Base64.DEFAULT), 0, 0)
        } catch (_: Exception) { null }
    }
    Box(
        modifier = modifier.clip(CircleShape),
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
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {}
        }
    }
}

@Composable
private fun ChatBubble(
    message: Message,
    isUser: Boolean,
    fontSize: Int,
    onCardClick: (String) -> Unit
) {
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // 思考内容折叠块
        if (!isUser && message.reasoningContent.isNotBlank()) {
            FoldableSection(
                title = "思考",
                preview = message.reasoningContent
            ) {
                ChatMarkdown(text = message.reasoningContent, fontSize = (fontSize - 1).coerceAtLeast(11))
            }
            Spacer(Modifier.height(4.dp))
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 6.dp,
                topEnd = if (isUser) 6.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            color = bubbleColor
        ) {
            if (message.content.isEmpty() && message.reasoningContent.isBlank()) {
                // 流式中：思考中…
                Text(
                    text = "思考中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            } else {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    parseContentBlocks(message.content).forEach { block ->
                        when (block) {
                            is ContentBlock.Text -> {
                                if (block.text.isNotBlank()) {
                                    ChatMarkdown(text = block.text, fontSize = fontSize, textColor = textColor)
                                }
                            }
                            is ContentBlock.CardMarker -> {
                                // 聊天内角色卡 UI：标记 → 卡片组件
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isUser) {
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .clickable { onCardClick(block.cardId) }
                                ) {
                                    Text(
                                        text = "📇 角色卡 ${block.cardId}（点击查看）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = textColor,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(
                java.util.Date(message.timestamp)
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Markdown 渲染：复用 Markwon（AndroidView 包装） */
@Composable
fun ChatMarkdown(
    text: String,
    fontSize: Int = 15,
    textColor: androidx.compose.ui.graphics.Color? = null
) {
    val context = LocalContext.current
    val markwon = remember { Markwon.builder(context).usePlugin(www.cetool.com.NoItalicPlugin()).build() }
    AndroidView(
        factory = { ctx -> TextView(ctx) },
        update = { tv ->
            tv.textSize = fontSize.toFloat() // TextView.textSize 单位即 sp
            textColor?.let { tv.setTextColor(it.toArgb()) }
            markwon.setMarkdown(tv, text)
        },
        modifier = Modifier.fillMaxWidth()
    )
}
