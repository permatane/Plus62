package com.kazefuri

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Kazefuri : MainAPI() {
    override var mainUrl = "https://sv3.kazefuri.cloud"
    override var name = "Kazefuri"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    // Halaman utama sederhana
    override val mainPage = mainPageOf(
        "" to "Series Terbaru"
    )

    // Main page scraper
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(url).document
        
        val items = document.select("section, article, div.container > div").mapNotNull { element ->
            val titleEl = element.selectFirst("h2, h3, .title")
            val linkEl = element.selectFirst("a")
            val imgEl = element.selectFirst("img")
            
            if (titleEl == null || linkEl == null) return@mapNotNull null
            
            val title = titleEl.text()
            val href = linkEl.attr("href")
            val poster = imgEl?.attr("src") ?: imgEl?.attr("data-src")
            
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // Search function
    override suspend fun search(query: String): List<SearchResponse> {
        // Kazefuri mungkin tidak punya search, jadi scrape dari main page
        val document = app.get(mainUrl).document
        
        return document.select("section, article, div.container > div").mapNotNull { element ->
            val titleEl = element.selectFirst("h2, h3, .title")
            val linkEl = element.selectFirst("a")
            
            if (titleEl == null || linkEl == null) return@mapNotNull null
            
            val title = titleEl.text()
            val href = linkEl.attr("href")
            
            if (!title.contains(query, ignoreCase = true)) return@mapNotNull null
            
            newAnimeSearchResponse(title, fixUrl(href))
        }
    }

    // Load details
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1, h2.title")?.text() ?: "Unknown"
        val poster = document.selectFirst("img.poster, img.thumbnail, img")?.attr("src")
        val description = document.selectFirst("div.description, div.synopsis, p")?.text()
        
        // Cari episode (simplified)
        val episodes = document.select("a[href*=/watch/], a[href*=/episode/]").mapNotNull { link ->
            val epHref = link.attr("href")
            val epText = link.text()
            
            // Extract episode number
            val epNum = Regex("Ep\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("Episode\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            
            Episode(fixUrl(epHref), "Episode $epNum", episode = epNum)
        }.sortedBy { it.episode }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // Load video links (sederhana dulu)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Coba cari iframe langsung
        val iframe = document.selectFirst("iframe")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                val fullUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
                
                // Gunakan extractor sederhana
                callback(
                    ExtractorLink(
                        name,
                        "Direct",
                        fullUrl,
                        mainUrl,
                        Qualities.Unknown.value,
                        isM3u8 = fullUrl.contains(".m3u8")
                    )
                )
                return true
            }
        }
        
        // Cari link video di script
        document.select("script").forEach { script ->
            val scriptText = script.html()
            val patterns = listOf(
                Regex("""src:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""file:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
                Regex("""(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    callback(
                        ExtractorLink(
                            name,
                            "Script Source",
                            videoUrl,
                            data,
                            Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }
        
        return false
    }
}