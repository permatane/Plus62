package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Donghuaid : Anichin() {
    override var mainUrl              = "https://donghuaid.live"
    override var name                 = "Donghua DonghuaId"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)


    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?status=&type=&order=popular" to "Paling Populer",
        "/anime/?status=&type=movie&sub=" to "Movies",
        "/anime/?status=completed&type=&order=" to "Complete",

    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders, referer = mainUrl).document
        val results = parseDonghuaCards(document, includeSidebar = page == 1 && request.data.removeSuffix("/") == mainUrl.removeSuffix("/"))
            .distinctBy { it.url.normalizedKey() }
        val hasNext = document.selectFirst(
            "a.next[href], a.next.page-numbers[href], link[rel=next], .hpage a[href*='page=${page + 1}'], a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']"
        ) != null
        return newHomePageResponse(request.name, results, hasNext)
    }
    
   override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        document.select("div.mobius > select.mirror > option")
                .mapNotNull {
                    fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src"))
                }
                .amap {
                    if (it.startsWith(mainUrl)) {
                        app.get(it, referer = "$mainUrl/").document.select("iframe").attr("src")
                    } else {
                        it
                    }
                }
                .amap { loadExtractor(httpsify(it), data, subtitleCallback, callback) }

        return true
    }
}


