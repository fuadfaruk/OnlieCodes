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
    val isModified: Boolean = false
)
