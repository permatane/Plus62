package com.Funmovieslix


import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.FileMoonIn
import android.content.Context

@CloudstreamPlugin
class FunmovieslixProvider: Plugin() {
    override fun load(context: Context) {
        Funmovieslix.context = context
        registerMainAPI(Funmovieslix())
    registerExtractorAPI(Ryderjet())
    // FilemoonV2 not available in this build environment; skip registration
    registerExtractorAPI(Dhtpre())
    registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(Vidhideplus())
        registerExtractorAPI(VideyV2())
    }
}