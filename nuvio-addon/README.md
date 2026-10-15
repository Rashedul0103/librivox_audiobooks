# LibriVox Audiobooks — Nuvio Enhanced addon

This addon uses Nuvio's Stremio-compatible addon protocol. It is designed to expose the selected-language audiobook catalogue as one installable manifest URL, while leaving all audio hosted at its original source URLs.

## What it exposes

- One catalogue for all supported-language books.
- Separate catalogue rows for English, Bangla, Hindi, Urdu, Arabic, and Turkish.
- Genre filtering and search across book titles, authors, and genres.
- Book details with chapters represented as series episodes.
- Direct chapter streams from the source M3U files.

The generated index contains book metadata only. Chapter URLs are read from the generated per-book M3U on demand. The Worker caches the index and accessed book playlists to reduce repeat requests.

## Hosting and cost

The Worker is designed to run on Cloudflare Workers Free, with no D1 database and no paid binding required. The repository remains the source of truth. The free tier has request limits, so usage should be monitored in the Cloudflare dashboard. Do not switch to a paid plan or add a payment method for this project. If Cloudflare requires a card for the account, stop and use a static/GitHub-hosted fallback instead.

## Generate the catalogue data

The repository workflow generates filtered/nuvio/catalog.json whenever the selected-language playlist generator runs. The catalogue JSON is committed to GitHub and served to the Worker from GitHub Raw.

Before deploying, wait for the GitHub Actions workflow named **Generate selected-language audiobook playlists** to complete successfully and verify that the raw catalogue JSON URL is reachable.

## Deploy

1. Create a Cloudflare account using the Free plan. Stop if the account flow asks for a payment card; it is not needed to develop this code locally.
2. Install Node.js 22 or later.
3. From this directory, run npm install.
4. Run npm test.
5. Run npx wrangler login and complete Cloudflare's browser login.
6. Run npx wrangler deploy.

wrangler.toml points the Worker at the generated catalogue JSON. After deployment, test /health and /manifest.json on the workers.dev URL. Then add the URL ending in /manifest.json in Nuvio Enhanced's Addons settings.

## API routes

- GET /manifest.json
- GET /catalog/series/librivox_all.json
- GET /catalog/series/librivox_english.json (and the other five language IDs)
- GET /meta/series/{book-id}.json
- GET /stream/series/{book-id}__chapter_{number}.json
- GET /health

## Important compatibility check

This prototype uses the Stremio series model so each audiobook can expose its chapters. Whether the installed Nuvio Enhanced build accepts direct MP3 audio-only streams must be tested in the app before considering this complete. The catalogue protocol and server responses can be tested independently; they do not prove MP3 playback works in every build.
