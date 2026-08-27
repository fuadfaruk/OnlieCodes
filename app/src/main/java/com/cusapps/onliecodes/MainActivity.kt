package com.cusapps.onliecodes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import android.webkit.MimeTypeMap
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.model.ProjectFile
import com.cusapps.onliecodes.ui.MainScreen
import com.cusapps.onliecodes.ui.theme.OnlieCodesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : ComponentActivity() {

    private val _projectName = MutableStateFlow<String?>(null)
    private val _projectFiles = MutableStateFlow<List<ProjectFile>>(emptyList())
    private val _openFiles = MutableStateFlow<List<OpenFile>>(emptyList())
    private val _activeFile = MutableStateFlow<OpenFile?>(null)

    private var rootTreeUri: Uri? = null

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            rootTreeUri = uri
            saveRootTreeUri(uri)
            val rootDoc = DocumentFile.fromTreeUri(this, uri)
            _projectName.value = rootDoc?.name ?: "Project"
            refreshProjectTree()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreLastProject()
        setContent {
            OnlieCodesTheme {
                val projectNameState = _projectName.collectAsState()
                val projectFilesState = _projectFiles.collectAsState()
                val openFilesState = _openFiles.collectAsState()
                val activeFileState = _activeFile.collectAsState()

                MainScreen(
                    projectName = projectNameState.value,
                    projectFiles = projectFilesState.value,
                    openFiles = openFilesState.value,
                    activeFile = activeFileState.value,
                    onOpenFolderClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        openFolderLauncher.launch(intent)
                    },
                    onFileClick = { file -> openFile(file) },
                    onToggleExpand = { file -> toggleFolderExpand(file) },
                    onTabSelect = { openFile -> _activeFile.value = openFile },
                    onTabClose = { openFile -> closeTab(openFile) },
                    onContentChange = { newContent -> updateActiveFileContent(newContent) },
                    onSaveClick = { saveActiveFile() },
                    onCreateFile = { parent, name -> createFile(parent, name) },
                    onCreateFolder = { parent, name -> createFolder(parent, name) },
                    onDeleteFile = { file -> deleteFile(file) },
                    onRenameFile = { file, newName -> renameFile(file, newName) }
                )
            }
        }
    }

    private fun saveRootTreeUri(uri: Uri) {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("last_project_uri", uri.toString())
            .apply()
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
        _projectName.value = rootDoc?.name ?: "Project"
        refreshProjectTree()
    }

    private fun refreshProjectTree() {
        val uri = rootTreeUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri)
            if (rootDoc != null) {
                // Read expanded states map to restore user navigation state
                val expandedMap = getExpandedPaths(_projectFiles.value)
                val files = buildTree(rootDoc, expandedMap)
                withContext(Dispatchers.Main) {
                    _projectFiles.value = files
                }
            }
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
        val existing = _openFiles.value.find { it.uri == file.uri }
        if (existing != null) {
            _activeFile.value = existing
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(file.uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    withContext(Dispatchers.Main) {
                        val openFile = OpenFile(file.uri, file.name, text)
                        _openFiles.value = _openFiles.value + openFile
                        _activeFile.value = openFile
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun closeTab(openFile: OpenFile) {
        val nextList = _openFiles.value.filter { it.uri != openFile.uri }
        _openFiles.value = nextList
        if (_activeFile.value?.uri == openFile.uri) {
            _activeFile.value = nextList.lastOrNull()
        }
    }

    private fun updateActiveFileContent(newContent: String) {
        val active = _activeFile.value ?: return
        val updated = active.copy(content = newContent, isModified = true)
        _activeFile.value = updated
        _openFiles.value = _openFiles.value.map {
            if (it.uri == active.uri) updated else it
        }
    }

    private fun saveActiveFile() {
        val active = _activeFile.value ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(active.uri, "w")?.use { stream ->
                    stream.bufferedWriter().use { writer ->
                        writer.write(active.content)
                    }
                }
                withContext(Dispatchers.Main) {
                    val saved = active.copy(isModified = false)
                    if (_activeFile.value?.uri == active.uri) {
                        _activeFile.value = saved
                    }
                    _openFiles.value = _openFiles.value.map {
                        if (it.uri == active.uri) saved else it
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun createFile(parent: ProjectFile?, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val parentDoc = if (parent != null) {
                DocumentFile.fromTreeUri(this@MainActivity, parent.uri)
            } else {
                rootTreeUri?.let { DocumentFile.fromTreeUri(this@MainActivity, it) }
            }
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
        lifecycleScope.launch(Dispatchers.IO) {
            val parentDoc = if (parent != null) {
                DocumentFile.fromTreeUri(this@MainActivity, parent.uri)
            } else {
                rootTreeUri?.let { DocumentFile.fromTreeUri(this@MainActivity, it) }
            }
            parentDoc?.createDirectory(name)
            refreshProjectTree()
        }
    }

    private fun deleteFile(file: ProjectFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val doc = DocumentFile.fromSingleUri(this@MainActivity, file.uri)
            doc?.delete()
            withContext(Dispatchers.Main) {
                // If the deleted file is currently open, close it.
                val openFile = _openFiles.value.find { it.uri == file.uri }
                if (openFile != null) {
                    closeTab(openFile)
                }
            }
            refreshProjectTree()
        }
    }

    private fun renameFile(file: ProjectFile, newName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val doc = DocumentFile.fromSingleUri(this@MainActivity, file.uri)
            doc?.renameTo(newName)
            withContext(Dispatchers.Main) {
                // Update open tab names if renamed
                _openFiles.value = _openFiles.value.map {
                    if (it.uri == file.uri) it.copy(name = newName) else it
                }
                val active = _activeFile.value
                if (active != null && active.uri == file.uri) {
                    _activeFile.value = active.copy(name = newName)
                }
            }
            refreshProjectTree()
        }
    }
}
