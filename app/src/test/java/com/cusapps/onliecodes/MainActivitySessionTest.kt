package com.cusapps.onliecodes

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.cusapps.onliecodes.model.EditorSession
import com.cusapps.onliecodes.model.LocalDraftStore
import com.cusapps.onliecodes.model.LocalSessionStore
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.model.SavedSession
import com.cusapps.onliecodes.model.SessionTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileNotFoundException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySessionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val treeUri = DocumentsContract.buildTreeDocumentUri("session.test", "project")
    private val permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val mainDispatcher = StandardTestDispatcher()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private lateinit var application: Application
    private lateinit var provider: ProjectProvider
    private lateinit var project: File
    private lateinit var sessionFile: File
    private lateinit var sessions: LocalSessionStore
    private lateinit var drafts: LocalDraftStore

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = RuntimeEnvironment.getApplication()
        project = temporaryFolder.newFolder("project")
        provider = ProjectProvider(project)
        provider.attachInfo(application, ProviderInfo().apply {
            authority = "session.test"
            exported = true
            grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal("session.test", provider)
        application.contentResolver.takePersistableUriPermission(treeUri, permissions)
        sessionFile = File(application.noBackupFilesDir, "session.json")
        sessions = LocalSessionStore(sessionFile)
        drafts = LocalDraftStore(File(application.noBackupFilesDir, "drafts"))
    }

    @After
    fun tearDown() {
        try {
            runTest(mainDispatcher) {
                controllers.toList().forEach { stopActivity(it) }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun startupRestoresOrderedNestedTabsActiveFileAndUnsavedDraftsWithoutWritingOriginals() = runTest(mainDispatcher) {
        val first = file("first.kt", "first original")
        val nested = file("nested/second.kt", "second original")
        val third = file("third.kt", "third original")
        val saved = snapshot(listOf(nested, first, third), first.uri)
        sessions.write(saved)
        drafts.write(first.uri, "unsaved first")
        drafts.write(third.uri, "")

        val current = editor(startActivity())

        assertEquals(current.errorMessage, saved.tabs.map { it.uri }, current.openFiles.map { it.uri })
        assertEquals(first.uri, current.activeFile?.uri)
        assertEquals("unsaved first", current.activeFile?.content)
        assertEquals(first.content, current.activeFile?.savedContent)
        assertTrue(current.activeFile!!.isModified)
        assertEquals(nested, current.openFiles.first())
        assertEquals("", current.openFiles.last().content)
        assertTrue(current.openFiles.last().isModified)
        assertEquals("first original", File(project, "first.kt").readText())
        assertEquals("third original", File(project, "third.kt").readText())
        assertEquals(saved, sessions.read())
    }

    @Test
    fun backgroundFlushRestoresLatestEditsAndSelectionInFreshActivity() = runTest(mainDispatcher) {
        val first = file("first.kt", "first")
        val second = file("second.kt", "second")
        sessions.write(snapshot(emptyList(), null))
        val initial = startActivity()
        val current = editor(initial)
        current.openFile(first)
        current.updateContent("first latest draft")
        current.openFile(second)
        current.updateContent("second latest draft")
        current.selectFile(first.uri)

        stopActivity(initial)
        val restarted = startActivity()
        val recovered = editor(restarted)

        assertNotSame(current, recovered)
        assertEquals(listOf(first.uri, second.uri), recovered.openFiles.map { it.uri })
        assertEquals(first.uri, recovered.activeFile?.uri)
        assertEquals(listOf("first latest draft", "second latest draft"), recovered.openFiles.map { it.content })
        assertTrue(recovered.openFiles.all { it.isModified })
        assertEquals("first", File(project, "first.kt").readText())
        assertEquals("second", File(project, "second.kt").readText())

        recovered.selectFile(second.uri)
        assertEquals(second.uri, sessions.read()?.activeFileUri)
        stopActivity(restarted)
        assertEquals(second.uri, editor(startActivity()).activeFile?.uri)
    }

    @Test
    fun closedAndDiscardedTabsDoNotReturnAndManualSaveDoesNotRecoverStaleDraft() = runTest(mainDispatcher) {
        val clean = file("clean.kt", "clean")
        val discarded = file("discarded.kt", "discarded original")
        val saved = file("saved.kt", "saved original")
        sessions.write(snapshot(listOf(clean, discarded, saved), saved.uri))
        drafts.write(discarded.uri, "discarded edit")
        drafts.write(saved.uri, "manually saved edit")
        val initial = startActivity()
        val current = editor(initial)

        current.requestCloseTab(clean.uri)
        assertNull(current.filesPendingSave)
        current.requestCloseTab(discarded.uri)
        current.discardPendingChanges()
        current.saveActiveFile()
        stopActivity(initial)

        val recovered = editor(startActivity())
        assertEquals(listOf(saved.uri), recovered.openFiles.map { it.uri })
        assertEquals("manually saved edit", recovered.activeFile?.content)
        assertFalse(recovered.activeFile!!.isModified)
        assertNull(drafts.read(discarded.uri))
        assertNull(drafts.read(saved.uri))
        assertEquals("discarded original", File(project, "discarded.kt").readText())
        assertEquals("manually saved edit", File(project, "saved.kt").readText())
    }

    @Test
    fun missingOriginalRecoversEmptyDraftAndUnreadableTabsRemainIndexedForRetry() = runTest(mainDispatcher) {
        val clean = file("clean.kt", "clean")
        val missingDraft = OpenFile(documentUri("missing.kt"), "missing.kt", "old original")
        val unavailable = OpenFile(documentUri("later.kt"), "later.kt", "old original")
        sessions.write(snapshot(listOf(clean, missingDraft, unavailable), missingDraft.uri))
        drafts.write(missingDraft.uri, "")
        val initial = startActivity()
        val current = editor(initial)

        assertEquals(listOf(clean.uri, missingDraft.uri), current.openFiles.map { it.uri })
        assertEquals("", current.activeFile?.content)
        assertNull(current.activeFile?.savedContent)
        assertTrue(current.activeFile!!.isModified)
        assertNotNull(current.errorMessage)
        assertEquals(listOf(clean.uri, missingDraft.uri, unavailable.uri), sessions.read()?.tabs?.map { it.uri })

        current.requestCloseTab(clean.uri)
        stopActivity(initial)
        file("later.kt", "now readable")
        val recovered = editor(startActivity())

        assertEquals(listOf(missingDraft.uri, unavailable.uri), recovered.openFiles.map { it.uri })
        assertEquals(missingDraft.uri, recovered.activeFile?.uri)
        assertEquals("now readable", recovered.openFiles.last().content)
        assertEquals("", drafts.read(missingDraft.uri))
    }

    @Test
    fun reselectingProjectAfterPermissionLossKeepsRecoveredDraftsInsteadOfDiscardingThem() = runTest(mainDispatcher) {
        val first = file("first.kt", "first original")
        val second = file("second.kt", "second original")
        sessions.write(snapshot(listOf(first, second), first.uri))
        drafts.write(first.uri, "unsaved first")
        application.contentResolver.releasePersistableUriPermission(treeUri, permissions)
        provider.denyReads = true
        val initial = startActivity()
        val current = editor(initial)

        assertEquals(listOf(first.uri), current.openFiles.map { it.uri })
        assertNull(current.activeFile?.savedContent)
        assertEquals(treeUri, sessions.read()?.projectUri)
        current.dismissError()
        ReflectionHelpers.callInstanceMethod<Unit>(initial.get(), "openFolderPicker")
        val launched = shadowOf(initial.get()).nextStartedActivityForResult
        provider.denyReads = false
        shadowOf(initial.get()).receiveResult(launched.intent, Activity.RESULT_OK, Intent().setData(treeUri))
        mainDispatcher.scheduler.runCurrent()

        assertNull(current.filesPendingSave)
        assertEquals("unsaved first", current.activeFile?.content)
        assertTrue(current.activeFile!!.isModified)
        assertEquals(listOf(first.uri, second.uri), sessions.read()?.tabs?.map { it.uri })
        stopActivity(initial)
        val recovered = editor(startActivity())
        assertEquals(listOf(first.uri, second.uri), recovered.openFiles.map { it.uri })
        assertEquals("unsaved first", recovered.activeFile?.content)
        assertEquals(first.content, recovered.activeFile?.savedContent)
    }

    @Test
    fun corruptSessionFallsBackToRememberedProjectWithoutDeletingDraftText() = runTest(mainDispatcher) {
        val first = file("first.kt", "first")
        application.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("last_project_uri", treeUri.toString()).commit()
        drafts.write(first.uri, "preserved draft")
        sessionFile.writeText("{broken session")

        val current = editor(startActivity())

        assertTrue(current.openFiles.isEmpty())
        assertTrue(current.errorMessage!!.contains("Could not read the editor session"))
        assertEquals("preserved draft", drafts.read(first.uri))
        assertEquals(treeUri, sessions.read()?.projectUri)
    }

    @Test
    fun stoppingBeforeRecoveryStartsDoesNotReplaceSavedSessionWithEmptyState() = runTest(mainDispatcher) {
        val first = file("first.kt", "first")
        val saved = snapshot(listOf(first), first.uri)
        sessions.write(saved)
        drafts.write(first.uri, "preserved draft")
        val initial = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        controllers += initial

        initial.pause().stop().destroy()
        controllers -= initial
        mainDispatcher.scheduler.runCurrent()

        assertEquals(saved, sessions.read())
        assertEquals("preserved draft", drafts.read(first.uri))
        assertEquals("preserved draft", editor(startActivity()).activeFile?.content)
    }

    private fun documentUri(path: String) = DocumentsContract.buildDocumentUriUsingTree(treeUri, "project/$path")

    private fun file(path: String, content: String): OpenFile {
        val target = File(project, path)
        target.parentFile!!.mkdirs()
        target.writeText(content)
        return OpenFile(documentUri(path), target.name, content)
    }

    private fun snapshot(files: List<OpenFile>, activeUri: Uri?) = SavedSession(
        treeUri, files.map { SessionTab(it.uri, it.name) }, activeUri
    )

    private suspend fun startActivity(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        controllers += controller
        ReflectionHelpers.getField<Job>(controller.get(), "sessionRestoreJob").join()
        return controller
    }

    private suspend fun stopActivity(controller: ActivityController<MainActivity>) {
        controller.pause().stop().destroy()
        controllers -= controller
        drafts.read(Uri.EMPTY)
        runCatching { sessions.read() }
    }

    private fun editor(controller: ActivityController<MainActivity>): EditorSession =
        ReflectionHelpers.getField(controller.get(), "editor")

    private class ProjectProvider(private val directory: File) : ContentProvider() {
        var denyReads = false

        override fun onCreate() = true

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val target = documentFile(uri)
            val files = if (uri.lastPathSegment == "children") target.listFiles().orEmpty().toList() else listOf(target)
            return MatrixCursor(columns).apply {
                for (file in files.filter { it.exists() }) {
                    addRow(columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "project" + file.relativeTo(directory).path
                                .replace(File.separatorChar, '/').let { if (it.isEmpty()) "" else "/$it" }
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (file.isDirectory) {
                                DocumentsContract.Document.MIME_TYPE_DIR
                            } else {
                                "text/plain"
                            }
                            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                            DocumentsContract.Document.COLUMN_SIZE -> file.length()
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denyReads) throw SecurityException("Permission denied")
            val target = documentFile(uri)
            if (!target.exists()) throw FileNotFoundException("Document unavailable")
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
        }

        private fun documentFile(uri: Uri): File {
            val id = DocumentsContract.getDocumentId(uri)
            return if (id == "project") directory else File(directory, id.removePrefix("project/"))
        }

        override fun getType(uri: Uri) = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
