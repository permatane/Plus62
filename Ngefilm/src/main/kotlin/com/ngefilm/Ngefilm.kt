package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.toNewSearchResponseList
import org.jsoup.nodes.Element


class Ngefilm : MainAPI() {


    override var mainUrl = "https://new39.ngefilm.site"

    override var name = "Ngefilm21"

    override var lang = "id"

    override val hasMainPage = true


    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries,
            TvType.Anime,
            TvType.AsianDrama
        )



    override val mainPage =
        mainPageOf(

            "/page/%d/?s&search=advanced&post_type=movie" to "Movies Terbaru",

            "/page/%d/?s=&search=advanced&post_type=tv" to "Series Terbaru",

            "country/usa/page/%d/" to "Film Barat",

            "country/indonesia/page/%d/" to "Film Indonesia",

            "country/japan/page/%d/" to "Film Jepang"

        )




    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {


        val document =
            app.get(
                "$mainUrl/${request.data.format(page)}"
            )
                .document


        val home =
            document
                .select("article.item")
                .mapNotNull {
                    it.toSearchResult()
                }


        return newHomePageResponse(
            request.name,
            home
        )

    }





    private fun Element.toSearchResult(): SearchResponse? {


        val title =
            this.selectFirst("h2.entry-title > a")
                ?.text()
                ?.trim()
                ?: return null



        val href =
            fixUrl(
                this.selectFirst("a")
                    ?.attr("href")
                    ?: return null
            )



        val poster =
            fixUrlNull(
                this.selectFirst("img")
                    ?.attr("src")
            )



        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ){

            this.posterUrl = poster

        }

    }







    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {


        val document =
            app.get(
                "$mainUrl/page/$page/?s=$query"
            )
                .document



        return document
            .select("article")
            .mapNotNull {
                it.toSearchResult()
            }
            .toNewSearchResponseList()

    }








    override suspend fun load(
        url: String
    ): LoadResponse {


        val document =
            app.get(url)
                .document



        val title =
            document
                .selectFirst("h1.entry-title")
                ?.text()
                ?.trim()
                ?: "Unknown"



        val poster =
            fixUrlNull(
                document
                    .selectFirst("figure img")
                    ?.attr("src")
            )



        val description =
            document
                .selectFirst("[itemprop=description]")
                ?.text()



        val rating =
            document
                .selectFirst("[itemprop=ratingValue]")
                ?.text()



        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ){

            this.posterUrl = poster

            this.plot = description

            addScore(rating)

        }

    }









    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit

    ): Boolean {


        val document =
            app.get(data)
                .document



        /*
        Cari iframe player
        */

        val iframe =
            document
                .selectFirst("iframe")
                ?.attr("src")
                ?: return false




        val playerUrl =
            fixUrl(iframe)




        /*
        Ambil response player
        */

        val playerResponse =
            app.get(
                playerUrl,
                referer = data
            )
                .text





        /*
        Cari master.m3u8
        */

        var m3u8 =
            Regex(
                """https?://[^"'\\ ]+\.m3u8[^"'\\ ]*"""
            )
                .find(playerResponse)
                ?.value




        /*
        fallback jika player memakai escape JSON
        */

        if (m3u8 == null) {


            val apiResponse =
                app.get(
                    playerUrl,
                    headers = mapOf(
                        "Referer" to data,
                        "User-Agent" to USER_AGENT
                    )
                )
                .text



            m3u8 =
                Regex(
                    """https?://[^"'\\ ]+master\.m3u8[^"'\\ ]*"""
                )
                    .find(apiResponse)
                    ?.value

        }





        if (m3u8 == null)
            return false






        callback(

            ExtractorLink(

                source = "Ngefilm21",

                name = "Morencius HLS",

                url = m3u8,

                referer = "https://morencius.com/",

                quality = 720

            )

        )




        return true

    }

}
