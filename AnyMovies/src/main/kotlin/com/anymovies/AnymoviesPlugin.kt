package com.anymovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class AnymoviesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anymovies())
        registerExtractorAPI(D0000d())
        registerExtractorAPI(Cdnplayer())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(Gdriveplayer())  
        registerExtractorAPI(XStreamCdn())
    }
}
