package com.kazefuri

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KazefuriProvider : Plugin() {
    override fun load(context: Context) {
        // Hanya register main API dulu
        registerMainAPI(Kazefuri())
    }
}