package com.rashedul.audible

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AudiblePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AudibleProvider())
        openSettings = { settingsContext ->
            AudibleSettings.showSettings(settingsContext)
        }
    }
}
