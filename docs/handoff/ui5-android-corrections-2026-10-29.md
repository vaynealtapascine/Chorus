# UI5 Android — parent correction pass

Implementation: GPT-6.1-sol, 2026-10-29. Existing checkout
`F:/DunBuild/chorus-ui-redesign`, `feat/figma-ui-redesign`, starting at clean `a44cc00`.
Claim `23b3cf7`; channel safety/compatibility batch `cd2f743`. This is implementation evidence
for the parent's independent review, not final visual acceptance. No subagents, push, merge,
production deployment, service/system changes, uninstall or app-data clearing.

Authority: owner's bounded correction request, D-079/D-088 and the existing specification.
Figma session `ed6fc8cd-2bd5-4a86-818e-34fc0504803f`, file `389A1Z2QPTRpZw6d79Cz7k`.
Read-only high-fidelity context/render checks: 3:776, 3:822, 3:1170, 2094:5065;
3:1210 reference render is retained from the first pass. No Figma edits.

## Findings and implementation

1. **Density and typography.** Reproduced inherited bodyLarge line boxes on metadata. Chat
   names now have 13/18 sp, metadata 12/16, body 15/20; header/list roles have explicit line
   heights too. Removed the redundant presence caption and reduced padding before font changes.
   Channel rows have a 70 dp minimum; small text does not shrink the 48 dp action targets.
   Journal rows use a shared avatar gutter, inline identity/time and 15/21 feed text. Shortened
   repeated metadata; reaction identity is disclosed in a contextual confirmation before writing,
   with the acting member in accessibility semantics. No private reaction identities are fetched.
   Existing groupStart/groupEnd remain. Native new messages exposed a second issue: the core
   supplies an ordinary single-author segment, but the old grouping guard rejected any segment.
   Same-author segments may now group; mixed-author segments and existing privacy/reply/time
   boundaries still break groups. A regression uses an actual core composer result.

2. **Creation and photos.** One labelled New chat FAB opens a native choice sheet with prominent
   New channel and explanatory copy; Direct message and New shared space retain existing paths
   behind their own choices. Empty folders have explicit creation actions. Channel-sheet copy is
   shorter and offline status remains meaningful. Platform ImageDecoder applies EXIF orientation
   before bounded square cropping; no dependency was added. See item 7 for compatibility gating.

3. **Focused chrome.** Phone conversation/info, journal write/profile/detail routes hide the
   global destination bar. It returns on pop to a root. The viewport/bar resize over the same
   short transition; the shell owns the system navigation inset once, and focused editors retain
   IME padding. Expanded layouts retain their rail. Debug-only inspection extras can override
   font scale and motion in composition, never device settings; release ignores those extras.

4. **Formatting.** A horizontally scrollable toolbar gives each action 48 dp. Bold, italic,
   strike, code, spoiler, links and quotes insert existing core markup around/at the selection;
   selection direction/caret are preserved and active IME composition is left intact. Link input
   validates its scheme. Bullets/numbering insert readable plain text (core has no list entity);
   no heading entity/action is invented. Core alone parses the submitted body. The editor remains
   a markup editor, not a new WYSIWYG library. Author, compact mode choice and audience remain
   visible with the keyboard; optional fields/poll stay under More. Pending photos/files use a compact horizontal preview strip; their details sheet retains alt text and spoiler edits without uploading on selection. Tests cover UTF-16/reversed
   selection, toggling, empty caret, composing IME and real core entities.

5. **Routes and state.** Channel/thread identity and hierarchy are part of the chat route.
   Outgoing panes keep their own channel/messages/parent/composer/scroll snapshot. Outgoing
   visual content cannot receive touch/accessibility actions; Back handlers belong to active
   routes, including info and thread. Read marks are gated by the active main destination and
   scroll effects by the active pane. External chat-open requests are consumed once, so returning to Chat cannot replay an old request over the restored selection; read marks wait until that request is applied. Q22’s Also send choice resets on channel/thread changes. Main destinations and scenes have SaveableStateHolders.
   Per-channel composer stores retain text, author, audience, reply, CW, files and poll options;
   reading positions are keyed by channel and reader, created on opening the conversation, not
   while merely displaying its folder. Journal files/options are saveable and scene state retains
   editor/detail scroll. Busy flags remain ephemeral so cancellation cannot restore a stuck busy
   draft. The Android client has no existing draft-op integration to reuse: this implements
   navigation/saved-state restoration, not cross-device draft syncing or force-stop durability.

6. **Journal detail/Friends/errors.** Detail header and supported reply/heart footer sit outside
   the scrolling post/replies. Root-card action duplication is removed. Friends uses the same
   presentation frame/type hierarchy using only its audience-filtered API fields; entities come
   from that response, never a private local member/post lookup. Reaction names remain reachable
   in the contextual action. Reply-load errors use readable connection/retry copy without a raw
   URL/exception. No unsupported bookmark/repost/share semantics were added.

7. **Privacy and concurrent edits.** RichBody handles text long-press directly, keeps link/spoiler
   taps separate, and exposes message/link/spoiler accessibility actions. Existing spoiler-mask
   safety tests remain. List/pin/reply/action previews also mask spoilers; forwarded snapshots now retain and render their core entities rather than exposing raw spoiler text. A regression covers both ordinary and forwarded previews. The channel sheet captures its opening baseline; an image-only save
   cannot overwrite another device's name/topic edits. Regression coverage checks that delta.
   Image-less create omits avatar_blob rather than sending null (schema-15 compatibility).
   Updated `/server` advertises `capabilities:["channel_images"]`; Android gates image selection
   and removal on that confirmation, cached per server for offline use. Unknown/old servers show
   a server-update explanation, while name/description operations remain available.

