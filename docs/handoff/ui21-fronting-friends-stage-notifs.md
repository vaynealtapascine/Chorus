# UI21 audit: Fronting (F3), Friends (F7), Stage (F8), Notifications (F11) and the app shell, Android

Read-only audit, 2026-11-08 (claude-sonnet-5.5 for claude-opus-5.5). Android code in
`android/app/src/main/kotlin/garden/vayne/chorus/` (paths below are relative to it; `ui/` = `ui/`).
Frames: `F:\DunBuild\figma-cache\frames`. Web is covered only by section 6, and only by grep.

**Reading the Figma cache.** Several PNGs are stale crops; the XML text is authoritative.
- `F7.12.png` is the old inbox (F7.11), `F7.13.png` is really F7.12 (Notifications from one friend),
  `F7.14.png` is really F7.13 (this device). The lock screen (F7.14) has no matching PNG: use
  `shots/3-1332__F7_Friends_amp_sharing.png` (bottom right).
- `F3.9.png` shows presets Simple / Layered / Detailed; the XML (and D-122) says Simple / Detailed /
  Complete. Android follows the XML. Not a conflict.
- Bottom bars drawn in F3.x / F7.x PNGs (Home/Members/Fronting...) are old; the XML bar is
  Fronting / Notifications / Chat / Journal / More, which is what Android ships (D-127).

Size: S under half a day, M about a day, L several.

## 0. Totals (37 Android frames: F3.1–F3.11, F7.1–F7.10 and F7.12–F7.14, F8.1–F8.9, F11.1–F11.4)

- **Matches: 20** (F3.1 F3.2 F3.4 F3.5 F3.7 F3.9 F3.11; F7.1–F7.5 F7.7–F7.10 F7.12; F11.1–F11.3). "Matches" can still
  carry text-trim items from sections 3 and 4.
- **Partial: 15** (F3.3 F3.6 F3.8 F3.10; F7.6 F7.14; F8.1–F8.4 F8.6–F8.9; F11.4)
- **Missing: 2** (F7.13, F8.5)

The recent UI15/UI19/UI20 work is close to the Figma. The big gaps are **Stage (F8), almost all
of it**, the **member state screen (F3.10)**, the **Review screen (F3.6)**, **per-kind device
notifications (F7.13)** and **notification actions (F7.14)**. Everything else is polish, deep links and text.

## 1. Frame table

Columns: Feature gaps / Visual gaps / where / size / server or core change?

### F3 Fronting

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Server/core? |
| --- | --- | --- | --- | --- | --- | --- |
| F3.1 Fronting containment | matches | Member ⋮ is inert (`Box ... Glyph(ic_more_vert, "options")`, not clickable) and the whole row opens PlaceSheet, so there is no quick Set state / Remove from the row. Fronting "+" chip is labelled "Add or manage fronts" and opens the Fronts screen, not a new-front flow. "Other: …" line for unlisted layers is plain text. | Layer footer / member rows / Away / Other fronts / Switch FAB all match. Header uses 20 sp, Figma 22 sp title (nit). | `ui/Home.kt:403`, `:545` (inert ⋮), `:242` (+), `:316` (Other) | S | n |
| F3.2 Switch sheet | matches | "Starting now / Change" expands inline (Now/Earlier chips, note, four `FilterChip` alert options, "Arrange widget pins" link) instead of Figma's single "Change" link. Sheet is `ChorusSheet`, not `Drawer` (D-124 leftover). | Grid, ring + layer tag, Replace/Add segmented, footer summary all match. Material `FilterChip`s in options. | `ui/Switcher.kt:102-255`, `:193` | M | n |
| F3.3 Move between layers | partial | Custom/separate layer rows in Figma carry a rename pencil and a "+" (known left); "Also present in" rows show the layer name or "Follows X · changing it stops following" where Figma says "Can be picked as speaker in chat". State row shows "Tired, nonverbal, busy, or your own words". Button row: Remove + Move to X matches Figma's "Remove / Keep co-conscious". | Sheet is `ChorusSheet` with hand-made radio boxes (`NestedChoice`, `ChoiceBox`) instead of a Drawer with `FormCard`; spacing fine. | `ui/Switcher.kt:284-413` | M | n |
| F3.4 Fronts | matches | Per-front editor is a `ChorusSheet` full of `FilterChip`s and five "Who counts for what" chip rows; not in the Figma (section 2). | Cards, orbit, tags, eye + grip, New front card with template chips all match. | `ui/Fronts.kt:129-360` | S (cards) | n |
| F3.5 Log a past switch | matches | `PickSubjects` (Add member) is a `ChorusSheet` with the old `SearchField`. | Teal note row matches; save appears twice (✓ in bar and wide button), as in Figma. | `ui/LogPastSwitch.kt:221`, `:144` | S | n |
| F3.6 Review | partial | Android shows buttons `Keep both / Keep one… / Merge` in a card at the top of History. Figma is its own screen: "Review · 1 thing to check", two device cards (Phone · 09:12 / Laptop · 09:12), radio list "Both are right / Keep the phone's / Keep the laptop's" with a sentence each, footer **Later** + **Save to history**. "Keep one…" needs a pre-pick tap and an error toast ("Tap the one that was right"). No Review screen reachable from the Notifications "Review" button (it opens History). | Warning icon card with title "Two devices switched within a minute" + "Pick what really happened so your history stays right everywhere." | `ui/History.kt:377-430`, `ui/Notifications.kt:100` | M | n (reuses `front.review_resolve`; "Both are right" = existing merge) |
| F3.7 History | matches | Front line actions are `ChorusSheet` (not MenuDrawer) (`History.kt:158`). "Saved entries" list (Edit time / Undo / Redo text links) has no Figma frame. | Lanes, legend, now line, sentences with layer tag, Log a past switch link all match. Tick labels and lane height fine. | `ui/History.kt:158-260` | S | n |
| F3.8 Widgets | partial | **Unread widget ("Chorus 3 unread for Foxglove") missing.** Front widget and small "Steering F A" widget exist (`widget/FrontWidget.kt`). Lock-screen chip is not possible on a phone (D-122); an opt-in ongoing notification "Fronting: F · A · N · V" is the nearest thing (owner question Q-B). | Cannot be checked without placing the widgets on a home screen (still open from UI15). | `widget/FrontWidget.kt`, `res/xml/front_widget.xml` | M | n |
| F3.9 Layers & display | matches | `LayerEditor` and `SpeakerPicker` are `ChorusSheet` with Material `FilterChip`/`TextButton`. "Front states" section (add/remove states) has no frame. | Preset tiles, layer rows with eye, Reduce clutter toggles, value row all match. | `ui/LayersDisplay.kt:210-300`, `:145` | S | n |
| F3.10 Member state | partial | Android picks **one** chip (`picked`) or free text and stores `{text, at}`. Figma: **multi-select** chips (Tired, Nonverbal, Busy, Low spoons, Needs quiet, Out and about, + Custom), header chips of the current states under the name, **Note (optional)**, **Clear when X stops fronting** toggle, **Who sees this** row, **Clear** + **Set state**. Only 3 built-in chips; the other three are missing. Reached by Fronting → tap member → State row → sheet (4 taps). | Sheet, not a full screen with ✓ in the bar. Chip labels are upper-case bold (`name.uppercase()`), Figma is sentence case with a ✓ on picked chips. | `ui/Switcher.kt:415-452`, `data/FrontingDisplay.kt` (`statusPayload`, `memberStatus`) | M | Pref shape only (`member_status:<id>` gains `states[]`, `note`, `clear_on_exit`, no server); "Who sees this" needs owner (Q-C). |
| F3.11 Compact view | matches | — | Simple card, "+2 nearby · Wren, Foxglove" row, undo bar all match. Hint card text is long (section 3). | `ui/Home.kt:277-296`, `:507-565` | S | n |

