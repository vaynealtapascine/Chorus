# UI21 web parity plan (after Android waves 1-7)

Owner order 2026-11-09: web parity for waves 1-7 comes before Android wave 8 (`ui21-plan.md`). This plan is
for a cold agent: do it slice by slice (section 5), each shippable alone. Android is the reference for
behaviour (names of Android files are given so you can read them); the Figma cache for looks; the four
audits (`ui21-*.md`, each has a *Web* section and a *Text to trim* section that applies to web copy too).

## 0. Rules and facts

- Same rules as `ui21-plan.md`: no new explanatory copy, subtitles only when they carry data, fewer taps,
  Basic vs Advanced (DESIGN §6), `python scripts/verify.py --changed` before each commit (web stage = `npm run check`
  + `npm test`), commit per slice, author Vayne Altapascine, co-author line. Deploy with `scripts/deploy-linux.ps1`.
  Browser flows: `bash scripts/e2e-web.sh` (see `web/e2e/README.md`); check UI in headless Chrome on a private demo
  copy (see PROGRESS log, `F:/DunBuild/ui21-home-demo`, port 5301), never on the real server.
- Web frames exist at 1280x800 only for: F0.7, F0.8, F2.1-F2.4/F2.6/F2.7 (Home PC), F4.6, F5.11, F5.12, F5.21,
  F6.13, F9.2-F9.4, F10.6, F10.8, F11.5. Every other screen has a 390x844 frame only: build it responsive (same
  content at narrow width; on wide screens a centred 560-720 px column or, for drawers, a centred dialog as
  `ui/Sheet.svelte` already does). F10.8's XML is the F0.7 text (bad crop): treat "install Chorus" as a small
  card/banner, not a screen.
- Frames: `F:/DunBuild/figma-cache/frames/<ID>_*.png|xml` (the XML has the exact copy).
- Data rules: per-account state stays in account prefs (synced), per-device state in `localStorage` (as
  `appearance.svelte.ts` does for the theme). Android's pure models are the spec to port: `data/HomeDashboard.kt`,
  `data/FrontingDisplay.kt` (web twin `lib/fronting-display.ts`), `data/FrontReviews.kt`, `data/Rings.kt`,
  `data/ChatForward.kt`. Port each with its tests; if two clients need the same non-trivial logic, prefer moving it
  to `chorus-core` with a fixture (AGENTS.md) over a third copy.
- Not on web by nature: widgets (F3.8), lock-screen chips, QR scanning (a browser can show a QR but scanning
  needs `BarcodeDetector`; optional), Android notification channels, camera tile in attach.
