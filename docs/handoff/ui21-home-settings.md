# UI21 audit: Home, Getting started, Away-from-home, Data, Settings (Android)

Read-only audit, 2026-11-08, against the Figma cache `F:\DunBuild\figma-cache` (frames F0.1-F0.6, F1.1-F1.6,
F2.5, F9.1, F9.5, F9.6, F10.1-F10.5, F10.7; web F0.7, F0.8, F9.2-F9.4, F10.6, F10.8). Android code under
`android/app/src/main/kotlin/garden/vayne/chorus/` (shortened to `ui/`, `data/`, `MainActivity.kt`).

Cache caveats: the PNGs for F0.2-F0.6 are mis-cropped (they show slivers of other frames), and the XML for
F10.8 is the F0.7 tree (its PNG is the web "Install Chorus" popover). Use the XML for F0.x. Text names in
the XML are cut at about 40 characters.

**Headline finding.** `ui/Home.kt` is the *Fronting* screen (F3.1/F3.11). **There is no Home dashboard on
Android** (or web): the whole F0 section is new. Several F0 parts already exist as pieces to reuse (friends
rows, chat rows, switcher, history), listed under F0.1.

**Totals (22 Android frames): 0 match, 9 partial, 13 missing.**

---

## 1. Frame tables

Size: S under half a day, M about a day, L multi-day. "Core/server" = change outside `android/`.
Visual conventions to reuse everywhere (all already in `ui/Drawers.kt`, D-124/D-125): `FormCard`,
`FormValueRow` (46 dp), `FormNote`, `Drawer`/`ConfirmDrawer`/`MenuDrawer`, `ChoiceDrawer`, `ChorusSwitch`,
`DrawerButton`, `SubpageBar` (History.kt:177), `UnderlineTabs` (Friends.kt:110), `SectionLabel` (teal).

### F0 Home

Shared notes for F0.1-F0.6.

- New destination. `Tab` (MainActivity.kt:218) has `Home("Fronting")`, which is the Fronting screen and whose
  *name* `"Home"` is persisted in `chorus.navigation` prefs (`data/DeviceNavigation.kt:5-13`). Do not repurpose
  the key. Rename the enum entry to `Fronting` (still stored as `"Home"` for compatibility) and add a new
  stored name for the dashboard (suggest `"Today"`, label "Home").
- The title row replaces the generic header: 32 dp reader avatar, title "Home" (about 18 sp bold), subtitle
  "{system} · Saturday 7 November" (12 sp ink3), search icon at right; the reader avatar opens "reading as".
  Bell with badge only in the offline variants (F10.3).
