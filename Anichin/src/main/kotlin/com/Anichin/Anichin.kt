package com.Anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.cafe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Baru Rilis",
        "anime/?status=&type=&order=popular" to "Populer",
        "anime/?status=completed&type=&order=update" to "Tamat",
        "anime/?status=&type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}&page=$page"
        val document = app.get(url).document
        val home = document.select("div.listupd article, div.post-show article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, h3, .entry-title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".thumb img")?.attr("src")
        val description = document.selectFirst(".entry-content, .synopsis")?.text()?.trim()
        
        val episodes = document.select(".eplister li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val num = it.selectFirst(".epl-num")?.text() ?: ""
            newEpisode(href) {
                this.name = "Episode $num"
                this.episode = num.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
        val document = app.get(data).document
        
        // Anichin sering menyimpan link video di dalam dropdown mirror (base64)
        document.select("select.mirror option").forEach { option ->
            val base64Value = option.attr("value")
            if (base64Value.isNotEmpty()) {
                val decodedHtml = try { base64Decode(base64Value) } catch (e: Exception) { "" }
                val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)
                
                if (!iframeUrl.isNullOrEmpty()) {
                    loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }

        // Juga cek jika ada iframe langsung di halaman
        document.select(".video-content iframe, .embed-responsive iframe").forEach {
            val src = it.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(httpsify(src), mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}