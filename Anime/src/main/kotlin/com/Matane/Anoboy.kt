package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.base64Decode

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    // ✅ 100% URL SESUAI ASLI Anoboy
    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?sub=&order=latest" to "Baru ditambahkan",
        "anime/?status=&type=&order=popular" to "Terpopuler",
        "anime/?sub=&order=rating" to "Rating Tertinggi",
        "anime/?status=ongoing" to "Sedang Tayang",
        "anime/?status=completed" to "Tamat",
        "anime/?genre=action" to "Action",
        "anime/?genre=fantasy" to "Fantasi",
        "anime/?genre=comedy" to "Komedi",
        "anime/?genre=romance" to "Romansa",
        "anime/?genre=isekai" to "Isekai",
        "anime/?genre=drama" to "Drama",
        "anime/?genre=adventure" to "Petualangan"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ✅ Paginasi: tambah &page=N
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}${separator}page=$page"
        }
        val document = app.get(url).documentLarge

        val home = document.select("article.bs").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("a.next, .nav-next, .page-nav a:contains(Next)").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("div.bsx > a") ?: return null
        val rawHref = link.attr("href")
        val rawTitle = this.selectFirst("h2, .tt")?.text()?.trim() ?: return null

        // Bersihkan judul: hapus "Episode XX Subtitle Indonesia"
        val cleanTitle = rawTitle
            .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Subtitle\\s+Indonesia$", RegexOption.IGNORE_CASE), "")
            .trim()

        // Ekstrak nomor episode
        val epText = this.selectFirst("span.epx, .ep")?.text()?.trim()
        val epNum = epText?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        // Normalisasi URL: /judul-episode-xx/ → /anime/judul/
        val animeHref = runCatching {
            val href = rawHref.removePrefix(mainUrl)
            val parts = href.split("/").filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && parts.last().startsWith("episode-", ignoreCase = true)) {
                val slug = parts.dropLast(1).joinToString("/")
                fixUrl("/$slug/")
            } else {
                fixUrl(rawHref)
            }
        }.getOrElse { fixUrl(rawHref) }

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotEmpty() && !it.startsWith("data:image") }
        )

        return newAnimeSearchResponse(cleanTitle, animeHref, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epNum != null) this.name = "$cleanTitle — Ep $epNum"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$mainUrl/?s=$query"
        return try {
            val doc = app.get(endpoint).documentLarge
            doc.select("article.bs").mapNotNull { it.toSearchResult() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: "Anime"
        val title = rawTitle
            .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Subtitle\\s+Indonesia$", RegexOption.IGNORE_CASE), "")
            .trim()

        val posterUrl = fixUrlNull(
            document.selectFirst("div.thumb img, .poster img, img.wp-post-image")?.attr("src")
                ?.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                ?.takeIf { !it.isNullOrEmpty() && !it.startsWith("data:image") }
        )

        val plot = document.selectFirst(".synopsis, .description, div.entry-content")?.text()?.trim()
            ?.substringBefore("Genre")?.trim()

        val genres = document.select(".genres a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val scoreText = document.selectFirst(".score, .rating, .numscore")?.text()?.trim()?.toDoubleOrNull()
        val trailerUrl = document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("src")
            ?: document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("href")

        // Ambil daftar episode dari AJAX
        val episodes = mutableListOf<Episode>()
        val ajaxUrl = if (url.endsWith("/")) "${url}ajax_episodes" else "$url/ajax_episodes"

        try {
            val ajaxDoc = app.get(ajaxUrl).document
            ajaxDoc.select("a[href*=/episode/]").forEach { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epText = ep.text().trim()
                val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                if (epUrl.isNotEmpty() && epNum != null) {
                    episodes.add(newEpisode(epUrl) {
                        this.episode = epNum
                        this.name = epText
                    })
                }
            }
        } catch (_: Exception) {
            document.select("a[href*=/episode/]").forEach { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epText = ep.text().trim()
                val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                if (epUrl.isNotEmpty() && epNum != null) {
                    episodes.add(newEpisode(epUrl) {
                        this.episode = epNum
                        this.name = epText
                    })
                }
            }
        }

        val sortedEpisodes = episodes.sortedBy { it.episode }
        val isMovie = sortedEpisodes.size <= 1 && !url.contains("/episode-", ignoreCase = true)

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                if (scoreText != null) this.score = Score.from10(scoreText)
                if (!trailerUrl.isNullOrEmpty()) addTrailer(trailerUrl)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = genres
                if (scoreText != null) this.score = Score.from10(scoreText)
                if (!trailerUrl.isNullOrEmpty()) addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var success = false

        document.select("select.mirror option, select.server option, option[value*=eyJ]").forEach { opt ->
            val b64 = opt.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            runCatching {
                val decodedHtml = base64Decode(b64)
                val decodedDoc = Jsoup.parse(decodedHtml)
                var iframeSrc = decodedDoc.selectFirst("iframe")?.attr("src") ?: return@forEach

                iframeSrc = when {
                    iframeSrc.startsWith("//") -> "https:$iframeSrc"
                    iframeSrc.startsWith("/") -> fixUrl(iframeSrc)
                    else -> iframeSrc
                }

                if (iframeSrc.isNotEmpty()) {
                    success = true
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                }
            }
        }

        if (!success) {
            document.select("iframe[src]").forEach { iframe ->
                var src = iframe.attr("src")
                src = when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> fixUrl(src)
                    else -> src
                }
                if (src.isNotEmpty() && !src.startsWith(mainUrl)) {
                    success = true
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return success
    }
}
