package com.Javsek

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Javsek : MainAPI() {
    override var mainUrl = "https://javsek.net"
    override var name = "Javsek"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
  
private val mainHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/indo-sub/" to "Jav Sub indo",
        "$mainUrl/jav/" to "Latest Updates",
        
//        "$mainUrl/category/uncensored/page/" to "Uncensored",
//       "$mainUrl/category/censored/page/" to "Censored",
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1)
            mainUrl + request.data
        else
            "${mainUrl}${request.data}page/$page/"

        val document = app.get(url).document

        val items = doc.select("article").mapNotNull { article ->
            val a = article.selectFirst("a") ?: return@mapNotNull null
            val title = article.selectFirst("h2")?.text() ?: return@mapNotNull null
            val posterUrl = article.selectFirst("img")?.attr("src")


        newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders                     
            }
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNextPage = true
        )
    }

    // ================= SEARCH =================

    override suspend fun search(query: String,page: Int): SearchResponseList {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document

        return document.select("article").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst("h2")?.text() ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")

        newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mainHeaders                     
            }
        }
    }

    // ================= DETAIL =================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: "Javsek Video"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }


    // ================= VIDEO =================

    override fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("iframe[src]").forEach { iframe ->
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