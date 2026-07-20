package com.cusapps.onliecodes.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cusapps.onliecodes.model.ProjectFile
import com.cusapps.onliecodes.ui.theme.SurfaceDark

@Composable
fun FileTree(
    projectFiles: List<ProjectFile>,
    onFileClick: (ProjectFile) -> Unit,
    onToggleExpand: (ProjectFile) -> Unit,
    onCreateFile: (ProjectFile?) -> Unit,      // Passes parent folder, or null for root
    onCreateFolder: (ProjectFile?) -> Unit,    // Passes parent folder, or null for root
    onDeleteFile: (ProjectFile) -> Unit,
    onRenameFile: (ProjectFile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(SurfaceDark)
    ) {
        // Toolbar for root actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PROJECT FILES",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
            Row {
                IconButton(onClick = { onCreateFile(null) }) {
                    Icon(Icons.Default.NoteAdd, contentDescription = "New File", tint = Color.LightGray)
                }
                IconButton(onClick = { onCreateFolder(null) }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = Color.LightGray)
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            val flattenedList = flattenProjectTree(projectFiles, 0)
            items(flattenedList) { (item, depth) ->
                FileTreeItem(
                    file = item,
                    depth = depth,
                    onItemClick = {
                        if (item.isDirectory) {
                            onToggleExpand(item)
                        } else {
                            onFileClick(item)
                        }
                    },
                    onCreateFile = { onCreateFile(item) },
                    onCreateFolder = { onCreateFolder(item) },
                    onDelete = { onDeleteFile(item) },
                    onRename = { onRenameFile(item) }
                )
            }
        }
    }
}

@Composable
fun FileTreeItem(
    file: ProjectFile,
    depth: Int,
    onItemClick: () -> Unit,
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .padding(start = (depth * 16 + 8).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) {
                if (file.isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder
            } else {
                Icons.Default.Description
            },
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else Color.LightGray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = file.name,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )

        // Actions menu trigger
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "File Actions", tint = Color.Gray)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (file.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("New File") },
                        leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onCreateFile()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New Folder") },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onCreateFolder()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

private fun flattenProjectTree(
    files: List<ProjectFile>,
    depth: Int
): List<Pair<ProjectFile, Int>> {
    val list = mutableListOf<Pair<ProjectFile, Int>>()
    for (file in files) {
        list.add(file to depth)
        if (file.isDirectory && file.isExpanded) {
            list.addAll(flattenProjectTree(file.children, depth + 1))
        }
    }
    return list
}
