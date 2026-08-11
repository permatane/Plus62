package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Donghuaid : Anichin() {
    override var mainUrl              = "https://donghuaid.live"
    override var name                 = "Donghua DonghuaId"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)


    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?status=&type=&order=popular" to "Paling Populer",
        "/anime/?status=&type=movie&sub=" to "Movies",
        "/anime/?status=completed&type=&order=" to "Complete",

    )

   override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").documentLarge
        val home = document.select("div.listupd > article")
            .mapNotNull { it.toSearchResultFixed() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    // ==========================================
    // 🔥 FIX #2: search — POSTER dari data-src
    // ==========================================
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("${mainUrl}/page/$page/?s=$query").documentLarge
        return document.select("div.listupd > article")
            .mapNotNull { it.toSearchResultFixed() }
            .toNewSearchResponseList()
    }

    // ==========================================
    // Converter: article -> SearchResponse
    // Poster: data-src > data-litespeed-src > src (skip data:image/*)
    // ==========================================
    private fun Element.toSearchResultFixed(): SearchResponse? {
        val linkEl = selectFirst("div.bsx > a") ?: return null
        val title = linkEl.attr("title").trim().ifEmpty { return null }
        val href = fixUrl(linkEl.attr("href"))

        val imgEl = linkEl.selectFirst("img")
        val posterUrl = imgEl?.let {
            it.attr("data-src")
                .ifEmpty { it.attr("data-litespeed-src") }
                .ifEmpty { it.attr("src") }
                .takeIf { s -> s.isNotEmpty() && !s.startsWith("data:image") }
        }?.let { fixUrlNull(it) }

        val isMovie = href.contains("/movie/") || title.contains("Movie", true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        return if (isMovie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ==========================================
    // 🔥 FIX #3: load — POSTER HALAMAN DETAIL
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")

        // ✅ POSTER: data-src > data-litespeed-src > src (skip SVG) > og:image
        val thumbImg = document.selectFirst("div.thumb img")
        val poster = thumbImg?.let {
            it.attr("data-src")
                .ifEmpty { it.attr("data-litespeed-src") }
                .ifEmpty { it.attr("src") }
                .takeIf { s -> s.isNotEmpty() && !s.startsWith("data:image") }
        }?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        // Sinopsis
        val description = document.selectFirst("div.entry-content, .wp-content, [class*=desc]")
            ?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Info dasar
        val infoText = document.selectFirst(".spe, .info-content, .post-content")?.text() ?: ""
        val isMovie = infoText.contains("Movie", true) ||
                url.contains("/movie/") ||
                document.selectFirst("span:contains(Movie)") != null

        val duration = document.selectFirst(".spe:contains(Duration), .duration")?.text()
            ?.replace("Duration:", "")?.trim()

        val releaseDate = document.selectFirst(".spe:contains(Released), .date")?.text()
            ?.replace("Released:", "")?.trim()
        val year = Regex("""(\d{4})""").find(releaseDate ?: infoText)
            ?.groupValues?.get(1)?.toIntOrNull()

        val tvTag = if (isMovie) TvType.Movie else TvType.TvSeries

        // ---- EPISODE LIST (TV Series) ----
        val episodeElements = document.select("div.eplister > ul > li")

        if (tvTag == TvType.TvSeries && episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull { info ->
                val href1 = info.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                // Episode poster juga lazy load
                val epPoster = info.selectFirst("a img")?.let {
                    it.attr("data-src")
                        .ifEmpty { it.attr("data-litespeed-src") }
                        .ifEmpty { it.attr("src") }
                        .takeIf { s -> s.isNotEmpty() && !s.startsWith("data:image") }
                }
                val epnum = info.selectFirst("div.epl-num, .num, [class*=epl]")
                    ?.text()?.let { t ->
                        Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                    }
                val epTitle = info.selectFirst("div.epl-title, .ep-title, [class*=title]")
                    ?.text()?.trim()
                    ?: "Episode $epnum"

                newEpisode(href1) {
                    this.episode = epnum
                    this.name = epTitle
                    this.posterUrl = epPoster
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
                this.year = year

            }
        }

        // ---- MOVIE ----
        val hrefEp = episodeElements.firstOrNull()?.selectFirst("a")?.attr("href") ?: url

        return newMovieLoadResponse(title, url, TvType.Movie, hrefEp) {
            this.posterUrl = poster
            this.plot = description
            this.year = year

        }
    }
   override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        document.select("div.mobius > select.mirror > option")
                .mapNotNull {
                    fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
                }
                .amap {
                    if (it.startsWith(mainUrl)) {
                        app.get(it, referer = "$mainUrl/").document.select("iframe").attr("src")
                    } else {
                        it
                    }
                }
                .amap { loadExtractor(httpsify(it), data, subtitleCallback, callback) }

        return true
    }
}


