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

    // 1. Data bisa single URL atau list URL (movie / series)
    if (data.startsWith("http")) {
        sources.add(data)
    } else {
        data.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.startsWith("http") }
            .forEach { sources.add(it) }
    }

    // 2. Tidak ada link → stop
    if (sources.isEmpty()) return false

    // 3. Proses tiap source
    sources.forEach { url ->
        safeApiCall {
            when {
                // Rebahin new player (Player 1 & 2)
                url.contains("rebahin") || url.contains("/play") -> {
                    extractRebahinPlayer(
                        url,
                        subtitleCallback,
                        callback
                    )
                }

                // Fallback ke extractor Cloudstream bawaan
                else -> {
                    loadExtractor(
                        url,
                        referer = directUrl ?: mainUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    }

    return true
}

    private fun getBaseUrl(url: String): String {
    return try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        mainUrl
    }
}

  
private suspend fun extractRebahinPlayer(
    url: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    // 1. Halaman /play
    val playDoc = app.get(
        fixUrl(url),
        referer = directUrl ?: mainUrl
    ).document

    // 2. iframe iembed
    val iframeUrl = playDoc.selectFirst("iframe#iframe-embed")
        ?.attr("src")
        ?.let { fixUrl(it) }
        ?: return

    // 3. Decode source base64
    val encoded = iframeUrl.substringAfter("source=", "")
    if (encoded.isBlank()) return

    val embedUrl = base64Decode(encoded)

    // 4. Request embed server (JWPlayer)
    val embedResp = app.get(
        embedUrl,
        referer = iframeUrl,
        headers = mapOf(
            "Referer" to iframeUrl,
            "Origin" to getBaseUrl(embedUrl)
        )
    )

    val html = embedResp.text

    // 5. Extract m3u8
    val m3u8Links = mutableSetOf<String>()

    Regex("""file\s*:\s*["']([^"']+\.m3u8)""")
        .findAll(html)
        .forEach { m3u8Links.add(it.groupValues[1]) }

    Regex(
        """sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+\.m3u8)""",
        RegexOption.DOT_MATCHES_ALL
    ).findAll(html).forEach {
        m3u8Links.add(it.groupValues[1])
    }

    // 6. Kirim ke Cloudstream
    m3u8Links.forEach { m3u8 ->
        M3u8Helper.generateM3u8(
            name,
            fixUrl(m3u8),
            referer = embedUrl,
            headers = mapOf(
                "Referer" to embedUrl,
                "Origin" to getBaseUrl(embedUrl)
            )
        ).forEach(callback)
    }

    // 7. Subtitle JWPlayer
    Regex("""tracks\s*:\s*(\[[^\]]+])""", RegexOption.DOT_MATCHES_ALL)
        .find(html)
        ?.groupValues
        ?.get(1)
        ?.let { json ->
            tryParseJson<List<Tracks>>(json)?.forEach { track ->
                track.file?.let {
                    subtitleCallback(
                        SubtitleFile(
                            track.label ?: "Subtitle",
                            fixUrl(it)
                        )
                    )
                }
            }
        }
}

}
