package com.Animexin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*


@CloudstreamPlugin
class AnimexinProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(Animexin())
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Okrulink())
        registerExtractorAPI(Gdriveplayer())        
        registerExtractorAPI(DoodLaExtractor())


    }
}
