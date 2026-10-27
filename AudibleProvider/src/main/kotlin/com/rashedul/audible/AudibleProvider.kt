package com.rashedul.audible

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Audible catalogue provider backed by Libex metadata, with an isolated adapter
 * for verified public-domain recordings and in-app version cards.
 */
class AudibleProvider : MainAPI() {
    override var mainUrl = API_ROOT
    override var name = "Audible"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    // CloudStream's home-provider picker defaults to Movie/TvSeries filters and has no
    // AudioBook chip. Keep AudioBook as the real item type but advertise these aliases
    // so users can select Audible from the default homepage provider list.
    override val supportedTypes = setOf(
        TvType.AudioBook,
        TvType.Others,
        TvType.Movie,
        TvType.TvSeries
    )

    private val region = "us"

    // Audio discovery is separate from the Audible catalogue. Only verified public-domain
    // recordings from approved sources are cached for cards and playback.
    private val audioSourceAdapter = AudioAZPublicDomainAdapter()
    private val audioSourceCache =
        ConcurrentHashMap<String, List<AudioAZPublicDomainAdapter.Recording>>()

    // Keep catalogue discovery concurrent without flooding the public metadata API.
    // The same limit also applies to book details and search requests.
    private val metadataRequestSemaphore = Semaphore(permits = 4)

    private data class AudibleBook(
        val asin: String,
        val title: String,
        val authors: String,
        val narrators: String,
        val cover: String,
        val description: String,
        val releaseDate: String,
        val publisher: String,
        val durationMinutes: Int?,
        val genres: List<String>,
        val plans: List<String>,
        val series: String,
        val audibleUrl: String
    ) {
        fun card(provider: AudibleProvider) =
            provider.newMovieSearchResponse(title, provider.bookUrl(asin), TvType.AudioBook) {
                posterUrl = provider.displayCover(cover)
            }
    }

    private fun bookUrl(asin: String): String = "${API_ROOT}/book/${asin}?region=${region}"

    /**
     * The app's poster view center-crops a fixed portrait box. Normalize covers
     * to the same portrait dimensions and use fill so the server emits that exact
     * canvas without cutting off title lettering or corner marks. Square source
     * artwork is stretched vertically to fit this fixed card shape.
     */
    private fun displayCover(source: String): String? {
        val original = source.trim()
        if (original.isBlank()) return null
        if (!original.startsWith("https://") && !original.startsWith("http://")) return null
        val encoded = original.encodeUri()
        val fit = AudibleSettings.coverFitMode()
        val params = when (fit) {
            // Remove white/off-white outer margins embedded in some source covers,
            // then stretch the artwork to the full portrait canvas.
            AudibleSettings.FIT_STRETCH ->
                "w=380&h=600&fit=fill&trim=10&tbg=ffffff"
            AudibleSettings.FIT_CROP -> "w=380&h=600&fit=cover"
            else -> "w=380&h=600&fit=contain&cbg=080808"
        }
        // CloudStream caches by URL; WebP reduces transfer size on first load and on cache misses.
        // Keep the dimensions large enough for both poster cards and the blurred detail background.
        return "https://wsrv.nl/?url=${encoded}&${params}&output=webp&q=85"
    }

