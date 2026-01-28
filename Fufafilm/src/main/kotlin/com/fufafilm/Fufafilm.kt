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

    // Gerbang utama (Landing Page Blogger)
    override var mainUrl = "https://www.fufafilm.sbs"
    override var name = "FufaFilm LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Variabel untuk menyimpan domain asli hasil klik otomatis
    private var baseRealUrl: String? = null

    /**
     * MENGAMBIL DOMAIN AKTIF (Sama seperti logika Filmapik)
     * Mencari tombol "KE HALAMAN Web LK21" di landing page.
     */
    private suspend fun getActiveDomain(): String {
        baseRealUrl?.let { return it }
        return try {
            val response = app.get(mainUrl, timeout = 15)
            val doc = response.document
            
            // Selector berdasarkan cuplikan HTML landing page fufafilm
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
        "country/usa/page/%d/" to "Bioskop Hollywood",
        "country/indonesia/page/%d/" to "Bioskop Indonesia",
        "tv/page/%d/" to "Series Unggulan",
        "eps/page/%d" to "Drama Episode",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentDomain = getActiveDomain()
        val url = "$currentDomain/${request.data.format(page)}"
        
        val document = app.get(url).document
        // Mendukung berbagai class pembungkus film (item atau has-post-thumbnail)
        val home = document.select("article.item, article.has-post-thumbnail").mapNotNull { 
            it.toSearchResult(currentDomain) 
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val a = this.selectFirst("h2.entry-title > a, a[title]") ?: return null
        val title = a.text().trim()
        val href = fixUrl(a.attr("href"), baseUrl)
        val posterUrl = fixUrl(this.selectFirst("img")?.getImageAttr() ?: "", baseUrl).fixImageQuality()
        
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim()

        // Jika tidak ada label quality, anggap sebagai Series
        return if (quality.isEmpty() && !href.contains("/movie/")) {
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
        val currentDomain = getActiveDomain()
        val document = app.get("$currentDomain/page/$page/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item, article.has-post-thumbnail").mapNotNull { 
            it.toSearchResult(currentDomain) 
        }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val currentDomain = getActiveDomain()
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = fixUrl(document.selectFirst("figure.pull-left > img, .poster img")?.getImageAttr() ?: "", currentDomain).fixImageQuality()
        val plot = document.selectFirst("div[itemprop=description] > p, .entry-content p")?.text()?.trim()
        val rating = document.selectFirst("span[itemprop=ratingValue], .gmr-meta-rating span")?.text()?.trim()
        val tags = document.select("strong:contains(Genre) ~ a, .gmr-moviedata a[rel='category tag']").eachText()

        // Deteksi apakah ini TV Series
        if (url.contains("/tv/") || document.selectFirst("div.gmr-listseries") != null) {
            val episodes = document.select("div.gmr-listseries a.button").mapIndexedNotNull { index, it ->
                newEpisode(fixUrl(it.attr("href"), currentDomain)) {
                    this.name = it.text().trim()
                    this.episode = index + 1
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                addScore(rating)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
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
        val currentDomain = getActiveDomain()
        val document = app.get(data).document

        // STRATEGI 1: AJAX Player (Muvipro/Dooplay Standard)
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").forEach { ele ->
                val tabId = ele.attr("href").replace("#", "")
                try {
                    val ajaxRes = app.post(
                        "$currentDomain/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to id
                        ),
                        headers = mapOf(
                            "Referer" to data,
                            "X-Requested-With" to "XMLHttpRequest" // Penting agar tidak diblokir
                        )
                    ).document
                    val iframeSrc = ajaxRes.select("iframe").attr("src").let { httpsify(it) }
                    if (iframeSrc.isNotEmpty()) {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) { }
            }
        }

        // STRATEGI 2: Link Download (Sangat stabil untuk Filemoon/Buzzheavier)
        document.select("div#down a.myButton").forEach { button ->
            val downloadHref = button.attr("href")
            if (downloadHref.isNotEmpty()) {
                val finalUrl = if (downloadHref.startsWith("//")) "https:$downloadHref" else downloadHref
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // --- Helper Functions ---

    private fun Element.getImageAttr(): String = when {
        this.hasAttr("data-src") -> this.attr("abs:data-src")
        this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
        else -> this.attr("abs:src")
    }

    private fun String?.fixImageQuality(): String? = this?.replace(Regex("(-\\d*x\\d*)"), "")

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.isEmpty()) return ""
        if (url.startsWith("http")) return url
        val cleanBase = baseUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$cleanBase$url" else "$cleanBase/$url"
    }
}
