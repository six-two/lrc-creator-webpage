# Changelog

High-level, human-readable list of what changed in the Android app. Newest version first.

## 0.0.6

- New warning "short break between lyrics": an empty line followed by a lyric line less than a set time later. The limit is set in Settings > Editor > Warnings (default 0.3 s). The empty line shows "short" at its end, and the warning is above the editor and in the Fix list under Warnings.
- New problem "timestamp after the end of the song": a lyric time later than the song is long. It shows in the Fix list, above the editor, and as "past end" on the lines concerned. The length of each song is now read when the library is scanned.
- New warning "empty lines in a row" for an empty line directly after another empty line.
- Editor: long-press a line's text to edit it, with all of the text selected. This replaces double-tapping, which also made the player jump to the line. Tapping a line still plays from it.
- Editor menu: "Mark file as buggy" puts the song in the Fix list (group "Marked as buggy file") until you remove the mark. Like the broken-lyrics mark it is kept on the phone, not in the file.
- Songs marked as buggy or as having broken lyrics are written, with their full path, to the log (`adb logcat -s LrcFlagged`) and to lrc-flagged-files.txt in the app's folder on shared storage (Android/data/dev.lrceditor/files) on every start and whenever a mark changes, so a problem can be looked at straight from the file.
- Fetching lyrics: when a source can't be reached, the message now names the actual error (for example UnknownHostException), and the network state (DNS servers, Private DNS) is written to the log (`adb logcat -s LyricsFetch`). This is to find out why fetching fails on some phones.
- Settings > Lyrics sources: "Test connection" tries every source (also turned-off ones) and shows which worked and the exact error of those that did not, plus the phone's network state. Tap the result to copy it.
- Import / edit lyrics text: all the text starts selected, so pasting or typing replaces it, and a new button "Replace text with clipboard" does it in one tap.
- Settings > Editor > Player: "Minimal player" (off by default) hides the skip, play / pause, speed and tick buttons under the progress bar. Then a tap on the time next to the progress bar plays / pauses, a double tap jumps back 2 seconds, and more taps jump back further (5, 10, then 30 seconds; never before the start). The playback speed now has a setting too, next to the tick sound.
- New warning "empty line at the start": the first line of the lyrics (after the song info tags like [ar:...]) is empty, which is unnecessary. In the Fix list under Warnings a button removes it from the lyrics of all those songs at once (asks first, like the tag fix). Songs with a synced frame and the song open in the editor are skipped.
- The Fix list now has "Include (all of)" and "Exclude (none of)" fields with suggestions, like the Tags search, to pick problem categories (with their song counts). Without an included category it lists songs with problems, and "Also songs with only warnings" adds the others. The groups below follow the included categories.
- The "Remove it from N songs" button for the empty first line is now in that problem's group in the Fix list, and only touches the songs listed there.
- Closing the keyboard now also takes the focus off the text field, so suggestion dropdowns (tags, problems) close with it.
- The library data version went up (to 6) because the index now stores the song length and the short breaks, so the app rescans all songs in the background once.

## 0.0.5

