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

    override var mainUrl = "https://www.fufafilm.sbs"
    override var name = "FufaFilm LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private var directUrl: String? = null

    // Metode untuk menangkap domain streaming asli dari tombol landing page
    private suspend fun getDirectUrl(): String {
        directUrl?.let { return it }
        return try {
            val response = app.get(mainUrl).document
            val target = response.selectFirst("a.green-button, a:contains(KE HALAMAN Web LK21)")?.attr("href")
            if (!target.isNullOrBlank()) {
                val uri = URI(target)
                val domain = "${uri.scheme}://${uri.host}"
                directUrl = domain
                domain
            } else mainUrl
        } catch (e: Exception) {
            mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "category/film/page/%d/" to "Semua Film",
        "tv/page/%d/" to "Series Unggulan",
        "eps/page/%d" to "Drama Episode",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val domain = getDirectUrl()
        val document = app.get("$domain/${request.data.format(page)}").document
        val home = document.select("article.item, article.has-post-thumbnail").mapNotNull { 
            it.toSearchResult(domain) 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val a = this.selectFirst("h2.entry-title > a, a[title]") ?: return null
        val title = a.text().trim()
        val href = if (a.attr("href").startsWith("http")) a.attr("href") else "$baseUrl${a.attr("href")}"
        val posterUrl = this.selectFirst("img")?.getImageAttr()?.fixImageQuality()

        return if (href.contains("/tv/") || href.contains("/eps/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val domain = getDirectUrl()
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("figure.pull-left > img")?.getImageAttr()?.fixImageQuality()
        
        val episodes = document.select("div.gmr-listseries a.button").mapIndexed { index, it ->
            newEpisode(it.attr("href")) {
                this.name = it.text().trim()
                this.episode = index + 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val domain = getDirectUrl()
        val document = app.get(data).document

        // STRATEGI 1: Ambil ID untuk Request AJAX (Muvipro)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val tabId = ele.attr("href").replace("#", "")
                try {
                    val server = app.post(
                        "$domain/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to id
                        ),
                        headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")
                    ).document.select("iframe").attr("src").let { httpsify(it) }

                    if (server.isNotEmpty()) {
                        loadExtractor(server, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        }

        // STRATEGI 2: Cari Iframe Langsung (Metode FilmApik)
        document.select("iframe, .gmr-embed-responsive iframe").forEach { 
            val src = it.getIframeAttr()
            if (!src.isNullOrEmpty()) {
                loadExtractor(httpsify(src), data, subtitleCallback, callback)
            }
        }

        // STRATEGI 3: Tombol Download
        document.select("div#down a.myButton").forEach { 
            val href = it.attr("href")
            if (href.isNotEmpty()) loadExtractor(httpsify(href), data, subtitleCallback, callback)
        }

        return true
    }

    private fun Element.getImageAttr(): String = this.attr("abs:src").ifEmpty { this.attr("abs:data-src") }

    private fun Element?.getIframeAttr(): String? = this?.attr("data-litespeed-src") ?: this?.attr("src")

    private fun String?.fixImageQuality(): String? = this?.replace(Regex("(-\\d*x\\d*)"), "")
}
