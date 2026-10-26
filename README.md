# OnlieCodes

OnlieCodes (shown as **Onlie Codes** on Android) is a native Android code and text editor built with Kotlin, Jetpack Compose, and Material 3. Open a project folder on your device, browse its files, and edit multiple files in tabs.

## Features

- Folder-based projects using Android's Storage Access Framework, with access granted through the system folder picker.
- An expandable file tree with actions to create, rename, and delete files and folders.
- Tabbed editing with an unsaved-change indicator and a save action for the active file.
- A monospace editor with line numbers, soft wrapping, and vertical scrolling.
- Basic regex-based highlighting for common keywords, numbers, quoted strings, and C-style comments.
- Light and dark themes through **Settings**, with the theme preference and last project folder remembered between launches.

## Requirements

- **JDK 17**, configured as the Gradle JDK in Android Studio or through `JAVA_HOME` for command-line builds.
- **Android SDK Platform 34**, **Build Tools 34.0.0**, and **Platform Tools**.
- An Android device or emulator running **Android 10 (API 29)** or newer.
- Internet access for the first Gradle sync/build to download dependencies.

The project uses Android Gradle Plugin **8.5.2**, Kotlin **1.9.24**, and the bundled **Gradle 8.7** wrapper. A separate Gradle installation is not required. Both the compile and target SDK versions are **34**.

## Getting Started

### Android Studio

1. Clone or download the repository and open its root folder in Android Studio.
2. Install the required Android SDK components using **SDK Manager** and select JDK 17 as the **Gradle JDK**.
3. Allow Gradle sync to finish.
4. Select the `app` run configuration and an API 29 or newer device/emulator, then run the app.

### Command Line

Run commands from the repository root. Set `JAVA_HOME` to your JDK 17 installation and configure the Android SDK location through `ANDROID_HOME` or an untracked `local.properties` file.

For example, a Windows `local.properties` file can contain the following, replacing `YOUR_USERNAME` with your account name:

```properties
sdk.dir=C:/Users/YOUR_USERNAME/AppData/Local/Android/Sdk
```

On macOS or Linux, use your local SDK path instead. Do not commit `local.properties`; it is machine-specific and already ignored by Git.

**Windows (PowerShell):**

```powershell
.\gradlew.bat :app:assembleDebug
```

**macOS / Linux:**

```bash
./gradlew :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

With `adb` on your `PATH` and a running emulator or an authorized USB-debugging device, install it with:

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Using the App

1. Tap **Open Folder**, select a project directory in the system picker, and grant access.
2. Expand folders in the **PROJECT FILES** sidebar and tap a file to open it in a tab. Use the menu button to hide or show the sidebar.
3. Use **New File** and **New Folder** in the sidebar toolbar to create items at the project root. Each item's **File Actions** menu provides rename/delete actions; folders also provide creation actions for their contents.
4. Edit a file and tap **Save Current File** in the top bar. A `*` beside a tab name indicates unsaved changes.
5. Use **Options > Open New Project** to select another folder, or **Options > Settings > Dark Theme** to switch themes.

**Saving is manual.** Closing a tab, switching projects, or leaving the app can discard unsaved edits; there is currently no unsaved-change confirmation or recovery mechanism. File operations affect the selected folder directly, so keep backups of important files.

The app is an editor, not a compiler or execution environment. Syntax highlighting uses a shared set of regex patterns rather than language-specific parsing; it does not provide autocomplete, diagnostics, or full language support.

## Project Structure

Application sources are under `app/src/main/java/com/cusapps/onliecodes/`:

| Path | Purpose |
| --- | --- |
| `MainActivity.kt` | Project access, file operations, open-file state, and saved preferences. |
| `model/EditorModels.kt` | File-tree and open-file models. |
| `ui/MainScreen.kt` | Main layout, toolbar, and dialogs. |
| `ui/components/CodeEditor.kt` | Text rendering, syntax highlighting, line numbers, and cursor offset mapping. |
| `ui/components/FileTree.kt` | Project sidebar and file/folder actions. |
| `ui/components/FileTabs.kt` | Open-file tabs and modified-state indicators. |
| `ui/theme/` | Material theme and editor color palettes. |

Build configuration lives in `app/build.gradle.kts`, `build.gradle.kts`, and `settings.gradle.kts`. The Gradle wrapper is in `gradle/wrapper/`.

## Development Checks

Run Android Lint from the project root:

```powershell
.\gradlew.bat :app:lintDebug
```

On macOS or Linux, use `./gradlew :app:lintDebug`. The report is written to `app/build/reports/lint-results-debug.html`.

There are currently no unit or instrumentation test sources in the repository. For manual validation, check folder selection, file creation/rename/deletion, tab switching, saving, wrapped-line rendering, and theme persistence on a device or emulator.

## CI and Container Builds

The [Android CI workflow](.github/workflows/build.yml) builds the debug APK for pushes and pull requests targeting `main` or `master`, and can also be triggered manually. Successful runs upload the APK as the `app-debug-apk` artifact.

The repository also includes a `Dockerfile` and `docker-build.sh`. These currently install and invoke Gradle 8.5 rather than the project's Gradle 8.7 wrapper, so the container helper needs to be aligned with the project's build requirements before use. Prefer the wrapper-based build instructions above.
