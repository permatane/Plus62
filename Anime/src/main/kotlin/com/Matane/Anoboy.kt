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

        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }
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
        val link = this.selectFirst("div.bsx > a") ?: return null
        val href = fixUrl(link.attr("href")).takeIf { it.startsWith("http") } ?: return null

        val title = this.selectFirst("h2[itemprop=headline], div.tt")?.text()?.trim()
            ?.substringBefore("Episode")?.trim() ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotEmpty() && !it.startsWith("data:image") }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
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

        val posterUrl = fixUrlNull(
            document.selectFirst("div.thumb img, .poster img, img.wp-post-image")?.attr("src")
                ?.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                ?.takeIf { !it.isNullOrEmpty() && !it.startsWith("data:image") }
        )

        val plot = document.selectFirst(".synopsis, .description, div.entry-content")?.text()?.trim()
            ?.substringBefore("Genre")?.trim()

        val genres = document.select(".genres a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val scoreText = document.selectFirst(".score, .rating")?.text()?.trim()?.toDoubleOrNull()
        val trailerUrl = document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("src")
            ?: document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("href")

        val episodes = mutableListOf<Episode>()

        // Daftar episode di halaman
        document.select("div.eplister li a, ul.episodios li a, div.episodios a, .ep-listing a, li.episod a").forEach { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            val epNumText = ep.selectFirst(".ep-num, .episode-number, .num, .eps-num")?.text()?.toIntOrNull()
                ?: ep.text().trim().filter { it.isDigit() }.toIntOrNull()
            if (epUrl.isNotEmpty()) {
                episodes.add(newEpisode(epUrl) {
                    this.episode = epNumText
                })
            }
        }

        // Jika tidak ada daftar → URL itu sendiri = Episode 1/N
        if (episodes.isEmpty()) {
            val epNum = url.substringAfter("episode-").substringBefore("-").filter { it.isDigit() }.toIntOrNull()
            episodes.add(newEpisode(url) {
                this.episode = epNum
            })
        }

        episodes.reverse()
        val isMovie = episodes.size <= 1 && !url.contains("/episode-", ignoreCase = true)

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                if (scoreText != null) this.score = Score.from10(scoreText)
                if (!trailerUrl.isNullOrEmpty()) addTrailer(trailerUrl)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
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
        var success = false

        // ✅ SERVER: Base64 decode → Blogger/GoogleVideo URL
        document.select("select.mirror option, select.server option, option[value*=eyJ]").forEach { opt ->
            val b64 = opt.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            runCatching {
                val decodedHtml = base64Decode(b64)
                val decodedDoc = Jsoup.parse(decodedHtml)
                var iframeSrc = decodedDoc.selectFirst("iframe")?.attr("src") ?: return@forEach

                // Perbaiki URL relatif
                iframeSrc = when {
                    iframeSrc.startsWith("//") -> "https:$iframeSrc"
                    iframeSrc.startsWith("/") -> fixUrl(iframeSrc)
                    else -> iframeSrc
                }

                if (iframeSrc.isNotEmpty()) {
                    success = true
                    // ⭐ Kirim ke extractor bawaan: BloggerExtractor
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }
        }

        // ✅ IFRAME LANGSUNG
        if (!success) {
            document.select("iframe[src]").forEach { iframe ->
                var src = iframe.attr("src")
                src = when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> fixUrl(src)
                    else -> src
                }
                if (src.isNotEmpty() && !src.startsWith(mainUrl)) {
                    success = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return success
    }
}
