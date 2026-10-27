package com.rashedul.audible

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
 * AudioAZ catalogue adapter restricted to explicitly identified public-domain items.
 *
 * Search results are only candidates. A recording is returned only after its detail
 * page has a public-domain statement, an approved LibriVox/Internet Archive source,
 * a strong title match, and an author match when Audible supplies an author.
 * Playback URLs are independently checked before they are emitted to CloudStream.
 */
internal class AudioAZPublicDomainAdapter {
    data class Recording(
        val title: String,
        val url: String,
        val author: String,
        val narrators: String,
        val language: String,
        val duration: String,
        val releaseDate: String,
        val description: String,
        val poster: String?,
        val durationMinutes: Int?,
        val matchScore: Double
    ) {
        val displayTitle: String
            get() = listOf(
                title,
                narrators.takeIf { it.isNotBlank() }?.let { "Narrated by $it" },
                duration.takeIf { it.isNotBlank() },
                language.takeIf { it.isNotBlank() }
            ).filterNotNull().joinToString(" — ")

        val summaryText: String
            get() = buildList {
                if (author.isNotBlank()) add("Author: $author")
                if (narrators.isNotBlank()) add("Narrator: $narrators")
                if (language.isNotBlank()) add("Language: $language")
                if (duration.isNotBlank()) add("Duration: $duration")
                if (releaseDate.isNotBlank()) add("Release date: $releaseDate")
                add("Source: AudioAZ; playback is limited to verified public-domain audio sources.")
                if (description.isNotBlank()) add(description)
            }.joinToString("\n\n")
    }

    private data class SearchHit(
        val url: String,
        val title: String,
        val poster: String?
    )

