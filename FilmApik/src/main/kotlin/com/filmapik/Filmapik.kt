package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI

class Filmapik : MainAPI() {
    override var mainUrl = "https://filmapik.to"
    override var name = "FilmApik"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Variabel dinamis untuk menangkap domain yang berubah-ubah
    private var baseRealUrl: String? = null

    /**
     * Otomatisasi menangkap domain terbaru dari tombol landing page
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
        val href = fixUrlCustom(a.attr("href"), baseUrl)
        val poster = fixUrlCustom(selectFirst("img[src]")?.attr("src") ?: "", baseUrl).fixImageQuality()
        
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
            fixUrlCustom(it, currentDomain) 
        }

        // Ambil Deskripsi / Sinopsis
        val description = document.selectFirst("div[itemprop=description] p")?.text()
            ?.replace("ALUR CERITA : –", "")?.trim() 

        // Ambil Metadata
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
        // 1. Ambil dokumen dari halaman film/episode
        // Pastikan 'data' (URL) sudah menggunakan domain terbaru
        val doc = app.get(data).document

        // 2. Ambil semua opsi player (li.dooplay_player_option)
        // Sesuai HTML Anda: data-url berisi link stream (byseqekaho, efek.stream, short.icu)
        doc.select("li.dooplay_player_option").forEach { el ->
            val type = el.attr("data-type")
            if (type == "trailer") return@forEach // Lewati trailer

            var iframeUrl = el.attr("data-url").trim()
            
            // Jaga-jaga jika sewaktu-waktu web berubah menggunakan sistem AJAX (nume/post)
            if (iframeUrl.isEmpty()) {
                val post = el.attr("data-post")
                val nume = el.attr("data-nume")
                val currentDomain = getActiveDomain() // Ambil domain dinamis terbaru

                val ajaxRes = app.post(
                    "$currentDomain/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = mapOf("Referer" to data, "X-Requested-With" to "XMLHttpRequest")
                ).text
                
                iframeUrl = Regex("""(?<=embed_url":")(?<url>.*?)(?=")""").find(ajaxRes)?.groupValues?.get(1)
                    ?.replace("\\/", "/") ?: ""
            }

            // 3. Eksekusi Extractor jika link ditemukan
            if (iframeUrl.isNotEmpty()) {
                // Bersihkan URL jika protokolnya relatif (//domain.com)
                val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
                
                // loadExtractor akan mencocokkan link (short.icu, efek.stream, dll) 
                // dengan modul extractor yang terinstall di CloudStream
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
        private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        
        // Gunakan baseRealUrl (domain terbaru) jika tersedia, 
        // jika belum tersedia gunakan mainUrl sebagai basis awal
        val baseUrl = baseRealUrl ?: mainUrl
        val cleanBase = baseUrl.removeSuffix("/")
        
        return if (url.startsWith("/")) "$cleanBase$url" else "$cleanBase/$url"
    }
}