- Out of scope (Android wave 8, then both): ticks and read receipts (D-131), handover and Notes to ourselves
  (D-132, F5.15/F5.19, the Home "Handover" card stays hidden like Android's `UNBUILT`), full shared-space
  permissions (D-133), lock-screen front notification (D-137), member states shareable (D-138), custom emoji
  changes beyond what web has.
- Web is **ahead** of Android in places; do not regress them: desktop three-pane chat (F5.21), custom emoji,
  chat Pinned list, feed live preview, Insights data tables, Friends F7 screens (UI20), Notifications page (F11.5
  / D-127, done), polls closing time + Nudge (D-136/D-141/D-143, done), headings H1/H2 rendering (D-134, done),
  hold-to-act-as-several (D-142, done), forward hides the source chat name in another space (4164a53, done).

## 1. Gaps per wave

Size: S under half a day, M about a day, L multi-day. "Web now" cites what exists in `web/src/lib/ui/` unless noted.

### W1 Sweep (Android waves 1: trims, small tap cuts, banner/toast)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| Connection banner F2.5 / F10.3 (`ui/ConnectionBanner.kt`) | only a dot + "Offline · saved on this device" in the app bar (`App.svelte:100-103, 201`; `sync.status` is `offline/connecting/live/no-device`) | Full-width strip under the app bar on every route while not live: offline (warn tint, "Offline. Changes wait on this device."), asleep (coral, Retry = make `SyncClient.reconnectNow()` (`sync/client.ts:697`, private) public; offline vs asleep = `navigator.onLine` plus repeated connect failures), "Away from home" wording only on a Chorus Home install (`homeStatus()` non-null / device has pin). Remove the app-bar label (keep the dot on the sidebar identity). Remove per-screen "Offline" strings. | S |
| Slow-down toast F10.7 | `http.ts` parses `Retry-After` and retries reads, but nothing shows | Toast pill "Catching its breath. Try again in N seconds." with countdown, fed by `http.ts` 429 and the socket `rate_limited` frame in `sync/client.ts`; host in `App.svelte` beside `UndoToast`. | S |
| Text trims (`*Text to trim*` in all four audits) | same strings on web (e.g. `Home.svelte:201` repeats the long Simple-view hint; `Fronts`, `Friends`, `Notifications`, `Onboarding` leads, `Settings`) | Grep each quoted audit string in `web/src` and delete or shorten as Android did (see `git show 4643aba 5877c61 ef5b4df 2dba4b5` for the exact copy decisions). Keep copy that is data. | M |
| Icon-led empty states (`EmptyState` in `Drawers.kt`) | 15 hand-written `<p class="empty">` plus `.fr-empty` | One `EmptyState` component (section 3), replace all. | S |
| Small tap cuts (audit section 4 of each) | e.g. "+" on fronting opens Fronts list; Needs-you rows without time | Do those listed per area; note ones that only apply to touch. | S-M |

### W2 Home (F0.1-F0.8, D-130)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| New Home destination, app opens on it (`HomeScreen.kt`, `HomeDashboard.kt`, `HomeSections.kt`) | router `home` **is** Fronting (`Home.svelte`, 405 lines; `routeTitle` "Fronting"); person's `home` is "Hi, name / Friends right now" | New route `home` = dashboard (`ui/HomeDashboard.svelte`); Fronting moves to route `fronting` (`#/fronting`) with the existing `Home.svelte` renamed `Fronting.svelte`; update `router.svelte.ts` `parse`, `active` and `routeTitle` in `App.svelte`, deep links and e2e that assume `#/` is Fronting; sidebar: Home first (F0.7 nav is Home, Notifications, Chat, Journal, Members, Friends, Settings, with the identity block and Switch button); Back from a top-level route returns to Home. | M |
| F0.1/F0.7 system Home: Fronting now card (presence avatars solid/dashed/plain, "since 15:12 · 2h 15m", **Switch**, **Log a past one**) | Fronting screen has the card, switcher (`Switcher.svelte`) and `LogPastSwitch.svelte` | Compact card reusing `FrontCard.svelte` + `fronting-display.ts` presence; Switch opens `Switcher`, Log opens `LogPastSwitch`. | M |
| Quick switch row (40 px faces + names, "Everyone", "Edit"; one tap switches per `Front.quick`) | none | Port `HomeDashboard.quickSwitch` (own members + pins + recents, limit by width); tap = same op as Switcher quick mode; "Edit" opens Switcher. | M |
| Handover card | none | Hidden (D-132 unbuilt); keep the section id in prefs so the list matches Android's seven ids. | S |
| Waiting for you (unread chats, "Chat"/"Open Chat" link) | `chat-list.ts` already computes unread rows | Port `HomeDashboard.waiting` over `chat-list.ts` rows (unread, not muted, not archived, newest first, cap 3-4); rows open the chat. | S |
| Friends right now strip (164 px cards: Around / NotYet "You'll see who's around after their next switch" / Posted / Quiet / NotSharing "Doesn't share fronting") | `friend-fronts.svelte.ts`, `friends-state.svelte.ts`; the old person Home lists them | Port `card()`/`friends()` (card kind from `FriendRow`), horizontal strip, "All friends" link to `#/people`. | M |
| Today timeline (own switches from named history, own posts, closed polls since midnight; time column, icon disc on a rule, "History" link) | `front-history.ts`, `polls.ts`, posts in `data.ts` | Port `HomeDashboard.today` + `switchLine`; icons swap/journal/poll. | M |
| New from friends (post rows, "Journal" link) | `friends-feed.ts` | Compact `FriendPostCard` row (avatar, name @handle, body, reply/repost counts). | S |
| F0.3 Sections drawer (7 rows with grip + switch, Reset + Done; per-reader `home.sections:<member>`, fallback `home.sections`) | none | `HomeSections.svelte` in `Sheet` with drag handles (use the shared drag list, section 3; `ChatFolders.svelte` already reorders); same pref keys and JSON as `HomeDashboard.payload` so the phone and the browser share them; "Sections" pill in the title row (F0.7) and "Edit" on Quick switch. | M |
| F0.4/F0.8 person Home: composer row ("Write something, Ren...", image + poll icons), Friends right now (systems she follows), Waiting, New from friends; no fronting | `Home.svelte` person branch | Same component with `person` branch; composer row opens `PostComposer` (as Journal's `externalNewEntry`). | S |
| F0.5 caught up, F0.6 first day (Welcome card with **Find someone** / **Share @handle**, empty Friends + Waiting lines, Try rows "Write your first post", "Fill in your profile"; gone once there is a post, a follow and a profile field) | none | Port `HomeDashboard.firstDay`; Find someone opens the follow sheet (`FollowBackSheet.svelte`/`People.svelte` add flow); Share uses `navigator.share` else copies the handle + host. | S |
| F0.7 desktop layout (three columns: fronting + quick switch + handover, Waiting + search box "Search chats, posts and people", right rail friends / Today / New from friends) | n/a | CSS grid at >= 1100 px; one column below. Search box = the Search route's input (shared with the Journal search, W6). | S |

### W3 Members (F4.1-F4.6)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| F4.1 directory: title row, underline tabs Members / Groups / Custom fronts, counted chips (All, Fronting, Archived), flat rows with layer pill, filter drawer (Folders/List, Select, Customize, Reorder, Import) (`Members.kt`, `MembersCustomize.kt`) | `Members.svelte` (460): chips + "Groups…" toggle + "Import from PluralKit…" button (`:177-183`); no tabs, no counts, no layer pill | `UnderlineTabs` (section 3), counted chips, row restyle, move Customize/Reorder/Select/Import behind one filter icon opening a menu sheet; PK import already opens a panel (`PkImport.svelte`), restyle into a `Sheet` (F9 audit "PkImportSheet" row: export file row, three stat chips, notes, Import button). Reuse `member-list.ts` (D-100). | M |
| F4.4 Groups tab: expandable group cards with member rows, add/remove members of **any** group, New group drawer (`MemberGroups.kt`) | groups are a collapsible panel + filter chips (`Members.svelte:141-183, 242`); check that plain groups can be filled (audit asks) | Groups tab with expandable cards; add/remove for every group; New group in a `Sheet`; Custom fronts tab lists custom fronts (from `fronts.ts`, with link to Fronts). | M |
| F4.2 Edit member (Figma top bar + Save bar, swatches inline, Name / Pronouns / Display / Description, More rows: Groups, Who can see, Custom fields, Handles, Banner, Archive) | `MemberEditor.svelte` (532): one long form with an audience chip per part | Split into top fields + a "More" value-row list; Advanced rows kept (sigils, Roles, Speaking, Chat notifications, Members list); each row opens a `Sheet` or sub-page. Keep D-101 per-part audiences reachable from Who can see. | L |
| F4.3 Who can see (privacy page) | none (per-part chips only, D-101) | New page `#/members/<id>/who-can-see`: the account's buckets as radio rows (`BucketLabel`/`bucket-controls.ts`) and one line per profile part. Read `MemberAudience.kt`/`profile-audience.ts`. | M |
| F4.5 Archive confirm | ghost buttons in the editor (`MemberEditor.svelte:366-369`), no confirm | `ConfirmSheet` ("Archive Nairon?") using the section 3 component. | S |
| F4.6 Custom fields page (web frame): list with reorder, type segmented (Text / Date / Number / Link / List), default audience, "Show age, not the date", Retire | fields managed inline in `ProfileFields.svelte` (453) | New page reached from the Members tab bar and the editor row; fields list with drag, editor in a `Sheet` per F4.6 XML; inline field headers shrink to values only. Check Android's `ProfileFieldRows.kt` / audit "Custom fields screen" for the data path (field defs are the existing `field.*` ops). | L |
| UI22 banners two-thirds width | done (c3a0acd) | none | - |

### W4 Chat (F5.x; audit `ui21-chat.md` slices 0-9)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| Sweep: chat menu cleanup, folder-tab pencil gone, FAB, `#` before channel names, header tap opens info | menu items still present; see audit section 3/4 | Apply audit slice 0 rows that exist on web. | S |
| F5.18 New message + F5.10 New shared space (list page, two-tap DM, folder-aware FAB, system chips + search) | `NewChat.svelte` (245): choose/direct/group stepper | Rewrite as a page/sheet: search box, people list (friends + own members), chips for shared-space picks; keep D-098/D-099 rules. Read `ChatNewMessage.kt`. | M-L |
| F5.4/F5.16 Aside pill + "Everyone here" audience bar; dashed composer + dashed bubbles in shared spaces | audience is a `<select>` (`Chat.svelte:~1486`), no dashed bubbles | One pill in the composer row, bar above it, dashed style on aside bubbles (`Message.svelte` class). Read `Chat.kt` aside parts + `ChatPresentation.kt`. | M |
| F5.6 message actions cut to eight + More, one date line; F5.13 mention list (`@everyone`, role text) | ⋯ / right-click menu (`Message.svelte:416-430`, D-120) with more rows ("Select for bundle", "Reply in another channel"); mentions via `mentionChoices` | Reorder to Android's eight rows, move the rest under More; keep right-click/long-press. Mention popup gains `@everyone` and a role line. Keep D-142 hold-to-act-as-several. | S-M |
| F5.20 Forward / Reply in grid with search, comment, **Show who originally wrote it** toggle, inline warning (`ChatForwardSheet.kt`, `ChatForward.kt`) | `<select>` in a bar (`Chat.svelte:~1426`) + `confirm()` for outward shares; no comment, no author toggle | `ForwardSheet.svelte`: grid of chats with search, comment field (becomes the forward message `text`), author toggle (empty `authors` in the snapshot, as Android), inline warning replacing `confirm()` (`sharingOutward`). Reply in… and Quote bundle reuse it. Fixes the "Forwarded from" bug (section 2). | M |
| F5.8 Attach sheet: recent photos, files, Journal tile, caption row, eye (spoiler) toggle, send in sheet (`ChatAttach.kt`) | plain file input + drop/paste (`Chat.svelte:541-555`) | Attach button opens a `Sheet` with Photos/Files/Poll/Journal tiles and a caption row; keep drag-drop and paste. (No recent-photo grid on web: tile opens the file picker.) | M |
| F5.9 Search: own top bar, tabs Chats / Media / Files / Links, filter chips, tappable cards (`ContentSearch.kt`) | `Search.svelte` (189): "Search" + "Close search", scope select | Tabs + chips over the existing scoped search (D-114); keep Saved and Posts scopes. | M-L |
| F5.26 Chat info: Pinned and Folder tiles, Polls tab, per-member unread, sticky tabs (`ChatDetails.kt`) | room-info with Pinned (web is the model), Members by layer with unread, Media/Files/Links; no Polls tab, no Folder tile | Add Polls tab (`polls.ts`) and a Folder tile that opens chat-folder assignment (`chat-folders.ts`). | S-M |
| F5.25 Folders screen visual (grip, chevrons, folder editor in form style) | `ChatFolders.svelte` (173) works, visual unchecked | Restyle to the form card kit; no behaviour change. | S |
| F5.1/F5.22/F5.23 chat rows: lock for private chats, "• Garden" suffix, icon-led empty states | `ChatList.svelte` (426) | Check each row of audit F5.1; add lock icon for private chats (cb9a30a). | S |
| F5.17 offline send and retry | "Not sent" rows with Copy/Dismiss (`Chat.svelte:~1438`), no clock, no retry | Needs core `retry_issue`/pending ids (Android used FFI `pending_ids`, `retry_issue`); expose the same from the wasm build, then clock on pending bubbles and a Retry button. Ticks wait for D-131 (wave 8). | M-L |
| F5.2/F5.5/F5.7 unread divider "12 unread for Amarynthe", reading-as banner, `@` jump button | generic "Unread messages" (`Chat.svelte:~1367`) | Divider text with count + reader; `@` jump chip when mentions are unread. | S |
| F5.3 "Send message as…" picker | speaker chips (D-103) | Keep chips; add the sheet only if the owner asks (Android-only frame). | - |
| F5.11 shared-space permissions (desktop frame), F5.15, F5.19 | six-permission model in `ChannelPermissions.svelte` | Wave 8 (D-132/D-133). Skip. | - |
| F5.12 custom emoji, F5.21 three-pane | exist | none (web is the reference) | - |

### W5 More / Settings / Data / Getting started (F1, F2, F9, F10)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| F10.1 More in two sections ("Go to", "Settings") with one-line status per row (`AppNavigation.kt`) | `More.svelte` (39 lines): plain card list; also the narrow-screen nav | Two sections with dynamic subtitles ("4 members, groups and custom fronts", "Dark · teal · comfortable", ...). On wide screens More is replaced by the Settings sub-nav below. | S |
| Settings sub-nav (F9.2-F9.4, F10.6 desktop frames): left list You (Account, Appearance, Sharing, Notifications) / Data (Your data, API tokens, Webhooks, Trash) / This computer (Devices, This computer, Server health, Updates), one page each | `Settings.svelte` (191) is one page; `DataPage.svelte` (340) stacks exports, tokens, webhooks, server health; `Trash.svelte`, `ThisComputer.svelte`, `ThisDevice.svelte`, `LinkDevice.svelte` are separate routes | `SettingsShell.svelte` (left nav at >= 900 px, list page on narrow) with routes `#/settings/<page>`; split `DataPage` into `YourData`, `ApiTokens`, `Webhooks`, `ServerHealth` (content exists, only structure changes); fold Trash, Devices (`LinkDevice`), This device (`ThisDevice`) and This computer into the nav. Old `#/data`, `#/trash`, `#/computer` keep working (redirect). | L |
| F10.2 Appearance: live preview (two bubbles), theme segmented, **accent** (Teal, Sunflower, Lime, Lavender, Sky), member colours in chat, text size, density, Advanced row (`AppearanceScreen.kt`, `d8aeba3`) | theme `<select>` only (`appearance.svelte.ts`, `localStorage`) | `design/tokens.json` already holds the five accents (comment says "Android"); extend `scripts/gen-tokens.mjs` to emit `:root[data-accent="sunflower"]` etc. into `web/src/lib/design/tokens.css` (per mode); `appearance.svelte.ts` gains `accent`, `density`, `textSize`, `memberColours` (device-local, same storage pattern), applied as `data-*` attributes + CSS vars; preview card. | M |
| Notification settings page (no frame), Privacy and sharing page, Advanced subpage | `Settings.svelte` inline blocks: free-text quiet hours, preset `<select>`, `CeilingControls` | Pages per audit section 2 rows: value rows, time inputs for quiet hours, presets as the radio rows of the accept sheet, `CeilingControls` behind an Advanced row. | M |
| System settings page (name, handle, words, connection) | handle editable in the account/profile? none as a page | New page `System`: name, handle (D-126), words (below), server address read-only (pinned-home note). Read `SettingsScreen`'s system rows (`4ad7f79`). | M |
| F9.5 Trash (tabs All / Messages / Posts / Members, search, per-row Restore, "Deleted by Nairon · 2 days ago", This week / Earlier groups) | `Trash.svelte` (77): search + type filters, no "Deleted by" | Core already stores `deleted_by_member_id` (ca0c737): read it; tabs + groups + Restore pill; moves into the Settings nav. | M |
| F9.1 Insights: Week / Month / Year segmented, three stat tiles, Patterns rows, stacked columns | `Insights.svelte` (203): range segmented, horizontal bars, per-chart CSV + View data | Add tiles and Patterns (port the Android computation, `InsightsScreen.kt`, `insights.ts` is the shared base), vertical stacked columns, one View data + Download CSV; keep the three extra charts (they are ahead). | M |
| F9.6 On this device (usage bar 3 segments, Clear downloaded files, Last synced) | `ThisDevice.svelte` (86): keep-everything toggle, fill files, space used | Usage bar from `sync/keep.ts` `spaceUsed` split (messages/posts vs blobs); "Clear downloaded files" = evict the blob cache entries (`sync/blobs.ts`), keep rows. | S-M |
| F10.4 Sync issues page (human title, red reason, quoted text, Retry / Copy text / Discard, Fixed list) | issues only in the chat (`Chat.svelte:781, 1449`: Copy, Dismiss) | Page reachable from Your data and the banner; needs `retry_issue` (same wasm export as F5.17) for Retry and Fixed. Ship Copy/Discard first. | M |
| F10.5 Update drawer (What's new, Later / Install) | none; `main.ts:26` registers `/sw.js` (built by `plugins/service-worker`), no waiting-worker UI | When a new service worker is waiting, a `Sheet` "Chorus is ready" with What's new (read `android/whats-new.txt` style file; add `web/public/whats-new.txt` written by the deploy script) and Install = `skipWaiting` + reload; Later keeps the waiting worker. | S-M |
| F10.8 Install Chorus | no `beforeinstallprompt` handling | Capture the event, show an Install card on Home/More (not in standalone mode, dismissable); iOS: one line "Share > Add to Home Screen". | S |
| F10.6 Server health (stat tiles Last backup / Errors / Devices; Restore in progress list "3 of 5 devices back") | stacked card in `DataPage.svelte:34, 83-86, 196` (data already read) | Lay out as the frame in the Settings nav (`ServerHealth`). | S |
| F9.2-F9.4 exports, tokens, webhooks | content in `DataPage.svelte`; `ExportBundle.svelte` | See sub-nav; Exports page per F9.2 XML (Change log JSON lines / Tables CSV / Database copy SQLite / Full export with progress + Cancel). | M (in the sub-nav row) |
| F1.1/F1.5 one-screen welcome: invite code, system name / your name, handle, this device, "I am plural" toggle, Continue, Link device; blobs + CHORUS wordmark | `Onboarding.svelte` (238): heading "Welcome to Chorus", multi-step, two big kind radios | Single screen on a teal field with blobs and the wordmark (section 4); validation ticks; "I am plural" switch; "Link device" shows this browser's link code or the QR scan input. | M |
| F1.2-F1.4 setup after joining (words, who's in it, who's steering) | none; web has no `terminology` anywhere | Setup steps after account creation (system only). Words: `system.terminology.system` / `.front` (DATA_MODEL §4.3, one word per row, as Android `517d3c5`); add a small `words.ts` and use it only where Android does (setup titles, Home "Fronting now", More's Fronting row, System settings). Who's in it: member grid + Import from PluralKit. Steering: first switch. | L |
| F1.6 Link a device (QR + Copy link + devices list + sign out) | `LinkDevice.svelte` (158) has QR and devices | Restyle into a page with form-card rows (initial discs, Last seen, Sign out); Copy link if missing. | S |
| F2.1-F2.4, F2.6, F2.7 Home PC frames | `ThisComputer.svelte` (151), `Onboarding` homeSetup | Compare each frame once; restyle with the kit. Lowest priority. | S-M |

### W6 Journal (F6.x; audit `ui21-members-journal.md` slices 3-7)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| F6.2/F6.11 composer: one-tap image/camera/poll/emoji/CW/# icons, two-row format toolbar incl. **H1/H2** (D-134), floating selection toolbar, Formatting drawer, "Saved" status, Drafts list (`JournalComposer.kt`, `JournalFormatToolbar.kt`, `EditorFormatting.kt`) | `PostComposer.svelte` (284): no toolbar; poll is a checkbox; headings **render** (`RichText.svelte`) and parse (`parseMarkup(..., {headings: true})`) but there is no H button; `journal-draft.ts` keeps one draft | Icon row, toolbar (bold, italic, code, heading, list, quote, spoiler) acting on the textarea selection, floating toolbar on selection, Formatting `Sheet`, poll button opening the poll fields (with closing time, already in `journal-draft.ts`), "Saved" status, drafts list (extend `journal-draft.ts` to many drafts). | L |
| F6.1/F6.3/F6.4/F6.12 reading polish: underline tabs, # header icon, thread join line, audience pill and ⋮ on the post screen, Message pill on profiles, long-read chips, quote rule, hanging-indent lists, sticky action bar | `Journal.svelte` (149) nav row; `Profile.svelte` tabs Posts / Replies / Highlights / Relationships (no Journal / Media / About, no Message button); `PostThread.svelte` (267) | Apply per audit slice 5; `RichText.svelte` already handles headings, add the quote rule and list indents. | M |
| F6.13 desktop journal: search, "Trending in our system" (tags), "Your feeds" (pinned), Formatting card (web frame) | `Journal.svelte` right column of static shortcut cards | Right rail per the frame: tag counts from `search.ts`/posts, pinned feeds, formatting hint. | M |
| F6.7/F6.8 Feeds: pinned feeds as Journal tabs, Feeds screen sections (Pinned / Shared by / Made by you), rule-builder editor over the same query string, live preview | `JournalFeeds.svelte` (197): free-text query box + preview; no pinning | Pin pref (same pref as Android: read `JournalFeeds.kt`/`FeedEditor.kt`), tabs from pins, cards, rule rows that read and write `feeds.ts` query syntax (round trip test). | M-L |
| F6.5/F6.6 Relationships (Inside / Friends tabs, Message/View, add drawer) and Lists index (faces, Audience / Feed pills) | inline tab in `Profile.svelte`; `JournalLists.svelte` (76) | New relationships page from `ProfileRelationships.svelte`; Lists index page. | M |
| F6.9/F6.10 + D-135 System board (Posts and Polls tabs), poll card with percent bars, voting-as chip, "N of M voted", Votes list, poll detail (`SystemBoard.kt`, `PollCard.kt`) | `PollCard.svelte` mirrors Android's old card (counts, "Choose one", "Remove my vote"); closing time and Nudge exist; no board or detail | Restyle `PollCard`, add detail with Votes list and voting-as chip (D-142 sheet exists), board route `#/journal/board` (tabs Posts | Polls) listing the system board per D-135 (read `docs/POLLS.md`, `32f2350`). | M-L |
| D-136 closing time and Nudge | done (995778f, fb4c2c8) | none | - |

### W7 Fronting, Friends, Stage, Notifications (audit `ui21-fronting-friends-stage-notifs.md`)

| Android feature / frame | Web now | Build | Size |
| --- | --- | --- | --- |
| Slice 1 sweep: trims, member ⋮ menu (Set state / Place / Remove), Needs-you rows show their time, "+" opens New front | `Fronting`/`Notifications` copy; member rows open `PlaceSheet` | Same cuts (done as W1 for copy); member row menu as a `MenuSheet`. | S |
| F3.10 Member state page: multi-select chips (6 built-in + system's custom states), note, clear-on-exit, "Only us", chips on Fronting rows (`MemberStatePage.kt`, `FrontingDisplay.kt`) | `PlaceSheet.svelte:100` single chip state; writes `member_status:<id> = {text, at}` | New `MemberState.svelte` page/sheet; extend `fronting-display.ts` to the `{tags, note, at, clear_on_leave, text}` shape (docs/FRONTS.md line 232; keep writing `text` for older readers). **Compat hazard now**: web's `{text, at}` write wipes tags an Android phone set, so port the parser first (also a bug fix). | M |
| F3.6 Review page (radio list, Later / Save to history), opened from Notifications, Fronting and History; Notifications row says "Phone says X, laptop says Y" (`FrontReviewPage.kt`, `FrontReviews.kt`) | `Reviews.svelte` "Keep both" buttons inline in History; Notifications "Review" button goes to `#/history` | Page `#/review/<id>`, same data (`front-reviews.ts`); update Notifications row copy. | M |
| F7.13 per-kind notification toggles on this device (status card, six toggles, Send a test, Troubleshoot) (`Rings.kt`) | `Settings.svelte:121-165`: three checkboxes + Send a test + quiet hours | Notifications page (W5 sub-nav): status card from `push.ts`, one toggle per kind stored per device (`localStorage` + service-worker filter), Send a test, Troubleshoot link (permission state). | M |
| F7.14 notification actions (Reply / Mark read / Reply as…), deep links to friend page / notify page / bucket | no SW actions | Service worker `notificationclick` actions + route targets (`people/<id>`, `people/notify/<id>`). Check Safari/Firefox limits (actions are Chromium only). | M |
| Slice 2 deep links (Notifications rows, hold menu) | rows open generic routes | Row targets as Android's `FriendsScreen(openRoute)`. | S |
| F3.11 compact view hint text | `Home.svelte:201` long hint | W1 trim. | S |
| F8 Stage shell (bottom 5-tab drawer: Select, Filters, Style, Redact, Names & times; Save drawer; F8.5 Stage a post with replies/levels) | `Stage.svelte` (449) and `PostStage.svelte` (74) old panel UI | **Blocked on Android's wave 7 Stage items** (slices 3-5 not committed; `StagePlan.kt` is in someone's working tree). Core `stagePlan` additions (hide_reactions, hide_thread_previews, black-out spans...) land with fixtures first; then web adopts the same shell in a right-hand panel on desktop, bottom drawer on narrow. Do last. | L |
| F3.8 widgets, F3.2 launcher | not applicable | skip | - |
| Slices 8, 11 (sheets to drawers, polish) | fully covered by the section 3 kit | restyle `Switcher`, `LogPastSwitch`, `PlaceSheet`, `LayersDisplay` pickers etc. with the kit once it exists | M |
| F11.5 Notifications (web frame), F7 Friends | done (UI19, UI20, D-127/D-128) | none beyond W7 rows above | - |

## 2. Known web bugs to fix along the way

| Bug | Where | Fix |
| --- | --- | --- |
| A forward whose author is hidden shows "Forwarded from " with nothing after it. Android (3e9a7c8) now sends `authors: []` in the snapshot when "Show who originally wrote it" is off, and its card reads "Forwarded". | `ui/Message.svelte:323` (`from` string joins `f.authors`), and `ui/Stage.svelte:334` ("Forwarded from {authors}:") | `authors.length ? 'Forwarded from ' + names : 'Forwarded'`, then ` · #channel` only when there is a channel name; same in Stage; add a `Message` test with empty `authors`; the sender side (author toggle) comes with the Forward sheet in W4. |
| Web member state write erases Android's multi-tag state (`member_status` is `{tags, note, ...}` on Android since fa1e20e; web writes `{text, at}` only) | `PlaceSheet.svelte:100`, `lib/fronting-display.ts` | Parse the full shape and keep unknown fields when writing; see W7. Do this before any web state UI. |
| Unread divider says "Unread messages" with no count/reader | `Chat.svelte:~1367` | W4 row F5.2/F5.5. |
| "Not sent" rows have no retry, no clock | `Chat.svelte:~1438` | W4 F5.17 (needs wasm `retry_issue`). |
| Long "Simple view" hint repeats | `Home.svelte:201` | W1 trim. |
| `routeTitle` shows "Home" for the person's Fronting-less route and "Fronting" for systems; once Home is the dashboard the title and `active` tab logic in `App.svelte:137-161` must be redone | `App.svelte` | W2. |
| Fronting `Reviews` "Keep both" wording (F3.6 wants a radio + Later / Save to history) | `Reviews.svelte:76` | W7. |
| `design/tokens.json` accent block is commented "Android only"; web ignores it | `tokens.json:44`, `scripts/gen-tokens.mjs` | W5 Appearance. |

Check for regressions each slice: `git grep -n "Forwarded from"` after the fix returns only the guarded form.

## 3. Shared pieces to add once

Android's reference is `ui/Drawers.kt` (D-124/D-125) plus `UnderlineTabs`, `SectionLabel`, `SubpageBar`. Web already has
`ui/Sheet.svelte` (bottom sheet, centred dialog on wide screens, layers for sheet-over-sheet, footer slot) and the
`fr-*` classes in `lib/design/friends.css` (Friends screens). Promote and extend rather than start over: put new
components in `web/src/lib/ui/kit/` and move the `fr-*` styles to `lib/design/kit.css` (keep `friends.css` importing
it; rename classes gradually, no flag day).

| Component (Android twin) | Web home | Replaces |
| --- | --- | --- |
| `Sheet` (`Drawer`) | keep `ui/Sheet.svelte`; add `size`/`footer` variants only if needed | ad hoc `.panel` dialogs (`PkImport`, etc.) |
| `MenuSheet` + `MenuRow`/`MenuDivider` (`MenuDrawer`) | `ui/kit/MenuSheet.svelte` | per-screen popover menus (`Members.svelte:346`, `Home.svelte` card menu) |
| `ConfirmSheet` (`ConfirmDrawer`) | `ui/kit/ConfirmSheet.svelte` | `confirm()` calls (forward warning, archive, delete) |
| `FormCard`, `FormValueRow` (46 px, value + chevron), `FormNote`, `FormDivider` | `ui/kit/FormCard.svelte`, `ValueRow.svelte`, `FormNote.svelte` | `.setting` rows (`PlaceSheet`), `Settings` blocks, `MemberEditor` sections |
| `ChoiceField`/`ChoiceChip`, `Segmented`, `Switch` (`ChorusSwitch`) | `ui/kit/Segmented.svelte`, `Switch.svelte`, `ChoiceChips.svelte` (promote `fr-seg`, `fr-switch`, `fr-chips`) | native `<select>`, checkboxes in settings |
| `EmptyState(icon, label, action)` | `ui/kit/EmptyState.svelte` | 15 `<p class="empty">` + `.fr-empty` |
| `UnderlineTabs` (`Friends.kt:131`) | `ui/kit/UnderlineTabs.svelte` (promote `fr-tabs`) | chip rows posing as tabs (`Members`, `Journal`, `Profile`, `Search`) |
| `SectionLabel` (teal, optional right link) | `ui/kit/SectionLabel.svelte` | `<h2>`s on Home/Settings |
| `SubpageBar` | extract from `App.svelte` `.app-bar.subpage` (`:199`) | per-page back links |
| `ConnectionBanner` + `Toast` host (`ConnectionBanner.kt`, `Notice`) | `ui/kit/ConnectionBanner.svelte`, `ui/kit/Toasts.svelte` (+ `lib/notices.svelte.ts`, merge `UndoToast`) | status label, silent 429s |
| `Avatar`/`Face` presence rings | exists (`Face.svelte`) | none |
| `SettingsShell` (left sub-nav) | `ui/kit/SubNav.svelte` | `More`, `DataPage` |
| Drag-reorder rows with grip (Home sections, bottom bar, fields, folders) | `lib/drag-reorder.ts` + `ui/kit/DragList.svelte` built from the existing reorder code in `ChatFolders.svelte` / `Members.svelte` | four near copies |
| Forward/Reply-in chat picker grid | `ui/ForwardSheet.svelte` (W4), reused by Reply in…, Quote bundle | `<select>` bars |

Rule: before writing a screen in a slice, check whether a kit component it needs exists; if not, add it in that
slice's first commit with a small component test (`vitest`, see `PollCard.test.ts`).

## 4. Brand assets

- Source: `F:/DunBuild/affinity-export` (`INVENTORY.md`). Use the **outlined SVGs only**: MPI Roman Condensed
  (the wordmark font) is proprietary, never bundle it; Hanken Grotesk (UI face) is OFL and already bundled
  (`web/public/fonts`). Confirm with the owner before first public deploy that the outlines are theirs to publish
  under MIT (INVENTORY says so; Android already ships them).
- The welcome wordmark: Android's `android/app/src/main/res/drawable/chorus_wordmark.xml` is the CHORUS wordmark
  with the mark in place of the O (as F1.1 draws it), cream `#FEF4E8`, viewport 3754 x 1215, **one path**. Copy its
  `android:pathData` verbatim into `web/src/lib/brand/chorus-wordmark.svg` (`viewBox="0 0 3754 1215"`, `fill="#FEF4E8"`;
  Android path syntax is SVG path syntax). Export as an inline Svelte component `ui/kit/Wordmark.svelte` taking
  `fill` (cream on teal, teal on cream) with `role="img"` and `aria-label="Chorus"`. Do **not** use
  `svg/chorus-wordmark-*.svg` for onboarding: those have the plain O; they are for places with no mark (footer,
  README, `web/index.html` noscript).
- Blobs: `svg/chorus-blobs-clipped.svg` (3.3 KB, artboard 1 without the logo) as the welcome background (teal `#3da599`,
  olive `#7aa31c`, sunflower `#f0bd1b`, mustard `#d0a10e`); Android draws them on a Canvas (`AvatarBlobs.kt` /
  `OnboardingArt.kt`), so either inline the clipped SVG with `preserveAspectRatio="xMidYMid slice"` or mirror the shapes.
  Tagline "VOICE FOR THE MANY" in Hanken Grotesk italic.
- Other uses: PWA icons and favicon (`web/public/icon.svg`, `manifest.webmanifest`) can take
  `svg/chorus-appicon-teal.svg` / `chorus-appicon-light.svg` (the Android launcher icon already follows the brand;
  check it matches before swapping); `theme-color` stays per theme.
- Serif for member names in F1.3 does not exist in the logo file (plan section "Owner questions"): keep the
  app's sans.

## 5. Slice order (each ships alone, biggest value first)

1. **Kit + sweep (W1 + section 3 basics + section 2 bugs).** Forward bug, text trims, EmptyState, MenuSheet,
   ConfirmSheet, FormCard/ValueRow, UnderlineTabs, Segmented, Switch, ConnectionBanner, rate-limit toast. S-M, no
   behaviour risk, and every later slice needs it.
2. **Home dashboard (W2).** Biggest user-visible gap (D-130 says Chorus opens on Home on web too), 3-4 taps saved per
   switch, removes the "Fronting is Home" confusion. Includes the Fronting route rename and Sections sheet. L.
3. **Chat: Forward/Reply-in sheet, New message page, aside + audience bar, message actions (W4 slices 0-4).** The
   web chat is the most used screen and the `<select>` forward and stepper are the worst divergences. L.
4. **Settings shell + Appearance + Trash + Data pages (W5).** Sub-nav (F9.2-F9.4/F10.6 frames), accents/density/text
   size, Trash with "Deleted by", Server health layout, Install card, update sheet. L but splits per page.
5. **Members directory, Groups, Edit member, Who can see, Custom fields (W3).** M-L; F4.6 has a web frame.
6. **Onboarding + brand (W5 F1.x, section 4).** One-screen welcome with wordmark first (S-M, high first impression),
   then setup + words (L) last.
7. **Journal composer + formatting + polls/board (W6 composer, polls, System board).** Then reading polish, feeds,
   relationships and lists as separate commits.
8. **Fronting leftovers (W7): member state parser (do early, it is a bug), state page, review page, per-kind
   notifications, deep links.**
9. **Chat remainder: attach sheet, search tabs, chat info Polls/Folder, folders restyle, unread divider, offline
   retry (needs wasm `retry_issue`) (W4 slices 5-9).**
10. **Stage shell (W7)** after Android's Stage definition fields and F8 shell exist in core; then wave 8 for both.

Each slice: read its audit rows and frames, grep the web for the existing piece (table above names it), commit with
tests, `python scripts/verify.py --changed`, run `bash scripts/e2e-web.sh` when routes change (slice 2 and 4 change
routes), update `PROGRESS.md` via `python scripts/board.py` with a log line, deploy web with the next Android wave 8
deploy or on its own through `deploy-linux.ps1` when the owner asks.

## 6. Status (2026-11-13)

All ten slices and both stages of the design consistency pass (ui21-web-consistency.md) are on main and deployed (06e066f, 2026-11-13).
The full gate passes; the browser suite passes 31 of 31 except when the service-worker takeover in
failure.spec stalls, which is Playwright's debugger on the worker and not the app (NOTES, Web). Rarely opened
screens load on demand so the opening script stays in budget (D-156).

Left over, for wave 8 or after:

- Home: the F0.7/F0.8 right-hand column (search, Requests, Your spaces, Also new); a "Reading as" switcher; a deep
  link into the composer for "Write something"; DM titles from author cards.
- Chat: `@everyone` (waits for a spec line); offline retry and the clock on pending bubbles, and Retry on Sync
  issues (need a wasm `retry_issue` export); per-option detail on chat info's Polls tab; a poll message whose
  text equals its question shows it twice (seen with seed data; check a real poll).
- Settings: Webhooks' Deliveries list (data isn't on web); a System settings page with the words (F1.2) editable
  after setup. Sync issues was never seen in a browser (it needs an unsynced op).
- Members: the held menu; Restore on an archived profile; drag handles for group members. "Show age" only changes
  what is drawn: the date still reaches readers who may see the field.
- Journal: a drafts list; the feed rule builder and its two switches; the "Co-fronting with" chip; hanging-indent
  lists; Relationships as its own page; the F6.13 right rail. Votes and Nudge were not seen with other accounts' votes.
- Notifications: no per-kind filter on this device (the switches are the account-wide `notify_chat`), no push-tap
  deep links or notification actions (the service worker has no click routing).
- Fronting: the front editor is still an inline card, not value rows and sheets. Opening `#/review/<front>` by
  reload or link goes straight back (reported by the tour, not reproduced since; the page leaves when it finds
  nothing to check, which may run before the front's history is ready).
- Stage: "Load older"; the Save sheet's name prefill; the Style tab's Width control sits below the fold of the
  phone drawer.
- The slow-down toast was never triggered in a browser.
- Service worker: `activate` deletes a newer version's cache when three versions follow closely (NOTES, Web).