    private fun text(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value.trim()
            if (value is Number) return value.toString()
        }
        return ""
    }

    private fun names(obj: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is JSONArray) {
                val found = (0 until value.length()).mapNotNull { index ->
                    when (val item = value.opt(index)) {
                        is String -> item.trim().takeIf { it.isNotBlank() }
                        is JSONObject -> text(item, "name", "fullName", "title", "displayName")
                            .takeIf { it.isNotBlank() }
                        else -> null
                    }
                }.distinct()
                if (found.isNotEmpty()) return found
            } else if (value is String && value.isNotBlank()) {
                return value.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    private fun cover(obj: JSONObject): String {
        val direct = text(obj, "coverUrl", "image", "imageUrl", "thumbnail", "productImage")
        if (direct.startsWith("https://") || direct.startsWith("http://")) return direct
        val coverValue = obj.opt("cover")
        if (coverValue is String && (coverValue.startsWith("https://") || coverValue.startsWith("http://"))) {
            return coverValue
        }
        val images = obj.optJSONArray("productImages")
        if (images != null) {
            for (i in 0 until images.length()) {
                val image = images.opt(i)
                val url = when (image) {
                    is String -> image
                    is JSONObject -> text(image, "url", "imageUrl", "uri")
                    else -> ""
                }
                if (url.startsWith("https://") || url.startsWith("http://")) return url
            }
        }
        val coverObject = obj.optJSONObject("cover")
        if (coverObject != null) {
            val url = text(coverObject, "url", "imageUrl", "uri")
            if (url.startsWith("https://") || url.startsWith("http://")) return url
        }
        return ""
    }

    private fun record(obj: JSONObject): AudibleBook? {
        val asin = text(obj, "asin", "ASIN", "asinId").uppercase()
        val title = text(obj, "title", "name")
        if (!asin.matches(Regex("[A-Z0-9]{10}")) || title.isBlank()) return null

        val duration = text(obj, "lengthMinutes", "runtimeLengthMin", "durationMinutes")
            .toIntOrNull() ?: text(obj, "length").toIntOrNull()
        val seriesValue = obj.opt("series")
        val seriesFromValue = when (seriesValue) {
            is JSONArray -> (0 until seriesValue.length()).mapNotNull { index ->
                val item = seriesValue.optJSONObject(index) ?: return@mapNotNull null
                val name = text(item, "name", "title")
                if (name.isBlank()) null else {
                    val position = text(item, "position", "sequence")
                    if (position.isBlank()) name else "$name ($position)"
                }
            }.distinct().joinToString(", ")
            is JSONObject -> text(seriesValue, "name", "title")
            else -> ""
        }
        val series = text(obj, "seriesName", "seriesTitle").ifBlank { seriesFromValue }
        val audibleUrl = text(obj, "url", "audibleUrl", "productUrl", "link")
            .takeIf { it.startsWith("https://") }
            ?: "https://www.audible.com/pd/${asin}"

        return AudibleBook(
            asin = asin,
            title = title,
            authors = names(obj, "authors", "authorNames", "author").joinToString(", "),
            narrators = names(obj, "narrators", "narratorNames", "narrator").joinToString(", "),
            cover = cover(obj),
            description = text(obj, "description", "summary", "publisherSummary"),
            releaseDate = text(obj, "releaseDate", "publicationDate", "publishedDate"),
            publisher = text(obj, "publisher", "publisherName"),
            durationMinutes = duration,
            genres = names(obj, "genres", "tags", "categoryNames"),
            plans = names(obj, "plans", "planNames"),
            series = series,
            audibleUrl = audibleUrl
        )
    }

    private fun records(json: String): List<AudibleBook> {
        val trimmed = json.trim()
        val objects = when {
            trimmed.startsWith("[") -> {
                val array = JSONArray(trimmed)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            }
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                val nested = sequenceOf("books", "results", "items", "matches", "data")
                    .mapNotNull { root.optJSONArray(it) }
                    .firstOrNull()
                if (nested != null) {
                    (0 until nested.length()).mapNotNull { nested.optJSONObject(it) }
                } else {
                    val nestedBook = root.optJSONObject("book")
                    listOfNotNull(nestedBook ?: root.takeIf { it.has("asin") || it.has("ASIN") })
                }
            }
            else -> emptyList()
        }
        return objects.mapNotNull(::record).distinctBy { it.asin }
    }

    private suspend fun getJson(url: String): String =
        metadataRequestSemaphore.withPermit { app.get(url).text }

    private suspend fun fetchList(urls: List<String>): List<AudibleBook> {
        for (url in urls) {
            val result = try {
                records(getJson(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun apiUrl(path: String, query: String = ""): String =
        "${API_ROOT}${path}?region=${region}&${query}".trimEnd('?', '&')

    private data class ShelfSpec(
        val title: String,
        // Variants are tried in order; the first one with results supplies the row.
        val variants: List<List<Pair<String, String>>>
    )

    private fun dbBookUrl(page: Int, filters: List<Pair<String, String>>): String {
        val params = mutableListOf(
            "region" to region,
            "sort" to "rating",
            "order" to "desc",
            "limit" to "24",
            "page" to page.coerceAtLeast(1).toString()
        )
        params.addAll(filters)
        val query = params.joinToString("&") { (key, value) ->
            "$key=${value.encodeUri()}"
        }
        return "${API_ROOT}/db/book?${query}"
    }

    private suspend fun fetchShelf(spec: ShelfSpec, page: Int): HomePageList? {
        if (!AudibleSettings.isRowEnabled(spec.title)) return null
        for (variant in spec.variants) {
            val books = fetchList(listOf(dbBookUrl(page, variant)))
            if (books.isNotEmpty()) {
                return HomePageList(spec.title, books.map { it.card(this) }, false)
            }
        }
        return null
    }

    private data class SearchShelfSpec(
        val title: String,
        // Each entry is tried until one query returns books.
        val queryVariants: List<List<Pair<String, String>>>
    )

    private fun liveSearchUrl(query: List<Pair<String, String>>, page: Int): String {
        // Libex's live Audible search uses a zero-based page parameter (0-9).
        val apiPage = (page - 1).coerceAtLeast(0).coerceAtMost(9)
        val params = mutableListOf(
            "region" to region,
            "limit" to "24",
            "page" to apiPage.toString()
        )
        params.addAll(query)
        val encoded = params.joinToString("&") { (key, value) ->
            "$key=${value.encodeUri()}"
        }
        return "${API_ROOT}/search?${encoded}"
    }

    private suspend fun fetchSearchShelf(spec: SearchShelfSpec, page: Int): HomePageList? {
        // Libex live search is limited to zero-based pages 0-9.
        if (!AudibleSettings.isRowEnabled(spec.title) || page !in 1..10) return null
        val books = fetchList(spec.queryVariants.map { liveSearchUrl(it, page) })
        if (books.isEmpty()) return null
        return HomePageList(spec.title, books.map { it.card(this) }, false)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val currentPage = page.coerceAtLeast(1)

            val genreShelves = listOf(
                ShelfSpec("Science Fiction", listOf(listOf("genre" to "Science Fiction"))),
                ShelfSpec("Fantasy", listOf(listOf("genre" to "Fantasy"))),
                ShelfSpec("Mystery, Thriller & Suspense", listOf(listOf("genre" to "Mystery"))),
                ShelfSpec("Romance", listOf(listOf("genre" to "Romance"))),
                ShelfSpec("Horror", listOf(listOf("genre" to "Horror"))),
                ShelfSpec("Historical Fiction", listOf(listOf("genre" to "Historical Fiction"))),
                ShelfSpec("Biography & Memoir", listOf(listOf("genre" to "Biography"))),
                ShelfSpec("Self-Help & Personal Development", listOf(listOf("genre" to "Self-Help"))),
                ShelfSpec("Business & Careers", listOf(listOf("genre" to "Business"))),
                ShelfSpec("Teen & Young Adult", listOf(listOf("genre" to "Young Adult"))),
                ShelfSpec("Children & Family", listOf(listOf("genre" to "Children"))),
                ShelfSpec("Religion & Spirituality", listOf(listOf("genre" to "Religion")))
            )

            val typeShelves = listOf(
                ShelfSpec("Abridged Audiobooks", listOf(listOf("book_format" to "abridged"))),
                ShelfSpec("Unabridged Audiobooks", listOf(listOf("book_format" to "unabridged"))),
                ShelfSpec("Virtual Voice (AI Narrated)", listOf(listOf("is_vvab" to "true"))),
                ShelfSpec("Short Audiobooks (Under 3 Hours)", listOf(listOf("shorter_than" to "180"))),
                ShelfSpec("Long Listens (10+ Hours)", listOf(listOf("longer_than" to "600")))
            )

            val discoveryShelves = listOf(
                SearchShelfSpec(
                    "Full Cast & Ensemble",
                    listOf(
                        listOf("narrator" to "Full Cast"),
                        listOf("keywords" to "full cast"),
                        listOf("title" to "full cast"),
                        listOf("keywords" to "ensemble cast")
                    )
                ),
                SearchShelfSpec(
                    "Collections & Box Sets",
                    listOf(
                        listOf("keywords" to "collection audiobook"),
                        listOf("keywords" to "box set audiobook"),
                        listOf("title" to "collection"),
                        listOf("keywords" to "complete collection")
                    )
                ),
                SearchShelfSpec(
                    "Dramatizations & Audio Dramas",
                    listOf(
                        listOf("keywords" to "dramatization"),
                        listOf("keywords" to "dramatized"),
                        listOf("keywords" to "audio drama"),
                        listOf("keywords" to "radio play"),
                        listOf("title" to "dramatization")
                    )
                )
            )

            // Launch every enabled row together. getJson() applies a shared four-request
            // limit, so rows no longer wait for earlier batches but the API is not flooded.
            val rows = coroutineScope {
                val newReleasesJob = async {
                    if (!AudibleSettings.isShelfEnabled("new_releases")) {
                        null
                    } else {
                        fetchList(
                            listOf(apiUrl("/db/new-releases", "days=90&limit=24&page=${currentPage}"))
                        ).takeIf { it.isNotEmpty() }?.let { books ->
                            HomePageList("New Releases", books.map { it.card(this@AudibleProvider) }, false)
                        }
                    }
                }

                val popularJob = async {
                    if (!AudibleSettings.isShelfEnabled("popular")) {
                        null
                    } else {
                        fetchList(
                            listOf(apiUrl("/db/book", "sort=rating&order=desc&limit=24&page=${currentPage}"))
                        ).takeIf { it.isNotEmpty() }?.let { books ->
                            HomePageList("Popular Audiobooks", books.map { it.card(this@AudibleProvider) }, false)
                        }
                    }
                }

                val recentlyIndexedJob = async {
                    if (!AudibleSettings.isShelfEnabled("recent_metadata")) {
                        null
                    } else {
                        fetchList(
                            listOf(apiUrl("/db/book", "sort=updatedAt&order=desc&limit=24&page=${currentPage}"))
                        ).takeIf { it.isNotEmpty() }?.let { books ->
                            HomePageList(
                                "Recently Updated Metadata",
                                books.map { it.card(this@AudibleProvider) },
                                false
                            )
                        }
                    }
                }

                val catalogueJobs = (genreShelves + typeShelves).map { shelf ->
                    async { fetchShelf(shelf, currentPage) }
                }
                val discoveryJobs = discoveryShelves.map { shelf ->
                    async { fetchSearchShelf(shelf, currentPage) }
                }

                val primaryRows = listOf(newReleasesJob, popularJob, recentlyIndexedJob)
                    .awaitAll()
                    .filterNotNull()
                primaryRows +
                    catalogueJobs.awaitAll().filterNotNull() +
                    discoveryJobs.awaitAll().filterNotNull()
            }

            val arrangedRows = AudibleSettings.arrangeHomeRows(rows)
            newHomePageResponse(arrangedRows, currentPage < 100 && arrangedRows.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return null
        return try {
            val q = query.encodeUri()
            val currentPage = page.coerceAtLeast(1)
            // Libex live search accepts zero-based pages 0-9; CloudStream pages are one-based.
            if (currentPage > 10) return null
            val apiPage = currentPage - 1
            val limit = 24
            val candidates = mutableListOf(
                "${API_ROOT}/search?keywords=${q}&region=${region}&limit=${limit}&page=${apiPage}",
                "${API_ROOT}/search?title=${q}&region=${region}&limit=${limit}&page=${apiPage}"
            )
            // Quick search has no page parameter, so use it only as a first-page fallback.
            if (currentPage == 1) {
                candidates += "${API_ROOT}/quick-search?keywords=${q}&region=${region}"
            }
            fetchList(candidates).map { it.card(this) }.toNewSearchResponseList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // CloudStream renders plot text as HTML, so explicit markup is needed for reliable line breaks.
    // Escape all API-supplied text before inserting it into the markup.
    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun synopsisHtml(value: String): String =
        escapeHtml(value)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n\n", "<br><br>")
            .replace("\n", "<br>")

    private fun recordingCard(
        recording: AudioAZPublicDomainAdapter.Recording,
        fallbackPoster: String? = null
    ) = newMovieSearchResponse(recording.displayTitle, recording.url, TvType.AudioBook) {
        posterUrl = recording.poster ?: fallbackPoster
    }

    override suspend fun load(url: String): LoadResponse {
        // Recording cards point back to AudioAZ pages; route them through this provider
        // so their verified metadata and playback are handled inside CloudStream.
        if (audioSourceAdapter.isAudioAZBookUrl(url)) {
            val recording = audioSourceAdapter.inspectRecording(url)
                ?: throw ErrorLoadingException(
                    "This page does not expose a verified public-domain recording from an approved source."
                )
            val alternatives = try {
                audioSourceAdapter.findMatches(recording.title, recording.author)
                    .filterNot { it.url == recording.url }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            return newMovieLoadResponse(
                recording.displayTitle,
                recording.url,
                TvType.AudioBook,
                recording.url
            ) {
                plot = buildString {
                    append("<b>Verified public-domain recording</b><br>")
                    append(synopsisHtml(recording.summaryText))
                    append("<br><br><b>Source page</b><br>")
                    append(escapeHtml(recording.url))
                    if (alternatives.isNotEmpty()) {
                        append("<br><br><b>OTHER MATCHING RECORDINGS</b><br>")
                        append(alternatives.joinToString("<br><br>") {
                            "<b>" + escapeHtml(it.title) + "</b>" +
                                (if (it.narrators.isBlank()) "" else "<br>Narrator: " + escapeHtml(it.narrators)) +
                                (if (it.duration.isBlank()) "" else "<br>Duration: " + escapeHtml(it.duration)) +
                                "<br><i>Select its recording card below to open that version.</i>"
                        })
                    }
                }
                posterUrl = recording.poster
                backgroundPosterUrl = recording.poster
                duration = recording.durationMinutes
                recommendations = alternatives.map { recordingCard(it, recording.poster) }
            }
        }

        val asin = Regex("/book/([A-Za-z0-9]{10})")
            .find(url)?.groupValues?.getOrNull(1)?.uppercase()
            ?: throw ErrorLoadingException("Invalid Audible book identifier.")

        return try {
            val book = fetchList(
                listOf("${API_ROOT}/book/${asin}?region=${region}&cache=true")
            ).firstOrNull { it.asin == asin }
                ?: throw ErrorLoadingException("Libex did not return this book. Try again later.")

            val recordings = try {
                audioSourceAdapter.findMatches(book.title, book.authors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            audioSourceCache[asin] = recordings

            val metadataLines = buildList {
                add("<b>Author:</b> ${escapeHtml(book.authors.ifBlank { "Not listed" })}")
                add("<b>Narrator:</b> ${escapeHtml(book.narrators.ifBlank { "Not listed" })}")
                if (book.series.isNotBlank()) add("<b>Series:</b> ${escapeHtml(book.series)}")
                if (book.releaseDate.isNotBlank()) {
                    add("<b>Release date:</b> ${escapeHtml(book.releaseDate)}")
                }
                if (book.publisher.isNotBlank()) {
                    add("<b>Publisher:</b> ${escapeHtml(book.publisher)}")
                }
                if (book.durationMinutes != null && book.durationMinutes > 0) {
                    add("<b>Duration:</b> ${book.durationMinutes / 60}h ${book.durationMinutes % 60}m")
                }
                if (book.genres.isNotEmpty()) {
                    add("<b>Genres:</b> ${escapeHtml(book.genres.joinToString(", "))}")
                }
                if (book.plans.isNotEmpty()) {
                    add("<b>Audible plans:</b> ${escapeHtml(book.plans.joinToString(", "))}")
                }
            }

            val description = buildString {
                append("<b>Book information</b><br>")
                append(metadataLines.joinToString("<br>"))
                append("<br><br><b>Synopsis</b><br>")
                append(synopsisHtml(book.description.ifBlank { "No description available." }))
                if (recordings.isNotEmpty()) {
                    append("<br><br><b>VERIFIED PUBLIC-DOMAIN RECORDINGS</b><br>")
                    append(recordings.joinToString("<br><br>") { recording ->
                        buildString {
                            append("<b>").append(escapeHtml(recording.title)).append("</b>")
                            if (recording.narrators.isNotBlank()) {
                                append("<br>Narrator: ").append(escapeHtml(recording.narrators))
                            }
                            if (recording.language.isNotBlank()) {
                                append("<br>Language: ").append(escapeHtml(recording.language))
                            }
                            if (recording.duration.isNotBlank()) {
                                append("<br>Duration: ").append(escapeHtml(recording.duration))
                            }
                            if (recording.releaseDate.isNotBlank()) {
                                append("<br>Release date: ").append(escapeHtml(recording.releaseDate))
                            }
                            append("<br><i>Select the matching recording card below to open this version in CloudStream.</i>")
                        }
                    })
                } else {
                    append("<br><br><i>No exact public-domain recording match was verified from AudioAZ. Catalogue metadata remains available.</i>")
                }
                append("<br><br><b>Audible listing</b><br>")
                append(escapeHtml(book.audibleUrl))
            }

            newMovieLoadResponse(book.title, url, TvType.AudioBook, asin) {
                this.plot = description
                this.posterUrl = displayCover(book.cover)
                this.tags = book.genres
                this.duration = book.durationMinutes
                this.backgroundPosterUrl = displayCover(book.cover)
                recommendations = recordings.map { recordingCard(it, displayCover(book.cover)) }
            }
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Unable to load Audible metadata from Libex. Try again later.")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // A version card stores its verified AudioAZ page URL. The main Audible card
        // stores an ASIN, in which case resolve the matching public-domain recordings.
        if (audioSourceAdapter.isAudioAZBookUrl(data)) {
            return audioSourceAdapter.loadLinks(data, subtitleCallback, callback)
        }

        val asin = data.trim().uppercase()
        if (!asin.matches(Regex("[A-Z0-9]{10}"))) return false

        val recordings = audioSourceCache[asin] ?: run {
            val book = fetchList(
                listOf("${API_ROOT}/book/${asin}?region=${region}&cache=true")
            ).firstOrNull { it.asin == asin } ?: return false
            val matches = try {
                audioSourceAdapter.findMatches(book.title, book.authors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
                emptyList()
            }
            audioSourceCache[asin] = matches
            matches
        }

        var found = false
        recordings.take(6).forEach { recording ->
            if (audioSourceAdapter.loadLinks(recording.url, subtitleCallback) { link ->
                    callback(link)
                    found = true
                }
            ) {
                found = true
            }
        }
        return found
    }

    companion object {
        private const val API_ROOT = "https://libexdb.com"
    }
}
