package com.Matane

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Gofile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anoboy : MainAPI() {
    // ============================================================
    // 
    // ============================================================
    override var mainUrl = "https://anoboy.be"
    override var name = "Anoboy"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // ============================================================
    // Header anti-Cloudflare + blokir bot
    // ============================================================
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to desktopUA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.8,en-US;q=0.5,en;q=0.3",
        "Referer" to mainUrl
    )

    // ============================================================
    // [FIX #3] Daftarkan Gofile — SUDAH ADA di library CloudStream!
    // Tidak perlu buat extractor sendiri, cukup register host-nya
    // ============================================================
    init {
        // Gofile resmi: https://recloudstream.github.io/dokka/library/.../-gofile/
        registerExtractorAPI(Gofile().apply {
            mainUrl = "https://gofile.io"
        })
        // Fallback host gofile.cc / gofile.wiki (mirror sering ganti)
        registerExtractorAPI(Gofile().apply {
            mainUrl = "https://gofile.cc"
        })
    }

    // ============================================================
    // Helper: request dengan header konsisten
    // ============================================================
    private suspend fun getDoc(url: String): Document {
        return app.get(url, headers = defaultHeaders).document
    }

    // ============================================================
    // HALAMAN UTAMA
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc(mainUrl)
        val items = ArrayList<HomePageList>()

        // Anime Terbaru (update-anime)
        val latest = doc.select(".post-show article").mapNotNull { el ->
            el.toSearchResponse()
        }
        if (latest.isNotEmpty()) items.add(HomePageList("Episode Terbaru", latest))

        // Anime Populer
        val popular = doc.select(".sidebar .widget:nth-child(2) li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }
            val href = fixUrl(a.attr("href"))
            val img = el.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = img
            }
        }
        if (popular.isNotEmpty()) items.add(HomePageList("Populer", popular))

        return HomePageResponse(items)
    }

    // ============================================================
    // PENCARIAN
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = getDoc(url)

        return doc.select(".result-item, .post-show article").mapNotNull { el ->
            el.toSearchResponse()
        }
    }

    // ============================================================
    // LOAD DETAIL SERIES / HALAMAN EPISODE
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = getDoc(url)

        // Deteksi: ini halaman episode BUKAN halaman series?
        val isEpisodePage = doc.selectFirst(".download-eps") != null &&
                            url.contains(Regex("/episode-\\d+", RegexOption.IGNORE_CASE))

        val title = doc.selectFirst("h1.entry-title, h1.title")?.text()
            ?.replace(Regex("\\s+Subtitle\\s+Indonesia.*$", RegexOption.IGNORE_CASE), "")
            ?.trim() ?: return null

        val poster = doc.selectFirst(".post-body img, .thumb img, .poster img")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        val sinopsis = doc.select(".sinopsis, .post-body p")
            .firstOrNull { it.text().length > 80 }
            ?.text()

        val genre = doc.select(".genre a, .tagcloud a").map { it.text().trim() }

        // ===== KASUS 1: User membuka HALAMAN EPISODE langsung =====
        if (isEpisodePage) {
            val epNum = Regex("episode[\\s-]+(\\d+)", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

            // Buat series "semu" dengan 1 episode
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = genre
                addEpisode(
                    AnoboyData(
                        name = title,
                        url = url,
                        episode = epNum,
                        posterUrl = poster
                    )
                )
            }
        }

        // ===== KASUS 2: Halaman SERIES — ambil daftar semua episode =====
        val episodes = doc.select(".episodelist a, .listeps a").mapNotNull { a ->
            val epTitle = a.attr("title").ifBlank { a.text() }
            val epHref = fixUrl(a.attr("href"))
            val num = Regex("(\\d+)(?:\\s*end)?$", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            Episode(num, epHref, epTitle)
        }.reversed() // episode tertua di atas

        if (episodes.isEmpty()) return null

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = genre
            this.episodes = episodes
        }
    }

    // ============================================================
    // [FIX UTAMA] LOAD LINKS — ekstrak Gofile & kirim ke player
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            getDoc(data)
        } catch (e: Exception) {
            return false
        }

        // [FIX #2] Selector DOWNLOAD — disesuaikan Agustus 2026
        // Struktur: <div class="download-eps">
        //             <strong>720p</strong>
        //             <a href="https://gofile.io/d/xxxx">gofile</a>
        //           </div>
        val links = doc.select(".download-eps a, .download a").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") return@mapNotNull null

            val qualityText = a.previousElementSibling()?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
                ?: Regex("(\\d{3,4})p?").find(a.parent()?.text() ?: "")
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val quality = when (qualityText) {
                in 200..399 -> Qualities.P360.value
                in 400..540 -> Qualities.P480.value
                in 650..799 -> Qualities.P720.value
                in 900..1100 -> Qualities.P1080.value
                else -> Qualities.Unknown.value
            }

            Pair(href, quality)
        }

        if (links.isEmpty()) return false

        // Untuk SETIAP link download → lewati ke extractor resmi CloudStream
        links.forEach { (url, quality) ->
            try {
                // [FIX #3] Pakai loadExtractor() — otomatis pilih Gofile extractor
                // yang sudah kita register di init{}
                loadExtractor(
                    url = url,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = { link ->
                        // Override kualitas supaya akurat di UI
                        callback.invoke(
                            link.copy(
                                quality = if (link.quality == Qualities.Unknown.value)
                                    quality else link.quality
                            )
                        )
                    }
                )
            } catch (_: Exception) {
                // Skip link gagal, lanjut ke mirror berikutnya
            }
        }

        return true
    }

    // ============================================================
    // Helper: Element → SearchResponse
    // ============================================================
    private fun Element.toSearchResponse(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val title = a.attr("title").ifBlank { a.text() }
            .replace(Regex("\\s+Subtitle\\s+Indonesia.*$", RegexOption.IGNORE_CASE), "")
            .trim()
        val href = fixUrl(a.attr("href"))
        val img = selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = img
            addDubStatus(DubStatus.Subbed)
        }
    }

    // ============================================================
    // Data class untuk episode dari halaman episode langsung
    // ============================================================
    data class AnoboyData(
        val name: String,
        val url: String,
        val episode: Int,
        val posterUrl: String? = null
    ) : EpisodeData {
        override fun toString(): String = url
    }
}
