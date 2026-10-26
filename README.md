# AudioAZ CloudStream extension

A CloudStream provider for AudioAZ. It browses and searches the catalogue on demand and shows audiobook title, cover, author, language, narrator, genres, duration and summary where available.

## Features

- Home lists for most popular and most recent audiobooks.
- Search by title, author, or keyword.
- Audiobook detail pages with available metadata.
- Playback attempts only links that the page explicitly exposes from LibriVox or Internet Archive and that point to recognized audio files.
- No bulk crawling, audio rehosting, authentication bypass, DRM bypass, or access-control circumvention.

Not every AudioAZ listing will play directly in CloudStream. When the page does not expose an authorized external audio source, use its AudioAZ website link instead. AudioAZ says its catalogue aggregates public-domain materials, but rights can vary by item, so this extension deliberately keeps playback limited to recognized public-source links.

## Install

When the GitHub Actions build succeeds, add this URL in CloudStream under Settings → Extensions → Add Repository:

https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/builds/repo.json

The workflow builds the .cs3 package automatically when the audioaz-cloudstream source branch changes. You do not need Android Studio or a local Gradle setup.

## Project layout

The source project lives in the AudioAZCloudStream folder on the audioaz-cloudstream branch. Main is not changed.
