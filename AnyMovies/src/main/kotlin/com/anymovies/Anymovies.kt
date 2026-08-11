package com.anymovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Anymovies : MainAPI() {

    // ==========================================
    // PROVIDER CONFIGURATION
    // ==========================================
    override var mainUrl = "https://www.downloads-anymovies.co"
    override var name = "AnyMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val providerType = ProviderType.DirectProvider
    override val usesWebView = false
    override val vpnStatus = VPNStatus.None

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // ==========================================
    // MAIN PAGE SECTIONS
    // ==========================================
    override val mainPage = mainPageOf(
        Pair("$mainUrl/movies", "Trending Movies"),
        Pair("$mainUrl/movies", "New in HD"),
        Pair("$mainUrl/movies", "Recently Added"),
        Pair("$mainUrl/featured", "Featured Movies"),
        Pair("$mainUrl/tv-shows", "TV Shows"),
        Pair("$mainUrl/top-100-movies", "Top 100 Movies"),
        Pair("$mainUrl/genre/action", "Action"),
        Pair("$mainUrl/genre/horror", "Horror"),
        Pair("$mainUrl/genre/comedy", "Comedy"),
        Pair("$mainUrl/genre/sci-fi", "Sci-Fi"),
        Pair("$mainUrl/genre/romance", "Romance"),
        Pair("$mainUrl/genre/thriller", "Thriller"),
    )

    // ==========================================
    // GET MAIN PAGE
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}/page/$page"
        } else {
            request.data
        }

        val document = app.get(url).document

        val items = document.select("a[href*=/movie/], a[href*=/tv-show/], a[href*=/series/]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ==========================================
    // SEARCH
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("a[href*=/movie/], a[href*=/tv-show/], a[href*=/series/]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    // ==========================================
    // ELEMENT -> SEARCH RESPONSE
    // ==========================================
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href") ?: return null
        if (!href.contains("/movie/") && !href.contains("/tv-show/") && !href.contains("/series/")) return null

        val fullUrl = fixUrl(href)

        val imgEl = selectFirst("img")
        val posterUrl = imgEl?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
                .ifEmpty { it.attr("data-lazy-src") }
                .ifEmpty { it.attr("data-original") }
        }?.let { fixUrlNull(it) }

        val title = selectFirst("h2, h3, h4, [class*=title], [class*=Title]")?.text()
            ?.trim()
            ?: imgEl?.attr("alt")?.trim()
            ?: attr("title")?.trim()
            ?: text().trim().takeIf { it.isNotEmpty() && it.length < 100 }
            ?: return null

        val yearPattern = Regex("""\((\d{4})\)""")
        val yearFromTitle = yearPattern.find(title)?.groupValues?.get(1)?.toIntOrNull()
        val yearFromUrl = Regex("""-(\d{4})(?:/|$)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
        val year = yearFromTitle ?: yearFromUrl

        val cleanTitle = title.replace(yearPattern, "").trim()

        val isTv = href.contains("/tv-show/") || href.contains("/series/")
        val type = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(cleanTitle, fullUrl, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fullUrl, type) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    // ==========================================
    // LOAD MEDIA DETAIL
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.select("title").text()
                .replace("Full Movie Online", "")
                .replace("Watch", "")
                .replace("Online Free", "")
                .trim()
            ?: return null

        val yearMatch = Regex("""\((\d{4})\)""").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        val title = rawTitle.replace(Regex("""\(\d{4}\)"""), "").trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: document.selectFirst("img[itemprop=image]")?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }?.let { fixUrlNull(it) }

        val ratingText = document.selectFirst("div:contains(Ratings:)")?.ownText()
            ?.replace("Ratings:", "")?.trim()
        val rating = Regex("""([\d.]+)""").find(ratingText ?: "")
            ?.groupValues?.get(1)?.toDoubleOrNull()
            ?.times(1000)?.toInt()

        val released = document.selectFirst("div:contains(Released:)")?.ownText()
            ?.replace("Released:", "")?.trim()
        val releaseYear = year ?: Regex("""(\d{4})""").find(released ?: "")
            ?.groupValues?.get(1)?.toIntOrNull()

        val duration = document.selectFirst("div:contains(Runtime:)")?.ownText()
            ?.replace("Runtime:", "")?.trim()

        val genres = document.selectFirst("div:contains(Genres:)")
            ?.select("a")?.map { it.text().trim() }

        val countries = document.selectFirst("div:contains(Countries:)")
            ?.select("a")?.map { it.text().trim() }

        val director = document.selectFirst("div:contains(Director:)")
            ?.select("a")?.map { it.text().trim() }

        val actors = document.selectFirst("div:contains(Actors:)")
            ?.select("a")?.map { it.text().trim() }

        val plot = document.selectFirst("h2:contains(Synopsis) + p, div:contains(Synopsis) + p, .synopsis, #synopsis, [class*=desc]")
            ?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val recommendations = document.select("a[href*=/movie/], a[href*=/tv-show/]")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .filter { it.url != url }
            .take(20)

        // === COLLECT SERVER SOURCES ===
        val dataUrls = mutableListOf<Pair<String, String>>()

        // 1) iframes
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && src.contains("http")) {
                dataUrls.add(Pair(detectServerName(src), src))
            }
        }

        // 2) data attributes
        document.select("[data-embed], [data-src], [data-url], [data-link]").forEach { el ->
            val embed = el.attr("data-embed").ifEmpty { el.attr("data-src") }
                .ifEmpty { el.attr("data-url") }.ifEmpty { el.attr("data-link") }
            if (embed.isNotEmpty() && embed.contains("http") && dataUrls.none { it.second == embed }) {
                val srvName = el.text().trim().ifEmpty { detectServerName(embed) }
                dataUrls.add(Pair(srvName, embed))
            }
        }

        // 3) onclick / href on buttons
        document.select("button, .tab, [class*=server], [class*=Server]").forEach { btn ->
            val onClick = btn.attr("onclick")
            val href = btn.attr("href")
            listOf(onClick, href, btn.attr("data-id")).firstOrNull { it.contains("http") }?.let { candidate ->
                Regex("""https?://[^\s"'<>)]+""").find(candidate)?.groupValues?.get(0)?.let { u ->
                    if (dataUrls.none { it.second == u }) {
                        val srvName = btn.text().trim().ifEmpty { detectServerName(u) }
                        dataUrls.add(Pair(srvName, u))
                    }
                }
            }
        }

        // 4) scan <script> tags
        val embedRx = Regex(
            """['"](https?://[^'"]*?(?:dood|vidplay|vidsrc|vidfast|movietoplay|movieplay|videasy|supervideo|mixdrop|streamtape|mp4upload|embed|player)[^'"]*)['"]""",
            RegexOption.IGNORE_CASE
        )
        document.select("script").forEach { script ->
            embedRx.findAll(script.html()).forEach { m ->
                val found = m.groupValues[1]
                if (dataUrls.none { it.second == found }) {
                    dataUrls.add(Pair(detectServerName(found), found))
                }
            }
        }

        val finalData = if (dataUrls.isNotEmpty()) {
            dataUrls.distinctBy { it.second }.joinToString("|||") {
                "${it.first}|::|${it.second}"
            }
        } else {
            url
        }

        val isTvSeries = url.contains("/tv-show/") || url.contains("/series/")

        return if (isTvSeries) {
            val episodes = listOf(
                newEpisode(finalData) {
                    this.name = "Full Episode"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = releaseYear
                this.plot = plot
                this.tags = genres
                this.rating = rating
                this.recommendations = recommendations
                addDuration(duration)
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, finalData) {
                this.posterUrl = poster
                this.year = releaseYear
                this.plot = plot
                this.tags = genres
                this.rating = rating
                this.recommendations = recommendations
                addDuration(duration)
                addActors(actors)
            }
        }
    }

    // ==========================================
    // SERVER DETECTION
    // ==========================================
    private fun detectServerName(url: String): String {
        val lower = url.lowercase()
        return when {
            "dood" in lower -> "Doodstream"
            "vidplay" in lower -> "VidPlay"
            "vidsrc" in lower -> "VideoSrc"
            "vidfast" in lower -> "VidFast"
            "movietoplay" in lower || "movieplay" in lower -> "MovietoPlay"
            "videasy" in lower -> "VidEasy"
            "super" in lower && "server" in lower -> "Super Server"
            "mixdrop" in lower -> "Mixdrop"
            "streamtape" in lower -> "Streamtape"
            "mp4upload" in lower -> "Mp4Upload"
            "upstream" in lower -> "Upstream"
            "filemoon" in lower -> "Filemoon"
            "luluvdo" in lower -> "LuluVDO"
            else -> "Server"
        }
    }

    // ==========================================
    // LOAD LINKS
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false

        if (data.contains("|::|")) {
            val sources = data.split("|||").mapNotNull { entry ->
                val parts = entry.split("|::|")
                if (parts.size >= 2) Pair(parts[0], parts[1]) else null
            }
            sources.apmap { (_, embedUrl) ->
                runCatching {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                    success = true
                }.onFailure {
                    runCatching { extractManual(embedUrl, subtitleCallback, callback) }
                }
            }
            return success
        }

        if (data.startsWith("http")) {
            val doc = app.get(data).document
            val sources = mutableListOf<Pair<String, String>>()

            doc.select("iframe[src]").forEach {
                val s = it.attr("src")
                if (s.contains("http")) sources.add(Pair(detectServerName(s), s))
            }
            doc.select("[data-embed], [data-src], [data-url]").forEach {
                val u = it.attr("data-embed").ifEmpty { it.attr("data-src") }
                    .ifEmpty { it.attr("data-url") }
                if (u.contains("http") && sources.none { s -> s.second == u })
                    sources.add(Pair(detectServerName(u), u))
            }
            val rx = Regex("""['"](https?://[^'"]*(?:dood|vidplay|vidsrc|vidfast|embed|player|stream|mixdrop)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            doc.select("script").forEach { sc ->
                rx.findAll(sc.html()).forEach { m ->
                    val u = m.groupValues[1]
                    if (sources.none { s -> s.second == u })
                        sources.add(Pair(detectServerName(u), u))
                }
            }

            if (sources.isEmpty()) return false
            sources.distinctBy { it.second }.apmap { (_, u) ->
                runCatching { loadExtractor(u, mainUrl, subtitleCallback, callback); success = true }
                    .onFailure { runCatching { extractManual(u, subtitleCallback, callback) } }
            }
            return success
        }

        return false
    }

    // ==========================================
    // MANUAL EXTRACTION FALLBACK
    // ==========================================
    private suspend fun extractManual(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resp = app.get(url, headers = mapOf("Referer" to mainUrl))
            val body = resp.text
            val srvName = detectServerName(url)

            val patterns = listOf(
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]+\.mp4[^'"]*)['"]"""),
                Regex("""file\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
                Regex("""src\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]""")
            )

            patterns.forEach { pat ->
                pat.findAll(body).forEach { m ->
                    var video = m.groupValues[1]
                    if (!video.startsWith("http")) {
                        val base = url.substring(0, url.indexOf("/", 8))
                        video = if (video.startsWith("/")) base + video else "$base/$video"
                    }
                    val q = when {
                        "1080" in video -> Qualities.P1080.value
                        "720" in video -> Qualities.P720.value
                        "480" in video -> Qualities.P480.value
                        "360" in video -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    callback(
                        ExtractorLink(
                            source = srvName,
                            name = srvName,
                            url = video,
                            referer = url,
                            quality = q,
                            isM3u8 = ".m3u8" in video
                        )
                    )
                }
            }

            Regex("""['"](https?://[^'"]+\.(?:vtt|srt)[^'"]*)['"]""").findAll(body).forEach {
                subtitleCallback(SubtitleFile(lang = "English", url = it.groupValues[1]))
            }
        }
    }
}
