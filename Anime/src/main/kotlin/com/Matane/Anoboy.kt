package com.Matane

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import android.content.Context
import java.util.regex.Pattern

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

        // Ekstrak URL langsung dari teks atau HTML hasil dekode
        fun extractUrlFromContent(content: String): String? {
            // Pola 1: Cari tag iframe -> ambil src/data-src
            val srcPattern = Pattern.compile("""src\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
            val dataSrcPattern = Pattern.compile("""data-src\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)

            var matcher = srcPattern.matcher(content)
            if (matcher.find()) {
                val url = matcher.group(1)
                if (url!!.startsWith("//") || url.startsWith("http")) return url
            }

            matcher = dataSrcPattern.matcher(content)
            if (matcher.find()) {
                val url = matcher.group(1)
                if (url!!.startsWith("//") || url.startsWith("http")) return url
            }

            // Pola 2: Isi langsung berupa URL
            val trimmed = content.trim()
            if (trimmed.startsWith("http") || trimmed.startsWith("//")) return trimmed

            return null
        }
    }

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?sub=&order=latest" to "Baru ditambahkan",
        "anime/?status=&type=&order=popular" to "Terpopuler",
        "anime/?sub=&order=rating" to "Rating Tertinggi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}".plus("&page=$page")
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()
        } ?: return null
        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        val isSeries = href.contains("/series/", true) || href.contains("drama", true)
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
        return document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("div.tt")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }
        val description = document.select("div.entry-content p")
            .joinToString("\n") { it.text() }
            .trim()

        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            h * 60 + m
        }
        val country = document.selectFirst("span:matchesOwn(Negara:)")?.ownText()?.trim()
        val type = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()
        val tags = document.select("div.genxed a").map { it.text() }
        val rating = document.selectFirst("div.rating strong")
            ?.text()
            ?.replace("Rating", "", ignoreCase = true)
            ?.trim()
            ?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val statusText = document.selectFirst("div.info-content div.spe span")
            ?.ownText()
            ?.replace(":", "")
            ?.trim()
        val status = getStatus(statusText)

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendResult() }

        // === PERBAIKAN: Urutan & penomoran episode ===
        val episodeElements = document.select("div.eplister ul li a")
        val episodes = episodeElements
            .reversed() // terbaru di atas → urut dari episode 1
            .mapIndexed { index, aTag ->
                val href = fixUrl(aTag.attr("href"))
                val episodeNum = index + 1
                newEpisode(href) {
                    this.name = "Episode $episodeNum"
                    this.episode = episodeNum
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

    // === FUNGSI UTAMA DIPERBAIKI: Ekstraksi link video ===
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    var foundAny = false
    val refererUrl = data  // URL halaman episode = Referer sah

    // ⭐ Beri tahu ekstraktor Blogger asal halamannya
    AnoboyBlogger.refererOverride = refererUrl

    // Fungsi bantu normalisasi URL
    fun httpsify(url: String): String {
        return when {
            url.startsWith("//") -> "https:${url}"
            url.startsWith("/") -> "$mainUrl$url"
            !url.startsWith("http") -> "https://$url"
            else -> url
        }
    }

    // 1️⃣ Proses player utama
    document.selectFirst("div.player-embed iframe")
        ?.getIframeAttr()
        ?.let { httpsify(it) }
        ?.let { url ->
            if (loadExtractor(url, refererUrl, subtitleCallback, callback)) {
                foundAny = true
            }
        }

    // 2️⃣ Proses SEMUA mirror server (dekode Base64)
    for (opt in document.select("select.mirror option[value]:not([disabled])")) {
        val b64 = opt.attr("value").replace("\\s".toRegex(), "")
        if (b64.isBlank()) continue

        val decoded = runCatching {
            AnoboyBlogger.extractUrlFromContent(base64Decode(b64).toString(Charsets.UTF_8))
        }.getOrNull() ?: runCatching {
            AnoboyBlogger.extractUrlFromContent(base64Decode(b64).toString(Charsets.ISO_8859_1))
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
