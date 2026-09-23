# Chorus — notifications

Two families:

- **Switch notifications** to *followers* (friends, partners, other systems) — the privacy-heavy
  part: per-switch, per-member and per-recipient rules, random delay, time fuzzing, digests.
- **Activity notifications** to the account's own devices — mentions, DMs, replies, review cards,
  follow requests.

---

## 1. Delivery

- **UnifiedPush via ntfy** on the tailnet (D-035). Each device registers a UnifiedPush endpoint
  (`device.push_endpoint`). The server POSTs to it.
- Payloads are encrypted with **RFC 8291 (Web Push encryption)** using keys the app registered, so
  ntfy only relays ciphertext. Payload ≤ 3 KB: the rendered, already-filtered notification. If it
  would be larger, the server sends a **tickle** (`{"t":"sync"}`) and the app syncs, then renders.
- Fallbacks (Settings → Advanced → Delivery): persistent foreground WebSocket service; or
  WorkManager poll every 15 min.
- **Web PWA:**
  - In-page notifications while it is open.
  - **Web Push** (D-061) once "Notify me in this browser" is on. It works in the built app only,
    since dev builds have no service worker. The payload is the same encrypted one, delivered
    through the browser's push service with a VAPID signature.
- Every notification is also a row in `notification` (history, debugging, "Notifications" screen).

## 2. The switch-notification pipeline

For each surviving switch `S` of account `A` (after projection), and each follower `F` with an
active follow on `A`:

```
S ─▶ settle ─▶ per-switch ─▶ visible diff for F ─▶ per-member ─▶ ceiling(A→F) ─▶ prefs(F)
                                                                            │
                                    schedule (delay) ◀──────────────────────┘
                                          │
                                 supersede / collapse ─▶ fuzz displayed time ─▶ deliver
                                          │
                                 reveal to follower view (§5)  ← happens even if F muted
```

1. **Settle.** Nothing is scheduled until `settle_s` (default 15 s, ≥ undo window) after the
   switch is accepted, so undone switches never notify. Retracting later cancels anything still
   pending.
2. **Per-switch** (`switch.notify`, chosen in the switcher; default `default`):
   `silent` (no notification, but still revealed on schedule — §5), `default`, `now` (skip random
   delay where the ceiling allows `allow_now`), `extra_delay` (adds the system's
   `extra_delay_range`).
3. **Visible diff.** Compute F's view of the front before and after (only subjects F may see;
   levels F may see). Hidden subjects are omitted or shown as "someone" per ceiling
   `hidden_subjects`. If the visible diff is empty → stop.
4. **Per-member** policy (`member.notify_policy`): `announce: everyone | buckets[…] | nobody`,
   optional `extra_delay_range`, and `announce_leaving: bool`. A member with `nobody` is treated as
   hidden for notifications even if visible on their profile.
5. **Ceiling** (system → follower, §3). Hard limits: which levels, min/max delay, fuzz, digest-only,
   max per day, whether switch-outs are sent.
6. **Prefs** (follower, §4). Can only narrow: fewer members/levels, quieter hours, digests, mute.
7. **Schedule.** `delay = uniform(min, max)` from the effective range, drawn once per
   (switch, follower) from a CSPRNG and persisted as `due_at` (restarts don't redraw).
   `due_at = S.occurred_at + settle + delay`. If `due_at` is already past (switch arrived late
   because the phone was offline), `due_at = now + uniform(0, 120 s)`.
8. **Supersede** (ceiling `stacking`):
   - `collapse` (default): a newer pending notification to F about A cancels older pending ones;
     the survivor describes the latest visible state ("Kai & June are fronting").
   - `sequence`: every switch is delivered, in order; `due_at` is bumped so order is preserved.
9. **Fuzz displayed time** (§3 `time`). The notification shows `displayed_time`, never the raw
   time. Guarantee: `displayed_time ≤ delivered_at` and non-decreasing per (F, A).
10. **Quiet hours** (from ceiling or prefs, whichever is stricter): hold until the end (collapsing)
    or drop, per prefs.
11. **Digest**: if digest-only (ceiling) or chosen (prefs), items go into the next digest instead
    of being delivered. Digest times: hourly, or daily at a time F picks (± random 0–20 min).
    Content: fuzzed, e.g. "Today at Stars: Kai (morning), June & Rin (evening)".
12. **Max per day**: beyond the limit, items fall into the next digest.

## 3. Ceiling (set by the followed account)

Stored as JSON: defaults on the account, per-bucket (`bucket.ceiling`), per-follow override
(`follow.ceiling`). Effective value per field: follow override → most permissive among the
follower's buckets → account default.

```json
{
  "switch_notifications": true,
  "levels": ["front", "cocon"],
  "hidden_subjects": "omit",               // omit | someone
  "announce_leaving": false,
  "delay": { "min_s": 300, "max_s": 1200 },
  "extra_delay": { "min_s": 1800, "max_s": 7200 },   // added for switches sent as extra_delay
  "allow_now": true,
  "time": { "mode": "round", "round_min": 15 },  // exact | round | jitter | part_of_day | hidden
  "stacking": "collapse",                  // collapse | sequence
  "digest_only": false,
  "max_per_day": null,
  "quiet_hours": null,                     // {"from":"23:00","to":"08:00","tz":"system"}
  "share_current_front": true,             // follower view shows who is here now
  "share_history": false,                  // follower can browse (fuzzed) front history
  "share_stats": false                     // front-time stats on profiles
}
```

**Presets** (Basic UI shows only these, per follower or per bucket):

| Preset | Delay | Time shown | Other |
| --- | --- | --- | --- |
| Close — instant | 0 | exact | switch-outs on |
| Gentle | 5–20 min | rounded to 15 min | |
| Private | 30–90 min | part of day ("this evening") | co-con hidden |
| Digest | — | part of day | daily digest only |
| Off | — | — | no switch notifications; profile only |

The UI explains the difference in one line: *delay hides **when** you switched as it happens;
fuzzing hides the exact time afterwards — use both for real privacy.*

## 4. Prefs (set by the follower)

```json
{
  "enabled": true,
  "subjects": { "mode": "all" },          // or {"mode":"only","ids":[…]} / {"mode":"except","ids":[…]}; group ids cover their members
  "levels": ["front"],
  "switch_outs": false,
  "delivery": "each",                     // each | digest_hourly | digest_daily
  "digest_time": "20:00",
  "quiet_hours": { "from": "23:00", "to": "08:00" },
  "quiet_behaviour": "hold",              // hold | drop
  "mute_until": null,
  "tz_offset_min": 540,                   // the follower's UTC offset, sent by their client
  "channel": "switches"                   // Android channel/sound choice
}
```

`quiet_hours` and `digest_time` here are the **follower's** local time, using `tz_offset_min`.
Clients send their current offset whenever they save prefs. Without it, the switch's (system's)
offset is used, as it always is for the ceiling's own `quiet_hours`.

