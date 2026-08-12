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
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}${separator}page=$page"
        }
        val document = app.get(url).documentLarge

        val home = document.select("article.bs").mapNotNull { it.toSearchResultItem() }
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

    private fun Element.toSearchResultItem(): SearchResponse? {
        val link = this.selectFirst("div.bsx > a") ?: return null
        val rawHref = link.attr("href")
        val rawTitleText = this.selectFirst("h2, .tt")?.text()?.trim() ?: return null

        val cleanTitle = rawTitleText
            .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Subtitle\\s+Indonesia$", RegexOption.IGNORE_CASE), "")
            .trim()

        val episodeInfo = this.selectFirst("span.epx, .ep")?.text()?.trim()
        val episodeNumber = episodeInfo?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val displayTitle = if (episodeNumber != null) {
            "$cleanTitle — Ep $episodeNumber"
        } else {
            cleanTitle
        }

        val animeHref = runCatching {
            val hrefClean = rawHref.removePrefix(mainUrl)
            val parts = hrefClean.split("/").filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && parts.last().startsWith("episode-", ignoreCase = true)) {
                val slug = parts.dropLast(1).joinToString("/")
                fixUrl("/$slug/")
            } else {
                fixUrl(rawHref)
            }
        }.getOrElse { fixUrl(rawHref) }

        val posterImageUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotEmpty() && !it.startsWith("data:image") }
        )

        return newAnimeSearchResponse(displayTitle, animeHref, TvType.Anime) {
            this.posterUrl = posterImageUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val endpoint = "$mainUrl/?s=$query"
        return try {
            val doc = app.get(endpoint).documentLarge
            doc.select("article.bs").mapNotNull { it.toSearchResultItem() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val rawPageTitle = document.selectFirst("h1")?.text()?.trim() ?: "Anime"
        val titleClean = rawPageTitle
            .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Subtitle\\s+Indonesia$", RegexOption.IGNORE_CASE), "")
            .trim()

        val posterUrlFinal = fixUrlNull(
            document.selectFirst("div.thumb img, .poster img, img.wp-post-image")?.attr("src")
                ?.ifEmpty { document.selectFirst("meta[property=og:image]")?.attr("content") }
                ?.takeIf { !it.isNullOrEmpty() && !it.startsWith("data:image") }
        )

        val plotSummary = document.selectFirst(".synopsis, .description, div.entry-content")?.text()?.trim()
            ?.substringBefore("Genre")?.trim()

        val genreList = document.select(".genres a, .tags a").map { it.text().trim() }.filter { it.isNotEmpty() }
        val scoreValue = document.selectFirst(".score, .rating, .numscore")?.text()?.trim()?.toDoubleOrNull()
        val trailerLink = document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("src")
            ?: document.selectFirst("iframe[src*=youtube], a[href*=youtu]")?.attr("href")

        val episodesList = mutableListOf<Episode>()
        val ajaxEndpoint = if (url.endsWith("/")) "${url}ajax_episodes" else "$url/ajax_episodes"

        try {
            val ajaxDocument = app.get(ajaxEndpoint).document
            ajaxDocument.select("a[href*=/episode/]").forEach { epAnchor ->
                val epFullUrl = fixUrl(epAnchor.attr("href"))
                val epDisplayText = epAnchor.text().trim()
                val extractedNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epDisplayText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epDisplayText)?.groupValues?.get(1)?.toIntOrNull()

                if (epFullUrl.isNotEmpty() && extractedNum != null) {
                    episodesList.add(newEpisode(epFullUrl) {
                        this.episode = extractedNum
                        this.name = epDisplayText
                    })
                }
            }
        } catch (_: Exception) {
            document.select("a[href*=/episode/]").forEach { epAnchor ->
                val epFullUrl = fixUrl(epAnchor.attr("href"))
                val epDisplayText = epAnchor.text().trim()
                val extractedNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(epDisplayText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(epDisplayText)?.groupValues?.get(1)?.toIntOrNull()

                if (epFullUrl.isNotEmpty() && extractedNum != null) {
                    episodesList.add(newEpisode(epFullUrl) {
                        this.episode = extractedNum
                        this.name = epDisplayText
                    })
                }
            }
        }

        val sortedEpisodes = episodesList.sortedBy { it.episode }
        val isMovieFlag = sortedEpisodes.size <= 1 && !url.contains("/episode-", ignoreCase = true)

        return if (isMovieFlag) {
            newMovieLoadResponse(titleClean, url, TvType.Movie, url) {
                this.posterUrl = posterUrlFinal
                this.plot = plotSummary
                this.tags = genreList
                if (scoreValue != null) this.score = Score.from10(scoreValue)
                if (!trailerLink.isNullOrEmpty()) addTrailer(trailerLink)
            }
        } else {
            newTvSeriesLoadResponse(titleClean, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = posterUrlFinal
                this.plot = plotSummary
                this.tags = genreList
                if (scoreValue != null) this.score = Score.from10(scoreValue)
                if (!trailerLink.isNullOrEmpty()) addTrailer(trailerLink)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageDocument = app.get(data).document
        var linkFound = false

        pageDocument.select("select.mirror option, select.server option, option[value*=eyJ]").forEach { optElement ->
            val base64Value = optElement.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            runCatching {
                val htmlContent = base64Decode(base64Value)
                val decodedPage = Jsoup.parse(htmlContent)
                var iframeAddress = decodedPage.selectFirst("iframe")?.attr("src") ?: return@forEach

                iframeAddress = when {
                    iframeAddress.startsWith("//") -> "https:$iframeAddress"
                    iframeAddress.startsWith("/") -> fixUrl(iframeAddress)
                    else -> iframeAddress
                }

                if (iframeAddress.isNotEmpty()) {
                    linkFound = true
                    loadExtractor(iframeAddress, data, subtitleCallback, callback)
                }
            }
        }

        if (!linkFound) {
            pageDocument.select("iframe[src]").forEach { iframeElement ->
                var directSrc = iframeElement.attr("src")
                directSrc = when {
                    directSrc.startsWith("//") -> "https:$directSrc"
                    directSrc.startsWith("/") -> fixUrl(directSrc)
                    else -> directSrc
                }
                if (directSrc.isNotEmpty() && !directSrc.startsWith(mainUrl)) {
                    linkFound = true
                    loadExtractor(directSrc, data, subtitleCallback, callback)
                }
            }
        }

        return linkFound
    }
}
