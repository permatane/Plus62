package com.fufafilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element
import android.util.Base64

class Fufafilm : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://www.fufafilm.sbs"
    override var name = "FufaFilm LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private var baseRealUrl: String? = null

    // Ambil domain aktif dari landing page
    private suspend fun getActiveDomain(): String {
        baseRealUrl?.let { return it }
        return try {
            val response = app.get(mainUrl, timeout = 15)
            val doc = response.document
            val targetLink = doc.selectFirst("a.green-button, a:contains(KE HALAMAN Web LK21)")?.attr("href")

            if (!targetLink.isNullOrBlank()) {
                val uri = URI(targetLink)
                val cleanUrl = "${uri.scheme}://${uri.host}"
                baseRealUrl = cleanUrl
                cleanUrl
            } else {
                mainUrl
            }
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
        val currentDomain = getActiveDomain()
        val url = "$currentDomain/${request.data.format(page)}"
        val document = app.get(url).document
        val home = document.select("article.item, article.has-post-thumbnail").mapNotNull { 
            it.toSearchResult(currentDomain) 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val a = this.selectFirst("h2.entry-title > a, a[title]") ?: return null
        val title = a.text().trim()
        val href = fixUrl(a.attr("href"), baseUrl)
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("abs:src") ?: "", baseUrl)
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val currentDomain = getActiveDomain()
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("figure.pull-left > img, .poster img")?.attr("abs:src")

        if (url.contains("/tv/") || document.selectFirst("div.gmr-listseries") != null) {
            val episodes = document.select("div.gmr-listseries a.button").mapIndexedNotNull { index, it ->
                newEpisode(fixUrl(it.attr("href"), currentDomain)) {
                    this.name = it.text().trim()
                    this.episode = index + 1
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentDomain = getActiveDomain()
        val document = app.get(data).document

        // STRATEGI 1: Ambil data-url langsung (Metode Filmapik)
        // Banyak situs LK21 menaruh link di li.dooplay_player_option atau .gmr-embed-responsive
        document.select("li.dooplay_player_option, div.gmr-embed-responsive iframe, a.myButton").forEach { el ->
            var url = el.attr("data-url").takeIf { it.isNotEmpty() } 
                      ?: el.attr("src").takeIf { it.isNotEmpty() }
                      ?: el.attr("href").takeIf { it.contains("efek.stream") || it.contains("short.icu") }
                      ?: ""

            if (url.isNotEmpty()) {
                // Decode jika Base64 (ciri: tidak ada 'http' tapi panjang)
                if (!url.startsWith("http") && !url.startsWith("//") && url.length > 20) {
                    try {
                        url = String(Base64.decode(url, Base64.DEFAULT))
                    } catch (e: Exception) {}
                }

                val finalUrl = httpsify(url)
                if (finalUrl.contains("http")) {
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            }
        }

        // STRATEGI 2: AJAX (Muvipro/Dooplay)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val tabId = ele.attr("href").replace("#", "")
                val ajaxRes = app.post(
                    "$currentDomain/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "muvipro_player_content", "tab" to tabId, "post_id" to id),
                    headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")
                ).document
                
                val iframeSrc = ajaxRes.select("iframe").attr("src")
                if (iframeSrc.isNotEmpty()) {
                    loadExtractor(httpsify(iframeSrc), data, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("/")) "${baseUrl.removeSuffix("/")}$url" else "${baseUrl.removeSuffix("/")}/$url"
    }
}
