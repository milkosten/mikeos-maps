# MikeText — CLAUDE.md

## What this repo is

MikeText is the MikeOS SMS client app-agent: it reads texts, triages unread on the on-device GPU brain, drafts replies, and routes sends through the daemon's telephony agent.

**Type:** MikeOS **Android app** (app-agent). Package / applicationId **`com.mikeos.text`**
(namespace `com.mikeos.text`), versionCode **1**, versionName **0.1.0-p1**.

## Build & install

Android app — minSdk **31**, compile/target **35**, **Kotlin 2.0**, **AGP 8.7**.

```bash
./gradlew assembleDebug --no-daemon --max-workers=2
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
# on the real device (adb over WiFi):
adb -s 192.168.1.76:5555 install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.1.76:5555 shell pm grant com.mikeos.text android.permission.READ_SMS
adb -s 192.168.1.76:5555 shell pm grant com.mikeos.text android.permission.SEND_SMS
adb -s 192.168.1.76:5555 shell pm grant com.mikeos.text android.permission.RECEIVE_SMS
```

`gradle.properties` pins `org.gradle.jvmargs=-Xmx1280m` — keep Gradle memory bounded.

## MikeOS architecture contract

- **Every app is an autonomous agent**, not a thin UI. It runs a closed loop continuously:
  **perceive → reason → act → remember → message peers → repeat**.
- **Heartbeat:** every **60s while active** (foreground Service) and every **15min while dormant**
  (WorkManager). Each beat: read context → reason on the GPU → act if warranted → write memory →
  optionally message peers.
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

## House rules — hard-won, do not repeat these bugs

These cost real incidents. Read them every time; they apply across the whole ecosystem.

- **MEMORY: never load a whole file into RAM.** An app that did
  `contentResolver.openInputStream(uri).readBytes()` with no size cap hit a **1.55 GB video**,
  exhausted phone RAM, and put the device in a **reboot loop**. Always **cap file size (~30 MB)** and
  **skip video / oversized media**; large media goes to the daemon media pipeline (ffmpeg frames +
  Whisper transcript), never inline.
- **TIMESTAMPS: cloud services expect ISO-8601 strings.** Sending epoch-ms `Long`s makes the
  FastAPI cloud return **HTTP 200 while silently persisting 0 rows**. Use
  `java.time.Instant.ofEpochMilli(x).toString()`. *(Exception: `mikeos-photos-cloud`'s `taken_at` is
  a raw int column.)* Clouds should parse timestamps tolerantly (accept both).
- **SQL: never use a reserved keyword as a column** (`left`→`left_at`, etc.). Keep migrations
  idempotent (`IF NOT EXISTS`). **Parameterized queries only** — never interpolate values into SQL.
- **DAEMON: `dist/index.js` MUST keep `require('./dns-fix.js');` as line 2** (c-ares DNS shim).
  A `tsc` recompile once silently dropped it and broke the daemon. Never emit-compile over `dist/`
  without re-adding it.
- **SECURITY (millions of untrusted devices): no key stored on a phone is ever admin.**
  Publish/admin keys live **server-side only**; device-held keys are **ingest/write-only** and should
  become **per-device IdP-minted keys scoped to their own `device_id`**.
- **Never trust HTTP 200 alone** — verify the response actually stored data (check for
  `upserted` / `stored` / a real `id`), or you'll log "success" on a silent drop.

## Shared infrastructure — READ THESE FIRST

The MikeOS source-of-truth docs live in the **mikeos-architecture** repo (the infra/architecture
repo). Read them at the absolute path before changing anything:

`/home/mikeos/projects/mikeos-architecture/docs/`
- `APP-ANATOMY.md` — the app-agent contract (§0 self-registration, the perceive→reason→act→
  remember→message closed loop, the heartbeat, §3a location authority, §4 messaging, §7 events).
- `AUTH.md`, `INFRASTRUCTURE.md`, `EVENTS.md`, `OAUTH.md`, `APP-CATALOG.md`, `STATUS.md`, `API-NEEDS.md`.
- `reference/hive/` — seed of the shared `mikeos-android-core` SDK: `HiveIdentity.kt`, `MikeHive.kt`,
  `MikeEvents.kt`, `VoiceInput.kt` (each app copies these into its own `com.mikeos.<name>.hive`).

(A mirror also exists under `/home/mikeos/projects/mike-ecosystem/mikeosinfrastructure/`; the
top-level `mikeos-architecture` is canonical.)
