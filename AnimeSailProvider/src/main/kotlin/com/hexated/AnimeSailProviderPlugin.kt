package com.hexated

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class AnimeSailProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        // AnimeSailProvider.context = context
        registerMainAPI(AnimeSailProvider())
    }
}
