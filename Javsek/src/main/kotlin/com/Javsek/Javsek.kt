package com.Javsek

import android.util.Base64
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class Javsek : MainAPI() {
    override var mainUrl = "https://javsek.net"
    override var name = "Javsek"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/indo-sub/" to "Jav Sub indo",
        "$mainUrl/jav/" to "Latest Updates",
        
//        "$mainUrl/category/uncensored/page/" to "Uncensored",
//       "$mainUrl/category/censored/page/" to "Censored",
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1)mainUrl + request.data else "${mainUrl}${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("article").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) {
            "$mainUrl/search/video/?s=$query"
        } else {
            "$mainUrl/search/video/?s=$query&page=$page"
        }

        val document = app.get(url).document
        val results = document.select("article").mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("a.thumbnail") ?: return null
        val title = link.selectFirst("span.video-title")?.text() ?: link.attr("title")
        val href = fixUrlNull(link.attr("href")) ?: return null
        val posterUrl = fixUrlNull(link.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val contentContainer = document.select("div.content-container")
        val yearText = contentContainer.text()
        val year =
            Regex("""Release Day: (\d{4})""").find(yearText)?.groupValues?.get(1)?.toIntOrNull()

        val detailBox = document.select("div.col-xs-12.col-sm-6.col-md-8")
        val tags = detailBox.select("a:has(i.fa-th-list)").map { it.text().trim() }.distinct()

        val actors = detailBox.select("a[href*='/pornstar/']").map {
            Actor(it.text().trim())
        }.ifEmpty {
            Regex("""type pornstar (.+) and""").find(description ?: "")?.groupValues?.get(1)?.let {
                listOf(Actor(it))
            }
        } ?: emptyList()

        val duration =
            document.selectFirst("meta[property=og:video:duration]")?.attr("content")?.toIntOrNull()
                ?.let { it / 60 }

        val episodes = document.select("button.button_choice_server").mapNotNull { btn ->
            val encodedEmbed = btn.attr("data-embed") ?: return@mapNotNull null
            val decodedBytes =
                Base64.decode(encodedEmbed, android.util.Base64.DEFAULT)
            val decodedUrl = String(decodedBytes, Charsets.UTF_8)
            decodedUrl
        }

        val recommendations = document.select("ul.videos.related li").mapNotNull { element ->
            val aTag = element.selectFirst("a.thumbnail") ?: return@mapNotNull null
            val recTitle =
                aTag.attr("title").ifEmpty { element.selectFirst("span.video-title")?.text() }
                    ?: return@mapNotNull null
            val recHref = aTag.attr("href") ?: return@mapNotNull null
            val recPoster = element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            addActors(actors)
            this.recommendations = recommendations
        }
    }

override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(
                    fixUrl(src),
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}