package com.cusapps.onliecodes.model

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EditorSessionTest {
    private val first = OpenFile(Uri.parse("content://files/first.kt"), "first.kt", "first")
    private val second = OpenFile(Uri.parse("content://files/second.kt"), "second.kt", "second")
    private val nextProject = Uri.parse("content://files/tree/next")
    private val writes = mutableListOf<OpenFile>()
    private var switchedTo: Uri? = null
    private var editor = EditorSession(
        writeFile = { writes += it },
        switchProject = { switchedTo = it }
    )

    private fun openModified(file: OpenFile) {
        editor.openFile(file)
        editor.updateContent("${file.content} edited")
    }

    @Test
    fun closingCleanTabDoesNotPromptEvenWithOtherDirtyTabs() {
        openModified(first)
        editor.openFile(second)

        editor.requestCloseTab(second.uri)

        assertEquals(listOf(first.uri), editor.openFiles.map { it.uri })
        assertEquals(first.uri, editor.activeFile?.uri)
        assertNull(editor.filesPendingSave)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun closingInactiveDirtyTabOnlyPromptsForThatTab() {
        openModified(first)
        openModified(second)

        editor.requestCloseTab(first.uri)

        assertEquals(listOf(first.uri), editor.filesPendingSave?.map { it.uri })
        assertEquals(second.uri, editor.activeFile?.uri)
        assertEquals(2, editor.openFiles.size)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun cancelingTabCloseKeepsAllEditsAndSelection() {
        openModified(first)
        openModified(second)
        val before = editor.openFiles
        editor.requestCloseTab(first.uri)

        editor.cancelPendingAction()

        assertEquals(before, editor.openFiles)
        assertEquals(second.uri, editor.activeFile?.uri)
        assertNull(editor.filesPendingSave)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun discardingTabCloseOnlyRemovesRequestedTabWithoutWriting() {
        openModified(first)
        openModified(second)
        editor.requestCloseTab(first.uri)

        editor.discardPendingChanges()

        assertEquals(listOf(second.uri), editor.openFiles.map { it.uri })
        assertTrue(editor.activeFile!!.isModified)
        assertNull(editor.filesPendingSave)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun deletingTabResolvesItsPendingCloseConfirmation() {
        openModified(first)
        editor.requestCloseTab(first.uri)

        editor.removeFile(first.uri)

        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.filesPendingSave)
        assertNull(editor.errorMessage)
    }

    @Test
    fun deletingTabDuringSaveDoesNotRestoreItsPendingCloseOrReportStaleErrors() = runBlocking {
        val releaseWrite = CompletableDeferred<Unit>()
        editor = EditorSession(
            writeFile = { releaseWrite.await(); throw IOException("Document deleted") },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        editor.requestCloseTab(first.uri)
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { editor.savePendingChanges() }

        editor.removeFile(first.uri)
        releaseWrite.complete(Unit)
        saving.join()

        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.filesPendingSave)
        assertNull(editor.errorMessage)
        assertFalse(editor.isSaving)
    }

    @Test
    fun savingInactiveTabWritesItsLatestContentBeforeClosingOnlyIt() = runBlocking {
        openModified(first)
        openModified(second)
        editor.requestCloseTab(first.uri)

        editor.savePendingChanges()

        assertEquals(listOf(first.uri), writes.map { it.uri })
        assertEquals("first edited", writes.single().content)
        assertEquals(listOf(second.uri), editor.openFiles.map { it.uri })
        assertTrue(editor.activeFile!!.isModified)
        assertNull(editor.filesPendingSave)
    }

    @Test
    fun switchingWithCleanTabsDoesNotPrompt() {
        editor.openFile(first)

        editor.requestSwitchProject(nextProject)

        assertEquals(nextProject, switchedTo)
        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.activeFile)
        assertNull(editor.filesPendingSave)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun cancelingProjectSwitchKeepsAllTabsAndDoesNotSwitch() {
        openModified(first)
        openModified(second)
        val before = editor.openFiles
        editor.requestSwitchProject(nextProject)
        assertEquals(before, editor.filesPendingSave)

        editor.cancelPendingAction()

        assertEquals(before, editor.openFiles)
        assertEquals(second.uri, editor.activeFile?.uri)
        assertNull(switchedTo)
        assertNull(editor.filesPendingSave)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun discardingProjectSwitchClosesAllTabsWithoutWriting() {
        openModified(first)
        openModified(second)
        editor.requestSwitchProject(nextProject)

        editor.discardPendingChanges()

        assertEquals(nextProject, switchedTo)
        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.activeFile)
        assertNull(editor.filesPendingSave)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun saveAllWritesEveryDirtyTabAndSkipsCleanTabsBeforeSwitching() = runBlocking {
        editor = EditorSession(
            writeFile = { writes += it },
            switchProject = {
                assertEquals(2, writes.size)
                assertTrue(editor.openFiles.none { file -> file.isModified })
                switchedTo = it
            }
        )
        openModified(first)
        openModified(second)
        editor.openFile(OpenFile(Uri.parse("content://files/clean.kt"), "clean.kt", "clean"))
        editor.requestSwitchProject(nextProject)

        editor.savePendingChanges()

        assertEquals(listOf(first.uri, second.uri), writes.map { it.uri })
        assertEquals(listOf("first edited", "second edited"), writes.map { it.content })
        assertEquals(nextProject, switchedTo)
        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.filesPendingSave)
    }

    @Test
    fun partialSaveAllFailureKeepsProjectOpenAndRetryOnlySavesRemainingDirtyFiles() = runBlocking {
        var failFirst = true
        editor = EditorSession(
            writeFile = {
                if (it.uri == first.uri && failFirst) throw IOException("Storage full")
                writes += it
            },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        openModified(second)
        editor.requestSwitchProject(nextProject)

        editor.savePendingChanges()

        assertNull(switchedTo)
        assertEquals(2, editor.openFiles.size)
        assertTrue(editor.openFiles[0].isModified)
        assertFalse(editor.openFiles[1].isModified)
        assertEquals(listOf(first.uri), editor.filesPendingSave?.map { it.uri })
        assertEquals(listOf(second.uri), writes.map { it.uri })
        assertTrue(editor.errorMessage!!.contains("first.kt"))
        assertFalse(editor.isSaving)

        failFirst = false
        editor.savePendingChanges()

        assertEquals(listOf(second.uri, first.uri), writes.map { it.uri })
        assertEquals(nextProject, switchedTo)
        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.errorMessage)
    }

    @Test
    fun permissionFailureKeepsClosingTabOpenAndAllowsCancel() = runBlocking {
        editor = EditorSession(
            writeFile = { throw SecurityException("Permission denied") },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        editor.requestCloseTab(first.uri)

        editor.savePendingChanges()

        assertEquals("first edited", editor.activeFile?.content)
        assertTrue(editor.activeFile!!.isModified)
        assertNotNull(editor.filesPendingSave)
        assertTrue(editor.errorMessage!!.contains("Permission denied"))
        assertFalse(editor.isSaving)

        editor.cancelPendingAction()
        assertNull(editor.filesPendingSave)
        assertNull(editor.errorMessage)
        assertEquals(1, editor.openFiles.size)
    }

    @Test
    fun manualSaveFailureReportsErrorWithoutMarkingFileClean() = runBlocking {
        editor = EditorSession(
            writeFile = { throw IOException("Unable to open write stream") },
            switchProject = { switchedTo = it }
        )
        openModified(first)

        editor.saveActiveFile()

        assertTrue(editor.activeFile!!.isModified)
        assertEquals("first edited", editor.activeFile?.content)
        assertTrue(editor.errorMessage!!.contains("first.kt"))
        assertFalse(editor.isSaving)
    }

    @Test
    fun revertingAfterPartialWriteFailureStillRequiresConfirmation() = runBlocking {
        var diskContent = first.content
        editor = EditorSession(
            writeFile = {
                diskContent = ""
                throw IOException("Write failed after truncation")
            },
            switchProject = { switchedTo = it }
        )
        openModified(first)

        editor.saveActiveFile()
        editor.dismissError()
        editor.updateContent(first.content)

        assertNotEquals(diskContent, editor.activeFile?.content)
        assertTrue(editor.activeFile!!.isModified)
        editor.requestCloseTab(first.uri)
        assertNotNull(editor.filesPendingSave)
        editor.cancelPendingAction()
        editor.requestSwitchProject(nextProject)
        assertNotNull(editor.filesPendingSave)
        assertNull(switchedTo)
    }

    @Test
    fun editsRenameAndSelectionDuringManualSaveAreNotOverwritten() = runBlocking {
        val releaseWrite = CompletableDeferred<Unit>()
        editor = EditorSession(
            writeFile = { writes += it; releaseWrite.await() },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { editor.saveActiveFile() }

        assertTrue(editor.isSaving)
        editor.updateContent("newer edit")
        editor.renameFile(first.uri, "renamed.kt")
        editor.openFile(second)
        releaseWrite.complete(Unit)
        saving.join()

        val current = editor.openFiles.first()
        assertEquals("newer edit", current.content)
        assertEquals("renamed.kt", current.name)
        assertEquals("first edited", current.savedContent)
        assertTrue(current.isModified)
        assertEquals(second.uri, editor.activeFile?.uri)
        assertFalse(editor.isSaving)
    }

    @Test
    fun editsDuringConfirmationSaveKeepActionPendingUntilSavedAgain() = runBlocking {
        val releaseWrite = CompletableDeferred<Unit>()
        editor = EditorSession(
            writeFile = { writes += it; releaseWrite.await() },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        editor.requestCloseTab(first.uri)
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { editor.savePendingChanges() }

        editor.updateContent("newer edit")
        releaseWrite.complete(Unit)
        saving.join()

        assertEquals("newer edit", editor.activeFile?.content)
        assertTrue(editor.activeFile!!.isModified)
        assertNotNull(editor.filesPendingSave)
        assertNotNull(editor.errorMessage)

        editor.savePendingChanges()
        assertEquals(listOf("first edited", "newer edit"), writes.map { it.content })
        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.filesPendingSave)
    }

    @Test
    fun savingCannotBeDuplicatedDiscardedOrReplacedWhileRunning() = runBlocking {
        val releaseWrite = CompletableDeferred<Unit>()
        editor = EditorSession(
            writeFile = { writes += it; releaseWrite.await() },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        editor.requestSwitchProject(nextProject)
        val saving = launch(start = CoroutineStart.UNDISPATCHED) { editor.savePendingChanges() }

        editor.savePendingChanges()
        editor.cancelPendingAction()
        editor.discardPendingChanges()
        editor.requestCloseTab(first.uri)
        editor.requestSwitchProject(Uri.parse("content://files/tree/other"))

        assertTrue(editor.isSaving)
        assertNotNull(editor.filesPendingSave)
        assertNull(switchedTo)
        assertEquals(1, writes.size)
        assertEquals(1, editor.openFiles.size)

        releaseWrite.complete(Unit)
        saving.join()
        assertEquals(nextProject, switchedTo)
        assertTrue(editor.openFiles.isEmpty())
    }

    @Test
    fun revertingToLastSavedContentClearsDirtyState() = runBlocking {
        openModified(first)
        editor.updateContent(first.content)
        assertFalse(editor.activeFile!!.isModified)

        editor.updateContent("saved edit")
        editor.saveActiveFile()
        editor.updateContent("temporary edit")
        assertTrue(editor.activeFile!!.isModified)
        editor.updateContent("saved edit")
        assertFalse(editor.activeFile!!.isModified)

        editor.requestCloseTab(first.uri)
        assertTrue(editor.openFiles.isEmpty())
        assertNull(editor.filesPendingSave)
    }

    @Test
    fun unavailableNewProjectDoesNotDiscardOldBuffers() {
        editor = EditorSession(
            writeFile = { writes += it },
            switchProject = { throw IOException("Folder missing") }
        )
        openModified(first)
        val before = editor.openFiles
        editor.requestSwitchProject(nextProject)

        editor.discardPendingChanges()

        assertEquals(before, editor.openFiles)
        assertEquals(first.uri, editor.activeFile?.uri)
        assertNotNull(editor.errorMessage)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun canceledWriteKeepsPendingActionAndResetsSavingState() = runBlocking {
        editor = EditorSession(
            writeFile = { throw CancellationException("Canceled") },
            switchProject = { switchedTo = it }
        )
        openModified(first)
        editor.requestSwitchProject(nextProject)

        try {
            editor.savePendingChanges()
            fail("Cancellation should propagate")
        } catch (_: CancellationException) {
            assertFalse(editor.isSaving)
            assertTrue(editor.activeFile!!.isModified)
            assertNotNull(editor.filesPendingSave)
            assertNull(switchedTo)
        }
    }
}
