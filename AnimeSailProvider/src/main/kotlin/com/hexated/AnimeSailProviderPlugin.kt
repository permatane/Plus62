package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.registerMainAPI

@CloudstreamPlugin
class AnimeSailProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        AnimeSailProvider.context = context
        registerMainAPI(AnimeSailProvider())
    }
}
