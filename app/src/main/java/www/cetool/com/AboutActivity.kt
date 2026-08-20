package www.cetool.com

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import www.cetool.com.ui.theme.SAChatTheme

/**
 * 关于页（Compose 版）
 * 品牌感布局：渐变圆角徽标 + 功能亮点 chips + 列表卡片（作者/反馈/免责声明/开源许可）。
 */
class AboutActivity : ComponentActivity() {

    private val BILIBILI_URL = "https://space.bilibili.com/432122433"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "1.0" }

        setContent {
            SAChatTheme {
                AboutScreen(version = "v$version", onFeedback = {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BILIBILI_URL)))
                })
            }
        }
    }
}

@Composable
private fun AboutScreen(version: String, onFeedback: () -> Unit) {
    var showDisclaimer by remember { mutableStateOf(false) }
    val disclaimerContent = stringResource(R.string.disclaimer_content)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // 软件图标（launcher 图标，圆角显示）
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = "SAChat",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "$version · 轻量 AI 聊天",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(32.dp))

        // 列表卡片
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column {
                AboutRow(
                    icon = Icons.Filled.AccountCircle,
                    title = "作者 · 明日awo",
                    desc = "99% 纯天然 AI 制作",
                    onClick = { /* 无跳转 */ }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                AboutRow(
                    icon = Icons.Filled.Email,
                    title = stringResource(R.string.about_feedback),
                    desc = stringResource(R.string.about_feedback_desc),
                    onClick = onFeedback
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                AboutRow(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.disclaimer),
                    desc = stringResource(R.string.disclaimer_desc),
                    onClick = { showDisclaimer = true }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                AboutRow(
                    icon = Icons.Filled.Description,
                    title = "开源许可 · MIT",
                    desc = "可自由使用、修改与分发",
                    onClick = { /* 无跳转 */ }
                )
            }
        }

        Spacer(Modifier.height(40.dp))
        Text(
            text = "Copyright © 2026 5h1iky",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDisclaimer) {
        DisclaimerDialog(
            content = disclaimerContent,
            onDismiss = { showDisclaimer = false }
        )
    }
}

@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 40dp 圆形着色图标底
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun DisclaimerDialog(content: String, onDismiss: () -> Unit) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = {
            Text(
                text = htmlToAnnotated(content),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .heightIn(max = (screenHeightDp * 0.6f).dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
        }
    )
}

/**
 * 轻量 HTML → AnnotatedString 转换（支持 <b> <i> <br> <p>，其余标签忽略）。
 * 免责声明原文为 HTML 富文本，Compose 无 Html.fromHtml，这里做等价渲染。
 */
private fun htmlToAnnotated(html: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var index = 0
        var bold = false
        var italic = false
        while (index < html.length) {
            val c = html[index]
            if (c == '<') {
                val end = html.indexOf('>', index)
                if (end > index) {
                    val tag = html.substring(index + 1, end).trim().lowercase()
                    when {
                        tag == "b" -> bold = true
                        tag == "/b" -> bold = false
                        tag == "i" -> italic = true
                        tag == "/i" -> italic = false
                        tag == "br" || tag == "br/" || tag == "br /" -> append("\n")
                        tag == "/p" -> append("\n")
                        // <p> 及其他标签：忽略标签本身
                    }
                    index = end + 1
                } else {
                    append(c)
                    index++
                }
            } else {
                val next = html.indexOf('<', index)
                val segment = if (next < 0) html.substring(index) else html.substring(index, next)
                withStyle(
                    SpanStyle(
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
                    )
                ) { append(segment) }
                index = if (next < 0) html.length else next
            }
        }
    }
}
