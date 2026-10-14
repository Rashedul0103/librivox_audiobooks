const fs = require("fs");
const path = require("path");

const ROOT = process.cwd();
const OUTPUT_DIR = path.join(ROOT, "playlists");
const TAXONOMIES = ["by-author", "by-genre", "by-language", "by-title"];
const IGNORE_DIRS = new Set([".git", ".github", "node_modules", "playlists"]);
const AUDIO_EXTENSIONS = /\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|aiff)(?:$|[?#])/i;

function walk(dir, relative = "") {
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".") || IGNORE_DIRS.has(entry.name)) continue;
    const abs = path.join(dir, entry.name);
    const rel = relative ? relative + "/" + entry.name : entry.name;
    if (entry.isDirectory()) found.push(...walk(abs, rel));
    else if (entry.isFile() && entry.name.toLowerCase().endsWith(".m3u") &&
             !["playlist.m3u", "master.m3u"].includes(entry.name.toLowerCase())) {
      found.push({ abs, rel });
    }
  }
  return found;
}

function parsePlaylist(file) {
  const lines = fs.readFileSync(file.abs, "utf8").replace(/^\uFEFF/, "").split(/\r?\n/);
  const tracks = [];
  let pendingInfo = "";
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#EXTINF:")) { pendingInfo = line; continue; }
    if (line.startsWith("#")) continue;
    if (AUDIO_EXTENSIONS.test(line)) {
      const comma = pendingInfo.indexOf(",");
      const title = comma >= 0 ? pendingInfo.slice(comma + 1).trim() : path.basename(line);
      const duration = pendingInfo.startsWith("#EXTINF:")
        ? pendingInfo.slice(8, comma >= 0 ? comma : undefined).trim()
        : "-1";
      tracks.push({ url: line, title: title || path.basename(line), duration });
    }
    pendingInfo = "";
  }
  return tracks;
}

function safeText(value) {
  return String(value || "").replace(/[\r\n]+/g, " ").trim();
}

function renderPlaylist(title, tracks) {
  const out = ["#EXTM3U", "#PLAYLIST:" + safeText(title)];
  for (const track of tracks) {
    out.push("#EXTINF:" + (track.duration || "-1") + "," + safeText(track.title));
    out.push(track.url);
  }
  return out.join("\n") + "\n";
}

function main() {
  console.log("Scanning book playlists...");
  const files = walk(ROOT).sort((a, b) => a.rel.localeCompare(b.rel));
  const master = new Map();
  const byTaxonomy = new Map(TAXONOMIES.map(name => [name, new Map()]));
  let parsedBooks = 0;
  let parsedTracks = 0;

  for (const file of files) {
    const tracks = parsePlaylist(file);
    if (!tracks.length) continue;
    parsedBooks++;
    const taxonomy = TAXONOMIES.find(name => file.rel.startsWith(name + "/"));
    for (const track of tracks) {
      parsedTracks++;
      if (!master.has(track.url)) master.set(track.url, track);
      if (taxonomy) {
        const collection = byTaxonomy.get(taxonomy);
        if (!collection.has(track.url)) collection.set(track.url, track);
      }
    }
  }

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  const sortTracks = tracks => [...tracks.values()].sort((a, b) =>
    a.title.localeCompare(b.title, undefined, { sensitivity: "base" }) || a.url.localeCompare(b.url)
  );
  const masterTracks = sortTracks(master);
  fs.writeFileSync(path.join(ROOT, "playlist.m3u"),
    renderPlaylist("LibriVox Audiobooks — Complete (deduplicated)", masterTracks), "utf8");

  for (const [taxonomy, tracks] of byTaxonomy) {
    const sorted = sortTracks(tracks);
    fs.writeFileSync(path.join(OUTPUT_DIR, taxonomy + ".m3u"),
      renderPlaylist("LibriVox Audiobooks — " + taxonomy.replace("by-", "By "), sorted), "utf8");
    console.log(taxonomy + ": " + sorted.length + " unique tracks");
  }

  const summary = [
    "# LibriVox playlist index", "",
    "Generated: " + new Date().toISOString(),
    "Book playlists parsed: " + parsedBooks,
    "Track entries scanned (before deduplication): " + parsedTracks,
    "Unique tracks in playlist.m3u: " + masterTracks.length, "",
    "## Complete playlist",
    "- [playlist.m3u](../playlist.m3u) — all unique tracks, sorted alphabetically by track title.", "",
    "## Category playlists",
    ...TAXONOMIES.map(name => "- [" + name + ".m3u](" + name + ".m3u) — tracks from the " + name.replace("by-", "") + " catalogue."),
    "",
    "All audio entries stream directly from their original URLs. The playlists contain links, not copied audio files.",
    "A standard M3U file cannot provide interactive category navigation inside a single playlist; use the separate category playlists for that.", ""
  ].join("\n");
  fs.writeFileSync(path.join(OUTPUT_DIR, "README.md"), summary, "utf8");
  console.log("Complete: " + parsedBooks + " book playlists, " + masterTracks.length + " unique tracks.");
}

main();
