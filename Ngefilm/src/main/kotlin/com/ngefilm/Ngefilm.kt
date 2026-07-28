package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mainPageOf

class Ngefilm : MainAPI() {

    override var mainUrl = "https://new39.ngefilm.site"
  //  private var directUrl: String? = null
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

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
}
