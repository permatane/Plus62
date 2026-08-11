package com.Matane

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.Gdriveplayer
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.LuluStream
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import org.jsoup.Jsoup

open class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url,referer=mainUrl).documentLarge
        val extractedpack =response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = link,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }

                )
            }
        }
        return null
    }
}

class ArchiveOrgExtractor : ExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = url,
                    type = INFER_TYPE,
                    {
                        this.referer = referer ?: mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
            )
        }
}

class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class Lulustream1 : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://lulustream.com"
}

class Lulustream2 : LuluStream() {
    override val name = "Lulustream"
    override val mainUrl = "https://kinoger.pw"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}

class Vidtren: Gdriveplayer() {
    override var name = "Anichin Stream"
    override val mainUrl: String = "https://anichin.stream"
}

class P2pstream : VidStack() {
    override var mainUrl = "https://animekhor.p2pstream.vip"
}

class Filelions : VidhideExtractor() {
    override var name = "Filelions"
    override var mainUrl = "https://filelions.live"
}

class embedwish : StreamWishExtractor() {
    override var mainUrl = "https://embedwish.com"
}

class Swhoi : StreamWishExtractor() {
    override var mainUrl = "https://swhoi.com"
    override val requiresReferer = true
}

class wishfast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}
    
class VidHidePro5: VidHidePro() {
    override val mainUrl = "https://vidhidevip.com"
    override val requiresReferer = true
}
class Vidguardto1 : Vidguardto() {
    override val mainUrl = "https://bembed.net"
}

class Vidguardto2 : Vidguardto() {
    override val mainUrl = "https://listeamed.net"
}

class Vidguardto3 : Vidguardto() {
    override val mainUrl = "https://vgfplay.com"
}

open class Vidguardto : ExtractorApi() {
    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false
}

open class StreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
//        Log.d("streamrubby", "url = $url")
        val id = "embed-([a-zA-Z0-9]+)\\.html".toRegex().find(url)?.groupValues?.get(1) ?: return
//        Log.d("streamrubby", "id = $id")
        val response = app.post(
            "$mainUrl/dl", data = mapOf(
                "op" to "embed",
                "file_code" to id,
                "auto" to "1",
                "referer" to "",
            ), referer = referer
        )
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
//        Log.d("streamrubby", "m3u8 = $m3u8")
        callback.invoke(newExtractorLink(
            source = this.name,
            name = this.name,
            url  = m3u8.toString(),
            type = ExtractorLinkType.M3U8,
            {
                quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        ))
    }
}

class svanila : StreamRuby() {
    override var name = "svanila"
    override var mainUrl = "https://streamruby.net"
}

class svilla : StreamRuby() {
    override var name = "svilla"
    override var mainUrl = "https://streamruby.com"
}
    
class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()
            ?.substringAfter("{\"mp4")?.substringBefore("\"evt\":{")
        if (scriptData == null) return

        val regex = """"url":"(.*?)"|h":(.*?)\}""".toRegex()
        val matches = regex.findAll(scriptData)

        val processedUrls = mutableSetOf<String>()

        for (match in matches) {
            val rawUrl = match.groupValues[1]
            if (rawUrl.isBlank()) continue

            val cleanedUrl = rawUrl.replace("\\/", "/")
            if (!cleanedUrl.contains("rumble.com")) continue
            if (!cleanedUrl.endsWith(".m3u8")) continue
            if (!processedUrls.add(cleanedUrl)) continue

            val m3u8Response = app.get(cleanedUrl)
            val variantCount = "#EXT-X-STREAM-INF".toRegex().findAll(m3u8Response.text).count()

            if (variantCount > 1) {
                callback.invoke(
                    newExtractorLink(
                        this@Rumble.name,   // source
                        "Rumble",       // name
                        cleanedUrl,         // url
                        ExtractorLinkType.M3U8 // type
                        // initializer tidak perlu diisi
                    )
                )
                break
            }
        }
    }
}

open class Hydrax : ExtractorApi() {
    override val name = "Hydrax"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    private val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Referer" to (referer ?: "https://donghuaid.live/")
                )
            )
            val body = resp.text

            // Pola 1: m3u8 / mp4 langsung
            listOf(
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]"""),
                Regex("""['"](https?://[^'"]+\.mp4[^'"]*)['"]"""),
                Regex("""file\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
                Regex("""src\s*:\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]"""),
                Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]"""),
                Regex("""playlist\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]"""),
            ).forEach { pat ->
                pat.findAll(body).forEach { m ->
                    emit(m.groupValues[1], url, subtitleCallback, callback)
                }
            }

            // Pola 2: base64 via atob()
            Regex("""atob\(['"]([A-Za-z0-9+/=]{20,})['"]\)""").findAll(body).forEach { m ->
                runCatching {
                    val decoded = base64Decode(m.groupValues[1])
                    Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*""").find(decoded)
                        ?.groupValues?.get(0)?.let { link ->
                            emit(link, url, subtitleCallback, callback)
                        }
                }
            }

            // Pola 3: window.hydrax = {...} JSON
            Regex("""window\.hydrax\s*=\s*(\{[\s\S]*?\});""").find(body)?.groupValues?.get(1)?.let { jsonStr ->
                Regex("""['"]?['"]?(https?://[^'"]+\.m3u8[^'"]*)""").findAll(jsonStr).forEach { m ->
                    emit(m.groupValues[1], url, subtitleCallback, callback)
                }
            }

            // Pola 4: setup player dengan sources array
            Regex("""player\.setup\(\s*(\{[\s\S]*?\})""").find(body)?.groupValues?.get(1)?.let { setup ->
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(setup).forEach { m ->
                    emit(m.groupValues[1], url, subtitleCallback, callback)
                }
            }

            // Subtitle Hydrax (.vtt / .srt)
            Regex("""['"](https?://[^'"]+\.(?:vtt|srt)[^'"]*)['"]""").findAll(body).forEach {
                subtitleCallback(SubtitleFile(lang = "Indonesia", url = it.groupValues[1]))
            }
        }
    }

    private suspend fun emit(
        raw: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var video = raw
        if (!video.startsWith("http")) {
            val base = pageUrl.substring(0, pageUrl.indexOf("/", 8))
            video = if (video.startsWith("/")) base + video else "$base/$video"
        }
        val q = when {
            "1080" in video -> Qualities.P1080.value
            "720" in video -> Qualities.P720.value
            "480" in video -> Qualities.P480.value
            "360" in video -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = video,
                type = INFER_TYPE
            ) {
                this.referer = pageUrl
                this.quality = q
            }
        )
    }
}

// Mirror Hydrax di berbagai domain DonghuaId
class HydraxAbyss : Hydrax() {
    override val mainUrl = "https://abyssplayer.com"
}

class HydraxNet : Hydrax() {
    override val mainUrl = "https://hydrax.net"
}

class HydraxTo : Hydrax() {
    override val mainUrl = "https://hydrax.to"
}

fun Http(url: String): String {
    return if (url.startsWith("//")) {
        "https:$url"
    } else {
        url
    }
}
