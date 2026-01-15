package com.Nontonanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Nontonanime : MainAPI() {
    override var mainUrl = "https://s7.nontonanimeid.boats"
    override var name = "NontonAnime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "ongoing-list/" to "Ongoing List",
        "popular-series/" to "Popular Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        // Selector .animeseries mencakup poster di halaman depan
        val home = document.select(".animeseries").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val a = this.selectFirst("a")
        val href = fixUrl(a?.attr("href") ?: "")
        val title = this.selectFirst(".title")?.text() ?: ""
        
        // PERBAIKAN POSTER: Mengambil atribut data-src karena situs menggunakan lazy load
        val posterUrl = this.selectFirst("img")?.let { 
            val url = it.attr("data-src").ifEmpty { it.attr("src") }
            fixUrlNull(url)
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")?.text()?.trim() ?: ""
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
            // Search biasanya menggunakan src langsung
            val poster = it.selectFirst("img")?.attr("abs:src")
            
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // PERBAIKAN "COMING SOON": Jika klik dari episode, paksa ambil data dari halaman series/detail
        val detailUrl = if (url.contains("-episode-")) {
            document.selectFirst(".nvs.nvsc a")?.attr("href") ?: url
        } else url
        
        val detailDoc = if (detailUrl != url) app.get(detailUrl).document else document

        val title = detailDoc.selectFirst("h1.entry-title")?.text()
            ?.replace("Nonton Anime", "")?.replace("Sub Indo", "")?.trim() ?: ""
        
        val poster = detailDoc.selectFirst(".poster img")?.attr("abs:src")
        val description = detailDoc.select(".entry-content.seriesdesc p").text().trim()
        val rating = detailDoc.select(".nilaiseries").text().trim()

        // Selector episode terbaru sesuai struktur misha_posts_wrap2
        val episodes = detailDoc.select(".misha_posts_wrap2 li, .list-episode li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val name = a.text()
            val epNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(fixUrl(a.attr("href"))) {
                this.name = name
                this.episode = epNum
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            addScore(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data)
        val document = res.document

        // PERBAIKAN VIDEO: Mengambil nonce secara dinamis dari script di halaman
        val nonce = Regex("""["']nonce["']\s*:\s*["']([^"']+)""").find(res.text)?.groupValues?.get(1)

        document.select(".container1 > ul > li[data-post]").amap {
            val dataPost = it.attr("data-post")
            val dataNume = it.attr("data-nume")
            val dataType = it.attr("data-type")

            if (!nonce.isNullOrEmpty()) {
                val response = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to dataPost,
                        "nume" to dataNume,
                        "type" to dataType,
                        "nonce" to nonce
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text

                // Ekstrak URL iframe dari response Ajax
                val iframeUrl = Regex("""src=['"]([^"']+)""").find(response)?.groupValues?.get(1)
                
                if (!iframeUrl.isNullOrEmpty()) {
                    loadExtractor(fixUrl(iframeUrl), "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
