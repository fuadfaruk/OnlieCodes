package com.cusapps.onliecodes.model

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

class EditorSession(
    private val writeFile: suspend (OpenFile) -> Unit,
    private val switchProject: (Uri) -> Unit,
    private val onFilesChanged: (List<OpenFile>) -> Unit = {}
) {
    private sealed interface PendingAction {
        data class CloseTab(val uri: Uri) : PendingAction
        data class SwitchProject(val uri: Uri) : PendingAction
    }

    var openFiles by mutableStateOf<List<OpenFile>>(emptyList())
        private set
    private var activeFileUri by mutableStateOf<Uri?>(null)
    private var pendingAction by mutableStateOf<PendingAction?>(null)
    var isSaving by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val activeFile: OpenFile?
        get() = openFiles.find { it.uri == activeFileUri }

    val filesPendingSave: List<OpenFile>?
        get() = pendingAction?.let { modifiedFilesFor(it) }

    fun openFile(file: OpenFile) {
        if (openFiles.none { it.uri == file.uri }) {
            updateFiles(openFiles + file)
        }
        activeFileUri = file.uri
    }

    fun selectFile(uri: Uri) {
        if (openFiles.any { it.uri == uri }) activeFileUri = uri
    }

    fun updateContent(content: String) {
        val uri = activeFileUri ?: return
        updateFiles(openFiles.map { if (it.uri == uri) it.copy(content = content) else it })
    }

    fun renameFile(uri: Uri, name: String, newUri: Uri = uri) {
        updateFiles(openFiles.map { if (it.uri == uri) it.copy(uri = newUri, name = name) else it })
        if (activeFileUri == uri) activeFileUri = newUri
        if (pendingAction == PendingAction.CloseTab(uri)) pendingAction = PendingAction.CloseTab(newUri)
    }

    // Also used after the user explicitly confirms deletion of a file.
    fun removeFile(uri: Uri) {
        updateFiles(openFiles.filter { it.uri != uri })
        if (activeFileUri == uri) activeFileUri = openFiles.lastOrNull()?.uri
        if (pendingAction == PendingAction.CloseTab(uri)) {
            pendingAction = null
            errorMessage = null
        }
    }

    fun requestCloseTab(uri: Uri) {
        requestAction(PendingAction.CloseTab(uri))
    }

    fun requestSwitchProject(uri: Uri) {
        requestAction(PendingAction.SwitchProject(uri))
    }

    fun cancelPendingAction() {
        if (isSaving) return
        pendingAction = null
        errorMessage = null
    }

    fun discardPendingChanges() {
        if (isSaving) return
        pendingAction?.let { performAction(it) }
    }

    fun dismissError() {
        errorMessage = null
    }

    fun reportError(message: String) {
        if (errorMessage == null) errorMessage = message
    }

    private fun updateFiles(files: List<OpenFile>) {
        openFiles = files
        onFilesChanged(files)
    }

    suspend fun saveActiveFile() {
        if (isSaving || pendingAction != null) return
        val file = activeFile?.takeIf { it.isModified } ?: return
        saveFiles(listOf(file))
    }

    suspend fun savePendingChanges() {
        if (isSaving) return
        val action = pendingAction ?: return
        val saved = saveFiles(modifiedFilesFor(action))
        if (pendingAction != action) {
            errorMessage = null
            return
        }
        if (saved) {
            if (modifiedFilesFor(action).isEmpty()) {
                performAction(action)
            } else {
                errorMessage = "Some files changed while saving. Save again to continue, or choose Discard or Cancel."
            }
        }
    }

    private fun modifiedFilesFor(action: PendingAction): List<OpenFile> =
        openFiles.filter { file ->
            file.isModified && when (action) {
                is PendingAction.CloseTab -> file.uri == action.uri
                is PendingAction.SwitchProject -> true
            }
        }

    private fun requestAction(action: PendingAction) {
        if (isSaving || pendingAction != null) return
        errorMessage = null
        if (modifiedFilesFor(action).isEmpty()) {
            performAction(action)
        } else {
            pendingAction = action
        }
    }

    private fun performAction(action: PendingAction) {
        pendingAction = null
        errorMessage = null
        when (action) {
            is PendingAction.CloseTab -> removeFile(action.uri)
            is PendingAction.SwitchProject -> {
                try {
                    switchProject(action.uri)
                    updateFiles(emptyList())
                    activeFileUri = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = "Could not open the selected project: ${e.message ?: "Check folder access permissions."}"
                }
            }
        }
    }

    private suspend fun saveFiles(files: List<OpenFile>): Boolean {
        isSaving = true
        errorMessage = null
        val failures = mutableListOf<String>()
        try {
            for (file in files) {
                // A failed or canceled write may already have truncated the document.
                updateFiles(openFiles.map {
                    if (it.uri == file.uri) it.copy(savedContent = null) else it
                })
                try {
                    writeFile(file)
                    // Only advance the saved baseline; never replace edits made during IO.
                    updateFiles(openFiles.map {
                        if (it.uri == file.uri) it.copy(savedContent = file.content) else it
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "Could not save '${file.name}': ${e.message ?: "Write failed."}"
                }
            }
            errorMessage = failures.takeIf { it.isNotEmpty() }?.joinToString("\n")
            return failures.isEmpty()
        } finally {
            isSaving = false
        }
    }
}
