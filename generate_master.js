#!/usr/bin/env node
// Build a top-level master.m3u: LibriVox books in English, Bangla, Urdu, Hindi,
// Turkish and Arabic only. Groups are "🌐 Language · 🎭 Genre · 🔤 A-Z" so IPTV
// players can navigate by language first, then genre, then author initial.
// Other languages (and unknown-language tracks from multilingual collections)
// are excluded. Companion playlists under by-language/-genre/-author/-title
// reuse the same deduplicated track set.

const fs = require("fs");
const path = require("path");

const ROOT = process.cwd();
const MASTER = path.join(ROOT, "master.m3u");
const COMPANION_OUT = process.env.MASTER_OUT || path.join(ROOT, "selected");
const AUDIO = /\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|aiff)(?:[?#].*)?$/i;
const IGNORE = new Set([".git", ".github", "node_modules", "playlists", "filtered", "selected"]);
const ALLOWED = new Map([
  ["English", "English"],
  ["Bengali", "Bangla"],
  ["Hindi", "Hindi"],
  ["Urdu", "Urdu"],
  ["Arabic", "Arabic"],
  ["Turkish", "Turkish"]
]);
const ALLOWED_VALUES = new Set(ALLOWED.values());
const FOREIGN_PREFIXES = new Set((
  "afrikaans ancient_greek bisaya-cebuano bulgarian cantonese chinese church_slavonic " +
  "croatian czech danish dutch esperanto finnish french galician german greek hebrew " +
  "hungarian indonesian irish italian japanese javanese korean latin latvian lithuanian " +
  "luxembourgish macedonian malay maori marathi middle_english multilingual norwegian " +
  "old_english old_javanese persian polish portuguese romanian russian serbian slovak " +
  "spanish swedish tagalog tamil ukrainian welsh yiddish"
).split(/\s+/));
const PREFIX_LANGUAGE = {
  english: "English",
  bengali: "Bangla",
  bangla: "Bangla",
  hindi: "Hindi",
  urdu: "Urdu",
  arabic: "Arabic",
  turkish: "Turkish"
};
const LANG_EMOJI = {
  English: "🇬🇧", Bangla: "🇧🇩", Hindi: "🇮🇳", Urdu: "🇵🇰", Arabic: "🇸🇦", Turkish: "🇹🇷"
};
const LANG_ORDER = ["English", "Bangla", "Hindi", "Urdu", "Arabic", "Turkish"];

const clean = s => String(s || "").replace(/[\r\n\t]+/g, " ").replace(/\s+/g, " ").trim();
const slug = s => String(s || "").normalize("NFKD")
  .replace(/[\u0300-\u036f]/g, "")
  .toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
const pretty = s => clean(String(s || "").replace(/[_-]+/g, " "))
  .replace(/\b\w/g, c => c.toUpperCase()) || "Unknown";

function walkInto(dir, rel, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith(".") || IGNORE.has(e.name)) continue;
    const abs = path.join(dir, e.name);
    const r = rel ? rel + "/" + e.name : e.name;
    if (e.isDirectory()) walkInto(abs, r, out);
    else if (
      e.isFile() &&
      e.name.toLowerCase().endsWith(".m3u") &&
      !["playlist.m3u", "master.m3u"].includes(e.name.toLowerCase())
    ) out.push({ abs, rel: r });
  }
  return out;
}

function metadata(rel) {
  const p = rel.split("/");
  const tax = p[0];
  const stem = path.basename(rel, path.extname(rel));
  const title = pretty(stem.replace(/-by-.+$/i, ""));
  const suffix = stem.match(/-by-(.+)$/i);
  const titleAuthor = suffix ? pretty(suffix[1].replace(/.*-by-/i, "")) : "";
  return {
    tax,
    language: tax === "by-language" && p.length >= 3 && ALLOWED.has(p[1]) ? ALLOWED.get(p[1]) : "",
    genre: tax === "by-genre" && p.length >= 3 ? pretty(p[1]) : "",
    author: tax === "by-author" && p.length >= 4 ? pretty(p[2].replace(/_\d+$/, "")) : "",
    title,
    titlePath: tax === "by-title" && p.length >= 3 ? p.slice(1).join("/") : "",
    titleAuthor,
    multilingual: /multilingual|language[-_]learning/i.test(stem)
  };
}

