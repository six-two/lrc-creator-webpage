# Changelog

High-level, human-readable list of what changed in the Android app. Newest version first.

## 0.0.2

- Updated and slimmed down the Github CI pipeline
- Editor: "Discard all changes…" in the overflow menu returns the lyrics to how they were when last loaded or saved (with a confirmation; Undo can bring the changes back).
- Fixed: swiping a line right to clear its time could delete the line instead, and the tenths of a second were cut off on small screens.
- Editor: the per-line play and edit buttons are gone. Tap a line's text to play from it (lines without a time are just selected); double-tap to edit.
- Small screens show times as m:ss.t (e.g. 1:23.5) in the line list and the player.
- Editor: undo and redo (in the overflow menu) for line changes such as edits, deletes, cleared/set times, nudges and imported lyrics.
- Editor: the plus/minus buttons on each line are replaced by swiping the timestamp (left/right or up/down, or off, in Settings > Editor). Nothing happens on lines without a time.
- Settings are now grouped into sub pages: Library (music folder, scanning, ignored folders/files), Search, Editor (timing and swipe gestures) and Saving.
- Editor: swipe a line to delete it or to clear its time. Both directions can be changed (or turned off) in Settings > Editor. The delete and clear buttons are gone to save space.
- Editor: double-tapping a line's text hides the timestamp and buttons so the text field gets the full width, with the cursor placed at the end of the text.
- Editor: the song's total length is no longer shown next to the seek bar; "Set time" is now "Set" and "+ Pause" is a compact icon, so the buttons fit on small screens. Rows and the player are slightly tighter on short screens.
- Songs: the "Recent" tab label no longer wraps on narrow screens, which made the tab row too tall.
- Faster scanning: folders and files are read in parallel.
- Settings to customize what search results show (artist/album, tags, lyrics status, file name) and a new option to show each song's path relative to the music folder, which helps tell same-named files apart. The lyrics status is hidden automatically when filtering for a specific lyrics state.
- The editor's save button now writes the lyrics straight into the MP3 you are editing, with an optional confirmation (on by default, can be turned off in Settings). Saving as a `.lrc` file, copying the lyrics, and saving an MP3 copy moved into an overflow menu. If the app has no write access to the original file it asks where to save instead.
- Editor header: the file name is shortened to fit, with the save button and an overflow menu next to it. The overflow menu holds the lyrics text import/edit and a new "File info" dialog showing the full path, tags, artist, album, bitrate, sample rate, tag version, size and cover art.
- On short screens the bottom tab bar drops its icons and becomes a slim text-only bar.
- After setting a line's time, the editor now moves on to the next line by default.
- The "default export action" setting is gone, since the save button replaces it.
- Option to ignore folders and file names while scanning. By default this skips Syncthing and other sync/trash folders (`.stfolder`, `.stversions`, `.debris`, ...) and Syncthing conflict copies.

## 0.0.1

First Android version, ported from the web app.


- Timing editor: line list, play/seek, "Set time" and "+ Pause" buttons, nudging, direct timestamp and multi-line text editing, optional tick sound.
- Reads and writes lyrics in MP3 files (embedded as synced LRC text) and can export `.lrc` files or copy them to the clipboard.
- Open an MP3 by sharing it to the app or with "Open with" from a file manager.
- Bottom tabs for Songs, Editor and Settings.
- Songs tab: recently edited songs, search by song title, search by file name (both with optional fuzzy matching), and search by tags with include/exclude lists and autocomplete showing how many songs have each tag. Your last search is remembered.
- Lyrics filter on every search page: any lyrics, unsynced, synced, or no lyrics.
- Music library index: pick a music folder in Settings; tags come from the grouping field ("Anime; English; Cover"). The index refreshes in the background on launch when older than 2 days, with a manual rescan, a full rescan, and the duration of the last scan shown.
