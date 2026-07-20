package com.cusapps.onliecodes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.ui.theme.BackgroundDark
import com.cusapps.onliecodes.ui.theme.SelectionColor
import com.cusapps.onliecodes.ui.theme.SurfaceDark

@Composable
fun FileTabs(
    openFiles: List<OpenFile>,
    activeFile: OpenFile?,
    onTabSelect: (OpenFile) -> Unit,
    onTabClose: (OpenFile) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(BackgroundDark)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        openFiles.forEach { openFile ->
            val isActive = openFile.uri == activeFile?.uri
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(if (isActive) SurfaceDark else Color.Transparent)
                    .clickable { onTabSelect(openFile) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = openFile.name + (if (openFile.isModified) " *" else ""),
                    color = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Tab",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onTabClose(openFile) }
                )
            }
            // Vertical divider between tabs
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(SelectionColor)
            )
        }
    }
}
