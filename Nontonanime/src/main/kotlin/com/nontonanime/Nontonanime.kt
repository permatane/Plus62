package com.Nontonanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class Nontonanime : MainAPI() {
    override var mainUrl = "https://s7.nontonanimeid.boats"
    override var name = "NontonAnime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )
      
    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "ongoing-list/?sort=date&mode=sort" to " Ongoing List",
        "popular-series/" to "Popular Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}").document
        val home = document.select(".animeseries").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst(".title")?.text() ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document

        return document.select(".result > ul > li").mapNotNull {
            val title = it.selectFirst("h2")!!.text().trim()
            val poster = it.selectFirst("img")?.getImageAttr()
            val tvType = getType(
                it.selectFirst(".boxinfores > span.typeseries")!!.text()
            )
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist = false, subExist = true)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) {
            url
        } else {
            app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href")
        }

        val req = app.get(fixUrl ?: return null)
        mainUrl = getBaseUrl(req.url)
        val document = req.document

        val animeCard = document.selectFirst("div.anime-card") ?: return null

        val title = animeCard.selectFirst(".anime-card__sidebar img")?.attr("alt")?.trim()
            ?.removePrefix("Nonton ")?.removeSuffix(" Sub Indo") ?: return null

        val poster = animeCard.selectFirst(".anime-card__sidebar img")?.getImageAttr()

        val tags = animeCard.select(".anime-card__genres a.genre-tag").map { it.text() }

        val aired = animeCard.selectFirst("li:contains(Aired:)")?.text()?.substringAfter("Aired:")?.trim() ?: ""
        val year = Regex("(\\d{4})").find(aired)?.groupValues?.get(1)?.toIntOrNull()

        val statusText = animeCard.selectFirst(".info-item.status-airing")?.text()?.trim() ?: ""
        val status = getStatus(statusText)

        val typeText = animeCard.selectFirst(".anime-card__score .type")?.text()?.trim() ?: ""
        val type = getType(typeText)

        val rating = animeCard.selectFirst(".anime-card__score .value")?.text()?.trim()

        val description = animeCard.selectFirst(".synopsis-prose p")?.text()?.trim() ?: "No Plot Found"

        val trailer = animeCard.selectFirst("a.trailerbutton")?.attr("href")

        // Extract nonce & post_id untuk AJAX episodes
        val lazyScript = document.select("script:contains(myLazySearchSeries)").html()
        val nonce = Regex("""nonce:\s*['"]([^'"]+)['"]""").find(lazyScript)?.groupValues?.get(1) ?: ""
        val postId = Regex("""post_id:\s*['"]([^'"]+)['"]""").find(lazyScript)?.groupValues?.get(1)
            ?: animeCard.selectFirst(".bookmark")?.attr("data-id") ?: ""

        // Coba load full episode list via search_endpoint (query kosong = full list)
        val episodes = mutableListOf<Episode>()
        if (postId.isNotBlank() && nonce.isNotBlank()) {
            val response = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "search_endpoint",
                    "nonce" to nonce,
                    "query" to "",
                    "post_id" to postId
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            if (response.startsWith("[") && response.endsWith("]")) {
                val items = AppUtils.parseJson<List<EpisodeItem>>(response)
                items.forEach { item ->
                    val epNum = Regex("Episode\\s?(\\d+)").find(item.title)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(fixUrl(item.url)) {
                        this.episode = epNum
                        this.name = item.title
                    })
                }
            }
        }

        // Jika AJAX gagal atau kosong, fallback ke statis (minimal Pertama & Terakhir)
        if (episodes.isEmpty()) {
            document.select(".meta-episodes .meta-episode-item a.ep-link").mapTo(episodes) {
                val episodeStr = it.text().trim()
                val epNum = Regex("Episode (\\d+)").find(episodeStr)?.groupValues?.get(1)?.toIntOrNull()
                val link = fixUrl(it.attr("href"))
                newEpisode(link) { this.episode = epNum }
            }
        }

        val recommendations = document.select(".result > li").mapNotNull {
            val epHref = it.selectFirst("a")!!.attr("href")
            val epTitle = it.selectFirst("h3")!!.text()
            val epPoster = it.selectFirst(".top > img")?.getImageAttr()
            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            addScore(rating)
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )).document

        // Extract nonce dengan beberapa metode fallback
        var nonce = ""
        val scriptExtra = document.selectFirst("script#ajax_video-js-extra")
        
        if (scriptExtra != null) {
            nonce = scriptExtra.html().substringAfter("nonce\":\"").substringBefore("\"")
                .takeIf { it.isNotBlank() } 
                ?: scriptExtra.html().substringAfter("'nonce\":\"").substringBefore("\"")
            
            if (nonce.isBlank() && scriptExtra.hasAttr("src") && scriptExtra.attr("src").contains("base64,")) {
                val base64Part = scriptExtra.attr("src").substringAfter("base64,")
                try {
                    val decoded = base64Decode(base64Part)
                    nonce = AppUtils.parseJson<Map<String, String>>(decoded.substringAfter("="))["nonce"] ?: ""
                } catch (_: Exception) {}
            }
        }

        if (nonce.isBlank()) {
            val anyScript = document.select("script:contains(player_ajax)").html()
            nonce = Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(anyScript)?.groupValues?.get(1) ?: ""
        }

        if (nonce.isBlank()) return false

        document.select(
            ".container1 > ul > li:not(.boxtab), " +
            ".server-list > li, " +
            ".servers > li, " +
            ".list-server > li, " +
            ".anime-card__main ul li:not(.boxtab)"
        ).amap {
            val dataPost = it.attr("data-post").takeIf { it.isNotBlank() } ?: return@amap
            val dataNume = it.attr("data-nume")
            val dataType = it.attr("data-type")

            val iframeResponse = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "player_ajax",
                    "post" to dataPost,
                    "nume" to dataNume,
                    "type" to dataType,
                    "nonce" to nonce
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document

            val iframe = iframeResponse.selectFirst("iframe")?.attr("src") ?: ""
            if (iframe.isNotBlank()) {
                loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private data class EpResponse(
        @JsonProperty("posts") val posts: String?,
        @JsonProperty("max_page") val max_page: Int?,
        @JsonProperty("found_posts") val found_posts: Int?,
        @JsonProperty("content") val content: String
    )

    private data class EpisodeItem(
        val url: String,
        val title: String,
        val date: String
    )
}
