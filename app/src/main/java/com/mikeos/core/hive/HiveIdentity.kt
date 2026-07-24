package com.mikeos.core.hive

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * The credentials an app-agent uses to talk to the hive.
 *
 * @property agentKey the per-app, user-scoped hive API key (a UUID). Sent to the
 *   hive as `X-API-KEY` (REST) and `?api_key=` (WebSocket).
 * @property name     the agent's hive name.
 * @property hiveUrl  base URL of the hive this agent is registered on.
 */
data class HiveCred(val agentKey: String, val name: String, val hiveUrl: String)

/**
 * APP-ANATOMY §0 — mandatory self-registration.
 *
 * The first thing every MikeOS app does: obtain its own hive identity. The app holds
 * NO device/user secret — it asks the LOCAL daemon (which is device-authenticated) to
 * broker a user-scoped app-agent key. Idempotent: credentials are persisted in the
 * app's files dir and reused across launches.
 *
 *   POST {daemon}/api/agents/register  {"app": "<AppName>"}
 *        200 {"agent_key": "...", "name": "...", "hive_url": "..."}
 *
 * Registration is auth-exempt (loopback only), so no bearer token is required.
 */
class HiveIdentity(
    private val appName: String,
    private val daemonBaseUrl: String,
) {
    private fun credFile(ctx: Context) = File(ctx.filesDir, "hive_credentials.json")

    /** Cached credentials, or null if this app has never registered. */
    fun load(ctx: Context): HiveCred? {
        val f = credFile(ctx)
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            HiveCred(j.getString("agent_key"), j.getString("name"), j.getString("hive_url"))
        }.getOrNull()
    }

    /**
     * Return this app's hive credentials, self-registering via the daemon if we don't
     * have them yet. MUST be called off the main thread. [daemonClient] must trust the
     * daemon's loopback self-signed cert. Best-effort: returns null on any failure.
     */
    fun ensure(ctx: Context, daemonClient: OkHttpClient): HiveCred? {
        load(ctx)?.let { return it }
        val body = JSONObject().put("app", appName).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$daemonBaseUrl/api/agents/register").post(body).build()
        return runCatching {
            daemonClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body!!.string())
                val cred = HiveCred(
                    j.getString("agent_key"),
                    j.optString("name", appName),
                    j.getString("hive_url"),
                )
                credFile(ctx).writeText(
                    JSONObject()
                        .put("agent_key", cred.agentKey)
                        .put("name", cred.name)
                        .put("hive_url", cred.hiveUrl)
                        .toString(),
                )
                cred
            }
        }.getOrNull()
    }
}
