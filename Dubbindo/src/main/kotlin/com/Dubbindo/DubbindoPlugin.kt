package com.Dubbindo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class DubbindoPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Dubbindo())
    }
}
