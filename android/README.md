# LRC Editor — Android

Native port of `../lrc-editor.html` (see `../lrc-editor-android-spec.md`). Kotlin, Jetpack Compose (Material 3),
Media3 ExoPlayer, DataStore. `minSdk 34`, `targetSdk/compileSdk 36`.

## Build

Open `android/` in Android Studio (it will create the Gradle wrapper and `local.properties`), or from a shell with
Gradle and the Android SDK installed:

    gradle wrapper && ./gradlew :app:installDebug
    ./gradlew :app:testDebugUnitTest      # LRC parser / ID3 round-trip tests

## Layout

- `core/Lrc.kt`, `core/Id3.kt` — 1:1 ports of the tested LRC parser/serializer and ID3v2 reader/writer.
- `core/Search.kt` — tag parsing, fuzzy name search, include/exclude tag filtering.
- `data/` — settings (DataStore), session + recents persistence, library index/scanner (`Library.kt`), last-search prefs, tick-sound synth.
- `EditorViewModel.kt` — all editor state and ExoPlayer control.
- `MainActivity.kt` — share (`ACTION_SEND`) and "Open with" (`ACTION_VIEW`) handling for MP3s.
- `ui/` — Compose screens. `ui/Songs.kt` is the song picker (Recent / By name / By tags), `ui/SettingsScreen.kt` holds the music-folder + rescan controls. Navigation is three bottom tabs: Songs, Editor, Settings.

## Design notes

- Opened files are copied into app-private storage (`current.mp3`), so session resume needs no URI permission
  (share-intent URIs only carry a temporary grant). Exports go through the system "save as" picker.

## Troubleshooting files and logs

- **Flagged songs.** Songs the user marked in the editor menu ("Mark file as buggy", "Mark lyrics as broken") are listed with their full path, title, tags and problems on every app start and whenever a mark changes. Read them with `adb logcat -d -s LrcFlagged`, or `adb shell cat /sdcard/Android/data/dev.lrceditor/files/lrc-flagged-files.txt`. Then `adb pull` the file to look at it.
- **Debug log.** Settings > Library > Export debug log writes every song with problems to `/sdcard/Android/data/dev.lrceditor/files/lrc-debug-log.json`.
- **Lyrics fetching.** A failed fetch logs the exception and the network state (DNS servers, Private DNS, validated) to logcat: `adb logcat -d -s LyricsFetch`.
