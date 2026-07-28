package com.MovieBox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MvieBoxPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MovieBox())
        
    }
}
