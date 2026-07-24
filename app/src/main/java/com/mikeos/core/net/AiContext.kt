package com.mikeos.core.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads the user's **interest context** from mikeos-ai-cloud so every agent can
 * personalise its reasoning, and lets each app's Agent Inspector edit that app's
 * **per-app** context.
 *
 * Two layers (both scoped to the user by the app's hive `X-API-KEY`):
 *  - **global** — set once in the MikeAI app; shared by every assistant.
 *  - **per-app** — set in this app's Agent Inspector → Context tab.
 * The agent injects `global + this app's per-app` into its system prompt each beat.
 *
 * Public-cert cloud on Railway → resolves via **DoH** ([Doh]) (this ROM's system DNS
 * is flaky). Best-effort: every call degrades to null/false on failure, never throws.
 */
object AiContext {
    private const val TAG = "AiContext"
    const val BASE_URL = "https://mikeos-ai-cloud-production.up.railway.app"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Doh.dns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** The user's context as this app sees it. */
    data class Ctx(
        val globalText: String,
        val personalize: Boolean,
        val perApp: Map<String, String>,
    ) {
        /** The interest block for [appName]'s system prompt, or "" (personalise off / empty). */
        fun promptBlock(appName: String, userName: String): String {
            if (!personalize) return ""
            val app = perApp[appName]?.takeIf { it.isNotBlank() }
            val g = globalText.takeIf { it.isNotBlank() }
            if (g == null && app == null) return ""
            val sb = StringBuilder("\n## About $userName — their standing context (personalise to it)\n")
            if (g != null) sb.append(g.trim()).append('\n')
            if (app != null) sb.append("For $appName specifically: ").append(app.trim()).append('\n')
            return sb.toString()
        }
    }

    private fun req(apiKey: String, path: String) =
        Request.Builder().url("$BASE_URL$path").header("X-API-KEY", apiKey).header("Accept", "application/json")

    /** GET /api/context — the user's global + per-app context. Null on failure. */
    suspend fun fetch(apiKey: String): Ctx? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            client.newCall(req(apiKey, "/api/context").get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) { Log.w(TAG, "fetch HTTP ${resp.code}"); return@withContext null }
                val o = JSONObject(raw)
                val perApp = mutableMapOf<String, String>()
                o.optJSONObject("per_app")?.let { pa ->
                    pa.keys().forEach { k -> pa.optString(k).takeIf { it.isNotBlank() }?.let { perApp[k] = it } }
                }
                Ctx(o.optString("global_text"), o.optBoolean("personalize", true), perApp)
            }
        } catch (e: Exception) { Log.w(TAG, "fetch failed: ${e.message}"); null }
    }

    /** This app's per-app context text (from the fetched Ctx or a direct read). "" if unset. */
    suspend fun getAppContext(apiKey: String, app: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(req(apiKey, "/api/context/apps/${enc(app)}").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ""
                JSONObject(resp.body?.string().orEmpty()).optString("context_text")
            }
        } catch (e: Exception) { Log.w(TAG, "getAppContext failed: ${e.message}"); "" }
    }

    /** PUT /api/context/apps/{app}. Returns true when the server confirms the saved text. */
    suspend fun putAppContext(apiKey: String, app: String, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("text", text).toString().toRequestBody(jsonMedia)
            client.newCall(req(apiKey, "/api/context/apps/${enc(app)}").put(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) { Log.w(TAG, "putAppContext HTTP ${resp.code}"); return@withContext false }
                // never trust 200 alone — confirm the echoed text.
                JSONObject(resp.body?.string().orEmpty()).optString("context_text") == text
            }
        } catch (e: Exception) { Log.w(TAG, "putAppContext failed: ${e.message}"); false }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
