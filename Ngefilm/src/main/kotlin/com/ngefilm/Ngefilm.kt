package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mainPageOf

class Ngefilm : MainAPI() {

    override var mainUrl = "https://new39.ngefilm.site"
    private var directUrl: String? = null
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private suspend fun updateToLatestDomain() {
        if (mainUrl.contains("ngefilm.live")) {
            val doc = app.get(mainUrl).document
            // Cari link domain baru 
            val newLink = doc.selectFirst("a[href*='ngefilm'], strong a, p a[href^='https://']")?.attr("href")
            if (!newLink.isNullOrBlank() && newLink.contains("ngefilm")) {
                mainUrl = newLink.substringBeforeLast("/", "").substringBefore("?")
            }
        }
    }

    override val mainPage =
            mainPageOf(
                    "/page/%d/?s&search=advanced&post_type=movie&index&orderby&genre&movieyear&country&quality=" to "Movies Terbaru",
       //           "" to "Movies Terbaru", 
                    "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=&quality=" to "Series Terbaru",
                    "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=drakor&movieyear=&country=&quality=" to "Series Korea",
                    "/page/%d/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality=" to "Series Indonesia",

                    "country/usa/page/%d/" to "Film Barat",
                    "country/indonesia/page/%d/" to "Film Indonesia",
                    "country/malaysia/page/%d/" to "Film Malaysia",
                    "country/philippines/page/%d/" to "Film Philippines",
                    "country/japan/page/%d/" to "Film Jepang",
                    "country/china/page/%d/" to "Film China",

            )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse { updateToLatestDomain()
    val data = request.data.format(page)
    val document = app.get("$mainUrl/$data").document
    val home = document.select("article.item").mapNotNull { it.toSearchResult() }
    return newHomePageResponse(request.name, home)
    }
}
