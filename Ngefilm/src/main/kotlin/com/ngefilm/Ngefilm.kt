package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import org.jsoup.nodes.Element

class Ngefilm : MainAPI() {
    override var mainUrl = "https://new39.ngefilm.site"
    private var directUrl: String? = null
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private suspend fun updateToLatestDomain() {
        try {
            if (mainUrl.contains("ngefilm")) {
                val doc = app.get(mainUrl).document
                val newLink = doc.selectFirst("a[href*='ngefilm'], strong a, p a[href^='https://']")?.attr("href")
                if (!newLink.isNullOrBlank() && newLink.contains("ngefilm")) {
                    mainUrl = newLink.substringBeforeLast("/", "").substringBefore("?")
                }
            }
        } catch (_: Exception) {}
    }

    override val mainPage = mainPageOf(
        "/page/%d/?s&search=advanced&post_type=movie&index&orderby&genre&movieyear&country&quality=" to "Movies Terbaru",
        "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=&quality=" to "Series Terbaru",
        "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=drakor&movieyear=&country=&quality=" to "Series Korea",
        "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality=" to "Series Indonesia",
        "country/usa/page/%d/" to "Film Barat",
        "country/indonesia/page/%d/" to "Film Indonesia",
        "country/malaysia/page/%d/" to "Film Malaysia",
        "country/philippines/page/%d/" to "Film Philippines",
        "country/japan/page/%d/" to "Film Jepang",
        "country/china/page/%d/" to "Film China",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateToLatestDomain()
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a")
            .text().trim().replace("-", "")
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()

        return if (quality.isEmpty()) {
            val episode = Regex("Episode\\s?([0-9]+)")
                .find(title)
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.get("$mainUrl/page/$page/?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
        return document.select("article.has-post-thumbnail").mapNotNull { it.toSearchResult() }.toNewSearchResponseList()
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr().fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document
        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.trim()
            .toString()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()
        val tags = document.select("strong:contains(Genre) ~ a").eachText()
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a")
            .text()
            .trim()
            .toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
            ?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()
        val recommendations = document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a")
                .map { eps ->
                    val href = fixUrl(eps.attr("href"))
                    val name = eps.text()
                    val episode = name.split(" ")
                        .lastOrNull()
                        ?.filter { it.isDigit() }
                        ?.toIntOrNull()
                    val season = name.split(" ")
                        .firstOrNull()
                        ?.filter { it.isDigit() }
                        ?.toIntOrNull()
                    newEpisode(href) {
                        this.name = name
                        this.episode = episode
                        this.season = if (name.contains(" ")) season else null
                    }
                }
                .filter { it.episode != null }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
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
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        val playerTabs = document.select("ul.muvipro-player-tabs li a, .gmr-player-nav li a, div.tab-links a")
        if (playerTabs.isNotEmpty()) {
            playerTabs.amap { ele ->
                val tabHref = fixUrl(ele.attr("href"))
                try {
                    val iframeDoc = app.get(tabHref).document
                    val iframe = iframeDoc.selectFirst("div.gmr-embed-responsive iframe, .embed-responsive iframe, iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                    
                    if (!iframe.isNullOrBlank()) {
                        processIframe(iframe, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }

        if (!id.isNullOrEmpty()) {
            document.select("div.tab-content-ajax, div.player-tab-content").amap { ele ->
                try {
                    val server = app.post(
                        "$directUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to ele.attr("id"),
                            "post_id" to "$id"
                        ),
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                        .document
                        .selectFirst("iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) } ?: return@amap
                    
                    processIframe(server, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        document.select("div.gmr-embed-responsive iframe, .player-embed iframe").mapNotNull { it.getIframeAttr() }
            .forEach { iframe ->
                processIframe(httpsify(iframe), subtitleCallback, callback)
            }

        return true
    }

    private suspend fun processIframe(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val currentDirectUrl = directUrl ?: mainUrl
        if (url.contains("rpmlive") || url.contains("playerngefilm") || url.contains("api/v1/video") || url.contains("ngefilm")) {
            try {
                val videoId = Regex("[?&]id=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
                    ?: Regex("/(?:v|embed|video)/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
                    ?: url.substringAfterLast("/").substringBefore("?")

                val host = try { URI(url).host } catch (_: Exception) { null } ?: "playerngefilm21.rpmlive.online"
                val refererHost = try { URI(currentDirectUrl).host } catch (_: Exception) { null } ?: "new39.ngefilm.site"

                val apiUrl = if (url.contains("/api/v1/video")) {
                    url
                } else {
                    "https://$host/api/v1/video?id=$videoId&w=1536&h=864&r=$refererHost"
                }

                val headers = mapOf(
                    "Referer" to "https://$refererHost/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                )

                val responseText = app.get(apiUrl, headers = headers).text

                val streamUrls = Regex("https?://[^\"'\\\\\\s]+?\\.(?:m3u8|mp4)[^\"'\\\\\\s]*").findAll(responseText)
                    .map { it.value.replace("\\/", "/") }
                    .toSet()

                val urlRegex = Regex("[\"'](?:file|url|src|data|stream|link)[\"']\\s*:\\s*[\"'](https?://[^\"']+)[\"']")
                val extractedFromKeys = urlRegex.findAll(responseText)
                    .map { it.groupValues[1].replace("\\/", "/") }
                    .toSet()

                val allStreams = (streamUrls + extractedFromKeys).filter { it.startsWith("http") }

                if (allStreams.isNotEmpty()) {
                    allStreams.forEach { streamUrl ->
                        if (streamUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = this.name,
                                streamUrl = streamUrl,
                                referer = "https://$refererHost/",
                                headers = headers
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = streamUrl,
                                  //  quality = Qualities.Unknown.value
                                ) {
                                    this.headers = headers
                                    this.isM3u8 = false
                                }
                            )
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        loadExtractor(url, "$currentDirectUrl/", subtitleCallback, callback)
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) {
            url
        }
    }
}
