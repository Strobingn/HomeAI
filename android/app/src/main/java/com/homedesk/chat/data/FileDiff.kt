package com.homedesk.chat.data

data class DiffRow(val kind: String, val text: String)

/**
 * A preview of what would change. Small files get a real line diff.
 * Large files show the changed middle, with the shared start and end omitted.
 */
fun previewDiff(before: String, after: String, context: Int = 2): List<DiffRow> {
    if (before == after) return emptyList()
    val oldLines = splitLines(before)
    val newLines = splitLines(after)
    if (oldLines.size <= 400 && newLines.size <= 400) {
        return compact(lineDiff(oldLines, newLines), context)
    }
    return compact(middleDiff(oldLines, newLines), context)
}

private fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return text.split('\n').map { it.removeSuffix("\r") }
}

private fun lineDiff(oldLines: List<String>, newLines: List<String>): List<DiffRow> {
    val rows = mutableListOf<DiffRow>()
    val width = oldLines.size + 1
    val height = newLines.size + 1
    val scores = Array(width) { IntArray(height) }
    for (i in oldLines.size - 1 downTo 0) {
        for (j in newLines.size - 1 downTo 0) {
            scores[i][j] = if (oldLines[i] == newLines[j]) {
                scores[i + 1][j + 1] + 1
            } else {
                maxOf(scores[i + 1][j], scores[i][j + 1])
            }
        }
    }
    var i = 0
    var j = 0
    while (i < oldLines.size && j < newLines.size) {
        when {
            oldLines[i] == newLines[j] -> {
                rows += DiffRow("same", oldLines[i])
                i += 1
                j += 1
            }
            scores[i + 1][j] >= scores[i][j + 1] -> {
                rows += DiffRow("remove", oldLines[i])
                i += 1
            }
            else -> {
                rows += DiffRow("add", newLines[j])
                j += 1
            }
        }
    }
    while (i < oldLines.size) {
        rows += DiffRow("remove", oldLines[i])
        i += 1
    }
    while (j < newLines.size) {
        rows += DiffRow("add", newLines[j])
        j += 1
    }
    return rows
}

private fun middleDiff(oldLines: List<String>, newLines: List<String>): List<DiffRow> {
    var start = 0
    while (start < oldLines.size && start < newLines.size && oldLines[start] == newLines[start]) {
        start += 1
    }
    var oldEnd = oldLines.size
    var newEnd = newLines.size
    while (oldEnd > start && newEnd > start && oldLines[oldEnd - 1] == newLines[newEnd - 1]) {
        oldEnd -= 1
        newEnd -= 1
    }
    val rows = mutableListOf<DiffRow>()
    if (start > 0) rows += DiffRow("skip", "…")
    for (line in oldLines.subList(start, oldEnd)) rows += DiffRow("remove", line)
    for (line in newLines.subList(start, newEnd)) rows += DiffRow("add", line)
    if (oldEnd < oldLines.size || newEnd < newLines.size) rows += DiffRow("skip", "…")
    return rows
}

private fun compact(rows: List<DiffRow>, context: Int): List<DiffRow> {
    if (rows.isEmpty()) return rows
    val changed = rows.indices.filter { rows[it].kind != "same" }
    if (changed.isEmpty()) return emptyList()
    val keep = BooleanArray(rows.size)
    for (index in changed) {
        val from = (index - context).coerceAtLeast(0)
        val to = (index + context).coerceAtMost(rows.lastIndex)
        for (cursor in from..to) keep[cursor] = true
    }
    val kept = mutableListOf<DiffRow>()
    var skipping = false
    for (index in rows.indices) {
        if (keep[index]) {
            skipping = false
            kept += rows[index]
        } else if (!skipping) {
            skipping = true
            kept += DiffRow("skip", "…")
        }
    }
    return kept
}
