#!/usr/bin/env node
// Build a language-restricted LibriVox catalogue without changing playlist.m3u.
const fs = require("fs");
const path = require("path");

const ROOT = process.cwd();
const OUT = path.join(ROOT, "filtered");
const ALLOWED = new Map([
  ["English", "English"],
  ["Bengali", "Bangla"],
  ["Hindi", "Hindi"],
  ["Urdu", "Urdu"],
  ["Arabic", "Arabic"],
  ["Turkish", "Turkish"]
]);
const ALLOWED_VALUES = new Set(ALLOWED.values());
const AUDIO = /\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|aiff)(?:[?#].*)?$/i;
const IGNORE = new Set([".git", ".github", "node_modules", "playlists", "filtered"]);
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

function walk(dir, rel = "") {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name.startsWith(".") || IGNORE.has(e.name)) continue;
    const abs = path.join(dir, e.name);
    const r = rel ? rel + "/" + e.name : e.name;
    if (e.isDirectory()) out.push(...walk(abs, r));
    else if (
      e.isFile() &&
      e.name.toLowerCase().endsWith(".m3u") &&
      !["playlist.m3u", "master.m3u"].includes(e.name.toLowerCase())
    ) out.push({ abs, rel: r });
  }
  return out;
}

const clean = s => String(s || "").replace(/[\r\n\t]+/g, " ").replace(/\s+/g, " ").trim();
const slug = s => String(s || "").normalize("NFKD")
  .replace(/[\u0300-\u036f]/g, "")
  .toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
const pretty = s => clean(String(s || "").replace(/[_-]+/g, " "))
  .replace(/\b\w/g, c => c.toUpperCase()) || "Unknown";

function metadata(rel) {
  const p = rel.split("/");
  const tax = p[0];
  const stem = path.basename(rel, path.extname(rel));
  const title = pretty(stem.replace(/-by-.+$/i, ""));
  const suffix = stem.match(/-by-(.+)$/i);
  const titleAuthor = suffix ? pretty(suffix[1].replace(/.*-by-/i, "")) : "";
  const result = {
    tax, language: "", genre: "", author: "", title, titlePath: "", titleAuthor,
    multilingual: /multilingual|language[-_]learning/i.test(stem)
  };

  if (tax === "by-language" && p.length >= 3 && ALLOWED.has(p[1])) {
    result.language = ALLOWED.get(p[1]);
  }
  if (tax === "by-genre" && p.length >= 3) result.genre = pretty(p[1]);
  if (tax === "by-author" && p.length >= 4) {
    result.author = pretty(p[2].replace(/_\d+$/, ""));
  }
  if (tax === "by-title" && p.length >= 3) {
    // Preserve source title-folder grouping (for example, a-1/...) in the output.
    result.titlePath = p.slice(1).join("/");
  }
  return result;
}

function parse(file) {
  const lines = fs.readFileSync(file.abs, "utf8").replace(/^\uFEFF/, "").split(/\r?\n/);
  const out = [];
  let info = "";
  for (const raw of lines) {
    const s = raw.trim();
    if (!s) continue;
    if (s.startsWith("#EXTINF:")) {
      info = s;
      continue;
    }
    if (s.startsWith("#")) continue;
    if (/^https?:\/\//i.test(s) && AUDIO.test(s)) {
      const comma = info.indexOf(",");
      const duration = info.startsWith("#EXTINF:")
        ? (info.slice(8, comma < 0 ? undefined : comma).match(/^-?\d+/) || ["-1"])[0]
        : "-1";
      const label = comma >= 0 ? clean(info.slice(comma + 1)) : path.basename(s);
      out.push({ url: s, duration, label });
    }
    info = "";
  }
  return out;
}

const KNOWN_LANGUAGE_PREFIXES = [...new Set([
  ...Object.keys(PREFIX_LANGUAGE),
  ...FOREIGN_PREFIXES
])].sort((a, b) => b.length - a.length);

