package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Donghuaid : Anichin() {

    override var mainUrl = "https://donghuaid.live"
    override var name = "Donghua DonghuaID"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?status=&type=&order=popular" to "Paling Populer",
        "/anime/?status=&type=movie&sub=" to "Movies",
        "/anime/?status=completed&type=&order=" to "Complete",
    )

    // ==========================================
    // Helper poster — seperti Animekhor.getsrcAttribute()
    // Prioritas: data-src > data-litespeed-src > src (skip data:image SVG LiteSpeed)
    // ==========================================
    private fun Element.getPoster(): String? {
        val src = attr("src")
        val dataSrc = attr("data-src")
        val dataLs = attr("data-litespeed-src")
        return when {
            dataSrc.isNotEmpty() && !dataSrc.startsWith("data:image") -> dataSrc
            dataLs.isNotEmpty() && !dataLs.startsWith("data:image") -> dataLs
            src.isNotEmpty() && !src.startsWith("data:image") -> src
            else -> null
        }
    }

    // ==========================================
    // Override getMainPage — HANYA untuk fix poster
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").documentLarge
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResultFixed() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResultFixed(): SearchResponse {
        val linkEl = select("div.bsx > a")
        val title = linkEl.attr("title")
        val href = fixUrl(linkEl.attr("href"))
        val posterUrl = fixUrlNull(linkEl.selectFirst("img")?.getPoster())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ==========================================
    // Override search — HANYA untuk fix poster
    // ==========================================
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("${mainUrl}/page/$page/?s=$query").documentLarge
        return document.select("div.listupd > article")
            .mapNotNull { it.toSearchResultFixed() }
            .toNewSearchResponseList()
    }

    // ==========================================
    // Override load — fix poster detail + poster episode
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().toString()
        val href = document.selectFirst("div.eplister > ul > li a")?.attr("href") ?: ""

        // ✅ Poster detail: getPoster() > og:image
        val poster = document.selectFirst("div.thumb img")?.getPoster()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().toString()
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val episodes = document.select("div.eplister > ul > li").map { info ->
                val href1 = info.select("a").attr("href")
                // ✅ Poster episode juga fix
                val posterr = info.selectFirst("a img")?.getPoster() ?: ""
                val epnum = info.selectFirst("div.epl-num")?.text()?.toIntOrNull()
                newEpisode(href1) {
                    this.episode = epnum
                    this.name = "Episode $epnum"
                    this.posterUrl = posterr
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ==========================================
    // Override loadLinks — seperti Kazefuri tapi TANPA .amap (deprecated)
    // Hydrax otomatis ditangani oleh class Hydrax di Extractor.kt
    // ==========================================
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
            .map {
                if (it.startsWith(mainUrl)) {
                    runCatching {
                        app.get(it, referer = "$mainUrl/").document.select("iframe").attr("src")
                    }.getOrDefault(it)
                } else it
            }
            .map { httpsify(it) }
            // ✅ .forEach BUKAN .amap (deprecated di Plus62)
            .forEach { loadExtractor(it, data, subtitleCallback, callback) }

        return true
    }
}