    private val siteRoot = "https://audioaz.com"
    private val siteBase = "$siteRoot/en"
    private val requestSemaphore = Semaphore(permits = 3)
    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to siteBase
    )

    suspend fun findMatches(bookTitle: String, bookAuthors: String): List<Recording> {
        if (bookTitle.isBlank()) return emptyList()

        val firstAuthor = bookAuthors
            .split(Regex("(?i)\\s*(?:,|;|&|\\band\\b)\\s*"))
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val queries = listOf(
            listOf(bookTitle, firstAuthor).filter { it.isNotBlank() }.joinToString(" "),
            bookTitle
        ).distinct()

        val hits = linkedMapOf<String, SearchHit>()
        for (query in queries) {
            searchHits(query).forEach { hit -> hits.putIfAbsent(hit.url, hit) }
            if (hits.size >= 14) break
        }
        if (hits.isEmpty()) return emptyList()

        val targetHits = hits.values
            .map { hit -> hit to titleSimilarity(bookTitle, hit.title) }
            .filter { (_, score) -> score >= 0.52 }
            .sortedByDescending { it.second }
            .take(10)

        return coroutineScope {
            targetHits.map { (hit, score) ->
                async {
                    inspectRecording(hit.url, bookTitle, bookAuthors, score)
                }
            }.awaitAll()
                .filterNotNull()
                .distinctBy { it.url }
                .sortedByDescending { it.matchScore }
                .take(6)
        }
    }

    fun isAudioAZBookUrl(raw: String): Boolean = validateAudioAZBookUrl(raw) != null

    suspend fun inspectRecording(
        rawUrl: String,
        expectedTitle: String = "",
        expectedAuthor: String = "",
        searchScore: Double? = null
    ): Recording? {
        val safeUrl = validateAudioAZBookUrl(rawUrl) ?: return null
        return try {
            val doc = fetchDocument(safeUrl)
            inspectDocument(doc, safeUrl, expectedTitle, expectedAuthor, searchScore)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    suspend fun loadLinks(
        rawUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val safeUrl = validateAudioAZBookUrl(rawUrl) ?: return false
        return try {
            val doc = fetchDocument(safeUrl)
            val pageConfirmsPublicDomain = hasPublicDomainClaim(doc.text())
            if (!pageConfirmsPublicDomain) return false

            var found = false
            val emittedUrls = mutableSetOf<String>()
            directAudioUrls(doc, safeUrl, pageConfirmsPublicDomain).forEach { audioUrl ->
                if (emittedUrls.add(audioUrl)) {
                    callback(newExtractorLink("Audible Public Domain", "Public-domain audio", audioUrl) {
                        quality = Qualities.Unknown.value
                        referer = safeUrl
                    })
                    found = true
                }
            }

            approvedSourcePages(doc, safeUrl).take(3).forEach { sourceUrl ->
                loadExtractor(sourceUrl, subtitleCallback) { link ->
                    if (isAllowedPublicAudioUrl(link.url, pageConfirmsPublicDomain) &&
                        emittedUrls.add(link.url)
                    ) {
                        callback(link)
                        found = true
                    }
                }
            }
            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    private suspend fun searchHits(query: String): List<SearchHit> {
        val encoded = query.encodeUri()
        val urls = listOf(
            "$siteBase/search?q=$encoded",
            "$siteRoot/en/audiobooks?q=$encoded",
            "$siteRoot/en/audiobooks?search=$encoded",
            "$siteBase/browse?search=$encoded",
            "$siteBase/browse?q=$encoded"
        )
        for (url in urls) {
            val hits = try {
                parseSearchHits(fetchDocument(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private suspend fun fetchDocument(url: String): Document =
        requestSemaphore.withPermit { app.get(url, headers = requestHeaders).document }

    private fun parseSearchHits(doc: Document): List<SearchHit> {
        val hits = linkedMapOf<String, SearchHit>()
        doc.select("a[href*='/en/audiobook/']").forEach { anchor ->
            val safeUrl = validateAudioAZBookUrl(absoluteUrl(anchor, "href")) ?: return@forEach
            val card = anchor.parents().firstOrNull {
                it.selectFirst("h1, h2, h3, h4, h5") != null ||
                    (it.selectFirst("img") != null && it.text().length <= 900)
            } ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val title = listOf(
                anchor.selectFirst("h1, h2, h3, h4, h5")?.text().orEmpty(),
                card.selectFirst("h2, h3, h4, h5")?.text().orEmpty(),
                anchor.attr("aria-label"),
                anchor.attr("title"),
                anchor.text(),
                image?.attr("alt").orEmpty(),
                card.text()
            ).map(::cleanTitle).firstOrNull { it.isNotBlank() }.orEmpty()
            if (title.isBlank()) return@forEach
            val poster = image?.let { normalizeHttpsUrl(absoluteUrl(it, "src")) }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.let(::normalizeHttpsUrl)
            hits.putIfAbsent(safeUrl, SearchHit(safeUrl, title, poster))
        }
        return hits.values.toList()
    }

    private fun inspectDocument(
        doc: Document,
        safeUrl: String,
        expectedTitle: String,
        expectedAuthor: String,
        searchScore: Double?
    ): Recording? {
        val pageText = doc.text()
        if (!hasPublicDomainClaim(pageText)) return null

        val title = cleanTitle(
            doc.selectFirst("h1")?.text()
                .orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
                .ifBlank { doc.title() }
        )
        if (title.isBlank()) return null

        val author = doc.select(
            "a[href*='/en/authors/'], a[href*='/en/author/'], a[href*='/authors/'], a[href*='/author/']"
        ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            .ifBlank { firstLabelValue(doc, "Author") }
        val narrators = doc.select(
            "a[href*='/en/narrators/'], a[href*='/en/narrator/'], a[href*='/narrators/'], a[href*='/narrator/']"
        ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            .ifBlank { firstLabelValue(doc, "Narrator") }

        val score = if (expectedTitle.isBlank()) 1.0 else titleSimilarity(expectedTitle, title)
        if (expectedTitle.isNotBlank() && score < 0.78) return null
        if (expectedAuthor.isNotBlank() && !authorsMatch(expectedAuthor, author)) return null

        val approvedPages = approvedSourcePages(doc, safeUrl)
        val directUrls = directAudioUrls(doc, safeUrl, true)
        // Do not show a dead-end version card merely because its title matches.
        if (approvedPages.isEmpty() && directUrls.isEmpty()) return null

        val language = firstLabelValue(doc, "Language")
        val duration = firstLabelValue(doc, "Length").ifBlank {
            firstLabelValue(doc, "Duration")
        }
        val releaseDate = firstLabelValue(doc, "Release date").ifBlank {
            firstLabelValue(doc, "Publication date")
        }.ifBlank { firstLabelValue(doc, "Published") }
        val poster = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")?.let(::normalizeHttpsUrl)
            ?: doc.selectFirst("main img[src], article img[src], img[src]")?.let {
                normalizeHttpsUrl(absoluteUrl(it, "src"))
            }

        return Recording(
            title = title,
            url = safeUrl,
            author = author,
            narrators = narrators,
            language = language,
            duration = duration,
            releaseDate = releaseDate,
            description = firstLabelValue(doc, "About").ifBlank {
                doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            },
            poster = poster,
            durationMinutes = durationToMinutes(duration),
            matchScore = maxOf(score, searchScore ?: 0.0)
        )
    }

    private fun approvedSourcePages(doc: Document, pageUrl: String): List<String> =
        doc.select("a[href]").mapNotNull { anchor ->
            val href = absoluteUrl(anchor, "href")
            val label = anchor.text().lowercase()
            href.takeIf {
                (label.contains("librivox") ||
                    label.contains("internet archive") ||
                    label.contains("archive.org")) &&
                    isAllowedSourcePage(it)
            }
        }.distinct().take(5)

    private fun directAudioUrls(
        doc: Document,
        pageUrl: String,
        pageConfirmsPublicDomain: Boolean
    ): List<String> = doc.select(
        "audio[src], audio source[src], source[src], meta[property=og:audio]"
    ).mapNotNull { element ->
        val raw = if (element.tagName().equals("meta", true)) {
            element.attr("content")
        } else {
            element.attr("src")
        }
        val absolute = runCatching { URI(pageUrl).resolve(raw).toString() }.getOrDefault("")
        absolute.takeIf { isAllowedPublicAudioUrl(it, pageConfirmsPublicDomain) }
    }.distinct()

    private fun isAllowedPublicAudioUrl(raw: String, pageConfirmsPublicDomain: Boolean): Boolean =
        runCatching {
            if (!pageConfirmsPublicDomain) return@runCatching false
            val uri = URI(raw)
            val host = uri.host?.lowercase().orEmpty()
            val approvedHost = host == "librivox.org" || host.endsWith(".librivox.org") ||
                host == "archive.org" || host.endsWith(".archive.org")
            val extension = listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac")
                .any { uri.path?.lowercase()?.endsWith(it) == true }
            uri.scheme == "https" && approvedHost && extension
        }.getOrDefault(false)

    private fun isAllowedSourcePage(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        uri.scheme == "https" && (
            ((host == "librivox.org" || host.endsWith(".librivox.org")) &&
                !path.contains("/people/") && !path.contains("/author/") &&
                !path.contains("/search") && !path.contains("/narrator")) ||
            ((host == "archive.org" || host.endsWith(".archive.org")) && path.startsWith("/details/"))
        )
    }.getOrDefault(false)

    private fun hasPublicDomainClaim(rawText: String): Boolean {
        val lower = rawText.lowercase()
        val claimed = Regex("""\bpublic domain\b""").containsMatchIn(lower)
        val denied = Regex("""\b(?:not|is not|isn't|no longer)\s+(?:in\s+the\s+)?public domain\b""")
            .containsMatchIn(lower)
        return claimed && !denied
    }

    private fun firstLabelValue(doc: Document, label: String): String =
        doc.select("p, li, div, span, dt, dd").map { it.text().trim() }
            .filter { it.startsWith("$label:", true) && it.length < 220 }
            .minByOrNull { it.length }
            ?.substringAfter(':')?.trim().orEmpty()

    private fun pageTitle(doc: Document): String =
        doc.selectFirst("h1")?.text().orEmpty()
            .ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty() }
            .ifBlank { doc.title() }

    private fun cleanTitle(raw: String): String = raw.trim()
        .replace(Regex("""(?i)\s*[-–—:]?\s*free audiobook\s*$"""), "")
        .replace(Regex("""(?i)\s*[-–—:]?\s*narrated by\s+.+$"""), "")
        .replace(Regex("""(?i)\s+audiobook\s*$"""), "")
        .replace(Regex("""(?i)\s*[-–—]\s*audioaz\s*$"""), "")
        .trim()

    private fun normalizeTitle(raw: String): String =
        cleanTitle(raw).lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun titleSimilarity(expected: String, candidate: String): Double {
        val left = normalizeTitle(expected).split(' ').filter { it.isNotBlank() }.toSet()
        val right = normalizeTitle(candidate).split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val common = left.intersect(right).size.toDouble()
        val containment = common / minOf(left.size, right.size).toDouble()
        val jaccard = common / left.union(right).size.toDouble()
        return maxOf(jaccard, containment * 0.94)
    }

    private fun authorsMatch(expected: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val expectedNames = expected.split(Regex("(?i)\\s*(?:,|;|&|\\band\\b)\\s*"))
            .map { authorTokens(it) }.filter { it.isNotEmpty() }
        val candidateNames = candidate.split(Regex("(?i)\\s*(?:,|;|&|\\band\\b)\\s*"))
            .map { authorTokens(it) }.filter { it.isNotEmpty() }
        return expectedNames.any { left ->
            candidateNames.any { right ->
                val overlap = left.intersect(right).size.toDouble()
                overlap / minOf(left.size, right.size).toDouble() >= 0.6
            }
        }
    }

    private fun authorTokens(value: String): Set<String> =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ")
            .split(' ').filter { it.length > 1 }.toSet()

    private fun durationToMinutes(value: String): Int? {
        val hours = Regex("""(?i)\b(\d+)\s*h(?:r|our)?s?\b""")
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(?i)\b(\d+)\s*m(?:in(?:ute)?s?)?\b""")
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun absoluteUrl(element: Element, attribute: String): String {
        val raw = element.attr(attribute).trim()
        if (raw.isBlank()) return ""
        return runCatching {
            val base = element.absUrl(attribute).takeIf { it.startsWith("https://") }
            base ?: URI(siteRoot).resolve(raw).toString()
        }.getOrDefault("")
    }

    private fun normalizeHttpsUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme == "https" && !uri.host.isNullOrBlank()) uri.toString() else null
    }.getOrNull()

    private fun validateAudioAZBookUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" ||
            (host != "audioaz.com" && !host.endsWith(".audioaz.com")) ||
            !uri.path.orEmpty().matches(Regex("^/en/audiobook/[^/]+/?$"))
        ) {
            null
        } else {
            uri.toString().substringBefore("#").substringBefore("?").trimEnd('/')
        }
    }.getOrNull()
}
