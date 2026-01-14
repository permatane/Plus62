package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Animechina : Anichin() {
    override var mainUrl              = "https://animechina.my.id"
    override var name                 = "Anime China"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "ongoing/" to "Ongoing"
    )
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = if (request.data.isEmpty()) {
        "$mainUrl/page/$page"
    } else {
        "$mainUrl/${request.data}&page=$page"
    }
    val document = app.get(url).documentLarge  // documentLarge lebih aman untuk situs berat

    val homeItems = document.select("div.series__card, article, .bsx, .bs, .tip, .anime-item, div.series__thumbnail")
        .mapNotNull { it.toSearchResult() }

    return newHomePageResponse(
        list = HomePageList(
            name = request.name,
            list = homeItems,
            isHorizontalImages = false  // false biasanya lebih bagus untuk portrait poster
        ),
        hasNext = document.select("a.next, .pagination a[href*='page=']").isNotEmpty()
    )
}

private fun Element.toSearchResult(): SearchResponse? {
    // Cari link utama dengan title
    val linkElem = selectFirst("a[href][title]") ?: selectFirst("a") ?: return null

    // Prioritaskan attr("title"), fallback ke h2 di series__title (dari snippet HTML kamu)
    var title = linkElem.attr("title").trim()
    if (title.isEmpty()) {
        title = selectFirst("div.series__title h2")?.text()?.trim() ?: ""
    }
    if (title.isEmpty()) {
        title = linkElem.text().trim()  // fallback terakhir: teks di dalam <a>
    }
    if (title.isEmpty()) return null

    val href = fixUrl(linkElem.attr("href"))

    // Poster: prioritas data-src untuk lazyload (seperti di snippet kamu)
    val posterElem = selectFirst("img.lazyload, img")
    val posterUrl = posterElem?.let {
        it.attr("data-src").ifBlank {
            it.attr("data-lazy-src").ifBlank { it.attr("src") }
        }
    }?.trim()?.let { fixUrlNull(it) }

    return newAnimeSearchResponse(title, href, TvType.Anime) {
        this.posterUrl = posterUrl
        this.name = title
    }
}

override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url).documentLarge
    val title = doc.selectFirst("h1.entry-title, h1.post-title, h1, .series__title h2")?.text()?.trim() ?: "Unknown Title"
    val poster = doc.selectFirst("div.thumb img, img.poster, meta[property=og:image], img.lazyload")?.let {
        it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("content") }
    }?.trim()?.let { fixUrlNull(it) }

    val synopsis = doc.selectFirst("div.entry-content p, .sinopsis, div[itemprop=description]")?.text()?.trim()

    val episodes = doc.select("div.eplister ul li, .episodelist li, #episode_related li, ul.episode-list li")
        .mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val epNumStr = el.selectFirst(".epl-num, .episode-number, span")?.text()?.trim() ?: ""
            val epNum = epNumStr.toIntOrNull() ?: Regex("""\d+""").find(epNumStr)?.value?.toIntOrNull()

            newEpisode(href) {
                this.name = "Episode ${epNum ?: "?"}"
                this.episode = epNum
            }
        }.reversed() 

    val isMovie = url.contains("/movie/") || title.lowercase().contains("movie") || title.lowercase().contains("batch") || episodes.isEmpty()

    return if (isMovie || episodes.isEmpty()) {
        newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.AnimeMovie,
            dataUrl = url  
        ) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    } else {
        newAnimeLoadResponse(
            name = title,
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = poster
            this.plot = synopsis
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}


}


