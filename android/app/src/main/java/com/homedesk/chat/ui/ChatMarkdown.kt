package com.homedesk.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Deliberately small renderer: readable prose, headings, lists and fenced code. */
data class MessageBlock(val kind: String, val text: String, val language: String = "")

fun messageBlocks(raw: String): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    val prose = mutableListOf<String>()
    val code = mutableListOf<String>()
    var language: String? = null
    fun flush() {
        if (prose.isNotEmpty()) {
            blocks += MessageBlock("prose", prose.joinToString("\n"))
            prose.clear()
        }
    }
    for (line in raw.lines()) {
        if (line.trimStart().startsWith("```")) {
            if (language == null) {
                flush()
                language = line.trim().removePrefix("```").trim()
            } else {
                blocks += MessageBlock("code", code.joinToString("\n"), language)
                code.clear()
                language = null
            }
        } else if (language != null) code += line
        else if (line.isBlank()) flush()
        else if (line.startsWith("#")) {
            flush()
            blocks += MessageBlock("heading", line.trimStart('#').trim())
        } else prose += line
    }
    flush()
    if (language != null) blocks += MessageBlock("code", code.joinToString("\n"), language)
    return blocks
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        if (match.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(match.groupValues[1])
        } else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Suppress("DEPRECATION")
@Composable
fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { messageBlocks(text) }
    val clipboard = LocalClipboardManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                "code" -> Surface(shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp)) {
                            Text(block.language.ifBlank { "Code" }, Modifier.weight(1f).padding(top = 14.dp),
                                style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)) }) { Text("Copy") }
                        }
                        SelectionContainer {
                            Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
                                style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                "heading" -> SelectionContainer {
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.titleMedium)
                }
                else -> SelectionContainer {
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
