package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class Filmapik : MainAPI() {
    // Domain utama sebagai pintu masuk (Landing Page)
    override var mainUrl = "https://filmapik.to"
    override var name = "FilmApik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Variabel untuk menyimpan domain target hasil tangkapan otomatis (misal: .fitness)
    private var baseRealUrl: String? = null

    /**
     * FUNGSI OTOMATIS: Menangkap domain terbaru dari tombol landing page secara real-time.
     */
    private suspend fun getActiveDomain(): String {
        baseRealUrl?.let { return it }

        return try {
            val response = app.get(mainUrl, timeout = 15)
            val doc = response.document
            
            // Mencari href dari tombol "KE HALAMAN FILMAPIK"
            val targetLink = doc.selectFirst("a.cta-button, a:contains(KE HALAMAN FILMAPIK)")?.attr("href")

            if (!targetLink.isNullOrBlank()) {
                val uri = URI(targetLink)
                val cleanUrl = "${uri.scheme}://${uri.host}"
                baseRealUrl = cleanUrl
                cleanUrl
            } else {
                mainUrl
            }
        } catch (e: Exception) {
            // Jika filmapik.to diblokir, coba akses domain terakhir yang diketahui (jika ada) atau tetap di mainUrl
            baseRealUrl ?: mainUrl
        }
    }

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "tvshows/page/%d/" to "Serial Terbaru",
        "latest/page/%d/" to "Film Terbaru",
        "category/action/page/%d/" to "Action",
        "category/romance/page/%d/" to "Romance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val currentDomain = getActiveDomain()
        val url = "$currentDomain/${request.data.format(page)}"
        
        val document = app.get(url).document
        val items = document.select("div.items.normal article.item").mapNotNull { 
            it.toSearchResult(currentDomain) 
        }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val a = selectFirst("a[title][href]") ?: return null
        val title = a.attr("title").trim()
        val href = fixUrl(a.attr("href"), baseUrl)
        val poster = fixUrl(selectFirst("img[src]")?.attr("src") ?: "", baseUrl).fixImageQuality()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            val quality = selectFirst("span.quality")?.text()?.trim()
            if (!quality.isNullOrBlank()) addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val currentDomain = getActiveDomain()
        val document = app.get("$currentDomain?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult(currentDomain) }
    }

    override suspend fun load(url: String): LoadResponse {
        val currentDomain = getActiveDomain()
        val document = app.get(url).document
        
        val title = document.selectFirst("h1[itemprop=name]")?.text()
            ?.replace("Subtitle Indonesia Filmapik", "")
            ?.replace("Nonton Film", "")?.trim() ?: ""
            
        val poster = document.selectFirst(".sheader .poster img")?.attr("src")?.let { 
            fixUrl(it, currentDomain) 
        }

        // --- MENAMPILKAN DESKRIPSI ---
        val description = document.selectFirst("div[itemprop=description] p")?.text()
            ?.replace("ALUR CERITA : –", "")?.trim() 

        val genres = document.select("span.sgeneros a").map { it.text() }
        val actors = document.select("span[itemprop=actor] a").map { it.text() }
        val year = document.selectFirst("span.country a[href*='release-year']")?.text()?.toIntOrNull()
        val rating = document.selectFirst("#repimdb strong")?.text()?.toDoubleOrNull()

        val seasonBlocks = document.select("#seasons .se-c")
        
        if (seasonBlocks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            seasonBlocks.forEach { block ->
                val seasonNum = block.selectFirst(".se-q .se-t")?.text()?.toIntOrNull() ?: 1
                block.select(".se-a ul.episodios li a").forEachIndexed { index, ep ->
                    val epData = fixUrl(ep.attr("href"), currentDomain)
                    episodes.add(newEpisode(epData) {
                        this.name = ep.text().trim()
                        this.season = seasonNum
                        this.episode = index + 1
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = genres
            this.score = Score.from10(rating)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document


        doc.select("div#down a.myButton").forEach { button ->
            val href = button.attr("href")
            if (href.isNotEmpty()) {
                val finalUrl = if (href.startsWith("//")) "https:$href" else href
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        // STRATEGI 2: Ekstraksi dari opsi player (cadangan)
        doc.select("li.dooplay_player_option[data-url]").forEach { el ->
            val iframeUrl = el.attr("data-url").trim()
            if (iframeUrl.isNotEmpty()) {
                val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // --- HELPER FUNCTIONS ---

    private fun String?.fixImageQuality(): String? = this?.replace(Regex("(-\\d*x\\d*)"), "")

    private fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        val cleanBase = baseUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$cleanBase$url" else "$cleanBase/$url"
    }
}
