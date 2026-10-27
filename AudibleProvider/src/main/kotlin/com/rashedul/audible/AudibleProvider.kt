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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Audible catalogue provider backed exclusively by Libex metadata.
 * Playback adapters will be added separately after the catalogue is verified.
 */
class AudibleProvider : MainAPI() {
    override var mainUrl = API_ROOT
    override var name = "Audible"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.AudioBook)

    private val region = "us"

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
                posterUrl = cover.ifBlank { null }
            }
    }

    private fun bookUrl(asin: String): String = "${API_ROOT}/book/${asin}?region=${region}"

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
        val seriesObj = obj.optJSONObject("series")
        val series = text(obj, "seriesName", "seriesTitle").ifBlank {
            seriesObj?.let { text(it, "title", "name") }.orEmpty()
        }
        val audibleUrl = text(obj, "url", "audibleUrl", "productUrl")
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

    private suspend fun getJson(url: String): String = app.get(url).text

    private suspend fun fetchList(urls: List<String>): List<AudibleBook> {
        for (url in urls) {
            val result = runCatching { records(getJson(url)) }
                .onFailure { logError(it) }
                .getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun apiUrl(path: String, query: String = ""): String =
        "${API_ROOT}${path}?region=${region}&${query}".trimEnd('?', '&')

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val rows = mutableListOf<HomePageList>()
            val newReleases = fetchList(
                listOf(apiUrl("/db/new-releases", "days=90&limit=24&page=${page}"))
            )
            if (newReleases.isNotEmpty()) {
                rows += HomePageList("New Releases", newReleases.map { it.card(this) }, false)
            }

            val popular = fetchList(
                listOf(apiUrl("/db/book", "sort=rating&order=desc&limit=24&page=${page}"))
            )
            if (popular.isNotEmpty()) {
                rows += HomePageList("Popular Audiobooks", popular.map { it.card(this) }, false)
            }

            val recentlyIndexed = fetchList(
                listOf(apiUrl("/db/book", "sort=updatedAt&order=desc&limit=24&page=${page}"))
            )
            if (recentlyIndexed.isNotEmpty()) {
                rows += HomePageList(
                    "Recently Updated Metadata",
                    recentlyIndexed.map { it.card(this) },
                    false
                )
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
            val q = query.encodeUri()
            val currentPage = page.coerceAtLeast(1)
            val limit = 24
            val candidates = listOf(
                "${API_ROOT}/search?keywords=${q}&region=${region}&limit=${limit}&page=${currentPage}",
                "${API_ROOT}/search?title=${q}&region=${region}&limit=${limit}&page=${currentPage}",
                "${API_ROOT}/quick-search?keywords=${q}&region=${region}"
            )
            fetchList(candidates).map { it.card(this) }.toNewSearchResponseList()
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val asin = Regex("/book/([A-Za-z0-9]{10})")
            .find(url)?.groupValues?.getOrNull(1)?.uppercase()
            ?: throw ErrorLoadingException("Invalid Audible book identifier.")

        return try {
            val book = fetchList(
                listOf("${API_ROOT}/book/${asin}?region=${region}&cache=true")
            ).firstOrNull { it.asin == asin }
                ?: throw ErrorLoadingException("Libex did not return this book. Try again later.")

            val description = buildString {
                appendLine("Author: " + book.authors.ifBlank { "Not listed" })
                appendLine("Narrator: " + book.narrators.ifBlank { "Not listed" })
                if (book.series.isNotBlank()) appendLine("Series: " + book.series)
                if (book.releaseDate.isNotBlank()) appendLine("Release date: " + book.releaseDate)
                if (book.publisher.isNotBlank()) appendLine("Publisher: " + book.publisher)
                if (book.durationMinutes != null && book.durationMinutes > 0) {
                    appendLine("Duration: ${book.durationMinutes / 60}h ${book.durationMinutes % 60}m")
                }
                if (book.genres.isNotEmpty()) appendLine("Genres: " + book.genres.joinToString(", "))
                if (book.plans.isNotEmpty()) appendLine("Audible plans: " + book.plans.joinToString(", "))
                appendLine()
                appendLine(book.description.ifBlank { "No description available." })
                appendLine()
                appendLine("Audible listing: ${book.audibleUrl}")
                append("Catalogue metadata provided by Libex. Audio playback sources are not yet integrated.")
            }

            newMovieLoadResponse(book.title, url, TvType.AudioBook, asin) {
                this.plot = description
                this.posterUrl = book.cover.ifBlank { null }
                this.tags = book.genres
                this.duration = book.durationMinutes
                this.backgroundPosterUrl = book.cover.ifBlank { null }
            }
        } catch (e: ErrorLoadingException) {
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
    ): Boolean = false

    companion object {
        private const val API_ROOT = "https://libexdb.com"
    }
}
