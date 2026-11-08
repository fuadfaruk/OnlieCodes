package com.cusapps.onliecodes.model

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class DraftAutosaver(
    private val scope: CoroutineScope,
    private val store: LocalDraftStore,
    private val onError: (String) -> Unit,
    private val pauseMillis: Long = 1_000L
) {
    private var files = emptyMap<Uri, OpenFile>()
    private val pendingWrites = mutableMapOf<Uri, Job>()

    fun updateFiles(openFiles: List<OpenFile>) {
        val next = openFiles.associateBy { it.uri }
        val previous = files
        files = next
        for (uri in previous.keys + next.keys) {
            val before = previous[uri]
            val after = next[uri]
            if (before?.content == after?.content && before?.isModified == after?.isModified) continue
            if (before?.isModified != true && after?.isModified != true) continue
            schedule(uri, after, after == null || !after.isModified)
        }
    }

    fun removeDraft(uri: Uri) {
        files = files - uri
        schedule(uri, null, immediately = true)
    }

    fun renameDraft(from: Uri, to: Uri) {
        if (from == to) return
        val file = files[from]?.copy(uri = to)
        files = files - from
        if (file != null) files = files + (file.uri to file)
        pendingWrites.remove(from)?.cancel()
        schedule(to, file, immediately = true, moveFrom = from)
    }

    fun flush() {
        for ((uri, file) in files) {
            if (file.isModified && pendingWrites[uri]?.isActive == true) {
                schedule(uri, file, immediately = true)
            }
        }
    }

    private fun schedule(uri: Uri, file: OpenFile?, immediately: Boolean, moveFrom: Uri? = null) {
        pendingWrites.remove(uri)?.cancel()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!immediately) delay(pauseMillis)
            try {
                when {
                    moveFrom != null -> store.move(moveFrom, uri, file?.takeIf { it.isModified }?.content)
                    file?.isModified == true -> store.write(uri, file.content)
                    else -> store.delete(uri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (coroutineContext.isActive) {
                    val action = when {
                        moveFrom != null -> "move"
                        file?.isModified == true -> "save"
                        else -> "remove"
                    }
                    val name = file?.name?.let { " for '$it'" }.orEmpty()
                    onError("Could not $action a local draft$name: ${e.message ?: "Storage unavailable."}")
                }
            }
        }
        pendingWrites[uri] = job
        job.invokeOnCompletion {
            if (pendingWrites[uri] === job) pendingWrites.remove(uri)
        }
    }
}
