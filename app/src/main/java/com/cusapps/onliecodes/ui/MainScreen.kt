package com.cusapps.onliecodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.model.ProjectFile
import com.cusapps.onliecodes.ui.components.CodeEditor
import com.cusapps.onliecodes.ui.components.FileTabs
import com.cusapps.onliecodes.ui.components.FileTree
import com.cusapps.onliecodes.ui.theme.LocalEditorPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    projectName: String?,
    projectFiles: List<ProjectFile>,
    openFiles: List<OpenFile>,
    activeFile: OpenFile?,
    projectMissing: Boolean,
    onOpenFolderClick: () -> Unit,
    onSelectProjectClick: () -> Unit,
    onInitializeProjectClick: () -> Unit,
    onProjectMissingDismissed: () -> Unit,
    onFileClick: (ProjectFile) -> Unit,
    onToggleExpand: (ProjectFile) -> Unit,
    onTabSelect: (OpenFile) -> Unit,
    onTabClose: (OpenFile) -> Unit,
    onContentChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onCreateFile: (ProjectFile?, String) -> Unit,
    onCreateFolder: (ProjectFile?, String) -> Unit,
    onDeleteFile: (ProjectFile) -> Unit,
    onRenameFile: (ProjectFile, String) -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    var showSidebar by remember { mutableStateOf(true) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    
    // Dialog States
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    var dialogTargetFile by remember { mutableStateOf<ProjectFile?>(null) }
    var dialogInputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName ?: "Onlie Codes") },
                navigationIcon = {
                    IconButton(onClick = { showSidebar = !showSidebar }) {
                        Icon(Icons.Default.Menu, contentDescription = "Toggle Sidebar")
                    }
                },
                actions = {
                    if (projectName == null) {
                        Button(onClick = onOpenFolderClick) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open Folder")
                        }
                    } else {
                        IconButton(onClick = onSaveClick, enabled = activeFile?.isModified == true) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save Current File",
                                tint = if (activeFile?.isModified == true) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                        DropdownMenuItem(
                            text = { Text("Open New Project") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                            onClick = {
                                showOptionsMenu = false
                                onInitializeProjectClick()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (isDarkTheme) "Switch to Light Theme" else "Switch to Dark Theme")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                showOptionsMenu = false
                                onToggleTheme()
                            }
                        )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val palette = LocalEditorPalette.current
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(palette.background)
        ) {
            // Sidebar Navigation Tree
            if (showSidebar && projectName != null) {
                FileTree(
                    projectFiles = projectFiles,
                    onFileClick = onFileClick,
                    onToggleExpand = onToggleExpand,
                    onCreateFile = { parent ->
                        dialogTargetFile = parent
                        dialogInputText = ""
                        showCreateFileDialog = true
                    },
                    onCreateFolder = { parent ->
                        dialogTargetFile = parent
                        dialogInputText = ""
                        showCreateFolderDialog = true
                    },
                    onDeleteFile = { target ->
                        dialogTargetFile = target
                        showDeleteDialog = true
                    },
                    onRenameFile = { target ->
                        dialogTargetFile = target
                        dialogInputText = target.name
                        showRenameDialog = true
                    }
                )
            }

            // Editor Work Area
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                if (openFiles.isNotEmpty()) {
                    FileTabs(
                        openFiles = openFiles,
                        activeFile = activeFile,
                        onTabSelect = onTabSelect,
                        onTabClose = onTabClose
                    )
                    
                    if (activeFile != null) {
                        CodeEditor(
                            content = activeFile.content,
                            onContentChange = onContentChange,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (projectName == null) "Open a directory to begin coding" else "Select a file to edit",
                            color = LocalEditorPalette.current.muted
                        )
                    }
                }
            }
        }
    }

    // Dialog: Create File
    if (showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create File") },
            text = {
                OutlinedTextField(
                    value = dialogInputText,
                    onValueChange = { dialogInputText = it },
                    label = { Text("File Name") },
                    supportingText = { Text("Include the extension, e.g. file1.txt") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (dialogInputText.isNotBlank()) {
                        onCreateFile(dialogTargetFile, dialogInputText)
                        showCreateFileDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Create Folder
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = dialogInputText,
                    onValueChange = { dialogInputText = it },
                    label = { Text("Folder Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (dialogInputText.isNotBlank()) {
                        onCreateFolder(dialogTargetFile, dialogInputText)
                        showCreateFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Rename File/Folder
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = dialogInputText,
                    onValueChange = { dialogInputText = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = dialogTargetFile
                    if (target != null && dialogInputText.isNotBlank()) {
                        onRenameFile(target, dialogInputText)
                        showRenameDialog = false
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Delete Confirmation
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Confirmation") },
            text = { Text("Are you sure you want to delete '${dialogTargetFile?.name}'?") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val target = dialogTargetFile
                        if (target != null) {
                            onDeleteFile(target)
                            showDeleteDialog = false
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Project Folder Missing
    if (projectMissing) {
        AlertDialog(
            onDismissRequest = { onProjectMissingDismissed() },
            title = { Text("Project Folder Not Found") },
            text = {
                Text("The current project folder could not be found. It may have been moved or deleted. Select a new project folder or initialize a new project to continue.")
            },
            confirmButton = {
                Button(onClick = onSelectProjectClick) { Text("Select New Project Folder") }
            },
            dismissButton = {
                TextButton(onClick = onInitializeProjectClick) { Text("Initialize New Project") }
            }
        )
    }
}
