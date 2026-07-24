package com.mikeos.core.hive

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One inbound hive message, addressed to this agent.
 *
 * @property id      the hive message id (used to ack / mark-read).
 * @property from    the sending agent's name.
 * @property type    message type (best-effort; parsed out of the body if the sender
 *   used a `type: body` convention, else "message").
 * @property body    the message content.
 * @property ts      epoch millis received.
 */
data class HiveMessage(
    val id: String,
    val from: String,
    val type: String,
    val body: String,
    val ts: Long = System.currentTimeMillis(),
)

/**
 * MIKEAGENT.md §1d — the LIVE HIVE.
 *
 * A persistent WebSocket client to the hive's `/api/agent/ws` endpoint, held open by
 * the foreground [com.mikeos.core.runtime.HeartbeatService]. Responsibilities:
 *
 *  • connect + auto-reconnect with exponential backoff;
 *  • announce presence on connect;
 *  • expose inbound messages as a real-time [inbound] Flow (push, not polling);
 *  • [send] a message (via REST `/api/hive/send`), and
 *  • buffer inbound messages for the next beat ([drainInbox]) AND fan them out to a
 *    registered reactive [handler] immediately.
 *
 * WIRE NOTE (from milko-hive/server/api/unread.py): the `/ws` endpoint is a *signal*
 * channel — it pushes `{agent_name, has_unread, unread_count, ...}` whenever the unread
 * count changes, NOT the message bodies. So on every push that indicates unread > 0 we
 * fetch the actual messages over REST (`GET /api/messages?unread_only=true`), emit them,
 * and ack them (`POST /api/messages/mark-read`). When the socket is down,
 * [readUnread] does the same fetch over REST as a fallback.
 */
