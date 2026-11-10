package com.cusapps.onliecodes.model

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class LocalSessionStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun read(): SavedSession? = diskMutex.withLock {
        withContext(ioDispatcher) {
            File("${file.path}.new").delete()
            val text = try {
                file.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: FileNotFoundException) {
                if (file.exists()) throw e
                return@withContext null
            }
            val json = JSONObject(text)
            if (json.getInt("version") != 1) throw IOException("Unsupported local session format.")
            val tabs = json.getJSONArray("tabs")
            SavedSession(
                projectUri = if (json.isNull("projectUri")) null else Uri.parse(json.getString("projectUri")),
                tabs = (0 until tabs.length()).map { index ->
                    val tab = tabs.getJSONObject(index)
                    SessionTab(Uri.parse(tab.getString("uri")), tab.getString("name"))
                }.distinctBy { it.uri },
                activeFileUri = if (json.isNull("activeFileUri")) null else Uri.parse(json.getString("activeFileUri"))
            )
        }
    }

    suspend fun write(session: SavedSession) = withContext(NonCancellable) {
        // Enter the queue before dispatching IO, including across Activity instances.
        diskMutex.withLock {
            withContext(ioDispatcher) {
                val parent = file.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("Could not create the local session directory.")
                }
                val json = JSONObject()
                    .put("version", 1)
                    .put("projectUri", session.projectUri?.toString() ?: JSONObject.NULL)
                    .put("activeFileUri", session.activeFileUri?.toString() ?: JSONObject.NULL)
                    .put("tabs", JSONArray().apply {
                        session.tabs.forEach { tab ->
                            put(JSONObject().put("uri", tab.uri.toString()).put("name", tab.name))
                        }
                    })
                val temporary = File("${file.path}.new")
                try {
                    FileOutputStream(temporary).use { stream ->
                        stream.write(json.toString().toByteArray(Charsets.UTF_8))
                        stream.fd.sync()
                    }
                    Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private companion object {
        val diskMutex = Mutex()
    }
}
