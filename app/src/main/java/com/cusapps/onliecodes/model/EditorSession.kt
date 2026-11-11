package com.cusapps.onliecodes.model

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

class EditorSession(
    private val writeFile: suspend (OpenFile) -> Unit,
    private val switchProject: (Uri) -> Unit,
    private val onFilesChanged: (List<OpenFile>) -> Unit = {},
    private val onSessionChanged: () -> Unit = {}
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
    var errorTitle by mutableStateOf("Action Failed")
        private set

    val activeFile: OpenFile?
        get() = openFiles.find { it.uri == activeFileUri }

    val filesPendingSave: List<OpenFile>?
        get() = pendingAction?.let { modifiedFilesFor(it) }

    fun openFile(file: OpenFile) {
        if (openFiles.none { it.uri == file.uri }) {
            updateFiles(openFiles + file, file.uri)
        } else {
            selectFile(file.uri)
        }
    }

    fun restoreFiles(files: List<OpenFile>, activeUri: Uri?) {
        updateFiles(files.distinctBy { it.uri }, activeUri)
    }

    fun selectFile(uri: Uri) {
        if (activeFileUri != uri && openFiles.any { it.uri == uri }) {
            activeFileUri = uri
            onSessionChanged()
        }
    }

    fun updateContent(content: String) {
        val uri = activeFileUri ?: return
        updateFiles(openFiles.map { if (it.uri == uri) it.copy(content = content) else it })
    }

    fun renameFile(uri: Uri, name: String, newUri: Uri = uri) {
        if (pendingAction == PendingAction.CloseTab(uri)) pendingAction = PendingAction.CloseTab(newUri)
        updateFiles(
            openFiles.map { if (it.uri == uri) it.copy(uri = newUri, name = name) else it },
            if (activeFileUri == uri) newUri else activeFileUri
        )
    }

    // Also used after the user explicitly confirms deletion of a file.
    fun removeFile(uri: Uri) {
        if (pendingAction == PendingAction.CloseTab(uri)) {
            pendingAction = null
            dismissError()
        }
        updateFiles(openFiles.filter { it.uri != uri })
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
        dismissError()
    }

    fun discardPendingChanges() {
        if (isSaving) return
        dismissError()
        pendingAction?.let { performAction(it) }
    }

    fun dismissError() {
        errorMessage = null
        errorTitle = "Action Failed"
    }

    fun reportError(message: String) {
        if (errorMessage == null) errorMessage = message
    }

    private fun updateFiles(files: List<OpenFile>, activeUri: Uri? = activeFileUri) {
        openFiles = files
        activeFileUri = activeUri?.takeIf { uri -> files.any { it.uri == uri } }
            ?: files.lastOrNull()?.uri
        onFilesChanged(files)
        onSessionChanged()
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
            if (pendingAction == null) dismissError()
            return
        }
        if (saved) {
            if (modifiedFilesFor(action).isEmpty()) {
                performAction(action)
            } else {
                errorTitle = "Unsaved Changes"
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
        dismissError()
        if (modifiedFilesFor(action).isEmpty()) {
            performAction(action)
        } else {
            pendingAction = action
        }
    }

    private fun performAction(action: PendingAction) {
        pendingAction = null
        when (action) {
            is PendingAction.CloseTab -> removeFile(action.uri)
            is PendingAction.SwitchProject -> {
                try {
                    switchProject(action.uri)
                    updateFiles(emptyList(), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorTitle = "Action Failed"
                    errorMessage = "Could not open the selected project: ${e.message ?: "Check folder access permissions."}"
                }
            }
        }
    }

    private suspend fun saveFiles(files: List<OpenFile>): Boolean {
        isSaving = true
        dismissError()
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
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: "Write failed."
                    failures += "Could not save '${file.name}': $reason"
                }
            }
            if (failures.isNotEmpty()) {
                errorTitle = "Save Failed"
                val saveError = failures.joinToString("\n") +
                    "\n\nYour changes are still unsaved. Check file access and available storage, then try saving again."
                // Do not erase a draft or session error reported while document IO was running.
                errorMessage = listOfNotNull(saveError, errorMessage).joinToString("\n\n")
            }
            return failures.isEmpty()
        } finally {
            isSaving = false
        }
    }
}
