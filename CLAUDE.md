# MikeMaps — CLAUDE.md

## What this repo is

MikeMaps is Mike's **navigation + road-sensing** app-agent: it routes A→B for free, records
the speed/GPS trail of every drive to trips-cloud, and announces the journey on the hive so the
other agents (Guide, Storyteller, Sound) compose on it. Every drive makes the next ETA and the
congestion map more true.

It owns **ROUTE + SPEED + TRAIL** only. It does **not** narrate surroundings (that's MikeGuide) or
tell stories (MikeStoryteller) — it announces the trip on the hive (`trip.started` / `trip.progress`
/ `trip.ended`) and lets them react.

**Type:** MikeOS **Android app** (app-agent). Package / applicationId **`com.mikeos.maps`**
(namespace `com.mikeos.maps`), versionCode **3**, versionName **0.3.0-mapfirst**.

**Map-first UX (like Google Maps):** the app opens straight onto a **full-screen OSM map**
(MapLibre GL Native, self-hosted **`mikeos-basemap`** — `$BASEMAP_URL/style.json`, a Protomaps planet
PMTiles basemap; no API key, no client PMTiles plugin). You see **where you are** (a live dot at
~5 km) that **moves with you**; the camera follows, panning turns follow off, a recenter button turns
it back on. Destinations + options live behind a **☰ menu** (a `ModalBottomSheet`), NOT an input
field. While driving, a bottom **HUD** shows km/h, distance/time remaining, and ETA clock. The map is
**preloaded ~100 km around you** (MapLibre `OfflineManager`) so it's instant / offline-resilient. The
app reads the ONE daemon fix (never its own GPS) and reports speed to trips-cloud **every 5 s** while
driving.

## Roadmap / phases (map-first mission)

Vision: *the one navigation surface that's map-first and quietly learns Mike's roads.* Shipped so far:
- **P1 Map-first shell** ✅ — full-screen map, live moving dot (~5 km), follow-camera + recenter, ☰
  menu for search/options (replaced the input-first layout).
- **P2 Driving HUD** ✅ — speed km/h, distance remaining, time remaining (`8 min`/`1 h 12 min`/`2 d 3 h`),
  ETA clock (`13:32`).
- **P3 5 s telemetry** ✅ — `TripManager` posts speed+loc to trips-cloud every 5 s while driving
  (decoupled from the 60 s heartbeat).
- **P4 Offline prefetch** ✅ — `OfflinePrefetch` caches ~100 km around Mike, refreshed when he moves far.
- Next: route-preview mode (see the whole route before you drive), turn-by-turn guidance, congestion
  overlay on the map, heading/bearing on the puck.

## Build & install

Android app — minSdk **31**, compile/target **35**, **Kotlin 2.0**, **AGP 8.7**.

```bash
./gradlew assembleDebug --no-daemon --max-workers=2
# → app/build/outputs/apk/debug/app-debug.apk
```

`gradle.properties` pins `org.gradle.jvmargs=-Xmx1280m` — keep Gradle memory bounded.

**Do NOT ship with `adb install`.** Updates reach the phone **OTA via mikeos-appstore**: bump
`versionCode` (+ `versionName`) in `app/build.gradle.kts`, build, then publish the APK to the store —
the on-device daemon Updater polls every ~15 min and installs it over WiFi/cellular. See
`/home/mikeos/projects/mikeos-architecture/docs/PUBLISHING-APP-UPDATES.md`. The store `version_code`
MUST equal the APK's internal `versionCode` (a mismatch loops forever), and the signing key must stay
the shared debug keystore (in-place `pm install -r`).

```bash
set -a; . ~/.mikeos/provider-keys.env; set +a       # PUBLISH_KEY + APPSTORE_URL (secret, never on a phone)
curl -sS --max-time 180 -X POST "$APPSTORE_URL/api/apps/com.mikeos.maps/releases" \
  -H "X-PUBLISH-KEY: $PUBLISH_KEY" \
  -F "file=@app/build/outputs/apk/debug/app-debug.apk" \
  -F "version_code=2" -F "version_name=0.2.0-my-change" -F "notes=what changed"
```

MikeMaps needs **no app-held Android permissions** for location — it reads the ONE shared fix from
the daemon (§3a below), never its own GPS.

## MikeOS architecture contract

- **Every app is an autonomous agent**, not a thin UI. It runs a closed loop continuously:
  **perceive → reason → act → remember → message peers → repeat**.
- **Heartbeat:** every **60s while active** (foreground Service) and every **15min while dormant**
  (WorkManager). Each beat: read context → reason on the GPU → act if warranted → write memory →
  optionally message peers. In MikeMaps the beat ALSO drives the deterministic trail recording:
  while a trip is active, `TripManager.beatSample()` (hooked off `HeartbeatService.perceptionProvider`)
  reads the daemon fix, POSTs one sample, and throttled-broadcasts `trip.progress` — no LLM decision.
- **Native only:** Kotlin + Jetpack Compose (Material 3). **Never a WebView wrapper** (sole
  ecosystem exception is MikeBrowser's content engine).
- **Self-registration (§0):** the app registers itself with the on-device daemon on its first beat
  (`HiveIdentity.ensure`), then pushes presence each beat (`MikeHive.sync`).
- **ONE shared location:** apps must **NOT run their own GPS**. Read the single fix from the daemon:
  `GET https://127.0.0.1:7743/api/location`. Only the designated provider pushes GNSS; everyone else reads.
- **Reason via the daemon:** `POST https://127.0.0.1:7743/api/agent/chat` (on-device GPU, Ollama `qwen3:8b`).
- **Daemon on-device:** `https://127.0.0.1:7743` — loopback, self-signed TLS (trust it, scoped to 127.0.0.1
  only). Auth is `Authorization: Bearer 7bdc23451b18b5801036f992b66a872670975d19` (from the daemon). `/api/location`,
  `/api/events`, `/api/agents/register` are auth-exempt loopback endpoints.
- **Identity authority:** `mikeoscomputers`. **Cloud services are user-scoped** via
  `X-API-KEY → user_id`.

## How MikeMaps is built

The whole point is that the trip lifecycle is **DETERMINISTIC** — it does NOT rely on the LLM
picking a skill (house rule: proactive features must be deterministic on the beat). The LLM skills
sit on top of the same functions for Q&A.

- **`trips/TripManager.kt`** — the heart. The trip lifecycle, wired directly and driven by the
  heartbeat + the UI: `route` → `startTrip` (create in trips-cloud + broadcast `trip.started`) →
  `beatSample` (one sample per beat while moving; throttled `trip.progress` ~60s) → `endTrip`
  (POST end + broadcast `trip.ended`). Serialized with a mutex so a beat sample can't race start/end.
- **`MapsViewModel.kt` + `MainActivity.kt`** — the routing / active-trip / history Compose UI.
  "Go" geocodes → reads the daemon fix → routes → draws it → starts the trip, all wired directly
  (not via the LLM).
- **`ui/NavigationMap.kt`** — the full-screen map surface: MapLibre GL Native (`MapView` via
  `AndroidView`) loading `$BASEMAP_URL/style.json`, with a live "you are here" puck (follow-camera,
  user-pan disables follow), the route traced ahead, and the initial ~5 km framing. Server-side
  tiling (go-pmtiles) → NO client PMTiles plugin.
- **`ui/MapLibreInit.kt`** — one-time MapLibre init; points its HTTP stack at `net/Doh` (flaky ROM).
- **`nav/Nav.kt`** — `NavFormat` (distance / `duration` min→`h min`→`d h` / `eta` clock) + `NavGeo`
  (remaining-distance-along-route) + `NavInfo` (the HUD readout).
- **`net/OfflinePrefetch.kt`** — MapLibre `OfflineManager` wrapper: rolling ~100 km prefetch around
  the user, best-effort, refreshed on move.
- **`MapsViewModel`** — polls the daemon fix ~3 s (the moving dot + prefetch + HUD `NavInfo`).
- **`TripManager`** — a **5 s sampler** posts speed+loc to trips-cloud while a trip is active (started
  on `startTrip`, stopped on `endTrip`); the 60 s beat only reports status now.
- **`agent/MapsMikeAgent.kt`** — wires the shared `com.mikeos.core` runtime in, sets the Soul
  (persona + goals), hooks the perception provider (which also drives `beatSample`), and exposes
  four LLM skills for Q&A: `route(dest)`, `start_trip(dest)`, `end_trip`, `congestion(near, hour?)`.
  The universal skills (`hive_send` / `remember` / `recall` / `notify` / `ask_siblings`) are added by
  the runtime.
- **`net/TripsCloudClient.kt`** — talks to **mikeos-trips-cloud** (Railway, valid public TLS → standard
  OkHttp + DoH, NOT the loopback client). `/api/route` is keyless (OSRM wrapper); trip calls carry
  `X-API-KEY` (the hive agent key). Honours the house rules: ISO-8601 `ts`, never-trust-200.
- **`net/DaemonLocation.kt`** — reads the ONE shared fix from the daemon (loopback, auth-exempt).
  Derives speed from consecutive fixes when the daemon omits it; clamps GPS teleport to 250 km/h.
- **`net/Geocoder.kt`** (Nominatim), **`net/PolylineCodec.kt`** (decode the OSRM polyline for the canvas).
- **`com/mikeos/core/*`** — the vendored shared MikeAgent runtime (copied, not a Gradle dependency):
  `agent/MikeAgent` (closed loop + hive collaboration protocol), `runtime/HeartbeatService`/`Worker`,
  `hive/*` (HiveSocket, HiveIdentity §0), `net/*` (DaemonBrain, loopback TLS, DoH). Re-vendor the whole
  tree as one unit on a fleet rebuild — never piecemeal.

### trips-cloud API (per the live spec)
```
POST /api/route              {from:{lat,lon},to:{lat,lon}} -> {ok,polyline,km,eta_min}      KEYLESS
POST /api/trips              {dest_name,dest_lat,dest_lon,origin_lat,origin_lon,polyline,km,eta_min,mode,device_id} -> {trip_id,started_at}
POST /api/trips/{id}/samples {samples:[{lat,lon,speed_kmh,ts}]} -> {stored}
POST /api/trips/{id}/end     {avg_kmh?} -> {trip_id,duration_min,sample_count,avg_kmh}
GET  /api/trips?limit=20     -> {trips:[...]}
GET  /api/trips/{id}         -> {trip:{...}}
GET  /api/congestion?lat=&lon=&radius_m=&hour= -> {avg_kmh,sample_count,by_hour}
```

## Hive contracts (MikeMesh / EVENTS.md)

| Type            | Direction | Payload                                     | Consumers                              |
|-----------------|-----------|---------------------------------------------|----------------------------------------|
| `trip.started`  | publish   | `{dest, eta_min, km, polyline, mode}`       | MikeGuide, MikeStoryteller, MikeSound  |
| `trip.progress` | publish   | `{lat, lon, speed_kmh, ts}` (throttled ~60s)| (trips-cloud via MikeMaps)             |
| `trip.ended`    | publish   | `{trip_id, duration_min, km, avg_kmh}`      | MikeStoryteller (stop), MikeMind       |

MikeMaps **consumes** nothing — it emits the trip lifecycle and lets siblings compose on it
(Guide answers `route.pois`, Storyteller a `story.ready`, Sound ducks the audio). See
`/home/mikeos/projects/mikeos-architecture/docs/FLEET-CHARTER.md` for the full journey wiring.

## House rules — hard-won, do not repeat these bugs

These cost real incidents. Read them every time; they apply across the whole ecosystem.

- **MEMORY: never load a whole file into RAM.** An app that did
  `contentResolver.openInputStream(uri).readBytes()` with no size cap hit a **1.55 GB video**,
  exhausted phone RAM, and put the device in a **reboot loop**. Always **cap file size (~30 MB)** and
  **skip video / oversized media**; large media goes to the daemon media pipeline (ffmpeg frames +
  Whisper transcript), never inline.
- **TIMESTAMPS: cloud services expect ISO-8601 strings.** Sending epoch-ms `Long`s makes the
  FastAPI cloud return **HTTP 200 while silently persisting 0 rows**. Use
  `java.time.Instant.ofEpochMilli(x).toString()`. MikeMaps does this for every sample `ts` in
  `TripsCloudClient.postSamples`. Clouds should parse timestamps tolerantly (accept both).
- **SQL: never use a reserved keyword as a column** (`left`→`left_at`, etc.). Keep migrations
  idempotent (`IF NOT EXISTS`). **Parameterized queries only** — never interpolate values into SQL.
- **DAEMON: `dist/index.js` MUST keep `require('./dns-fix.js');` as line 2** (c-ares DNS shim).
  A `tsc` recompile once silently dropped it and broke the daemon. Never emit-compile over `dist/`
  without re-adding it.
- **SECURITY (millions of untrusted devices): no key stored on a phone is ever admin.**
  Publish/admin keys live **server-side only**; device-held keys are **ingest/write-only** and should
  become **per-device IdP-minted keys scoped to their own `device_id`**. The appstore `PUBLISH_KEY`
  is operator/CI only — **never ship it onto a phone**.
- **Never trust HTTP 200 alone** — verify the response actually stored data (check for
  `upserted` / `stored` / a real `id`), or you'll log "success" on a silent drop. MikeMaps verifies
  a real `trip_id` on create/end and `stored > 0` on samples.

## Shared infrastructure — READ THESE FIRST

The MikeOS source-of-truth docs live in the **mikeos-architecture** repo (the infra/architecture
repo). Read them at the absolute path before changing anything:

`/home/mikeos/projects/mikeos-architecture/docs/`
- `APP-ANATOMY.md` — the app-agent contract (§0 self-registration, the perceive→reason→act→
  remember→message closed loop, the heartbeat, §3a location authority, §4 messaging, §7 events).
- `FLEET-CHARTER.md` — the journey system: MikeMaps/Guide/Storyteller/Sound charters + the
  `trip.*` / `route.pois` / `story.ready` hive contracts. **The accurate source for MikeMaps.**
- `PUBLISHING-APP-UPDATES.md` — OTA releases via mikeos-appstore (how MikeMaps updates reach the phone).
- `AGENT-COLLABORATION-AND-CONTEXT.md` — the shared `com.mikeos.core` runtime (DoH, hive protocol,
  interest context, heartbeat forcing).
- `AUTH.md`, `INFRASTRUCTURE.md`, `EVENTS.md`, `OAUTH.md`, `APP-CATALOG.md`, `STATUS.md`, `API-NEEDS.md`.
- `reference/hive/` — seed of the shared `mikeos-android-core` SDK: `HiveIdentity.kt`, `MikeHive.kt`,
  `MikeEvents.kt`, `VoiceInput.kt` (each app copies these into its own `com.mikeos.<name>` core tree).

> Note: `docs/services/trips.md` predates the journey pivot and describes a *planned travel-itinerary*
> aggregator — that is NOT the live `mikeos-trips-cloud` MikeMaps talks to. Trust `FLEET-CHARTER.md`.

(A mirror also exists under `/home/mikeos/projects/mike-ecosystem/mikeosinfrastructure/`; the
top-level `mikeos-architecture` is canonical.)
