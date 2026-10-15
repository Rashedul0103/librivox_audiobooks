const DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/filtered/nuvio/catalog.json";
const RAW_BASE = "https://raw.githubusercontent.com/Rashedul0103/librivox_audiobooks/main/filtered/by-title/";
const LANGUAGES = [
  { name: "English", id: "english" },
  { name: "Bangla", id: "bangla" },
  { name: "Hindi", id: "hindi" },
  { name: "Urdu", id: "urdu" },
  { name: "Arabic", id: "arabic" },
  { name: "Turkish", id: "turkish" }
];
const AUDIO_EXT = /\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wma|aiff)(?:[?#].*)?$/i;

function json(body, status, extraHeaders) {
  const headers = new Headers(extraHeaders || {});
  headers.set("Content-Type", "application/json; charset=utf-8");
  headers.set("Access-Control-Allow-Origin", "*");
  headers.set("Access-Control-Allow-Methods", "GET,HEAD,OPTIONS");
  headers.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
  headers.set("Cache-Control", "public, max-age=60");
  return new Response(JSON.stringify(body), { status: status || 200, headers: headers });
}

function cacheKey(origin, key) {
  return new Request(origin + "/__nuvio_cache/" + key);
}

async function readCachedText(url, origin, key, ttlSeconds) {
  const cache = typeof caches !== "undefined" && caches.default ? caches.default : null;
  const keyRequest = cache ? cacheKey(origin, key) : null;
  if (cache && keyRequest) {
    const hit = await cache.match(keyRequest);
    if (hit) return await hit.text();
  }
  const response = await fetch(url, { headers: { "Accept": "application/json,text/plain,*/*" } });
  if (!response.ok) throw new Error("Upstream returned HTTP " + response.status + " for " + url);
  const text = await response.text();
  if (cache && keyRequest) {
    const cachedResponse = new Response(text, {
      headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=" + (ttlSeconds || 3600) }
    });
    await cache.put(keyRequest, cachedResponse);
  }
  return text;
}

async function loadIndex(env, origin) {
  const raw = await readCachedText(env.CATALOG_URL || DEFAULT_CATALOG_URL, origin, "catalog-index-v1", 3600);
  const index = JSON.parse(raw);
  if (!index || !Array.isArray(index.books)) throw new Error("The generated Nuvio catalogue index is missing or invalid.");
  return index;
}

function clean(value) {
  return String(value || "").replace(/[\r\n\t]+/g, " ").replace(/\s+/g, " ").trim();
}

function parseExtra(segment) {
  const out = {};
  if (!segment) return out;
  let decoded = segment.replace(/\.json$/i, "");
  try { decoded = decodeURIComponent(decoded); } catch (_) {}
  for (const pair of new URLSearchParams(decoded)) out[pair[0]] = pair[1];
  return out;
}

function rawTitlePath(titlePath) {
  if (typeof titlePath !== "string" || !titlePath || titlePath.startsWith("/") ||
      titlePath.includes("\\") || titlePath.split("/").some(function (part) { return !part || part === "." || part === ".."; })) {
    throw new Error("Unsafe book playlist path in catalogue index.");
  }
  return RAW_BASE + titlePath.split("/").map(encodeURIComponent).join("/");
}

async function loadBookPlaylist(book, env, origin) {
  const url = rawTitlePath(book.titlePath);
  const cacheName = "playlist-" + book.id;
  return await readCachedText(url, origin, cacheName, 86400);
}

function parseM3U(content) {
  const lines = String(content || "").replace(/^\uFEFF/, "").split(/\r?\n/);
  const rows = [];
  let info = "";
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith("#EXTINF:")) {
      info = line;
      continue;
    }
    if (line.startsWith("#")) continue;
    if (/^https?:\/\//i.test(line) && AUDIO_EXT.test(line)) {
      const comma = info.indexOf(",");
      const durationText = info.startsWith("#EXTINF:")
        ? info.slice(8, comma >= 0 ? comma : undefined).match(/^-?\d+/)
        : null;
      const label = comma >= 0 ? clean(info.slice(comma + 1)) : line.split("/").pop();
      rows.push({
        url: line,
        title: label || "Chapter " + (rows.length + 1),
        duration: durationText ? Number(durationText[0]) : -1
      });
    }
    info = "";
  }
  return rows;
}

