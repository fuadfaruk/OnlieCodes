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
import com.cusapps.onliecodes.model.LocalSessionStore
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.model.ProjectFile
import com.cusapps.onliecodes.model.SavedSession
import com.cusapps.onliecodes.model.SessionTab
import com.cusapps.onliecodes.ui.MainScreen
import com.cusapps.onliecodes.ui.theme.OnlieCodesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
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
    private val sessionStore by lazy {
        LocalSessionStore(File(applicationContext.noBackupFilesDir, "session.json"))
    }
    private val drafts: DraftAutosaver by lazy {
        DraftAutosaver(lifecycleScope, draftStore, onError = { editor.reportError(it) })
    }
    private val editor = EditorSession(
        writeFile = { writeFile(it) },
        switchProject = { switchProject(it) },
        onFilesChanged = { drafts.updateFiles(it) },
        onSessionChanged = { persistSession() }
    )

    private var isDarkTheme by mutableStateOf(true)
    private var isRestoringSession by mutableStateOf(true)

    private var rootTreeUri: Uri? = null
    private var sessionProjectUri: Uri? = null
    private var lastSession: SavedSession? = null
    private var unrestoredTabs = emptyList<IndexedValue<SessionTab>>()
    private var sessionRestoreJob: Job? = null
    private var projectGeneration = 0L

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch {
                sessionRestoreJob?.join()
                if (rootTreeUri == null && uri == sessionProjectUri) {
                    try {
                        openProject(uri)
                        persistSession()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        editor.reportError("Could not reopen the project folder: ${e.message ?: "Check folder access permissions."}")
                    }
                } else if (uri != rootTreeUri) {
                    editor.requestSwitchProject(uri)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreSession()
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
                    onToggleTheme = { toggleTheme() },
                    isRestoringSession = isRestoringSession
                )
            }
        }
    }

    override fun onStop() {
        drafts.flush()
        persistSession(force = true)
        super.onStop()
    }

    private fun persistSession(force: Boolean = false) {
        if (isRestoringSession) return
        val tabs = editor.openFiles.mapTo(mutableListOf()) { SessionTab(it.uri, it.name) }
        // Keep unreadable entries indexed so temporary access failures do not orphan drafts.
        val previousTabs = lastSession?.tabs.orEmpty()
        for ((index, tab) in unrestoredTabs) {
            val previousIndex = previousTabs.indexOfFirst { it.uri == tab.uri }
            val position = if (previousIndex >= 0) {
                previousTabs.take(previousIndex).count { before -> tabs.any { it.uri == before.uri } }
            } else {
                index.coerceAtMost(tabs.size)
            }
            tabs.add(position, tab)
        }
        val session = SavedSession(
            sessionProjectUri,
            tabs,
            editor.activeFile?.uri ?: lastSession?.activeFileUri
                ?.takeIf { uri -> unrestoredTabs.any { it.value.uri == uri } }
        )
        if (!force && session == lastSession) return
        lastSession = session
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                sessionStore.write(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (lastSession == session) lastSession = null
                if (isActive) {
                    editor.reportError("Could not save the editor session: ${e.message ?: "Storage unavailable."}")
                }
            }
        }
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
        if (isRestoringSession || editor.isSaving || editor.filesPendingSave != null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        openFolderLauncher.launch(intent)
    }

    private fun switchProject(uri: Uri) {
        openProject(uri)
        unrestoredTabs = emptyList()
    }

    private fun openProject(uri: Uri) {
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
        sessionProjectUri = uri
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

    private fun restoreSession() {
        sessionRestoreJob = lifecycleScope.launch {
            try {
                val saved = try {
                    sessionStore.read()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    editor.reportError("Could not read the editor session: ${e.message ?: "Storage unavailable."}")
                    null
                }
                lastSession = saved
                sessionProjectUri = if (saved != null) saved.projectUri else {
                    getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getString("last_project_uri", null)?.let(Uri::parse)
                }
                restoreLastProject(sessionProjectUri)
                val generation = projectGeneration
                val files = mutableListOf<OpenFile>()
                val unavailable = mutableListOf<IndexedValue<SessionTab>>()
                for (tab in saved?.tabs.orEmpty().withIndex()) {
                    try {
                        files += loadFile(tab.value, generation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        unavailable += tab
                        editor.reportError("Could not restore '${tab.value.name}': ${e.message ?: "Check file access permissions."} It will be retried on the next launch.")
                    }
                }
                unrestoredTabs = unavailable
                editor.restoreFiles(files, saved?.activeFileUri)
                val draftCount = files.count { it.isModified }
                if (draftCount > 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "Restored $draftCount unsaved draft${if (draftCount == 1) "" else "s"}. Save to update the original files.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                isRestoringSession = false
            }
            persistSession()
            if (rootTreeUri != null) refreshProjectTree()
        }
    }

    private suspend fun restoreLastProject(uri: Uri?) {
        if (uri == null) return
        val hasPermission = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasPermission) {
            handleMissingProject()
            return
        }
        try {
            val name = withContext(Dispatchers.IO) {
                val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri)
                if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                    rootDoc.name ?: "Project"
                } else {
                    null
                }
            }
            if (name == null) {
                handleMissingProject()
            } else {
                rootTreeUri = uri
                _projectName.value = name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleMissingProject()
            editor.reportError("Could not reopen the project folder: ${e.message ?: "Check folder access permissions."}")
        }
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
        if (isRestoringSession) return
        val existing = editor.openFiles.find { it.uri == file.uri }
        if (existing != null) {
            editor.selectFile(existing.uri)
            return
        }
        val generation = projectGeneration
        lifecycleScope.launch {
            try {
                val restored = loadFile(SessionTab(file.uri, file.name), generation)
                if (generation != projectGeneration) return@launch
                unrestoredTabs = unrestoredTabs.filterNot { it.value.uri == file.uri }
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

    private suspend fun loadFile(tab: SessionTab, generation: Long): OpenFile {
        val original = try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openInputStream(tab.uri)
                    ?: throw IOException("The document provider did not open a read stream.")
                val text = stream.bufferedReader().use { it.readText() }
                OpenFile(tab.uri, tab.name, text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val draft = draftStore.read(tab.uri) ?: throw e
            if (generation == projectGeneration) {
                editor.reportError("The original file '${tab.name}' is unavailable. Its local draft was restored; check file access before saving.")
            }
            return OpenFile(tab.uri, tab.name, draft, savedContent = null)
        }
        return try {
            draftStore.restore(original)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation == projectGeneration) {
                editor.reportError("Could not read the local draft for '${tab.name}': ${e.message ?: "Storage unavailable."}")
            }
            original
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
                    if (generation == projectGeneration) {
                        unrestoredTabs = unrestoredTabs.filterNot { it.value.uri == file.uri }
                        editor.removeFile(file.uri)
                    }
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
                    unrestoredTabs = unrestoredTabs.map {
                        if (it.value.uri == file.uri) {
                            IndexedValue(it.index, SessionTab(newUri, newName))
                        } else {
                            it
                        }
                    }
                    drafts.renameDraft(file.uri, newUri)
                    editor.renameFile(file.uri, newName, newUri)
                }
            }
            refreshProjectTree()
        }
    }
}