function forEachLine(file, cb) {
  const buf = fs.readFileSync(file.abs);
  let start = 0;
  if (buf.length >= 3 && buf[0] === 0xEF && buf[1] === 0xBB && buf[2] === 0xBF) start = 3;
  let info = "";
  const end = buf.length;
  while (start < end) {
    let nl = buf.indexOf(0x0A, start);
    if (nl < 0) nl = end;
    let raw;
    try { raw = buf.toString("utf8", start, nl); } catch { raw = ""; }
    start = nl + 1;
    const s = raw.trim();
    if (!s) continue;
    if (s.startsWith("#EXTINF:")) { info = s; continue; }
    if (s.startsWith("#")) continue;
    if (/^https?:\/\//i.test(s) && AUDIO.test(s)) {
      const comma = info.indexOf(",");
      const duration = info.startsWith("#EXTINF:")
        ? (info.slice(8, comma < 0 ? undefined : comma).match(/^-?\d+/) || ["-1"])[0]
        : "-1";
      const label = comma >= 0 ? clean(info.slice(comma + 1)) : path.basename(s);
      cb({ url: s, duration, label });
    }
    info = "";
  }
}

const KNOWN_LANGUAGE_PREFIXES = [...new Set([
  ...Object.keys(PREFIX_LANGUAGE),
  ...FOREIGN_PREFIXES
])].sort((a, b) => b.length - a.length);

function languageFromLabel(label) {
  const normalized = clean(label).toLowerCase().normalize("NFKD");
  // LibriVox EXTINF labels for mixed-language recordings start with the spoken
  // language, e.g. "Bengali - ..." or "Old English - ...".
  const match = normalized.match(/^([a-z][a-z -]*?)\s*[-–—:]/);
  if (!match) return { matched: false, language: null };
  const name = match[1].trim().replace(/[\s-]+/g, "_");
  if (Object.prototype.hasOwnProperty.call(PREFIX_LANGUAGE, name)) {
    return { matched: true, language: PREFIX_LANGUAGE[name] };
  }
  if (FOREIGN_PREFIXES.has(name)) return { matched: true, language: null };
  return { matched: false, language: null };
}

