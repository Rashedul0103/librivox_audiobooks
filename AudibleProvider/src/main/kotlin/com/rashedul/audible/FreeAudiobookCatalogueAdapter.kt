package com.rashedul.audible

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Catalogue-only adapter for Golden Audiobooks and Full Length Audiobooks.
 *
 * It reads article metadata and exposes the original article page. It deliberately
 * does not resolve embedded players, third-party MP3 URLs, or download mirrors.
 */
internal class FreeAudiobookCatalogueAdapter {
    data class Entry(
        val sourceId: String,
        val sourceName: String,
        val title: String,
        val author: String,
        val url: String,
        val poster: String?,
        val description: String,
        val published: String
    ) {
        val displayTitle: String
            get() = if (author.isBlank()) title else "$title — $author"

        val summaryText: String
            get() = buildList {
                if (author.isNotBlank()) add("Author: $author")
                if (published.isNotBlank()) add("Published: $published")
                add("Catalogue: $sourceName")
                add("Source page: $url")
                if (description.isNotBlank()) add(description)
            }.joinToString("\n\n")
    }

    private data class Source(
        val id: String,
        val name: String,
        val root: String,
        val host: String
    )

    private val sources = listOf(
        Source("golden", "Golden Audiobooks", "https://goldenaudiobooks.com", "goldenaudiobooks.com"),
        Source("full-length", "Full Length Audiobooks", "https://fulllengthaudiobooks.com", "fulllengthaudiobooks.com")
    )
    private val requestSemaphore = Semaphore(permits = 3)
    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    suspend fun browse(sourceId: String, page: Int): List<Entry> {
        val source = sources.firstOrNull { it.id == sourceId } ?: return emptyList()
        val safePage = page.coerceAtLeast(1)
        val url = if (safePage == 1) {
            "${source.root}/"
        } else {
            "${source.root}/page/$safePage/"
        }
        return loadEntries(url, source).take(24)
    }

    suspend fun search(query: String, page: Int): List<Entry> {
        if (query.isBlank() || page !in 1..20) return emptyList()
        return coroutineScope {
            sources.map { source ->
                async {
                    val encoded = query.encodeUri()
                    val pageParam = if (page <= 1) "" else "&paged=$page"
                    loadEntries("${source.root}/?s=$encoded$pageParam", source)
                }
            }.awaitAll().flatten()
                .distinctBy { it.url }
                .take(40)
        }
    }

    fun isSourceUrl(rawUrl: String): Boolean =
        sourceAndUrl(rawUrl) != null

