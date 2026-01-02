package com.Kazefuri

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Kazefuri : MainAPI() {
    override var mainUrl = "https://sv3.kazefuri.cloud"
    override var name = "Kazefuri"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        // Mengambil daftar anime/movie dari halaman depan
        val items = document.select("article.obj-anime, div.bs").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse("Update Terbaru", items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2, .tt")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.attr("src")

        // Memperbaiki Unresolved reference posterUrl dengan blok init
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, div.bs").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, .entry-title")?.text() ?: ""
        val poster = document.selectFirst("img.wp-post-image, .poster img")?.attr("src")
        val description = document.selectFirst(".entry-content p, .description")?.text()

        // Deteksi Episode (untuk Anime/Series)
        val episodes = document.select(".eplister li").mapNotNull {
            val epHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epName = it.selectFirst(".epl-num")?.text() ?: "Episode"
            Episode(epHref, epName)
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                addEpisodes(NavType.ITunes, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Mencoba Player Iframe (External Extractor)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. Mencoba mencari link direct dari script (Menghindari Deprecated Constructor)
        val scriptData = document.select("script").joinToString { it.data() }
        val regex = """["']file["']\s*:\s*["']([^"']+)["']""".toRegex()
        
        regex.findAll(scriptData).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.contains("http")) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "Internal Player",
                        url = videoUrl,
                        referer = data,
                        quality = Qualities.P720.value,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }

        return true
    }
}
