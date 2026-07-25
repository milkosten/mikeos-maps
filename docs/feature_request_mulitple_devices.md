# Multi-Device Plan — MikeMaps (maps)

> Part of the MikeOS **multi-device** rollout (one user `mikaelwestoo@gmail.com`, two phones:
> **Note 10** + **Pixel 10 Pro**). Read the authoritative design first:
> **`mikeos-architecture/docs/MULTI-DEVICE.md`** — this file applies it to **this app**.
> Repo: `mikeos-maps` · Cloud: `mikeos-trips-cloud` · Category: **device-local**.

## 0. Bottom line
**Your data is about THIS phone specifically.** It must be tagged with `device_id` and must NOT be cross-applied to the other device. Both phones run you independently against their own rows.

**What this app manages:** active navigation on the phone in hand; consumes shared places

## 1. Sync rule for this app
Every row carries `device_id`. Each device reads/acts on **only its own** rows. Aggregate across devices ONLY when the feature explicitly wants it (e.g. 'all my devices'). Never run one device's logic against another's data.

> App-specific: Navigation is inherently on the device you're holding (device_id). Consumes user-shared places/routes.

## 2. Identity — how the system tells the two phones apart
- Keep `X-API-KEY → user_id` (both phones = same user → shared data). **Do not split the user.**
- **Send `X-DEVICE-ID: <daemon device_id>` on every cloud call** (comes free from the shared core once the
  fleet-wide core change lands). This is how the cloud knows which phone is calling. It is
  **write-scoped, never admin** — it identifies only.
- Your agent already registers in the hive as `mikaelwestoo/<device>/MikeMaps` — the device
  segment is your per-device identity in the hive.

## 3. Cloud changes (`mikeos-trips-cloud`)
active-trip rows carry device_id; saved routes user-scoped
- Migrations idempotent (`IF NOT EXISTS`), no reserved-keyword columns, parameterized SQL, ISO-8601
  timestamps, never-trust-200 (verify the row stored, including its `device_id` when relevant).
- If adding `device_id`: make it nullable, include it in the unique key where rows are per-device, and
  **backfill existing rows with the Note 10 id** (`3b5a6e4d-541e-4891-93fb-b77234e3ebf5`).

## 4. App changes (`mikeos-maps`)
navigate on this device; read shared places; handle maps.route same-device
- Ensure a **pull-on-heartbeat** path so this device's Room cache reflects the shared cloud state.
- Keep it offline-first: the local cache must still work with no network and reconcile on the next beat.
- Deterministic on the heartbeat (not LLM-gated) for any proactive/sync action.

## 5. Hive / cross-agent
maps.route handoff same-device
- **Peer queries prefer the SAME device** (match the `<device>` segment) — don't reach the other phone's
  agents unless the feature is explicitly user-level.

## 6. Notifications — no double-buzz
Rule for this app: **local (turn-by-turn)**.
- User-level notifications: only the **primary/active device** raises them (gate on
  `GET /api/devices/primary` or a cloud 'notified' claim keyed by event id).
- Device-local notifications: always local.

## 7. Checklist
- [ ] Core change landed (app sends `X-DEVICE-ID`) — fleet-wide, tracked in the master plan.
- [ ] Cloud: add device_id + backfill Note10.
- [ ] App: pull-on-heartbeat refresh; tag device_id on writes.
- [ ] Notifications deduped per rule above.
- [ ] Verify with BOTH phones registered (`GET /api/devices` shows 2).

## 8. Acceptance criteria
- With both phones on, `GET /api/devices` lists Note 10 **and** Pixel 10 as the same user.
- This app on each phone shows/acts on only its own device data; the other phone is unaffected.
- No duplicate notifications for the same user-level event.
- No data from one phone incorrectly applied to the other.

## 9. House rules (unchanged)
`X-DEVICE-ID` identifies only (never admin). ISO-8601 timestamps. Numeric fields numeric. Idempotent
migrations, no reserved-keyword columns, parameterized SQL. Never trust HTTP 200 — verify stored data.
One shared location (daemon `/api/location`). Free GPU only; cost = zero. Never load a whole file into
RAM (cap ~30 MB).

---
*Generated from `mikeos-architecture/docs/multi-device-index.json` by the multi-device planner cron.
Master design: `mikeos-architecture/docs/MULTI-DEVICE.md`.*
