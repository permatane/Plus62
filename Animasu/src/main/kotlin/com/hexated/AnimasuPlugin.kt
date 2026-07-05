package com.hexated

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class AnimasuPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
       // Animasu.context = context
        registerMainAPI(Animasu())
        registerExtractorAPI(Archivd())
        registerExtractorAPI(Newuservideo())
        registerExtractorAPI(Vidhidepro())
    }
}
