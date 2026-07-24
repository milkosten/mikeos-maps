# MISSING.md — open blockers & handoff (MikeMaps + mikeos-basemap)

Snapshot for another session to pick up. Everything below is either waiting on a human action or on
a long-running background job. Code is committed; nothing is lost.

## Two repos
- **`/home/mikeos/projects/mikeos-maps`** (Android app, `com.mikeos.maps`) — remote
  `git@github.com:milkosten/mikeos-maps.git`, **pushes fine over SSH**.
- **`/home/mikeos/projects/mikeos-basemap`** (Railway basemap service) — committed locally,
  remote wired to `git@github.com:milkosten/mikeos-basemap.git` but **the GitHub repo does not exist yet**.

## Blockers

### 1. `mikeos-basemap` GitHub repo doesn't exist (push blocked)
- `git push` → `ERROR: Repository not found`. SSH auth works (authenticated as `milkosten`), so this
  is purely "the repo hasn't been created" — an SSH key can push but cannot *create* a repo.
- `gh` CLI is **not installed** on this machine (searched PATH + filesystem — only an X11 keyboard
  file named `gh` exists). No `GITHUB_TOKEN`/`GH_TOKEN` env, no `~/.config/gh/hosts.yml`, no
  `~/.netrc`, no `~/.git-credentials` found.
- **UNBLOCK (pick one):**
  - Create an empty repo `milkosten/mikeos-basemap` at https://github.com/new (no README), then:
    `git -C /home/mikeos/projects/mikeos-basemap push -u origin HEAD`
  - Or provide a GitHub API token → create via `POST https://api.github.com/user/repos {"name":"mikeos-basemap"}` then push.
  - Or, if a repo already exists under a different name, repoint the remote and push.

### 2. Planet basemap tiles not live yet (background download)
- Service is deployed: Railway project **mikeos-basemap** / service **mikeos-basemap**, domain
  **https://mikeos-basemap-production.up.railway.app**. Volume `/data` resized to **250 GB**.
- The ~**127 GB** planet (`https://build.protomaps.com/20260723.pmtiles`) is **downloading in the
  background** (several hours; resumable via `curl -C -` across restarts).
- **LIVE now:** `/health`, `/style.json`, `/assets/fonts/*`, `/assets/sprites/*`.
- **NOT live until download finishes:** `/planet.json` and `/planet/{z}/{x}/{y}.mvt` (the road tiles).
  Verify with `curl -s https://mikeos-basemap-production.up.railway.app/planet.json` → should return
  TileJSON. Until then the app shows style/labels/route/puck but **no road tiles**.
- Check progress: `cd /home/mikeos/projects/mikeos-basemap && railway logs | grep -i planet`.

### 3. App not device-verified
- Compiles clean (`versionCode 3`, `0.3.0-mapfirst`, arm-only, ~78 MB APK). **Not run on the phone.**
  The moving dot depends on the daemon actually returning fresh `/api/location` fixes — needs a real
  device to confirm the map-first feel (dot moves, follow, HUD, 5 s sampling).

### 4. OTA publish pending
- APK at `app/build/outputs/apk/debug/app-debug.apk`, **not yet published** to mikeos-appstore.
- Publish per `mikeos-architecture/docs/PUBLISHING-APP-UPDATES.md` (uses `PUBLISH_KEY` from
  `~/.mikeos/provider-keys.env`; publish under `com.mikeos.maps` with `version_code=3`).

## Pinned versions to sanity-check (these drift)
- **go-pmtiles `1.31.2`** — Dockerfile ARG `PMTILES_VERSION` (basemap repo); bump if the release 404s.
- **`@protomaps/basemaps` `5.7.2`** — style generation; must match the planet tile schema.
- **MapLibre `org.maplibre.gl:android-sdk:11.8.0`**.
- **Protomaps planet build date `20260723`** — builds are retained ~7 days; a redeploy weeks later
  needs a newer dated URL in `PMTILES_DOWNLOAD_URL`.

## Notes / decisions
- Serving model is **server-side tiling** (go-pmtiles) so the app needs **no client PMTiles plugin**.
- `OfflinePrefetch` is best-effort; `setOfflineTileCountLimit` was removed (default 6000-tile limit
  covers the ~2200-tile, 100 km / z0-13 region).
- High-cadence **5 s** cloud sampling lives in `TripManager` (started on `startTrip`, stopped on
  `endTrip`); the 60 s heartbeat now only reports status.
