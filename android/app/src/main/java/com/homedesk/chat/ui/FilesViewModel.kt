package com.homedesk.chat.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homedesk.chat.AppGraph
import com.homedesk.chat.data.joinPcPath
import com.homedesk.chat.data.pcFileName
import com.homedesk.chat.net.DeskCallException
import com.homedesk.chat.net.FileEntry
import com.homedesk.chat.net.FileRoot
import com.homedesk.chat.net.explainTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

data class StagedFile(
    val name: String,
    val file: File,
    val size: Long,
)

data class FilesUiState(
    val configured: Boolean = true,
    val loading: Boolean = true,
    val atRoots: Boolean = true,
    val title: String = "Files",
    val path: String? = null,
    val parent: String? = null,
    val mode: String = "all",
    val roots: List<FileRoot> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val staged: List<StagedFile> = emptyList(),
    val uploading: Boolean = false,
    val uploadLabel: String = "",
    val uploadFraction: Float? = null,
)

class FilesViewModel(
    private val graph: AppGraph,
    private val acceptShares: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private var uploadJob: Job? = null

    init {
        if (acceptShares) {
            viewModelScope.launch {
                graph.share.pending.collect { incoming ->
                    if (incoming.id == 0L) return@collect
                    val uris = incoming.uris
                    val text = incoming.text
                    graph.share.clear()
                    stageIncoming(uris, text)
                }
            }
        }
        refresh()
    }

    fun refresh() {
        val current = _state.value
        if (current.atRoots || current.path.isNullOrBlank()) {
            loadRoots()
        } else {
            loadDir(current.path, current.title)
        }
    }

    fun openRoot(root: FileRoot) {
        loadDir(root.path, root.label)
    }

    fun openDir(entry: FileEntry) {
        loadDir(entry.path, entry.name)
    }

    fun up() {
        val parent = _state.value.parent
        if (parent.isNullOrBlank()) loadRoots() else loadDir(parent, pcFileName(parent))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun showError(message: String) = _state.update { it.copy(error = message, notice = null) }

    fun stageUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { stageIncoming(uris, null) }
    }

    fun removeStaged(staged: StagedFile) {
        staged.file.delete()
        _state.update { it.copy(staged = it.staged.filterNot { item -> item.file == staged.file }) }
    }

    fun clearStaged() {
        _state.value.staged.forEach { it.file.delete() }
        _state.update { it.copy(staged = emptyList()) }
    }

    fun uploadStaged() {
        val snapshot = _state.value
        val directory = snapshot.path
        if (directory.isNullOrBlank()) {
            showError("Open a drive or folder, then upload.")
            return
        }
        if (snapshot.staged.isEmpty() || snapshot.uploading) return
        val settings = graph.settings.get()
        if (!settings.isReady) {
            showError("Add the server URL and API key in Settings.")
            return
        }
        val batch = snapshot.staged
        uploadJob = viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            try {
                for (staged in batch) {
                    _state.update {
                        it.copy(
                            uploading = true,
                            uploadLabel = "${done + 1} of ${batch.size} · ${staged.name}",
                            uploadFraction = 0f,
                            error = null,
                        )
                    }
                    graph.files.upload(
                        settings.baseUrl,
                        settings.apiKey,
                        directory,
                        staged.name,
                        staged.file,
                    ) { sent, total ->
                        val fraction = if (total <= 0L) null else sent.toFloat() / total.toFloat()
                        _state.update { state -> state.copy(uploadFraction = fraction) }
                    }
                    staged.file.delete()
                    done += 1
                }
                _state.update {
                    it.copy(
                        uploading = false,
                        staged = emptyList(),
                        uploadLabel = "",
                        uploadFraction = null,
                        notice = if (batch.size == 1) {
                            "Saved ${batch.first().name} on the PC."
                        } else {
                            "Saved ${batch.size} files on the PC."
                        },
                    )
                }
                loadDir(directory)
            } catch (cancel: CancellationException) {
                _state.update {
                    it.copy(
                        uploading = false,
                        staged = batch.drop(done),
                        uploadLabel = "",
                        uploadFraction = null,
                    )
                }
                throw cancel
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        uploading = false,
                        staged = batch.drop(done),
                        uploadLabel = "",
                        uploadFraction = null,
                        error = failure(error, settings.baseUrl),
                    )
                }
                loadDir(directory)
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
    }

    fun createFolder(name: String) {
        val directory = _state.value.path
        if (directory.isNullOrBlank()) {
            showError("Open a drive or folder first.")
            return
        }
        val clean = name.trim()
        if (clean.isEmpty() || '/' in clean || '\\' in clean) {
            showError("That folder name is not valid.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                showError("Add the server URL and API key in Settings.")
                return@launch
            }
            try {
                graph.files.makeFolder(settings.baseUrl, settings.apiKey, joinPcPath(directory, clean))
                _state.update { it.copy(notice = "Created $clean.", error = null) }
                loadDir(directory, _state.value.title)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(error = failure(error, settings.baseUrl)) }
            }
        }
    }

    fun rename(entry: FileEntry, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == entry.name || '/' in clean || '\\' in clean) {
            if (clean != entry.name) showError("That name is not valid.")
            return
        }
        val directory = _state.value.path
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                showError("Add the server URL and API key in Settings.")
                return@launch
            }
            try {
                graph.files.rename(settings.baseUrl, settings.apiKey, entry.path, clean)
                _state.update { it.copy(notice = "Renamed to $clean.", error = null) }
                if (!directory.isNullOrBlank()) loadDir(directory, _state.value.title)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(error = failure(error, settings.baseUrl)) }
            }
        }
    }

    fun delete(entry: FileEntry) {
        val directory = _state.value.path
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                showError("Add the server URL and API key in Settings.")
                return@launch
            }
            try {
                graph.files.delete(settings.baseUrl, settings.apiKey, entry.path)
                _state.update {
                    it.copy(notice = "Moved ${entry.name} to the trash on the PC.", error = null)
                }
                if (!directory.isNullOrBlank()) loadDir(directory, _state.value.title)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(error = failure(error, settings.baseUrl)) }
            }
        }
    }

    fun downloadTo(path: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                showError("Add the server URL and API key in Settings.")
                return@launch
            }
            try {
                val stream = graph.app.contentResolver.openOutputStream(uri)
                    ?: throw DeskCallException("The phone could not open a place to save that file.")
                stream.use { output ->
                    graph.files.download(settings.baseUrl, settings.apiKey, path, output)
                }
                _state.update {
                    it.copy(notice = "Saved ${pcFileName(path)} on this phone.", error = null)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(error = failure(error, settings.baseUrl)) }
            }
        }
    }

    override fun onCleared() {
        uploadJob?.cancel()
        _state.value.staged.forEach { it.file.delete() }
        super.onCleared()
    }

    private fun loadRoots() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                _state.update {
                    it.copy(configured = false, loading = false, atRoots = true, path = null, title = "Files")
                }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, configured = true) }
            try {
                val body = graph.files.roots(settings.baseUrl, settings.apiKey)
                _state.update {
                    it.copy(
                        loading = false,
                        atRoots = true,
                        path = null,
                        parent = null,
                        title = "Files",
                        mode = body.mode,
                        roots = body.roots,
                        entries = emptyList(),
                        truncated = false,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = failure(error, settings.baseUrl)) }
            }
        }
    }

    private fun loadDir(path: String, title: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = graph.settings.get()
            if (!settings.isReady) {
                _state.update { it.copy(configured = false, loading = false) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, configured = true) }
            try {
                val listing = graph.files.list(settings.baseUrl, settings.apiKey, path)
                val shown = listing.path.ifBlank { path }
                _state.update {
                    it.copy(
                        loading = false,
                        atRoots = false,
                        path = shown,
                        parent = listing.parent,
                        title = title ?: pcFileName(shown),
                        entries = listing.entries,
                        truncated = listing.truncated,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = failure(error, settings.baseUrl)) }
            }
        }
    }

    private suspend fun stageIncoming(uris: List<Uri>, text: String?) {
        val staged = mutableListOf<StagedFile>()
        val problems = mutableListOf<String>()
        for (uri in uris) {
            try {
                staged += stageUri(uri)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                problems += error.message ?: "Could not read a file from the phone."
            }
        }
        if (uris.isEmpty() && !text.isNullOrBlank()) {
            try {
                staged += stageText(text)
            } catch (error: Exception) {
                problems += error.message ?: "Could not read that shared text."
            }
        }
        _state.update {
            it.copy(
                staged = it.staged + staged,
                error = problems.firstOrNull(),
                notice = if (staged.isNotEmpty() && it.path.isNullOrBlank()) {
                    "Open a drive or folder, then upload."
                } else {
                    it.notice
                },
            )
        }
    }

    private suspend fun stageUri(uri: Uri): StagedFile = withContext(Dispatchers.IO) {
        val resolver = graph.app.contentResolver
        val name = displayName(resolver, uri)
        val reported = reportedSize(resolver, uri)
        if (reported != null && reported > STAGE_LIMIT) {
            throw DeskCallException("That file is too large to send from the phone.")
        }
        copyToCache(name) { output ->
            resolver.openInputStream(uri)?.use { input -> input.copyTo(output) }
                ?: throw DeskCallException("The phone would not let Desk read that file. Try sharing it again.")
        }
    }

    private suspend fun stageText(text: String): StagedFile = withContext(Dispatchers.IO) {
        copyToCache("shared.txt") { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun copyToCache(name: String, write: (java.io.OutputStream) -> Unit): StagedFile {
        val folder = File(graph.app.cacheDir, "uploads").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "file" }
        val dest = File(folder, "${UUID.randomUUID()}-$safe")
        try {
            dest.outputStream().use(write)
        } catch (error: Exception) {
            dest.delete()
            if (error is DeskCallException) throw error
            throw DeskCallException("Could not read that file from the phone.")
        }
        if (dest.length() > STAGE_LIMIT) {
            dest.delete()
            throw DeskCallException("That file is too large to send from the phone.")
        }
        return StagedFile(name = name, file = dest, size = dest.length())
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)?.trim().orEmpty()
                if (name.isNotEmpty()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "shared-file"
    }

    private fun reportedSize(resolver: ContentResolver, uri: Uri): Long? {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun failure(error: Throwable, url: String): String {
        return when (error) {
            is DeskCallException -> error.message ?: "The server rejected that request."
            is IOException -> explainTransport(error, url)
            else -> error.message ?: "Something went wrong."
        }
    }

    private companion object {
        const val STAGE_LIMIT = 2048L * 1024L * 1024L
    }
}
