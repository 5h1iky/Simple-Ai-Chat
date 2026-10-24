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
    // NullSafeTypeAdapterFactory 会把 JSON 显式 null 的 attachmentType 翻成 ""，
    // 直接判空会失效 → 可能渲染"幻影附件卡"，先过滤空白串再走原逻辑
    val type = message.attachmentType?.takeIf { it.isNotBlank() } ?: return null
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
    conversationId: String = "",
    modifier: Modifier = Modifier
) {
    // M16：LazyListState 跨会话残留 —— 按会话 id 隔离，切换会话时重建滚动状态
    val listState = remember(conversationId) { LazyListState() }
    // 附件全文查看器：只有用户主动打开时才渲染全文（大文件不拖慢聊天列表）
    var viewingAttachment by remember { mutableStateOf<AttachmentInfo?>(null) }

    // B9：系统提示折叠项占 LazyColumn 的 index 0，消息 i 实际位于 index i+1。
    // 直接用 messages 的下标滚会永远差一格（系统提示非空时 100% 发生）。
    val headerCount = if (systemPromptPreview.isNotBlank()) 1 else 0
    val messageToIndex: (Int) -> Int = { it + headerCount }

    // 新消息自动滚动到底部
    // M16：key 加入会话 id 与末条 content 长度 —— 流式回复时 size 不变但内容增长也要跟随滚动，
    // 切到消息数相同的另一会话时也要重启 effect（否则滚动位置沿用旧会话）
    LaunchedEffect(
        conversationId, messages.size, headerCount, messages.lastOrNull()?.content?.length
    ) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messageToIndex(messages.size - 1))
        }
    }
    // 搜索跳转
    LaunchedEffect(scrollTarget) {
        val target = scrollTarget
        if (target != null && target >= 0 && target < messages.size) {
            listState.animateScrollToItem(messageToIndex(target))
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
            // M15：Message 无稳定 id，用「时间戳+下标」复合 key 防止删除/重生成时
            // LazyColumn 按位置复用组合状态（FoldableSection 展开态等迁移到相邻消息）
            itemsIndexed(messages, key = { index, m -> "${m.timestamp}-$index" }) { _, message ->
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
            // M13：头像只进 32dp 圆圈，按 256px 采样解码（含精确缩放），
            // 避免每条消息各持一份全尺寸副本
            www.cetool.com.util.ImageDecoder.decodeByteArray(bytes, maxEdge = 256)
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
                    // D5：parseContentBlocks 内部跑正则，原来每次重组都会整段重跑；
                    // 流式期间每个 token 都会重组，正文越长越贵。按正文内容记忆结果。
                    val blocks = remember(bodyText) { parseContentBlocks(bodyText) }
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        blocks.forEach { block ->
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

        // D5：原实现每次重组都 new 一个 SimpleDateFormat（构造有解析/初始化成本），
        // 且流式期间每个 token 都会重组全部气泡。改为用线程局部实例 + 按时间戳记忆结果。
        val timeText = remember(message.timestamp) { formatClock(message.timestamp) }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * D5：SimpleDateFormat 不是线程安全的，不能做成全局单例；
 * 但也没必要每次重建 —— 用 ThreadLocal 复用，并配合 remember 避免重复格式化。
 */
private val clockFormat = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
}

private fun formatClock(timestamp: Long): String =
    clockFormat.get()!!.format(java.util.Date(timestamp))

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
        // 图片：缓存解码结果，key 为 base64 内容本身（原用 data.length ——
        // 「长度变化 ⇔ 内容变化」是错误假设，长度相同的两张不同图会显示旧图）
        val bitmap = remember(attachment.data) {
            try {
                val bytes = Base64.decode(attachment.data, Base64.DEFAULT)
                // D3 同款限尺寸解码：旧数据/导入数据可能存全尺寸大图，直接 decode
                // 一张 8000×6000 就要约 192MB，必须先降采样。
                www.cetool.com.util.ImageDecoder.decodeByteArray(bytes, maxEdge = 1024)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                // P1：OutOfMemoryError 是 Error 不是 Exception —— 与 ChatAvatar 一致双 catch，
                // 否则组合期抛 OOM 会让该会话每次打开都复崩。
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
    // E15：连点两次时，先前那个 delay 协程仍会在 1.5s 后把 copied 置回 false，
    // 于是提示提前消失。改为持有当前任务并在新点击时取消它。
    var resetJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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
                // E15：取消上一次的复位任务，确保 1.5s 从"最后一次点击"开始计时
                resetJob?.cancel()
                resetJob = scope.launch {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }) {
                Text(stringResource(if (copied) R.string.attach_copied else R.string.attach_copy))
            }
        }
    )
}

/**
 * Markdown 渲染：复用 Markwon（AndroidView 包装）。
 *
 * D5：原本每个气泡都 `remember { Markwon.builder(...).build() }` —— N 条消息就是 N 个
 * Markwon 实例（各自带插件表与缓存），是列表滚动卡顿的主要来源。
 * 改为进程内的每 context 共享一个实例：这样既不依赖 CompositionLocal 的提供时机，
 * 又避免了组合 ts——每条消息仍在自己的 AndroidView 里渲染，只是不再各自建解析器。
 */
@Composable
fun ChatMarkdown(
    text: String,
    fontSize: Int = 15,
    textColor: androidx.compose.ui.graphics.Color? = null
) {
    val context = LocalContext.current
    val markwon = remember(context) { www.cetool.com.util.MarkwonProvider.get(context) }
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
