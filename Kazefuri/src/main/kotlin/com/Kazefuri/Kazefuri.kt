package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class KazefuriPlugin : MainAPI() {
    override var mainUrl = "https://sv3.kazefuri.cloud"
    override var name = "Kazefuri"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.ChineseDrama)
    
    // ==================== HALAMAN UTAMA ====================
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Series Terbaru"
    )
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.container, section").mapNotNull { it ->
            it.select("a").mapNotNull { anchor ->
                val title = anchor.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
                val href = anchor.attr("href")
                val poster = anchor.selectFirst("img")?.attr("src")
                val episode = anchor.selectFirst(".episode, .ep")?.text()?.toIntOrNull()
                
                newAnimeSearchResponse(title, href) {
                    this.posterUrl = poster
                    if (episode != null) episode?.toString()?.let { addEpisode(it, href) }
                }
            }
        }.flatten()
        return newHomePageResponse(request.name, home)
    }
    
    // ==================== PENCARIAN ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=${query.urlEncoded()}"
        val document = app.get(searchUrl).document
        return document.select("div.item, article").mapNotNull {
            val title = it.selectFirst("h3, .title")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")
            val year = it.selectFirst(".year")?.text()?.toIntOrNull()
            
            newAnimeSearchResponse(title, href) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }
    
    // ==================== DETAIL SERIES ====================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.title")?.text() ?: "Unknown"
        val poster = document.selectFirst("img.poster")?.attr("src")
        val description = document.selectFirst(".description, .synopsis")?.text()
        
        val episodes = document.select("ul.episodes li, .episode-list a").mapNotNull {
            val epTitle = it.text()
            val epHref = it.attr("href")
            Episode(epHref, epTitle)
        }
        
        return newAnimeLoadResponse(title, url, TvType.ChineseDrama) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
    
    // ==================== LOAD VIDEO LINKS (PERLU DILENGKAPI) ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).documentLarge

        val options = html.select("option[data-index]")

        for (option in options) {
            val base64 = option.attr("value")
            if (base64.isBlank()) continue
            val label = option.text().trim()
            val decodedHtml = try {
                base64Decode(base64)
            } catch (_: Exception) {
                Log.w("Error", "Base64 decode failed: $base64")
                continue
            }

            val iframeUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")?.let(::httpsify)
            if (iframeUrl.isNullOrEmpty()) continue
            when {
                "vidmoly" in iframeUrl -> {
                    val cleanedUrl = "http:" + iframeUrl.substringAfter("=\"").substringBefore("\"")
                    loadExtractor(cleanedUrl, referer = iframeUrl, subtitleCallback, callback)
                }
                iframeUrl.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(
                            label,
                            label,
                            url = iframeUrl,
                            INFER_TYPE
                        ) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                }
                else -> {
                    loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
