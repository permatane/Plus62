package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.toNewSearchResponseList
import org.jsoup.nodes.Element
import java.net.URI


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
            "/page/%d/?s&search=advanced&post_type=movie" to "Movies",
            "/page/%d/?s=&search=advanced&post_type=tv" to "Series",
            "country/usa/page/%d/" to "Film Barat",
            "country/indonesia/page/%d/" to "Indonesia",
            "country/japan/page/%d/" to "Jepang"
        )


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document =
            app.get(
                "$mainUrl/${request.data.format(page)}"
            ).document


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
            selectFirst("h2.entry-title > a")
                ?.text()
                ?.trim()
                ?: return null


        val href =
            fixUrl(
                selectFirst("a")!!
                    .attr("href")
            )


        val poster =
            fixUrlNull(
                selectFirst("a img")
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


        val doc =
            app.get(
                "$mainUrl/page/$page/?s=$query"
            )
            .document


        return doc
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
                .select("iframe")
                .attr("src")



        if (iframe.isEmpty())
            return false




        /*
        Buka player JWPlayer
        */


        val playerHtml =
            app.get(
                iframe,
                referer = data
            )
            .text




        /*
        Cari master.m3u8
        */


        val m3u8 =
            Regex(
                """https?://[^"' ]+master\.m3u8[^"' ]*"""
            )
            .find(playerHtml)
            ?.value



        if (m3u8 == null)
            return false




        callback(

            ExtractorLink(

                source = "Ngefilm21",

                name = "Morencius HLS",

                url = m3u8,

                referer = "https://morencius.com/",

                quality = Qualities.Unknown.value,

                type = ExtractorLinkType.M3U8

            )

        )


        return true

    }



    private fun getBaseUrl(url:String):String{

        return URI(url).let {

            "${it.scheme}://${it.host}"

        }

    }

}
