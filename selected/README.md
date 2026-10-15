# Master Playlist — Selected Languages

The full library exceeds GitHub's 100 MiB single-file limit, so it is published as **shards/**: one playlist per language (split further only if a language alone exceeds the limit). Subscribe to `master-index.m3u` for everything, or to an individual shard (e.g. `shards/hindi.m3u`) for one language.
Books in any other language are excluded. Multilingual compilation tracks are included only when their
EXTINF label or audio filename identifies one of the six languages; unknown-language items are dropped.

## Navigation design
Each track carries `group-title="🌐 Language · 🎭 Genre · 🔤 Author-initial"`, plus `#EXTGRP` markers at
every group change, so IPTV players build a three-level browse tree: Language → Genre → A–Z.
Titles use `Book — Author · Chapter`, so searching by book name or author works inside any group.

## Track counts
| Language | Books | Tracks |
| --- | --- | --- |
| 🇬🇧 English | 15583 | 365545 |
| 🇧🇩 Bangla | 5 | 5 |
| 🇮🇳 Hindi | 7 | 44 |
| 🇵🇰 Urdu | 1 | 35 |
| 🇸🇦 Arabic | 8 | 48 |
| 🇹🇷 Turkish | 3 | 4 |
| **Total** | **15603** | **365681** |

- Master size: 85.3 MiB across 6 shards (max file: 85.2 MiB)
- Mode: sharded-compact
- Genre categories: 147
- Author categories: 6346
- Per-book playlists: 16352

## Companion playlists (same filtered set, single-axis navigation)
- [by-language/](by-language/) — one file per language
- [by-genre/](by-genre/) — one file per genre
- [by-author/](by-author/) — one file per author
- [by-title/](by-title/) — one file per book (chapter order preserved)

Generated from the source M3U taxonomy by generate_master.js.
