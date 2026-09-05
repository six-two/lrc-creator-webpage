# A simple, manual LRC creator

You can upload an MP3 file, paste in the lyrics, and them time them by pressing a specific key (`Enter`) when a line should start.
All files are processed locally and insted of using the hosted version at <https://lrc-editor.six-two.dev/>, you can also just download the `lrc-editor.html` file and use it locally.

## Features

- Works offline
- No installation or dependencies
- Easy to create or slightly nudge timings using key bindings
- Can embed LRC directly into MP3 metadata
- Supported file formats: MP3 only

## Vibe coding disclaimer

Unlike my other projects, this is entirely vibecoded (by Claude Sonnet 5 - Medium). As the free plan has very limited tokens and you can tell any LLM to fix bugs about as well as I can, I most likely will not handle any bug request.
If you fix a bug or implement / vibe code a cool new feature yourself, feel free to open a PR with a short description of what you changed and I might merge it if I like the changes.

## Why this, when there are so many similar projects already?

I wanted something customized to my uses. For example, most of my music already contains unsynched lyrics (thanks Bandcamp), so extracting them with Mp3tag just to paste them again into a web app (which is how most existing tools seem to work) was too anoying for me.
Also it is a single HTML file, so it is easy to use locally, can easily be pasted into an LLM to customize it further, etc.
It also has persistence, as I hate loosing work when I close my browser.
