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
        val url = "$mainUrl/${request.data}&page=$page".replace("//&", "/?")  // Fix double slash
        val doc = app.get(url).document
        val home = doc.select("div.listupd > article, article.bs, .bsx").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = doc.select("a.next").isNotEmpty()
        )
    }
private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = a.attr("title").ifBlank { selectFirst(".tt h2, h3")?.text()?.trim() ?: "" }
        if (title.isEmpty()) return null
        val href = fixUrl(a.attr("href"))
        val poster = selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-lazy-src") } }
        }?.let { fixUrlNull(it) }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }


}


