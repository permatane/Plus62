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

        // Ambil nonce untuk mishafilter (dari script atau meta)
        val nonceScript = document.select("script:contains(misha), script:contains(lazySearch)").html()
        val mishaNonce = Regex("""nonce["']?\s*:\s*["']([^"']+)["']""").find(nonceScript)?.groupValues?.get(1) ?: ""

        // Episodes: Prioritaskan AJAX mishafilter untuk full list
        val episodes = if (true) {  // Selalu coba AJAX dulu
            val seriesId = animeCard.selectFirst(".bookmark")?.attr("data-id") ?: ""  // data-id="153447"
            val numEpRaw = animeCard.selectFirst(".info-item:contains(Episodes)")?.text()?.trim() ?: "1"
            val numEp = numEpRaw.replace(Regex("\\D+"), "")  // "24 Episodes" → "24"

            if (seriesId.isBlank() || numEp == "1") {
                // Fallback statis jika gagal
                document.select(".meta-episodes .meta-episode-item a.ep-link").map {
                    val episodeStr = it.text().trim()
                    val episodeNum = Regex("Episode (\\d+)").find(episodeStr)?.groupValues?.get(1)?.toIntOrNull()
                    val link = fixUrl(it.attr("href"))
                    newEpisode(link) { this.episode = episodeNum }
                }.reversed()
            } else {
                val ajaxResponse = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "mishafilter",
                        "series_id" to seriesId,
                        "misha_number_of_results" to numEp,
                        "misha_order_by" to "date-DESC",
                        "nonce" to mishaNonce  // Tambah nonce jika dibutuhkan
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )

                val contentHtml = ajaxResponse.parsed<EpResponse>().content
                Jsoup.parse(contentHtml).select("li").mapNotNull {
                    val aTag = it.selectFirst("a") ?: return@mapNotNull null
                    val episodeStr = aTag.text().trim()
                    val episodeNum = Regex("Episode\\s?(\\d+)").find(episodeStr)?.groupValues?.get(1)?.toIntOrNull()
                    val link = fixUrl(aTag.attr("href"))
                    newEpisode(link) { this.episode = episodeNum }
                }.reversed()
            }
        } else {
            // Fallback statis
            document.select(".meta-episodes .meta-episode-item a.ep-link").map {
                val episodeStr = it.text().trim()
                val episodeNum = Regex("Episode (\\d+)").find(episodeStr)?.groupValues?.get(1)?.toIntOrNull()
                val link = fixUrl(it.attr("href"))
                newEpisode(link) { this.episode = episodeNum }
            }.reversed()
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

        val document = app.get(data).document

        val nonce =
            document.select("script#ajax_video-js-extra").attr("src").substringAfter("base64,")
                .let {
                    AppUtils.parseJson<Map<String, String>>(base64Decode(it).substringAfter("="))["nonce"]
                }

        document.select(".container1 > ul > li:not(.boxtab)").amap {
            val dataPost = it.attr("data-post")
            val dataNume = it.attr("data-nume")
            val dataType = it.attr("data-type")

            val iframe = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "player_ajax",
                    "post" to dataPost,
                    "nume" to dataNume,
                    "type" to dataType,
                    "nonce" to "$nonce"
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).document.selectFirst("iframe")?.attr("src")

            loadExtractor(iframe ?: return@amap, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun Element.getImageAttr(): String? {
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
}
