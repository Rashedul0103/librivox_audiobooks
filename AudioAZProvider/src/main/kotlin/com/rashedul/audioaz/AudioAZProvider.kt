package com.rashedul.audioaz

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AudioAZProvider : MainAPI() {
    private val siteRoot = "https://audioaz.com"
    private val siteBase = "$siteRoot/en"

    // AudioAZ serves a Next.js website. Use ordinary browser headers and try
    // its current catalogue routes before older browse routes.
    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$siteBase"
    )

    private suspend fun fetchDocument(url: String): Document =
        app.get(url, headers = requestHeaders).document

    private suspend fun fetchBookCards(urls: List<String>): List<BookCard> {
        for (url in urls.distinct()) {
            val books = runCatching { parseBookCards(fetchDocument(url)) }
                .onFailure { logError(it) }
                .getOrDefault(emptyList())
            if (books.isNotEmpty()) return books
        }
        return emptyList()
    }

    private data class GenreShelf(val title: String, val slug: String)

    // Use real AudioAZ genre slugs so each shelf opens the matching catalogue.
    private val genreShelves = listOf(
        GenreShelf("History", "history"),
        GenreShelf("Science Fiction", "science-fiction"),
        GenreShelf("Horror & Supernatural", "horror-and-supernatural-fiction"),
        GenreShelf("Mystery & Detective", "crime-and-mystery-fiction"),
        GenreShelf("Action & Adventure", "action-and-adventure-fiction"),
        GenreShelf("Romance", "romance"),
        GenreShelf("Children's Fiction", "children-s-fiction"),
        GenreShelf("Poetry", "poetry"),
        GenreShelf("Biography & Autobiography", "biography-and-autobiography"),
        GenreShelf("Philosophy", "philosophy"),
        GenreShelf("Religion", "religion"),
        GenreShelf("Short Stories", "short-stories"),
        GenreShelf("Self-Help", "self-help")
    )

    private suspend fun fetchHomeRow(title: String, urls: List<String>): HomePageList? {
        val books = fetchBookCards(urls)
            .map { it.toSearchResponse(this@AudioAZProvider) }
        // false means vertical poster cards, not landscape thumbnails.
        return books.takeIf { it.isNotEmpty() }?.let { HomePageList(title, it, false) }
    }

    override var mainUrl = siteBase
    override var name = "AudioAZ"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Audio, TvType.Others)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val pageSuffix = if (page > 1) "&page=$page" else ""
            val pageQuery = if (page > 1) "?page=$page" else ""
            val rowQueries = mutableListOf<Pair<String, List<String>>>()

            rowQueries += "Most Popular" to listOf(
                "$siteBase/browse?language=English&sort=popular$pageSuffix",
                "$siteBase/browse$pageQuery"
            )
            rowQueries += "Most Recent" to listOf(
                "$siteBase/browse?language=English&sort=recent$pageSuffix",
                "$siteBase/browse?sort=recent" + if (page > 1) "&page=$page" else ""
            )

            genreShelves.forEach { shelf ->
                rowQueries += shelf.title to listOf(
                    "$siteBase/genre/${shelf.slug}?language=English&sort=popular$pageSuffix",
                    "$siteBase/genre/${shelf.slug}$pageQuery"
                )
            }

            // Load shelves concurrently so adding categories does not make the
            // entire home screen wait for every request in sequence.
            val rows = coroutineScope {
                rowQueries.map { (title, urls) ->
                    async { fetchHomeRow(title, urls) }
                }.awaitAll().filterNotNull()
            }

            newHomePageResponse(rows, page < 100 && rows.isNotEmpty())
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            val encoded = query.encodeUri()
            val pageSuffix = if (page > 1) "&page=$page" else ""
            val candidateUrls = listOf(
                "$siteBase/search?q=$encoded$pageSuffix",
                "$siteRoot/en/audiobooks?q=$encoded$pageSuffix",
                "$siteRoot/en/audiobooks?search=$encoded$pageSuffix",
                "$siteBase/browse?search=$encoded$pageSuffix",
                "$siteBase/browse?q=$encoded$pageSuffix"
            )
            var results: List<BookCard> = emptyList()
            for (candidate in candidateUrls) {
                results = runCatching { parseBookCards(fetchDocument(candidate)) }
                    .onFailure { logError(it) }
                    .getOrDefault(emptyList())
                if (results.isNotEmpty()) break
            }
            results.map { it.toSearchResponse(this@AudioAZProvider) }.toNewSearchResponseList()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val safeUrl = validateAudioAZUrl(url)
        return try {
            val doc = fetchDocument(safeUrl)
            val title = pageTitle(doc).ifBlank { "AudioAZ audiobook" }
            val author = firstLinkedText(doc, "a[href*='/en/authors/'], a[href*='/en/author/']")
            val genres = doc.select("a[href*='/en/genres/'], a[href*='/en/genre/']")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            val narrators = doc.select("a[href*='/en/narrators/'], a[href*='/en/narrator/']")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            val language = firstLabelValue(doc, "Language")
            val length = firstLabelValue(doc, "Length")
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("https://") }
                ?: doc.selectFirst("img[src]")?.let { imageUrl(it) }

            newMovieLoadResponse(title, safeUrl, TvType.Audio, safeUrl) {
                plot = buildString {
                    if (author.isNotBlank()) append("Author: ").append(author).append("\n\n")
                    if (language.isNotBlank()) append("Language: ").append(language).append("\n\n")
                    if (narrators.isNotEmpty()) append("Narrators: ").append(narrators.joinToString(", ")).append("\n\n")
                    if (genres.isNotEmpty()) append("Genres: ").append(genres.joinToString(", ")).append("\n\n")
                    val summary = findDescription(doc)
                    if (summary.isNotBlank()) append(summary).append("\n\n")
                    if (length.isNotBlank()) append("Length: ").append(length).append("\n\n")
                    append("Source page: ").append(safeUrl)
                }
                posterUrl = poster
                tags = genres
                duration = parseDurationMinutes(length)
            }
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Unable to load this AudioAZ page. Try opening it on the AudioAZ website.")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val safeUrl = runCatching { validateAudioAZUrl(data) }.getOrNull() ?: return false
        return try {
            val doc = fetchDocument(safeUrl)
            val pageConfirmsPublicDomain = doc.text().contains("public domain", ignoreCase = true)
            var found = false

            val directAudioUrls = doc.select(
                "audio[src], audio source[src], source[src], meta[property=og:audio]"
            ).mapNotNull { element ->
                val raw = if (element.tagName().equals("meta", true)) {
                    element.attr("content")
                } else element.attr("src")
                val absolute = if (raw.startsWith("https://")) raw else element.absUrl("src")
                absolute.takeIf { isAllowedPublicAudioUrl(it, pageConfirmsPublicDomain) }
            }.distinct()

            directAudioUrls.forEach { audioUrl ->
                callback(newExtractorLink(name, "Public-domain audio", audioUrl) {
                    quality = Qualities.Unknown.value
                    referer = safeUrl
                })
                found = true
            }

            // Require an explicit source label so narrator-profile links are ignored.
            val sourcePages = doc.select("a[href*='librivox.org'], a[href*='archive.org/details/']")
                .filter { link ->
                    val label = link.text().lowercase()
                    (label.contains("librivox") ||
                        label.contains("internet archive") ||
                        label.contains("archive.org")) &&
                        isAllowedSourcePage(link.absUrl("href"))
                }
                .map { it.absUrl("href") }
                .distinct()
                .take(2)

            sourcePages.forEach { sourceUrl ->
                loadExtractor(sourceUrl, subtitleCallback) { link ->
                    if (isAllowedPublicAudioUrl(link.url, pageConfirmsPublicDomain)) {
                        callback(link)
                        found = true
                    }
                }
            }
            found
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    private data class BookCard(
        val title: String,
        val url: String,
        val poster: String?
    ) {
        fun toSearchResponse(provider: AudioAZProvider): SearchResponse =
            provider.newMovieSearchResponse(title, url, TvType.Audio) {
                posterUrl = poster
            }
    }

    private fun parseBookCards(doc: Document): List<BookCard> {
        val cards = linkedMapOf<String, BookCard>()
        doc.select("a[href*='/en/audiobook/']").forEach { anchor ->
            val rawHref = anchor.attr("href").trim()
            val href = (
                anchor.absUrl("href").ifBlank {
                    runCatching { URI(siteRoot).resolve(rawHref).toString() }.getOrDefault("")
                }
            ).substringBefore('#').substringBefore('?').trimEnd('/')
            val uri = runCatching { URI(href) }.getOrNull() ?: return@forEach
            val host = uri.host?.lowercase().orEmpty()
            if ((host != "audioaz.com" && !host.endsWith(".audioaz.com")) ||
                !uri.path.orEmpty().contains("/en/audiobook/")
            ) return@forEach

            val card = anchor.parents().firstOrNull {
                it.selectFirst("h1, h2, h3, h4, h5") != null ||
                    (it.selectFirst("img") != null && it.text().trim().length in 5..700)
            } ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val heading = anchor.selectFirst("h1, h2, h3, h4, h5")
                ?: card.selectFirst("h1, h2, h3, h4, h5")
            val titleCandidates = listOf(
                heading?.text().orEmpty(),
                anchor.attr("aria-label"),
                anchor.attr("title"),
                anchor.text(),
                image?.attr("alt").orEmpty(),
                card.text()
            )
            val title = titleCandidates.asSequence()
                .map(::cleanBookTitle)
                .firstOrNull {
                    it.isNotBlank() &&
                        !it.equals("audiobook", true) &&
                        !it.equals("play", true) &&
                        !it.equals("read more", true) &&
                        it.length <= 180
                }.orEmpty()
            if (title.isBlank()) return@forEach
            cards.putIfAbsent(href, BookCard(title, href, image?.let(::imageUrl)))
        }
        return cards.values.toList()
    }

    private fun pageTitle(doc: Document): String {
        val h1 = doc.selectFirst("h1")?.text().orEmpty()
        val openGraph = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return cleanBookTitle(h1.ifBlank { openGraph.ifBlank { doc.title() } })
    }

    private fun cleanBookTitle(value: String): String = value.trim()
        .replace(Regex("(?i)^play\\s+"), "")
        .replace(Regex("(?i)^audiobook\\s+"), "")
        .replace(Regex("(?i)\\s*[-–—]\\s*Free Audiobook\\s*$"), "")
        .replace(Regex("(?i)\\s+audiobook\\s*$"), "")
        .trim()

    private fun imageUrl(img: Element): String {
        return listOf("src", "data-src", "data-lazy-src", "srcset")
            .asSequence()
            .map { img.attr(it).substringBefore(',').substringBefore(' ').trim() }
            .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            ?: img.absUrl("src")
    }

    private fun firstLinkedText(doc: Document, selector: String): String =
        doc.select(selector).firstOrNull()?.text()?.trim().orEmpty()

    private fun firstLabelValue(doc: Document, label: String): String {
        return doc.select("p, li, div, span, dt, dd")
            .map { it.text().trim() }
            .filter { it.startsWith("$label:", true) && it.length < 180 }
            .minByOrNull { it.length }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
    }

    private fun findDescription(doc: Document): String {
        val heading = doc.select("h2, h3").firstOrNull {
            it.text().equals("About", true) || it.text().equals("Summary", true)
        }
        val text = heading?.nextElementSibling()?.text()?.trim().orEmpty()
        return text.ifBlank {
            doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        }
    }

    private fun parseDurationMinutes(raw: String): Int? {
        val token = Regex("(?<!\\d)(\\d{1,3}:\\d{2}(?::\\d{2})?)(?!\\d)")
            .find(raw)?.groupValues?.get(1) ?: return null
        val parts = token.split(":").mapNotNull { it.toIntOrNull() }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return (seconds / 60.0).toInt().takeIf { it > 0 }
    }

    private fun validateAudioAZUrl(raw: String): String {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" ||
            (host != "audioaz.com" && !host.endsWith(".audioaz.com")) ||
            !uri.path.startsWith("/en/audiobook/")
        ) {
            throw IllegalArgumentException("Only AudioAZ HTTPS audiobook pages are supported")
        }
        return uri.toString()
    }

    private fun isAllowedSourcePage(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty().lowercase()
        uri.scheme == "https" && (
            ((host == "librivox.org" || host.endsWith(".librivox.org")) &&
                !path.contains("/people/") && !path.contains("/author/") &&
                !path.contains("/search") && !path.contains("/narrator")) ||
            ((host == "archive.org" || host.endsWith(".archive.org")) &&
                path.startsWith("/details/"))
        )
    }.getOrDefault(false)

    private fun isAllowedPublicAudioUrl(raw: String, pageConfirmsPublicDomain: Boolean): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        val librivoxHost = host == "librivox.org" || host.endsWith(".librivox.org")
        val archiveHost = host == "archive.org" || host.endsWith(".archive.org")
        // Internet Archive also hosts copyrighted material; accept it only when
        // the specific AudioAZ item explicitly identifies itself as public domain.
        val approvedHost = librivoxHost || (archiveHost && pageConfirmsPublicDomain)
        val path = uri.path?.lowercase().orEmpty()
        val extension = listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac")
            .any { path.endsWith(it) }
        uri.scheme == "https" && approvedHost && extension
    }.getOrDefault(false)
}