### F7 Friends

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Server/core? |
| --- | --- | --- | --- | --- | --- | --- |
| F7.1 Friends as a system | matches | Bottom-of-list `QuietNote` and the "as each friend shares it" caption are Figma text but fail the owner's trim rule (section 3). More row has no Friends count badge (F10.1 shows "2"). | Matches. | `ui/Friends.kt:289-309`, `ui/AppNavigation.kt:43` | S | n |
| F7.2 Friend, a system | matches | "3 of 4 members visible" / "1 private" face deliberately not built (Q24, default stands). | Hero, pills, buttons, Around now ring faces, members faces, Between you rows match. | `ui/FriendPage.kt:75-210` | — | decision Q24 |
| F7.3 Friend, a person | matches | Explanatory grey note under buttons (trim). | Matches. Posts tab uses `SharedPostPreview`. | `ui/FriendPage.kt:156-161` | S | n |
| F7.4 Friends, just me | matches | — | Systems / People groups with counts match the XML. | `ui/Friends.kt:312-322` | — | n |
| F7.5 System seen by a person | matches | Member rows (not faces) for a person viewer: done. Footer note (trim). | Matches. | `ui/FriendPage.kt:167-178` | S | n |
| F7.6 DM, just me | partial | DM header "Foxglove is fronting" exists (`Chat.kt:855`, `data/FriendFronts.kt`). Sender line with the speaking layer next to the name ("Amarynthe co-conscious", "Foxglove steering") is missing; this is a cross-account leak of layers unless the follower already sees them (owner question Q-A, default skip). "Direct chat between Ren and The Lanterns" pill not checked (chat agent, F5). | — | `ui/Chat.kt:847-856` | S | y if built (message card would carry the speaker's layer as shown to this follower) |
| F7.7 Requests / accept | matches | — | Accept drawer matches (radio presets, Bucket row, "Accept as Gentle"). Delay/rounding note under it is text to cut. | `ui/Friends.kt:530-585` | S | n |
| F7.8 Sharing buckets | matches | Top note (trim); drawer subtitles repeat it. | Bucket tiles, who · how much, New bucket, Everyone else rows match. | `ui/FriendSharing.kt:52-129` | S | n |
| F7.9 Sharing with one friend | matches | — | What they see right now, segmented, buckets chips, toggles, rows, Remove follower all match. | `ui/FriendSharing.kt:131-285` | — | n |
| F7.10 Bucket advanced | matches | — | Name + 7 colour dots inline, preset segmented, Details rows. Icon picking is one extra tap (tile → drawer); fine. | `ui/FriendSharing.kt:376-500` | — | n |
| F7.12 Notifications from one friend | matches | — | Matches (switches, About chips, Layers chips, delivery segmented, Quiet hours, Mute, New posts). Trim texts only. | `ui/FriendNotify.kt` | S | n |
| F7.13 Notifications on this device | **missing** | No screen. Android has three channels only (Switches, "Mentions, replies and DMs", Post alerts; `data/Push.kt:414-416`) and device pushes ring for everything. Figma: status card ("Notifications are on · Arriving through ntfy · last one 2 min ago", **Send a test**, **Troubleshoot**), then per-kind toggles: Friends' switches, Mentions, Direct messages, Replies, New posts you asked for, Our own switches, and one Android channel per kind so sound/vibration can be set in system settings. Today "Send a test notification" and the ntfy choice sit in the long Settings screen (`ui/SettingsScreen.kt:212-300`). | New drawer-styled screen: status card, 6 `ToggleRow`s, grey note (cut it). | `ui/SettingsScreen.kt:212`, `data/Push.kt:410-420,425-520` | M | Client-only gating is possible: payload already carries `t` and `kind` (`message` kinds, `switch`, `post`, `own_switch`; server `activity.rs:728`, `notifier.rs:843`). Mentions vs DMs vs replies need `kind` on message pushes: confirm it distinguishes them, else add a field (small server change). |
| F7.14 Lock screen | partial | Push shows: MessagingStyle with a **Reply** action only (`Reply.action`, `data/Reply.kt:63`). Missing: **Reply as…** (choose member), **Mark read**, "Replying as Foxglove" chip, a **Digest** notification ("Today at Driftwood"), channel subtitles ("Chorus · Switches · now"). Switch pushes have no actions. | Notification content matches (title/text/avatar). | `data/Push.kt:430-530`, `data/Reply.kt` | M | n (Mark read = existing `inbox.read:` pref, written from a receiver) |

### F8 Stage (the biggest gap)

Android's whole Stage is one `ChatStage` composable (`ui/ChatStage.kt`, 361 lines): text "Back / Capture", an
"Advanced" toggle that reveals a scrolling stack of `JournalChoice` chips, an inline name field.
It has none of the Figma's structure: no bottom 5-tab drawer (Select · Filters · Style · Redact ·
Names & times), no round check marks, no segmented controls. Web's `Stage.svelte` is equally old (section 6).

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Server/core? |
| --- | --- | --- | --- | --- | --- | --- |
| F8.1 Start from a message | partial | Message menu has "Select in stage mode" (`Chat.kt:2079`); chat overflow has "Stage mode / Pick messages to show as a scene" (`Chat.kt:923`). Figma: one row **Stage…** (camera icon, teal-wash row, chevron, subtitle "Make a screenshot-ready view of this chat") between Select and Delete. | Row styling only. | `ui/Chat.kt:923`, `:2079` | S | n |
| F8.2 Select | partial | Header "Stage · #general · 3 of 5 picked" (Android: "Stage · #name" + sentence "Tap messages to pick them..."); **Capture** as a teal pill top right. Round check circles on each message (Android prefixes "✓ " into the name text); unpicked messages dim. Segmented **Hidden / Context only / Shown** (Android: chips "Fold / Hide / Show"), **Pick all / Pick none / Only Foxglove** chips (missing). | Bottom drawer with 5-icon tab bar. | `ui/ChatStage.kt:122-148`, `:288` | L (shell shared by all tabs) | n |
| F8.3 Filters | partial | Only these members (member chips) exists as "Only X" chips inside Advanced; **Hide reactions**, **Hide thread previews** missing; **Hide reply bars** exists; **Crop reply chains** is chips "All / No / Direct / 2 deep" (Figma: − 2 + stepper). Members are shown as faces + names, with the author list built from loaded messages. | Switch rows, stepper. | `ui/ChatStage.kt:190-198` | M | y: `hide_reactions`, `hide_thread_previews` in the stage definition and `stagePlan` (+ fixtures) |
| F8.4 Style | partial | Six presets exist (`style`). Preset **thumbnails** (a mini drawing each) missing; Theme is Auto/Light/Dark (Figma Dark / Light / **Custom**); **Width Phone / Square / Wide** missing on Android (web has it; Android refuses saved stages with width ≠ phone, `StagePlan.supported`). | Thumbnail grid, segmented rows. | `ui/ChatStage.kt:100-115`, `data/StagePlan.kt:74-76` | M | n for width (field exists); **Custom** theme needs a palette field (y, small) |
| F8.5 Stage a post | **missing** | Android has no Stage for Journal posts/entries at all (grep: `Stage` only in `Chat.kt`). Figma: card of the post with replies indented, "2 deeper replies hidden", Replies panel (Reply levels stepper, Only replies from member chips, Hide like and repost counts). Web has `PostStage.svelte` (no replies, no depth). | Card style with reply thread. | new, entry from the post's ⋯ menu (`ui/JournalActions.kt`) | L | y: stage definition for a post with replies (`post_id`, reply depth, hide counts) and a `stagePlan` for posts |
| F8.6 Redact | partial | Exists: replace names (chip "Hide real names"), conceal avatars, hide times (as a Times chip), hide channel name, conceal attachments. Missing: **black out words** by press-and-drag over the preview; labels differ ("Replace names with Member A, B…", "Plain grey pictures", "Hide times", "Hide the chat name", "Blur photos and files"). Placeholder names "Member A" are core's. | Five `ToggleRow`s. | `ui/ChatStage.kt:156-167` | M (toggles) + M (word black-out) | y for black-out (spans stored in the definition, rendered by core plan or client) |
| F8.7 Names & times | partial | Fake names: per-author field only under Advanced, no colour dot (core refuses colour: `StagePlan.supported`). Times: Android has Real / Hide / Shift (minutes) / Start at; Figma has **Shift all / Start at / By hand** with a clock chip "09:00" and "Gaps between messages are kept", **Hide dates** toggle, and the teal note "Only in this stage. Real messages and names don't change." (trim). **By hand** and **Hide dates** missing. | Rows "Amarynthe > [Rose] ●". | `ui/ChatStage.kt:168-189` | M | y: `time.mode: "hand"`, `hide_dates`, fake name `color` supported by core plan (`stagePlan`) |
| F8.8 Capture | partial | Android hides the status bar and shows a top-right "Done" after 2 s (`ChatStage.kt:100-109`, `:335`). Figma: a top pill "Take a screenshot now · long screenshots work too" that fades, bottom **✓ Done** pill, a `# plans` channel title row. | Pill styling; "Done" position. | `ui/ChatStage.kt:329-340` | S | n |
| F8.9 Save / reopen | partial | Android: name field + "Save" always on screen, saved stages as `TextButton`s in a row (remove only in Advanced); a stage with a saved width or colour reads "name · web view" and can't be opened. Figma: **Save stage** drawer (name field, "Saved stages" list with icon per kind, summary "#general · 4 messages · Minimal", ⋮ per row), saved stages also for a post ("Journal post · Card · replies cropped"). | Whole drawer. | `ui/ChatStage.kt:200-240` | M | n (lists `model.savedStages`); summary built client-side |

