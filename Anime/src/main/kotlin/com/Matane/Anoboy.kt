package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "Anime Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "?status=ongoing" to "Sedang Tayang",
        "?status=completed" to "Tamat",
        "?status=upcoming" to "Akan Datang",
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
        val document = app.get("$mainUrl/${request.data}&page=$page").documentLarge
        val home = document.select("article, div.anime-card, a[href*=/episode/]").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.attr("href")).takeIf { it.startsWith("http") } ?: return null
        val title = this.selectFirst("h2, h3, .title")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?.ifEmpty { this.selectFirst("img")?.attr("data-litespeed-src") }
                ?.ifEmpty { this.selectFirst("img")?.attr("src") }
                ?.takeIf { !it.startsWith("data:image") }
        )
        val ratingText = this.selectFirst(".rating, .score")?.text()?.trim()
        val score = ratingText?.toDoubleOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (score != null) this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoints = listOf(
            "$mainUrl/?s=$query",
            "$mainUrl/search/$query",
            "$mainUrl/?search=$query"
        )
        for (endpoint in endpoints) {
            try {
                val doc = app.get(endpoint).documentLarge
                val results = doc.select("article, div.anime-card, a[href*=/episode/]")
                    .mapNotNull { it.toSearchResult() }
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                continue
            }
        }
        // Fallback: filter dari halaman utama
        val doc = app.get(mainUrl).documentLarge
        return doc.select("article, div.anime-card, a[href*=/episode/]")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Anime"
        val posterUrl = fixUrlNull(
            document.selectFirst("div.thumb img, .poster img")?.attr("data-src")
                ?.ifEmpty { document.selectFirst("div.thumb img, .poster img")?.attr("data-litespeed-src") }
                ?.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                ?.ifEmpty { document.selectFirst("div.thumb img, .poster img")?.attr("src") }
                ?.takeIf { !it.isNullOrEmpty() && !it.startsWith("data:image") }
        )

        val plot = document.selectFirst("div.entry-content, .synopsis, .description")?.text()?.trim()
            ?.substringBefore("Genre")?.trim()

        val genres = document.select(".genres a, .genre-tags a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val statusText = document.selectFirst(".status, .status-label")?.text()?.trim()
        val status = when {
            statusText?.contains("Ongoing", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
            statusText?.contains("Tamat", ignoreCase = true) == true -> ShowStatus.Completed
            statusText?.contains("Upcoming", ignoreCase = true) == true -> ShowStatus.Upcoming
            else -> null
        }

        val ratingText = document.selectFirst(".rating, .score")?.text()?.trim()
        val score = ratingText?.toDoubleOrNull()

        val trailerUrl = document.selectFirst("a[href*=youtube.com], a[href*=youtu.be]")?.attr("href")

        val episodeElements = document.select("div.eplister li a, .episodes li a, .episode-list a")
        val episodes = episodeElements.mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNumText = ep.selectFirst(".ep-num, .episode-number")?.text()?.toIntOrNull()
            val epTitle = ep.text().trim()
            newEpisode(epUrl) {
                this.episode = epNumText
                this.name = epTitle
            }
        }.reversed()

        val type = if (episodes.size <= 1) TvType.Movie else TvType.Anime

        return if (type == TvType.TvSeries || episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                this.status = status
                if (score != null) this.score = Score.from10(score)
                if (!trailerUrl.isNullOrEmpty()) addTrailer(trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                this.status = status
                if (score != null) this.score = Score.from10(score)
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

        // Pola 1: option[value] base64 (sama seperti Anichin/Donghuaid)
        document.select("div.mobius select.mirror option, select.server option, option[value*=eyJ]")
            .mapNotNull { opt ->
                val b64 = opt.attr("value").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                runCatching {
                    val decoded = Jsoup.parse(base64Decode(b64))
                    val iframe = decoded.selectFirst("iframe")?.attr("src")
                    fixUrl(iframe ?: "")
                }.getOrNull()
            }
            .forEach { embedUrl ->
                val resolvedUrl = if (embedUrl.startsWith(mainUrl)) {
                    app.get(embedUrl).document.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) } ?: embedUrl
                } else {
                    embedUrl
                }
                runCatching {
                    loadExtractor(resolvedUrl, data, subtitleCallback, callback)
                }
            }

        // Pola 2: iframe langsung di halaman
        document.select("iframe[src]").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
                .takeIf { it.isNotEmpty() && !it.startsWith(mainUrl) } ?: return@forEach
            runCatching {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}