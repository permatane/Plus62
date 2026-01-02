package com.kazefuri

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KazefuriPlugin: Plugin() {
    override fun load(context: Context) {
        // Pastikan nama class "Kazefuri()" sesuai dengan yang ada di Kazefuri.kt
        registerMainAPI(Kazefuri())
    }
}