function languageFromChapter(label, url) {
  const aliases = {
    english: "English",
    bengali: "Bangla",
    bangla: "Bangla",
    hindi: "Hindi",
    urdu: "Urdu",
    arabic: "Arabic",
    turkish: "Turkish"
  };
  // In the generated M3U, multilingual language labels follow the display title after " · ".
  const parts = clean(label).split(" · ");
  const descriptor = parts[parts.length - 1].trim().toLowerCase();
  const match = descriptor.match(/^([a-z][a-z _-]*?)\s*[-–—:]/i);
  if (match) {
    const token = match[1].trim().replace(/[\s-]+/g, "_");
    if (aliases[token]) return aliases[token];
    // A label explicitly identifying a different language must not be assigned to the book's fallback language.
    if (token.length > 1) return null;
  }
  const filename = decodeURIComponent(String(url || "").split(/[?#]/)[0].split("/").pop() || "").toLowerCase();
  const prefix = filename.split("_")[0];
  if (aliases[prefix]) return aliases[prefix];
  return undefined;
}

function chaptersForBook(rows, book) {
  const multilingual = /multilingual|language[-_ ]learning/i.test((book.name || "") + " " + (book.titlePath || ""));
  return rows.filter(function (row) {
    const detected = languageFromChapter(row.title, row.url);
    if (detected) return detected === book.language;
    if (detected === null && multilingual) return false;
    return !multilingual;
  });
}

function displayChapter(title, book) {
  let value = clean(title);
  const split = value.split(" · ");
  if (split.length > 1) value = split[split.length - 1];
  value = value.replace(/^\s*(?:English|Bengali|Bangla|Hindi|Urdu|Arabic|Turkish)\s*[-–—:]\s*/i, "");
  return value || "Chapter";
}

function getBook(index, id) {
  return index.books.find(function (book) { return book.id === id; }) || null;
}

function displayGenres(book) {
  return Array.from(new Set([book.language].concat(book.genres || []))).filter(Boolean);
}

function catalogExtra() {
  return [
    { name: "search", isRequired: false },
    { name: "genre", isRequired: false, optionsLimit: 1 },
    { name: "skip", isRequired: false }
  ];
}

function manifest(index) {
  const genreOptions = Array.from(new Set((index.genres || []).concat(LANGUAGES.map(function (l) { return l.name; })))).sort();
  const catalogs = [{
    type: "series",
    id: "librivox_all",
    name: "LibriVox · All Audiobooks",
    extra: catalogExtra()
  }];
  for (const language of LANGUAGES) {
    catalogs.push({
      type: "series",
      id: "librivox_" + language.id,
      name: "LibriVox · " + language.name,
      extra: catalogExtra()
    });
  }
  for (const catalog of catalogs) {
    catalog.extra = catalog.extra.map(function (item) {
      return item.name === "genre" ? Object.assign({}, item, { options: genreOptions }) : item;
    });
  }
  return {
    id: "org.rashedul0103.librivox.audiobooks",
    version: "1.0.0",
    name: "LibriVox Audiobooks",
    description: "Public-domain audiobooks with catalogues by language and genre, title/author search, and chapter streams.",
    resources: [
      "catalog",
      { name: "meta", types: ["series"], idPrefixes: ["lv_"] },
      { name: "stream", types: ["series"], idPrefixes: ["lv_"] }
    ],
    types: ["series"],
    catalogs: catalogs,
    idPrefixes: ["lv_"]
  };
}

function languageForCatalog(id) {
  if (id === "librivox_all") return null;
  const language = LANGUAGES.find(function (item) { return id === "librivox_" + item.id; });
  return language ? language.name : undefined;
}

function bookMatches(book, search, genre) {
  if (genre) {
    const matchesGenre = (book.genres || []).some(function (item) { return item.toLowerCase() === genre.toLowerCase(); });
    if (!matchesGenre && String(book.language).toLowerCase() !== genre.toLowerCase()) return false;
  }
  if (search) {
    const searchable = [book.name, book.author, book.language].concat(book.genres || []).join(" ").toLowerCase();
    if (!searchable.includes(search.toLowerCase())) return false;
  }
  return true;
}

async function catalogResponse(id, extra, index) {
  const language = languageForCatalog(id);
  if (language === undefined) return { metas: [] };
  const search = clean(extra.search || "");
  const genre = clean(extra.genre || "");
  const skip = Math.max(0, Math.min(1000000, Number.parseInt(extra.skip || "0", 10) || 0));
  const metas = index.books
    .filter(function (book) { return (!language || book.language === language) && bookMatches(book, search, genre); })
    .sort(function (a, b) {
      return a.name.localeCompare(b.name, undefined, { sensitivity: "base", numeric: true }) ||
        String(a.author).localeCompare(String(b.author), undefined, { sensitivity: "base" });
    })
    .slice(skip, skip + 100)
    .map(function (book) {
      return {
        id: book.id,
        type: "series",
        name: book.name,
        description: [book.author || "Unknown author", book.language, (book.genres || []).join(", ")].filter(Boolean).join(" · "),
        genres: displayGenres(book)
      };
    });
  return { metas: metas };
}

async function metadataResponse(id, index, env, origin) {
  const book = getBook(index, id);
  if (!book) return { meta: null };
  const raw = await loadBookPlaylist(book, env, origin);
  const rows = chaptersForBook(parseM3U(raw), book);
  const videos = rows.map(function (row, i) {
    return {
      id: book.id + "__chapter_" + (i + 1),
      title: (String(i + 1).padStart(2, "0") + ". ") + displayChapter(row.title, book),
      season: 1,
      episode: i + 1,
      overview: row.duration > 0 ? "Duration: " + Math.floor(row.duration / 60) + " min" : undefined
    };
  });
  return {
    meta: {
      id: book.id,
      type: "series",
      name: book.name,
      description: [book.author || "Unknown author", book.language, (book.genres || []).join(", "), videos.length + " chapters"].filter(Boolean).join(" · "),
      genres: displayGenres(book),
      videos: videos
    }
  };
}

async function streamResponse(videoId, index, env, origin) {
  const marker = videoId.lastIndexOf("__chapter_");
  if (marker < 0) return { streams: [] };
  const bookId = videoId.slice(0, marker);
  const chapterNumber = Number.parseInt(videoId.slice(marker + "__chapter_".length), 10);
  if (!Number.isInteger(chapterNumber) || chapterNumber < 1) return { streams: [] };
  const book = getBook(index, bookId);
  if (!book) return { streams: [] };
  const raw = await loadBookPlaylist(book, env, origin);
  const rows = chaptersForBook(parseM3U(raw), book);
  const chapter = rows[chapterNumber - 1];
  if (!chapter) return { streams: [] };
  const title = displayChapter(chapter.title, book);
  const filename = decodeURIComponent(chapter.url.split(/[?#]/)[0].split("/").pop() || "");
  return {
    streams: [{
      name: "LibriVox",
      title: title,
      url: chapter.url,
      mimeType: /\.mp3(?:[?#]|$)/i.test(chapter.url) ? "audio/mpeg" : undefined,
      behaviorHints: { filename: filename, bingeGroup: book.id }
    }]
  };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET,HEAD,OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type,Authorization",
          "Access-Control-Max-Age": "86400"
        }
      });
    }
    if (request.method !== "GET" && request.method !== "HEAD") return json({ error: "Method not allowed" }, 405);
    if (url.pathname === "/health") return json({ ok: true, service: "LibriVox Nuvio addon" });
    if (url.pathname === "/") {
      return new Response("LibriVox Audiobooks addon. Install the /manifest.json URL in Nuvio Enhanced.", {
        headers: { "Content-Type": "text/plain; charset=utf-8", "Access-Control-Allow-Origin": "*" }
      });
    }
    try {
      const index = await loadIndex(env, url.origin);
      if (url.pathname === "/manifest.json") return json(manifest(index));
      const segments = url.pathname.split("/").filter(Boolean);
      const resource = segments[0] || "";
      const type = segments[1] || "";
      const rawId = segments[2] || "";
      const id = rawId.replace(/\.json$/i, "");
      const extra = parseExtra(segments[3] || "");
      url.searchParams.forEach(function (value, key) { extra[key] = value; });

      if (resource === "catalog" && type === "series") {
        return json(await catalogResponse(id, extra, index));
      }
      if (resource === "meta" && type === "series") {
        return json(await metadataResponse(id, index, env, url.origin));
      }
      if (resource === "stream" && type === "series") {
        return json(await streamResponse(id, index, env, url.origin));
      }
      return json({ error: "Not found" }, 404);
    } catch (error) {
      return json({ error: "Addon request failed", detail: String(error && error.message || error) }, 502);
    }
  }
};
