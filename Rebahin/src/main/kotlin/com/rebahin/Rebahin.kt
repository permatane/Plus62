private suspend fun invokeRebahinNewPlayer(
    url: String,
    subCallback: (SubtitleFile) -> Unit,
    sourceCallback: (ExtractorLink) -> Unit
) {
    val realUrl = fixUrl(url)
    val baseReferer = directUrl ?: mainUrl

    // Step 1: Load the /iembed/ page (or direct player if already there)
    var response = app.get(
        realUrl,
        referer = baseReferer,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to mainServer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin"
        ),
        allowRedirects = true,
        timeout = 45
    )

    if (!response.isSuccessful) {
        logError("iembed failed: ${response.code} - ${response.url}")
        return
    }

    // Follow any redirect (some /iembed/ redirect to player directly)
    if (response.url.toString() != realUrl) {
        response = app.get(
            response.url.toString(),
            referer = realUrl,
            headers = response.headers.toMap() // preserve previous headers if needed
        )
    }

    val doc = parse(response.text)
    var playerUrl = realUrl

    // Step 2: Detect and load nested player iframe (95.214.54.154/embed/...)
    val innerIframe = doc.selectFirst("body > iframe[src*=/embed/]")
        ?: doc.selectFirst("iframe[src*=/embed/]")
        ?: doc.selectFirst("iframe[src*=/player/]")

    if (innerIframe != null) {
        playerUrl = innerIframe.attr("abs:src").takeIf { it.isNotBlank() } ?: playerUrl
        // Load the actual player page
        response = app.get(
            playerUrl,
            referer = realUrl,  // critical: referer must be the iembed page
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
                "Referer" to realUrl,
                "Origin" to "https://rebahinxxi3.ink",
                "Accept" to "*/*"
            ),
            timeout = 60
        )

        if (!response.isSuccessful) {
            logError("Player page failed: ${response.code}")
            // Fallback: try loadExtractor on the playerUrl anyway
            loadExtractor(playerUrl, realUrl, subCallback, sourceCallback)
            return
        }
    }

    val playerDoc = parse(response.text)
    val playerHtml = playerDoc.outerHtml()

    // Step 3: Extract .m3u8 / video sources (enhanced regex for 2025+ players)
    val m3u8Candidates = mutableListOf<String>()

    // Direct URL patterns
    Regex("""(https?://[^\s"'<>)]+\.(?:m3u8|mp4|mkv|ts))""").findAll(playerHtml).forEach {
        m3u8Candidates.add(it.value)
    }

    // JWPlayer / video.js style
    Regex("""["']?(?:file|src|url|hls|manifest)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4))["']""").findAll(playerHtml).forEach {
        m3u8Candidates.add(it.groupValues[1])
    }

    // sources array
    Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""").findAll(playerHtml).forEach {
        m3u8Candidates.add(it.groupValues[1])
    }

    m3u8Candidates.distinct().forEach { candidate ->
        val isHls = candidate.contains(".m3u8") || candidate.contains(".ts")
        val quality = if (candidate.contains("1080")) Qualities.P1080.value
        else if (candidate.contains("720")) Qualities.P720.value
        else Qualities.Unknown.value

        M3u8Helper.generateM3u8(
            name,
            candidate,
            referer = playerUrl,
            headers = mapOf(
                "Referer" to playerUrl,
                "Origin" to "https://rebahinxxi3.ink",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        ).forEach { link ->
            sourceCallback(
                ExtractorLink(
                    source = this.name,
                    name = if (isHls) "HLS (${link.quality}p)" else "MP4",
                    url = link.url,
                    referer = playerUrl,
                    quality = link.quality,
                    isM3u8 = isHls,
                    headers = link.headers
                )
            )
        }
    }

    // Step 4: Subtitle extraction (if tracks present)
    val tracksRegex = Regex("""tracks\s*[:=]\s*(\[.+?\])""", RegexOption.DOT_MATCHES_ALL)
    tracksRegex.find(playerHtml)?.groupValues?.get(1)?.let { tracksJson ->
        tryParseJson<List<Tracks>>("[$tracksJson]")?.forEach { track ->
            track.file?.takeIf { it.endsWith(".srt") || it.endsWith(".vtt") || it.endsWith(".ass") }?.let { fileUrl ->
                subCallback(
                    SubtitleFile(
                        lang = getLanguage(track.label ?: "Indonesian"),
                        url = fixUrl(fileUrl)
                    )
                )
            }
        }
    }

    // Fallback: Let CloudStream's built-in extractors try (handles StreamSB, Fembed, etc. if embedded)
    if (m3u8Candidates.isEmpty()) {
        loadExtractor(playerUrl, referer = realUrl, subtitleCallback = subCallback, callback = sourceCallback)
    }
}