class HiveSocket(
    private val cred: HiveCred,
    /**
     * Public-cert OkHttpClient for the hive (NOT the loopback-trusting one). Resolves via
     * **DoH** ([com.mikeos.core.net.Doh]) because this ROM's system DNS intermittently fails —
     * without it the hive WebSocket never connects and agents can't message each other.
     */
    baseClient: OkHttpClient = OkHttpClient.Builder()
        .dns(com.mikeos.core.net.Doh.dns)
        .build(),
    /**
     * Persistent log of every message sent/received. Optional (null → no logging) so
     * HiveSocket stays usable without a Context. The runtime always supplies one.
     */
    private val hiveLog: HiveLog? = null,
) {
    private val tag = "HiveSocket"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = baseClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on the socket
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Real-time push stream. Replay of 0; buffer generous so a burst is never dropped.
    private val _inbound = MutableSharedFlow<HiveMessage>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val inbound: SharedFlow<HiveMessage> = _inbound.asSharedFlow()

    // Buffered messages waiting to be drained into the next beat's perception, each
    // paired with its hive-log row id (or -1 if logging was unavailable) so we can mark
    // the row read once the message has been drained into a beat.
    private val buffer = CopyOnWriteArrayList<Pair<HiveMessage, Long>>()

    // Immediate reactive handlers (e.g. "a sibling messaged me → wake a beat now").
    private val handlers = CopyOnWriteArrayList<suspend (HiveMessage) -> Unit>()

    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private var backoffMs = INITIAL_BACKOFF_MS
    private val readLock = Mutex()

    /**
     * Register a handler invoked immediately for each inbound message.
     *
     * ‼️ THREADING CONTRACT — READ BEFORE YOU SEND FROM A HANDLER ‼️
     * Handlers run INSIDE [fetchAndEmitUnread], which the socket invokes on its own IO
     * coroutine while fetching the unread batch. Historically that ran while holding the
     * socket's `readLock`, and [send] also took that lock — so any handler that replied
     * inline (search + `send`) SELF-DEADLOCKED forever. [send] no longer contends with
     * `readLock` (it is an independent REST call), so a reply will not deadlock. Even so,
     * a handler is a good citizen when it stays FAST and NON-BLOCKING: dispatch any real
     * work (a search, an LLM call, a `send`) onto YOUR OWN coroutine scope and return
     * immediately, so one slow handler never stalls delivery of the rest of the batch.
     */
    fun onMessage(handler: suspend (HiveMessage) -> Unit) {
        handlers.add(handler)
    }

    /** Open the socket and keep it open (auto-reconnecting). Idempotent. */
    fun connect() {
        if (!running.compareAndSet(false, true)) return
        openSocket()
    }

    private fun openSocket() {
        if (!running.get()) return
        val wsUrl = buildWsUrl()
        val req = Request.Builder().url(wsUrl).build()
        Log.i(tag, "connecting: $wsUrl")
        webSocket = client.newWebSocket(req, listener)
    }

    /** ws(s)://<hiveHost>/api/agent/ws?api_key=<agentKey> (auth per unread.py). */
    private fun buildWsUrl(): String {
        val base = cred.hiveUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base/api/agent/ws?api_key=${cred.agentKey}"
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.i(tag, "connected as ${cred.name}")
            backoffMs = INITIAL_BACKOFF_MS
            announcePresence()
            // On (re)connect there may already be unread we missed while down.
            scope.launch { fetchAndEmitUnread() }
        }

        override fun onMessage(ws: WebSocket, text: String) {
            // The server pushes an unread-status object; when it says we have unread,
            // pull the actual messages over REST.
            val hasUnread = runCatching {
                JSONObject(text).optBoolean("has_unread", false)
            }.getOrDefault(false)
            if (hasUnread) scope.launch { fetchAndEmitUnread() }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.w(tag, "socket failure: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.i(tag, "socket closed ($code): $reason")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        webSocket = null
        if (!running.get()) return
        val delayMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            openSocket()
        }
    }

    /**
     * Announce presence. The hive tracks presence server-side off any authenticated
     * request; we do a lightweight REST `/api/hive/read` which the server treats as a
     * connect+poll (see milko-hive http_server.hive_read). Best-effort.
     */
    fun announcePresence() {
        scope.launch {
            runCatching {
                val body = JSONObject()
                    .put("agent_name", cred.name)
                    .put("description", "present")
                    .toString().toRequestBody(jsonMedia)
                val req = Request.Builder()
                    .url("${cred.hiveUrl.trimEnd('/')}/api/hive/read")
                    .header("X-API-KEY", cred.agentKey)
                    .post(body)
                    .build()
                client.newCall(req).execute().use { it.body?.string() }
            }
        }
    }

    /**
     * Send a message to a sibling. Uses the REST `/api/hive/send` endpoint (the `/ws`
     * channel is inbound-signal only). Returns a short result string for the skill.
     * `type` is prefixed onto the body as `type: body` so the receiver can route it.
     */
    /**
     * Resolve a short sibling name ("MikeSpace") to its full hive name
     * ("<user>/<device>/MikeSpace") using THIS agent's own scope. The hive addresses
     * agents by full scoped name, but siblings are configured by short name.
     */
    private fun resolveName(to: String): String {
        if (to.isBlank() || to.contains("/")) return to
        val scope = cred.name.substringBeforeLast("/", "")
        return if (scope.isEmpty()) to else "$scope/$to"
    }

    // DEADLOCK FIX: send() must NOT take `readLock`. That lock exists only to serialize the
    // unread-fetch/ack cycle ([fetchAndEmitUnread]); a send is an independent REST POST that
    // touches no shared state guarded by it. onMessage handlers run inside that cycle, so if
    // send() also took the lock, an inline reply would wait on a lock the very fetch that
    // invoked it still holds — a hard self-deadlock. Keeping send() lock-free lets handlers
    // reply safely (and it can safely run concurrently with a fetch — OkHttp is thread-safe).
    suspend fun send(to: String, type: String, body: String): String {
        return runCatching {
            val message = if (type.isBlank()) body else "$type: $body"
            val payload = JSONObject()
                .put("agent_name", cred.name)
                .put("description", "hive_send")
                .put("to_agent", resolveName(to))
                .put("message", message)
                .toString().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("${cred.hiveUrl.trimEnd('/')}/api/hive/send")
                .header("X-API-KEY", cred.agentKey)
                .post(payload)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    // Record every message the agent SENDS (stored read).
                    hiveLog?.recordSent(peer = to, type = type.ifBlank { "message" }, body = body)
                    "sent to $to"
                } else {
                    "send failed: HTTP ${resp.code}"
                }
            }
        }.getOrElse { "send failed: ${it.message}" }
    }

    /**
     * List the full hive names of every OTHER agent registered in this user's group
     * (excludes self). Backs [broadcast] and any "who's around" lookup. Best-effort;
     * returns an empty list on failure. Lock-free (independent REST GET).
     */
    suspend fun listAgents(): List<String> = runCatching {
        val req = Request.Builder()
            .url("${cred.hiveUrl.trimEnd('/')}/api/agents")
            .header("X-API-KEY", cred.agentKey)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList<String>()
            val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("agents")
                ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() && it != cred.name }
        }
    }.getOrDefault(emptyList())

    /**
     * Broadcast a message to EVERY sibling agent in this user's group (send to each
     * discovered by [listAgents]). Returns how many were delivered. Best-effort; use for
     * fleet-wide announces (e.g. `capability.announce`), not routine chatter.
     */
    suspend fun broadcast(type: String, body: String): Int {
        val peers = listAgents()
        var n = 0
        for (p in peers) {
            if (send(p, type, body).startsWith("sent")) n++
        }
        return n
    }

    /**
     * REST fallback: fetch unread messages, emit + ack them. Used both by the socket's
     * push handler and directly by the runtime when the socket is down.
     * Returns the messages fetched.
     */
    suspend fun readUnread(): List<HiveMessage> = fetchAndEmitUnread()

    private suspend fun fetchAndEmitUnread(): List<HiveMessage> = readLock.withLock {
        val msgs = runCatching {
            val req = Request.Builder()
                .url("${cred.hiveUrl.trimEnd('/')}/api/messages?unread_only=true&limit=100")
                .header("X-API-KEY", cred.agentKey)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList<HiveMessage>()
                parseMessages(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())

        if (msgs.isNotEmpty()) {
            for (m in msgs) {
                // Record every message the agent RECEIVES (stored unread until drained).
                val logId = hiveLog?.recordRecv(peer = m.from, type = m.type, body = m.body) ?: -1L
                buffer.add(m to logId)
                _inbound.emit(m)
                for (h in handlers) runCatching { h(m) }
            }
            ackRead(msgs.map { it.id })
        }
        msgs
    }

    private fun ackRead(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            val payload = JSONObject().put("ids", arr).toString().toRequestBody(jsonMedia)
            val req = Request.Builder()
                .url("${cred.hiveUrl.trimEnd('/')}/api/messages/mark-read")
                .header("X-API-KEY", cred.agentKey)
                .post(payload)
                .build()
            client.newCall(req).execute().use { it.body?.string() }
        }
    }

    private fun parseMessages(raw: String): List<HiveMessage> = runCatching {
        val arr = JSONObject(raw).optJSONArray("messages") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val content = m.optString("content")
            val (type, body) = splitType(content)
            HiveMessage(
                id = m.optString("id"),
                from = m.optString("from_agent"),
                type = type,
                body = body,
            )
        }
    }.getOrDefault(emptyList())

    /** Recover the `type: body` convention used by [send]; default type "message". */
    private fun splitType(content: String): Pair<String, String> {
        val idx = content.indexOf(": ")
        if (idx in 1..40) {
            val maybeType = content.substring(0, idx)
            if (maybeType.matches(Regex("[a-zA-Z0-9_.\\-]+"))) {
                return maybeType to content.substring(idx + 2)
            }
        }
        return "message" to content
    }

    /**
     * Drain buffered inbound messages for the next beat's perception. Returns and
     * clears the buffer. The runtime renders these into the {hive_inbox} slot.
     */
    suspend fun drainInbox(): List<HiveMessage> {
        val out = buffer.toList()
        buffer.clear()
        // These messages are now surfaced to the agent → mark their log rows read.
        val ids = out.mapNotNull { (_, logId) -> logId.takeIf { it >= 0 } }
        hiveLog?.markRead(ids)
        return out.map { it.first }
    }

    /** True if there is anything waiting for the next beat. */
    fun hasBufferedInbox(): Boolean = buffer.isNotEmpty()

    /** Close the socket and stop reconnecting. */
    fun close() {
        running.set(false)
        webSocket?.close(1000, "bye")
        webSocket = null
        scope.cancel()
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
