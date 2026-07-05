package com.anoboy

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class AnoboyPlugin : BasePlugin() {
    override fun load() {
      //  Anoboy.context = context
        registerMainAPI(Anoboy())
    }
}
