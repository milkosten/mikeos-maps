package com.mikeos.core.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * DNS-over-HTTPS resolver (Cloudflare) for the shared runtime.
 *
 * This device is a GApps-less ROM whose **system DNS intermittently fails**
 * (`getaddrinfo ENOTFOUND` for valid hosts like the Railway hive). Every public HTTPS
 * call that relies on the system resolver silently breaks — which is exactly why the
 * **live hive WebSocket never connected** (`Unable to resolve host mikeos-hive-...`),
 * leaving agents unable to send or receive messages.
 *
 * DoH sidesteps it: hostnames are resolved by POSTing to `https://1.1.1.1/dns-query` —
 * reached by **IP**, so it needs no working system resolver to bootstrap. Falls back to
 * the system resolver on a DoH miss, so it can only help. Share one instance across clients.
 *
 * The REST cloud clients already use their own per-app `Doh`; this core copy is what the
 * hive transport (`HiveSocket`) uses so agent-to-agent messaging works on this ROM.
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