function urlLanguage(url, folderLanguage, isMultilingual, label) {
  if (isMultilingual) {
    const fromLabel = languageFromLabel(label);
    if (fromLabel.matched) return fromLabel.language;
  }
  const file = decodeURIComponent(url.split(/[?#]/)[0].split("/").pop() || "").toLowerCase();
  const token = KNOWN_LANGUAGE_PREFIXES.find(prefix => file.startsWith(prefix + "_"));
  if (token) return PREFIX_LANGUAGE[token] || null;
  // Unknown-language items in mixed collections must not inherit the folder
  // language — they get excluded instead of being mislabelled.
  if (isMultilingual) return null;
  return folderLanguage || null;
}

function safeAttr(s) {
  return clean(s).replace(/"/g, "'").replace(/,/g, " ");
}

function writeM3U(file, rows, opts) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const o = opts || {};
  const lines = ["#EXTM3U", "#PLAYLIST:" + (o.playlist || "LibriVox — selected languages")];
  let prevGroup = null;
  rows.forEach((t, i) => {
    const group = o.groupOf ? o.groupOf(t) : (t.group || t.genre || "Other");
    if (o.extgrp && group !== prevGroup) {
      lines.push("#EXTGRP:" + safeAttr(group));
      prevGroup = group;
    }
    const num = o.numbered ? String((o.startNo || 1) + i).padStart(6, "0") + " · " : "";
    lines.push(
      '#EXTINF:' + t.duration + ' group-title="' + safeAttr(group) + '",' +
      num + clean(o.displayOf ? o.displayOf(t) : (t.display || t.label)),
      t.url
    );
  });
  const fd = fs.openSync(file, "w");
  const CHUNK = 20000;
  for (let i = 0; i < lines.length; i += CHUNK) {
    fs.writeSync(fd, lines.slice(i, i + CHUNK).join("\n") + "\n");
  }
  fs.closeSync(fd);
}

function main() {
  const files = walkInto(ROOT, "", []).sort((a, b) => a.rel.localeCompare(b.rel));
  const tracks = new Map();

  const ingest = (f, row) => {
    const meta = f.meta;
    let t = tracks.get(row.url);
    if (!t) {
      t = {
        url: row.url,
        duration: row.duration,
        label: row.label,
        language: "",
        genreSet: null,
        author: "",
        title: "",
        titlePath: "",
        fallbackTitle: meta.title
      };
      tracks.set(row.url, t);
    }
    if (meta.language) {
      const language = urlLanguage(row.url, meta.language, meta.multilingual, row.label);
      if (language && ALLOWED_VALUES.has(language)) t.language = language;
    }
    if (meta.genre) { if (!t.genreSet) t.genreSet = new Set(); t.genreSet.add(meta.genre); }
    if (meta.author && !t.author) t.author = meta.author;
    if (meta.titlePath) {
      t.titlePath = meta.titlePath;
      t.title = meta.title;
      if (meta.titleAuthor) t.author = meta.titleAuthor;
    } else if (!t.title && meta.title) {
      t.title = meta.title;
    }
  };

  // Process in batches so finished files can be garbage-collected.
  const BATCH = 500;
  for (let i = 0; i < files.length; i += BATCH) {
    const batch = files.slice(i, i + BATCH);
    for (const f of batch) { f.meta = metadata(f.rel); }
    for (const f of batch) forEachLine(f, row => ingest(f, row));
  }

  const all = [...tracks.values()].filter(t => ALLOWED_VALUES.has(t.language));
  if (!all.length) throw new Error("No matching tracks; refusing to write an empty master.m3u.");

  for (const t of all) {
    t.genres = t.genreSet && t.genreSet.size ? [...t.genreSet].sort() : ["Other - Unclassified"];
    t.genre = t.genres[0];
    t.title = t.title || t.fallbackTitle || "Unknown title";
    t.author = t.author || "Unknown author";
    // Source taxonomy sometimes files collection titles under "by-author".
    if (/^(multilingual|librivox language learning|librivox multilingual|poetry multi-version|short works collection|short story collection)\b/i.test(t.author)) {
      t.author = "Various";
    }
    let descriptor = clean(t.label);
    if (descriptor.toLowerCase().startsWith(t.title.toLowerCase())) {
      descriptor = descriptor.slice(t.title.length).replace(/^\s*[-–—:]\s*/, "");
    }
    t.chapter = descriptor || t.label;
    t.display = t.title + " — " + t.author + (descriptor ? " · " + descriptor : "");
    const initial = /^[A-Za-z]/.test(t.author) ? t.author[0].toUpperCase() : "0-9";
    t.group = "🌐 " + t.language + " · 🎭 " + t.genre + " · 🔤 " + initial;
    if (!t.titlePath) {
      t.titlePath = "uncategorized/" + (slug(t.title) || slug(t.label) || "unknown") + ".m3u";
    }
  }

  const langRank = l => { const i = LANG_ORDER.indexOf(l); return i < 0 ? 99 : i; };
  const sort = (a, b) =>
    (a.title || "").localeCompare(b.title || "", undefined, { sensitivity: "base", numeric: true }) ||
    a.url.localeCompare(b.url);

  all.sort((a, b) =>
    langRank(a.language) - langRank(b.language) ||
    a.genre.localeCompare(b.genre, undefined, { sensitivity: "base" }) ||
    a.author.localeCompare(b.author, undefined, { sensitivity: "base", numeric: true }) ||
    sort(a, b)
  );

  // ---- size guard: a single flat master can exceed GitHub's 100 MiB file limit.
  // When that happens we do NOT truncate tracks — instead we split the master
  // into per-language shards (each far below the limit) and emit a small
  // master-index.m3u pointing to them. Players that follow relative #EXTINF
  // targets load the whole library; players that don't can subscribe to one
  // language shard at a time.
  const LIMIT = 95 * 1048576;
  const approxBytes = rows => rows.reduce((n, t) =>
    n + t.url.length + Buffer.byteLength(t.group, "utf8") +
    Buffer.byteLength(clean(t.display), "utf8") + 60, 0);

  const SHARD_DIR = path.join(ROOT, "shards");
  const MASTER_INDEX = path.join(ROOT, "master-index.m3u");
  let mode;

  if (approxBytes(all) <= LIMIT) {
    // Fits: classic single-file master.
    writeM3U(MASTER, all, {
      playlist: "LibriVox Master — English · Bangla · Hindi · Urdu · Arabic · Turkish",
      groupOf: t => t.group,
      extgrp: true,
      numbered: true,
      displayOf: t => t.display
    });
    mode = "single-file";
  } else {
    // Doesn't fit: compact labels first (keep every track), then shard by language.
    let rows = all;
    mode = "sharded";
    const perLangOverLimit = LANG_ORDER.some(lang =>
      approxBytes(rows.filter(t => t.language === lang)) > LIMIT);
    if (perLangOverLimit) {
      for (const r of rows) r.display = r.title + " — " + r.author;
      mode = "sharded-compact";
    }

    fs.mkdirSync(SHARD_DIR, { recursive: true });
    // Clean stale shards from previous runs.
    for (const e of fs.readdirSync(SHARD_DIR)) {
      if (e.endsWith(".m3u")) fs.unlinkSync(path.join(SHARD_DIR, e));
    }

    let globalNo = 0;
    for (const lang of LANG_ORDER) {
      const langRows = rows.filter(t => t.language === lang);
      if (!langRows.length) continue;
      const genreChunks = [];
      let cur = [], curBytes = 0;
      for (const t of langRows) {
        const b = t.url.length + Buffer.byteLength(t.group, "utf8") +
                  Buffer.byteLength(clean(t.display), "utf8") + 60;
        if (cur.length && curBytes + b > LIMIT) { genreChunks.push(cur); cur = []; curBytes = 0; }
        cur.push(t); curBytes += b;
      }
      if (cur.length) genreChunks.push(cur);

      genreChunks.forEach((chunk, i) => {
        const suffix = genreChunks.length > 1 ? "-" + String(i + 1).padStart(2, "0") : "";
        const file = slug(lang) + suffix + ".m3u";
        writeM3U(path.join(SHARD_DIR, file), chunk, {
          playlist: "LibriVox — " + lang + (suffix ? " (part " + (i + 1) + ")" : ""),
          groupOf: t => t.group,
          extgrp: true,
          numbered: true,
          startNo: globalNo + 1,
          displayOf: t => t.display
        });
        globalNo += chunk.length;
      });
    }

    // ---- master-index.m3u: tiny top-level playlist referencing the shards ----
    const indexLines = ["#EXTM3U",
      "#PLAYLIST:LibriVox Master Index — English · Bangla · Hindi · Urdu · Arabic · Turkish"];
    let no = 0;
    for (const lang of LANG_ORDER) {
      const files = fs.readdirSync(SHARD_DIR)
        .filter(f => f.startsWith(slug(lang)) && f.endsWith(".m3u"))
        .sort();
      for (const f of files) {
        no++;
        indexLines.push('#EXTINF:-1 group-title="🌐 ' + lang + '",' +
          String(no).padStart(5, "0") + " · " + lang + " — " + f.replace(/\.m3u$/, "").replace(/-/g, " "));
        indexLines.push("shards/" + f);
      }
    }
    fs.writeFileSync(MASTER_INDEX, indexLines.join("\n") + "\n");
    // The monolithic master is intentionally not written when sharding.
    if (fs.existsSync(MASTER)) fs.unlinkSync(MASTER);
  }
  console.log("master mode: " + mode);

  // ---- companion single-axis playlists (written to MASTER_OUT, default ./selected) ----
  for (const lang of LANG_ORDER) {
    writeM3U(path.join(COMPANION_OUT, "by-language", slug(lang) + ".m3u"),
      all.filter(t => t.language === lang).sort(sort),
      { playlist: "LibriVox — " + lang, groupOf: t => "🌐 " + lang + " · 🎭 " + t.genre, displayOf: t => t.display });
  }

  const genres = [...new Set(all.flatMap(t => t.genres))].sort();
  for (const genre of genres) {
    writeM3U(path.join(COMPANION_OUT, "by-genre", slug(genre) + ".m3u"),
      all.filter(t => t.genres.includes(genre))
         .sort((a, b) => langRank(a.language) - langRank(b.language) || sort(a, b)),
      { playlist: "LibriVox — " + genre, groupOf: t => "🎭 " + genre + " · 🌐 " + t.language, displayOf: t => t.display });
  }

  const authors = [...new Set(all.map(t => t.author))].sort((a, b) =>
    a.localeCompare(b, undefined, { sensitivity: "base", numeric: true }));
  for (const author of authors) {
    writeM3U(path.join(COMPANION_OUT, "by-author", slug(author) + ".m3u"),
      all.filter(t => t.author === author).sort((a, b) => langRank(a.language) - langRank(b.language) || sort(a, b)),
      { playlist: "LibriVox — " + author, groupOf: t => "🔤 " + author + " · 🌐 " + t.language, displayOf: t => t.display });
  }

  const titleGroups = new Map();
  for (const t of all) {
    if (!titleGroups.has(t.titlePath)) titleGroups.set(t.titlePath, []);
    titleGroups.get(t.titlePath).push(t);
  }
  for (const [relativePath, rows] of titleGroups) {
    writeM3U(path.join(COMPANION_OUT, "by-title", relativePath), rows.sort(sort),
      { playlist: "LibriVox — " + rows[0].title, groupOf: t => "📖 " + t.title, displayOf: t => t.chapter });
  }

  // ---- stats & README ----
  const sizeMiB = fs.existsSync(MASTER)
    ? (fs.statSync(MASTER).size / 1048576).toFixed(1)
    : ((() => { const s = fs.readdirSync(SHARD_DIR).reduce((n, f) => n + fs.statSync(path.join(SHARD_DIR, f)).size, 0);
        return (s / 1048576).toFixed(1) + " MiB across " + fs.readdirSync(SHARD_DIR).length + " shards (max file: " +
          (Math.max(...fs.readdirSync(SHARD_DIR).map(f => fs.statSync(path.join(SHARD_DIR, f)).size)) / 1048576).toFixed(1) + " MiB)"; })());
  const counts = LANG_ORDER.map(lang => {
    const rows = all.filter(t => t.language === lang);
    const books = new Set(rows.map(t => t.title)).size;
    return "| " + (LANG_EMOJI[lang] || "") + " " + lang + " | " + books + " | " + rows.length + " |";
  });
  const totalBooks = new Set(all.map(t => t.title)).size;
  const summary = [
    "# Master Playlist — Selected Languages",
    "",
    mode === "single-file"
      ? "`master.m3u` contains every audiobook track in **English, Bangla (Bengali), Hindi, Urdu, Arabic and Turkish**."
      : "The full library exceeds GitHub's 100 MiB single-file limit, so it is published as **shards/**: one playlist per language (split further only if a language alone exceeds the limit). Subscribe to `master-index.m3u` for everything, or to an individual shard (e.g. `shards/hindi.m3u`) for one language.",
    "Books in any other language are excluded. Multilingual compilation tracks are included only when their",
    "EXTINF label or audio filename identifies one of the six languages; unknown-language items are dropped.",
    "",
    "## Navigation design",
    "Each track carries `group-title=\"🌐 Language · 🎭 Genre · 🔤 Author-initial\"`, plus `#EXTGRP` markers at",
    "every group change, so IPTV players build a three-level browse tree: Language → Genre → A–Z.",
    "Titles use `Book — Author · Chapter`, so searching by book name or author works inside any group.",
    "",
    "## Track counts",
    "| Language | Books | Tracks |",
    "| --- | --- | --- |",
    ...counts,
    "| **Total** | **" + totalBooks + "** | **" + all.length + "** |",
    "",
    "- Master size: " + sizeMiB + (fs.existsSync(MASTER) ? " MiB" : ""),
    "- Mode: " + mode,
    "- Genre categories: " + genres.length,
    "- Author categories: " + authors.length,
    "- Per-book playlists: " + titleGroups.size,
    "",
    "## Companion playlists (same filtered set, single-axis navigation)",
    "- [by-language/](by-language/) — one file per language",
    "- [by-genre/](by-genre/) — one file per genre",
    "- [by-author/](by-author/) — one file per author",
    "- [by-title/](by-title/) — one file per book (chapter order preserved)",
    "",
    "Generated from the source M3U taxonomy by generate_master.js.",
    ""
  ].join("\n");
  fs.writeFileSync(path.join(COMPANION_OUT, "README.md"), summary, "utf8");

  console.log(JSON.stringify({
    masterSizeMiB: Number(String(sizeMiB).split(" ")[0]),
    masterMode: mode,
    uniqueAudioUrls: all.length,
    books: totalBooks,
    languageCounts: Object.fromEntries(LANG_ORDER.map(lang => [
      lang, all.filter(t => t.language === lang).length
    ])),
    titlePlaylists: titleGroups.size,
    genreCategories: genres.length,
    authorCategories: authors.length
  }, null, 2));
}

main();