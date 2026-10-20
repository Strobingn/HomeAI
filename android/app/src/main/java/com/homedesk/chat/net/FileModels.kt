package com.homedesk.chat.net

import kotlinx.serialization.Serializable

@Serializable
data class FileRoot(
    val path: String,
    val label: String,
    val writable: Boolean = true,
)

@Serializable
data class FileRoots(
    val mode: String = "all",
    val roots: List<FileRoot> = emptyList(),
)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val kind: String,
    val size: Long? = null,
    val modified: Long? = null,
    val text: Boolean = false,
    val link: Boolean = false,
    val blocked: Boolean = false,
)

@Serializable
data class FileList(
    val path: String = "",
    val parent: String? = null,
    val truncated: Boolean = false,
    val entries: List<FileEntry> = emptyList(),
)

@Serializable
data class FileText(
    val path: String,
    val name: String = "",
    val content: String = "",
    val size: Long = 0,
)

@Serializable
data class SavedFile(
    val path: String,
    val name: String = "",
    val bytes: Long = 0,
)

@Serializable
data class UploadResult(
    val saved: List<SavedFile> = emptyList(),
)

@Serializable
data class WriteResult(
    val path: String = "",
    val bytes: Long = 0,
    val backup: String? = null,
    val unchanged: Boolean = false,
)
