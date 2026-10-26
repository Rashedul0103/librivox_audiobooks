# CloudStream audiobook extensions

This branch contains two separate CloudStream providers, built from the same repository.

## AudioAZ

The AudioAZ provider browses and searches the AudioAZ catalogue, displays audiobook details, and only attempts playback when a page explicitly exposes a recognized public-domain source.

## Audible

The Audible provider uses the public Libex API (`https://libexdb.com`) for Audible catalogue metadata. It supports catalogue shelves, search, book details, author/narrator/series metadata where returned, release information, cover art, genres, and Audible plan information.

The Audible provider does not scrape Audible pages directly. This initial version is metadata-only; audio-source adapters are a separate stage and are not represented as working until tested.

## Install

After the GitHub Actions build publishes the packages, add this repository in CloudStream:

`https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/builds/repo.json`

Then install **Audible** or **AudioAZ** from the repository list.

The build workflow is intended to compile the source modules and publish their `.cs3` packages and repository metadata to the `builds` branch. No local Android Studio or Gradle installation is required.
