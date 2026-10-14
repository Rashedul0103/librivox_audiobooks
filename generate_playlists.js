const fs = require("fs");
const path = require("path");

const ROOT = process.cwd();
const OUTPUT_DIR = path.join(ROOT, "playlists");
const IGNORE_DIRS = new Set([".git", ".github", "node_modules", "playlists"]);
const AUDIO_EXTENSIONS = /\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|aiff)(?:[?#].*)?$/i;
const MAX_MASTER_BYTES = 95000000;

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

function safeText(value) {
  return String(value || "").replace(/[\r\n\t]+/g, " ").replace(/\s+/g, " ").trim();
}
function safeAttribute(value) {
  return safeText(value).replace(/"/g, "'").replace(/,/g, " ");
}
function slugify(value) {
  return String(value || "").normalize("NFKD").replace(/[\u0300-\u036f]/g, "")
    .toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}
function titleCase(value) {
  return safeText(value).split(" ").map(word =>
    word.split("-").map(part => part ? part.charAt(0).toUpperCase() + part.slice(1).toLowerCase() : part).join("-")
  ).join(" ");
}
function displayCategory(folder) {
  return titleCase(String(folder || "").replace(/_/g, " "));
}
function displayAuthor(folder) {
  const cleaned = String(folder || "").replace(/_\d+$/, "").replace(/_+$/g, "").replace(/_/g, " ");
  return titleCase(cleaned).replace(/\b([A-Z])\b/g, "$1.");
}
function displayBookSlug(slug) {
  return titleCase(String(slug || "").replace(/[_-]+/g, " "));
}

function pathMetadata(relativePath) {
  const parts = relativePath.split("/");
  const taxonomy = parts[0];
  const stem = path.basename(relativePath, path.extname(relativePath));
  let author = "";
  let genre = "";
  let language = "";
  let bookSlug = slugify(stem);

  if (taxonomy === "by-author" && parts.length >= 4) {
    author = displayAuthor(parts[2]);
    const authorSlug = slugify(String(parts[2] || "").replace(/_\d+$/, "").replace(/_+$/g, ""));
    const suffix = "-by-" + authorSlug;
    if (authorSlug && bookSlug.endsWith(suffix)) bookSlug = bookSlug.slice(0, -suffix.length);
  } else {
    const suffixMatch = bookSlug.match(/^(.+)-by-[a-z0-9]+(?:-[a-z0-9]+)*$/i);
    if (suffixMatch) bookSlug = suffixMatch[1];
  }

  if (taxonomy === "by-genre" && parts.length >= 3) genre = displayCategory(parts[1]);
  if (taxonomy === "by-language" && parts.length >= 3) language = displayCategory(parts[1]);

  return {
    taxonomy,
    bookTitle: displayBookSlug(bookSlug) || displayBookSlug(stem) || "Unknown Book",
    author,
    genre,
    language
  };
}

function cleanChapterTitle(value) {
  return safeText(value)
    .replace(/\s*[-–—]\s*Read by\b.*$/i, "")
    .replace(/\s*[-–—]\s*Duration\s+\d{1,2}:\d{2}:\d{2}\b.*$/i, "")
    .replace(/\s+Duration\s+\d{1,2}:\d{2}:\d{2}\b.*$/i, "")
    .replace(/\s+/g, " ").trim() || "Track";
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

    if (AUDIO_EXTENSIONS.test(line) && /^https?:\/\//i.test(line)) {
      const comma = pendingInfo.indexOf(",");
      const info = pendingInfo.startsWith("#EXTINF:")
        ? pendingInfo.slice(8, comma >= 0 ? comma : undefined).trim() : "";
      const durationMatch = info.match(/^-?\d+/);
      const rawTitle = comma >= 0 ? pendingInfo.slice(comma + 1).trim() : path.basename(line);
      tracks.push({
        url: line,
        duration: durationMatch ? durationMatch[0] : "-1",
        chapterTitle: cleanChapterTitle(rawTitle),
        order: tracks.length + 1
      });
    }
    pendingInfo = "";
  }
  return tracks;
}

function shorten(value, maxChars) {
  const text = safeText(value);
  if (text.length <= maxChars) return text;
  if (maxChars <= 1) return text.slice(0, maxChars);
  const head = text.slice(0, maxChars - 1);
  const lastSpace = head.lastIndexOf(" ");
  return (lastSpace >= Math.floor(maxChars * 0.55) ? head.slice(0, lastSpace) : head).trimEnd() + "…";
}
function chooseFirst(values, fallback) {
  return [...values].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }))[0] || fallback;
}
function getGroup(track, mode) {
  const genre = track.genre || "Other / Unclassified";
  const language = track.language || "Unknown language";
  return mode === "genre-language" ? "🎭 " + genre + " | 🌐 " + language : "🎭 " + genre;
}
function makeLabel(track, options) {
  const number = String(track.order || 1).padStart(2, "0");
  const book = track.bookTitle || "Unknown Book";
  const author = track.author ? " by " + track.author : "";
  if (options.style === "rich") return shorten(book + author + " · " + number + " — " + track.chapterTitle, options.maxLabel);
  if (options.style === "author") return shorten(book + author + " · " + number, options.maxLabel);
  if (options.style === "chapter") return shorten(book + " · " + number + " — " + track.chapterTitle, options.maxLabel);
  return shorten(book + " · " + number, options.maxLabel);
}
function renderMaster(tracks, options) {
  const lines = ["#EXTM3U", "#PLAYLIST:LibriVox Audiobooks — Complete, IPTV Categorized"];
  for (const track of tracks) {
    const group = safeAttribute(getGroup(track, options.groupMode));
    const duration = options.duration ? (track.duration || "-1") : "-1";
    lines.push("#EXTINF:" + duration + ' group-title="' + group + '",' + safeText(makeLabel(track, options)));
    lines.push(track.url);
  }
  return lines.join("\n") + "\n";
}
function compareForMode(a, b, mode) {
  const groupDiff = getGroup(a, mode).localeCompare(getGroup(b, mode), undefined, { sensitivity: "base" });
  if (groupDiff) return groupDiff;
  const bookDiff = a.bookTitle.localeCompare(b.bookTitle, undefined, { sensitivity: "base", numeric: true });
  if (bookDiff) return bookDiff;
  const authorDiff = a.author.localeCompare(b.author, undefined, { sensitivity: "base" });
  if (authorDiff) return authorDiff;
  return (a.order || 0) - (b.order || 0) || a.url.localeCompare(b.url);
}

