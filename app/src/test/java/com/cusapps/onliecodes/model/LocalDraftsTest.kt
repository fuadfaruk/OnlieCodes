package com.cusapps.onliecodes.model

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
class LocalDraftsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val first = OpenFile(Uri.parse("content://files/first.kt"), "first.kt", "first")
    private val second = OpenFile(Uri.parse("content://files/second.kt"), "second.kt", "second")
    private val nextProject = Uri.parse("content://files/tree/next")

    @Test
    fun newStoreRecoversUriKeyedEmptyAndUnicodeDrafts() = runTest {
        val directory = temporaryFolder.newFolder()
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalDraftStore(directory, ioDispatcher)
        val sameNameElsewhere = Uri.parse("content://other-provider/first.kt")
        val unicode = "// caf\u00e9 \u6f22\u5b57 \uD840\uDC00\r\n"

        assertNull(store.read(first.uri))
        store.write(first.uri, "")
        store.write(sameNameElsewhere, unicode)

        val recovered = LocalDraftStore(directory, ioDispatcher)
        assertEquals("", recovered.read(first.uri))
        assertEquals(unicode, recovered.read(sameNameElsewhere))
        val restoredEmpty = recovered.restore(first)
        val restoredUnicode = recovered.restore(first.copy(uri = sameNameElsewhere))
        assertEquals(first.copy(content = ""), restoredEmpty)
        assertEquals(first.copy(uri = sameNameElsewhere, content = unicode), restoredUnicode)
        assertTrue(restoredEmpty.isModified)
        assertTrue(restoredUnicode.isModified)
        val filenames = directory.listFiles()!!.map { it.name }
        assertEquals(2, filenames.size)
        assertTrue(filenames.all { it.matches(Regex("[0-9a-f]{64}\\.draft")) })

        recovered.write(first.uri, "replacement")
        assertEquals("replacement", store.read(first.uri))
        recovered.delete(first.uri)
        recovered.delete(first.uri)
        assertNull(store.read(first.uri))
        assertEquals(unicode, store.read(sameNameElsewhere))
    }

    @Test
    fun failedReplacementPreservesPreviouslyPersistedDraft() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = LocalDraftStore(directory, StandardTestDispatcher(testScheduler))
        store.write(first.uri, "previous draft")
        val temporary = File("${directory.listFiles()!!.single().path}.new")
        assertTrue(temporary.mkdir())
        File(temporary, "blocker").writeText("prevents replacement")

        try {
            store.write(first.uri, "newer draft")
            fail("The blocked temporary file should fail the write.")
        } catch (_: IOException) {
            assertEquals("previous draft", store.read(first.uri))
        }
    }

    @Test
    fun restoringDraftsMatchingOriginalPrunesThemIncludingEmptyContent() = runTest {
        val directory = temporaryFolder.newFolder()
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val store = LocalDraftStore(directory, ioDispatcher)
        val emptyOriginal = OpenFile(second.uri, second.name, "")
        store.write(first.uri, first.content)
        store.write(emptyOriginal.uri, "")

        val recovered = LocalDraftStore(directory, ioDispatcher)
        assertEquals(first, recovered.restore(first))
        assertNull(recovered.read(first.uri))
        assertEquals("", recovered.read(emptyOriginal.uri))
        assertEquals(emptyOriginal, recovered.restore(emptyOriginal))
        assertNull(recovered.read(emptyOriginal.uri))
        assertEquals(first, recovered.restore(first))
    }

    @Test
    fun typingPauseStoresOnlyLatestTextWithoutWritingOriginalOrClearingDirtyFlag() = runTest {
        val original = temporaryFolder.newFile("original.kt")
        original.writeText("original sentinel", Charsets.UTF_8)
        val file = OpenFile(Uri.fromFile(original), original.name, "original sentinel")
        val drafts = fixture(writeFile = { original.writeText(it.content, Charsets.UTF_8) })
        drafts.editor.openFile(file)
        drafts.editor.updateContent("intermediate edit")
        advanceTimeBy(600)
        drafts.editor.updateContent("latest edit")

        advanceTimeBy(999)
        runCurrent()
        assertNull(drafts.store.read(file.uri))
        assertEquals("original sentinel", original.readText(Charsets.UTF_8))
        assertTrue(drafts.manualWrites.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals("latest edit", drafts.store.read(file.uri))
        assertEquals("latest edit", drafts.editor.activeFile?.content)
        assertEquals("original sentinel", drafts.editor.activeFile?.savedContent)
        assertTrue(drafts.editor.activeFile!!.isModified)
        assertEquals("original sentinel", original.readText(Charsets.UTF_8))
        assertTrue(drafts.manualWrites.isEmpty())
        assertTrue(drafts.errors.isEmpty())
    }

    @Test
    fun tabsDebounceIndependentlyAndSelectionDoesNotRestartTypingPause() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        advanceTimeBy(600)
        drafts.editor.openModified(second)
        drafts.editor.selectFile(first.uri)

        advanceTimeBy(399)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertNull(drafts.store.read(second.uri))
        advanceTimeBy(1)
        runCurrent()
        assertEquals("first edited", drafts.store.read(first.uri))
        assertNull(drafts.store.read(second.uri))

        drafts.editor.selectFile(second.uri)
        advanceTimeBy(599)
        runCurrent()
        assertNull(drafts.store.read(second.uri))
        drafts.editor.selectFile(first.uri)
        advanceTimeBy(1)
        runCurrent()

        assertEquals("first edited", drafts.store.read(first.uri))
        assertEquals("second edited", drafts.store.read(second.uri))
        assertEquals(first.uri, drafts.editor.activeFile?.uri)
        assertTrue(drafts.editor.openFiles.all { it.isModified })
        assertTrue(drafts.manualWrites.isEmpty())
    }

    @Test
    fun revertingToSavedTextRemovesDraftAndCancelsPendingWrite() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("first edited", drafts.store.read(first.uri))
        drafts.editor.updateContent("pending edit")
        advanceTimeBy(500)

        drafts.editor.updateContent(first.content)
        runCurrent()

        assertFalse(drafts.editor.activeFile!!.isModified)
        assertNull(drafts.store.read(first.uri))
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertTrue(drafts.manualWrites.isEmpty())
    }

    @Test
    fun successfulManualSaveRemovesDraftAndCancelsPendingWrite() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("first edited", drafts.store.read(first.uri))
        drafts.editor.updateContent("manually saved edit")
        advanceTimeBy(500)

        drafts.editor.saveActiveFile()
        runCurrent()

        assertEquals("manually saved edit", drafts.manualWrites.single().content)
        assertFalse(drafts.editor.activeFile!!.isModified)
        assertEquals("manually saved edit", drafts.editor.activeFile?.savedContent)
        assertNull(drafts.store.read(first.uri))
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
    }

    @Test
    fun editsDuringManualSaveKeepDraftAndTheirOriginalDebounceDeadline() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val drafts = fixture(writeFile = { releaseWrite.await() })
        drafts.editor.openModified(first)
        advanceTimeBy(1_000)
        runCurrent()
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { drafts.editor.saveActiveFile() }
        assertTrue(drafts.editor.isSaving)

        drafts.editor.updateContent("newer edit")
        advanceTimeBy(500)
        releaseWrite.complete(Unit)
        saving.join()
        runCurrent()

        assertEquals("first edited", drafts.manualWrites.single().content)
        assertEquals("newer edit", drafts.editor.activeFile?.content)
        assertEquals("first edited", drafts.editor.activeFile?.savedContent)
        assertTrue(drafts.editor.activeFile!!.isModified)
        assertEquals("first edited", drafts.store.read(first.uri))
        advanceTimeBy(499)
        runCurrent()
        assertEquals("first edited", drafts.store.read(first.uri))
        advanceTimeBy(1)
        runCurrent()
        assertEquals("newer edit", drafts.store.read(first.uri))
        assertFalse(drafts.editor.isSaving)
    }

    @Test
    fun failedOriginalSaveRetainsDraftAndDirtyBuffer() = runTest {
        val drafts = fixture(writeFile = { throw IOException("Original storage unavailable") })
        drafts.editor.openModified(first)
        advanceTimeBy(1_000)
        runCurrent()

        drafts.editor.saveActiveFile()
        runCurrent()

        assertEquals("first edited", drafts.store.read(first.uri))
        assertEquals("first edited", drafts.editor.activeFile?.content)
        assertTrue(drafts.editor.activeFile!!.isModified)
        assertNull(drafts.editor.activeFile?.savedContent)
        assertTrue(drafts.editor.errorMessage!!.contains("Original storage unavailable"))
        assertEquals(1, drafts.manualWrites.size)
        assertTrue(drafts.errors.isEmpty())
    }

    @Test
    fun cancelingCloseOrProjectSwitchRetainsDraftAndPendingEdits() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        advanceTimeBy(1_000)
        runCurrent()
        drafts.editor.updateContent("pending newer edit")
        advanceTimeBy(500)
        val before = drafts.editor.openFiles

        drafts.editor.requestCloseTab(first.uri)
        drafts.editor.cancelPendingAction()
        drafts.editor.requestSwitchProject(nextProject)
        drafts.editor.cancelPendingAction()
        runCurrent()

        assertEquals(before, drafts.editor.openFiles)
        assertEquals("first edited", drafts.store.read(first.uri))
        assertNull(drafts.editor.filesPendingSave)
        assertNull(drafts.switchedTo)
        assertTrue(drafts.manualWrites.isEmpty())
        advanceTimeBy(500)
        runCurrent()
        assertEquals("pending newer edit", drafts.store.read(first.uri))
        assertTrue(drafts.editor.activeFile!!.isModified)
    }

    @Test
    fun failedProjectSwitchAfterDiscardRetainsAllDraftsAndDirtyBuffers() = runTest {
        val drafts = fixture(switchProject = { throw IOException("Folder missing") })
        drafts.editor.openModified(first)
        drafts.editor.openModified(second)
        advanceTimeBy(1_000)
        runCurrent()
        val before = drafts.editor.openFiles

        drafts.editor.requestSwitchProject(nextProject)
        drafts.editor.discardPendingChanges()
        runCurrent()

        assertEquals(before, drafts.editor.openFiles)
        assertTrue(drafts.editor.openFiles.all { it.isModified })
        assertEquals("first edited", drafts.store.read(first.uri))
        assertEquals("second edited", drafts.store.read(second.uri))
        assertTrue(drafts.editor.errorMessage!!.contains("Folder missing"))
        assertNull(drafts.switchedTo)
        assertTrue(drafts.manualWrites.isEmpty())
    }

    @Test
    fun confirmedTabDiscardRemovesOnlyItsDraftAndCancelsPendingWrite() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        drafts.editor.openModified(second)
        advanceTimeBy(1_000)
        runCurrent()
        drafts.editor.selectFile(first.uri)
        drafts.editor.updateContent("discarded pending edit")
        advanceTimeBy(500)
        drafts.editor.requestCloseTab(first.uri)
        assertEquals("first edited", drafts.store.read(first.uri))

        drafts.editor.discardPendingChanges()
        runCurrent()

        assertEquals(listOf(second.uri), drafts.editor.openFiles.map { it.uri })
        assertTrue(drafts.editor.activeFile!!.isModified)
        assertNull(drafts.store.read(first.uri))
        assertEquals("second edited", drafts.store.read(second.uri))
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertEquals("second edited", drafts.store.read(second.uri))
        assertTrue(drafts.manualWrites.isEmpty())
    }

    @Test
    fun confirmedProjectDiscardRemovesAllDraftsWithoutWritingOriginals() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        drafts.editor.openModified(second)
        advanceTimeBy(1_000)
        runCurrent()
        drafts.editor.updateContent("discarded pending edit")

        drafts.editor.requestSwitchProject(nextProject)
        drafts.editor.discardPendingChanges()
        runCurrent()

        assertEquals(nextProject, drafts.switchedTo)
        assertTrue(drafts.editor.openFiles.isEmpty())
        assertNull(drafts.store.read(first.uri))
        assertNull(drafts.store.read(second.uri))
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertNull(drafts.store.read(second.uri))
        assertTrue(drafts.manualWrites.isEmpty())
    }

    @Test
    fun confirmedFileDeletionRemovesDraftAndCancelsPendingWrite() = runTest {
        val drafts = fixture()
        drafts.editor.openModified(first)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("first edited", drafts.store.read(first.uri))
        drafts.editor.updateContent("deleted pending edit")
        advanceTimeBy(500)

        drafts.editor.removeFile(first.uri)
        runCurrent()

        assertTrue(drafts.editor.openFiles.isEmpty())
        assertNull(drafts.store.read(first.uri))
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertTrue(drafts.manualWrites.isEmpty())
    }

    @Test
    fun renamingPendingDraftMovesLatestTextAndCancelsOldUriDebounce() = runTest {
        val drafts = fixture()
        val renamedUri = Uri.parse("content://files/renamed.kt")
        drafts.editor.openModified(first)
        advanceTimeBy(400)
        drafts.editor.updateContent("latest pending edit")

        drafts.autosaver.renameDraft(first.uri, renamedUri)
        drafts.editor.renameFile(first.uri, "renamed.kt", newUri = renamedUri)
        runCurrent()

        assertNull(drafts.store.read(first.uri))
        assertEquals("latest pending edit", drafts.store.read(renamedUri))
        assertEquals(renamedUri, drafts.editor.activeFile?.uri)
        assertTrue(drafts.editor.activeFile!!.isModified)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertEquals("latest pending edit", drafts.store.read(renamedUri))

        drafts.editor.updateContent("edit after rename")
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
        assertEquals("edit after rename", drafts.store.read(renamedUri))
        assertTrue(drafts.manualWrites.isEmpty())
        assertTrue(drafts.errors.isEmpty())
    }

    @Test
    fun renamingUnopenedPersistedDraftPreservesEmptyContentAcrossStoreInstances() = runTest {
        val directory = temporaryFolder.newFolder()
        val drafts = fixture(directory = directory)
        val renamedUri = Uri.parse("content://files/renamed.kt")
        drafts.store.write(first.uri, "")

        drafts.autosaver.renameDraft(first.uri, renamedUri)
        runCurrent()

        val recovered = LocalDraftStore(directory, StandardTestDispatcher(testScheduler))
        assertNull(recovered.read(first.uri))
        assertEquals("", recovered.read(renamedUri))
        assertTrue(drafts.editor.openFiles.isEmpty())
        assertTrue(drafts.manualWrites.isEmpty())
        assertTrue(drafts.errors.isEmpty())
    }

    @Test
    fun deletingAcrossStoreInstancesCannotBeUndoneByCanceledInFlightWrite() = runTest {
        val directory = temporaryFolder.newFolder()
        val ioDispatcher = QueuedDispatcher()
        val lifetime = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val drafts = fixture(directory = directory, ioDispatcher = ioDispatcher, scope = scope)
        val cleanup = DraftAutosaver(
            scope,
            LocalDraftStore(directory, ioDispatcher),
            onError = { drafts.errors += it }
        )

        try {
            drafts.editor.openFile(first.copy(content = "stale in-flight draft"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, ioDispatcher.queuedCount)

            cleanup.removeDraft(first.uri)
            scope.cancel()
            runCurrent()
            // Cleanup must wait for the older write, even through another store instance.
            assertEquals(1, ioDispatcher.queuedCount)
            ioDispatcher.runNext()
            runCurrent()
            assertEquals(1, ioDispatcher.queuedCount)
            assertEquals(
                "stale in-flight draft",
                directory.listFiles()!!.single().readText(Charsets.UTF_8)
            )
            ioDispatcher.runNext()
            runCurrent()

            assertEquals(0, ioDispatcher.queuedCount)
            assertTrue(lifetime.isCompleted)
            val recovered = LocalDraftStore(directory, StandardTestDispatcher(testScheduler))
            assertNull(recovered.read(first.uri))
            assertTrue(drafts.manualWrites.isEmpty())
            assertTrue(drafts.errors.isEmpty())
        } finally {
            scope.cancel()
            runCurrent()
            // Release non-cancellable IO even if an assertion failed while holding the mutex.
            while (ioDispatcher.queuedCount > 0) {
                ioDispatcher.runNext()
                runCurrent()
            }
        }
    }

    @Test
    fun flushPersistsAllLatestPendingEditsBeforeScopeCancellation() = runTest {
        val lifetime = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val drafts = fixture(scope = scope)
        drafts.editor.openModified(first)
        drafts.editor.openModified(second)
        advanceTimeBy(250)
        drafts.editor.updateContent("second latest edit")

        drafts.autosaver.flush()
        scope.cancel()
        runCurrent()

        assertEquals(250L, testScheduler.currentTime)
        assertEquals("first edited", drafts.store.read(first.uri))
        assertEquals("second latest edit", drafts.store.read(second.uri))
        assertTrue(lifetime.isCompleted)
        assertTrue(drafts.editor.openFiles.all { it.isModified })
        assertTrue(drafts.manualWrites.isEmpty())
        assertTrue(drafts.errors.isEmpty())
    }

    @Test
    fun cleanOpenAndClosePreserveAnUnreadExistingDraft() = runTest {
        val drafts = fixture()
        drafts.store.write(first.uri, "unread local draft")

        drafts.editor.openFile(first)
        runCurrent()
        assertEquals("unread local draft", drafts.store.read(first.uri))
        drafts.editor.requestCloseTab(first.uri)
        runCurrent()

        assertTrue(drafts.editor.openFiles.isEmpty())
        assertEquals("unread local draft", drafts.store.read(first.uri))
        assertTrue(drafts.manualWrites.isEmpty())
        drafts.autosaver.removeDraft(first.uri)
        runCurrent()
        assertNull(drafts.store.read(first.uri))
    }

    @Test
    fun localStorageFailureIsReportedWithoutWritingOriginalOrCleaningBuffer() = runTest {
        val drafts = fixture(directory = temporaryFolder.newFile("not-a-directory"))
        drafts.editor.openModified(first)
        val before = drafts.editor.openFiles

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(before, drafts.editor.openFiles)
        assertTrue(drafts.editor.activeFile!!.isModified)
        assertEquals(first.content, drafts.editor.activeFile?.savedContent)
        assertTrue(drafts.manualWrites.isEmpty())
        assertNull(drafts.store.read(first.uri))
        assertEquals(1, drafts.errors.size)
        assertTrue(drafts.errors.single().contains("save a local draft"))
        assertTrue(drafts.errors.single().contains("first.kt"))
        assertTrue(drafts.errors.single().contains("Could not create the local drafts directory"))
    }

    private fun EditorSession.openModified(file: OpenFile) {
        openFile(file)
        updateContent("${file.content} edited")
    }

    private fun TestScope.fixture(
        directory: File = temporaryFolder.newFolder(),
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        scope: CoroutineScope = backgroundScope,
        writeFile: suspend (OpenFile) -> Unit = {},
        switchProject: (Uri) -> Unit = {}
    ) = DraftEditor(scope, directory, ioDispatcher, writeFile, switchProject)

    private class DraftEditor(
        scope: CoroutineScope,
        directory: File,
        ioDispatcher: CoroutineDispatcher,
        writeFile: suspend (OpenFile) -> Unit,
        switchProject: (Uri) -> Unit
    ) {
        val manualWrites = mutableListOf<OpenFile>()
        val errors = mutableListOf<String>()
        var switchedTo: Uri? = null
            private set
        val store = LocalDraftStore(directory, ioDispatcher)
        val autosaver = DraftAutosaver(scope, store, onError = { errors += it })
        val editor = EditorSession(
            writeFile = {
                manualWrites += it
                writeFile(it)
            },
            switchProject = {
                switchProject(it)
                switchedTo = it
            },
            onFilesChanged = autosaver::updateFiles
        )
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
