package com.cusapps.onliecodes.model

import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LocalSessionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val first = SessionTab(Uri.parse("content://files/first.kt"), "first.kt")
    private val second = SessionTab(Uri.parse("content://files/second.kt"), "second.kt")
    private val project = Uri.parse("content://files/tree/project%20space")

    @Test
    fun newStoreRoundTripsOrderedMetadataWithoutFileContents() = runTest {
        val file = File(temporaryFolder.newFolder(), "session.json")
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalSessionStore(file, ioDispatcher)
        val files = listOf(
            OpenFile(
                second.uri,
                "caf\u00e9 \u6f22\u5b57 \uD840\uDC00 \"quoted\".kt",
                "private unsaved buffer sentinel",
                savedContent = "original file baseline sentinel"
            ),
            OpenFile(first.uri, "", "")
        )
        val expected = SavedSession(
            project,
            files.map { SessionTab(it.uri, it.name) },
            second.uri
        )

        assertNull(store.read())
        store.write(expected)

        assertEquals(expected, LocalSessionStore(file, ioDispatcher).read())
        val text = file.readText(Charsets.UTF_8)
        val json = JSONObject(text)
        assertEquals(setOf("version", "projectUri", "tabs", "activeFileUri"), json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("version"))
        val tabs = json.getJSONArray("tabs")
        assertEquals(files.size, tabs.length())
        for (index in files.indices) {
            assertEquals(setOf("uri", "name"), tabs.getJSONObject(index).keys().asSequence().toSet())
        }
        assertFalse(text.contains(files.first().content))
        assertFalse(text.contains(files.first().savedContent!!))
    }

    @Test
    fun nullProjectAndSelectionAndEmptySessionsRoundTrip() = runTest {
        val file = File(temporaryFolder.newFolder(), "session.json")
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalSessionStore(file, ioDispatcher)
        val states = listOf(
            SavedSession(null, listOf(first), null),
            SavedSession(project, emptyList(), null),
            SavedSession(null, emptyList(), null)
        )

        for (expected in states) {
            store.write(expected)
            assertEquals(expected, LocalSessionStore(file, ioDispatcher).read())
        }
    }

    @Test
    fun damagedOrUnsupportedMetadataIsReportedWithoutSilentlyClearingIt() = runTest {
        val file = temporaryFolder.newFile("session.json")
        val store = LocalSessionStore(file, StandardTestDispatcher(testScheduler))
        val damagedMetadata = listOf(
            """{"version":1,"tabs":[""" to JSONException::class.java,
            """{"version":1,"tabs":[{"uri":"content://files/first.kt"}]}""" to JSONException::class.java,
            """{"version":2,"projectUri":null,"activeFileUri":null,"tabs":[]}""" to IOException::class.java
        )

        for ((text, exceptionType) in damagedMetadata) {
            file.writeText(text, Charsets.UTF_8)
            try {
                store.read()
                fail("Invalid metadata should be reported, not restored as an empty session.")
            } catch (e: Exception) {
                assertTrue("Expected ${exceptionType.simpleName}, got $e", exceptionType.isInstance(e))
            }
            assertEquals(text, file.readText(Charsets.UTF_8))
        }

        val replacement = SavedSession(project, listOf(first), first.uri)
        store.write(replacement)
        assertEquals(replacement, store.read())
    }

    @Test
    fun interruptedWritesNeverExposePartialMetadata() = runTest {
        val file = File(temporaryFolder.newFolder(), "session.json")
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalSessionStore(file, ioDispatcher)
        val atomicFile = AtomicFile(file)
        val partialMetadata = """{"version":1,"tabs":[""".toByteArray(Charsets.UTF_8)

        // Closing without finishWrite simulates a process dying before the atomic commit.
        atomicFile.startWrite().use { it.write(partialMetadata) }
        assertTrue(File("${file.path}.new").exists())
        assertNull(LocalSessionStore(file, ioDispatcher).read())

        val committed = SavedSession(project, listOf(first, second), first.uri)
        store.write(committed)
        atomicFile.startWrite().use { it.write(partialMetadata) }

        assertEquals(committed, LocalSessionStore(file, ioDispatcher).read())
        assertFalse(File("${file.path}.new").exists())
        val replacement = SavedSession(null, listOf(second), second.uri)
        store.write(replacement)
        assertEquals(replacement, LocalSessionStore(file, ioDispatcher).read())
    }

    @Test
    fun failedReplacementPreservesPreviouslyCommittedSession() = runTest {
        val file = File(temporaryFolder.newFolder(), "session.json")
        val store = LocalSessionStore(file, StandardTestDispatcher(testScheduler))
        val committed = SavedSession(project, listOf(first, second), first.uri)
        store.write(committed)
        val temporary = File("${file.path}.new")
        assertTrue(temporary.mkdir())
        File(temporary, "blocker").writeText("prevents replacement")

        try {
            store.write(SavedSession(null, emptyList(), null))
            fail("The blocked temporary file should fail the write.")
        } catch (_: IOException) {
            assertEquals(committed, store.read())
        }
    }

    @Test
    fun latestWriteAcrossInstancesSurvivesCancellationOfQueuedWriters() = runTest {
        val file = File(temporaryFolder.newFolder(), "session.json")
        val olderIo = QueuedDispatcher()
        val newerIo = QueuedDispatcher()
        val olderStore = LocalSessionStore(file, olderIo)
        val newerStore = LocalSessionStore(file, newerIo)
        val lifetime = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val stale = SavedSession(project, listOf(first, second), first.uri)
        val latest = SavedSession(
            Uri.parse("content://files/tree/latest"),
            listOf(second.copy(name = "renamed.kt"), first),
            second.uri
        )

        try {
            val older = scope.launch(start = CoroutineStart.UNDISPATCHED) { olderStore.write(stale) }
            val newer = scope.launch(start = CoroutineStart.UNDISPATCHED) { newerStore.write(latest) }
            assertEquals(1, olderIo.queuedCount)
            assertEquals(0, newerIo.queuedCount)

            scope.cancel()
            runCurrent()
            assertFalse(older.isCompleted)
            assertFalse(newer.isCompleted)
            assertEquals(1, olderIo.queuedCount)
            assertEquals(0, newerIo.queuedCount)

            // A different IO dispatcher must not let the latest write overtake the older commit.
            olderIo.runNext()
            runCurrent()
            assertEquals(0, olderIo.queuedCount)
            assertEquals(1, newerIo.queuedCount)
            assertTrue(older.isCompleted)
            assertEquals(first.uri.toString(), JSONObject(file.readText(Charsets.UTF_8)).getString("activeFileUri"))

            newerIo.runNext()
            runCurrent()

            assertEquals(0, newerIo.queuedCount)
            assertTrue(newer.isCompleted)
            assertTrue(lifetime.isCompleted)
            assertEquals(latest, LocalSessionStore(file, StandardTestDispatcher(testScheduler)).read())
        } finally {
            scope.cancel()
            runCurrent()
            // Release non-cancellable writes even if an assertion failed while holding the process-wide mutex.
            while (olderIo.queuedCount > 0 || newerIo.queuedCount > 0) {
                if (olderIo.queuedCount > 0) olderIo.runNext()
                if (newerIo.queuedCount > 0) newerIo.runNext()
                runCurrent()
            }
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        val queuedCount: Int
            get() = queued.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runNext() {
            queued.removeFirst().run()
        }
    }
}
