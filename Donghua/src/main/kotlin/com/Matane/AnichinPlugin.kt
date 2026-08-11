package com.Matane

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*
// import android.content.Context

@CloudstreamPlugin
class AnichinPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
        registerMainAPI(Kazefuri())
        registerMainAPI(Animexin())
        registerMainAPI(Animekhor())
        registerMainAPI(Donghuaword())
        registerMainAPI(Donghuaid())
        registerMainAPI(Donghub())
        registerMainAPI(Anixverse())
        registerMainAPI(Yunshanid())
        registerExtractorAPI(ArchiveOrgExtractor())
        registerExtractorAPI(Ewish())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(LuluStream())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(OkRuHTTPMobile())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuSSLMobile())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(D0000d())
        registerExtractorAPI(Cdnplayer())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(Gdriveplayer())  
        registerExtractorAPI(XStreamCdn())
        registerExtractorAPI(Vidtren())    
        registerExtractorAPI(svilla())
        registerExtractorAPI(svanila())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3()) 
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(HydraxAbyss())  
        registerExtractorAPI(HydraxNet())  
        registerExtractorAPI(HydraxTo())   
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
    }
}
