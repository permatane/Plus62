package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Auratail : Animekhor() {
    override var mainUrl              = "https://auratail.vip"
    override var name                 = "Auratail"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Update Terbaru"
    )


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
                .amap {
                    if (it.startsWith(mainUrl)) {
                        app.get(it, referer = "$mainUrl/").document.select("iframe").attr("src")
                    } else {
                        it
                    }
                }
                .amap { loadExtractor(httpsify(it), data, subtitleCallback, callback) }

        return true
    }
}


