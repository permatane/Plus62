package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.base64Decode

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "?status=ongoing" to "Sedang Tayang",
        "?status=completed" to "Tamat",
        "?order=popular" to "Paling Populer",
        "?genre=action" to "Action",
        "?genre=fantasy" to "Fantasy",
        "?genre=comedy" to "Comedy",
        "?genre=romance" to "Romance",
        "?genre=isekai" to "Isekai",
        "?genre=drama" to "Drama",
        "?genre=adventure" to "Adventure"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "$mainUrl/page/$page/${request.data}$sep"
        }
        val document = app.get(url).documentLarge

        // ✅ SELEKTOR TEPAT SESUAI HTML ASLI: article.bs
        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }

        // Deteksi halaman berikutnya
        val hasNext = document.select("a.next, .nav-next, .page-nav a:contains(Next)").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // ✅ Sesuai struktur: article.bs > div.bsx > a
        val link = this.selectFirst("div.bsx > a") ?: return null
        val href = fixUrl(link.attr("href")).takeIf { it.startsWith("http") } ?: return null

        // ✅ Judul dari h2[itemprop=headline] atau div.tt
        val title = this.selectFirst("h2[itemprop=headline], div.tt")?.text()?.trim()
            ?.substringBefore("Episode")?.trim()
            ?: return null

        // ✅ Poster dari img[src] — TIDAK pakai data-src!
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotEmpty() && !it.startsWith("data:image") }
        )

        // ✅ Info episode
        val epText = this.selectFirst("span.epx")?.text()?.trim()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.ep = epText
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$mainUrl/?s=$query"
        return try {
            val doc = app.get(endpoint).documentLarge
            doc.select("article.bs").mapNotNull { it.toSearchResult() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val title = document.selectFirst("h1")?.text()?.trim()?.substringBefore("Episode")?.trim() ?: "Anime"

        // Poster halaman detail
        val posterUrl = fixUrlNull(
            document.selectFirst("div.thumb img, .poster img, img.wp-post-image")?.attr("src")
                ?.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                ?.takeIf { !it.isNullOrEmpty() && !it.startsWith("data:image") }
        )

        val plot = document.selectFirst(".synopsis, .description, div.entry-content")?.text()?.trim()
            ?.substringBefore("Genre")?.trim()

        val genres = document.select(".genres a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val statusText = document.selectFirst(".status, .post-status")?.text()?.trim()
        val showStatus = when {
            statusText?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
            statusText?.contains("Tamat", ignoreCase = true) == true -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }

        val scoreText = document.selectFirst(".score, .rating")?.text()?.trim()?.toDoubleOrNull()
        val trailerUrl = document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("src")
            ?: document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("href")

        // Daftar episode
        val episodes = document.select("div.eplister li a, .episodios li a, .episodes a").mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNum = ep.selectFirst(".ep-num, .episode-number")?.text()?.toIntOrNull()
            newEpisode(epUrl) {
                this.episode = epNum
            }
        }.reversed()

        val isMovie = episodes.size <= 1

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                this.status = showStatus
                if (scoreText != null) this.score = Score.from10(scoreText)
                if (!trailerUrl.isNullOrEmpty()) addTrailer(trailerUrl)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                this.status = showStatus
                if (scoreText != null) this.score = Score.from10(scoreText)
                if (!trailerUrl.isNullOrEmpty()) addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Server base64 (sama seperti Anichin)
        document.select("select option[value*=eyJ], .mirror option").forEach { opt ->
            val b64 = opt.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            runCatching {
                val decoded = Jsoup.parse(base64Decode(b64))
                val iframe = decoded.selectFirst("iframe")?.attr("src") ?: return@forEach
                loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
            }
        }

        // Iframe langsung
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")).takeIf { it.isNotEmpty() && !it.startsWith(mainUrl) }
                ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