## 5. The follower-view invariant (D-016)

A follower must not learn more, or earlier, from **any** surface than from their notifications
under the ceiling. Surfaces: profile "fronting now" badge, member "last fronted", stats, history,
feeds, API tokens, SSE stream, view sync.

Rules:

1. The server maintains `follower_front_view` per (follower, account). It is updated **only at
   reveal time** — the `due_at` computed in §2.7 — whether or not a notification is delivered
   (muted, silent switch, prefs-filtered, digest). Silent switches still reveal on the default
   schedule; `silent` means "don't ping", not "rewrite history".
   *Exception:* `switch_notifications: false` + `share_current_front: false` → never revealed.
2. `displayed_since` in the view is fuzzed with the same `time` rule.
3. View-sync frames for F are emitted at reveal time only. No version bumps, counters or `ETag`s
   that change at the real switch time are visible to F.
4. "Last fronted", stats and history shown to F are computed from **revealed** intervals, rounded
   to the fuzz granularity (part-of-day → date only).
5. Followers get no webhooks and no raw op access for other accounts.
6. Shared-space chat is a known side channel: a message authored as Kai reveals Kai is around at
   that time. The sharing screen says so plainly; systems can post with a "delay send" option
   (Advanced, per space) that holds outgoing messages for a random 0–N minutes.

Tests (M8): for random switch sequences and ceilings, assert that the sequence of follower-visible
states over time (from every surface) equals the sequence of revealed states, and that no surface
changes between reveal times.

Implemented as `follower_surfaces_only_change_at_reveal_times` in
`crates/chorus-server/tests/notifier.rs`. It covers the follower view and the inbox; add any new
follower surface to its `surfaces` closure. A mutation that reveals one minute early fails it.

## 6. Late arrivals and server downtime

- Switches that reach the server late (offline phone) are scheduled from their real
  `occurred_at`; anything already overdue is delivered at `now + uniform(0, 2 min)`, collapsed per
  follower. No floods.
- Server restart: pending rows in `notification` are resumed by `due_at`.
- Delivery failure: retry with backoff for 24 h, then mark failed; the item still appears in the
  follower's in-app notification list on next sync.

## 7. Activity notifications (own account)

| Kind | Default | Notes |
| --- | --- | --- |
| Mention of a member | on | Per member: "notify me for mentions of Kai: always / only when Kai is fronting or co-con / never". `@front` pings current fronters. |
| DM (account-to-account) | on | |
| Member DM (internal) | on for recipients fronting | Per member toggle. |
| Reply to a post / message | on | |
| Reactions | off | |
| Channel messages | per channel: all · mentions · none | Default: internal channels "mentions", shared spaces "mentions", DMs "all". |
| Review card | on | Concurrent switches need a look (SYNC.md §5.7). |
| Follow request | on | |
| Own switches from other devices | off | "Switched to Kai (from phone)" on the PC browser. |
| Sync issues | on | Rejected ops. |

Android: MessagingStyle notifications with member avatars as `Person`s, grouped per channel;
inline reply (`RemoteInput`) sends as the current primary fronter (or the member the notification
mentions, if Advanced "reply as mentioned member" is on) and works offline (queued op).
Notification channels: Switches, Digests, Mentions, Direct messages, Replies, Channels, System —
the user can tune each in Android settings too.

## 7a. Implementation (`chorus_core::notify`)

All of §2–§5 that must agree everywhere is pure core code: `effective_ceiling` (layers + the
most-permissive bucket fold; a bucket that leaves a field unset inherits the account default for
it), `view` (the reveal), `diff` (what a follower is pinged about), `due_at` (settle + delay +
late-arrival spread; random draws are passed in and persisted by the server), `supersede`,
`quiet_until`, `route` (deliver / digest / reveal-only) and `displayed` (time fuzzing with the
≤ delivered, non-decreasing guarantee — property-tested in `tests/notify_props.rs`).
Member policy JSON: `{"announce": "everyone" | "nobody" | {"buckets": […]}, "announce_leaving":
bool, "extra_delay_range": {min_s, max_s}?}`. Leaving is announced when the ceiling and the
follower's `switch_outs` allow it and the member announces to everyone (or opted in with
`announce_leaving`).

## 8. Data model additions

- `member.notify_policy` JSON (default `{"announce":"everyone","announce_leaving":false}`).
- `bucket.ceiling` JSON (default `{}` = inherit account default).
- Account default ceiling in `account.settings.follow_ceiling`.
- `notification` table and `follower_front_view` (DATA_MODEL.md §4).