### F11 Notifications

| Frame | Status | Feature gaps | Visual gaps | Where | Size | Server/core? |
| --- | --- | --- | --- | --- | --- | --- |
| F11.1 as a system | matches | Review row says "Check which one is right"; Figma "Phone says Foxglove, laptop says Amarynthe" (needs the two device names and the two members, available from the review card). **Review** opens History, not a Review screen (F3.6). Friends rows open the Friends list, not that friend (section 4). Stacked faces for "Kit and Juniper" show one face. | Time on Needs-you rows is hidden (`!item.needsYou`), Figma shows "1h"/"09:13" with the dot. Needs-you rows are tinted individually; Figma tints the whole section as one block. | `ui/Notifications.kt:105`, `:314`, `data/Inbox.kt:159` | S–M | n |
| F11.2 just me | matches | Same as above. Person's "Accept" without drawer: done. | Matches. | | — | n |
| F11.3 hold for options | matches | Subtitle uses the category ("Friends"), Figma uses the kind ("Friends' switches"). "Notifications from X" opens the Friends list (deep link, section 4). | Matches. | `ui/Notifications.kt:176-205` | S | n |
| F11.4 read as | partial | Rows show only "N unread" / "caught up" as subtitle. Figma: name + **layer line** (steering / co-conscious / nearby) at left, **unread count right-aligned** in teal ("caught up" grey), ✓ on the chosen row, and a footer sentence. Sorted in the order the system shows them (Android: `model.active`). | Row layout; avatar 40 not 32. | `ui/Notifications.kt:211-217` | S | n |

