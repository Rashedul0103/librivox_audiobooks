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

    private data class HomeShelf(val title: String, val url: String)

    // These are real AudioAZ routes. Keep a compact set of useful shelves so
    // the home page does not make dozens of slow requests on first open.
    private val formatShelves = listOf(
        HomeShelf("Dramatic Readings", "$siteBase/genre/dramatic-readings"),
        HomeShelf("Ensemble & Full-Cast Plays", "$siteBase/topic/free-shakespeare-audiobooks"),
        HomeShelf("Multi-Version Poetry", "$siteBase/genre/multi-version")
    )

    private val discoveryShelves = listOf(
        HomeShelf("Most Recent", "$siteBase/browse?sort=recent"),
        HomeShelf("Science Fiction", "$siteBase/genre/science-fiction"),
        HomeShelf("Horror & Ghost Stories", "$siteBase/topic/free-horror-audiobooks"),
        HomeShelf("Mystery & Detective", "$siteBase/topic/free-mystery-audiobooks"),
        HomeShelf("Romance", "$siteBase/topic/free-romance-audiobooks"),
        HomeShelf("Kids & Family", "$siteBase/topic/free-audiobooks-for-kids"),
        HomeShelf("History", "$siteBase/genre/history"),
        HomeShelf("Non-fiction", "$siteBase/genre/non-fiction"),
        HomeShelf("English Learners", "$siteBase/topic/english-audiobooks-for-learners")
    )

    private suspend fun fetchHomeRow(title: String, urls: List<String>, page: Int = 1): HomePageList? {
        val pagedUrls = urls.map { url ->
            if (page <= 1) url
            else url + if (url.contains("?")) "&page=$page" else "?page=$page"
        }
        val books = fetchBookCards(pagedUrls)
            .map { it.toSearchResponse(this@AudioAZProvider) }
        // CloudStream uses false for vertical / portrait cards.
        return books.takeIf { it.isNotEmpty() }?.let { HomePageList(title, it, false) }
    }

    private fun parseHomeSection(doc: Document, headingText: String): List<BookCard> {
        val heading = doc.select("h1, h2, h3, h4").firstOrNull {
            it.text().contains(headingText, ignoreCase = true)
        } ?: return emptyList()

        // Find the nearest containing carousel/section, not the entire page.
        val section = heading.parents().firstOrNull { parent ->
            val links = parent.select("a[href*='/en/audiobook/']")
            links.size in 2..24 &&
                parent.select("h1, h2, h3, h4").any {
                    it.text().contains(headingText, ignoreCase = true)
                }
        } ?: heading.parent() ?: return emptyList()

        return parseBookCards(section)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val homeDoc = runCatching { fetchDocument(siteBase) }
                .onFailure { logError(it) }
                .getOrNull()

            val rows = mutableListOf<HomePageList>()
            val popular = homeDoc?.let { parseHomeSection(it, "Most Streamed Audiobooks") }.orEmpty()
                .ifEmpty { fetchBookCards(listOf("$siteBase/browse")) }
                .map { it.toSearchResponse(this@AudioAZProvider) }
            if (popular.isNotEmpty()) rows += HomePageList("Most Streamed", popular, false)

            // Put distinct audio formats near the top, before general genre shelves.
            val formatRows = coroutineScope {
                formatShelves.map { shelf ->
                    async { fetchHomeRow(shelf.title, listOf(shelf.url), page) }
                }.awaitAll().filterNotNull()
            }
            rows += formatRows

            // AudioAZ marks this homepage shelf for books with read-along text.
            val readAlong = homeDoc?.let { parseHomeSection(it, "Listen and read along") }.orEmpty()
                .map { it.toSearchResponse(this@AudioAZProvider) }
            if (readAlong.isNotEmpty()) rows += HomePageList("Listen & Read Along", readAlong, false)

            // Load up to four genre/topic pages concurrently to reduce total latency
            // without sending a burst of requests to AudioAZ.
            discoveryShelves.chunked(4).forEach { batch ->
                val batchRows = coroutineScope {
                    batch.map { shelf ->
                        async { fetchHomeRow(shelf.title, listOf(shelf.url), page) }
                    }.awaitAll().filterNotNull()
                }
                rows += batchRows
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
            val readAlongPage = isReadAlongUrl(safeUrl)
            val bookUrl = safeUrl.substringBefore("/read/")
            val bookTitle = pageTitle(doc).ifBlank { "AudioAZ audiobook" }
            val chapterTitle = if (readAlongPage) readAlongChapterTitle(doc) else ""
            val displayTitle = if (readAlongPage) {
                "$bookTitle — Read Along: ${chapterTitle.ifBlank { "Chapter ${readAlongNumber(safeUrl) ?: 1}" }}"
            } else bookTitle

            val author = firstLinkedText(doc, "a[href*='/en/authors/'], a[href*='/en/author/']")
            val genres = doc.select("a[href*='/en/genres/'], a[href*='/en/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val narrators = doc.select("a[href*='/en/narrators/'], a[href*='/en/narrator/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
            val language = firstLabelValue(doc, "Language")
            val length = firstLabelValue(doc, "Length")
            val publicationDate = findReleaseDate(doc)
            val poster = findBookPoster(doc, bookTitle)
            val background = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
                ?.attr("content")?.takeIf { it.startsWith("https://") }
            val summary = findDescription(doc)

            var versions = parseOtherVersions(doc, bookUrl, poster)
            var readAlongChapters = parseReadAlongChapters(doc, safeUrl, poster)

            // Alternate recordings often live on /read/1 rather than the plain
            // book landing page. Probe only when the main page gives a chapter count
            // but does not expose any versions or chapter entries.
            val companionReadAlongDoc = if (
                !readAlongPage &&
                    (versions.isEmpty() || readAlongChapters.isEmpty()) &&
                    parseChapterCount(doc.text()) > 0
            ) {
                runCatching { fetchDocument("$bookUrl/read/1") }
                    .onFailure { logError(it) }
                    .getOrNull()
            } else null

            if (versions.isEmpty() && companionReadAlongDoc != null) {
                versions = parseOtherVersions(companionReadAlongDoc, bookUrl, poster)
            }
            if (readAlongChapters.isEmpty() && companionReadAlongDoc != null) {
                readAlongChapters = parseReadAlongChapters(companionReadAlongDoc, safeUrl, poster)
            }

            val chapterText = if (readAlongPage) findReadAlongText(doc) else ""

            newMovieLoadResponse(displayTitle, safeUrl, TvType.Audio, safeUrl) {
                plot = buildDescription(
                    author = author,
                    language = language,
                    narrators = narrators,
                    genres = genres,
                    length = length,
                    publicationDate = publicationDate,
                    summary = summary,
                    sourceUrl = bookUrl,
                    versions = versions,
                    readAlongPage = readAlongPage,
                    chapterTitle = chapterTitle,
                    chapterText = chapterText,
                    readAlongChaptersAvailable = readAlongChapters.isNotEmpty()
                )
                posterUrl = poster
                backgroundPosterUrl = background
                posterHeaders = requestHeaders
                tags = genres
                duration = parseDurationMinutes(length)
                recommendations = (versions.map { it.card } + readAlongChapters)
                    .distinctBy { it.url }
                    .take(26)
                    .map { it.toSearchResponse(this@AudioAZProvider) }
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
                posterHeaders = provider.requestHeaders
            }
    }

    private data class VersionInfo(
        val title: String,
        val url: String,
        val poster: String?,
        val reader: String?,
        val language: String?,
        val duration: String?,
        val releaseDate: String?
    ) {
        fun toCard(): BookCard {
            val subtitle = listOfNotNull(reader, duration, language)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · ")
            return BookCard(
                title = if (subtitle.isBlank()) title else "$title — $subtitle",
                url = url,
                poster = poster
            )
        }
    }

    private fun parseBookCards(container: Element): List<BookCard> {
        val cards = linkedMapOf<String, BookCard>()
        container.select("a[href*='/en/audiobook/']").forEach { anchor ->
            val href = absoluteUrl(anchor, "href")
                .substringBefore('#').substringBefore('?').trimEnd('/')
            if (!isAudioAZBookUrl(href)) return@forEach

            val card = anchor.parents().firstOrNull {
                it.selectFirst("h1, h2, h3, h4, h5") != null ||
                    (it.selectFirst("img") != null && it.text().trim().length in 5..700)
            } ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val heading = anchor.selectFirst("h1, h2, h3, h4, h5")
                ?: card.selectFirst("h2, h3, h4, h5")
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
                        !it.equals("image", true) &&
                        it.length <= 180
                }.orEmpty()
            if (title.isBlank()) return@forEach
            val poster = image?.let(::imageUrl)
            cards.putIfAbsent(href, BookCard(title, href, poster))
        }
        return cards.values.toList()
    }

    private fun parseOtherVersions(
        doc: Document,
        currentBookUrl: String,
        fallbackPoster: String?
    ): List<VersionInfo> {
        val heading = doc.select("h2, h3, h4").firstOrNull {
            it.text().startsWith("Other versions", ignoreCase = true)
        } ?: return emptyList()

        val section = heading.parents().firstOrNull { parent ->
            val links = parent.select("a[href*='/en/audiobook/']")
            links.size in 1..14 &&
                parent.text().contains("Other versions", ignoreCase = true)
        } ?: heading.parent() ?: return emptyList()

        val current = normalizeBookUrl(currentBookUrl)
        val versions = linkedMapOf<String, VersionInfo>()
        section.select("a[href*='/en/audiobook/']").forEach { anchor ->
            val href = absoluteUrl(anchor, "href")
                .substringBefore('#').substringBefore('?').trimEnd('/')
            if (!isAudioAZBookUrl(href) || normalizeBookUrl(href) == current) return@forEach

            val title = cleanBookTitle(
                anchor.text().ifBlank {
                    anchor.attr("aria-label").ifBlank { anchor.attr("title") }
                }
            ).take(180)
            if (title.isBlank()) return@forEach

            val detailElement = anchor.parents().firstOrNull { parent ->
                parent.text().length <= 500 &&
                    (parent.text().contains("narrated by", true) ||
                        parent.text().contains("several narrators", true) ||
                        parent.text().contains("one narrator", true) ||
                        Regex("""\b\d+\s*h(?:r|our)?s?(?:\s*\d+\s*m(?:in(?:ute)?s?)?)?\b""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(parent.text()))
            } ?: anchor.parent()
            val details = detailElement?.text()?.trim().orEmpty()
            val reader = parseReader(details)
            val language = parseLanguage(details)
            val duration = parseVersionDuration(details)
            val releaseDate = parseReleaseDateFromText(details)
            val image = anchor.selectFirst("img") ?: detailElement?.selectFirst("img")
            val entry = VersionInfo(
                title = title,
                url = href,
                poster = image?.let(::imageUrl) ?: fallbackPoster,
                reader = reader,
                language = language,
                duration = duration,
                releaseDate = releaseDate
            )
            versions.putIfAbsent(href, entry)
        }
        return versions.values.toList().take(10)
    }

    private fun parseReadAlongChapters(
        doc: Document,
        currentUrl: String,
        fallbackPoster: String?
    ): List<BookCard> {
        val current = currentUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        val chapters = linkedMapOf<String, BookCard>()

        doc.select("a[href*='/read/']").forEach { anchor ->
            val href = absoluteUrl(anchor, "href")
                .substringBefore('#').substringBefore('?').trimEnd('/')
            if (!isReadAlongUrl(href) || href == current) return@forEach

            val number = readAlongNumber(href)
            val nearby = anchor.parents().firstOrNull {
                it.text().length <= 180 && it.text().contains(anchor.text().trim(), true)
            }?.text().orEmpty()
            val rawLabel = anchor.text().trim().ifBlank { nearby }
            val label = cleanChapterLabel(rawLabel).ifBlank {
                "Chapter ${number ?: chapters.size + 1}"
            }
            val img = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img")
            chapters.putIfAbsent(
                href,
                BookCard(
                    title = "Read Along — $label",
                    url = href,
                    poster = img?.let(::imageUrl) ?: fallbackPoster
                )
            )
        }
        if (chapters.isNotEmpty()) {
            return chapters.values.toList().filter { it.url != current }.take(16)
        }

        val text = doc.text()
        val chapterCount = parseChapterCount(text)
        val readAlongSupported = text.contains("Read along Follow the text", true)
        if (!readAlongSupported || chapterCount < 1) return emptyList()

        val baseUrl = normalizeBookUrl(currentUrl)
        return (1..minOf(chapterCount, 16))
            .map { chapter ->
                BookCard(
                    title = "Read Along — Chapter $chapter",
                    url = "$baseUrl/read/$chapter",
                    poster = fallbackPoster
                )
            }
            .filter { it.url != current }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun htmlText(value: String): String = escapeHtml(value.trim())
        .replace("\\r\\n", "\\n")
        .replace("\\r", "\\n")
        .replace("\\n", "<br>")

    private fun buildDescription(
        author: String,
        language: String,
        narrators: List<String>,
        genres: List<String>,
        length: String,
        publicationDate: String?,
        summary: String,
        sourceUrl: String,
        versions: List<VersionInfo>,
        readAlongPage: Boolean,
        chapterTitle: String,
        chapterText: String,
        readAlongChaptersAvailable: Boolean
    ): String {
        val sections = mutableListOf<String>()

        fun addSection(heading: String, value: String) {
            val trimmed = value.trim()
            if (trimmed.isNotBlank()) {
                sections += "<b>" + escapeHtml(heading) + "</b><br>" + htmlText(trimmed)
            }
        }

        addSection("AUTHOR", author)
        addSection("LANGUAGE", language)
        addSection("LENGTH", length)
        addSection("RELEASE / PUBLICATION DATE", publicationDate.orEmpty())
        if (genres.isNotEmpty()) {
            addSection("GENRES", genres.distinct().joinToString("\\n") { "• $it" })
        }
        if (narrators.isNotEmpty()) {
            addSection("NARRATORS", narrators.distinct().joinToString("\\n") { "• $it" })
        }
        addSection(
            if (readAlongPage) "CHAPTER" else "ABOUT / SUMMARY",
            if (readAlongPage) chapterTitle else summary
        )

        if (versions.isNotEmpty()) {
            val versionText = versions.joinToString("<br><br>") { version ->
                buildString {
                    append("• <b>").append(escapeHtml(version.title)).append("</b>")
                    append("<br>Reader: ").append(escapeHtml(version.reader ?: "Not listed on this version card"))
                    if (!version.language.isNullOrBlank()) {
                        append("<br>Language: ").append(escapeHtml(version.language))
                    }
                    if (!version.duration.isNullOrBlank()) {
                        append("<br>Duration: ").append(escapeHtml(version.duration))
                    }
                    append("<br>Release date: ")
                        .append(escapeHtml(version.releaseDate ?: "Not listed by AudioAZ"))
                    append("<br><i>Open this recording using its matching clickable card below.</i>")
                }
            }
            sections += "<b>OTHER RECORDINGS / VERSIONS</b><br>" + versionText
        }

        if (readAlongPage) {
            addSection(
                "READ-ALONG TEXT",
                chapterText.ifBlank { "AudioAZ did not expose the chapter text in a readable page section." }
            )
            addSection(
                "READ-ALONG NOTE",
                "Chapter text is shown here. CloudStream can play a supported source when one is exposed, but AudioAZ's synchronized word highlighting belongs to its own website player and cannot be reproduced by a standard CloudStream provider."
            )
        } else if (readAlongChaptersAvailable) {
            addSection(
                "READ ALONG",
                "Read-along chapters are available as clickable related cards below. Open a chapter card to view its text inside CloudStream."
            )
        }

        addSection("SOURCE PAGE", sourceUrl)

        return sections.joinToString("<br><br>━━━━━━━━━━━━━━━━━━━━<br><br>")
    }

    private fun absoluteUrl(element: Element, attribute: String): String {
        val raw = element.attr(attribute).trim()
        if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
        return runCatching {
            val base = element.absUrl(attribute).takeIf { it.startsWith("https://") }
            base ?: URI(siteRoot).resolve(raw).toString()
        }.getOrDefault("")
    }

    private fun isAudioAZBookUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" &&
            (host == "audioaz.com" || host.endsWith(".audioaz.com")) &&
            uri.path.orEmpty().matches(Regex("^/en/audiobook/[^/]+/?$"))
    }.getOrDefault(false)

    private fun normalizeBookUrl(raw: String): String =
        raw.substringBefore("/read/").substringBefore('#').substringBefore('?').trimEnd('/')

    private fun isReadAlongUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" &&
            (host == "audioaz.com" || host.endsWith(".audioaz.com")) &&
            uri.path.orEmpty().matches(Regex("^/en/audiobook/[^/]+/read/[^/]+/?$"))
    }.getOrDefault(false)

    private fun readAlongNumber(raw: String): Int? =
        runCatching { URI(raw).path.orEmpty().substringAfterLast('/').toIntOrNull() }.getOrNull()

    private fun parseChapterCount(raw: String): Int =
        Regex("""(?i)\b(\d{1,3})\s+chapter(?:s|\(s\))?(?=\b|\s|$)""")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 60) ?: 0

    private fun cleanChapterLabel(raw: String): String {
        val text = raw.replace(Regex("""(?i)^read along\s*"""), "").trim()
        val cleaned = text.replace(Regex("""^\d+\s*[./-]\s*"""), "").trim()
        return cleaned.takeIf { it.isNotBlank() && !it.equals("follow the text while you listen", true) }
            ?.take(120).orEmpty()
    }

    private fun parseReader(raw: String): String? {
        val text = raw.replace('\n', ' ').trim()
        val narrated = Regex(
            """(?i)narrated by\s+(.+?)(?=\s*[·•|]\s*|\s+(?:English|French|German|Spanish|Italian|Vietnamese|Arabic|Urdu|Hindi|Portuguese|Dutch|Greek|Russian|Japanese|Chinese)\b|\s+\d+\s*h|$)"""
        ).find(text)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '·', '•')
        if (!narrated.isNullOrBlank()) return narrated
        return when {
            text.contains("several narrators", true) -> "Several narrators"
            text.contains("multiple narrators", true) -> "Multiple narrators"
            text.contains("one narrator", true) -> "Single narrator (name not shown here)"
            else -> null
        }
    }

    private fun parseLanguage(raw: String): String? {
        val languages = listOf(
            "English", "Vietnamese", "German", "Spanish", "French", "Dutch", "Portuguese",
            "Italian", "Greek", "Russian", "Japanese", "Polish", "Chinese", "Arabic", "Urdu",
            "Hindi", "Latin", "Hebrew", "Finnish", "Czech", "Persian", "Korean"
        )
        return languages.firstOrNull { Regex("""(?i)\b${Regex.escape(it)}\b""").containsMatchIn(raw) }
    }

    private fun parseVersionDuration(raw: String): String? =
        Regex("""(?i)\b\d+\s*h(?:r|our)?s?(?:\s*\d+\s*m(?:in(?:ute)?s?)?)?\b|\b\d+\s*m(?:in(?:ute)?s?)\b""")
            .find(raw)?.value?.replace(Regex("""\s+"""), " ")?.trim()

    private fun parseReleaseDateFromText(raw: String): String? {
        val pattern = Regex(
            """(?i)(?:release date|publication date|published date|date published|published on)\s*[:–—-]?\s*(.{3,45}?)(?=\s*[·•|]|$)"""
        )
        return pattern.find(raw)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', ';')
            ?.takeIf { it.isNotBlank() }
    }

    private fun findReleaseDate(doc: Document): String? {
        val labelled = doc.select("p, li, dd, dt, div, span")
            .map { it.text().trim() }
            .firstOrNull { parseReleaseDateFromText(it) != null }
            ?.let(::parseReleaseDateFromText)
        if (!labelled.isNullOrBlank()) return labelled

        val jsonLd = doc.select("script[type=application/ld+json]").joinToString(" ") { it.data() }
        val jsonDate = Regex("\"(?:datePublished|releaseDate)\"\\s*:\\s*\"([^\"]+)\"")
            .find(jsonLd)?.groupValues?.getOrNull(1)
        return jsonDate?.takeIf { it.isNotBlank() }
    }

    private fun readAlongChapterTitle(doc: Document): String {
        val headings = doc.select("h1, h2, h3, h4")
        val aboutIndex = headings.indexOfFirst {
            it.text().equals("About", true) || it.text().equals("Summary", true)
        }.let { if (it < 0) headings.size else it }

        return headings.take(aboutIndex).asReversed().firstOrNull { heading ->
            val text = heading.text().trim()
            text.isNotBlank() &&
                !text.startsWith("Other versions", true) &&
                !text.matches(Regex("""\d+\s*/\s*\d+.*""")) &&
                !text.contains("Free Audiobook", true) &&
                text.length <= 160 &&
                !text.equals("Now Playing", true)
        }?.text()?.trim().orEmpty()
    }

    private fun findReadAlongText(doc: Document): String {
        val headings = doc.select("h1, h2, h3, h4")
        val aboutHeading = headings.firstOrNull {
            it.text().equals("About", true) || it.text().equals("Summary", true)
        }
        val chapterTitle = readAlongChapterTitle(doc)
        val chapterHeading = headings.lastOrNull {
            it.text().trim().equals(chapterTitle, true)
        }
        if (chapterHeading != null) {
            val parts = mutableListOf<String>()
            var node = chapterHeading.nextElementSibling()
            while (node != null && node !== aboutHeading) {
                if (node.tagName() in setOf("h1", "h2", "h3", "h4")) break
                val text = node.text().trim()
                if (text.length > 35 &&
                    !text.contains("Sleep Timer", true) &&
                    !text.contains("Show all", true) &&
                    !text.contains("Read along Follow the text", true)
                ) parts += text
                node = node.nextElementSibling()
            }
            val gathered = parts.distinct().joinToString("\n\n").trim()
            if (gathered.length > 80) return gathered
        }

        // Fallback for page templates where the chapter body is wrapped in a
        // nested reader element rather than following its heading directly.
        val paragraphs = doc.select("article p, main p, [class*=read] p, [class*=chapter] p")
            .map { it.text().trim() }
            .filter {
                it.length > 60 &&
                    !it.contains("The Prince is a political treatise", true) &&
                    !it.contains("Free Audiobook", true)
            }
            .distinct()
        return paragraphs.joinToString("\n\n")
    }

    private fun pageTitle(doc: Document): String {
        val h1 = doc.selectFirst("h1")?.text().orEmpty()
        val openGraph = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return cleanBookTitle(h1.ifBlank { openGraph.ifBlank { doc.title() } })
    }

    private fun cleanBookTitle(value: String): String = value.trim()
        .replace(Regex("^\\d+\\s*[.)-]\\s*"), "")
        .replace(Regex("(?i)^play\\s+"), "")
        .replace(Regex("(?i)^audiobook\\s+"), "")
        .replace(Regex("(?i)\\s*[-–—]\\s*Free Audiobook\\s*$"), "")
        .replace(Regex("(?i)\\s+—\\s+narrated by\\s+.+$"), "")
        .replace(Regex("(?i)\\s+audiobook\\s*$"), "")
        .trim()

    private fun imageUrl(img: Element): String {
        // Prefer the largest srcset candidate; some listing images are low-res crops.
        val srcsetCandidates = img.attr("srcset").split(',')
            .mapNotNull { part ->
                val pieces = part.trim().split(Regex("\\s+"))
                val rawUrl = pieces.firstOrNull().orEmpty()
                if (rawUrl.isBlank()) return@mapNotNull null
                val descriptor = pieces.getOrNull(1).orEmpty()
                val size = Regex("""(\d+)(?:w|x)?""").matchEntire(descriptor)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val url = normalizeImageUrl(rawUrl)
                if (url.isBlank()) null else size to url
            }
            .sortedByDescending { it.first }
            .map { it.second }

        val directCandidates = listOf("data-src", "data-lazy-src", "data-original", "src")
            .map { img.attr(it).trim() }
            .map(::normalizeImageUrl)
            .filter { it.isNotBlank() }

        return (srcsetCandidates + directCandidates)
            .distinct()
            .firstOrNull { it.startsWith("https://") }
            .orEmpty()
    }

    private fun normalizeImageUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank() || value.startsWith("data:")) return ""
        return runCatching {
            val resolved = if (value.startsWith("//")) "https:$value"
            else URI(siteRoot).resolve(value).toString()
            val uri = URI(resolved)
            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) uri.toString() else ""
        }.getOrDefault("")
    }

    private fun findBookPoster(doc: Document, title: String): String? {
        val normalizedTitle = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val titleWords = normalizedTitle.split(' ').filter { it.length > 2 }.toSet()
        val images = doc.select("main img[src], main img[data-src], main img[data-lazy-src], article img[src], img[src], img[data-src], img[data-lazy-src]")
            .take(80)

        val scored = images.mapNotNull { img ->
            val url = imageUrl(img).takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val alt = img.attr("alt").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            val urlLower = url.lowercase()
            if (urlLower.endsWith(".svg") || alt.contains("avatar") || alt.contains("logo") ||
                alt.contains("profile") || urlLower.contains("avatar") || urlLower.contains("logo")
            ) return@mapNotNull null

            val width = img.attr("width").toIntOrNull()
            val height = img.attr("height").toIntOrNull()
            val hasDimensions = width != null && height != null && width > 0 && height > 0
            var score = 0
            if (alt.contains(normalizedTitle) && normalizedTitle.isNotBlank()) score += 100
            else if (titleWords.count { it in alt } >= 2) score += 50
            if (urlLower.contains("cover") || urlLower.contains("book")) score += 12
            if (alt.contains("audiobook")) score += 10
            if (hasDimensions) {
                val ratio = height!!.toDouble() / width!!.toDouble()
                if (ratio in 1.1..2.2) score += 40
                else if (ratio in 0.75..1.1) score += 10
                else if (ratio < 0.75) score -= 25
            }
            val dimensions = if (hasDimensions) width.toString() + "x" + height.toString() else ""
            Triple(score, url, dimensions)
        }
        val best = scored.maxByOrNull { it.first }?.second
        if (!best.isNullOrBlank()) return best

        return doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.let(::normalizeImageUrl)
            ?.takeIf { it.isNotBlank() }
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
        val heading = doc.select("h2, h3, h4").firstOrNull {
            it.text().equals("About", true) || it.text().equals("Summary", true)
        }
        if (heading != null) {
            val paragraphs = mutableListOf<String>()
            var node = heading.nextElementSibling()
            var visited = 0
            while (node != null && visited < 20) {
                visited++
                if (node.tagName() in setOf("h1", "h2", "h3", "h4")) break
                val text = node.text().trim()
                if (text.isNotBlank() &&
                    !text.contains("Read More", true) &&
                    !text.contains("Show less", true) &&
                    !text.contains("Other versions", true) &&
                    !text.contains("Related Audiobooks", true)
                ) {
                    if (text.length > 20) paragraphs += text
                }
                node = node.nextElementSibling()
            }
            val collected = paragraphs.distinct().joinToString("\n\n").trim()
            if (collected.isNotBlank()) return collected
        }
        return doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
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
        val path = uri.path.orEmpty()
        val isBookPage = path.matches(Regex("^/en/audiobook/[^/]+/?$"))
        val isReadAlongPage = path.matches(Regex("^/en/audiobook/[^/]+/read/[^/]+/?$"))
        if (uri.scheme != "https" ||
            (host != "audioaz.com" && !host.endsWith(".audioaz.com")) ||
            (!isBookPage && !isReadAlongPage)
        ) {
            throw IllegalArgumentException("Only AudioAZ HTTPS audiobook and read-along pages are supported")
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
