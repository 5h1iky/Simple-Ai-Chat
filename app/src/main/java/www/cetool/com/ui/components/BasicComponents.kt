package www.cetool.com.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import www.cetool.com.R

/**
 * 可折叠区块（对应 1.3 的系统提示词折叠块 / 思考折叠块）。
 * 参考 gpt_mobile 的 ThinkingBlock 写法，使用主题色 + 展开动画。
 */
@Composable
fun FoldableSection(
    title: String,
    preview: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!expanded && preview.isNotBlank()) {
                Text(
                    text = preview.take(60).replace("\n", " ") + if (preview.length > 60) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.6f)
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                // E6：全项目唯一漏网硬编码中文 —— 英文模式 TalkBack 会念中文
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

/**
 * 分组卡片容器（角色编辑/世界书编辑的分区卡片）
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // E14：这里的 Spacer(Modifier.width(0.dp)) 是无效占位，Column 下不产生任何效果
            Column(modifier = Modifier.padding(top = 12.dp)) {
                content()
            }
        }
    }
}

/**
 * 标签行（角色身份标签、关键词 chips）
 *
 * B24：原实现 `take(8)` 硬截断且从不传 onTagClick → 打错的标签永久留在卡里，
 * 超过 8 个的完全不可见也无法管理。现在全部标签都显示，并可逐个删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    onTagClick: ((String) -> Unit)? = null,
    onRemove: ((String) -> Unit)? = null
) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = if (onTagClick != null) {
                    Modifier.clickable { onTagClick(tag) }
                } else Modifier
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (onRemove != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onRemove(tag) }
                        )
                    }
                }
            }
        }
    }
}