F11.5 (Web notifications) is in section 6.

## 2. Android screens with no frame (my scope + shell sweep) and the pattern they should adopt

Already out of my scope and covered by others: F9.1 Fronting insights (`ui/InsightsScreen.kt`), F10.x Settings/More/Update,
chat (F5), Members/Journal (F4/F6), Home/Onboarding (F0/F1/F2). I note cross-references only where a trim item sits there.

| Screen | Where | Why it is off | Adopt |
| --- | --- | --- | --- |
| Front editor (per front) | `ui/Fronts.kt:291-340` | `ChorusSheet` + Material `FilterChip`s, text buttons "Save" beside fields, "Follows" chips, five chip rows under "Who counts for what" | F3.9: a `Drawer` with `FormCard`: name/description `ChorusField`s saving on Done; Icon row; **Follows** as a `FormValueRow` → `ChoiceDrawer`; **Who counts for what** as five value rows with a summary ("Steering + co-con") → layer multi-select drawer, the same one as F3.9's "Suggested speakers" (it already exists as `SpeakerPicker`). |
| Layer editor, Speaker picker | `ui/LayersDisplay.kt:210`, `:273` | `ChorusSheet`, `FilterChip`, `TextButton` | `Drawer` + `FormCard` + `DrawerButton`; Look as a 4-tile swatch row like the preset tiles; Colour dots as in F7.10. |
| Place sheet "Also present in" and State row | `ui/Switcher.kt:284-360` | see F3.3 | F3.3 Drawer + `FormCard`; state as a value row ("Tired · Nonverbal") |
| Switch sheet options | `ui/Switcher.kt:216-243` | inline expansion with Material chips | F3.5: **Change** opens a Drawer: Time value row (`DateTimeDrawer`), Note field, Alerts `ChoiceField` (Usual / Silent / Alert now / Delay) |
| Widget pin arranger | `ui/Switcher.kt:456-495` (`WidgetPinsSheet`: "★ name ↑ ↓ Remove") | text arrows, `ChorusSheet` | F3.4/F3.9: `ReorderColumn` rows with grip handle and eye/× inside a `Drawer`; opened from the quick-switch widget's settings, not from the Switch sheet |
| Quick-switch launcher (search shortcut) | `SearchActivity.kt:130-282` | Pre-F3: places everyone on layer `"front"` of Body only (`multiSwitchEntries`), "Multi ✓" text toggle, "Add"/"Remove" text, "Switch to…" | F3.2: a launcher-sized Switch drawer: search field on top, the same face grid with layer tags, Replace/Add segmented, **Switch** summary button. Reuse `Switcher` with `searching = true`. |
| Quick-switch widget tiles | `widget/QuickSwitchWidget.kt` (Replace/Add mode chip, folders) | Body-only, level `front` | Keep as the "tap a face" widget; make Replace/Add place on the innermost layer of Body (already) and say nothing else; no text beyond the face names. Optional: pinned layer. |
| History line actions, Saved entries | `ui/History.kt:158-178`, `:240-285` | `ChorusSheet`, text links "Edit time / Undo / Redo" | `MenuDrawer` (`MenuRow` Edit time, Undo this switch, Open note); Saved entries as F3.7 sentence rows with a ⋮ |
| Log-a-past-switch member picker (`PickSubjects`) | `ui/LogPastSwitch.kt:221` | `ChorusSheet` + old `SearchField` | `Drawer` with `ChorusField` search and `FormCard` list |
| Fronts "Removed fronts" | `ui/Fronts.kt:207-217` | plain rows with `TextButton` Restore | `FormCard` rows with a "Restore" `SmallButton`; collapse under a value row when none |
| Fronting header overflow/⋮ menu for the front | `ui/Home.kt:290-300` | exists (`MenuDrawer`) | fine; matches F3.1's ⋮. Keep |
| Generic shell top bar | `MainActivity.kt:391-401` | shows title + "Chorus · synced" + three icons (search, **swap**, settings) on More, Members list, Settings, Insights, Search | F10.1/F7.1 header: reader face (36 dp) + title + one-line subtitle "The Lanterns · reading as Foxglove", search icon only. Drop the **swap** icon (the Fronting screen has the Switch FAB; the More page doesn't need it). Show offline as a small dot/chip only when not live, not "Chorus · synced". |
| Share chooser ("Share into Chorus") | `MainActivity.kt:~520-560` (`ShareChooser`) | plain `Text` buttons ("Post as…", "Send to channel…", "Cancel") | F5.20 Forward drawer: `Drawer` with a `FormCard` list: Journal (post/entry), then channels as faced rows; no sentence. |
| Bottom navigation chooser | `ui/NavigationSettings.kt:12-89` | Material `Button`, `TextButton`, `IconButton`s with rotated chevrons for reorder, "Preview" box | `Drawer` + `ReorderColumn` of `FormCard` rows (grip, icon, name, ×), "Add" rows with `+`, one `DrawerButton`. The preview strip stays but shrinks. |
| Search (global) | `ui/ContentSearch.kt` (inside generic shell bar) | F5.9 is the chat's search; scopes Journal / Saved / Switches have no frame | Reuse F5.9 header (back, field, scope chips) once the chat agent ships it; trim copy (section 3). Ownership: chat agent for the shell, me for the copy. |
| More page rows | `ui/AppNavigation.kt:33-54` | other agent (F10.1): "Go to" / "Settings" groups, Friends count badge | cross-ref only. Note the F3-specific rows ("Fronts and layers", "Front history", "Fronting insights") duplicate buttons already on Fronting; Figma More has just Home, Members, Friends, System board and polls, Fronting insights. |
| Front widget configure | `widget/WidgetConfigureActivity.kt` | already a Drawer | fine |
| Unread widget | none | F3.8 | new `AppWidgetProvider` (RemoteViews) "Chorus · N unread for {reader}" |

## 3. Text to trim (be decisive)

Rule used: delete a sentence if the control, label or preset name already says it. Keep one-line
confirmations before irreversible or outward actions. Where Figma draws the sentence and the
owner's trim instruction disagrees, trim it (flagged "F" below: Figma has it).

**Fronting**
- `ui/Fronts.kt:175` "Each front has its own layers. Track who's in the body, who's posting in Chorus and who's in your inner world, without mixing them up." (F) delete the paragraph.
- `ui/Fronts.kt:171` subtitle "Separate places someone can be present" (F) delete (keep title only, or "N fronts").
- `ui/Fronts.kt:318` "A following front mirrors its source. Changing it by hand keeps your change until you choose Resume following." delete (the card tag says "Follows Body" / "Changed by hand" and the Resume link appears when relevant).
- `ui/Fronts.kt:334` "Nobody is selected." delete (the selected chips show it).
- `ui/Fronts.kt:163` confirm text "Adding “X” turns on separate fronts for this system. Body keeps its history and who's in it; this can't be turned off again, though a front can be removed." keep the confirm (irreversible) but cut to: "Turns on separate fronts. Body keeps its history. This can't be undone."
- `ui/Home.kt:284` Simple view hint "Only one layer is shown, empty layers and other fronts are hidden. Switch back to Detailed at any time." (F) delete the sentence; keep "Simple view" + "Layers & display" link, dismissible.
- `ui/Switcher.kt:194` "Tap to place someone. Tap again to move them outward." (F) delete; the faces show their layer tag and ring.
- `ui/Switcher.kt:320` "Tired, nonverbal, busy, or your own words" delete (show the current state or nothing).
- `ui/Switcher.kt:334` "Follows Body · changing it stops following" shorten to "Follows Body".
- `ui/Switcher.kt:429` subtitle "· only your system sees this" delete (singlets never see this screen; systems know).
- `ui/History.kt:394` "Keep an accurate history across your devices." delete; `:395` keep the title only: "Two devices switched within a minute".
- `ui/History.kt:247` "Edit, undo or restore what was recorded. Past edits keep following active." delete.
- `ui/History.kt:165` "This change came from the front X follows; edit it there." shorten to "Comes from X".
- `ui/History.kt:214` "Loading saved history…" make it a thin progress bar, no text.
- `ui/History.kt:409` error "Tap the one that was right, then Keep this one." goes away with F3.6's radio list.
- `ui/LogPastSwitch.kt:144` "Logging the past doesn't change who's fronting now." (F) delete the note; the screen title says it.
- `ui/LayersDisplay.kt:87` subtitle "· each front can be set up differently" (F) delete.
- `ui/LayersDisplay.kt:129` "Names and order are shared by every front; renaming or reordering never rewrites history." delete.
- `ui/LayersDisplay.kt:147` "States like Blurry or Dissociated can be switched in like a member." delete.
- `ui/LayersDisplay.kt:243` "Shown as its own row instead of inside the nested layers" delete (label "List separately" suffices).
- `ui/LayersDisplay.kt:282` "Who in X the composer offers as speakers." delete.
- `ui/AppNavigation.kt:38-52` the second line on every More row ("Your members and profiles", "Current presence and quick switching", "Channels and direct messages", ...) F10.1 keeps a one-line subtitle: keep it only where it carries a number (Members: "4 members", Friends: "2 requests") and drop the rest. (F10 agent owns the page; this is the rule.)

**Friends**
- `ui/Friends.kt:307` footer "Times are as each friend shares them. Some round to the quarter hour or show only “this morning”." (F) delete; `:299` "as each friend shares it" (F) keep that caption instead, it carries the same meaning in five words.
- `ui/Friends.kt:329` "Nobody follows you yet. Share your handle so friends can ask." → "No followers yet".
- `ui/Friends.kt:361` "Follow someone by their handle with the button at the top. They choose what you see." → empty state: icon + "No friends yet" with the Follow button.
- `ui/Friends.kt:430` "No requests right now." → "No requests".
- `ui/Friends.kt:510` Follow drawer subtitle "They’ll get a request and choose what you see" delete; `:515-516` FormNote "Once they accept, you see what they share with followers. Following them doesn’t share anything of yours." delete (the second half is the one worth keeping if anything: "Shares nothing of yours").
- `ui/Friends.kt:534` "Choose how much they hear. You can change it any time." (F) → delete "You can change it any time."; `:567` "A delay hides when you switched as it happens. Rounding hides the exact time afterwards. Use both for real privacy." (F) delete (preset rows already say "5–20 min later, rounded to 15 min"); `:571` drawer subtitle "Friends in a bucket hear the same things" delete.
- `ui/FriendPage.kt:157` "$name is one person, so there’s no fronting to show. You see their posts, journal and messages." (F) delete (the tabs show it, and the list row already says "One person, so nothing to front").
- `ui/FriendPage.kt:164` "Once they accept, you’ll see who’s around as they share it." delete; `:174` "$name haven’t shared any member profiles with you yet." → hide the section.
- `ui/FriendPage.kt:177` "$name choose what they share and when. You can make their notifications quieter, never louder." (F) delete; the same idea is on the notify page (below).
- `ui/FriendPage.kt:196` unfollow confirm "You stop hearing their switches and seeing what they share with followers. They aren’t told." keep the confirm but cut to "They aren’t told."; `:366` "You see what $name chose to share with you." delete; `:207` drawer subtitle "Spaces you and $name are both in" delete.
- `ui/FriendSharing.kt:55` "Friends in the same bucket hear the same things. A friend in two buckets gets the more open one." (F) delete from the page; keep the second half as the subtitle of the Add-to-bucket drawer only ("Most open bucket wins" at most). `:214` drawer subtitle, `:119` "Where accepting a request starts; you can still pick another", `:224` "Each member chooses who hears when they front", `:237` "Anything set here wins over their buckets", `:326` "Each is inherited from X until you change it", `:361` "They see Body only until you allow more. Delays and privacy rules still apply.", `:539` "Buckets and each friend’s own settings can share more or less than this..." and `:192` "No buckets yet. Make one under Sharing." all go. Keep `DETAIL_NOTES` (the one-liner under each detail, `:~46`): they are the labels F7.10 draws.
- `ui/FriendSharing.kt:496` delete-bucket confirm "Anything shared only with it goes back to just you. The friends in it stay friends." keep (outward effect).
- `ui/FriendNotify.kt:85` subtitle "Only you see these settings" (F) delete; `:89` "$name decide the most you can hear. Here you can only make it quieter." (F) delete; `:95` "As often and as exactly as $name allow" → "As $name allow"; `:122` "Tap it again to change the time." delete (show the time as a value row); `:147` "Your time. Their switches wait or are skipped." delete; `:160` "Their switches wait until then; nothing is lost" delete.

**Notifications and push**
- `ui/Notifications.kt:211` Read-as subtitle "Each member keeps their own unread marks. Marks never leave your system." (F) → delete; the screen header already reads "Reading as Foxglove".
- `ui/Notifications.kt:220` "Everything here also reaches your devices as set in Notification settings." delete (the "Notification settings" row beneath is the pointer).
- `ui/Notifications.kt:136` "Nothing waiting. Mentions, replies and friends’ news land here." → "All caught up".
- `ui/Notifications.kt:205` "From $name" delete.
- `ui/Notifications.kt:~99` "Couldn’t check for new notifications. Showing what this device has." → "Offline. Showing saved" (small chip, not a paragraph).
- `ui/Notifications.kt:196` menu row subtitle "Who, which layers, how often" (F) keep (it is the only label of what the page holds)... shorten to nothing if the title already reads "Notifications from The Junipers".

**Stage** (rewrite removes most; list so nothing survives)
- `ui/ChatStage.kt:130` "Tap messages to pick them. With nothing picked, all show." / "N picked · tap to add or remove." → title "3 of 5 picked" only.
- `ui/ChatStage.kt:133-135` "N picked messages are not loaded. Load older messages or clear those picks." → a "Load older" button with a count badge.
- `ui/ChatStage.kt:254` "No messages to stage in this channel." → empty state: "No messages".
- `ui/ChatStage.kt:256` "Load 100 older messages" → "Load older".
- `ui/ChatStage.kt:~200` "All authors shown" / "Only selected authors shown" delete.
- Figma's own F8.2 sentence "Context-only messages shrink to one faint line, so the chat still makes sense without showing them." (F) and F8.7 "Only in this stage. Real messages and names don't change." (F) and F8.6 "To black out words, press and drag over them in the preview." (F: keep as a hint on first use only), F8.9 "Saves the setup, not a copy of the messages. Deleted messages drop out." (F, keep: one line in the save drawer, it states a real limit).
- `ui/Chat.kt:923` "Pick messages to show as a scene" → none (Stage… row as Figma, subtitle optional).

**Shell**
- `MainActivity.kt:395` "Chorus · synced" / "Connecting…" / "Offline · saved on this device" → only when not live, as a chip.
- `MainActivity.kt:~531` "Choose where to prepare this draft. You can review it before posting or sending." delete; "Text · 2 files" line stays as the drawer subtitle.
- `ui/NavigationSettings.kt:45` "Choose up to four destinations. More always stays at the end." → "Up to four" (or nothing: the 5th add button is simply disabled).
- `ui/ContentSearch.kt:174` "Try words or from:, in:, has:, before:, after:, is:pinned." (hint) → placeholder "Search"; operators stay in a "?" drawer for anyone who wants them. `:188` "Type to search this scope. Chats use the current front; Journal and Saved contain posts and entries." delete; `:209` "Showing the newest 100 local matches. Narrow the search for more." → "100+ results".
- `SearchActivity.kt:220` "Switch to…" fine; `:169` "Quick switching is for systems" fine (singlet).

## 4. Taps to cut

1. **Notification → friend page.** A friends-switch / follow / post row calls `routes.onFriends()` (`ui/Notifications.kt:105`), which only selects the Friends tab; the user must find the friend again (3–4 taps). `FriendsScreen`'s route stack is internal (`stack`, `rememberSaveable`). Fix: add an `openRoute: String?` parameter ("friend:<account>", "notify:<follow>") so `onFriends(route)` pushes the page. Same for hold menu "Notifications from X" (`:196`): today 4 taps (hold, pick, find friend, open Notifications row), after: 2.
2. **Review.** Needs-you "Review" → History → find the card → tap one device → Keep this one = 4–5 taps. With the F3.6 screen: Review → radio default "Both are right" → Save = 2 (3 if changing). Also show the Review row on Fronting (named fronts show no review cards on Fronting, `Home.kt:260-272` handles only unconfigured accounts) as a one-line card.
3. **Set a member's state.** Fronting → tap member → PlaceSheet → State row → chips → Set = 5 taps. Make the member's ⋮ (currently inert, `Home.kt:403/545`) a `MenuDrawer`: Place in layer…, Set state…, Remove from front. State = ⋮ → Set state → chips → Set (4); a one-chip state with auto-apply on the first chip when no note is wanted: 3.
4. **Add a front.** "+" in the tab strip opens the Fronts page where "New front" must be tapped again (`Home.kt:242`). Open the New front drawer (name + template chips) directly: saves one tap and one screen.
5. **Stage from a message.** Message hold → "Select in stage mode" → Stage opens with that one message picked: fine; but then rename a person (Advanced → scroll to the author → type), redact (Advanced → scroll), save (type name + Save). In the F8 tab drawer: Names & times tab is one tap away, Redact one tap, Save one tap + name. Also "Pick all" / "Only Foxglove" chips replace tapping N messages.
6. **Accept a request.** Notifications row "Accept…" → drawer → Accept: 2 taps, good. Friends › Requests: same. No change.
7. **Follow someone.** Friends header ➕ → type handle → Send: 3 taps incl. keyboard; good. Remove the FormNote; keep.
8. **Switching.** FAB → grid → tap faces → Switch = n+2; changing "Starting now" is 2 more. Fine. Quick-switch launcher (`SearchActivity`) takes search → tap → but places on the front layer only; to place on a layer needs opening the app. After adopting F3.2: launcher shows layers. Saves leaving the launcher.
9. **Quiet a friend.** Hold → Quiet for an hour: 2 taps. Fine.
10. **Read as another member.** Tap the header subtitle or the avatar: both work, good.
11. **Friend → what they see.** Friends › friend › What you share with them › card at top: 3 taps. Could also show "What they see right now" (one line) on the friend page's Between you row subtitle. S, optional.
12. **Notification settings per kind (F7.13).** Today: More → Appearance & preferences → Notifications section (scroll). After: Friends/Notifications header sliders → "This device" row: 2 taps. Put a "This device" row at the top of the Notifications "What shows here" drawer.
13. **Front history from More.** More → Front history (also a bottom tab option): history is on Fronting's top bar (1 tap); More rows "Front history", "Fronts and layers" duplicate it. Remove both from More for systems (still reachable via the Fronting bar) = fewer rows.

## 5. Conflicts and owner questions

**Conflicts (DECISIONS beat Figma; noted, not gaps):**
- F3.9 PNG (Simple / Layered / Detailed) vs D-122 and the XML (Simple / Detailed / Complete): XML and D-122 agree, nothing to do.
- F7.2 "3 of 4 members visible" and the "1 private" face vs D-101 and Q24: stays "N members visible", no private face (the server sends only shared members).
- F7.6 per-message layer word ("Amarynthe co-conscious") vs NOTIFICATIONS §5 (a follower mustn't learn more or earlier through the API than through notifications): see Q-A.
- F3.10 "Who sees this · Same as Foxglove's fronting status" implies friends can see a member's state; D-122 says `member_status:` is private to the account's devices. See Q-C.
- F7.13 (per-kind ringing on this device) is "not built" in D-128 by choice of scope, not by conflict; it is a client-only feature.
- F7.14 "Reply as…" is a notification action choosing the speaker; D-087/D-098 chat rules decide who may speak; it must offer only those `Reply.speaker`-style members the channel allows.

**Owner questions (defaults I would assume, so no one is blocked):**
- **Q-A** (F7.6) Show the speaker's layer ("co-conscious", "steering") beside a system's name in a DM with another account? **Default: no.** It would reveal a layer the follower may not be allowed to see. If yes: only the words the follower's preset already shows in notifications (`Friends.levelWord`).
- **Q-B** (F3.8) Lock-screen "F · A · N · V" chip: Android phones can't draw it. Offer an opt-in ongoing low-priority notification "Fronting: Foxglove, Amarynthe" (title: front names; tap = Switch)? **Default: no, not built.** (It would be visible on a locked phone to anyone.)
- **Q-C** (F3.10) Do member states (Tired, Nonverbal, notes) ever reach friends ("Who sees this")? **Default: no.** Build multi-select chips, the note and "Clear when stops fronting" privately (account pref `member_status:`); the "Who sees this" row shows "Only us" and is not a control.
- **Q-D** (F8) Stage "Custom" theme, "By hand" times and "black out words" need new fields in the saved stage definition (`StagePlan` and core `stagePlan`, shared with web). Approve extending the definition (additive, old saved stages still open)? **Default: yes**, as core fields with fixtures.
- (Not a question, a rule I applied) Sentences that Figma itself draws (F7.1 footer, F7.3 note, F7.9, F11.4 footer) are trimmed per the owner's "no textual explanations". If you would rather keep any Figma sentence, say so; otherwise they go.

## 6. Web (`web/src`), shallow (grep and file sizes, no browser)

- **F8 Stage:** `lib/ui/Stage.svelte` (449 lines) and `PostStage.svelte` (74) are the old panel UI: select/theme/width `<select>`s and checkboxes; no bottom tab drawer, no filters (hide reactions, thread previews, crop stepper), no redact words, no by-hand times, no Save drawer; PostStage has no replies or depth (F8.5 diverges badly).
- **F3.10 Member state:** `PlaceSheet.svelte:100` has the same single-chip state as Android (no multi-select, note, clear-on-exit, who-sees).
- **F3.6 Review:** `Reviews.svelte` uses "Keep both" buttons, not the F3.6 radio screen with Later / Save to history.
- **F3.11:** `Home.svelte:201` repeats the long Simple hint text.
- **F3.8:** no widget on web (not applicable); no unread widget.
- **F7.13 / F7.14:** web has Settings › Notifications › This browser with "Send a test" (`Settings.svelte:137`) but no per-kind toggles and no service-worker notification actions (Reply / Mark read / Reply as…).
- **F7.1–F7.12, F11.1–F11.5:** present under `#/people` and `#/notifications` (UI19/UI20); not re-audited here. Web shares every text-trim item in section 3 (same copy: grep the strings).
- **F7.6:** same as Android (no layer word, by default).

## 7. Proposed Android slices, each shippable alone, biggest value first

1. **Copy and affordance sweep (S, 1 day).** Every trim in section 3 on Fronting/Friends/Notifications; make member ⋮ a `MenuDrawer` (Set state / Place / Remove); Needs-you rows show their time; "+" opens New front directly; generic shell top bar (reader face, subtitle, no swap, no "synced"). No new behaviour; unblocks the owner's "no explanations" goal at once.
2. **Notification deep links (S).** `FriendsScreen(openRoute)`; Notifications rows, hold menu and push taps land on the friend page / notify page / bucket; review row says "Phone says X, laptop says Y".
3. **Stage shell (L).** Rewrite `ChatStage` as F8.2/F8.8/F8.9: bottom 5-tab drawer (Select, Filters, Style, Redact, Names & times), round checks, segmented Hidden / Context only / Shown, Pick all/none/Only X, Capture pill + Done, Save drawer with the saved list. Uses existing definition fields only (everything Android can already do, in the new UI). Includes F8.1 "Stage…" row.
4. **Stage definition additions (M, core + web).** hide_reactions, hide_thread_previews, width on Android, Custom theme, time "by hand", hide_dates, fake-name colour, black-out spans; `StagePlan` and core `stagePlan` + fixtures; Android UI for them; web follows later.
5. **Stage a post (L).** F8.5 on Android (entry from the post's ⋯): card with replies, levels stepper, only-replies-from, hide counts; saved with `post_id` as web does.
6. **Member state screen (M).** F3.10: multi-select chips (6 built-in + system's custom), note, clear-on-exit, "Only us" row, header chips; show multiple states on Fronting rows; pref shape additive.
7. **Review screen (M).** F3.6 as its own page (radio list, Later / Save to history), opened from Notifications, Fronting card and History; remove the inline card from History or keep as a list row.
8. **Remaining sheets to drawers (M).** Front editor with value rows, Layer editor, Speaker picker, Place sheet, Switch options, History actions, PickSubjects, Widget pins, Share chooser, Navigation chooser → `Drawer`/`FormCard`/`MenuDrawer`; no `ChorusSheet`, Material chips or text buttons left in my screens.
9. **Per-kind notifications on this device (M).** F7.13 screen (status card, six toggles, one channel per kind, Send a test, Troubleshoot) + F7.14 actions (Reply as…, Mark read, Digest).
10. **Quick-switch launcher and unread widget (M).** `SearchActivity` becomes the F3.2 drawer with layers; F3.8 unread widget.
11. **Read-as and row polish (S).** F11.4 layer line and right-aligned count; stacked faces; section tint; "Phone says…" detail; F3.3 "Can be picked as speaker" subtitle; Friends count badge on the More row.
