# UI5 independent parent review

GPT-6-astra, 2026-10-29. Reviewed implementation through `a1869cf` on
`feat/figma-ui-redesign`, in `F:/DunBuild/chorus-ui-redesign`.

## Verdict

Accepted for the owner's requested "within striking distance" visual/polish scope on the
phone layout. This includes source/recorded native-evidence review and a final Samsung smoke
check; it does not claim exact pixel parity, measured Telegram performance parity, or release/deployment approval.
Sol-6.1 wrote and inspected the implementation; the parent reviewed its first pass, supplied seven
concrete correction areas, and independently reviewed the resulting changes and captures.

## Evidence reviewed

Artifacts are under `F:/DunBuild/chorus-ui5-evidence/`; provenance, APK hashes, commands and
limits are in `correction-manifest.json` and the implementation
[correction report](ui5-android-corrections-2026-10-29.md).

- Equal-width Figma/native comparisons for chat list, populated conversation and journal feed.
  Header density and message grouping now approach the reference; the journal has a coherent
  avatar gutter and compact identity/content/actions hierarchy. Dynamic fixture content differs.
- Native creation sheet, editor with IME, long-entry detail, 150% text captures and motion
  contact sheet covering list, conversation, info and thread. The New chat action exposes a
  prominent New channel choice. Focused screens omit global destination chrome; reading chrome
  stays fixed. Formatting controls and audience remain reachable above the keyboard.
- Source review of channel baseline/delta handling and capability gating, bounded platform image
  decoding, formatting selection edits, per-channel drafts/reading positions, destination saved
  state, route snapshots/Back ownership, inactive-pane interaction suppression, spoiler masking
  and body gesture/accessibility actions.
- Native-check results and restored-state evidence supplied by Sol. Full final gate log reports
  395 Rust tests, 172 Android JVM tests, 109 web tests with one existing skip, lint and budgets
  passing. A transient Windows socket allocation failure was separately rechecked and the full
  suite passed with serial harness scheduling; no test was skipped to conceal the failure.

The seven parent findings have substantive resolutions. No further blocking defect was identified
in this bounded review. Metadata density, channel discovery, focused chrome, formatting, route
identity/state retention, journal reading/Friends presentation and concurrent channel edits are
all materially improved. Supported actions remain real; delivery receipts, voice, bookmarks and
reposts are not fabricated to imitate the reference.

## Explicit limits and next checks

- Correction APK `chorus-ui5-correction.apk` is version 0.1.561, SHA-256
  `b9559d19bd1e6e4e0a645c6736a20081d24ad3ca8f731dbc18fb74685943601c`.
  The Samsung reconnected during parent review. Parent installed this exact hash with
  `adb -s RRCY500455P install -r` and verified installed versionCode 561 / versionName 0.1.561.
  No uninstall or data clearing occurred.
- Automatic approval review rejected restarting the isolated demo server with the updated binary
  with the stated reason "blocked by policy". Do not bypass that rejection. Consequently fresh
  picker/EXIF mutation checks remain unverified. The first pass exercised create/change/remove,
  offline upload and restart persistence; the correction pass verified existing images render and
  the old-server gate. These are distinct claims.
- Channel-image editing needs the branch's server/schema 16 and advertised `channel_images`
  capability. Older servers retain image-less channel creation and metadata editing; image
  controls explain the required update. No production server upgrade was performed.
- No tablet, live TalkBack or hardware frame-time acceptance. Large text and reduced spatial motion
  used debug-only composition overrides, not global device settings. The formatting toolbar edits
  core markup, not WYSIWYG; draft retention covers navigation/Android saved state, not force-stop
  durability or cross-device draft sync.

This review changes documentation only. The repository-required quick verification is run before
the review commit; the full Android gate already passed for the unchanged implementation.

## Final physical-device check

After the owner reconnected/unlocked SM-A566B, parent inspected the installed correction build:
chat list, labelled creation sheet, new-channel editor/server-update gate, existing conversation,
Samsung keyboard/composer insets, conversation info, Back to list, Journal root, Post/Journal entry
choice, author/audience/formatting controls with the keyboard, and Cancel/Back to Journal.
Screens were readable with no overlapping controls in these exercised states; focused screens
hid destination navigation and roots restored it. No messages, posts, reactions, channels or
images were submitted to the owner's account. Opening chat may use normal read tracking.
Populated journal-detail, formatting mutations and draft restoration retain the emulator evidence;
no synthetic content was added to the owner's journal for testing.

Phone captures are local only at `F:/DunBuild/chorus-ui5-evidence/parent-phone-*-private.png`.
They contain owner-account UI and must not be committed/published. The keep-awake helper used the
observed status-bar location during this check and was stopped via its sentinel at handback.
No global device setting was changed. The earlier child report's absent-phone limit is superseded
only for the physical flows listed here; its tablet/TalkBack/hardware-timing and image-mutation
limits still apply.

Pre-existing out-of-scope observation: Fronting's generic header icons were dark against the
dark background on the phone. Chat and Journal use their correctly tinted scoped controls; the
Fronting icon issue was not changed as part of this task.
