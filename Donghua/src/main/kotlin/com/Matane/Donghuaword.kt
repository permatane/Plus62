package com.Matane

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor


class Donghuaword  : Animekhor() {
    override var mainUrl              = "https://donghuaworld.com"
    override var name                 = "Donghuaword"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val title= document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href=document.selectFirst(".eplister li > a")?.attr("href") ?:""
        var poster = document.select("meta[property=og:image]").attr("content")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type=document.selectFirst(".spe")?.text().toString()
        val tvtag=if (type.contains("Movie")) TvType.Movie else TvType.TvSeries
        return if (tvtag == TvType.TvSeries) {
            val Eppage= document.selectFirst(".eplister li > a")?.attr("href") ?:""
            val doc= app.get(Eppage).documentLarge
            val epposter = doc.select("meta[property=og:image]").attr("content")
            val episodes=doc.select("div.episodelist > ul > li").map { info->
                        val href1 = info.select("a").attr("href")
                        val episode = info.select("a span").text().substringAfter("-").substringBeforeLast("-")
                        newEpisode(href1)
                        {
                            this.name=episode
                            this.posterUrl=epposter
                        }
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            if (poster.isEmpty())
            {
                poster=document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().toString()
            }
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).documentLarge
        document.select("div.server-item a").map {
            val base64=it.attr("data-hash")
            val decodedUrl = base64Decode(base64)
            val regex = Regex("""src=["']([^"']+)["']""",RegexOption.IGNORE_CASE)
            val matchResult = regex.find(decodedUrl)
            val url = matchResult?.groups?.get(1)?.value ?: "Not found"
            loadExtractor(url, referer = mainUrl, subtitleCallback, callback)

        }
        return true
    }
}