- Section header style is the Figma teal 13 sp label with an optional right-hand teal link ("Edit", "Chat",
  "Friends", "History", "Journal"): `SectionLabel` in `ui/Friends.kt` is the closest existing component.

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Core/server |
| --- | --- | --- | --- | --- | --- | --- |
| **F0.1 Home as a system** | missing | Everything. Needs: (a) *Fronting now* card: members as 64 dp presence avatars (solid ring = fronting, dashed = co-conscious, plain = present; name under), "since 15:12 · 2h 15m", **Switch** (teal, 44 dp) and **Log a past one** (surface2, 44 dp) side by side. (b) *Quick switch* row: 40 dp avatars + 14 sp names, last item "Everyone", "Edit" link. A tap switches (one tap, `Front.quick` modes, `data/Front.kt:8`). (c) *Handover* card: "Handover from Amarynthe / Appointment at 2 pm · keys by the door" with **Read**. (d) *Waiting for you* (unread chats, "Chat" link). (e) *Friends right now* horizontal strip of 164 dp cards ("Kit steering · Juniper co-conscious", "since about 10:15"). (f) bell badge in the bottom bar (exists). | New composable; card = `p.surface` 22 dp radius with 1 dp `p.line` border; "Switch" is a full-width-half teal `DrawerButton`; presence avatar is 48 dp `Avatar` + ring from `layerInk` (Home.kt `LayerBox`/`FaceChip`). The Fronting FAB (Home.kt:362) is *not* on Home. | new `ui/HomeDashboard.kt` (about 450 lines). Reuse: `FrontScene.build` + `rememberSince` (Home.kt:125, 168-170), `Switcher` (ui/Switcher.kt:102; pinned+recents at :134, widget pins editor :464 for "Edit"), `LogPastSwitch` (ui/LogPastSwitch.kt:63; currently only opened from History.kt:178), friends rows `Friends.rows/around/nowLine/sinceLine` (`data/Friends.kt:32-82`) and `FriendFronts.refresh` (`data/FriendFronts.kt:28`), `ChatListRow` (ui/ChatChrome.kt:89). **Blocker for (d):** the chat row builder is inline in `Chat()` (ui/Chat.kt:578-619) and needs `directory`, `foreignAuthors`, `privateCards`; extract to `rememberChatRows(chorus, model)` first. | L | no. Handover read-state = account pref `home.handover_read` (`pref.set` is free-form, `crates/chorus-core/src/model.rs:427`) |
| **F0.2 further down** | missing | *Friends right now* adds "Mossbank: Doesn't share fronting" (muted card, no faces). *Today* timeline: 56 dp rows, time column 38 dp, 26 dp icon disc on a 2 dp vertical rule (swap = switches, journal = posts, poll = poll closed), text "Foxglove started steering · Amarynthe co-conscious"; "History" link. *New from friends*: post rows (avatar, name `@handle`, body, reply/repost counts), "Journal" link. | Today needs a merged feed of: own switches (named history `chorus.frontHistory`, `data/FrontStory.kt:14`), own posts, closed polls (`data/Polls.kt`) from midnight. | add `HomeToday.kt`; posts from `data/FriendsFeed.kt:28 shown()`; reuse the Journal post row (`ui/JournalFeeds.kt`) in compact form | M | no |
| **F0.3 choose what shows** | missing | "Home sections" **drawer**: title + subtitle ("Drag to reorder. Each member keeps their own Home" in the XML), 7 rows (grip, name, one-line description, `ChorusSwitch`): Fronting now, Quick switch, Handovers and reviews, Waiting for you, Friends right now, Today, New from friends; footer **Reset** (74 dp, quiet) + **Done** (276 dp, teal). Per-reader storage ("each member keeps their own"). | Drawer per D-124; row 55 dp; `ic_grip` exists. Entry point: a "Sections" icon in the Home title row (web F0.7 has a "Sections" pill) and "Edit" link on Quick switch. | `ui/HomeSections.kt`; pref `home.sections:<reader member id>` (fallback `home.sections`) = ordered `[{id,on}]` | M | no (pref) |
| **F0.4 just me** | missing | Person account: *Write something, Ren...* composer row (avatar, placeholder, image + poll icons; opens the Journal composer, `Journal(externalNewEntry)` path at MainActivity.kt:294, 466), *Friends right now* (here the **systems she follows** with their fronting; a not-yet-revealed one reads "You'll see who's around after their next switch" / "Followed today", D-093), *Waiting for you*, *New from friends*. No Fronting card/quick switch. | Same card styles as F0.1. | same file, `model.isPerson` branch. Today `routedTab` sends a person's `Tab.Home` to Journal (MainActivity.kt:245): remove once Home exists. | M | no |
| **F0.5 just me, down** | missing | End of feed "You're all caught up / Older posts are in Journal." (36 dp check disc, 19 sp + 16 sp lines) after 3 posts. | Centered `QuietNote`-like block. | same | S | no |
| **F0.6 first day** | missing | *Welcome, Ren* card: line "Chorus is quieter with friends. Follow a..." (XML cut off), buttons **Find someone** (opens `FollowDrawer`, ui/Friends.kt:508) and **Share @ren** (system share sheet with the handle and server host); empty *Friends right now* ("Nobody yet / Systems you follow show who's fronting here"), empty *Waiting for you* one-liner ("Nothing waiting. Messages and mentions land here"), *Try*: rows "Write your first post / Friends can reply and react" and "Fill in your profile / Pronouns, a picture, a few words" (each 56 dp, icon, chevron). Disappears when there is a post, a follow and a profile field. | Welcome card is tonal (`accentSoft`), 358 x 175. | same; "Fill in profile" opens the self profile editor (`ui/MemberEditor.kt`) | S | no |

