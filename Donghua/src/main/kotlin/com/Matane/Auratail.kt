package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Auratail : Anichin() {
    override var mainUrl              = "https://auratail.vip"
    override var name                 = "Auratail"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

        private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

override val mainPage = mainPageOf(
        "" to "Home",                              // homepage utama
        "genre/ongoing/?order=update" to "Update Terbaru",
        "genre/ongoing/?order=popular" to "Paling Populer",
        "?s=&post_type=post" to "Semua Series",    // atau sesuaikan jika ada kategori khusus
        "genre/movie/" to "Movies / Batch"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "" -> "$mainUrl/page/$page/"
            else -> "$mainUrl/${request.data}&page=$page"
        }

        val document = app.get(url, timeout = 45).document

        val home = document.select("article.bs, article.bsx, div.listupd article, .bsx")
            .mapNotNull { it.toSearchResult() }

        return HomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                )
            ),
            hasNext = document.select("a.next, .pagination a.next").isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst("a") ?: return null
        val title = titleElement.attr("title").ifBlank { titleElement.text().trim() }
        if (title.isEmpty()) return null

        val href = fixUrl(titleElement.attr("href"))
        val poster = selectFirst("img")?.attr("abs:src", "data-src", "data-lazy-src", "src")
            ?.let { fixUrlNull(it) }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document

        return doc.select("article.bs, article.bsx, .listupd article, .result-item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 45).document

        val title = doc.selectFirst("h1.entry-title, .post-title h1, h1[itemprop=name]")
            ?.text()?.trim() ?: "No Title"

        val poster = doc.selectFirst("div.thumb img, .wp-post-image, meta[property=og:image]")
            ?.attr("abs:src", "content", "src")
            ?.let { fixUrlNull(it) }

        val synopsis = doc.selectFirst("div.entry-content p, .desc, .sinopsis, div[itemprop=description]")
            ?.text()?.trim()

        val genres = doc.select("div.gmr-movie-on a[rel=tag], .genre a, .tags a")
            .map { it.text().trim() }

        // Cek apakah series atau movie
        val isMovie = url.contains("/movie/") || doc.selectFirst("span[itemprop=duration]") != null ||
                title.lowercase().contains("batch") || title.lowercase().contains("movie")

        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.Anime) {
            val episodes = doc.select("div.episodelist ul li, #episode_related li, .eplister ul li")
                .mapNotNull { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNull null
                    val epHref = fixUrl(a.attr("href"))
                    val epText = a.text().trim()

                    val epNum = Regex("""\d+(\.\d+)?""").find(epText)?.value?.toFloatOrNull()

                    newEpisode(epHref) {
                        this.name = epText
                        this.episode = epNum?.toInt()
                    }
                }.reversed() // biasanya episode terbaru di atas

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            // Movie / Batch
            val data = doc.selectFirst("div.mobius > select.mirror > option, a.directlink, .download-button a")
                ?.attr("value", "href")?.trim() ?: ""

            newMovieLoadResponse(title, url, tvType, data.ifEmpty { url }) {
                this.posterUrl = poster
                this.plot = synopsis
                this.tags = genres
            }
        }
    }
