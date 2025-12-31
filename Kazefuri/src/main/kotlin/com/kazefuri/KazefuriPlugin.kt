package com.kazefuri

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Geodailymotion

@CloudstreamPlugin
class KazefuriPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Donghuastream())
        registerMainAPI(SeaTV())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Ultrahd())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(PlayStreamplay())
    }
}