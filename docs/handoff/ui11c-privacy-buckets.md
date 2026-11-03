# UI11c — privacy buckets (D-115)

Owner request (2026-11-02, slice 3 of `owner-three-slices.md`): customizable notification
controls, name and icon for each bucket, and the same bucket presentation everywhere privacy is
controlled. GPT-6.1-sol started it in `F:/DunBuild/chorus-avatars` (feat/links) and was
interrupted by the 0.1.706 regression; claude-opus-5.5 carried that WIP into a fresh worktree
`F:/DunBuild/chorus-buckets`, branch `ui11c`, from main `a0c30a0`, on 2026-11-03.

## What it does

- **Server, privacy invariant.** A reveal is planned when the front changes. Sol's audit found
  that a policy made stricter afterwards didn't reach reveals already pending. Now
  `notifier::changes_policy` / `on_policy_change` (called from ingest) re-plans every pending
  reveal of the account from the same switch time under the new rules, on: `bucket.set` with a
  ceiling, `bucket.delete`, `bucket.assign`/`unassign`, `follow.set_ceiling`, the account
  default (`pref.set follow_ceiling`, or `account.set settings.follow_ceiling`) and
  `member.set notify_policy`. Name/icon changes don't. Tests: `tests/notifier.rs`
  `a_stricter_bucket_holds_back_a_reveal_already_pending`,
  `leaving_a_bucket_before_the_reveal_keeps_its_member_hidden`.
- **Bucket look.** `bucket.icon` in core `BUCKET_FIELDS` (migration `0019_bucket_icon`): a built-in
  icon from `fixtures/ui_bucket_controls.json` or the bucket's own emoji; `bucket.color` (already a
  column): a swatch, any `#RRGGBB`, or none, toned like a member's colour. Web `BucketGlyph`/
  `BucketIconPicker`, Android `BucketGlyph` and the editor's emoji field and colour row.
- **Audience menus** show every option at once (Everyone here no longer behind More…); past
  eight buckets the rest fold under "N more buckets", keeping chosen ones visible
  (`listedBuckets` / `BucketControls.listed`, `menu_collapse_after` in the fixture).
- **Advanced ceiling controls**, one definition for both clients (`fixtures/ui_bucket_controls.json`:
  fields, choice labels, limits, validation cases; web `bucket-controls.ts` +
  `CeilingControls`/`CeilingField`, Android `BucketControls` + `ui/BucketControls.kt`). Typed:
  switches, chips, delays in minutes (stored as seconds), whole-number limits, HH:MM quiet hours.
  Each detail is inherited until customized. Used for the account default (Settings), each
  bucket and each follow override.
- **Presets** still only rewrite their six fields; limits, sharing toggles, grants and unknown
  keys survive, and the Basic picker reads only those fields (Android now matches web).
- **Revoking the last grant** keeps `fronts: []` / `layers: []` on the follow so a bucket can't
  grant it back (follow override wins).
- **Presentation everywhere privacy is chosen**, icon + name: audience chips (posts, entries,
  replies, feeds, profile parts, fields, field defaults, relationships), the post card label,
  a member's announce buckets, follower assignments, the bucket list.
- **Android** gains: People → Sharing buckets (rows, editor sheet: name, icon grid, preset,
  Advanced, who's in it, delete with confirmation), bucket chips on each follower, Advanced on
  each follow override and on the default in Settings, a member's "Who hears when they front",
  and feed sharing through the audience chip (it used to drop bucket audiences on edit).

## Deliberately not here

- Bucket audiences on channel permissions, private chats, message `members`/`system_only`
  audiences, notification-kind preferences, read-as and stage filters, or lists: other
  semantics (Sol's audit). Group privacy and per-type creation defaults have no audience surface.
- Per-bucket front/layer grants (D-084): the fields merge as unions across buckets in core, but
  neither client edits them on a bucket yet; grants stay per follow.
- Android's delay, limit and minute fields save with a Save button; web saves each on change.

## Budget

The web JS bundle is 204,384 of 204,800 bytes gzipped after UI11c (SPEC §9, summed over all
chunks). It went 348 bytes over until `bucket-controls.ts` imported only the fixture fields it
uses (the validation cases stay out). The next web feature has to trim or move something first.
UI13 (2026-11-03) did: removing Chat's unreachable inline new-chat form left 204,679 bytes.

## Review focus

Re-plan keeps the original switch time and never reveals before the new delay; deleted or
unassigned buckets and stale bucket ids fail closed; presets keep independent controls; the
fixture is the single source of icons/fields for both clients.
