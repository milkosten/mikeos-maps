package com.mikeos.maps.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * DNS-over-HTTPS resolver (Cloudflare).
 *
 * This device is a GApps-less ROM on flaky cellular whose **system DNS intermittently fails**
 * (`getaddrinfo ENOTFOUND` for perfectly valid hosts like the Railway cloud and the daemon's LLM
 * host). That silently breaks every public HTTPS call the app makes.
 *
 * DoH sidesteps it: hostnames are resolved by POSTing to `https://1.1.1.1/dns-query` — reached by
 * **IP**, so it needs no working system resolver to bootstrap. Falls back to the system resolver on
 * a DoH miss, so it can only help. Share one instance across clients.
 */
object Doh {
    val dns: Dns by lazy {
        val bootstrap = OkHttpClient.Builder().build()
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .includeIPv6(false)   // avoid AAAA-only dead paths on IPv4 cellular
            .build()
    }
}