function main() {
  console.log("Scanning source audiobook playlists...");
  const files = walk(ROOT).sort((a, b) => a.rel.localeCompare(b.rel));
  const master = new Map();
  let parsedBooks = 0;
  let scannedTrackEntries = 0;

  for (const file of files) {
    const metadata = pathMetadata(file.rel);
    const parsedTracks = parsePlaylist(file);
    if (!parsedTracks.length) continue;
    parsedBooks++;
    for (const parsed of parsedTracks) {
      scannedTrackEntries++;
      let track = master.get(parsed.url);
      if (!track) {
        track = {
          ...parsed,
          bookTitle: metadata.bookTitle,
          author: metadata.author,
          genres: new Set(),
          languages: new Set()
        };
        master.set(parsed.url, track);
      } else {
        if (!track.author && metadata.author) track.author = metadata.author;
        if ((!track.bookTitle || track.bookTitle === "Unknown Book") && metadata.bookTitle) {
          track.bookTitle = metadata.bookTitle;
        }
      }
      if (metadata.genre) track.genres.add(metadata.genre);
      if (metadata.language) track.languages.add(metadata.language);
    }
  }

  const tracks = [...master.values()];
  for (const track of tracks) {
    track.genre = chooseFirst(track.genres, "Other / Unclassified");
    track.language = chooseFirst(track.languages, "Unknown language");
    delete track.genres;
    delete track.languages;
  }
  if (!tracks.length) throw new Error("No audio tracks were found. Refusing to write an empty master playlist.");

  const optionsToTry = [
    { groupMode: "genre-language", style: "rich", maxLabel: 72, duration: true },
    { groupMode: "genre-language", style: "rich", maxLabel: 56, duration: true },
    { groupMode: "genre-language", style: "author", maxLabel: 48, duration: true },
    { groupMode: "genre-language", style: "chapter", maxLabel: 42, duration: true },
    { groupMode: "genre-language", style: "book", maxLabel: 34, duration: true },
    { groupMode: "genre", style: "author", maxLabel: 42, duration: true },
    { groupMode: "genre", style: "chapter", maxLabel: 38, duration: true },
    { groupMode: "genre", style: "book", maxLabel: 32, duration: true },
    { groupMode: "genre", style: "book", maxLabel: 26, duration: false },
    { groupMode: "genre", style: "book", maxLabel: 18, duration: false }
  ];

  let masterContent = "";
  let selected = null;
  let selectedSize = 0;
  const sortedByMode = new Map();

  for (const options of optionsToTry) {
    if (!sortedByMode.has(options.groupMode)) {
      sortedByMode.set(options.groupMode, [...tracks].sort((a, b) => compareForMode(a, b, options.groupMode)));
    }
    const candidate = renderMaster(sortedByMode.get(options.groupMode), options);
    const bytes = Buffer.byteLength(candidate, "utf8");
    console.log("Candidate: group=" + options.groupMode + ", labels=" + options.style +
      "/" + options.maxLabel + ", size=" + bytes + " bytes");
    if (bytes <= MAX_MASTER_BYTES) {
      masterContent = candidate;
      selected = options;
      selectedSize = bytes;
      break;
    }
  }

  if (!selected) {
    const options = { groupMode: "genre", style: "book", maxLabel: 10, duration: false };
    const fallbackTracks = [...tracks].sort((a, b) => compareForMode(a, b, options.groupMode));
    masterContent = renderMaster(fallbackTracks, options);
    selected = options;
    selectedSize = Buffer.byteLength(masterContent, "utf8");
  }
  if (selectedSize > MAX_MASTER_BYTES) {
    throw new Error("The compact master would be " + selectedSize +
      " bytes, above the safety limit. No playlist was written.");
  }

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(ROOT, "playlist.m3u"), masterContent, "utf8");

  const groupNames = new Set(tracks.map(track => getGroup(track, selected.groupMode)));
  const sizeMiB = (selectedSize / (1024 * 1024)).toFixed(2);
  const readme = [
    "# LibriVox playlist index",
    "",
    "Generated: " + new Date().toISOString(),
    "Source book playlists parsed: " + parsedBooks,
    "Audio entries scanned before deduplication: " + scannedTrackEntries,
    "Unique audio URLs in the master: " + tracks.length,
    "IPTV group categories: " + groupNames.size,
    "Master playlist size: " + sizeMiB + " MiB",
    "Compact label mode: " + selected.style + " (" + selected.maxLabel + " characters max)",
    "Grouping mode: " + selected.groupMode,
    "",
    "## One-link master playlist",
    "",
    "[playlist.m3u](../playlist.m3u) — a complete, deduplicated M3U playlist with IPTV-compatible categories.",
    "",
    "Raw URL:",
    "https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/playlist.m3u",
    "",
    "## Browse the original catalogues",
    "",
    "- [By author](../by-author/) — original book playlists grouped into author folders.",
    "- [By genre](../by-genre/) — original book playlists grouped by genre.",
    "- [By language](../by-language/) — original book playlists grouped by language.",
    "- [By title](../by-title/) — original book playlists grouped by title.",
    "",
    "The master links to the original audio URLs; it does not copy audio files into this repository. To keep the one-file raw playlist below GitHub's 100 MiB limit, track labels are shortened automatically if needed. The source book M3Us retain the original chapter names, reader names, durations, and direct audio URLs.",
    "",
    "A standard M3U entry supports one group-title. The master combines genre and language in each category when the file-size budget permits, and falls back to genre groups if necessary. Book and author names remain in track labels when the selected compact format allows.",
    ""
  ].join("\n");
  fs.writeFileSync(path.join(OUTPUT_DIR, "README.md"), readme, "utf8");

  console.log("Complete: " + parsedBooks + " source book playlists, " +
    tracks.length + " unique audio tracks, " + groupNames.size + " groups.");
  console.log("Master size: " + selectedSize + " bytes (" + sizeMiB + " MiB); group mode=" +
    selected.groupMode + ", label mode=" + selected.style + ".");
}
main();
