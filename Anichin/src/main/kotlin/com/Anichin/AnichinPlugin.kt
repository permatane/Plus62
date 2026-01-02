package com.Anichin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnichinPlugin: Plugin() {
    override fun load(context: Context) {
        // Baris ini memanggil class Anichin() dari file sebelah
        registerMainAPI(Anichin())
    }
}
