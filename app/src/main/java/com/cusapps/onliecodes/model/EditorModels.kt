package com.cusapps.onliecodes.model

import android.net.Uri

data class ProjectFile(
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val children: List<ProjectFile> = emptyList(),
    val isExpanded: Boolean = false
)

data class OpenFile(
    val uri: Uri,
    val name: String,
    val content: String,
    val savedContent: String? = content
) {
    val isModified: Boolean
        get() = content != savedContent
}

data class SessionTab(val uri: Uri, val name: String)

data class SavedSession(
    val projectUri: Uri?,
    val tabs: List<SessionTab>,
    val activeFileUri: Uri?
)