- The app now needs Android 16 or newer (it no longer installs on older versions), so it behaves the same on every supported phone.
- New Realign mode (editor menu > Realign timings): when a whole song is early or late, play it and tap Set when each of the first few lines is actually sung. The app then shows how far off each was and moves every line of the song by the middle value (so one bad tap doesn't matter). Undo brings the old times back. How many lines you time (default 5) is set in Settings > Editor.
- The Fix list and the editor now show how often each problem occurs in the song, e.g. "out of order ×3" or "same timestamp twice in a row ×7".
- Editor: a line with exactly the same time as the line before it now has a warning at its end.
- Fixed: lyrics that are in the file twice with the same timestamps (a b c, a b c) are flagged as "two lyrics in one" again, also when each line is twice in a row. 
- Fixed: the Back button goes to the tab you used last (for example Settings > Back > Editor > Back > Library) instead of only returning to the editor.
- Fixed: a second block of lyrics after the first (for example a translation under its own [by:...] tag) is now flagged as "two lyrics in one" even when it uses exactly the same times, instead of only "out of order". This also applies when the second block has no song info in front of it, as long as it is on (nearly) the same times. Songs with song info in the middle are reported once, not under both "two lyrics in one" problems.
- The library data version went up (to 4) because the index now stores the counts and the check changed, so the app rescans all songs in the background once.

## 0.0.4

- New problems "two lyrics in one (tags in the middle)" and "two lyrics in one (times start over)" in the Fix list. The first is flagged when song info like [ar:...] or [ti:...] sits between lyric lines (credits at the end, other tags like [offset:0] before the lyrics, and a second block with the same times as the first, as in lyrics with a translation, are ignored). The second when the times run up, jump back near the start and run up again with mostly the same text (two synced lyrics pasted one after the other). Songs with synced lyrics are re-read once to find these.
- New warning "same timestamp twice in a row" when two timed lines in a row have exactly the same time. It shows in the Fix list under Warnings and above the editor, where tapping it jumps to the line.
- Songs with two lyrics in one: the editor warning has a "compare" link that opens both lyrics side by side (one above the other on narrow screens). Tap any line to play from it; both lyrics highlight their current line, so you can see which one follows the singing. "Keep this" keeps one of them (Undo brings the other back).
- Settings > Library: "Export debug log" writes every song with problems (path, title, lyrics state, problems) and the number of songs per problem to a JSON file in the app's folder on shared storage (Android/data/dev.lrceditor/files/lrc-debug-log.json), for troubleshooting.
- The library index now carries a data version. When it differs from the one the app expects (for example after an update that changes what is stored or checked), the app rescans all songs in the background on start and tells you when it starts and when it is done. This replaces the one-time partial re-reads.
- A popup tells you when the app only has read access to the music folder, and lets you choose the folder again.
- Fixed: choosing the music folder could give the app read-only access, so saving lyrics into a song opened the "save as" picker instead. The folder picker now asks for write access too, and the app tells you if it only got read access. If saving still asks where to save, choose the music folder again in Settings.
- Editor menu: "Mark lyrics as broken" puts the song in the Fix list (group "Marked as broken") until you choose "Remove broken-lyrics mark". The mark is kept on the phone, not written into the file, and it stays when you change the lyrics, so remove it yourself once the song is fixed.
- The Fix list is now grouped by problem. Each group has a title with its song count and can be expanded or collapsed; a song with several problems appears in each of its groups.
- Playing a song now pauses other audio (music, podcasts) on the phone.

## 0.0.3

- Fetch lyrics: the editor can now download lyrics from LRCLIB, NetEase Cloud Music and Lyricsify (tried in the order set in Settings > Lyrics sources). Synced lyrics are preferred; plain text is used if nothing synced exists. You get a preview first and can undo. NetEase can include romanization as a second line. If a site blocks the app, "Open in browser" and "Paste from clipboard" let you bring the lyrics in by hand. The app now needs internet access for this.
- Learning tags: every song row and the editor menu have a "Learning tag" choice (None, Learn, SingAlong, Unsingable). It writes that one tag into the file's grouping and leaves all other tags alone.
- New Learn tab with your Learn, Unsingable and SingAlong songs, their counts, and a search box for each list.
- Songs are now tagged automatically when lyrics are saved: "Lyrics" if the song has lyrics, "Karaoke" if every line is also timed.
- New "Fix" list in the Library for songs with problems: untimed lines, times out of order, wrong Lyrics/Karaoke tags, more than one learning tag. "Fix tags" corrects wrong tags in bulk without opening the songs. The editor shows the same warnings for the open song.
- Lines without a time are now saved as 00:00.00 and stay untimed when loaded again (before, they borrowed the previous line's time). Other players show such lines at the very start. A real time is never saved below 00:00.01. Exception: a 00:00.00 on the very first lyric line is read as a real time (many LRCs from the internet start that way), as long as some other line has a real time.
- The Songs tab is now called Library. A song without lyrics no longer opens the paste dialog by itself; the editor offers "Fetch lyrics" and "Paste lyrics" buttons instead.
- Songs with problems open on the first problem line, and tapping a warning above the editor (untimed lines, times out of order, odd timestamps) jumps to the next line with that problem.
- Lines with several timestamps (like [00:10.00][00:50.00] chorus, common in LRCs from the internet) are split into one line per timestamp and put in time order. This only works when every line of the song has a time; otherwise the editor shows the lyrics as raw text with an explanation, and saving is blocked until you fix them. The Fix list marks such songs as "lyrics can't be read".
- Fixed: metadata lines with brackets inside, like [ti:Song [english ver.]], were treated as untimed lyric lines.
- The Fix list now names the exact tag problem: "Lyrics tag missing", "Karaoke tag missing", "Karaoke tag but not fully timed" or "Lyrics tag but no lyrics". "Fix tags" corrects all of them.
- Settings shows the installed version at the bottom. Local debug builds also show when they were installed.
- Back button: opening a song from the Library or Learn tab and pressing Back now returns to that list, with its scroll position and search kept. Back from there leaves the app.
- Older library indexes are re-read once on the next scan to find these problems.
- Songs whose lyrics contain timestamp-like tags written with odd Unicode digits (for example left behind by a romanization tool) are flagged as warnings. The Fix list has a new Warnings view for reviewing them, and the editor shows the same warning. Such tags are treated as plain text, never as times.

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
