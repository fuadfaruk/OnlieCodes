package com.cusapps.onliecodes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import android.webkit.MimeTypeMap
import com.cusapps.onliecodes.model.DraftAutosaver
import com.cusapps.onliecodes.model.EditorSession
import com.cusapps.onliecodes.model.LocalDraftStore
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.model.ProjectFile
import com.cusapps.onliecodes.ui.MainScreen
import com.cusapps.onliecodes.ui.theme.OnlieCodesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val _projectName = MutableStateFlow<String?>(null)
    private val _projectFiles = MutableStateFlow<List<ProjectFile>>(emptyList())
    private val _projectMissing = MutableStateFlow(false)
    private val draftStore by lazy {
        LocalDraftStore(File(applicationContext.noBackupFilesDir, "drafts"))
    }
    private val drafts: DraftAutosaver by lazy {
        DraftAutosaver(lifecycleScope, draftStore, onError = { editor.reportError(it) })
    }
    private val editor = EditorSession(
        writeFile = { writeFile(it) },
        switchProject = { switchProject(it) },
        onFilesChanged = { drafts.updateFiles(it) }
    )

    private var isDarkTheme by mutableStateOf(true)

    private var rootTreeUri: Uri? = null
    private var projectGeneration = 0L

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            if (uri != rootTreeUri) editor.requestSwitchProject(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreLastProject()
        isDarkTheme = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("dark_theme", true)
        setContent {
            OnlieCodesTheme(darkTheme = isDarkTheme) {
                val projectNameState = _projectName.collectAsState()
                val projectFilesState = _projectFiles.collectAsState()
                val projectMissingState = _projectMissing.collectAsState()

                MainScreen(
                    projectName = projectNameState.value,
                    projectFiles = projectFilesState.value,
                    openFiles = editor.openFiles,
                    activeFile = editor.activeFile,
                    projectMissing = projectMissingState.value,
                    filesPendingSave = editor.filesPendingSave,
                    isSaving = editor.isSaving,
                    errorMessage = editor.errorMessage,
                    onOpenFolderClick = { openFolderPicker() },
                    onSelectProjectClick = { openFolderPicker() },
                    onInitializeProjectClick = { openFolderPicker() },
                    onProjectMissingDismissed = { _projectMissing.value = false },
                    onFileClick = { file -> openFile(file) },
                    onToggleExpand = { file -> toggleFolderExpand(file) },
                    onTabSelect = { openFile -> editor.selectFile(openFile.uri) },
                    onTabClose = { openFile -> editor.requestCloseTab(openFile.uri) },
                    onContentChange = { newContent -> editor.updateContent(newContent) },
                    onSaveClick = { lifecycleScope.launch { editor.saveActiveFile() } },
                    onSaveChanges = { lifecycleScope.launch { editor.savePendingChanges() } },
                    onDiscardChanges = { editor.discardPendingChanges() },
                    onCancelChanges = { editor.cancelPendingAction() },
                    onErrorDismissed = { editor.dismissError() },
                    onCreateFile = { parent, name -> createFile(parent, name) },
                    onCreateFolder = { parent, name -> createFolder(parent, name) },
                    onDeleteFile = { file -> deleteFile(file) },
                    onRenameFile = { file, newName -> renameFile(file, newName) },
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { toggleTheme() }
                )
            }
        }
    }

    override fun onStop() {
        drafts.flush()
        super.onStop()
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_theme", isDarkTheme)
            .apply()
    }

    private fun saveRootTreeUri(uri: Uri) {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("last_project_uri", uri.toString())
            .apply()
    }

    private fun clearSavedRootTreeUri() {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .remove("last_project_uri")
            .apply()
    }

    private fun openFolderPicker() {
        if (editor.isSaving || editor.filesPendingSave != null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        openFolderLauncher.launch(intent)
    }

    private fun switchProject(uri: Uri) {
        val rootDoc = DocumentFile.fromTreeUri(this, uri)
        if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
            throw IOException("The selected project folder could not be found.")
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val name = rootDoc.name ?: "Project"
        saveRootTreeUri(uri)
        projectGeneration++
        rootTreeUri = uri
        _projectName.value = name
        _projectFiles.value = emptyList()
        _projectMissing.value = false
        refreshProjectTree()
    }

    private fun handleMissingProject() {
        projectGeneration++
        rootTreeUri = null
        clearSavedRootTreeUri()
        _projectName.value = null
        _projectFiles.value = emptyList()
        // Keep open buffers so choosing a replacement project still protects their edits.
        _projectMissing.value = true
    }

    private fun restoreLastProject() {
        val uriString = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("last_project_uri", null) ?: return
        val uri = Uri.parse(uriString)
        val hasPermission = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) return
        rootTreeUri = uri
        val rootDoc = DocumentFile.fromTreeUri(this, uri)
        if (rootDoc == null || !rootDoc.exists()) {
            // The stored project folder no longer exists (moved/deleted).
            handleMissingProject()
            return
        }
        _projectName.value = rootDoc.name ?: "Project"
        refreshProjectTree()
    }

    private fun refreshProjectTree() {
        lifecycleScope.launch {
            val uri = rootTreeUri ?: return@launch
            val generation = projectGeneration
            val expandedMap = getExpandedPaths(_projectFiles.value)
            val files = withContext(Dispatchers.IO) {
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri)
                if (rootDoc != null && rootDoc.exists()) {
                    buildTree(rootDoc, expandedMap)
                } else {
                    null
                }
            }
            if (generation != projectGeneration) return@launch
            if (files != null) _projectFiles.value = files else handleMissingProject()
        }
    }

    private fun getExpandedPaths(files: List<ProjectFile>): Map<Uri, Boolean> {
        val map = mutableMapOf<Uri, Boolean>()
        fun recurse(list: List<ProjectFile>) {
            for (f in list) {
                if (f.isDirectory) {
                    map[f.uri] = f.isExpanded
                    recurse(f.children)
                }
            }
        }
        recurse(files)
        return map
    }

    private fun buildTree(
        directory: DocumentFile,
        expandedMap: Map<Uri, Boolean>
    ): List<ProjectFile> {
        val list = mutableListOf<ProjectFile>()
        val files = directory.listFiles()
        for (f in files) {
            val name = f.name ?: continue
            val uri = f.uri
            val isDir = f.isDirectory
            if (isDir) {
                val isExpanded = expandedMap[uri] ?: false
                val children = if (isExpanded) buildTree(f, expandedMap) else emptyList()
                list.add(ProjectFile(name, uri, isDirectory = true, children = children, isExpanded = isExpanded))
            } else {
                list.add(ProjectFile(name, uri, isDirectory = false))
            }
        }
        // Sort folders first, then files, both alphabetically
        return list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun toggleFolderExpand(file: ProjectFile) {
        fun updateTree(list: List<ProjectFile>): List<ProjectFile> {
            return list.map { f ->
                if (f.uri == file.uri) {
                    val nextExpanded = !f.isExpanded
                    var nextChildren = f.children
                    if (nextExpanded) {
                        val doc = DocumentFile.fromTreeUri(this, f.uri)
                        if (doc != null) {
                            nextChildren = buildTree(doc, emptyMap())
                        }
                    }
                    f.copy(isExpanded = nextExpanded, children = nextChildren)
                } else if (f.isDirectory) {
                    f.copy(children = updateTree(f.children))
                } else {
                    f
                }
            }
        }
        _projectFiles.value = updateTree(_projectFiles.value)
    }

    private fun openFile(file: ProjectFile) {
        val existing = editor.openFiles.find { it.uri == file.uri }
        if (existing != null) {
            editor.selectFile(existing.uri)
            return
        }
        val generation = projectGeneration
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(file.uri)
                        ?: throw IOException("The document provider did not open a read stream.")
                    stream.bufferedReader().use { it.readText() }
                }
                if (generation != projectGeneration) return@launch
                val original = OpenFile(file.uri, file.name, text)
                val restored = try {
                    draftStore.restore(original)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == projectGeneration) {
                        editor.reportError("Could not read the local draft for '${file.name}': ${e.message ?: "Storage unavailable."}")
                    }
                    original
                }
                if (generation != projectGeneration) return@launch
                editor.openFile(restored)
                if (restored.isModified) {
                    Toast.makeText(
                        this@MainActivity,
                        "Local draft restored for '${file.name}'. Save to update the original file.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == projectGeneration) {
                    editor.reportError("Could not open '${file.name}': ${e.message ?: "Check file access permissions."}")
                }
            }
        }
    }

    private suspend fun writeFile(file: OpenFile) = withContext(Dispatchers.IO) {
        val stream = contentResolver.openOutputStream(file.uri, "wt")
            ?: throw IOException("The document provider did not open a write stream.")
        stream.bufferedWriter().use { writer ->
            writer.write(file.content)
        }
    }

    private fun createFile(parent: ProjectFile?, name: String) {
        val parentUri = parent?.uri ?: rootTreeUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val parentDoc = DocumentFile.fromTreeUri(this@MainActivity, parentUri)
            parentDoc?.createFile(mimeTypeFor(name), name)
            refreshProjectTree()
        }
    }

    private fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            val mimeMap = MimeTypeMap.getSingleton()
            val mimeType = mimeMap.getMimeTypeFromExtension(extension)
            if (mimeType != null && mimeMap.getExtensionFromMimeType(mimeType) == extension) {
                return mimeType
            }
        }
        return "application/x-unknown"
    }

    private fun createFolder(parent: ProjectFile?, name: String) {
        val parentUri = parent?.uri ?: rootTreeUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val parentDoc = DocumentFile.fromTreeUri(this@MainActivity, parentUri)
            parentDoc?.createDirectory(name)
            refreshProjectTree()
        }
    }

    private fun deleteFile(file: ProjectFile) {
        val generation = projectGeneration
        lifecycleScope.launch(Dispatchers.IO) {
            val doc = DocumentFile.fromSingleUri(this@MainActivity, file.uri)
            val deleted = doc?.delete() == true
            withContext(Dispatchers.Main) {
                if (deleted) {
                    if (generation == projectGeneration) editor.removeFile(file.uri)
                    drafts.removeDraft(file.uri)
                }
            }
            refreshProjectTree()
        }
    }

    private fun renameFile(file: ProjectFile, newName: String) {
        val generation = projectGeneration
        lifecycleScope.launch(Dispatchers.IO) {
            val doc = DocumentFile.fromSingleUri(this@MainActivity, file.uri)
            val renamed = doc?.renameTo(newName) == true
            withContext(Dispatchers.Main) {
                if (renamed && generation == projectGeneration) {
                    val newUri = doc?.uri ?: file.uri
                    drafts.renameDraft(file.uri, newUri)
                    editor.renameFile(file.uri, newName, newUri)
                }
            }
            refreshProjectTree()
        }
    }
}
