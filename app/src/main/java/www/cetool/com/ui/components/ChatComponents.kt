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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import www.cetool.com.R
import www.cetool.com.manager.WorldInfoEngine
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

/**
 * 附件展示信息（与气泡正文分离，正文里不含文件全文）。
 *
 * @param text 用户实际输入的文字（已剥离旧版遗留的 "[文件: x]" 前缀）
 */
data class AttachmentInfo(
    val type: String,
    val name: String,
    val data: String,
    val text: String
)

/** 组装附件展示信息（无附件返回 null） */
@Composable
private fun rememberAttachmentInfo(message: Message): AttachmentInfo? {
    val data = message.attachmentData ?: return null
    val type = message.attachmentType ?: return null
    val text = if (type == Message.ATTACH_TYPE_TEXT) message.displayText() else message.content
    val name = message.attachmentName?.takeIf { it.isNotBlank() }
        ?: stringResource(if (type == Message.ATTACH_TYPE_IMAGE) R.string.file_image else R.string.file_text)
    return AttachmentInfo(type, name, data, text)
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
    bindingWorldNames: List<String>,
    scrollTarget: Int?,
    onClearScrollTarget: () -> Unit,
    onCharacterBindingClick: () -> Unit,
    onWorldBindingClick: () -> Unit,
    onCardClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // 附件全文查看器：只有用户主动打开时才渲染全文（大文件不拖慢聊天列表）
    var viewingAttachment by remember { mutableStateOf<AttachmentInfo?>(null) }

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

    Column(modifier = modifier) {
        BindingBar(
            characterName = bindingCharacterName,
            worldNames = bindingWorldNames,
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
                    onCardClick = onCardClick,
                    onViewAttachment = { viewingAttachment = it }
                )
            }
        }
    }

    viewingAttachment?.let { attachment ->
        AttachmentViewerDialog(
            attachment = attachment,
            onDismiss = { viewingAttachment = null }
        )
    }
}

@Composable
private fun BindingBar(
    characterName: String?,
    worldNames: List<String>,
    onCharacterClick: () -> Unit,
    onWorldClick: () -> Unit
) {
    if (characterName == null && worldNames.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (characterName != null) {
            BindingChip(
                icon = Icons.Filled.Person,
                text = characterName,
                onClick = onCharacterClick
            )
        }
        // 多本世界书：每本一个提示 chip
        worldNames.forEach { name ->
            BindingChip(
                icon = Icons.Filled.MenuBook,
                text = name,
                onClick = onWorldClick
            )
        }
    }
}

@Composable
private fun BindingChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SystemPromptFold(systemPrompt: String) {
    FoldableSection(
        title = stringResource(R.string.msg_system_prompt_fold),
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
    onCardClick: (String) -> Unit,
    onViewAttachment: (AttachmentInfo) -> Unit
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
            ChatBubble(
                message = message,
                isUser = isUser,
                fontSize = fontSize,
                onCardClick = onCardClick,
                onViewAttachment = onViewAttachment
            )
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
            val bytes = Base64.decode(avatarBase64, Base64.DEFAULT)
            // 第三个参数是要解析的字节数，必须传实际长度（传 0 会返回 null 且不抛异常）
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    }
    Box(
        modifier = modifier.clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.msg_avatar_desc),
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
    onCardClick: (String) -> Unit,
    onViewAttachment: (AttachmentInfo) -> Unit
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

    // 附件与正文分离：文本附件的正文已剥离旧版 "[文件: x]" 前缀
    val attachment = rememberAttachmentInfo(message)
    val bodyText = attachment?.text ?: message.content
    val hasAttachment = attachment != null
    val hasBody = bodyText.isNotBlank()
    // 纯图片（无文字）不渲染气泡，只显示图片
    val showBubbleBody = hasBody || !hasAttachment

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // 思考内容折叠块
        if (!isUser && message.reasoningContent.isNotBlank()) {
            FoldableSection(
                title = stringResource(R.string.msg_thinking_label),
                preview = message.reasoningContent
            ) {
                ChatMarkdown(text = message.reasoningContent, fontSize = (fontSize - 1).coerceAtLeast(11))
            }
            Spacer(Modifier.height(4.dp))
        }

        if (hasAttachment) {
            AttachmentCard(
                attachment = attachment!!,
                isUser = isUser,
                onViewText = onViewAttachment
            )
        }

        if (showBubbleBody) {
            if (hasAttachment) Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 6.dp,
                    topEnd = if (isUser) 6.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = bubbleColor
            ) {
                if (!hasBody && message.reasoningContent.isBlank()) {
                    // 流式中：思考中…
                    Text(
                        text = stringResource(R.string.msg_thinking_dots),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                } else {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        parseContentBlocks(bodyText).forEach { block ->
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
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Badge,
                                                contentDescription = null,
                                                tint = textColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.msg_card_marker),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = textColor
                                            )
                                        }
                                    }
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

/**
 * 附件卡片：气泡内的附件展示。
 *
 * 设计要点（针对「用户可能上传几万字大文件」）：
 * - 气泡里**从不渲染文件全文**，避免 Markwon 解析几万字造成卡顿、气泡高度失控。
 * - 图片 → 直接显示缩略图（`remember` 缓存 bitmap，避免流式重组时反复 decode）。
 * - 文本 → 只显示卡片（文件名 + 规模 + token 估算）；短文件额外给几行纯文本预览（不用 Markdown，零解析成本）；
 *   全文通过「查看全文」按钮打开全屏查看器按需渲染。
 */
@Composable
private fun AttachmentCard(
    attachment: AttachmentInfo,
    isUser: Boolean,
    onViewText: (AttachmentInfo) -> Unit
) {
    val shape = RoundedCornerShape(12.dp)

    if (attachment.type == Message.ATTACH_TYPE_IMAGE) {
        // 图片：缓存解码结果，key 为 base64 长度（内容变化时长度必然变化）
        val bitmap = remember(attachment.data.length) {
            try {
                val bytes = Base64.decode(attachment.data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(220.dp)
                    .heightIn(max = 280.dp)
                    .clip(shape)
            )
        } else {
            AttachmentTextCard(
                icon = Icons.Filled.Description,
                title = attachment.name,
                subtitle = stringResource(R.string.attach_read_failed),
                isUser = isUser,
                footnote = null,
                onClick = null
            )
        }
    } else {
        val text = attachment.data
        val lines = text.count { it == '\n' } + 1
        val tokens = WorldInfoEngine.estimateTokens(text)

        AttachmentTextCard(
            icon = Icons.Filled.Description,
            title = attachment.name,
            subtitle = stringResource(R.string.attach_file_meta, text.length, lines),
            footnote = stringResource(R.string.attach_token_estimate, tokens),
            isUser = isUser,
            onClick = { onViewText(attachment) }
        )
    }
}

/** 文本附件卡片（不含全文，只给元信息 + 可选短预览） */
@Composable
private fun AttachmentTextCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isUser: Boolean,
    footnote: String?,
    onClick: (() -> Unit)?
) {
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() } else Modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.75f)
            )
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.6f)
                )
            }
            if (onClick != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.attach_view_full),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor
                )
            }
        }
    }
}

/**
 * 全屏附件查看器：按需渲染 + 可滚动 + 可复制。
 * 大文件不会被预先渲染（只有用户主动打开时才组合），因此不会拖慢聊天列表。
 */
@Composable
private fun AttachmentViewerDialog(
    attachment: AttachmentInfo,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = attachment.data,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(attachment.data))
                copied = true
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }) {
                Text(stringResource(if (copied) R.string.attach_copied else R.string.attach_copy))
            }
        }
    )
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
