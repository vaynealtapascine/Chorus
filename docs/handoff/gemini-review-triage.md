# Gemini review triage (2026-11-06, claude-opus-5.5)

Gemini (gemini-3.8-flash-high, via Antigravity) wrote four read-only reviews on 2026-11-05. They
sit in the untracked `gemini_review/` folder (not committed: unverified claims). Every finding was
checked against the code; this file records the verdict and what was done, so nobody re-checks
them. **Fixed** = changed and tested in SEC1. **Rejected** = the claim is wrong, or the spec
decides otherwise. **Open** = real, but needs the owner, a device, or a later slice.

## SECURITY_AUDIT.md

The cross-account write holes were found the day before by our own audit
(`crates/chorus-server/tests/privacy_audit.rs`, a01–a12); Gemini's SEC-01…SEC-13 restate the same
root cause. One rule fixes them (D-119, SYNC.md §4.1): an entity lives in the scope it was made in,
and payload ids/files an op names must be the author's to name. Tests a13–a18 cover the rest.

| ID | Verdict | Notes |
| --- | --- | --- |
| SEC-01 follow.accept by the follower | Fixed | a01 |
| SEC-02 follow.set_ceiling by the follower | Fixed | a02 |
| SEC-03 follow.end of someone else's follow | Fixed | a10 |
| SEC-04 member.set/delete/archive on another system | Fixed | a03, a09 |
| SEC-05 bucket.assign into another's bucket | Fixed | a04 |
| SEC-06 bucket.set/delete | Fixed | a05, a15 |
| SEC-07 feed.set/delete | Fixed | a07, a15 |
| SEC-08 field.define/set_def/set_value | Fixed | a06 (it used a non-existent kind before; now `field.set_def`), a08 |
| SEC-09 groups | Fixed | a14; also: adding another system's member to one's own group |
| SEC-10 highlights | Fixed | a12 |
| SEC-11 states, lists | Fixed | a15 (list *items* may be other systems' members: Journal lists, so only the list itself is owned) |
| SEC-12 account.set / system.set | Fixed | a13. Clients never write these; only the server's demo seed does |
| SEC-13 foreign attachment ids in posts/messages | Fixed | a11, a17. Also the sibling hole Gemini missed: naming another account's *blob hash* in one's own attachment or avatar made `can_read` hand it out. Now an op may name a stored blob only if its author uploaded it, holds it (sent the bytes) or can read it; else `blob_pending` (held, retried). a18 |
| SEC-14 dedup existence oracle on PUT | Fixed | Another account's finished blob answers like a new upload and compares the bytes (`blobs.rs` `prove`, `blob_holder`, migration 0020). Partial uploads still answer 403 to others (minor, left) |
| SEC-15 search `from:`/`in:` scan all members/channels | Partly | No leak: hits are limited to messages the reader can see, which already show their authors. Channel names now resolve only in the reader's spaces; members stay server-wide (an account that left a space still wrote its old messages) |
| SEC-16 named-front avatars unreadable | Fixed | A granted front's revealed member avatar is readable; revoked grants delete their rows (notifier.rs) |
| SEC-17 `/relationship-targets` lists all accounts | Rejected (as a leak) | It returns only cards each member's own visibility allows (`server` = everyone on the server, by design). Cost is O(accounts) per call: fine at this size |
| §5 side channels | Agreed | Matches NOTIFICATIONS.md §5 |

Not yet done: whether anyone used these holes on the public server (open since 2026-10-31). Ingest
now refuses them, but ops accepted earlier stay in the log and re-project on a rebuild. Check the
live log read-only with `scripts/privacy-audit.sql` (docs/NOTES.md §Security) before deciding on a cleanup purge.

## BUGS_FOUND.md

| ID | Verdict | Notes |
| --- | --- | --- |
| BUG-01 rate limit bypass with made-up tokens | Fixed | Credentialed requests also pay a per-address bucket four devices large; a full table drops credential buckets instead of scanning on every request. Unit tests |
| BUG-02 export packs any hash its ops name | Fixed | Ingest refuses such ops now; exports also pack only files the account may read (old ops) |
| BUG-03 Chorus Home setup from a web page | Fixed | `local_only` also requires a loopback `Host`, a loopback or absent `Origin`, and not `Sec-Fetch-Site: cross-site` (DNS rebinding / CSRF). Test in `home_setup.rs` |
| BUG-04 deleted members' poll votes count | Open → **Q23** | POLLS.md checks the member at voting time only; deletion is a restorable tombstone. Default: keeps counting |
| BUG-05 unpinned webhook/push targets | Fixed | A name that didn't resolve at check time is resolved at delivery through a guard that applies the same address rule. Also (TRIM-10) webhooks' `internal` mode now refuses link-local (cloud metadata), like push |
| BUG-06 `utf16_slice` asymmetry | Rejected | Both ends round forward to a code-point boundary, it never splits a pair, and every client runs this one core function, so nothing can desynchronise. Offsets inside a pair are invalid input anyway |
| BUG-07 poll tallies on parent edits | Rejected | POLLS.md: edits may not carry `poll`, so options never change |
| BUG-08 two writes per authenticated request | Fixed | Session expiry and `last_seen_at` move at most once a minute |
| BUG-09 restore window reopens if the clock steps back | Fixed | Expiry writes `restore_open = 0`; test |
| BUG-10 post alert face is the first author | Fixed (minor) | The title already named all authors; the face and `member_id` are now the first author the subscriber follows |

## TRIM_CANDIDATES.md

| ID | Verdict | Notes |
| --- | --- | --- |
| TRIM-02 hex | Fixed | `crate::hex` (server); core's `color`/`member_order` hex are different functions |
| TRIM-03 hash validators | Fixed | `blobs::valid_hash` (export, import, purge, backup) |
| TRIM-04 blob path sharding | Fixed | `blobs::sharded` |
| TRIM-05 base64url | Fixed (part) | fcm.rs uses `push::b64url`. The two `Cursor` structs differ (search vs feeds) and stay |
| TRIM-07 bearer extraction | Fixed | `auth::bearer` (app, blobs, home, ratelimit) |
| TRIM-08 `need` | Fixed | `api_data::need`, with `api_data::parse` (TRIM-09's copies in the same two modules) |
| TRIM-09 other JSON-column closures | Open | Small and local; fold in when touching those modules |
| TRIM-01 civil-date math | Open | `chorus_core::search::days_from_civil` and `export_job::ymd` are the only named copies; others are inline. Worth one core `time` helper when next touched |
| TRIM-06 `valid_handle` into core | Rejected | One copy, server-only |
| TRIM-10 webhook vs push IP rules | Fixed (the security half) | See BUG-05 |
| TRIM-11 `Fronter` vs `Presence` in core | Open | A model refactor; not without a reason beyond tidiness |

## DESIGN_SUGGESTIONS.md (Android)

| Item | Verdict | Notes |
| --- | --- | --- |
| 1.1 default to system theme | Rejected | DESIGN.md: dark is the default (D-079) |
| 1.2 Material You | Rejected as default | Planned as an Advanced theme (DESIGN.md "Additional themes") |
| 1.3 more M3 colour roles | Open (low) | Only matters for stock M3 components that read them |
| 2.1 nav-bar inset | Open (device check) | The shell applies the inset once on purpose so focused routes without the bar get it; Gemini's change would break those. Worth a look on the phone for a colour band under the bar |
| 2.2 / P3 TopAppBar migration | Open | Polish, owner's call |
| 3.1 / 3.2 platform theme, dark splash | Rejected | Deliberate charcoal first frame (themes.xml, D-079) |
| 3.3 translucent SearchActivity | Rejected | It is a launcher overlay; translucency is intended |
| 4.1 pin arrows under 48 dp, unlabeled | Fixed | 48 dp targets, spoken names |
| 4.2 settings toggles: switch-only target | Fixed | Whole row toggles, `Role.Switch` |
| 4.3 ★ / × glyphs unlabeled | Fixed | Content descriptions |
| 5.1 typed timestamps → pickers | Open | UX change for the owner |
| 5.2 SearchField vs M3 SearchBar | Open | Polish |
| 6.1 PNG icons → vectors | Open | Asset work; check what the Figma export gives |
| 7.x predictive back | Open (device check) | Opting in changes system back animations; try on the phone first |
| 8.1 large screens | Open | Later |