function urlLanguage(url, folderLanguage, isMultilingual) {
  const file = decodeURIComponent(url.split(/[?#]/)[0].split("/").pop() || "").toLowerCase();
  // Match a known language token followed by an underscore. Longest-first preserves
  // multiword tokens such as old_english while avoiding a greedy read into the title.
  const token = KNOWN_LANGUAGE_PREFIXES.find(prefix => file.startsWith(prefix + "_"));
  if (token) {
    if (PREFIX_LANGUAGE[token]) return PREFIX_LANGUAGE[token];
    return null;
  }
  // Multilingual collections must have a recognizable selected-language prefix.
  // Unknown or missing prefixes must not inherit the collection's folder language.
  if (isMultilingual) return null;
  return folderLanguage || null;
}

function safeAttr(s) {
  return clean(s).replace(/"/g, "'").replace(/,/g, " ");
}

function writeM3U(file, rows, category) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const lines = ["#EXTM3U", "#PLAYLIST:LibriVox — selected languages"];
  for (const t of rows) {
    lines.push(
      '#EXTINF:' + t.duration + ' group-title="' + safeAttr(category || t.genre || "Other") +
      '",' + clean(t.display || t.label),
      t.url
    );
  }
  fs.writeFileSync(file, lines.join("\n") + "\n", "utf8");
}

function main() {
  const files = walk(ROOT).sort((a, b) => a.rel.localeCompare(b.rel));
  const tracks = new Map();

  for (const f of files) {
    const meta = metadata(f.rel);
    for (const row of parse(f)) {
      let t = tracks.get(row.url);
      if (!t) {
        t = {
          ...row,
          language: "",
          genreSet: new Set(),
          author: "",
          title: "",
          titlePath: "",
          fallbackTitle: meta.title
        };
        tracks.set(row.url, t);
      }

      if (meta.language) {
        const language = urlLanguage(row.url, meta.language, meta.multilingual);
        if (language && ALLOWED_VALUES.has(language)) t.language = language;
      }
      if (meta.genre) t.genreSet.add(meta.genre);
      if (meta.author && !t.author) t.author = meta.author;
      // The title taxonomy is authoritative for display title and per-book playlist path.
      if (meta.titlePath) {
        t.titlePath = meta.titlePath;
        t.title = meta.title;
        if (meta.titleAuthor) t.author = meta.titleAuthor;
      } else if (!t.title && meta.title) {
        t.title = meta.title;
      }
    }
  }

  const all = [...tracks.values()].filter(t => ALLOWED_VALUES.has(t.language));
  if (!all.length) throw new Error("No matching tracks; refusing to write empty playlists.");

  for (const t of all) {
    t.genres = t.genreSet.size ? [...t.genreSet].sort() : ["Other - Unclassified"];
    t.genre = t.genres[0];
    t.title = t.title || t.fallbackTitle || "Unknown title";
    // Source taxonomy sometimes files collection titles under "by-author".
    if (/^(multilingual|librivox language learning|librivox multilingual|poetry multi-version|short works collection|short story collection)\b/i.test(t.author)) {
      t.author = "Various";
    }
    let descriptor = clean(t.label);
    if (descriptor.toLowerCase().startsWith(t.title.toLowerCase())) {
      descriptor = descriptor.slice(t.title.length).replace(/^\s*[-–—:]\s*/, "");
    }
    t.display = (t.title || t.label) + (t.author ? " — " + t.author : "") + (descriptor ? " · " + descriptor : "");
    if (!t.titlePath) {
      t.titlePath = "uncategorized/" + (slug(t.title) || slug(t.label) || "unknown") + ".m3u";
    }
  }

  const sort = (a, b) =>
    (a.title || "").localeCompare(b.title || "", undefined, { sensitivity: "base", numeric: true }) ||
    a.url.localeCompare(b.url);

  all.sort((a, b) => a.language.localeCompare(b.language) || sort(a, b));
  writeM3U(path.join(OUT, "playlist.m3u"), all, "Selected languages");

  for (const lang of ALLOWED.values()) {
    writeM3U(path.join(OUT, "by-language", slug(lang) + ".m3u"),
      all.filter(t => t.language === lang).sort(sort), lang);
  }

  const genres = [...new Set(all.flatMap(t => t.genres))].sort();
  for (const genre of genres) {
    writeM3U(path.join(OUT, "by-genre", slug(genre) + ".m3u"),
      all.filter(t => t.genres.includes(genre)).sort(sort), genre);
  }

  const authors = [...new Set(all.map(t => t.author || "Unknown author"))].sort();
  for (const author of authors) {
    writeM3U(path.join(OUT, "by-author", slug(author) + ".m3u"),
      all.filter(t => (t.author || "Unknown author") === author).sort(sort), author);
  }

  const titleGroups = new Map();
  for (const t of all) {
    if (!titleGroups.has(t.titlePath)) titleGroups.set(t.titlePath, []);
    titleGroups.get(t.titlePath).push(t);
  }
  for (const [relativePath, rows] of titleGroups) {
    writeM3U(path.join(OUT, "by-title", relativePath), rows.sort(sort), rows[0].title);
  }

  const counts = [...ALLOWED.values()].map(lang =>
    "- " + lang + ": " + all.filter(t => t.language === lang).length + " tracks");
  const summary = [
    "# LibriVox — selected-language playlists",
    "",
    "This generated library includes only English, Bangla (Bengali), Hindi, Urdu, Arabic, and Turkish.",
    "The source playlist.m3u is not modified. Audio URLs are linked, not copied.",
    "",
    "## Playlists",
    "- [Filtered master](playlist.m3u)",
    "- [By language](by-language/)",
    "- [By genre](by-genre/)",
    "- [By author](by-author/)",
    "- [By individual title](by-title/)",
    "",
    "## Track counts",
    ...counts,
    "- Unique audio URLs: " + all.length,
    "- Per-title playlists: " + titleGroups.size,
    "- Genre categories: " + genres.length,
    "- Author categories: " + authors.length,
    "",
    "Multilingual collection tracks are included only when their audio filenames have a recognizable prefix for one of the six requested languages. Tracks with an unknown or missing language marker are excluded from such mixed collections rather than being mislabelled.",
    "",
    "Generated from source M3U taxonomy files by generate_filtered_playlists.js.",
    ""
  ].join("\n");
  fs.writeFileSync(path.join(OUT, "README.md"), summary, "utf8");

  console.log(JSON.stringify({
    uniqueAudioUrls: all.length,
    languageCounts: Object.fromEntries([...ALLOWED.values()].map(lang => [
      lang, all.filter(t => t.language === lang).length
    ])),
    titlePlaylists: titleGroups.size,
    genreCategories: genres.length,
    authorCategories: authors.length
  }, null, 2));
}

main();
