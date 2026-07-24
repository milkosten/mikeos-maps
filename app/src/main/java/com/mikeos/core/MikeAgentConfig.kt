package com.mikeos.core

/**
 * Runtime configuration for the shared MikeAgent. An app supplies this once at
 * [MikeAgent.install] time. There are no compile-time BuildConfig fields in the
 * library itself, so every consuming app owns its own daemon token / cloud URLs.
 *
 * @property daemonBaseUrl  Loopback URL of the on-device MikeDaemon that runs the
 *   brain and brokers hive identity. Always `https://127.0.0.1:7743` in practice;
 *   the loopback-trusting HTTP client is scoped to this host only.
 * @property daemonToken    Bearer token the daemon expects on `/api/agent/chat`.
 * @property userName       Display name of the device owner, interpolated into the
 *   SYSTEM-CONTEXT ({user_name}).
 * @property siblings       Human-readable list of sibling agents sharing this phone,
 *   interpolated into the hive section of the SYSTEM-CONTEXT. Best-effort/static;
 *   apps may leave it blank and let the hive teach them who is around.
 * @property maxBrainIterations  Safety bound on the agentic loop (default 18).
 */
data class MikeAgentConfig(
    val daemonBaseUrl: String = "https://127.0.0.1:7743",
    val daemonToken: String,
    val userName: String = "Mike",
    val siblings: List<String> = emptyList(),
    val maxBrainIterations: Int = 18,
)
