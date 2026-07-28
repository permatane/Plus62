package com.Moviebox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieboxProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(Moviebox())

    }
}
