package com.anymovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Anymovies : MainAPI() {

    // ==========================================
    // KONSTANTA
    // ==========================================
    private val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    // Regex ekstrak URL dari onclick="_loadP(this,'https://...')"
    private val onclickRegex = Regex("""_loadP\s*\(\s*[^,]+,\s*['"]([^'"]+)['"]\s*\)""")

    // ==========================================
    // PROVIDER CONFIG
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
        Pair("$mainUrl/series", "TV Show"), 
        Pair("$mainUrl/featured/1", "Featured Movies"),
        Pair("$mainUrl/top-films-of-all-time", "Top Movies"),
        Pair("$mainUrl/filter/search.php?genre=Action", "Action"), 
        Pair("$mainUrl/filter/search.php?genre=Horor", "Horror"),
        Pair("$mainUrl/filter/search.php?genre=Comedy", "Comedy"),
        Pair("$mainUrl/filter/search.php?genre=Science%20Fiction", "Sci-Fi"), 
        Pair("$mainUrl/filter/search.php?genre=Romance", "Romance"),
        Pair("$mainUrl/filter/search.php?genre=Thriller", "Thriller"), 
        Pair("$mainUrl/filter/search.php?adult=1", "Recently Added"), 
    )

    // ==========================================
    // GET MAIN PAGE
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page" else request.data

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to CHROME_UA)
        ).document

        // SELECTOR: semua <a> yang href-nya diawali /movie/ atau /tv-show/ DAN punya <img>
        val items = document.select("a[href]")
            .filter { el ->
                val h = el.attr("href")
                (h.startsWith("/movie/") || h.startsWith("/tv-show/") || h.startsWith("/series/"))
                        && el.selectFirst("img") != null
            }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    // ==========================================
    // SEARCH — multi-endpoint + fallback client-side
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val headers = mapOf("User-Agent" to CHROME_UA)

        // Coba berbagai endpoint search
        val endpoints = listOf(
            "$mainUrl/?s=$enc",
            "$mainUrl/search/$enc",
            "$mainUrl/movies?s=$enc",
            "$mainUrl/movies?search=$enc",
        )

        for (ep in endpoints) {
            runCatching {
                val doc = app.get(ep, headers = headers).document
                val results = doc.select("a[href]")
                    .filter { el ->
                        val h = el.attr("href")
                        (h.startsWith("/movie/") || h.startsWith("/tv-show/"))
                                && el.selectFirst("img") != null
                    }
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
                if (results.isNotEmpty()) return results
            }
        }

        // FALLBACK: ambil halaman /movies lalu filter client-side
        runCatching {
            val doc = app.get("$mainUrl/movies", headers = headers).document
            val low = query.lowercase()
            return doc.select("a[href]")
                .filter { el ->
                    val h = el.attr("href")
                    (h.startsWith("/movie/") || h.startsWith("/tv-show/"))
                            && el.selectFirst("img") != null
                }
                .mapNotNull { it.toSearchResponse() }
                .filter { it.name.lowercase().contains(low) }
                .distinctBy { it.url }
        }

        return emptyList()
    }

    // ==========================================
    // ELEMENT -> SEARCH RESPONSE
    // ==========================================
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        if (!href.startsWith("/movie/") && !href.startsWith("/tv-show/") && !href.startsWith("/series/")) return null

        val fullUrl = fixUrl(href)

        val imgEl = selectFirst("img")
        val posterUrl = imgEl?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
                .ifEmpty { it.attr("data-lazy-src") }
                .ifEmpty { it.attr("data-original") }
        }?.let { fixUrlNull(it) }

        val rawTitle = imgEl?.attr("alt")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length < 150 }
            ?: text().trim()
                .replace(Regex("""\(\d{4}\).*"""), "")
                .trim()
                .takeIf { it.isNotEmpty() && it.length < 150 }
            ?: return null

        val yearPattern = Regex("""\((\d{4})\)""")
        val year = yearPattern.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""-(\d{4})(?:/|-|$)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

        val cleanTitle = rawTitle.replace(yearPattern, "").trim()

        val isTv = href.startsWith("/tv-show/") || href.startsWith("/series/")
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
    // LOAD DETAIL — 🔥 INTI PERBAIKAN
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to CHROME_UA)
        ).document

        // ---- TITLE ----
        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.select("title").text()
                .replace("Full Movie Online", "")
                .replace("Watch", "")
                .replace("Online Free", "")
                .trim()
            ?: return null

        val yearMatch = Regex("""\((\d{4})\)""").find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        val title = rawTitle.replace(Regex("""\(\d{4}\)"""), "").trim()

        // ---- POSTER ----
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?.let { fixUrlNull(it) }

        // ---- SCORE ----
        val ratingText = document.selectFirst("div:contains(Ratings:)")?.ownText()
            ?.replace("Ratings:", "")?.trim()
        val tmdbScore = Regex("""([\d.]+)""").find(ratingText ?: "")
            ?.groupValues?.get(1)?.toDoubleOrNull()

        // ---- METADATA ----
        val released = document.selectFirst("div:contains(Released:)")?.ownText()
            ?.replace("Released:", "")?.trim()
        val releaseYear = year ?: Regex("""(\d{4})""").find(released ?: "")
            ?.groupValues?.get(1)?.toIntOrNull()

        val duration = document.selectFirst("div:contains(Runtime:)")?.ownText()
            ?.replace("Runtime:", "")?.trim()

        val genres = document.selectFirst("div:contains(Genres:)")
            ?.select("a")?.map { it.text().trim() }

        val actors = document.selectFirst("div:contains(Actors:)")
            ?.select("a")?.map { it.text().trim() }

        val plot = document.selectFirst("h2:contains(Synopsis) + p, div:contains(Synopsis) + p, .synopsis, #synopsis, [class*=desc]")
            ?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // ---- YOUTUBE TRAILER ----
        val trailer = document.selectFirst("#mediaActionsMount, [data-trailer]")
            ?.attr("data-trailer")?.takeIf { it.isNotEmpty() }

        // ---- REKOMENDASI ----
        val recommendations = document.select("a[href]")
            .filter { el ->
                val h = el.attr("href")
                (h.startsWith("/movie/") || h.startsWith("/tv-show/"))
                        && el.selectFirst("img") != null
                        && fixUrl(h) != url
            }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(16)

        // ==========================================
        // 🔥 EKSTRAK 7 SERVER DARI button.server-btn
        // ==========================================
        // <button class="server-btn" onclick="_loadP(this,'https://playmogo.com/e/xxx')">
        //   <span class="pc">..icon..</span> Doodstream
        // </button>
        val serverButtons = document.select("button.server-btn[onclick]")
        val serverList = mutableListOf<Pair<String, String>>()

        serverButtons.forEach { btn ->
            val onclick = btn.attr("onclick")
            val m = onclickRegex.find(onclick) ?: return@forEach
            val embedUrl = m.groupValues[1]
            if (!embedUrl.startsWith("http")) return@forEach

            // Nama server dari text button (bersihkan whitespace & icon svg)
            val srvName = btn.text()
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifEmpty { detectServerName(embedUrl) }

            serverList.add(Pair(srvName, embedUrl))
        }

        // Fallback: cari iframe jika tombol tidak ada
        if (serverList.isEmpty()) {
            document.select("iframe#playerFrame[src], iframe[src*=embed], iframe[src*=movie]").forEach { fr ->
                val src = fr.attr("src")
                if (src.startsWith("http")) {
                    serverList.add(Pair(detectServerName(src), src))
                }
            }
        }

        // Encode ke format pipe-delimited untuk loadLinks()
        val finalData = if (serverList.isNotEmpty()) {
            serverList.distinctBy { it.second }.joinToString("|||") {
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
                this.score = Score.from10(tmdbScore)
                this.recommendations = recommendations
                addDuration(duration)
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, finalData) {
                this.posterUrl = poster
                this.year = releaseYear
                this.plot = plot
                this.tags = genres
                this.score = Score.from10(tmdbScore)
                this.recommendations = recommendations
                addDuration(duration)
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    }

    // ==========================================
    // DETEKSI SERVER — SESUAI TOMBOL DI UI SITUS
    // ==========================================
    private fun detectServerName(url: String): String {
        val lower = url.lowercase()
        return when {
            "playmogo" in lower -> "Doodstream"
            "vsembed" in lower -> "Video Src"
            "vidfast.pro" in lower || "vidfast" in lower -> "VidFast"
            "primesrc" in lower -> "MovietoPlay"
            "multiembed" in lower -> "Super Server"
            "videasy" in lower -> "VidEasy"
            "peachify" in lower -> "VidPlay"
            "dood" in lower -> "Doodstream"
            "vidplay" in lower -> "VidPlay"
            "vidsrc" in lower -> "Video Src"
            "mixdrop" in lower -> "Mixdrop"
            "streamtape" in lower -> "Streamtape"
            "mp4upload" in lower -> "Mp4Upload"
            "filemoon" in lower -> "Filemoon"
            else -> "Server"
        }
    }

    // ==========================================
    // LOAD LINKS — panggil extractor per server
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
                if (parts.size >= 2) Pair(parts[0].trim(), parts[1].trim()) else null
            }

            // Server yang didukung built-in CloudStream:
            // Doodstream, Vidplay, Mixdrop, Streamtape, Mp4Upload, Filemoon,
            // GenericM3U8 (untup vsembed, vidfast, primesrc, multiembed, videasy, peachify)
            sources.forEach { (_, embedUrl) ->
                runCatching {
                    loadExtractor(
                        url = embedUrl,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    success = true
                }.onFailure {
                    runCatching { extractManualFallback(embedUrl, subtitleCallback, callback) }
                }
            }
            return success
        }

        // Fallback: data = URL halaman -> parse ulang tombol
        if (data.startsWith("http")) {
            runCatching {
                val doc = app.get(data, headers = mapOf("User-Agent" to CHROME_UA)).document
                doc.select("button.server-btn[onclick]").forEach { btn ->
                    onclickRegex.find(btn.attr("onclick"))?.groupValues?.get(1)?.let { u ->
                        if (u.startsWith("http")) {
                            runCatching {
                                loadExtractor(u, mainUrl, subtitleCallback, callback)
                                success = true
                            }
                        }
                    }
                }
            }
            return success
        }

        return false
    }

    // ==========================================
    // FALLBACK EKSTRAK MANUAL
    // ==========================================
    private suspend fun extractManualFallback(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val srvName = detectServerName(url)
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Referer" to "$mainUrl/"
                )
            )
            val body = resp.text

            val patterns = listOf(
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]+\.mp4[^'"]*)['"]"""),
                Regex("""file\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
                Regex("""src\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]"""),
                Regex("""hls\s*:\s*['"]([^'"]+)['"]"""),
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
                        newExtractorLink(
                            source = srvName,
                            name = srvName,
                            url = video,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = q
                        }
                    )
                }
            }

            // Subtitle
            Regex("""['"](https?://[^'"]+\.(?:vtt|srt)[^'"]*)['"]""").findAll(body).forEach {
                subtitleCallback(SubtitleFile(lang = "English", url = it.groupValues[1]))
            }
        }
    }
}
