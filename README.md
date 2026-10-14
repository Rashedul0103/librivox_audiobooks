# librivox_audiobooks

LibriVox audiobook playlists in M3U format.

## Complete IPTV-compatible playlist

**Raw playlist URL:**

https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlist.m3u

Open that raw URL in a compatible IPTV or audio player. The generator scans the repository's by-author, by-genre, by-language, and by-title book playlists; collects the audio URLs; deduplicates tracks; and builds one playlist with IPTV-compatible categories.

The master is ordered by category, book, and chapter sequence. Its groups combine genre and language when the GitHub file-size budget permits, falling back to genre groups if needed. Labels are compacted automatically to keep the complete playlist below GitHub's 100 MiB per-file limit. The individual book M3U files retain their full chapter names, reader names, durations, and direct audio URLs.

## Original catalogues

- [Browse by author](by-author/)
- [Browse by genre](by-genre/)
- [Browse by language](by-language/)
- [Browse by title](by-title/)

## How it works

The master playlist references the original audio URLs; audio files are not copied into this repository. The generator runs automatically when catalogue files or its script change, and can also be run manually from the GitHub Actions tab. See [the generated index](playlists/README.md) for the last run's track count, category count, and final playlist size.

A standard M3U entry supports one group-title value. To avoid duplicating hundreds of thousands of audio entries (which would exceed GitHub's per-file limit), the unified master uses a combined category label rather than creating a separate duplicate for every author, genre, language, and title.
