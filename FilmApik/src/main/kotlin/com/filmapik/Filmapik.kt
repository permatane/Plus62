package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Filmapik : MainAPI() {
    // Landing page tetap statis
    override var mainUrl = "https://filmapik.to"
    override var name = "FilmApik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Variabel untuk menyimpan domain hasil "klik" otomatis
    private var baseRealUrl: String? = null

    /**
     * Fungsi otomatis klik "KE HALAMAN FILMAPIK"
     */
    private suspend fun getRealUrl(): String {
        // Jika sudah pernah ambil, gunakan yang ada (cache)
        baseRealUrl?.let { return it }

        return try {
            val doc = app.get(mainUrl).document
            // Mencari tombol "KE HALAMAN FILMAPIK" atau cta-button
            var newLink = doc.selectFirst("a:contains(KE HALAMAN FILMAPIK), a.cta-button")?.attr("href")
            
            if (newLink.isNullOrBlank()) {
                newLink = doc.selectFirst("a[href*='filmapik']")?.attr("href")
            }

            if (!newLink.isNullOrBlank()) {
                // Membersihkan URL agar hanya mengambil Base URL (domain)
                val cleanUrl = newLink.substringBeforeLast("/").substringBefore("?")
                baseRealUrl = cleanUrl
                cleanUrl
            } else {
                mainUrl // Fallback jika gagal
            }
        } catch (e: Exception) {
            mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "tvshows/page/%d/" to "Serial Terbaru",
        "latest/page/%d/" to "Film Terbaru",
        "category/action/page/%d/" to "Action"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentBase = getRealUrl()
        val url = "$currentBase/${request.data.format(page)}"
        val document = app.get(url).document
        val items = document.select("div.items.normal article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[title][href]") ?: return null
        val title = a.attr("title").trim()
        // Gunakan fungsi fixUrl yang merujuk ke baseRealUrl
        val href = fixUrl(a.attr("href"))
        val poster = fixUrlNull(selectFirst("img[src]")?.attr("src")).fixImageQuality()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            val quality = selectFirst("span.quality")?.text()?.trim()
            if (!quality.isNullOrBlank()) addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentBase = getRealUrl()
        val document = app.get("$currentBase?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        // URL di sini sudah hasil fixUrl (domain terbaru)
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name], .sheader h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".sheader .poster img")?.attr("src")?.let { fixUrl(it) }
        val description = document.selectFirst("div[itemprop=description], .wp-content")?.text()?.trim() ?: ""
        
        val seasonBlocks = document.select("#seasons .se-c")
        if (seasonBlocks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            seasonBlocks.forEach { block ->
                val seasonNum = block.selectFirst(".se-q .se-t")?.text()?.toIntOrNull() ?: 1
                block.select(".se-a ul.episodios li a").forEachIndexed { index, ep ->
                    episodes.add(newEpisode(fixUrl(ep.attr("href"))) {
                        this.name = ep.text()
                        this.season = seasonNum
                        this.episode = index + 1
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        doc.select("li.dooplay_player_option[data-url]").forEach { el ->
            val iframeUrl = el.attr("data-url").trim()
            if (iframeUrl.isNotEmpty()) {
                // loadExtractor akan otomatis mencari link video dari iframe
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }

    // --- Helper Functions ---

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.firstOrNull()
        return if (match != null) this.replace(match, "") else this
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        // Gunakan domain terbaru jika tersedia, jika tidak gunakan landing page
        val base = baseRealUrl ?: mainUrl
        return if (url.startsWith("/")) "$base$url" else "$base/$url"
    }

    private fun fixUrlNull(url: String?): String? = url?.let { fixUrl(it) }
}
