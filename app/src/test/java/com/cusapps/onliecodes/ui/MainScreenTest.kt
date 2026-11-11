package com.cusapps.onliecodes.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cusapps.onliecodes.model.OpenFile
import com.cusapps.onliecodes.ui.theme.OnlieCodesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val first = OpenFile(Uri.parse("content://files/first.kt"), "first.kt", "edited", "original")
    private val second = OpenFile(Uri.parse("content://files/second.kt"), "second.kt", "edited", "original")
    private var displayedError by mutableStateOf<String?>(null)

    private fun showConfirmation(
        files: List<OpenFile>,
        isSaving: Boolean = false,
        errorMessage: String? = null,
        errorTitle: String = "Action Failed",
        onSave: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onCancel: () -> Unit = {},
        onErrorDismissed: () -> Unit = {},
        isRestoringSession: Boolean = false,
        projectName: String? = "Project",
        pendingFiles: List<OpenFile>? = files
    ) {
        displayedError = errorMessage
        compose.setContent {
            OnlieCodesTheme {
                MainScreen(
                    projectName = projectName,
                    projectFiles = emptyList(),
                    openFiles = files,
                    activeFile = files.firstOrNull(),
                    projectMissing = false,
                    filesPendingSave = pendingFiles,
                    isSaving = isSaving,
                    errorMessage = displayedError,
                    errorTitle = errorTitle,
                    onOpenFolderClick = {},
                    onSelectProjectClick = {},
                    onInitializeProjectClick = {},
                    onProjectMissingDismissed = {},
                    onFileClick = {},
                    onToggleExpand = {},
                    onTabSelect = {},
                    onTabClose = {},
                    onContentChange = {},
                    onSaveClick = {},
                    onSaveChanges = onSave,
                    onDiscardChanges = onDiscard,
                    onCancelChanges = onCancel,
                    onErrorDismissed = onErrorDismissed,
                    onCreateFile = { _, _ -> },
                    onCreateFolder = { _, _ -> },
                    onDeleteFile = {},
                    onRenameFile = { _, _ -> },
                    isDarkTheme = true,
                    onToggleTheme = {},
                    isRestoringSession = isRestoringSession
                )
            }
        }
    }

    @Test
    fun singleModifiedFileOffersSaveDiscardAndCancel() {
        var saves = 0
        var discards = 0
        var cancels = 0
        showConfirmation(
            listOf(first),
            onSave = { saves++ },
            onDiscard = { discards++ },
            onCancel = { cancels++ }
        )

        compose.onNodeWithText("Save changes to 'first.kt' before continuing?").assertExists()
        compose.onNodeWithText("Save All").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, saves)
        assertEquals(1, discards)
        assertEquals(1, cancels)
    }

    @Test
    fun multipleModifiedFilesOfferSaveAllAndListAffectedFiles() {
        var saves = 0
        showConfirmation(listOf(first, second), onSave = { saves++ })

        compose.onNodeWithText("first.kt").assertExists()
        compose.onNodeWithText("second.kt").assertExists()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Save All").performClick()

        assertEquals(1, saves)
    }

    @Test
    fun savingShowsProgressAndDisablesAllConfirmationActions() {
        showConfirmation(listOf(first, second), isSaving = true)

        compose.onNodeWithText("Saving changes...").assertExists()
        compose.onNodeWithText("Save All").assertIsNotEnabled()
        compose.onNodeWithText("Discard").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun failedSaveDisplaysErrorAndKeepsAllChoicesAvailable() {
        val error = "Could not save 'first.kt': Permission denied"
        showConfirmation(listOf(first), errorMessage = error, errorTitle = "Save Failed")

        compose.onNodeWithText("Save Failed").assertIsDisplayed()
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Discard").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun manualSaveFailureShowsDismissibleSaveFailedDialog() {
        var dismissals = 0
        val error = "Could not save 'first.kt': Storage full. Your changes are still unsaved."
        showConfirmation(
            listOf(first),
            errorMessage = error,
            errorTitle = "Save Failed",
            onErrorDismissed = { displayedError = null; dismissals++ },
            pendingFiles = null
        )

        compose.onNodeWithText("Save Failed").assertIsDisplayed()
        compose.onNodeWithText("Action Failed").assertDoesNotExist()
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("first.kt *").assertExists()
        compose.onNodeWithText("OK").performClick()

        assertEquals(1, dismissals)
        compose.onNodeWithText("Save Failed").assertDoesNotExist()
        compose.onNodeWithText("first.kt *").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save Current File").assertIsEnabled()
    }

    @Test
    fun saveAllFailureIsVisibleBeforeLongPendingFileList() {
        val files = (1..20).map { index ->
            first.copy(uri = Uri.parse("content://files/file$index.kt"), name = "file$index.kt")
        }
        val error = "Could not save 'file1.kt': Permission denied"
        showConfirmation(files, errorTitle = "Save Failed")
        compose.onNodeWithText("file20.kt").performScrollTo().assertIsDisplayed()

        compose.runOnIdle { displayedError = error }

        compose.onNodeWithText("Save Failed").assertIsDisplayed()
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("Save All").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
    }

    @Test
    fun resolvedChangesOfferContinueRatherThanSavingZeroFiles() {
        showConfirmation(emptyList())

        compose.onNodeWithText("There are no unsaved changes left. Continue?").assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Discard").assertDoesNotExist()
    }

    @Test
    fun recoveryShowsProgressAndPreventsEditingOrSwitchingProjects() {
        showConfirmation(
            listOf(first),
            errorMessage = "Recovery warning",
            isRestoringSession = true,
            projectName = null,
            pendingFiles = null
        )

        compose.onNodeWithText("Restoring session...").assertExists()
        compose.onNodeWithText("Open Folder").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Save Current File").assertIsNotEnabled()
        compose.onNodeWithText("edited").assertDoesNotExist()
        compose.onNodeWithText("Recovery warning").assertDoesNotExist()
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Open New Project").assertIsNotEnabled()
    }
}
