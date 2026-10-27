package com.rashedul.audible

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AudiblePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AudibleProvider())
    }
}
