package com.Anichin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.Okrulink
// import android.content.Context

@CloudstreamPlugin
class AnichinPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
        registerMainAPI(Auratail())
        registerExtractorAPI(embedwish())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Okrulink())
    }
}
