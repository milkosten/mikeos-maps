package com.mikeos.maps.ui

import android.content.Context
import com.mikeos.maps.net.Doh
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * One-time MapLibre initialisation. MUST run before any MapView / OfflineManager use. Points
 * MapLibre's resource loader at our DoH client (this ROM's system DNS is flaky — same reason every
 * cloud client here uses DoH). Idempotent + thread-safe.
 */
object MapLibreInit {
    @Volatile private var ready = false

    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            MapLibre.getInstance(context.applicationContext)
            HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().dns(Doh.dns).build())
            ready = true
        }
    }
}
