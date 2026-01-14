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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )
    
    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "ongoing/" to "Ongoing"
    )
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.ifEmpty { "/page/$page/" }
        val url = "$mainUrl$path".replace("//", "/")
        val doc = app.get(url, headers = headers, timeout = 45).document

        val items = doc.select(
            "article.bs, article.bsx, div.listupd article, .tip, .anime-item, .grid-item, .card, .post-item"
        ).mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = doc.select("a.next, .pagination a[href*='page=']").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = selectFirst("a[href]") ?: return null
        val title = linkElem.attr("title").ifBlank {
            selectFirst("h2, h3, .tt, .title")?.text()?.trim() ?: ""
        }
        if (title.isEmpty()) return null

        val href = fixUrl(linkElem.attr("href"))

        val imgElem = selectFirst("img")
        val poster = imgElem?.let {
            it.attr("src").ifBlank {
                it.attr("data-src").ifBlank {
                    it.attr("data-lazy-src").ifBlank { it.attr("data-original") }
                }
            }
        }?.trim()?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

}


