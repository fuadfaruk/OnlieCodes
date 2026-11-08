package com.cusapps.onliecodes.model

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

class LocalDraftStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun read(uri: Uri): String? = diskMutex.withLock {
        withContext(ioDispatcher) { readFile(uri) }
    }

    suspend fun restore(file: OpenFile): OpenFile = diskMutex.withLock {
        withContext(ioDispatcher) {
            val draft = readFile(file.uri)
            when (draft) {
                null -> file
                file.content -> {
                    deleteFile(file.uri)
                    file
                }
                else -> file.copy(content = draft)
            }
        }
    }

    suspend fun write(uri: Uri, content: String) = mutate { writeFile(uri, content) }

    suspend fun delete(uri: Uri) = mutate { deleteFile(uri) }

    suspend fun move(from: Uri, to: Uri, content: String?) = mutate {
        if (from != to) {
            val draft = content ?: readFile(from)
            if (draft != null) writeFile(to, draft)
            deleteFile(from)
        }
    }

    private suspend fun mutate(action: () -> Unit) = withContext(NonCancellable) {
        diskMutex.withLock {
            withContext(ioDispatcher) { action() }
        }
    }

    private fun readFile(uri: Uri): String? {
        val file = draftFile(uri)
        return try {
            file.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: FileNotFoundException) {
            if (file.exists()) throw e
            null
        }
    }

    private fun writeFile(uri: Uri, content: String) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the local drafts directory.")
        }
        val file = draftFile(uri)
        val temporary = File("${file.path}.new")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun deleteFile(uri: Uri) {
        val file = draftFile(uri)
        for (target in listOf(file, File("${file.path}.new"))) {
            if (target.exists() && !target.delete()) {
                throw IOException("Could not remove the local draft.")
            }
        }
    }

    private fun draftFile(uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$key.draft")
    }

    private companion object {
        // Queue mutations before dispatching IO so an older write cannot follow a discard.
        val diskMutex = Mutex()
    }
}
