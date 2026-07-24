# MikeMaps

**Mike's navigation + road-sensing agent.** A native Kotlin/Jetpack Compose maps app that is also
an autonomous agent — it routes A→B for free, records the speed/GPS trail of every drive, and
announces the journey on the hive so the other agents compose on it. Part of the
[MikeOS](https://github.com/milkosten) app fleet (see `mikeos-architecture/docs/APP-ANATOMY.md` and
`FLEET-CHARTER.md`).

Package `com.mikeos.maps` · minSdk 31 · compile/target 35 · "MikeMaps".

MikeMaps owns **ROUTE + SPEED + TRAIL**. It does **not** narrate surroundings (that's MikeGuide) or
tell stories (MikeStoryteller) — it emits the trip on the hive and lets them react. Every drive makes
the next ETA and the congestion map more true.

## What it does

- **Route** — geocodes a destination (Nominatim), reads Mike's current location from the daemon, and
  computes a free driving route via **trips-cloud** (`POST /api/route`, an OSRM wrapper): a polyline,
  distance in km, and an ETA. The route is drawn on a **real OSM map** — MapLibre GL Native rendering
  the self-hosted `mikeos-basemap` vector tiles, with a live "you are here" dot while driving.
- **Record the drive** — on "Go" it starts a trip in trips-cloud and, on **every heartbeat while the
  trip is active**, reads the shared daemon fix, computes speed, and POSTs one speed+loc sample. This
  is **deterministic** (driven by the beat, not by hoping the LLM picks a skill).
- **Announce on the hive** — `trip.started` when a trip begins, throttled `trip.progress` (~once/60s)
  while moving, `trip.ended` when it finishes — so MikeGuide/MikeStoryteller/MikeSound compose on it.
- **Learn congestion** — the recorded trails aggregate into a per-hour speed profile, queryable via
  `congestion(near)` to answer "how's traffic on my usual route".
- **Closed loop + heartbeat** — each beat perceives the active trip (or idle), reasons on the GPU,
  and can answer Q&A via its skills. The trail recording rides the same beat.

## Closed loop (App Anatomy)

```
PERCEIVE (active trip state + the ONE shared fix from the daemon)
   -> REASON (daemon /api/agent/chat: answer route / traffic questions)
      -> ACT   (deterministic on the beat: sample the trail, broadcast trip.progress)
         -> REMEMBER / message peers (trip.started / trip.progress / trip.ended on the hive)
```

- **Heartbeat:** 60s foreground `HeartbeatService` (active) / 15min `HeartbeatWorker` (dormant). The
  perception provider ALSO calls `TripManager.beatSample()` each beat while a trip is active.
- **Deterministic trip lifecycle:** routing, trip creation, per-beat sampling, and end-of-trip are all
  wired directly in `TripManager` — never dependent on the LLM choosing a skill.

## Hive message types (MikeMesh)

| Type            | Direction | Payload                                     | Meaning / consumers                          |
|-----------------|-----------|---------------------------------------------|----------------------------------------------|
| `trip.started`  | publish   | `{ dest, eta_min, km, polyline, mode }`     | A drive began. → Guide, Storyteller, Sound   |
| `trip.progress` | publish   | `{ lat, lon, speed_kmh, ts }` (throttled)   | Live position while moving.                  |
| `trip.ended`    | publish   | `{ trip_id, duration_min, km, avg_kmh }`    | A drive finished. → Storyteller (stop), Mind |

MikeMaps consumes nothing — it emits the trip lifecycle and lets siblings react (Guide returns
`route.pois`, Storyteller a `story.ready`, Sound ducks the audio channel).

## LLM skills

The runtime exposes four skills so the brain can answer questions on top of the same functions:
`route(dest)`, `start_trip(dest)`, `end_trip`, `congestion(near, hour?)`. The universal skills
(`hive_send` / `remember` / `recall` / `notify` / `ask_siblings`) are added by the shared runtime.

## Build / install

```bash
./gradlew assembleDebug --no-daemon --max-workers=2
# → app/build/outputs/apk/debug/app-debug.apk
```

MikeMaps needs **no app-held location permission** — it reads the ONE shared fix from the daemon.

### Shipping to the phone — OTA, not adb

Updates reach the phone **over the air via mikeos-appstore**, not `adb install`. Bump `versionCode`
(and `versionName`) in `app/build.gradle.kts`, build, then publish the APK to the store; the on-device
daemon Updater polls every ~15 min and installs it over WiFi/cellular. The store `version_code` MUST
equal the APK's internal `versionCode`, and the signing key stays the shared debug keystore.

```bash
set -a; . ~/.mikeos/provider-keys.env; set +a       # PUBLISH_KEY + APPSTORE_URL (secret, never on a phone)
curl -sS --max-time 180 -X POST "$APPSTORE_URL/api/apps/com.mikeos.maps/releases" \
  -H "X-PUBLISH-KEY: $PUBLISH_KEY" \
  -F "file=@app/build/outputs/apk/debug/app-debug.apk" \
  -F "version_code=2" -F "version_name=0.2.0-my-change" -F "notes=what changed"
```

See `mikeos-architecture/docs/PUBLISHING-APP-UPDATES.md` for the full model.

## Daemon & clouds

- **MikeDaemon** (on-device) at `https://127.0.0.1:7743` — Bearer token, self-signed loopback TLS
  trusted only for `127.0.0.1`. MikeMaps reads the shared location fix here (`GET /api/location`,
  auth-exempt) via `net/DaemonLocation.kt`, and reasons via `POST /api/agent/chat`.
- **mikeos-trips-cloud** (`https://mikeos-trips-cloud-production.up.railway.app`) — per-user journeys,
  speed/GPS trails, and the congestion model. Valid public TLS → standard OkHttp + DoH (this ROM's
  system DNS is flaky). `/api/route` is keyless; trip calls carry the hive `X-API-KEY`. See
  `net/TripsCloudClient.kt`.
- **Nominatim** — destination geocoding (`net/Geocoder.kt`).
- **mikeos-basemap** (`https://mikeos-basemap-production.up.railway.app`) — the self-hosted OSM
  vector basemap (MapLibre style + Protomaps planet PMTiles) rendered under the route. No API key.
  Set `BASEMAP_URL` in `app/build.gradle.kts` to the deployed domain. See the `mikeos-basemap` repo.

## Structure

```
app/src/main/java/com/mikeos/maps/
  MainActivity.kt          routing + active-trip + history UI (Compose), lifecycle
  MapsViewModel.kt         UI state; "Go" (geocode → fix → route → start) and end-trip actions
  trips/TripManager.kt     the DETERMINISTIC trip lifecycle: route/start/beatSample/end + hive broadcasts
  agent/MapsMikeAgent.kt   wires the shared runtime; Soul + 4 skills; hooks beatSample onto the beat
  net/                     TripsCloudClient, DaemonLocation, Geocoder, PolylineCodec, Doh
  ui/MapLibreRouteMap.kt   the real OSM map surface (MapLibre GL Native) — basemap + route + you-are-here
  ui/theme/                MikeOS dark theme

app/src/main/java/com/mikeos/core/   vendored shared MikeAgent runtime (agent, hive, runtime, net)
```
