package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Yunshanid : MainAPI() {

    // ==========================================
    // PROVIDER CONFIG
    // ==========================================
    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val providerType = ProviderType.DirectProvider

    // 🔥 WAJIB: YunshanID = Next.js 100% Client-Side Rendered
    // Tanpa WebView, JS tidak jalan → tidak ada data di HTML statis
    override val usesWebView = true
    override val vpnStatus = VPNStatus.None

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries,
        TvType.Movie,
    )

    // ==========================================
    // MAIN PAGE SECTIONS
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/" to "Paling Populer",
        "$mainUrl/jadwal" to "Jadwal Rilis",
        "$mainUrl/" to "Donghua Ongoing",
    )

    // ==========================================
    // GET MAIN PAGE
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document

        // Section "UPDATE TERBARU" — card dengan poster + judul + episode
        val items = document.select("a[href]").filter { el ->
            val href = el.attr("href")
            // Card link ke /synopsis/ID atau langsung ke /episode/ID/EP
            (href.contains("/synopsis/") || href.contains("/episode/"))
                    && el.selectFirst("img") != null
                    && el.text().isNotBlank()
        }.mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .take(30)

        return newHomePageResponse(request.name, items)
    }

    // ==========================================
    // SEARCH
    // ==========================================
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = query.replace(" ", "+")
        val document = app.get("$mainUrl/?search=$encoded").document

        return document.select("a[href]").filter { el ->
            val href = el.attr("href")
            (href.contains("/synopsis/") || href.contains("/episode/"))
                    && el.selectFirst("img") != null
                    && el.text().lowercase().contains(query.lowercase())
        }.mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
            .toNewSearchResponseList(true)
    }

    // ==========================================
    // Converter: Element -> SearchResponse
    // ==========================================
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null

        // Normalisasi: jika link ke episode, ambil anime ID-nya
        val animeUrl = when {
            href.contains("/synopsis/") -> fixUrl(href)
            href.contains("/episode/") -> {
                // /episode/116/1 → /synopsis/116
                val parts = href.trim('/').split("/")
                if (parts.size >= 2) fixUrl("/synopsis/${parts[1]}") else fixUrl(href)
            }
            else -> fixUrl(href)
        }

        val imgEl = selectFirst("img")
        val posterUrl = imgEl?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
                .ifEmpty { it.attr("data-lazy-src") }
                .takeIf { s -> s.isNotBlank() && !s.startsWith("data:image") }
        }?.let { fixUrlNull(it) }

        // Judul: dari img alt, atau text dalam link
        val title = imgEl?.attr("alt")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length < 150 }
            ?: text().trim()
                .replace(Regex("""\bEP?\s*\d+.*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\(\d{4}\)"""), "")
                .trim()
                .takeIf { it.isNotEmpty() && it.length < 150 }
            ?: return null

        // Episode number dari badge
        val epNum = Regex("""EP?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text())?.groupValues?.get(1)?.toIntOrNull()

        // Type: jika ada badge Movie atau dari URL
        val isMovie = text().contains("Movie", true) || href.contains("movie", true)
        val type = if (isMovie) TvType.Movie else TvType.Anime

        return if (isMovie) {
            newMovieSearchResponse(title, animeUrl, type) {
                this.posterUrl = posterUrl
            }
        } else {
            newAnimeSearchResponse(title, animeUrl, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ==========================================
    // LOAD — halaman /synopsis/{ID}
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Extract ID dari URL: /synopsis/116 → 116
        val animeId = Regex("""/synopsis/(\d+)""").find(url)?.groupValues?.get(1) ?: "0"

        // Judul
        val title = document.selectFirst("h1, h2, [class*=title], [class*=Title]")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: throw ErrorLoadingException("Title not found")

        // Poster
        val poster = document.selectFirst("img[alt*=$title], div[class*=poster] img, div[class*=thumb] img, [class*=Poster] img")
            ?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
                    .takeIf { s -> s.isNotBlank() && !s.startsWith("data:image") }
            }?.let { fixUrlNull(it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        // Sinopsis
        val description = document.selectFirst("[class*=sinopsis], [class*=Sinopsis], [class*=desc], [class*=Description], p")
            ?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // Tipe: Movie / Series
        val typeBadge = document.selectFirst("[class*=type], span:contains(SERIES), span:contains(MOVIE)")?.text() ?: ""
        val isMovie = typeBadge.contains("MOVIE", true) ||
                document.selectFirst("a[href*=movie]") != null
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        // ==========================================
        // DAFTAR EPISODE — tombol angka 1,2,3,4,5,6...
        // Setiap tombol link ke /episode/{animeId}/{ep}
        // ==========================================
        val episodeButtons = document.select("a[href*=/episode/]").filter { el ->
            val href = el.attr("href")
            href.contains("/episode/$animeId/") || Regex("""/episode/\d+/\d+""").containsMatchIn(href)
        }

        if (tvType == TvType.TvSeries && episodeButtons.isNotEmpty()) {
            val episodes = episodeButtons.mapNotNull { btn ->
                val href = fixUrl(btn.attr("href"))
                val epText = btn.text().trim()
                val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    this.episode = epNum
                    this.name = "Episode $epNum"
                    this.posterUrl = poster
                }
            }.sortedBy { it.episode }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // ---- MOVIE ----
        // Jika Movie, link player = episode 1 atau halaman /episode/{id}/1
        val moviePlayerUrl = if (episodeButtons.isNotEmpty()) {
            fixUrl(episodeButtons.first().attr("href"))
        } else {
            fixUrl("/episode/$animeId/1")
        }

        return newMovieLoadResponse(title, url, TvType.Movie, moviePlayerUrl) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ==========================================
    // LOAD LINKS — halaman /episode/{ID}/{EP}
    // Server: Gdrive, Ok.ru, Dailymotion (semua built-in di Plus62)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false
        val document = app.get(data).document

        // 1) Cari iframe player langsung (current server)
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isBlank() || src.startsWith("about:")) return@forEach
            val embedUrl = httpsify(src)

            runCatching {
                // Plus62 built-in extractor menangani:
                // Gdrive → Gdriveplayer()
                // Ok.ru → OkRuSSL() / OkRuHTTP()
                // Dailymotion → Dailymotion()
                loadExtractor(embedUrl, data, subtitleCallback, callback)
                success = true
            }
        }

        // 2) Cari semua server dari dropdown "PILIH SERVER"
        // Setiap option value biasanya berisi URL embed atau ID server
        document.select("select option[value]").forEach { opt ->
            val valAttr = opt.attr("value").trim()
            if (valAttr.isBlank()) return@forEach

            val embedUrl = when {
                valAttr.startsWith("http") -> httpsify(valAttr)
                valAttr.contains("gdrive", true) || valAttr.contains("blogger", true) -> {
                    // Jika value adalah ID, construct URL Gdrive
                    "https://drive.google.com/file/d/$valAttr/preview"
                }
                valAttr.length in 20..60 && !valAttr.contains(" ") -> {
                    // Coba sebagai Gdrive ID
                    "https://drive.google.com/file/d/$valAttr/preview"
                }
                else -> null
            } ?: return@forEach

            runCatching {
                loadExtractor(embedUrl, data, subtitleCallback, callback)
                success = true
            }
        }

        // 3) Fallback: scan seluruh script untuk URL embed
        document.select("script").forEach { sc ->
            val html = sc.html()
            Regex("""['"](https?://[^'"]*(?:gdrive|blogger|ok\.ru|dailymotion|okru|player)[^'"]*)['"]""")
                .findAll(html).forEach { m ->
                    runCatching {
                        loadExtractor(httpsify(m.groupValues[1]), data, subtitleCallback, callback)
                        success = true
                    }
                }
        }

        return success
    }
}
