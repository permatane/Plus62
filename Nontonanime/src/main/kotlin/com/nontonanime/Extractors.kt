package com.Nontonanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.extractors.Gdriveplayer
import com.lagradost.cloudstream3.extractors.Hxfile



class Nontonanimeid : Hxfile() {
    override val name = "Nontonanimeid"
    override val mainUrl = "https://nontonanimeid.com"
    override val requiresReferer = true
}

class EmbedKotakAnimeid : Hxfile() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed2.kotakanimeid.com"
    override val requiresReferer = true
}

class KotakAnimelink1 : Hxfile() {
    override val name = "KotakAnimelink1"
    override val mainUrl = "https://s1.kotakanimeid.link"
    override val requiresReferer = true
}

class KotakAnimelink2 : Hxfile() {
    override val name = "KotakAnimelink2"
    override val mainUrl = "https://s2.kotakanimeid.link"
    override val requiresReferer = true
}

class Kotaksb : Hxfile() {
    override val name = "Kotaksb"
    override val mainUrl = "https://kotaksb.fun"
    override val requiresReferer = true
}

class KotakAnimeidCom : Hxfile() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
    override val requiresReferer = true
}
