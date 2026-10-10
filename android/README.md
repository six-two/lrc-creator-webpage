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
