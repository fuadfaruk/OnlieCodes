package com.cusapps.onliecodes.model

import android.net.Uri
import android.util.AtomicFile
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
import java.io.IOException

class LocalSessionStore(
    file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val atomicFile = AtomicFile(file)

    suspend fun read(): SavedSession? = diskMutex.withLock {
        withContext(ioDispatcher) {
            val text = try {
                atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: FileNotFoundException) {
                if (atomicFile.baseFile.exists()) throw e
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
                val json = JSONObject()
                    .put("version", 1)
                    .put("projectUri", session.projectUri?.toString() ?: JSONObject.NULL)
                    .put("activeFileUri", session.activeFileUri?.toString() ?: JSONObject.NULL)
                    .put("tabs", JSONArray().apply {
                        session.tabs.forEach { tab ->
                            put(JSONObject().put("uri", tab.uri.toString()).put("name", tab.name))
                        }
                    })
                val stream = atomicFile.startWrite()
                try {
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                    atomicFile.finishWrite(stream)
                } catch (e: Exception) {
                    atomicFile.failWrite(stream)
                    throw e
                }
            }
        }
    }

    private companion object {
        val diskMutex = Mutex()
    }
}
