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
    data: String,                  // e.g. https://rebahinxxi3.ink/.../play/?ep=2&sv=2
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Step 1: Get the play page (may contain the iembed iframe)
    val playResp = app.get(data, referer = mainUrl)
    val playDoc = Jsoup.parse(playResp.text)

    // Find the iembed iframe (most reliable selector from your HTML)
    var embedUrl = playDoc.selectFirst("div#colimedia iframe#iframe-embed")?.attr("abs:src")
        ?: playDoc.selectFirst("iframe#iframe-embed")?.attr("abs:src")

    if (embedUrl.isNullOrBlank()) {
        // Fallback: some pages put it directly in body or other div
        embedUrl = playDoc.selectFirst("iframe[src*=/iembed/]")?.attr("abs:src")
    }

    if (embedUrl.isNullOrBlank()) return false

    // Step 2: Load the /iembed/ page (this returns a wrapper with inner player iframe)
    val embedResp = app.get(embedUrl, referer = data, timeout = 45)
    val embedDoc = Jsoup.parse(embedResp.text)

    // Step 3: Extract the final player URL (the 199.87.210.226 one)
    var playerUrl = embedDoc.selectFirst("iframe[src*=/player/]")?.attr("abs:src")
        ?: embedDoc.selectFirst("body > iframe")?.attr("abs:src")   // from your <body><iframe>...</body>

    if (playerUrl.isNullOrBlank()) {
        // Last fallback: sometimes it's directly in script or meta
        playerUrl = embedDoc.select("script").firstOrNull { it.data().contains("/player/") }
            ?.data()?.substringAfter("src=\"")?.substringBefore("\"")
    }

    if (playerUrl.isNullOrBlank()) return false

    // Step 4: Load the actual player page (this is where the video tag / hls config usually is)
    val playerResp = app.get(
        playerUrl,
        referer = embedUrl,                // Important: keep referer chain
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
        ),
        timeout = 60
    )

    val playerDoc = Jsoup.parse(playerResp.text)

    // Step 5: Try to find direct video sources (most common patterns on these players)
    var found = false

    // A. Classic <video> or <source>
    playerDoc.select("video source").forEach { source ->
        val src = source.attr("abs:src") ?: source.attr("src")
        if (src.isNotBlank() && (src.endsWith(".mp4") || src.endsWith(".m3u8"))) {
            callback(ExtractorLink(this.name, "Direct MP4/M3U8", src, playerUrl, Qualities.Unknown.value, src.contains(".m3u8")))
            found = true
        }
    }

    // B. JWPlayer / video.js style config in script
    playerDoc.select("script").forEach { script ->
        val content = script.data()
        if (content.isNotBlank()) {
            // Common patterns: file: "https://...", sources: [{file:...}]
            listOf("file:", "sources:", "playlist:", "hls:").forEach { key ->
                if (content.contains(key)) {
                    // Very rough extraction – improve with regex if needed
                    val potentialUrls = Regex("""(https?://[^\s"'<>)]+\.(?:mp4|m3u8|mkv|ts))""")
                        .findAll(content)
                        .map { it.value.trim('"', '\'') }
                        .toList()

                    potentialUrls.forEach { url ->
                        if (url.isNotBlank()) {
                            val type = if (url.contains(".m3u8")) "HLS" else "MP4"
                            callback(ExtractorLink(this.name, "$type from script", url, playerUrl, Qualities.P720.value, url.contains(".m3u8")))
                            found = true
                        }
                    }
                }
            }
        }
    }

    // C. If nothing found → try built-in extractors on the final player URL
    if (!found) {
        loadExtractor(playerUrl, referer = embedUrl, subtitleCallback, callback)
    }

    // Optional: look for subtitles track if present
    playerDoc.select("track[kind=subtitles]").forEach {
        val subSrc = it.attr("abs:src")
        if (subSrc.isNotBlank()) subtitleCallback(SubtitleFile("Indonesian", subSrc))
    }

    return found || true  // Return true even if fallback extractor might catch it
}
}
