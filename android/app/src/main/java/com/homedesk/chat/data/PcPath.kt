package com.homedesk.chat.data

/** Join a folder on the PC with a child name. Keeps Windows drive roots intact. */
fun joinPcPath(parent: String, name: String): String {
    val child = name.trim().trim('/', '\\')
    if (parent.isEmpty()) return child
    if (parent == "/") return "/$child"
    val windows = '\\' in parent || (parent.length >= 2 && parent[1] == ':')
    val separator = if (windows) '\\' else '/'
    val base = parent.trimEnd('\\', '/')
    if (base.length == 2 && base[1] == ':') return "$base\\$child"
    if (base.isEmpty()) return "$separator$child"
    return base + separator + child
}

fun pcFileName(path: String): String {
    val trimmed = path.trimEnd('\\', '/')
    val name = trimmed.substringAfterLast('\\').substringAfterLast('/')
    return name.ifBlank { path }
}
