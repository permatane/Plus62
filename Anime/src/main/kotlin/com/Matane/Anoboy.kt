package com.Matane

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// ============================================================
// Extractor GOFILE CUSTOM (sesuai versi library lawas)
// Tidak butuh registerExtractorAPI (tidak tersedia)
// ============================================================
class GofileAnoboy : ExtractorApi() {
    override val name: String = "Gofile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer: Boolean = false

    private data class GofileResp<T>(val data: T?, val status: String?)
    private data class GofileServer(val server: String?)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            // 1. Ambil server
            val server = app.get("$mainUrl/rest/getServer")
                .parsedSafe<GofileResp<GofileServer>>()
                ?.data?.server ?: return@runCatching

            val contentId = url.substringAfterLast("/").substringBefore("?")

            // 2. Ambil konten
            val json = app.get(
                "https://$server.gofile.io/rest/getContent",
                params = mapOf("contentId" to contentId),
                headers = mapOf("User-Agent" to desktopUA)
            ).text

            // Parse manual link direct (hindari dependensi class bersarang)
            val linkRegex = Regex(""""link"\s*:\s*"([^"]+)"""")
            val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
            val sizeRegex = Regex(""""size"\s*:\s*(\d+)""")

            val links = linkRegex.findAll(json).map { it.groupValues[1] }.toList()
            val sizes = sizeRegex.findAll(json).map { it.groupValues[1].toLongOrNull() ?: 0L }.toList()

            links.forEachIndexed { i, direct ->
                val size = sizes.getOrNull(i) ?: 0L
                val quality = when {
                    size > 800_000_000 -> Quality.P1080.value
                    size > 300_000_000 -> Quality.P720.value
                    size > 100_000_000 -> Quality.P480.value
                    else -> Quality.Unknown.value
                }

                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = direct,
                        referer = this.mainUrl,
                        quality = quality,
                        isM3u8 = false
                    )
                )
            }
        }
    }
}

// ============================================================
// CLASS UTAMA ANOBOY
// ============================================================
class Anoboy : MainAPI() {
    // [FIX #1] Domain AKTIF per Agustus 2026
    override val mainUrl: String = "https://anoboy.be"
    override val name: String = "Anoboy"
    override val hasMainPage: Boolean = true
    override val hasDownloadSupport: Boolean = true
    override val supportedTypes: Set<TvType> = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        // Header anti-Cloudflare (sama persis pola Ngefilm)
        val desktopUA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

        val defaultHeaders: Map<String, String> = mapOf(
            "User-Agent" to desktopUA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.8,en-US;q=0.5,en;q=0.3",
            "Referer" to "https://anoboy.be"
        )
    }

    private val gofileExtractor = GofileAnoboy()

    private suspend fun getDoc(url: String): Document {
        return app.get(url, headers = defaultHeaders).document
    }

    // ============================================================
    // HALAMAN UTAMA
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc(mainUrl)
        val items = ArrayList<HomePageList>()

        // Episode Terbaru
        val latest = doc.select(".post-show article").mapNotNull { it.toSearch() }
        if (latest.isNotEmpty()) items.add(HomePageList("Episode Terbaru", latest))

        // Populer Sidebar
        val popular = doc.select(".sidebar li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }.cleanTitle()
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val img = el.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = img
                addDubStatus(DubStatus.Subbed)
            }
        }
        if (popular.isNotEmpty()) items.add(HomePageList("Populer", popular))

        // [FIX ERROR #3] Pakai helper newHomePageResponse (deprecated constructor)
        return newHomePageResponse(items, hasNext = false)
    }

    // ============================================================
    // PENCARIAN
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = getDoc(url)
        return doc.select(".result-item, .post-show article").mapNotNull { it.toSearch() }
    }

    // ============================================================
    // LOAD DETAIL SERIES / EPISODE
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = runCatching { getDoc(url) }.getOrNull() ?: return null

        val rawTitle = doc.selectFirst("h1.entry-title, h1.title")?.text() ?: return null
        val title = rawTitle.cleanTitle()

        val poster = doc.selectFirst(".post-body img, .thumb img, .poster img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
        val sinopsis = doc.select(".sinopsis p, .post-body p")
            .firstOrNull { it.text().length > 80 }?.text()
        val genre = doc.select(".genre a, .tagcloud a").map { it.text().trim() }

        val isEpisodePage = url.contains(Regex("/episode-\\d+", RegexOption.IGNORE_CASE))

        // ===== KASUS 1: Halaman EPISODE LANGSUNG =====
        if (isEpisodePage) {
            val epNum = Regex("episode[\\s-]+(\\d+)", RegexOption.IGNORE_CASE)
                .find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            // [FIX ERROR #4] TIDAK PAKAI addEpisode() — masukkan langsung sebagai Map
            // [FIX ERROR #10] TIDAK PAKAI EpisodeData — kirim URL String saja
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = genre
                this.episodes = mapOf(
                    DubStatus.Subbed to listOf(
                        // [FIX ERROR #5] Pakai newEpisode + named argument (urutan benar!)
                        newEpisode(
                            data = url,
                            name = "Episode $epNum",
                            episode = epNum,
                            posterUrl = poster
                        )
                    )
                )
            }
        }

        // ===== KASUS 2: Halaman SERIES =====
        val episodeList = doc.select(".episodelist a, .listeps a").mapNotNull { a ->
            val epTitle = a.attr("title").ifBlank { a.text() }
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val num = Regex("(\\d+)(?:\\s*end)?$", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            // [FIX ERROR #5] Parameter URUTAN & TIPE BENAR: data=String pertama
            newEpisode(
                data = epHref,
                name = epTitle,
                episode = num
            )
        }.reversed()

        if (episodeList.isEmpty()) return null

        // [FIX ERROR #6] episodes harus Map<DubStatus, List>, BUKAN List langsung
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = genre
            this.episodes = mapOf(DubStatus.Subbed to episodeList)
        }
    }

    // ============================================================
    // LOAD LINKS → PLAY VIDEO
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = runCatching { getDoc(data) }.getOrNull() ?: return false

        // Selector download sesuai struktur anoboy.be Agustus 2026
        val downloadAnchors = doc.select(".download-eps a, .download a")

        val gofileUrls = downloadAnchors.mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.contains("gofile", ignoreCase = true)) href else null
        }

        if (gofileUrls.isEmpty()) {
            // Fallback: coba semua link non-# lewat loadExtractor umum
            downloadAnchors.forEach { a ->
                val href = a.attr("href").trim()
                if (href.isNotBlank() && href != "#") {
                    runCatching {
                        loadExtractor(href, data, subtitleCallback, callback)
                    }
                }
            }
            return true
        }

        // [FIX ERROR #1, #2, #7, #8, #9] Ekstrak Gofile langsung
        // Tidak ada registerExtractorAPI → panggil getUrl() instance sendiri
        gofileUrls.forEach { goUrl ->
            runCatching {
                gofileExtractor.getUrl(goUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ============================================================
    // HELPER FUNGSI
    // ============================================================
    private fun Element.toSearch(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val title = a.attr("title").ifBlank { a.text() }.cleanTitle()
        val href = fixUrlNull(a.attr("href")) ?: return null
        val img = selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = img
            addDubStatus(DubStatus.Subbed)
        }
    }

    private fun String.cleanTitle(): String {
        return this.replace(Regex("\\s+Subtitle\\s+Indonesia.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+\\[.*?\\]"), "")
            .trim()
    }
}
