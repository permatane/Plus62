package com.fufafilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element

class Fufafilm : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    // URL Portal Utama (Landing Page)
    override var mainUrl = "https://www.fufafilm.sbs"
    private var streamingUrl: String? = null  
    override var name = "FufaFilm LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9"
    )

    
    private suspend fun updateToStreamingDomain() {
        // Jika sudah mendapatkan streamingUrl, gunakan yang sudah ada
        if (streamingUrl != null) return

        try {
            val response = app.get(mainUrl, headers = headers, timeout = 20).document
            
            // Selector spesifik untuk tombol "KE HALAMAN Web LK21"
            val targetButton = response.selectFirst("a.green-button, a:contains(KE HALAMAN Web LK21)")
                ?: response.selectFirst("a.cta-button")
            
            val href = targetButton?.attr("href")

            if (!href.isNullOrBlank() && href.startsWith("https")) {
                // Bersihkan URL dari path atau query string (mengambil domain saja)
                streamingUrl = href.substringBeforeLast("/", "").substringBefore("?")
            }
        } catch (e: Exception) {
            // Fallback jika gagal scraping, gunakan mainUrl
            streamingUrl = mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "category/film/page/%d/" to "Semua Film",
        "country/usa/page/%d/" to "Bioskop Hollywood",
        "country/indonesia/page/%d/" to "Bioskop Indonesia",
        "tv/page/%d/" to "Series Unggulan",
        "category/drakor/page/%d/" to "Drama Korea",
        "category/drachin/page/%d/" to "Drama China",
        "eps/page/%d" to "Drama Episode",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateToStreamingDomain()
        val currentDomain = streamingUrl ?: mainUrl
        val data = request.data.format(page)
        
        val document = app.get("$currentDomain/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()

        return if (quality.isEmpty()) {
            val episode = Regex("Episode\\s?([0-9]+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        updateToStreamingDomain()
        val currentDomain = streamingUrl ?: mainUrl
        val document = app.get("$currentDomain/page/$page/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.has-post-thumbnail").mapNotNull { it.toSearchResult() }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        updateToStreamingDomain()
        val currentDomain = streamingUrl ?: mainUrl
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.substringBefore("Season")?.substringBefore("Episode")?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())?.fixImageQuality()
        val tags = document.select("strong:contains(Genre) ~ a").eachText()
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }

        // TV Series Logic
        val seriesDoc = app.get(url).document
        val episodeElements = seriesDoc.select("div.gmr-listseries a.button.button-shadow")
        var episodeCounter = 1

        val episodes = episodeElements.mapNotNull { eps ->
            val href = fixUrl(eps.attr("href")).trim()
            val name = eps.text().trim()
            if (name.contains("View All Episodes", ignoreCase = true) || !name.contains("Eps", ignoreCase = true)) return@mapNotNull null

            val season = Regex("""S(\d+)\s*Eps""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episodeCounter++
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addScore(rating)
            addActors(actors)
            this.duration = duration ?: 0
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentDomain = streamingUrl ?: mainUrl
        val document = app.get(data).document
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val iframe = app.get(fixUrl(ele.attr("href"))).document
                    .selectFirst("div.gmr-embed-responsive iframe")
                    ?.getIframeAttr()?.let { httpsify(it) } ?: return@forEach

                loadExtractor(iframe, "$currentDomain/", subtitleCallback, callback)
            }
        } else {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val server = app.post(
                    "$currentDomain/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("href").replace("#", ""),
                        "post_id" to "$id"
                    )
                ).document.select("iframe").attr("src").let { httpsify(it) }

                loadExtractor(server, "$currentDomain/", subtitleCallback, callback)
            }
        }
        return true
    }

    // --- Utils ---
    private fun Element.getImageAttr(): String = when {
        this.hasAttr("data-src") -> this.attr("abs:data-src")
        this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
        this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
        else -> this.attr("abs:src")
    }

    private fun Element?.getIframeAttr(): String? = this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() } ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? = this?.replace(Regex("(-\\d*x\\d*)"), "")

    private fun fixUrl(url: String): String {
        val currentDomain = streamingUrl ?: mainUrl
        return if (url.startsWith("http")) url else "$currentDomain$url"
    }

    private fun fixUrlNull(url: String?): String? = url?.let { fixUrl(it) }
}
