package com.rebahin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import org.jsoup.nodes.Element

open class Rebahin : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.rest"
    private var directUrl: String? = null
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    open var mainServer = "https://rebahinxxi3.rest"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls =
                listOf(
                        Pair("Featured", "xtab1"),
                        Pair("Film Terbaru", "xtab2"),
                        Pair("Romance", "xtab3"),
                        Pair("Drama", "xtab4"),
                        Pair("Action", "xtab5"),
                        Pair("Scifi", "xtab6"),
                        Pair("Tv Series Terbaru", "stab1"),
                        Pair("Anime Series", "stab2"),
                        Pair("Drakor Series", "stab3"),
                        Pair("West Series", "stab4"),
                        Pair("China Series", "stab5"),
                        Pair("Japan Series", "stab6"),
                )

        val items = ArrayList<HomePageList>()

        for ((header, tab) in urls) {
            try {
                val home =
                        app.get("$mainUrl/wp-content/themes/indoxxi/ajax-top-$tab.php")
                                .document
                                .select("div.ml-item")
                                .mapNotNull { it.toSearchResult() }
                items.add(HomePageList(header, home))
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.mli-info > h2")?.text() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val type =
                if (this.select("span.mli-quality").isNotEmpty()) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            val posterUrl = fixUrlNull(this.select("img").attr("src"))
            val quality = getQualityFromString(this.select("span.mli-quality").text().trim())
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            val posterUrl =
                    fixUrlNull(
                            this.select("img").attr("src").ifEmpty {
                                this.select("img").attr("data-original")
                            }
                    )
            val episode =
                    this.select("div.mli-eps > span")
                            .text()
                            .replace(Regex("[^0-9]"), "")
                            .toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val req = app.get(url)
        directUrl = getBaseUrl(req.url)
        val document = req.document
        val title = document.selectFirst("h3[itemprop=name]")!!.ownText().trim()
        val poster =
                document.select(".mvic-desc > div.thumb.mvic-thumb")
                        .attr("style")
                        .substringAfter("url(")
                        .substringBeforeLast(")")
        val tags = document.select("span[itemprop=genre]").map { it.text() }

        val year =
                Regex("([0-9]{4}?)-")
                        .find(
                                document.selectFirst(".mvici-right > p:nth-child(3)")!!
                                        .ownText()
                                        .trim()
                        )
                        ?.groupValues
                        ?.get(1)
                        .toString()
                        .toIntOrNull()
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.select("span[itemprop=reviewBody] > p").text().trim()
        val trailer = fixUrlNull(document.selectFirst("div.modal-body-trailer iframe")?.attr("src"))
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()
        val duration =
                document.selectFirst(".mvici-right > p:nth-child(1)")!!
                        .ownText()
                        .replace(Regex("[^0-9]"), "")
                        .toIntOrNull()
        val actors = document.select("span[itemprop=actor] > a").map { it.select("span").text() }

        val baseLink = fixUrl(document.select("div#mv-info > a").attr("href"))

        return if (tvType == TvType.TvSeries) {
            val episodes =
                    app.get(baseLink)
                            .document
                            .select("div#list-eps > a")
                            .map { Pair(it.text(), it.attr("data-iframe")) }
                            .groupBy { it.first }
                            .map { eps ->
                                newEpisode(
                                        eps.value.map { fixUrl(base64Decode(it.second)) }.toString()){
                                        this.name = eps.key
                                        this.episode = eps.key.filter { it.isDigit() }.toIntOrNull()
                                }
                            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val links =
                    app.get(baseLink)
                            .document
                            .select("div#server-list div.server-wrapper div[id*=episode]")
                            .map { fixUrl(base64Decode(it.attr("data-iframe"))) }
                            .toString()
            newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = mutableListOf<String>()

        if (data.startsWith("http")) {
            sources.add(data.trim())
        } else {
            // Fallback pola lama jika data adalah list
            data.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() && it.startsWith("http") }
                .forEach { sources.add(it) }
        }

        sources.amap { src ->
            safeApiCall {
                if (src.contains(mainServer) || src.contains("rebahin")) {
                    invokeRebahinNewPlayer(src, subtitleCallback, callback)
                } else {
                    loadExtractor(src, directUrl ?: mainUrl, subtitleCallback, callback)
                }
            }
        }

        return sources.isNotEmpty()
    }

    private suspend fun invokeRebahinNewPlayer(
            url: String,
            subCallback: (SubtitleFile) -> Unit,
            sourceCallback: (ExtractorLink) -> Unit
    ) {
        val realUrl = fixUrl(url)
        var response = app.get(realUrl, referer = directUrl ?: mainUrl, allowRedirects = true, timeout = 30)

        // Ikuti redirect jika ada
        if (response.isSuccessful && response.url.toString() != realUrl) {
            response = app.get(response.url.toString(), referer = realUrl)
        }

        val doc = response.document
        val html = doc.outerHtml()

        // Extract m3u8 dari script (pola umum 2025+)
        val m3u8Links = Regex("""(https?://[^\s"'<>]+\.m3u8)""").findAll(html).map { it.value }.toList() +
                Regex("""["']?file["']?\s*[:=]\s*["']([^"']+\.m3u8)["']""").findAll(html).map { it.groupValues[1] } +
                Regex("""["']?src["']?\s*[:=]\s*["']([^"']+\.m3u8)["']""").findAll(html).map { it.groupValues[1] }

        m3u8Links.distinct().forEach { m3u8 ->
            M3u8Helper.generateM3u8(
                    name,
                    m3u8,
                    referer = realUrl,
                    headers = mapOf("Origin" to mainServer, "Referer" to realUrl)
            ).forEach(sourceCallback)
        }

        // Subtitle tracks
        val tracksStr = Regex("""tracks\s*[:=]\s*(\[.+?\])""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: ""
        tryParseJson<List<Tracks>>("[$tracksStr]")?.forEach { track ->
            track.file?.takeIf { it.endsWith(".srt") || it.endsWith(".vtt") }?.let { file ->
                subCallback(
                        newSubtitleFile(
                                getLanguage(track.label ?: "Default"),
                                fixUrl(file)
                        )
                )
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private data class Tracks(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("label") val label: String? = null,
            @JsonProperty("kind") val kind: String? = null
    )
}