### F1 Getting started

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Core/server |
| --- | --- | --- | --- | --- | --- | --- |
| **F1.1 invite, as plural** | partial | Present: invite link, kind peek (`Api.inviteKind`), name, handle. Missing: **"This device"** name field (Api.kt:86 hardcodes `MANUFACTURER MODEL`); **"I am plural" toggle** (replaces the two `KindChoice` cards; on = `system`, off = `person`; the peek still pre-sets it, D-093); **Link device** secondary button with scan icon (scan the QR on another device's Link screen: needs a QR scanner library, pin it per AGENTS.md); inline validation ticks on filled fields; all four fields on **one** screen. | Full-bleed teal background with lime/sunflower/teal blobs, cream **CHORUS** wordmark + "VOICE FOR THE MANY" tagline; fields are cream (about 85% opaque) 48 dp rounded rows with a 10 sp caps label ("INVITE CODE", "SYSTEM NAME", "HANDLE", "THIS DEVICE") and a teal check; **CONTINUE ->** cream pill (right), **LINK DEVICE** outlined pill (left). Currently a dark `Column` with Material `Button`/`RadioButton`/`TextButton` (ui/Onboarding.kt:50-173). | ui/Onboarding.kt:104-172 (rewrite); `Api.enrol` (data/Api.kt:79) gains `deviceName` | M | no |
| **F1.5 invite, not plural** | partial | Same screen: toggle off, label becomes "YOUR NAME", toggle text reads "I am **not** plural" (accent word). | as F1.1 | same | (in F1.1) | no |
| **F1.2 words** | missing | Setup step: "We call ourselves" chips (System, Collective, Household, Home, Family, Custom...) and "When someone is in control, they are" (Fronting, Present, Out, Steering, Custom...). Multi-state chip: check = chosen, `+` bubble = custom entry. Stored in `system.terminology` (schema exists, DATA_MODEL line 295; D-004). **Terminology is not implemented on either client** (grep finds none), so choosing words is pointless until strings read from it. | cream pill chips on the teal field, BACK / CONTINUE footer | new `ui/SetupWords.kt`; a small `Words` object (system word, front word, "switch" verb) read by Home, Fronting header, friend strip lines, nav label | L (the string plumbing, not the chips) | no (`system.set` op exists) |
| **F1.3 who's in the system** | missing | Add members at setup: grid of 104 x 118 cream cards (avatar 48, serif name, caps role "SYSTEM HOST") + dashed **+ new** tile; plus (SPEC 3.1 step 3) **Import from PluralKit** entry. Android today creates an account with no setup flow; members come from Members > add (ui/Members.kt). | serif display face for names (Figma "Alter display"); needs a font decision | new `ui/SetupMembers.kt`; reuse member create path in `ui/MemberEditor.kt` and `PkImportSheet` | M | no |
| **F1.4 who's steering** | missing | Pick first front from the member grid (scrolls, fades at the bottom); CONTINUE records the first switch. | same card | `SetupFront` step, calls `Front.switch` | S | no |
| **F1.6 link a device** | partial | Present: one-use link text + Share (ui/DeviceLink.kt:25-52, a drawer). Missing: **QR** (server already returns `qr_svg` from `POST /devices/invite`, Chorus.kt:533 discards it: render by parsing the SVG rects, or draw modules on a Canvas), **Copy link** (only Share exists), **Devices list** ("Pixel 8 · This device", "Laptop · Last seen 2 days ago · SIGN OUT": `GET /me` lists devices, `POST /devices/{id}/revoke`, docs/API.md lines 78-81). | full page with `SubpageBar` ("Link a device", back, more) not a drawer; dashed 342 x 120 QR placeholder; 40 dp initial discs in `FormCard` rows | rewrite DeviceLink.kt as a routed screen (MainActivity.kt:335); `Chorus.deviceInvite` returns `{url, qr}` | M | no |

### F2.5, F10.3, F10.7 Banners and toasts (shared component)

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Core/server |
| --- | --- | --- | --- | --- | --- | --- |
| **F2.5 away from home** | missing | A banner on **every** destination while `Status != Live`: wifi-off icon + "Away from home. Changes wait on this phone..." (XML cut). Today the only indicator is the 12 sp subtitle on the generic header (MainActivity.kt:395) which Home/Fronting, Chat, Journal, Notifications, Friends and Members hide (own chrome, :386), plus ad-hoc strings at Journal.kt:523, ChannelEditor.kt:153, ContentSearch.kt:183. | 56 dp full-width strip under the title row, tinted (`warn` at about 15%), 18 dp icon, 2-line 13 sp text. | new `ui/ConnectionBanner.kt`, placed in `App` between header and `Box(Modifier.weight(1f))` (MainActivity.kt:402). Words differ by install: a Chorus Home install (device has a TLS pin, `DeviceRecord.pin`) says "Away from home"; the public server says "Offline. Changes wait on this phone." (D-095: the owner's server is public, so "home" never applies there). | S | no |
| **F10.3 server asleep** | missing | Same strip in coral (`danger` tint) with **Retry** button (`Chorus.reconnectNow()`, Chorus.kt:746) and copy "Chorus Home is asleep. Everything you do is saved here and sent later." Shown when connect fails repeatedly (reachable network, no server) vs F2.5's no-network/other-wifi case. Bell icon appears in the Home title row. | same component, `kind = Asleep` | same | S (with F2.5) | no |
| **F10.7 slow down** | missing | On `429 rate_limited` (server returns `Retry-After`, ratelimit.rs:3, 157; sync socket frame `rate_limited`, app.rs:2248) show a toast pill "Catching its breath. Try again in 12 seconds." with clock icon, counting down. `Api.call` (data/Api.kt:34-47) ignores the header; sync frame error is swallowed. | 314 x 38 pill, `surface2`, 14 sp, 16 dp clock icon, bottom-centre above the bar | `Chorus.notices: SharedFlow<Notice>`; `Api.call` reads `Retry-After`; toast host in `App` | S | no |

### F9 Data

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Core/server |
| --- | --- | --- | --- | --- | --- | --- |
| **F9.1 fronting insights** | partial | Present: charts and CSV (ui/InsightsScreen.kt:64-255), front/layer/history selectors. Missing: **Week / Month / Year** segmented (now `7 days`/`28 days` TextButtons, :166-167; Year = 365 d, bar by week/month); three **stat tiles** (Switches + "3 more than last week", Most time + "41% of the week", Together "11 h / co-fronting"); **Patterns** rows (Busiest hour "Most switches happen 09:00-10:00", Longest front "Foxglove, 6 h 20 m on Tuesday"); one **View data** + **Download CSV** pair (now per-chart TextButtons, :189-193). | Front-time chart is **vertical stacked columns** per day, member colours, M T W T F S S labels, legend dots (now horizontal bars, :197-212); the other three charts (week, co-front pairs, switches/day) have no frame: keep them below as extra cards in the same card style. Subtitle "For your own reflection · only your system sees this". Back arrow + `more` menu bar (`SubpageBar`). Front/Time layer/History pickers move into the `more` menu or a collapsed "Options" row (they are Advanced). | InsightsScreen.kt; computation in `data/InsightsData.kt` (needs previous-period snapshot for "vs last week", busiest hour, longest front) | M | no |
| **F9.5 trash** | missing | No Trash on Android at all (web has one: ui/Trash.svelte, data.ts `trashItems`, SPEC 8a/D-053; ChannelEditor.kt:222 even promises "restored from Trash"). Needs: list of deleted messages, posts/entries, members, groups, channels, grouped *This week / Earlier*; tabs **All / Messages / Posts / Members**; search icon; per-row **Restore** (`message.restore`/`post.restore`/`member.restore`/`group.restore`/`channel.restore`, same op names as web); note "Restoring puts it back where it was, with its replies and reactions." Figma footer "Empty trash - gone for good": see conflicts. | 60 dp rows: 40 dp tinted tile icon (message blue, post coral, member violet, photo amber), 15 sp title ("Message in #vent"), 12 sp "Deleted by Nairon · 2 days ago", pill **Restore** (surface2, retry icon). | new `ui/Trash.kt`; `Model` already collects `deletedMessageIds/PostIds/MemberIds` (data/Model.kt:174-177, 514-516) but not the rows: add `trashItems` (port web `data.ts:445-468`, same ownership filter). Entry: More > Your data and devices > Trash, and "Show deleted" in a chat's menu. | M | optional: "Deleted by <member>" needs `deleted_by_member_id` in the projection (core `model.rs:294`, op already carries `member_id`); ship without the "by" until then |
| **F9.6 on this device** | partial | Present (in Settings "This device", SettingsScreen.kt:179-208): keep everything toggle, sync now, space used (a single number). Missing: **usage bar** (3 segments) with legend "Messages and posts 300 MB / Photos and files 820 MB / Other 80 MB" (`chorus.spaceUsedBytes()` sums everything; split by DB vs blob dir), **Clear downloaded files** ("Keeps messages; frees about 820 MB", with the shield note), "Last synced 2 min ago" on Sync everything now. | `FormCard` usage block (143 dp): bold total at right, 10 dp bar with `#9DB8F0` / `sun` / grey segments, dots; then section label "Offline", `FormValueRow`s with sublines; section "Free up space". The two long progress strings (:202-203) become the row subline. | new `ui/OnThisDevice.kt`; `Chorus.clearBlobCache()` is new (evict blobs, keep rows; blobs re-fetch on demand, nothing server-side is deleted) | M | no |

### F10 Settings

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Core/server |
| --- | --- | --- | --- | --- | --- | --- |
| **F10.1 More** | partial | Present: a list of destinations (ui/AppNavigation.kt:32-55). Missing: **two sections** "Go to" (Home, Members, Friends [badge: pending requests, "2"], System board and polls, Fronting insights) and "Settings" (Appearance, Notification settings, Privacy and sharing, Your data and devices, System settings); each row has a **dynamic one-line status** ("4 members, groups and custom fronts", "Dark · teal · comfortable", "What rings, quiet hours, this device", "Exports, backups, linked devices", "Names, words we use, connection"). The current list also repeats bar items (Chat, Journal, Notifications) and mixes in Fronts and layers, Front history, Search, Link another device: drop what is on the bar, move Fronts/History under Fronting's own menu (they already are, Home.kt:218-219, 302-306), Search is the title-row icon, Link a device lives in Your data and devices. "System board and polls" opens Journal's system board (F6.9; owned by the Journal audit). Add **Sync issues · N** row (red) when `chorus.syncIssues` is non-empty, and an "Edit bottom bar" row (replaces SettingsScreen "This device > Bottom navigation"). | Title row `More` + "The Lanterns · reading as Foxglove" + search; rows are **flat 62 dp** (no card), 22 dp line icon (no tinted circle), 16 sp title, 13 sp ink3 subtitle, chevron, section labels teal 13 sp. Current: 80 dp raised cards with 40 dp tinted circles (AppNavigation.kt:58-73). | AppNavigation.kt `MoreScreen`/`DestinationRow` | M | no |
| **F10.2 appearance** | partial | Present: Dark / Light / Follow device (SettingsScreen.kt:139-147, `ChorusTheme` `chorus.appearance` prefs, designsystem ChorusTheme.kt:24-48). Missing: **live preview** (two chat bubbles, in/out, member colours); **Accent** (Teal, Sunflower, Lime, Lavender, Sky; 36 dp swatches, selected has a ring; `Palette.accent/accentSoft/onAccent/bubbleOut` per accent, per mode, so extend `design/tokens.json` + `scripts/gen-tokens.mjs`); **Member colours in chat** switch; **Text size** (Default = follows device, plus larger steps: scale `LocalDensity.fontScale`); **Density** (Comfortable / Compact); an **Advanced** row (Contrast, motion, chat style) that becomes the home of the other Advanced toggles. | top bar with back arrow, "Appearance / On this device", teal check at right (apply); **segmented** Light/Dark/Follow device (order Light, Dark, Follow), 33 dp; section labels Theme, Accent, Reading; value rows with the value in teal at the right (`FormValueRow`). Today: three `TextButton`s. | new `ui/AppearanceScreen.kt`; `ThemeMode`/`Appearance` data class grows (accent, textScale, density, memberColours) | M-L | no (device-local, `chorus.appearance`) |
| **F10.4 sync issues** | partial | Present: list with message, time, Copy text, Dismiss, inline in Settings and only if non-empty (SettingsScreen.kt:155-178). Missing: own screen titled "Sync issues / 1 change couldn't be saved"; shield note "Nothing is lost. Your original text is still saved on this device."; per issue a **human title** ("Edit to a message in #general", from `kind` + channel lookup), red reason ("Not saved · the channel was deleted"), quoted original text with member colour bar and "Foxglove · 10:24"; **Retry** / **Copy text** / **Discard**; a **Fixed** section ("Edit to 'A quiet afternoon' · Saved on retry · yesterday"). Retry and Fixed need new core API. | issue card 358 wide, red 40 dp alert tile, `Your text` block with 3 dp coloured rule | new `ui/SyncIssues.kt`; row in More | M | **yes, small**: `Replica` exposes issues without payload for retry; add `retry_issue(id)` (re-issue the op under a new id) and keep resolved issues for the Fixed list (crates/chorus-core/src/replica.rs:390-413, ffi lib.rs:312-318). Without it: ship Copy + Discard, no Retry/Fixed. |
| **F10.5 update available** | partial | Present: banner with Download/Update/Allow/Retry states (ui/UpdateBanner.kt:34-77, Updater states). Missing: the **drawer**: 44 dp "C" tile, "Chorus 0.10 is ready / From Chorus Home · 9 MB", **What's new** card (3 check rows), line "Installing restarts the app. Drafts and unsent messages are kept." (true: drafts are durable, DurableEditor.kt), **Later** + **Install**. | opens as a `Drawer` over the current screen when `Ready`; keep a slim banner for Downloading/Failed. | UpdateBanner.kt; `Updater.Release` (data/Updater.kt:54) gains `changelog` | S-M | **small**: `chorus.json` already carries `changelog` (app.rs:630) but `scripts/deploy.ps1:106` fills it with the last five git subjects ("feat(web): ...") and `deploy-linux.ps1` does not write it; need three human lines. Default: read `android/whats-new.txt`; hide the card when empty. |

---

## 2. Android screens in scope with no Figma frame

| Screen (file:line) | Today | Adopt |
| --- | --- | --- |
| Settings, "Notifications" block (SettingsScreen.kt:209-272) | `TextButton`s, free-text "From/Until" 24 h fields | Page "Notification settings" (F10.1 row): `FormCard` with `FormValueRow`s: *Android notifications* (state + opens system settings), *How this phone gets them* (value "Instant" / "Every few minutes", `ChoiceDrawer`), *Send a test* row, switches Mentions / Direct messages / Replies, *Quiet hours* `FormValueRow` opening `DateTimeDrawer` for From and Until (D-124 time picker). |
| Settings, "Sharing" block (:273-291) | `TextButton` presets + `CeilingControls` inline | Page "Privacy and sharing": the three presets as the radio rows of the accept sheet (F7.7, `AcceptDrawer` Friends.kt:525, `BucketTile`/`Radio`), "Buckets and what each friend sees" as a link row to Friends > Sharing; `CeilingControls` only behind an Advanced row. |
| Settings, "This device" (:179-208) | feature list in a card | F9.6 page (above). |
| Bottom navigation editor (ui/NavigationSettings.kt:27-86) | Material up/down chevrons, X, `TextButton`s, Material `Button` | F0.3 Home-sections drawer pattern: grip rows with `ChorusSwitch`, the live bar preview on top, **Reset** + **Done** footer; replaces "Move earlier/later" arrows. Entry: More footer row "Edit bottom bar". |
| Settings, Advanced (:292-352) | inline expander with 12 toggles | "Advanced" subpage (F10.2 row) with grouped `FormCard`s and teal section labels: Chat (typing, content warnings, annotations, private chats, per-member reading), Notifications (delivery choice, own-switch pings, reply-as), Sharing (history, stats). |
| "System settings" (F10.1 row, no frame) | **does not exist**: system name and the account handle (D-126) cannot be edited on Android after onboarding (`Model.kt:190` only reads the handle) | New page: system name, handle, words (F1.2), server address/connection (read-only, with the home-wifi note when pinned), `FormCard` of `FormValueRow`s. |
| "Your data and devices" (F10.1 row) | scattered: PK import only in the Members menu (Members.kt:232, a `ChorusSheet` with Material buttons: PkImport.kt:28-123), Link device in More, no export | One page: Linked devices (F1.6), Import from PluralKit (as a `Drawer`), Export (CSV from Insights; JSONL/DB exports are web-only today, F9.2), Trash, On this device, Sync issues. |
| `PkImportSheet` (PkImport.kt) | `ChorusSheet` + `OutlinedButton`/`Button`, a paragraph of instructions | `Drawer` with `FormValueRow` "Export file" -> picker, summary as three stat chips (members / groups / switches), `FormNote` for notes, `DrawerButton` "Import". |
| Insights "View data" table (InsightsScreen.kt:118-135) | `TextButton` back, raw " · " joined rows | `SubpageBar` + grouped rows (date, member, hours) in `FormCard`s. |
| Share-in chooser (MainActivity.kt:520-555) | big text rows | `FormCard` rows with icons ("Post as...", spaces/channels), no sentence. |
| Updater non-ready states (UpdateBanner.kt:51-56) | text banner | keep banner for Downloading/Installing; Failed/NeedsPermission become the drawer's footer states. |
| Duplicate "Offline" strings (Journal.kt:523, ChannelEditor.kt:153, ContentSearch.kt:183, Chat subtitle) | per-screen | delete once the global banner lands. |

---

## 3. Text to trim

Be decisive: delete, don't reword, unless a replacement is given.

| Where | Quote | Action |
| --- | --- | --- |
| Onboarding.kt:109 | "Welcome to Chorus" | the wordmark replaces it |
| Onboarding.kt:114 | "A cozy place for plural systems and the people close to them. Paste the invite link you were given." | delete (field label "Invite code" is enough) |
| Onboarding.kt:129 | "This link adds this phone to an account you already have." | delete; the button reads "Link this device" |
| Onboarding.kt:140-145 | the two `KindChoice` paragraphs ("More than one of us shares this body. Chorus keeps track of members, ..." / "I'm one person (a singlet). Follow friends, ...") | delete with the cards; one "I am plural" toggle |
| Onboarding.kt:147 | "Give your system (or yourself) a name to start." | delete |
| Onboarding.kt:158 | "Friends follow you with it." | delete; handle label stays |
| DeviceLink.kt:29 | "A one-use link that works for a day." | delete (the F1.6 line "Works once, for one day." is the only copy) |
| DeviceLink.kt:40 | "Open it on the other device. Anyone with it can join this account until it's used, so send it only to yourself." | -> "Works once, for one day." |
| SettingsScreen.kt:149-151 | "Settings" heading + "Account settings sync across your devices; settings under This device stay on this phone." | delete both |
| SettingsScreen.kt:152 | "Insights · Front history" button | delete (More has Fronting insights) |
| SettingsScreen.kt:193 | "Keeps available files for offline use. Files over 20 MB are opened online." | -> row subline "Read old chats and posts without a connection" |
| SettingsScreen.kt:202-203 | "Checked X of Y scopes" / "Files: a of b (c not available)" | -> a progress bar in the Sync row subline |
| SettingsScreen.kt:214 | "Notifications are turned off for Chorus in Android settings." | -> "Off in Android settings" + the existing button as a row |
| SettingsScreen.kt:226-231 | "Delivered instantly via Firebase." / "Delivered via X." / "Checked every few minutes in the background." | -> row value "Instant" / "Every few minutes" |
| SettingsScreen.kt:254, 261 | "Quiet hours for people you follow" / "Use 24-hour time, in your local time zone." | -> `FormValueRow` "Quiet hours  23:00 - 08:00"; the time drawers make the second line unnecessary |
| SettingsScreen.kt:277, 285, 289 | "Default for new followers" / "Custom ceiling is active. Choose a preset to replace it." / "Set buckets and each follower's access in People." (stale: the tab is "Friends") | delete all three |
| SettingsScreen.kt:300 | "Push delivery on this device" | -> row title "How this phone gets them" |
| SettingsScreen.kt:323, 329 | "Show members' private chats only while they're fronting" / "Replies from a notification speak as the mentioned member" | -> "Private chats only while their member is here" / "Reply as the mentioned member" |
| AppNavigation.kt:38-52 | every `DestinationRow` description ("Your members and profiles", "Current presence and quick switching", "Mentions, replies and friends' news", "Channels and direct messages", "Connections and following", "Entries, notes and member journals", "Separate fronts, following and who counts for what", "Look back, amend or log a switch", "History, patterns and exports", "Theme, notifications and privacy", "Messages, posts and switches on this device", "Use Chorus on another phone or browser") | delete; keep only dynamic status sublines (counts, current choice, "reading as X") |
| NavigationSettings.kt:45 | "Choose up to four destinations. More always stays at the end." | delete (the live bar preview shows it) |
| InsightsScreen.kt:174 | "$frontName · $layerName time. Change counts cover all layers of this front. Day and week bars show member-hours; simultaneous members add together." | delete; one-time info in the `more` menu if needed |
| InsightsScreen.kt:175 | "Front history on this device. Simultaneous fronts add together." | delete |
| PkImport.kt:103 | "In Discord, run pk;export and download the file PluralKit sends you. Importing again later only adds what's new." | -> "Choose your pk;export file" (button label) |
| PkImport.kt:108 | "(imported silently, no notifications)" | delete the parenthesis |
| Home.kt:283-285 | card "Simple view is on" / "Only one layer is shown, empty layers and other fronts are hidden. Switch back to Detailed at any time." (Fronting; F3 audit owns, listed because it is in Home.kt) | delete the hint card and its `hintDismissed` plumbing |
| MainActivity.kt:395 | "Chorus · synced" under every generic page title | delete; show state only when not live (the banner) |
| MainActivity.kt:531, 550 | "Choose where to prepare this draft. You can review it before posting or sending." / "No place to share yet. Finish setting up your account first." | delete / shorten to "Nothing to share into yet" |
| ContentSearch.kt:183 | "Offline · local replica only. Shared posts need a connection." | delete (banner covers it) |

---

## 4. Taps to cut

| Flow now | Taps | Figma/target | Fix |
| --- | --- | --- | --- |
| Switch to one member | Fronting tab, Switch FAB, pick, Apply: 4 | 1 (Quick switch avatar) | Home Quick switch row; long-press avatar = the full sheet |
| Log a past switch | Fronting > history icon > "+" > form: 4 | 1 (Log a past one) | button on Home opens `LogPastSwitch` directly |
| Read a handover note | no surface | 1 (Read) | Home card |
| Open an unread chat | Chat tab, find row: 2+ | 1 | Waiting for you on Home |
| See who is around | More > Friends > Friends tab: 3 | 0-1 | Friends right now on Home |
| Change theme | More > Appearance & preferences > scroll: 3 | 2 (More > Appearance) | F10.1/F10.2 |
| Notification quiet hours | More > Appearance & preferences > scroll to the Notifications card > type two 24 h strings > "Turn on quiet hours": 5+ | 3 (More > Notification settings > Quiet hours > pick) | subpage + `DateTimeDrawer` |
| Insights range | two `TextButton`s, plus four charts each with View data/Export | Week/Month/Year segmented, one View data + one Download CSV | F9.1 |
| Find a sync problem | only visible inside Settings after scrolling; no badge anywhere | 1 from the banner or More red row | row in More + link in the connection banner |
| Link another phone | More > Link another device > Share link > pick app, no QR: 4 | scan: 2 | QR + Copy link on F1.6; no Share needed for same-room linking |
| Join as a new system | paste link, Continue, choose kind, name, handle, Join: 6 screens/steps | 1 screen, 2 taps after paste (or open the invite link: 1 tap) | F1.1 single screen; add a paste icon in the field |
| Restore something deleted | impossible on Android | 2 (More > Your data > Trash > Restore) | F9.5 |
| Install an update | banner Update, system confirm: 2 | Install in drawer: 1 + system confirm | F10.5 |
| Edit bottom bar | Settings > scroll > Bottom navigation > drawer: 4 | 2 (More > Edit bottom bar) | More footer row |

---

## 5. Conflicts with DECISIONS/spec, and owner questions

### Conflicts (DECISIONS win; handled by default, not asked)

1. **F9.5 "Empty trash - gone for good"** vs D-053 / SPEC 8a ("There is no purge in the apps", hard deletes only by the server admin CLI) and AGENTS.md ("hard deletes happen only through explicit purge ops"). Default: omit the button; the Trash screen has no footer.
2. **F10.2 puts Density, Text size and Member colours in Basic**, while DESIGN.md 6 lists density, text size and member-colour intensity under Advanced ("When unsure whether an option is Basic, it is Advanced"). The Figma is newer and the owner's screen; D-079 makes it the visual guide. Default: follow the Figma for the three rows on the Appearance page, keep the finer options (colour intensity levels, contrast, motion, chat style) under the Advanced row.
3. **F2.5 "Away from home" / F10.3 "Chorus Home is asleep" / F10.5 "From Chorus Home" / F10.6 "Chorus Home on this computer"** assume a Chorus Home install, but the owner's server is public on ns2 (D-094/D-095). Default: copy depends on the install (pinned TLS = Home install; otherwise "Offline. Changes wait on this phone." / "From <server host>").
4. **F0.4 person bar `Home · Notifications · Chat · Journal`** vs D-127 person default `Journal · Notifications · Chat · Friends` and D-128 (Friends is a tab). Default: keep D-127's bars as the default; Home is the screen Chorus **opens on** and is on the bar for a person only if chosen (see Q2). Friends then sits in More with the pending-request count.
5. **F1.1 invite field shows a bare `chrs.1a2f...` code** whereas invites are links carrying the server (`https://host/i/<code>`, Home installs `chorus://ip:port/i/<code>#pin=...`, Api.kt:52-66). Default: keep accepting links; show the parsed code in the field once validated.
6. **F1.1 "I am plural" toggle** replaces D-093's "Who's joining? We're a system / Just me" cards: same semantics (`account.kind`), so no decision changes. The peek still pre-sets it from the invite.
7. **F10.5 "Chorus 0.10"** is a placeholder; real versions are `0.1.N` (CLIENTS 5a). Nothing to do.
8. **Fronting insights "For your own reflection · only your system sees this"** is consistent with `share_stats` being a separate, off-by-default grant (SettingsScreen.kt:337). No conflict.

### Owner questions (few)

| # | Question | Default assumed |
| --- | --- | --- |
| Q-A | Should Chorus **open on Home** (F10.1 says "Chorus opens here") and should Home be on the default bottom bar? F0.1/F10.1 show Fronting · Notifications · Chat · Journal · More for a system (D-127), F0.4 shows Home first for a person, and F10.3/F10.5 show another bar again. | Opens on Home for everyone; default bars stay as in D-127; Home is one of the choices in "Edit bottom bar" (D-089) and is More's first row for a system. |
| Q-B | **Empty trash** (F9.5): add an in-app purge? | No (D-053). |
| Q-C | Onboarding art: the repo has no **CHORUS wordmark / blob** artwork (no `.svg` in `assets/` or `design/`), and F1.3's serif display face for member names is not bundled. Can you export the wordmark and name the serif? | Draw the blobs on a Compose `Canvas` in the four brand colours; wordmark as a heavy Hanken Grotesk word until an SVG arrives; names in the existing sans. |

---

## 6. Web (brief; after Android)

Web frames in scope: F0.7, F0.8, F9.2, F9.3, F9.4, F10.6, F10.8.

- **F0.7 / F0.8 Home dashboard**: missing. `ui/Home.svelte` is Fronting (systems); the person variant is a plain "Friends right now" list. Needs the three-column Home (fronting, quick switch, handover, Waiting for you, right rail: friends, Today, New from friends) and a "Sections" button. L.
- **F9.2-F9.4 and F10.6**: all folded into one `ui/DataPage.svelte` (340 lines: exports, tokens, webhooks, server health as stacked cards). Figma wants a Settings **left sub-nav** (You: Account, Appearance, Notifications, Sharing; Data: Your data, API tokens, Webhooks, Trash; This computer: Devices, This computer, Server health, Updates) with one page each. Diverges badly in structure; the content exists.
- **F10.6 server health**: stat tiles (Last backup / Errors / Devices) and the per-device restore list (restore_window data is already read in DataPage.svelte:34, 83-86) are not laid out as in the frame.
- **F10.8 Install Chorus**: no `beforeinstallprompt` handling at all; missing. S.
- **F10.2/F10.1 on web**: `Settings.svelte` is one page (Appearance has only theme); no accent, density, text size, preview. `More.svelte` is the plain card list (it also serves narrow screens).
- **Trash (F9.5 web analogue)**: `Trash.svelte` exists, plain, no Messages/Posts/Members tabs styled as the frame, not in the Settings sub-nav.

---

## 7. Proposed Android slices (each ships alone, biggest value first)

1. **Home (system)**: nav key + start destination, title row, Fronting now card, Quick switch, Waiting for you (extract `rememberChatRows`), Friends right now, handover card, Today, New from friends (F0.1, F0.2). L. Removes 3-4 taps from switch/log/open-chat.
2. **Home (person) + Home sections drawer**: composer row, first-day welcome/Try, caught-up, per-reader section prefs (F0.3-F0.6). M.
3. **Connection banner + rate-limit toast**: F2.5, F10.3, F10.7; delete per-screen "Offline" strings and the "Chorus · synced" subtitle. S, quick win.
4. **More + Settings hub + Appearance**: F10.1, F10.2 (accents, text size, density, member colours, preview), settings subpages (Notification, Privacy and sharing, Advanced), bottom-bar editor restyle, all text trims in section 3. L, split into 4a More/hub, 4b Appearance, 4c subpages.
5. **Trash**: F9.5 (`trashItems` in Model, tabs, Restore). M.
6. **Fronting insights**: F9.1 (segmented range, stat tiles, stacked columns, patterns). M.
7. **On this device + Sync issues + Update drawer**: F9.6, F10.4 (Copy/Discard first, Retry when core ships), F10.5 (+ `whats-new.txt` in the deploy script). M.
8. **Onboarding + Link a device**: F1.1/F1.5 single screen with art, device name, plural toggle, QR scan; F1.6 QR + Copy + Devices list/revoke. M.
9. **System settings + Your data and devices pages** (no frames): rename system, handle, server info, PK import as a drawer, devices. M.
10. **System setup + words**: F1.2-F1.4 and the `Words` terminology plumbing (D-004). L, last because it touches strings everywhere.