    suspend fun inspect(rawUrl: String): Entry? {
        val pair = sourceAndUrl(rawUrl) ?: return null
        val (source, safeUrl) = pair
        return try {
            parseDetail(fetchDocument(safeUrl), source, safeUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private suspend fun loadEntries(url: String, source: Source): List<Entry> {
        return try {
            parseIndex(fetchDocument(url), source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    private suspend fun fetchDocument(url: String): Document =
        requestSemaphore.withPermit { app.get(url, headers = requestHeaders).document }

    private fun parseIndex(doc: Document, source: Source): List<Entry> {
        val anchors = doc.select(
            "article a[href], .entry-title a[href], .post-title a[href], h1 a[href], h2 a[href], h3 a[href]"
        )
        val found = linkedMapOf<String, Entry>()
        anchors.forEach { anchor ->
            val safeUrl = sourceAndUrl(anchor.attr("abs:href"))?.second ?: return@forEach
            if (found.containsKey(safeUrl)) return@forEach

            val ancestors = anchor.parents()
            val card = ancestors.firstOrNull {
                it.tagName() == "article" ||
                    it.selectFirst("h1, h2, h3, h4, .entry-title, .post-title") != null
            } ?: anchor.parent() ?: anchor
            val heading = anchor.selectFirst("h1, h2, h3, h4, .entry-title, .post-title")
                ?.text()?.trim().orEmpty()
                .ifBlank {
                    card.selectFirst("h1, h2, h3, h4, .entry-title, .post-title")
                        ?.text()?.trim().orEmpty()
                }
                .ifBlank { anchor.text().trim() }
                .ifBlank { anchor.attr("title").trim() }
            if (heading.isBlank()) return@forEach

            val image = imageUrl(card.selectFirst("img"), source)
            val excerpt = card.selectFirst(
                ".entry-summary, .post-excerpt, .excerpt, .entry-content p, .post-content p"
            )?.text()?.trim().orEmpty()
            val published = card.selectFirst("time[datetime], .posted-on, .post-date")
                ?.let { it.attr("datetime").ifBlank { it.text() }.trim() }.orEmpty()
            val entry = makeEntry(
                source = source,
                rawTitle = heading,
                url = safeUrl,
                poster = image,
                description = excerpt,
                published = published
            )
            found.putIfAbsent(entry.url, entry)
        }
        return found.values.toList()
    }

    private fun parseDetail(doc: Document, source: Source, safeUrl: String): Entry? {
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("abs:href")
            ?.let { sourceAndUrl(it)?.second }
            ?: safeUrl
        val rawTitle = doc.selectFirst("h1")?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .ifBlank { doc.title() }
        if (rawTitle.isBlank()) return null

        val image = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.let { normalizeImageUrl(it, source) }
            ?: imageUrl(doc.selectFirst("article img, .entry-content img, .post-content img"), source)
        val description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            .ifBlank {
                doc.selectFirst(".entry-content p, .post-content p, article p")?.text().orEmpty()
            }
        val published = doc.selectFirst("time[datetime], .posted-on, .post-date")
            ?.let { it.attr("datetime").ifBlank { it.text() }.trim() }.orEmpty()
        val entry = makeEntry(source, rawTitle, canonical, image, description, published)
        val explicitAuthor = doc.selectFirst(
            "[rel=author], .author a, .byline a, .entry-author, .author-name"
        )?.text()?.trim().orEmpty()
        return if (explicitAuthor.isBlank() || entry.author.isNotBlank()) entry
        else entry.copy(author = explicitAuthor)
    }

    private fun makeEntry(
        source: Source,
        rawTitle: String,
        url: String,
        poster: String?,
        description: String,
        published: String
    ): Entry {
        val cleaned = cleanTitle(rawTitle)
        val parts = cleaned.split(Regex("\\s+[–—-]\\s+"), limit = 2)
        val hasAuthorPrefix = parts.size == 2 &&
            parts[0].length in 2..60 &&
            parts[1].isNotBlank() &&
            !parts[0].contains("audiobook", ignoreCase = true)
        val author = if (hasAuthorPrefix) parts[0].trim() else ""
        val title = (if (hasAuthorPrefix) parts[1] else cleaned).trim().ifBlank { cleaned }
        return Entry(
            sourceId = source.id,
            sourceName = source.name,
            title = title,
            author = author,
            url = url,
            poster = poster,
            description = description.trim().take(1800),
            published = published
        )
    }

    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(Regex("""(?i)\s*[-–—|:]\s*(?:free\s+)?(?:full\s+length\s+)?audiobooks?(?:\s+(?:free|online|download|streaming))?\s*$"""), "")
        .replace(Regex("""(?i)\s+audiobooks?(?:\s+(?:free|online|download|streaming))?\s*$"""), "")
        .replace(Regex("""(?i)\s*\|\s*(?:golden\s*audiobooks|full\s*length\s*audiobooks?)\s*$"""), "")
        .replace(Regex("""(?i)\s*[-–—|:]\s*(?:free|download and keep forever)\s*$"""), "")
        .trim()

    private fun imageUrl(img: Element?, source: Source): String? {
        if (img == null) return null
        val raw = listOf("data-src", "data-lazy-src", "src", "data-original")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("srcset").substringBefore(",").substringBefore(" ").trim()
        return normalizeImageUrl(raw, source)
    }

    private fun normalizeImageUrl(raw: String, source: Source): String? {
        if (raw.isBlank()) return null
        return runCatching {
            val adjusted = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
                raw.startsWith("https://") -> raw
                else -> URI("${source.root}/").resolve(raw).toString()
            }
            val uri = URI(adjusted)
            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) uri.toString() else null
        }.getOrNull()
    }

    private fun sourceAndUrl(rawUrl: String): Pair<Source, String>? = runCatching {
        val uri = URI(rawUrl)
        if (uri.scheme != "https") return@runCatching null
        val host = uri.host?.lowercase().orEmpty()
        val source = sources.firstOrNull { host == it.host || host == "www.${it.host}" }
            ?: return@runCatching null
        val path = uri.path.orEmpty()
        if (path.isBlank() || path == "/" ||
            path.startsWith("/category/") || path.startsWith("/tag/") ||
            path.startsWith("/author/") || path.startsWith("/page/") ||
            path.startsWith("/wp-") || path.startsWith("/feed/") ||
            path.startsWith("/search/") ||
            path.substringAfterLast('/', "").contains('.') &&
                path.substringAfterLast('/').matches(Regex("(?i).+\\.(?:mp3|m4a|aac|ogg|opus|wav|flac|mp4|jpg|jpeg|png|webp|gif|css|js|xml)$"))
        ) return@runCatching null
        source to uri.toString().substringBefore("#")
    }.getOrNull()
}
