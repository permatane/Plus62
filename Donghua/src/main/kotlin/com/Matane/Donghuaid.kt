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
        val document = app.get("$mainUrl/${request.data}page/$page/").document
        val home = document.select("div.listupd > article, div.excels > div.bs").mapNotNull { element ->
            element.toSearchResult()
        }
        
        return HomePageResponse(arrayListOf(HomePageList(request.name, home)))
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2, h3, .tt")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        
        // Mengambil poster dengan prioritas pada data-src (lazy load), atribut src, atau class ts-post-image
        val posterUrl = fixUrl(
            this.selectFirst("img.ts-post-image, img")?.let { img ->
                img.attr("data-src").takeIf { it.isNotEmpty() } 
                    ?: img.attr("src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-lazyloaded") // Fallback pengaman jika diperlukan
            } ?: ""
        )
        
        val epNum = this.selectFirst(".epx, .episode")?.text()?.let { 
            Regex("\\d+").find(it)?.value?.toIntOrNull() 
        }

        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.lastEpisode = epNum
        }
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


