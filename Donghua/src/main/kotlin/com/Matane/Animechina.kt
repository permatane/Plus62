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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
       )
    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "ongoing/" to "Ongoing"
    )
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (request.data.isEmpty()) "/page/$page/" else request.data + "&page=$page"
        val url = "$mainUrl$path".replace("//", "/")

        val response = app.get(url, headers = headers, timeout = 50)
        if (response.code != 200) {
            // Log untuk debug
            println("Request ke $url gagal: ${response.code}")
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val doc = response.document

        // Selector utama dari snippet kamu
        val items = doc.select("div.series__card").mapNotNull { it.toSearchResult() }

        // Fallback jika selector utama gagal (situs mungkin punya variasi)
        if (items.isEmpty()) {
            doc.select("article, .bsx, .bs, .tip, .anime-item, div.series__thumbnail").mapNotNull { it.toSearchResult() }
        }

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
        val linkElem = selectFirst("a[href][title]") ?: return null
        val title = linkElem.attr("title").trim().ifEmpty {
            selectFirst("div.series__title h2")?.text()?.trim() ?: ""
        }
        if (title.isEmpty()) return null

        val href = fixUrl(linkElem.attr("href"))

        // Prioritaskan data-src untuk lazyload (seperti di snippet)
        val imgElem = selectFirst("img.lazyload")
        val poster = imgElem?.let {
            it.attr("data-src").ifBlank {
                it.attr("data-lazy-src").ifBlank { it.attr("src") }
            }
        }?.trim()?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

}


