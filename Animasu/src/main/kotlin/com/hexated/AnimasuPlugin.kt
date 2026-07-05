package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.registerExtractorAPI
import com.lagradost.cloudstream3.registerMainAPI
import com.lagradost.cloudstream3.*

@CloudstreamPlugin
class AnimasuPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        Animasu.context = context
        registerMainAPI(Animasu())
        registerExtractorAPI(Archivd())
        registerExtractorAPI(Newuservideo())
        registerExtractorAPI(Vidhidepro())
    }
}
