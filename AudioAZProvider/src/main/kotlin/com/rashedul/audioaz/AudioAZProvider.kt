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

class AudioAZProvider : MainAPI() {
    private val siteRoot = "https://audioaz.com"
    private val siteBase = "$siteRoot/en"

    override var mainUrl = siteBase
    override var name = "AudioAZ"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Audio, TvType.Others)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val popular = parseBookCards(app.get("$siteBase/browse?page=$page").document)
                .map { it.toSearchResponse(this@AudioAZProvider) }
            val recent = parseBookCards(app.get("$siteBase/browse?sort=recent&page=$page").document)
                .map { it.toSearchResponse(this@AudioAZProvider) }
            newHomePageResponse(
                listOf(
                    HomePageList("Most Popular", popular, page < 100),
                    HomePageList("Most Recent", recent, page < 100)
                ),
                page < 100
            )
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            val encoded = query.encodeUri()
            val candidateUrls = listOf(
                "$siteBase/search?q=$encoded&page=$page",
                "$siteBase/browse?search=$encoded&page=$page",
                "$siteBase/browse?q=$encoded&page=$page"
            )
            var results: List<BookCard> = emptyList()
            for (candidate in candidateUrls) {
                results = runCatching { parseBookCards(app.get(candidate).document) }
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
            val doc = app.get(safeUrl).document
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
            val doc = app.get(safeUrl).document
            var found = false

            val directAudioUrls = doc.select(
                "audio[src], audio source[src], source[src], meta[property=og:audio]"
            ).mapNotNull { element ->
                val raw = if (element.tagName().equals("meta", true)) {
                    element.attr("content")
                } else element.attr("src")
                val absolute = if (raw.startsWith("https://")) raw else element.absUrl("src")
                absolute.takeIf { isAllowedPublicAudioUrl(it) }
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
                    if (isAllowedPublicAudioUrl(link.url)) {
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
            val href = anchor.absUrl("href").substringBefore('#').substringBefore('?').trimEnd('/')
            if (!href.startsWith("$siteBase/audiobook/")) return@forEach

            val card = anchor.parents().firstOrNull {
                it.selectFirst("img") != null && it.text().trim().length in 5..700
            } ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val heading = anchor.selectFirst("h1, h2, h3, h4")
                ?: card.selectFirst("h2, h3, h4")
            val title = cleanBookTitle(
                heading?.text().orEmpty()
                    .ifBlank { image?.attr("alt").orEmpty() }
                    .ifBlank { anchor.attr("aria-label") }
                    .ifBlank { anchor.attr("title") }
                    .ifBlank { anchor.text() }
            )
            if (title.isBlank() || title.equals("Audiobook", true)) return@forEach
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
        .replace(Regex("(?i)\\s*[-–—]\\s*Free Audiobook\\s*$"), "")
        .replace(Regex("(?i)^audiobook\\s+"), "")
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

    private fun isAllowedPublicAudioUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        val approvedHost = host == "librivox.org" || host.endsWith(".librivox.org") ||
            host == "archive.org" || host.endsWith(".archive.org")
        val path = uri.path?.lowercase().orEmpty()
        val extension = listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac")
            .any { path.endsWith(it) }
        uri.scheme == "https" && approvedHost && extension
    }.getOrDefault(false)
}
