package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class Filmapik : MainAPI() {
    // Satu-satunya URL statis sebagai pintu masuk utama
    override var mainUrl = "https://filmapik.to"
    override var name = "FilmApik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Variabel dinamis untuk menyimpan domain hasil tangkapan otomatis
    private var baseRealUrl: String? = null

    /**
     * Fungsi Otomatis: Menangkap domain terbaru dari landing page secara real-time.
     */
    private suspend fun getActiveDomain(): String {
        // Gunakan cache jika domain sudah berhasil ditangkap sebelumnya
        baseRealUrl?.let { return it }

        return try {
            // Mengambil halaman landing page filmapik.to
            val response = app.get(mainUrl, timeout = 15)
            val doc = response.document
            
            // Mencari link pada tombol berdasarkan struktur HTML yang Anda berikan
            // <a href="https://domain-baru.com" class="cta-button green-button">KE HALAMAN FILMAPIK</a>
            val targetLink = doc.selectFirst("a.cta-button, a:contains(KE HALAMAN FILMAPIK)")?.attr("href")

            if (!targetLink.isNullOrBlank()) {
                // Ekstrak Scheme dan Host (Contoh: https://filmapik.id)
                val uri = URI(targetLink)
                val cleanUrl = "${uri.scheme}://${uri.host}"
                
                baseRealUrl = cleanUrl
                cleanUrl
            } else {
                // Jika tombol tidak ditemukan, kembalikan mainUrl agar tidak crash
                mainUrl
            }
        } catch (e: Exception) {
            // Jika filmapik.to diblokir atau DNS error, gunakan mainUrl sebagai fallback
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
        
        // Memastikan link film menggunakan domain hasil tangkapan terbaru
        val href = fixUrlCustom(a.attr("href"), baseUrl)
        val poster = fixUrlCustom(selectFirst("img[src]")?.attr("src") ?: "", baseUrl).fixImageQuality()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            val rating = selectFirst("div.rating")?.ownText()?.trim()?.toDoubleOrNull()
            this.score = Score.from10(rating)
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
        
        // 1. Ambil Judul
        val title = document.selectFirst("h1[itemprop=name]")?.text()
            ?.replace("Subtitle Indonesia Filmapik", "")
            ?.replace("Nonton Film", "")?.trim() ?: ""
            
        // 2. Ambil Poster
        val poster = document.selectFirst(".sheader .poster img")?.attr("src")?.let { 
            fixUrlCustom(it, currentDomain) 
        }

        // 3. Ambil Deskripsi / Sinopsis (Target Utama Anda)
        val description = document.selectFirst("div[itemprop=description] p")?.text()
            ?.replace("ALUR CERITA : –", "")?.trim() 

        // 4. Ambil Metadata (Genre, Aktor, Tahun, Rating)
        val genres = document.select("span.sgeneros a").map { it.text() }
        val actors = document.select("span[itemprop=actor] a").map { it.text() }
        val year = document.selectFirst("span.country a[href*='release-year']")?.text()?.toIntOrNull()
        val rating = document.selectFirst("#repimdb strong")?.text()?.toDoubleOrNull()

        // Cek apakah ini TV Series atau Movie
        val seasonBlocks = document.select("#seasons .se-c")
        
        if (seasonBlocks.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            seasonBlocks.forEach { block ->
                val seasonNum = block.selectFirst(".se-q .se-t")?.text()?.toIntOrNull() ?: 1
                block.select(".se-a ul.episodios li a").forEachIndexed { index, ep ->
                    episodes.add(newEpisode(fixUrlCustom(ep.attr("href"), currentDomain)) {
                        this.name = ep.text()
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
        // Mengambil link player dari data-url secara otomatis
        doc.select("li.dooplay_player_option[data-url]").forEach { el ->
            val iframeUrl = el.attr("data-url").trim()
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun String?.fixImageQuality(): String? = this?.replace(Regex("(-\\d*x\\d*)"), "")

    private fun fixUrlCustom(url: String, baseUrl: String): String {
        if (url.startsWith("http")) return url
        val cleanBase = baseUrl.removeSuffix("/")
        return if (url.startsWith("/")) "$cleanBase$url" else "$cleanBase/$url"
    }
}
