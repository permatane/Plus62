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


    // ==========================================
    // Converter: article -> SearchResponse
    // Poster: data-src > data-litespeed-src > src (skip data:image/*)
    // ==========================================
    private fun Element.toSearchResultFixed(): SearchResponse? {
        val linkEl = selectFirst("div.bsx > a") ?: return null
        val title = linkEl.attr("title").trim().ifEmpty { return null }
        val href = fixUrl(linkEl.attr("href"))

        val imgEl = linkEl.selectFirst("img")
        val posterUrl = imgEl?.let {
            it.attr("data-src")
                .ifEmpty { it.attr("data-litespeed-src") }
                .ifEmpty { it.attr("src") }
                .takeIf { s -> s.isNotEmpty() && !s.startsWith("data:image") }
        }?.let { fixUrlNull(it) }

        val isMovie = href.contains("/movie/") || title.contains("Movie", true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        return if (isMovie) {
            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val thumbImg = document.selectFirst("div.thumb img")
        val poster = thumbImg?.let {
            it.attr("data-src")
                .ifEmpty { it.attr("data-litespeed-src") }
                .ifEmpty { it.attr("src") }
                .takeIf { s -> s.isNotEmpty() && !s.startsWith("data:image") }
        }?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()


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


