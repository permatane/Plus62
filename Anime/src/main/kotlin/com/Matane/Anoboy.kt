package com.Matane

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.content.Context
import java.nio.charset.Charset

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        var context: Context? = null

        fun getStatus(t: String): ShowStatus {
            return when (t.trim()) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        // ✅ Pastikan tidak mengembalikan null
        fun Element?.getIframeAttr(): String? {
            return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
                ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this?.attr("src")?.takeIf { it.isNotBlank() }
        }

        fun Element.getImageAttr(): String {
            return when {
                hasAttr("data-src") -> attr("abs:data-src")
                hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
                hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
                else -> attr("abs:src")
            }
        }
    }

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?sub=&order=latest" to "Baru ditambahkan",
        "anime/?status=&type=&order=popular" to "Terpopuler",
        "anime/?sub=&order=rating" to "Rating Tertinggi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title").ifBlank { selectFirst("div.tt")?.text() } ?: return null
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        val isSeries = href.contains("/series/", true) || href.contains("drama", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("div.tt")?.text()?.trim() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }
        val description = document.select("div.entry-content p").joinToString("\n") { it.text() }.trim()

        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            h * 60 + m
        }
        val tags = document.select("div.genxed a").map { it.text() }
        val rating = document.selectFirst("div.rating strong")
            ?.text()?.replace("Rating", "", ignoreCase = true)?.trim()?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val statusText = document.selectFirst("div.info-content div.spe span")
            ?.ownText()?.replace(":", "")?.trim()
        val status = getStatus(statusText)
        val recommendations = document.select("div.listupd article.bs").mapNotNull { it.toRecommendResult() }

        // ✅ Dibalik lalu diberi nomor urut yang benar
        val episodes = document.select("div.eplister ul li a")
            .reversed()
            .mapIndexed { index, aTag ->
                val href = fixUrl(aTag.attr("href"))
                val num = index + 1
                newEpisode(href) {
                    this.name = "Episode $num"
                    this.episode = num
                }
            }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                trailer?.let { addTrailer(it) }
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    // ✅ Fungsi Pemutar Video — Referer otomatis untuk Blogger
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundAny = false
        val refererUrl = data

        // Atur Referer agar token Blogger sah
        AnoboyBlogger.refererOverride = refererUrl

        fun httpsify(url: String): String {
            return when {
                url.startsWith("//") -> "https:${url}"
                url.startsWith("/") -> "$mainUrl$url"
                !url.startsWith("http") -> "https://$url"
                else -> url
            }
        }

        // 1. Player Utama — ✅ Perbaikan: aman dari null
        document.selectFirst("div.player-embed iframe")
            .getIframeAttr()
            ?.let { httpsify(it) }
            ?.let { url ->
                if (loadExtractor(url, refererUrl, subtitleCallback, callback)) {
                    foundAny = true
                }
            }

        // 2. Semua Mirror Server — ✅ Perbaikan: ganti toString → decodeToString
        val utf8 = Charsets.UTF_8
        val iso = Charsets.ISO_8859_1

        for (opt in document.select("select.mirror option[value]:not([disabled])")) {
            val b64 = opt.attr("value").replace("\\s".toRegex(), "")
            if (b64.isBlank()) continue

            val decoded = runCatching {
                val bytes = base64Decode(b64)
                val text = bytes.decodeToString(0, bytes.size, utf8)
                AnoboyBlogger.extractUrlFromContent(text)
            }.getOrNull() ?: runCatching {
                val bytes = base64Decode(b64)
                val text = bytes.decodeToString(0, bytes.size, iso)
                AnoboyBlogger.extractUrlFromContent(text)
            }.getOrNull()

            decoded?.let { httpsify(it) }?.let { url ->
                if (loadExtractor(url, refererUrl, subtitleCallback, callback)) {
                    foundAny = true
                }
            }
        }

        return foundAny
    }
}