## Native evidence and verification

All artifacts are local in `F:/DunBuild/chorus-ui5-evidence/`, prefixed `correction-`.
The final external manifest `correction-manifest.json` identifies validated captures, the
committed-source APKs/hashes and gate logs. The review APK is `chorus-ui5-correction.apk`;
`chorus-ui5-correction-release.apk` is the release-variant companion. Both are rebuilt after
the correction commit, using the existing signing configuration. Nothing is published.
The emulator is the existing Chorus_A56 (`emulator-5554`), fictional demo at port 5265/schema 16.
All mutation checks use that demo; no production messages/posts were sent. Physical Samsung
`RRCY500455P` was absent from ADB during this pass; a reconnect request was sent while work
continued. Existing phone-private evidence stays local and is not claimed as fresh evidence.

The quick Android gate and final full release-mode Android gate passed. The latter is
`python scripts/verify.py --android --offline` with process-only `RUST_TEST_THREADS=1`,
`CARGO_BUILD_JOBS=2`, JBR and the F: build/cache paths. It ran every existing release test and
simulator/property seed; the harness scheduling is serial, not the tests’ internal concurrency.
Results: Rust **395 passed**, app **165 passed**, bridge **7 passed**, web **109 passed / 1
existing skip**, lint, builds and bundle budgets passed. `correction-full-android-final.log` is
the passing log. The first full attempt (`correction-full-android.log`) hit Windows socket
allocation error 10055 in the existing structured-visibility blob test. The isolated parallel
blob suite then passed **9/9** (`correction-blobs-recheck.log`), followed by the entire passing
serial-harness gate. No system limits or tests were changed to bypass the failure.
The local ADB helper now uses unique hierarchy paths and rejects failed dumps: the old fixed
path could return a stale UI tree. Long ADB key-event bursts also produced incomplete fixture text
under host load; the correction helper uses 60-character chunks and the editor XML was asserted
to equal all 507 fixture characters before Post. The saved private post and core entities were
then checked on the isolated demo. Native captures must be inspected, not inferred from a build.

Automatic approval review rejected the attempt to restart the isolated demo server with the
updated binary (`blocked by policy`). The running demo remains untouched. Its old binary lacks
the new capability flag, so image controls deliberately show the update gate; the existing image
channel still renders. Fresh photo-orientation/picker mutation checks require the updated demo
server and are not claimed. No production/server upgrade is performed by this pass.

Validated capture set (all under the evidence directory):

- `correction-chat-list-final.png`, `correction-image-channel-list-final.png`,
  `correction-creation-sheet-final.png`, `correction-channel-server-update.png`.
- `correction-chat-mixed-pin-reaction-final.png`, `correction-chat-ime-final.png`,
  `correction-info-final.png`, `correction-thread-final.png`.
- `correction-journal-feed-populated-final.png`, `correction-journal-editor-final.png`,
  `correction-journal-editor-thumbnail-final.png`, `correction-journal-editor-ime-final.png`,
  `correction-journal-detail-top-final.png`, `correction-journal-detail-scrolled-final.png`,
  `correction-friends-final.png`.
- `correction-chat-list-large-text.png`, `correction-chat-large-text.png`,
  `correction-editor-large-text-reduced-motion.png`: debug-only composition overrides at 150%
  font scale / disabled spatial motion. System font scale was read as 1.0; no system setting changed.
  Labels ellipsize explicitly and keep full accessibility names. Root/deep controls remain usable.
- `correction-navigation-final.mp4`, `correction-navigation-frames-final.png` and timestamped
  `correction-motion-events.json`: normal list/conversation/info and parent/thread transitions.
  The outgoing parent is visible before the thread enters. Two Back events 80 ms apart returned
  info → conversation → list and thread → parent → list (validated XML retained).
- `correction-comparison-chat-list.png`, `correction-comparison-conversation.png`,
  `correction-comparison-journal-feed.png`: Figma/native at equal width, native OS bars cropped,
  aspect ratios preserved. Native chat header/presence/folders is about 186 dp, rows about 70 dp.

Navigation checks retained typed chat text and journal title/body/attachment/audience/two poll
options when switching destinations and cancelling/resuming. Journal editor/detail scroll also
survived scene changes. `correction-chat-draft-restored.xml` and
`correction-journal-draft-restored.xml` retain those observed states. Detail → reply → Cancel
returned to the same reading position. Long-press on real body text opened actions; spoiler tap
revealed without opening actions; link tap launched the browser. TalkBack action semantics are
present and native hierarchy was inspected; the screen reader itself was not enabled.

Remaining limits for independent review:

- No fresh physical-phone evidence: Samsung stayed absent from ADB. The bounded helper was
  inspected and remained stopped; stop sentinel is set at handback. Existing private phone images
  remain local, and are not presented as this pass’s evidence.
- No tablet AVD or hardware frame-time guarantee. Motion was recorded on the existing phone-sized
  emulator under host build load. App-only large-text/reduced-motion overrides were exercised;
  no global accessibility/animation setting was changed.
- Fresh channel-image picker/EXIF mutation checks remain gated by the running demo’s absent
  capability flag and the rejected restart. Platform decoder and field/capability regressions are
  covered; the existing representative image still renders in the fresh channel-list capture.
- The selection toolbar edits core markup; it is not a WYSIWYG replacement. Lists are plain text,
  unsupported heading/repost/bookmark/share actions remain absent. Draft saving is navigation /
  Android saved-state restoration, not cross-device syncing or force-stop persistence.
- The parent owns final visual acceptance. Compilation is not that acceptance.
