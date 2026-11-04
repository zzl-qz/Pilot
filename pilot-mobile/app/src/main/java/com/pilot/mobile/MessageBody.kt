package com.pilot.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal data class MessageBlock(val text: String, val code: Boolean = false, val language: String = "", val heading: Boolean = false)

/** 展示常见段落、标题和 fenced code；不完整流式代码块仍保留全部正文。 */
internal fun messageBlocks(text: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val buffer = mutableListOf<String>()
    var code = false
    var language = ""
    fun flush() {
        if (buffer.isNotEmpty()) { blocks += MessageBlock(buffer.joinToString("\n"), code, language); buffer.clear() }
    }
    text.split('\n').forEach { line ->
        val fence = line.trimStart()
        when {
            fence.startsWith("```") && (!code || fence.trim() == "```") -> {
                flush(); code = !code; language = if (code) fence.removePrefix("```").trim().take(40) else ""
            }
            !code && line.isBlank() -> flush()
            !code && Regex("^#{1,4} .+").matches(line) -> { flush(); blocks += MessageBlock(line.replaceFirst(Regex("^#{1,4} "), ""), heading = true) }
            else -> buffer += line
        }
    }
    flush()
    return blocks
}

private val Inline = Regex("\\*\\*([^*]+)\\*\\*|`([^`\\n]+)`")
internal fun messageInline(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    Inline.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        if (match.groups[1] != null) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
fun MessageBody(text: String) {
    val blocks = remember(text) { messageBlocks(text) }
    val clipboard = LocalClipboardManager.current
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            blocks.forEach { block ->
                if (block.code) {
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            var copied by remember(block.text) { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(block.language.ifBlank { "代码" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) { Text(if (copied) "已复制" else "复制代码", style = MaterialTheme.typography.labelMedium) }
                            }
                            Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else Text(messageInline(block.text), style = if (block.heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
