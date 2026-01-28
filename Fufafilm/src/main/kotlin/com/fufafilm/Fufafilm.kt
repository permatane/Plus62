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

    // URL Landing Page sebagai gerbang utama
    override var mainUrl = "https://www.fufafilm.sbs"
    // Variabel dinamis untuk menampung domain streaming aktif
    private var streamingUrl: String? = null
    
    override var name = "FufaFilm LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    /**
     * Mendeteksi domain streaming aktif dengan mengklik tombol landing page
     */
    private suspend fun updateToStreamingDomain() {
        if (streamingUrl != null) return

        try {
            val response = app.get(mainUrl, headers = headers, timeout = 20).document
            // Mencari href dari tombol hijau utama berdasarkan HTML Anda
            val targetButton = response.selectFirst("a.green-button, a:contains(KE HALAMAN Web LK21)")
                ?: response.selectFirst("a.cta-button")
            
            val href = targetButton?.attr("href")

            if (!href.isNullOrBlank() && href.startsWith("https")) {
                // Ekstrak domain utama saja
                val uri = URI(href)
                streamingUrl = "${uri.scheme}://${uri.host}"
            }
        } catch (e: Exception) {
            streamingUrl = mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "category/film/page/%d/" to "Semua Film",
        "country/usa/page/%d/" to "Bioskop Hollywood",
        "country/indonesia/page/%d/" to "Bioskop Indonesia",
        "tv/page/%d/" to "Series Unggulan",
        "eps/page/%d" to "Drama Episode",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateToStreamingDomain()
        val currentDomain = streamingUrl ?: mainUrl
        val data = request.data.format(page)
        
        val document = app.get("$currentDomain/$data", headers = headers).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("a > img")?.getImageAttr()?.fixImageQuality()
        
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim()

        return if (quality.isEmpty()) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
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
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("figure.pull-left > img")?.getImageAttr()?.fixImageQuality()
        val plot = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.trim()

        return if (url.contains("/tv/")) {
            val episodes = document.select("div.gmr-listseries a.button").mapIndexedNotNull { index, it ->
                newEpisode(it.attr("href")) {
                    this.name = it.text().trim()
                    this.episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                addScore(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                addScore(rating)
            }
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

        if (!id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val server = app.post(
                    "$currentDomain/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to ele.attr("href").replace("#", ""),
                        "post_id" to id
                    )
                ).document.select("iframe").attr("src").let { httpsify(it) }

                loadExtractor(server, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.getImageAttr(): String = this.attr("abs:src").ifEmpty { this.attr("abs:data-src") }
    private fun String?.fixImageQuality(): String? = this?.replace(Regex("(-\\d*x\\d*)"), "")
}
