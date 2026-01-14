package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Auratail : Anichin() {
    override var mainUrl              = "https://auratail.vip"
    override var name                 = "Auratail"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

        private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

override val mainPage = mainPageOf(
        "" to "Update Terbaru",                              // homepage utama
        // "genre/ongoing/?order=update" to "Update Terbaru",
        // "genre/ongoing/?order=popular" to "Paling Populer",
        // "?s=&post_type=post" to "Semua Series",    // atau sesuaikan jika ada kategori khusus
        // "genre/movie/" to "Movies / Batch"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page").documentLarge
        val home     = document.select("article.bs, article.bsx, .listupd article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
    val titleElement = selectFirst("a") ?: return null
    
    val title = titleElement.attr("title").ifBlank { 
        titleElement.selectFirst("h2, h3, .title")?.text()?.trim() ?: titleElement.text().trim()
    }.trim()
    
    if (title.isEmpty()) return null

    val href = fixUrl(titleElement.attr("href"))

    // Perbaikan utama: ambil gambar dengan prioritas lazy loading
    val posterElement = selectFirst("img")
    val posterUrl = when {
        posterElement == null -> null
        else -> {
            val src = posterElement.attr("src").ifBlank { 
                posterElement.attr("data-src").ifBlank { 
                    posterElement.attr("data-lazy-src") 
                } 
            }
            if (src.isNotBlank()) fixUrlNull(src) else null
        }
    }

    return newAnimeSearchResponse(title, href, TvType.Anime) {
        this.posterUrl = posterUrl
    }
}
    
override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url).document

    val title = doc.selectFirst("h1.entry-title, .post-title h1, h1")?.text()?.trim() ?: "Unknown Title"
    val poster = doc.selectFirst("div.thumb img, img.poster, meta[property=og:image]")?.let {
        fixUrlNull(it.attr("src").ifBlank { it.attr("data-src") })
    }



    val episodes = doc.select("div.eplister ul li, .episodelist ul li, #episode_related li")
        .mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val epNumStr = el.selectFirst(".epl-num, .episode-number")?.text()?.trim() ?: ""
            val epNum = epNumStr.toIntOrNull() ?: Regex("""\d+""").find(epNumStr)?.value?.toIntOrNull()

            newEpisode(href) {
                this.name = "Episode ${epNum ?: "?"}"
                this.episode = epNum

            }
        }.reversed() 

    val isMovie = url.contains("/movie/") || title.lowercase().contains("movie") || title.lowercase().contains("batch") || episodes.isEmpty()

    return if (isMovie) {
        newMovieLoadResponse(
            title = title,
            url = url,
            type = TvType.AnimeMovie,
            data = url 
        ) {
            this.posterUrl = poster
            // tambahkan plot, tags, dll jika perlu
        }
    } else {
        newAnimeLoadResponse(
            title = title,
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}
}
