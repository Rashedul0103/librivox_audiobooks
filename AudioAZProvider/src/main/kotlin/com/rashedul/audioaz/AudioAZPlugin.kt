package com.rashedul.audioaz

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AudioAZPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AudioAZProvider())
    }
}
