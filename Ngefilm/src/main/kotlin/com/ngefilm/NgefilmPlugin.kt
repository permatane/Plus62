package com.ngefilm

//import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
// class NgefilmPlugin : Plugin() {
//   override fun load(context: Context) {
class NgefilmPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Ngefilm())
        registerMainAPI(Cda())
        registerMainAPI(Cdnplayer())
        registerMainAPI(CdnwishCom())
        registerMainAPI(Ewish())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Gdriveplayerto())
        registerExtractorAPI(Playerngefilm21())
        registerExtractorAPI(P2pplay())
        registerExtractorAPI(Shorticu())
    }
}

