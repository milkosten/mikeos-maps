# MikeText

**The MikeOS messaging app.** A native Kotlin/Jetpack Compose SMS client that is also an
autonomous agent — it reads your texts, triages the unread ones on the on-device GPU brain,
drafts replies, and routes sends through the daemon's telephony agent. Part of the
[MikeOS](https://github.com/milkosten) app fleet (see `mikeos-architecture/docs/APP-ANATOMY.md`).

Package `com.mikeos.text` · minSdk 31 · compile/target 35 · "MikeText".

## What it does

- **Read** — reads SMS from the system provider (`content://sms`, permission `READ_SMS`).
  Shows a **conversation list** grouped by address (newest first, with unread badges) and a
  **thread view** (in/out message bubbles).
- **AI drafts** — in a thread, the **Suggest** button sends the recent thread text to the brain
  (`POST /api/agent/chat`) and shows 1–3 draft replies you can tap to pre-fill the composer.
- **Send** — MikeText never holds `SEND_SMS`. It routes sending through the daemon's telephony
  agent: `POST /api/agent/chat {"message":"send an SMS to <number> saying: <text>"}`. The daemon's
  `send_sms` tool transmits via radio (`ISms.sendTextForSubscriber`) and records to
  `content://sms/sent`, which the app then reads back.
- **Closed loop + heartbeat** — on each beat it perceives unread SMS, asks the brain to
  summarize/triage them, and surfaces anything needing a reply as a notification. An SMS
  `ContentObserver` fires an immediate beat when the messages table changes (reactive loop).

## Closed loop (App Anatomy)

```
PERCEIVE (unread SMS via content://sms)
   -> REASON (daemon /api/agent/chat: triage unread / draft replies)
      -> ACT   (notify user; route sends through daemon telephony agent)
         -> REMEMBER / message peers (MikeMesh)
```

- **Heartbeat:** 60s foreground `HeartbeatService` (active) / 15min `HeartbeatWorker` (dormant),
  plus a reactive `ContentObserver` on `content://sms`.
- **Change-gate:** the GPU is only asked to triage when the set of unread messages actually
  changed, so idle beats are cheap.

## MikeMesh message types

| Type           | Direction | Payload                                  | Meaning                                  |
|----------------|-----------|------------------------------------------|------------------------------------------|
| `sms.received` | publish   | `{ address, body, date }`                | A new inbound SMS was observed.          |
| `sms.sent`     | publish   | `{ address, body, date }`                | An SMS was sent (via the daemon agent).  |

Other fleet agents (e.g. MikeMind, MikeBrief) can subscribe to these to weave messaging context
into shared memory and daily briefs.

## Build / install

```bash
export ANDROID_HOME=/home/mikeos/android-sdk        # JDK 17
./gradlew :app:assembleDebug
adb -s 192.168.1.76:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# Runtime permissions (READ_SMS is required to render; sending goes through the daemon)
adb -s 192.168.1.76:5555 shell pm grant com.mikeos.text android.permission.READ_SMS
adb -s 192.168.1.76:5555 shell pm grant com.mikeos.text android.permission.RECEIVE_SMS
adb -s 192.168.1.76:5555 shell pm grant com.mikeos.text android.permission.POST_NOTIFICATIONS
```

## Daemon

Talks to the on-device MikeDaemon at `https://127.0.0.1:7743` (Bearer token, self-signed
loopback TLS trusted only for `127.0.0.1`/`localhost` — traffic never leaves the phone). See
`net/DaemonClient.kt`.

## Structure

```
app/src/main/java/com/mikeos/text/
  MainActivity.kt        conversation list + thread UI (Compose), permission requests, lifecycle
  TextViewModel.kt       UI state, thread state, suggest/send actions
  net/                   DaemonClient (OkHttp + Bearer + loopback TLS) + wire types
  sms/                   SmsRepository (content://sms reader), SmsSender (routes via daemon)
  agent/                 TextAgent (closed loop), HeartbeatService/Worker, Notifier
  ui/theme/              MikeOS dark theme
```
