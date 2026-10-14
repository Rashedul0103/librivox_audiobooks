# librivox_audiobooks

LibriVox audiobook playlists in M3U format.

## Playlists

- **Complete playlist:** [playlist.m3u](playlist.m3u) — one deduplicated list of all discovered audio tracks, sorted alphabetically by track title.
- **Category playlists:** see the [playlists directory](playlists/README.md) for separate by-author, by-genre, by-language, and by-title playlists.

### Raw streaming URLs

Use these raw URLs in a compatible player:

- Complete playlist: https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlist.m3u
- By author: https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlists/by-author.m3u
- By genre: https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlists/by-genre.m3u
- By language: https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlists/by-language.m3u
- By title: https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlists/by-title.m3u

The playlists reference original audio URLs; they do not copy audio files into this repository. A standard M3U playlist cannot provide interactive category navigation inside one file, so the complete playlist and four category-specific playlists are generated separately.

The generator runs automatically when catalogue files change. You can also run the **Generate complete audiobook playlists** workflow manually from the Actions tab.
