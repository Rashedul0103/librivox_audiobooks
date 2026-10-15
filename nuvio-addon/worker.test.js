import test from "node:test";
import assert from "node:assert/strict";
import worker from "./worker.js";

const titlePath = "a-1/example-book.m3u";
const encodedPath = Buffer.from(titlePath, "utf8").toString("base64url");
const bookId = "lv_english_" + encodedPath;
const testIndex = {
  generatedAt: "test",
  languages: ["English", "Bangla", "Hindi", "Urdu", "Arabic", "Turkish"],
  genres: ["Adventure", "Poetry"],
  books: [
    { id: bookId, name: "Example Book", titlePath: titlePath, author: "Jane Example", language: "English", genres: ["Adventure"] },
    { id: "lv_bangla_" + Buffer.from("b-1/bangla-book.m3u").toString("base64url"), name: "Bangla Book", titlePath: "b-1/bangla-book.m3u", author: "Example Author", language: "Bangla", genres: ["Poetry"] }
  ]
};
const sampleM3U = [
  "#EXTM3U",
  "#PLAYLIST:Test",
  '#EXTINF:120 group-title="Adventure",Example Book - Chapter 1 - Read by Reader - Duration 00:02:00',
  "https://archive.org/download/example_book/chapter_01.mp3",
  '#EXTINF:90 group-title="Adventure",Example Book - Chapter 2 - Read by Reader - Duration 00:01:30',
  "https://archive.org/download/example_book/chapter_02.mp3",
  ""
].join("\n");

function installMocks() {
  const oldFetch = globalThis.fetch;
  const oldCaches = globalThis.caches;
  globalThis.caches = { default: { match: async () => undefined, put: async () => undefined } };
  globalThis.fetch = async function (input) {
    const address = String(input);
    if (address.includes("catalog.json")) {
      return new Response(JSON.stringify(testIndex), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    if (address.includes("example-book.m3u")) {
      return new Response(sampleM3U, { status: 200 });
    }
    return new Response("Not found", { status: 404 });
  };
  return function restore() {
    globalThis.fetch = oldFetch;
    if (oldCaches === undefined) delete globalThis.caches;
    else globalThis.caches = oldCaches;
  };
}

test("manifest exposes language catalogues and genre options", async () => {
  const restore = installMocks();
  try {
    const response = await worker.fetch(new Request("https://addon.test/manifest.json"), {});
    assert.equal(response.status, 200);
    const manifest = await response.json();
    assert.equal(manifest.id, "org.rashedul0103.librivox.audiobooks");
    assert.ok(manifest.catalogs.some((item) => item.id === "librivox_bangla"));
    assert.ok(manifest.catalogs[0].extra.some((item) => item.name === "genre" && item.options.includes("Adventure")));
    assert.equal(response.headers.get("Access-Control-Allow-Origin"), "*");
  } finally { restore(); }
});

test("catalogue search matches book title and author", async () => {
  const restore = installMocks();
  try {
    const response = await worker.fetch(new Request("https://addon.test/catalog/series/librivox_all.json?search=Jane%20Example"), {});
    const body = await response.json();
    assert.equal(body.metas.length, 1);
    assert.equal(body.metas[0].name, "Example Book");
  } finally { restore(); }
});

test("book metadata exposes chapters and stream endpoint returns direct audio URL", async () => {
  const restore = installMocks();
  try {
    const metaResponse = await worker.fetch(new Request("https://addon.test/meta/series/" + bookId + ".json"), {});
    const metaBody = await metaResponse.json();
    assert.equal(metaBody.meta.name, "Example Book");
    assert.equal(metaBody.meta.videos.length, 2);

    const streamId = bookId + "__chapter_2";
    const streamResponse = await worker.fetch(new Request("https://addon.test/stream/series/" + streamId + ".json"), {});
    const streamBody = await streamResponse.json();
    assert.equal(streamBody.streams.length, 1);
    assert.equal(streamBody.streams[0].url, "https://archive.org/download/example_book/chapter_02.mp3");
    assert.equal(streamBody.streams[0].mimeType, "audio/mpeg");
  } finally { restore(); }
});
