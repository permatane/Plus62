package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.toNewSearchResponseList
import org.jsoup.nodes.Element


class Ngefilm : MainAPI() {


    override var mainUrl = "https://new39.ngefilm.site"

    override var name = "Ngefilm21"

    override var lang = "id"

    override val hasMainPage = true


    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )



    override val mainPage = mainPageOf(

        "/page/%d/?s&search=advanced&post_type=movie" to "Movies",

        "/page/%d/?s=&search=advanced&post_type=tv" to "Series",

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
                selectFirst("a")
                    ?.attr("href")
                    ?: return null
            )



        val poster =
            fixUrlNull(
                selectFirst("img")
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
            ).document



        return document
            .select("article")
            .mapNotNull {
                it.toSearchResult()
            }
            .toNewSearchResponseList()

    }








    override suspend fun load(
        url:String
    ): LoadResponse {


        val document =
            app.get(url).document



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
        val document = app.get(data).document
        val href=document.selectFirst("meta[itemprop=embedURL]")?.attr("content")
        if (href!=null)
        {
            val doc= app.get(href, referer = mainUrl).text
            val video_id=Regex("video_id\\s*=\\s*['\"`](\\w+)['\"`];").find(doc)?.groupValues?.get(1).toString()
            val m3u8url=Regex("m3u8_loader_url\\s*=\\s*['\"`]([^'\"`]+)['\"`];").find(doc)?.groupValues?.get(1).toString()
            val regex = Regex("""^(?!.*//file).*?file:\s*["']([^"']*\.vtt)["']""", RegexOption.MULTILINE)
            val matches = regex.findAll(doc)
            for (match in matches) {
                val subtitle = match.groups[1]?.value.toString()
                if (subtitle.contains("subtitles"))
                {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            "English",  // Use label for the name
                            subtitle     // Use extracted URL
                        )
                    )
                }
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = "$m3u8url$video_id",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
