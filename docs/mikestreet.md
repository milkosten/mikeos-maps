# MikeStreet — crowdsourced street imagery from the dashboard

**What:** when Mike's phone sits on the dashboard, its camera has a clear view of the road. MikeStreet
turns that into a **street-imagery data lake** — a frame every second while driving/walking a road or
trail, each paired with a `.gps.json` (position, heading, speed, …). We can later mine it: POIs, signs,
road conditions, a home-grown street-view, congestion signals.

Think Mapillary, self-hosted and free, feeding the MikeOS fleet.

> **Owner:** MikeMaps hosts the **capture** (it already owns motion + GPS + speed + heading + the
> driving context). The **lake** is a new self-hosted service, `mikeos-street`, on the media box.

---

## Status

- **P1 — on-device capture (this cycle): ✅ building.** The `Give data to Mike Ecosystem` setting +
  CameraX capture + local frames + `.gps.json`, size-capped, **no upload yet**. Prove it on the Pixel.
- **P2 — road/trail vision gate:** classify a probe frame on the daemon GPU (qwen2.5vl) → only go to
  1 fps when it's actually a forward road/trail (not a pocket/indoors).
- **P3 — the lake:** `mikeos-street` in **Docker on the media box `91.98.177.242`** (Hetzner, EU/GDPR),
  frames on the **RAID6 `/data` (~100 TB)**, Postgres/PostGIS index, ingest + spatial-query API,
  upload pipeline from the phone.
- **P4+ :** face/plate blurring (deferred — single-user for now, no privacy work yet), viewer, mining.

---

## Capture state machine

```
setting OFF                       → nothing
setting ON + moving (>3 km/h)     → PROBE: 1 frame / 60 s          [P2: classify probe on GPU]
    probe is a forward road/trail → ACTIVE: 1 frame / 1 s          [P1: any movement → ACTIVE]
    (re-check every ~30 s; not road → back to PROBE)
stationary / OFF / low battery    → stop, close session
```

**P1 simplification:** no vision yet, so *moving → ACTIVE (1 fps)* directly. Capture only runs while
MikeMaps is **foreground** (the dashboard-nav case) — Android restricts background camera, and that's
fine: the nav screen is up while driving.

## Per-frame files (`frame_<epochMs>.jpg` + `frame_<epochMs>.gps.json`)

Downscaled JPEG (~1280 px) beside its metadata:

```json
{
  "ts_capture": "2026-07-27T22:14:05.123Z",
  "lat": 43.7009, "lon": 7.2683,
  "speed_kmh": 42.1, "speed_ms": 11.7,
  "heading_deg": 95.0,
  "device_id": "…", "session": "…", "trip_id": "… if a MikeMaps trip is active",
  "daemon_raw": { …the full /api/location object: altitude, accuracy, satellites, … }
}
```
Everything comes from the ONE daemon fix (`GET /api/location`) — we embed the **raw** object under
`daemon_raw` so we keep *as much GPS as the daemon has*, plus derived speed/heading.

## On-device storage (P1)

`getExternalFilesDir("mikestreet")/<session>/frame_<ts>.{jpg,gps.json}` — app-private, no permission.
**Size-capped ring buffer** (default ~400 frames / ~80 MB, oldest deleted) since there's no upload yet
(memory house rule: never unbounded, never load whole media into RAM). Inspect via
`adb pull … /Android/data/com.mikeos.maps/files/mikestreet`.

## P3 — the lake (`mikeos-street`, Docker on 91.98.177.242)

- FastAPI + asyncpg (standard MikeOS cloud template), **in Docker on the media box**, user-scoped
  (Bearer/X-API-KEY → `user_id`).
- `POST /api/street/frames` (multipart: image + gps.json) → store on RAID6:
  `/data/mikestreet/<user_id>/<yyyy>/<mm>/<dd>/<session>/frame_<ts>.{jpg,gps.json}`
- Postgres **PostGIS** index (frame_id, user_id, lat, lon, heading, speed, ts, path, trip_id, label)
  for "frames near X" / "along road Y".
- Phone upload pipeline: batched, WiFi-preferred, resumable, **never-trust-200** (confirm stored →
  delete local).
- `GET /api/street/near?lat&lon&radius` → frames; a viewer later.

## Resource guards

Foreground-only; pause on low battery; storage cap; (P3) WiFi-preferred upload + data cap. No frames
when parked/stationary. Vision (P2) runs on probes only, not every frame.

## Permissions

`CAMERA` (runtime-requested **only when the setting is switched on** — the app otherwise holds no
app-level permissions). Location is never the app's — always the ONE daemon fix.
