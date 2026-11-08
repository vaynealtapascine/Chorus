# UI21 audit: Android Chat vs Figma F5

2026-11-08, read-only audit (Sonnet 5.5) for UI21. Figma cache: `F:\DunBuild\figma-cache\frames\F5.*`.
Code read: `ui/Chat.kt` (all), `ChatChrome`, `ChatDetails`, `ChatAttach`, `ChatSpacePanel`, `ChatFoldersScreen`,
`ChatPermissions`, `ChannelEditor`, `DirectMessagePicker`, `ContentSearch`, `Drawers.kt`. `ChatStage` is F8, skipped.
Older gap lists (`ui5-android-2026-10-28.md`, `ui7-android-polish.md`) were checked: still open from them are
slice 8 (handover and notes), delivery ticks, and the F5.8 caption row. Everything else in them has landed.

Sizes: S under half a day, M about a day, L several. "Core/server" is only filled where something outside
`android/` must change.

Verdicts for the 23 Android frames: **5 match, 15 partial, 3 missing** (desktop F5.11/F5.12/F5.21 are counted apart).

## 1. Frame by frame

### F5.1 Chats · All folders (partial) · `ChatChrome.kt:106`
| | Gaps |
| --- | --- |
| Feature | (a) Own-message delivery ticks beside the time (✓✓ teal) are absent. (b) Handover (document icon) and Notes to ourselves (bookmark icon) rows do not exist as kinds (see F5.15/F5.19). (c) Mute is not in the row long-press menu (`ChatChrome.kt:274`: Pin, Arrange, Folders, Archive, Delete); Figma shows muted rows prominently and a one-tap Mute is the obvious missing action. (d) Strip avatars (`:178`) are not tappable; tapping one should set "reading as". |
| Visual | (a) FAB is an `ExtendedFloatingActionButton` with the words "New chat" (`:302`); Figma is a bare 56 dp round pencil, no label. (b) Title line lacks the grey "• Garden" space suffix that tells two `general` channels apart (`:254`; `row.whose` only fills it for private chats). (c) A pencil `Tab` sits at the end of the folder tabs (`:214`); Figma has none (folders are edited from the ⋮ menu). Remove it, the ⋮ item stays. (d) Folder tab counts: Figma greys unselected pills and yellows the selected one; Android already does this (`ChatCount(count, folder == candidate)`), so only the number's meaning needs checking (Android sums unread messages). |
| Size / core | S (a,b-visual,c) · M (ticks, see F5.17) · core/server: none except ticks |

### F5.2 Internal group · General (partial) · `Chat.kt:889-1160`
| | Gaps |
| --- | --- |
| Feature | Ticks on own bubbles (✓✓) missing (F5.17). |
| Visual | Header, pinned bar (`:943`, "Pinned message 2 of 3" cycling is good), date pills, front service lines, reply quote box, layer text ("nearby", "co-conscious") and reaction chips match. Reaction chip sits inside the bubble's bottom row (`:1989`); Figma overlaps it on the bubble's lower edge. Cosmetic, S. |
| Size / core | S · none |

### F5.3 Send as (matches) · `ChatDetails.kt:56`
Matches: "Send message as…", "Fronting · Body" / "Not fronting" groups, avatar, "steering · they/them", check.
- Gap: it is a raw `ChorusSheet` with no close X or grabber styling of the D-124 `Drawer` (`:66`). Wrap in `Drawer` with `leading` face. S.
- Figma's "The Lanterns · speak as the whole system" and "Unsigned" rows **conflict with D-003** (every message has a member author) and stay out (UI7 already settled this).
- Footer "Speaking as someone doesn't change who's fronting." (`:90`) matches Figma but is explanatory: trim (section 3).

### F5.4 System aside inside a shared space (partial) · `Chat.kt:1241-1272, 1329, 1959`
| | Gaps |
| --- | --- |
| Feature | None in the data path: `system_only` visibility already works. The gap is reaching it: audience lives three taps deep (attach icon → "Who sees" tile → "Only my system" chip → close), Figma has one **Aside** pill (lock, 30 dp) in a bar above the composer. |
| Visual | (a) No audience bar "Aside · only The Lanterns will see this" above the composer (lock icon, teal-tinted `accentSoft` strip, Aside switch right). (b) Composer field gets a dashed teal border and placeholder "Aside to The Lanterns…" while on. (c) Aside bubbles are plain; Figma gives them a dashed teal outline and an "aside" tag next to the name (replace the bottom text tag `"Only this system"` at `:1959`; keep "Chosen members" as is). (d) Send button label says "Send to Only my system" (`:1445` content description) fine for a11y, but name it "Send as aside". |
| Size / core | M · none |

### F5.5 Catch up as a member (matches) · `Chat.kt:986, 1073, 1129`
Reading-as banner with Change, "N unread for X" divider, `@` and `↓` jump buttons with badges all exist and behave like Figma.
Small copy: banner says "read through yesterday"/"no saved read position"; Figma "last read yesterday". Change to "last read yesterday" and drop the "no saved read position" variant text (show "Reading as X" only). S.

### F5.6 Message actions (partial) · `Chat.kt:2003-2085`
| | Gaps |
| --- | --- |
| Feature | Nothing missing; there is too much. The drawer has up to 15 rows (Reply, Reply in thread, Reply privately, Copy text, Copy link, Pin, Reply in another chat, Quote, Forward, Select, Edit, Edit history, Select in stage mode, Not seen by…, Delete). Figma has 8: Reply, Reply in thread, Copy text, Pin, Forward, Edit, Select, Delete. |
| Visual | (a) A full copy of the message (author, 4 lines of text, date) heads the drawer (`:2013`); the message is already on screen above the drawer. D-105 only requires the date/time line: keep one line "Today, 10:41 · edited", drop the body preview. (b) Figma is a popover next to the bubble; **D-124 settled drawers**, so keep the drawer, do not chase the popover. (c) "Seen by Amarynthe, Nairon" is correctly the first row, with face stack. (d) Order should be the Figma order. |
| Fix | Primary rows = the Figma eight. Everything else (Reply in another chat, Quote, Copy link, Select in stage mode, Not seen by…, Edit history, Reply privately when shown) goes behind one **More** row (`ic_more`) that opens a second `MenuDrawer` layered 28 dp lower (D-124). |
| Size / core | S-M · none |

### F5.7 Thread (matches) · `Chat.kt:1110-1123, 1334`
Header "Thread / in General · 3 replies", parent card, "3 replies" pill, "Also send to General" (Q22 default, two messages) all exist. Only the `Checkbox` is Material's square; use `ChorusSwitch`-style small teal check (S). Nothing else.

### F5.8 Attach · photos, files, polls (partial) · `ChatAttach.kt:171`
| | Gaps |
| --- | --- |
| Feature | (a) No caption field and send button inside the sheet (Figma: avatar, text field, eye toggle "hide behind a content note", send). Today: pick photos → "Add N photos" → sheet closes → type → send = 4 taps; Figma = 3. (b) Tiles **Journal** and **Front card** do not exist. Android has Gallery, File, Poll, Warning, Who sees, Speaker. (c) Eye toggle for content warning on the caption row replaces the "Warning" tile for the photo case. |
| Visual | Header "Add to message" + "N of 10" counter vs Figma "Recent photos" + "1 selected" in teal. Tile row is colored per tile in Figma (teal, blue, red, purple, orange); Android uses one `accentSoft`. Camera cell and the pick rings match (rings are numbered, better than Figma). |
| Fix | Caption row inside `AttachSheet` that writes into the same draft state (`draft`, `cw`) and a send circle that calls the same send lambda (lift it out of `Chat.kt:1398` into a local `fun send()`). Journal tile = pick a post or entry and insert its link (D-109 already embeds links); Front card: see owner questions. |
| Size / core | M · none |

### F5.9 Search messages (partial) · `ContentSearch.kt:52`
| | Gaps |
| --- | --- |
| Feature | (a) No **Chats** tab (find a chat by name; the global list search only searches messages). (b) No **Media / Files / Links** tabs across all chats (they exist per chat in info). (c) No filter chips "From: any member · In: all chats · Any date"; the operators exist (`from:`, `in:`, `before:`) but are only discoverable through the sentence at `:174`. (d) Results are open-message cards (D-114) plus an "Open message" text button; Figma is one compact row (avatar, "Amarynthe · General • Garden", time, snippet). |
| Visual | Old page style: "Search" title and a "Close" TextButton (`:164-167`), a labelled `ChorusField`, then `JournalChoice` chips ("All chats", "Journal", "Saved", "Switches"). Figma: back arrow, search field with × in the top bar, underlined tabs Chats · Messages · Media · Files · Links, count line "4 messages found", footer "Searching this device. Older history loads when you reconnect." |
| Fix | Rebuild the top as Figma (back + field + tabs). Journal, Saved and Switches stay (D-114) as a "Journal" tab group or behind a ⋮; do not drop them. **Make the whole result card tappable** (today the only target is the "Open message" button, `:201`), delete the "Match" label and the button. From/In/Date become chips that write the existing operators into the query (`from:name`). D-114 context cards stay (DECISIONS beats Figma rows). |
| Size / core | L · none (all local; Chats tab reads `model.channels`) |

### F5.10 New shared space (partial) · `Chat.kt:1697-1721`
| | Gaps |
| --- | --- |
| Feature | Flow is name-first, then a checkbox list of every connected account (`:1702`). Figma: pick systems first (chips of the selected ones under the title "2 systems selected", search field, rows with "Kit, Juniper · Kit fronting" or "6 members · last active Monday", round checks), then a round next-FAB leads to naming. Subtitles need data the friend shares: use "N members" only when visible (Q24 default) else "a system"/"one person". |
| Visual | Lives in a bottom drawer (`ChorusSheet` at `:1613`) and not a full-screen page; no chips, no search, no shield note (the note is explanatory, skip it). |
| Size / core | M · none |

### F5.13 Reply and mention (partial) · `Chat.kt:1184-1198`
| | Gaps |
| --- | --- |
| Feature | Suggestions are a horizontal chip row of up to 8 (`LazyRow`). Figma is a vertical list above the reply bar: round avatar, "@Amarynthe" bold, role on the right ("steering", "co-conscious"), first rows "@steering — whoever steers when it arrives" and "@everyone — all members". `@front` exists (label it "@steering"/"whoever steers" via `ChatMentions`); **`@everyone` does not exist** in `ChatMentions.choices` or core `mentions.rs`. |
| Visual | Reply bar matches (icon, "Reply to X", quote line, ×). |
| Fix | Vertical `Column` list capped at 4 rows, 48 dp each, with the member's layer (`ChatPresentation.layer`) as the right-hand text. `@everyone`: see conflicts. |
| Size / core | S (list) · M for `@everyone`, core `mentions.rs` + notify rule |

### F5.14 Member direct message (matches) · `Chat.kt:889-912`
Header avatar, name, "steering now · Body"-style subtitle (`ChatPresentation.subtitle`), private subtitles all match. The Figma pill "Only Foxglove and Amarynthe can open this chat" is explanatory: **do not add**.

### F5.15 Handover notes between fronts (missing)
| | Gaps |
| --- | --- |
| Feature | Nothing exists on Android or web beyond a folder suggestion that matches channel *names* by regex (`ChatFolders.kt:97`). Missing: a Handover chat kind with a "document" row icon and "notes between fronts" subtitle; "Since Foxglove last fronted" divider (front history already available: `chorus.frontHistory`); bottom action chips **Acknowledge · Checklist · At next switch**; the service line "Foxglove acknowledged the handover · 10:30"; "Write a handover…" placeholder; a pinned "today's handover" bar (the pin bar already exists). |
| Proposal (no server change) | Role as an account pref `chat_role:<channel>` = `handover` / `notes` (the D-097 pattern: syncs to the system's devices). "Since X last fronted" from front history. Acknowledge = a reaction by the speaker on the pinned handover rendered as a service line (so D-003 stays true: no authorless message). Checklist = `- [ ]` lines rendered tappable (needs a small core markup decision, defer). "At next switch" needs deferred send in server/core: drop. |
| Size / core | L · needs a D-xxx and the owner's answer to question 1; no schema change for the proposal |

### F5.16 Shared space between systems (partial) · `Chat.kt:1161`
Same as F5.4 plus: Figma shows a bar **"Everyone here · 3 systems"** (globe icon) with the **Aside** pill at its right, directly above the composer, in every shared-space chat. This bar is the audience control. Android has no such bar; the audience is hidden in the attach sheet and only appears as a chip after choosing something non-default (`:1329`). Header uses `# general` in Figma, Android shows `general` (`:903`); add the `#` for channels. Size M (shared with F5.4).

### F5.17 Offline send and retry (missing) · no code
| | Gaps |
| --- | --- |
| Feature | (a) A pending message shows no clock; a sent one no tick. (b) A refused op shows nothing in the chat: `Chorus.syncIssues` exists (`Chorus.kt:48, 250`) and the web already lists "Not sent: …" rows above the composer (`Chat.svelte:1438`), Android shows them only in the Sync screen. (c) No "tap to retry". (d) No centered pill "Messages send when you're back online". The header subtitle "Waiting for network…" does exist (`ChatPresentation.subtitle`). |
| Proposal | Per-message state: `waiting` (clock, op still in the outbox), `sent` (one tick), `seen` (two ticks: another member of our system has a read mark past it, internal chats only; never a claim about a friend's account). Failed = the red `!` after the text plus a line "Not sent · tap to retry"; retry re-enqueues the same op. |
| Size / core | M-L · **core/FFI**: a way to list pending op ids (`pending_count` exists, `crates/chorus-ffi/src/lib.rs:302`; a `pending_ids` is needed) and a `retry_issue(id)` |

### F5.18 New message (partial) · `Chat.kt:1611-1696`, `DirectMessagePicker.kt:35`
| | Gaps |
| --- | --- |
| Feature | Figma is a full page: four action rows (New internal group, New handover note, New shared space, Message another system), then "The Lanterns" members as rows (**one tap opens that DM**), "Notes to ourselves", then "Friends' systems" rows (one tap opens). Android: FAB → drawer with three big cards (New channel / Direct message / New shared space) → Direct message → *Who's speaking* picker → pick sender → pick recipients → "Start message" (5+ taps). The sender picker step is the main offender; the default speaker (`ChatSpeaker.pick`) is right almost always and the composer avatar changes it afterwards. |
| Visual | Descriptions under each card (`:1626-1632`), "Speaking as" card and the "Private to X" toggle in the picker. Figma rows are plain: round teal-tint icon, one teal label. |
| Fix | Full-screen page, searchable (search icon top right as Figma), rows as above. Group DM = long-press or the "New internal group" row. Private-to-one-member moves to chat info's existing "Start a private chat as…" (`ChatSpacePanel.kt:103`) and to a toggle only shown when a friend row is held. "New channel" = "New internal group" (same editor). |
| Size / core | M-L · none |

### F5.19 Notes to ourselves (missing)
Feature: a saved-messages chat for the whole system: row icon bookmark, `Save a note…` placeholder, tag chips **All · #reminders · #codes · #from chats** filtering by hashtag, and "Forwarded from Amarynthe / General · Monday" cards (Android already renders `forward` cards, `Chat.kt:1904`). Missing: the chat kind (see F5.15 proposal, role `notes`), the hashtag chips (core text `tags` exist for search docs; messages need a hashtag scan, local only), and a **Save to notes** action. Best value item here: make **Forward → "Notes to ourselves"** the first destination in the forward sheet. Size L · none for the proposal.

### F5.20 Forward · check the audience (partial) · `Chat.kt:1510-1534, 1591`
| | Gaps |
| --- | --- |
| Feature | (a) No search field. (b) No comment ("Add a comment…" with send). `ChatForward.payload` writes `text = ""` (`ChatForward.kt:68`) so a comment is a client-only change. (c) No "Show who originally wrote it" toggle. (d) **Privacy:** `ChatForward.snapshot(message, channelName)` always includes the source chat's name (`ChatForward.kt:63`) and the card prints it (`Chat.kt:1913`), so forwarding from an internal chat into a shared space tells friends the internal chat's name. Figma says "The original chat name stays hidden". Omit `channel_name` whenever the target space differs from the source space. |
| Visual | Android is a text list of rows in a `ChorusSheet` (no `Drawer`, no grabber/title/close): `ChannelDisc` + title + space label. Figma: grabber, search field, 4-up grid of round faces with names, the selected one ringed with a check, a red-tinted card "This leaves The Lanterns / The Junipers and Driftwood will see it. The original chat name stays hidden." directly in the sheet (Android asks in a *second* drawer, `:1591`), a toggle row, and a composer. |
| Fix | Grid + search; selecting a destination shows the warning card inline and removes the second drawer; send button forwards. Taps: Forward → pick → send (3, today 2 or 3 with the extra drawer); the extra tap is the price of the comment and of not mis-forwarding outward. |
| Size / core | M · none |

### F5.22 Chats · Shared folder (partial) · `ChatChrome.kt:294, 302`
Gaps: (a) caption under the list (`:295-299`, 4 variants) is explanatory: **remove** (Figma has it; the owner's direction overrides). (b) The FAB on the Shared folder is a people icon that starts "New shared space" directly (Figma); Android always opens the same chooser. Make the FAB folder-aware: Shared → New shared space page (F5.10); Direct → New message page; others → New message page. Rows show a globe and "Garden" suffix (see F5.1). Size S.

### F5.23 Chats · Direct folder (partial)
Same caption and FAB points. Rows: DM with a friend's system shows globe + name + "• Garden" (the space), own-system DM shows no icon; Android shows the person's icon only for `home.kind == "dm"` via `ChatRowDisc` (`ChatChrome.kt:76`). Own-message ticks ("✓✓ 10:32") missing. Size S (ticks aside).

### F5.24 Direct message with two systems (matches) · `Chat.kt:855, 905`
"Kit is fronting · 2 members"-style subtitle via `FriendFronts.line`; bubbles with the foreign member's name and system; sides correct. Not built and not wanted: the pill "Direct chat between The Lanterns and The Junipers" (explanatory). The little "switched in" tag beside a friend's name needs a per-message front-change signal; skip.

### F5.25 Chat folders · customize (partial) · `ChatFoldersScreen.kt:40, 170`
| | Gaps |
| --- | --- |
| Feature | All Figma items exist (My folders, Create a folder, Suggested with Add, three display toggles). The Android "Compact rows" and "Show who's here strip" match; "Unread counts per member" matches. |
| Visual | (a) Figma rows: drag grip on the *left*, chevron right (folder opens its editor); Android has the grip on the right, a switch (`ChorusSwitch`) and the whole row as hide toggle for built-ins. Keep the switch (hide in one tap is faster) but move the grip left and add the chevron to custom rows. (b) Folder *editor* (`:170`) is the old style: top bar with `TextButton("Cancel")` and a filled `Button("Save")`, `DisplaySwitch` rows ungrouped. D-124: use `FormCard` + `FormDivider`, `FormValueRow`, and a Drawer or the standard form bar. (c) Section subtitles under every switch (trim, section 3). |
| Size / core | M · none |

### F5.26 Chat info · members and media (partial) · `ChatDetails.kt:99`
| | Gaps |
| --- | --- |
| Feature | (a) Quick actions are **Mute · Search · Edit**; Figma **Mute · Search · Pinned · Folder** with Edit as a pencil in the app bar. **Pinned** (list of this chat's pinned messages) and **Folder** (the "Folders…" check list, already built in `ChatChrome.kt:308`) are missing. (b) No **Polls** tab (Members · Media · Files · Links · Polls); polls live in `model.polls`. (c) Members rows lack the right-hand "caught up" / "12 unread" / "4 unread" (`ReadTracking` already gives the per-member mark: `ChatDetails.kt:194`; it only shows "Caught up" as part of the sub-line). (d) "Add a member of The Lanterns" row only makes sense for a channel kept to some members; link it to `ChannelEditor` there. (e) Info is reachable only through ⋮ → Chat info; tapping the header title does nothing (`Chat.kt:902`). |
| Visual | Whole pane scrolls, tabs scroll away with the header; make the tab bar sticky. Media grid is 3-up (Figma too). Subtitle "Internal · 4 members · only The Lanterns" (Figma) vs "Internal · 4 members". The "Each member keeps their own read position when enabled." line and the empty-state sentences go (section 3). |
| Size / core | M · none |

### Desktop frames (Android view)
- **F5.11 Shared-space permissions: missing.** Android's `ChatPermissions.kt` is the six-permission allow/deny/inherit editor (view, send, react, thread, pin, manage; `ChannelPermissions.kt:8`), a different model from Figma's toggles ("Send photos and files", "Create polls", "Invite other systems", "Member names on each message", "Our fronting status", "History before they joined", "Allow system asides"). Most of those are **new permission kinds and need server, op and core work** (and a decision, question 2). Meanwhile restyle: `FormCard` of `ChorusSwitch` rows for send/react/thread/pin for "Everyone" with the per-person override behind a row; delete the explanation paragraphs (`ChatPermissions.kt:43, 58, 60, 86, 87`).
- **F5.12 Custom emoji: missing on Android.** The composer comment says "Android has no custom emoji yet" (`Chat.kt:1360`); core resolves `custom_emoji` entities (`ChatEdit.kt:53` handles them in edits) but `ChatMentions.names` passes `emoji: {}`, there is no emoji manager, and bubbles don't render them. Web has it (`Chat.svelte:316+`). Needs: projection of `custom_emoji` rows into `Model`, blob fetch, picker tab in the emoji sheet, `:name:` autocomplete, and a manager (Figma modal, as a Drawer). No server change (D-054 exists). Size L.
- **F5.21 Three-pane chat: partial on Android.** At ≥ 840 dp `expanded` gives list 240 + chat + info 260 (`Chat.kt:198, 875, 1452`); Figma is the 1280 desktop with a 56 dp icon rail and 340 list. Tablet-only; leave until the phone slices are done.

## 2. Chat screens with no Figma frame

| Screen | File:line | Adopt |
| --- | --- | --- |
| Read as… sheet (member picker with search, radio list) | `Chat.kt:1463` | `Drawer` + the F5.18 member-row style (face, name, layer line, round check); drop the `ListItem`/`RadioButton`. Reached 4 taps deep; the F5.5 banner "Change" and strip-avatar tap are the entry points. |
| Quote picker ("Select the part to quote…") | `Chat.kt:1536` | `Drawer` with title "Quote", read-only text field, one wide `DrawerButton` "Quote selection" and a quiet "Quote all"; remove the instruction line. |
| Reply in… | `Chat.kt:1556` | Same grid as the F5.20 forward sheet (search + 4-up faces). |
| Forward bundle (several messages) | `Chat.kt:1574` | The F5.20 sheet; warning as the red card. |
| Emoji tray (composer) | `Chat.kt:1361` | `Drawer` header; later custom emoji tab (F5.12). |
| Message edit | `MessageEditDialog.kt` | F6.2-style editor bar (Cancel/Save in a header like Journal editor) once UI21 Journal is done; check with the Journal audit. |
| Chat ⋮ menu | `Chat.kt:915-935` | Drop "New chat" (FAB path exists), "Message options" (opens the attach sheet: unclear), "Stage mode" subtitle; keep Info, Edit, Notifications, Read as, Stage mode, Leave. |
| Notifications drawer for a chat | `Chat.kt:1601` | `ChoiceDrawer` is fine; drop the three `describe` lines (`:1605`), the titles say it. |
| Archived chats (list mode) | `ChatChrome.kt:144-160` | Header sub-line "A new message brings a chat back" goes; keep back arrow + title. |
| Space entry (forum) rows and channel list | `ChatChrome.kt:334` | Add the "• Garden" style line? No: keep; just align the avatar/rounded-square with F5.22 rows. |
| Row long-press menu | `ChatChrome.kt:274` | Add Mute/Unmute first; keep Pin, Folders…, Archive, Delete (danger). |
| Arrange pinned chats | `ChatPins.kt:94` | `FormCard` rows, left grip as in F5.25; remove the sentence. |
| Chat folder editor | `ChatFoldersScreen.kt:170` | F5.25 form style (see above). |
| Channel editor (new / edit) | `ChannelEditor.kt:37` | Already a `Drawer` with `FormCard`: keep. Trim texts. The Icon (emoji) field and Colour are Advanced: collapse behind an "Advanced" row. |
| Chat info for a shared space (people list, roles, Add people, Leave/Delete) | `ChatSpacePanel.kt:45` | Rows like F5.26 members (face, name, "system"/"person" line, role pill at right); plain `TextButton` "Delete group/Leave" (`:124`) becomes a red `MenuRow`. People avatars are initials in grey circles (`:58`): use the account's own picture. |
| Private-chat locked state ("This chat is private") | `Chat.kt:977` | Keep the icon + title, drop the paragraph. |
| Search hit banner (jump from search) | `Chat.kt:961` | Remove: the message is jumped to and ringed (`flashId`); the banner repeats it. |
| Photo viewer | `ChatDetails.kt:353` | Stays full screen (D-124). |
| Poll composer page in attach sheet | `PollCard.kt:87` | Check with the Polls audit; no chat-specific change. |
| Permissions panel | `ChatPermissions.kt` | See F5.11 above. |

## 3. Text to trim

Decisive list. "Delete" means remove the string; "→" gives the replacement.

| Where | Text | Do |
| --- | --- | --- |
| `ChatChrome.kt:295-299` | "Group chats with friends. In a system, each member writes as themselves…", "One-to-one chats with friends…", "Shared spaces include other systems. Messages show who is speaking; system asides stay inside your system.", "One-to-one chats between your members and with other accounts." | Delete all four (Figma shows one; the owner says no). |
| `ChatChrome.kt:150` | "A new message brings a chat back" | Delete. |
| `ChatChrome.kt:152` | "Your conversations" fallback | → "" (omit the line). |
| `ChatChrome.kt:289-292` | "No chats here yet." + two TextButtons | → one icon-led empty state with the FAB; keep one button. |
| `Chat.kt:923` | MenuRow subtitle "Pick messages to show as a scene" | Delete. |
| `Chat.kt:928` | "Whose unread messages to show" | Delete. |
| `Chat.kt:926` | "Message options" | Delete the row (duplicates the attach icon). |
| `Chat.kt:931` | "New chat" in the chat ⋮ | Delete. |
| `Chat.kt:959` | "No spaces yet. Start a chat with someone you follow." | → "" with the F5.18 entry button. |
| `Chat.kt:966-973` | "Search match · …" banner | Delete the banner (jump + ring already do it). |
| `Chat.kt:982-983` | "It belongs to one member of your system, and opens while they're fronting or co-conscious." | Delete (title "This chat is private" stays). |
| `Chat.kt:989-990` | "read through …" / "no saved read position" | → "last read yesterday" (F5.5); no fallback text. |
| `Chat.kt:1176-1181` | "As Foxglove" line over the composer; "Choose a sender, or type their symbols followed by a space."; "Only X writes here." | Show the "As …" line only when a typed symbol picks someone other than the avatar's; delete the "Choose a sender…" sentence (the "?" avatar already says it). |
| `Chat.kt:1194` | chip captions "who's fronting", "group" | → role text from the member's layer (F5.13), nothing for groups. |
| `Chat.kt:1246` | "Readers see the warning first and tap to show the message." | Delete. |
| `Chat.kt:1270` | "Everyone in this space can see who is speaking." | Delete. |
| `Chat.kt:1580` | "This shares a snapshot outside where it was written; its audience doesn't follow it." | Delete (the red card says it once). |
| `Chat.kt:1592` | ConfirmDrawer body "This shares a snapshot of the message with X. Its original audience doesn't follow it there." duplicates the FormNote at `:1594` | Delete the body; keep the red note "This leaves The Lanterns". Moot once F5.20's inline card lands. |
| `Chat.kt:1598` | "It disappears for everyone in this chat. You can undo right after." | → no body. (Undo bar exists.) |
| `Chat.kt:1605` | notification describes "Every new message", "Only when someone names you", "Nothing from this chat" | Delete. |
| `Chat.kt:1541` | "Select the part to quote, or quote it all." | Delete. |
| `Chat.kt:1626-1632` | "A place for a topic, plans or shared notes", "Write to a member or a connected account", "Invite connected accounts to a group chat" | Delete with the cards (F5.18 rows have only labels). |
| `Chat.kt:1698, 1701` | "Messages show who is speaking to everyone in the space.", "Connect with an account in Friends before inviting them." | Delete the first; → "No friends yet" with a Friends button for the second. |
| `Chat.kt:1746-1747` | "It's deleted for everyone in your system, messages and all." / "It leaves your chats and its messages leave your view, on all your devices. It comes back if someone writes again; they keep everything." | → "Delete #general?" with a danger button; for clear: "Clear X? It comes back if someone writes." |
| `Chat.kt:2113` | "Choose who is speaking to react." | Keep as disabled state but shorter: nothing; grey out the emoji (they already disable). |
| `ChatDetails.kt:90` | "Speaking as someone doesn't change who's fronting." | Delete (Figma has it, owner says no). |
| `ChatDetails.kt:190` | "Each member keeps their own read position when enabled." | Delete. |
| `ChatDetails.kt:170/179/184` | "No photos or videos shared here yet." etc. | → icon + "Nothing yet". |
| `ChatDetails.kt:207` | "Private to your account" | Delete (the person sees one row, themself). |
| `ChatSpacePanel.kt:52, 88-89, 104, 148, 169, 180` | "Space details are unavailable offline.", "Private to X. It shows only while they're fronting or co-conscious, and only they write here. This is privacy within your system, not a lock…", "A private chat with … Hidden from their other members in Chorus…", "Only the member you pick writes and reads it here.", "Everyone you're connected to is already here…", "They stop seeing … You can add them back later.", "…stays in the history and can be restored." | Delete all but the offline line (→ "Offline"); remove-person confirm → "Remove Kit?" with no body. |
| `ChannelEditor.kt:153` | "Offline · changes will sync when connected." | → "Offline" subtitle only. |
| `ChannelEditor.kt:171` | "A square picture works best." / "Pictures need a server update…" | Delete the first; keep the second as a disabled "Add a picture". |
| `ChannelEditor.kt:197-198` | "It shows only while one of them is fronting or co-conscious. This is privacy within your system, not a lock: you all share this account." | Delete (`D-010` soft privacy is documented; one lock icon says "private"). |
| `ChannelEditor.kt:219` | "Channel owners and managers can edit this." | Delete. |
| `ChannelEditor.kt:222` | "It goes for everyone here, messages and all. It can be restored from Trash." | → "Delete general?" only. |
| `DirectMessagePicker.kt:61, 89, 93, 101-102` | "Speaking as", "Only X sees and writes in it, while fronting or co-conscious.", "a system · each member writes as themselves", "Follow someone in Friends to message them here." / "Connect to load…" | Delete sentences; "a system"/"one person" labels stay. |
| `ChatFoldersScreen.kt:79` | "Sort chats and hide what you don’t need" | Delete (Figma shows it; owner says no). |
| `ChatFoldersScreen.kt:158-163, 191-197` | "Who is fronting, above your chats", "Each member keeps their own read position", "Smaller pictures, more chats on screen", "Your own channels and DMs", "Spaces shared with other accounts", "One-to-one chats", "Unread for whoever is reading", "Leave muted chats out" | Delete every switch subtitle; keep the toggle titles. Folder summaries (`:261-271`: "3 chats · Shared · unread") stay: they are data. |
| `ChatFoldersScreen.kt:229` | "Its chats stay where they are; only the folder goes." | Delete. |
| `ChatPins.kt:94` | "Drag a handle to change the order. Pinned chats stay at the top of every folder." | Delete. |
| `ContentSearch.kt:174` | "Try words or from:, in:, has:, before:, after:, is:pinned." | Delete (replaced by the filter chips). |
| `ContentSearch.kt:183, 185, 188, 209` | "Offline · local replica only. Shared posts need a connection.", "This chat is unavailable to the current front.", "Type to search this scope. Chats use the current front…", "Showing the newest 100 local matches. Narrow the search for more." | → "Searching this device" footer (F5.9) once; the others delete. |
| `ChatPermissions.kt:43, 58, 60, 86, 87` | "A person's setting beats their role's, then Everyone's…", "This shares just this channel with them.", "Private chats still use their chosen members…", "0 turns slow mode off. Managers are exempt.", "Messages sent too soon wait on this device…" | Delete all; use the labels "Allow / Deny / Inherit" alone. |
| `ChatAttach.kt:378` | placeholder "Describe the image for people who can't see it" | → "Alt text". |
| `ChatAttach.kt:380` | "Hidden until someone taps it" | Delete. |

## 4. Taps to cut

| Flow | Now | Fix | Target |
| --- | --- | --- | --- |
| Start a DM with a member or a friend | FAB, "Direct message", pick sender, pick recipient(s), Start message (5-6; `DirectMessagePicker.kt`) | F5.18 page: FAB, tap the person (default speaker) | 2 |
| Send "aside" in a shared space | attach icon, "Who sees" tile, "Only my system" chip, Done, send (5) | Aside pill above the composer | 1 + send |
| Send a photo | attach, photo, "Add 1 photo", send (4) | caption + send inside the sheet | 3 |
| Mute a chat | ⋮, Notifications, choose (3), or ⋮, Chat info, Mute (3) | row long-press Mute (2); info Mute stays | 2 |
| Open chat info | ⋮, Chat info (2) | tap the header title/avatar | 1 |
| Read as another member | ⋮, Read as…, pick (3) in a search sheet | tap a face in the here-now strip (1); banner Change stays | 1 |
| Forward to a friend's system | Forward, pick, confirm drawer (3 + 1) | Forward, pick (inline warning), send | 3, no second drawer |
| Quote part of a message | tap, Quote, long-press-select inside a sheet, "Quote selection" | keep; it is rare. Move "Quote" into "More" (F5.6) | n/a |
| Open a search result | tap card, "Open message" button | whole card is the target | 1 |
| See all pinned messages | not possible, only cycle through the bar | info "Pinned" tile | 2 |
| Put a chat in a folder | long-press, "Folders…", tick, Done (4) | info "Folder" tile (same drawer); keep long-press | 3 |
| Switch who's writing | tap avatar, pick (2) | already optimal | 2 |
| React | tap bubble, tap emoji (2) / double tap heart (1) | already optimal | 2 |
| Create a channel | FAB, "New channel", fill, Create | F5.18: "New internal group" row (same editor) | 2 + form |

## 5. Conflicts with DECISIONS and owner questions

### Conflicts (DECISIONS win; not gaps)
- **F5.3 "The Lanterns" and "Unsigned" speakers**: D-003 (every message authored by members). Stay out.
- **F5.6 popover menu**: D-124 made every menu a drawer. Keep the drawer, take only the row set and order.
- **F5.9 compact result rows**: D-114 mandates context cards with one neighbour each side. Keep cards, make them tappable.
- **F5.12/F5.11/F5.21 desktop**: web frames; Android only gets the emoji manager and (maybe) the permissions restyle.
- **Figma explanatory captions** (F5.14 "Only Foxglove and Amarynthe can open this chat", F5.22/F5.23 list footers, F5.24 "Direct chat between…", F5.25/F5.26 helper lines, F5.10 shield note, F5.3 footer): the owner's instruction (no textual explanations) overrides the screens. Not built, removed where present.
- **Bottom navigation**: F5.1 shows Home · Members · Fronting · Chat · More, F5.22/23 show Members · Fronting · Chat · Friends · More; D-089/D-127 own the bar. Not part of chat.
- **F5.13 `@everyone`**: not in SPEC §5.3 mentions (`@member`, `@group`, `@account`, `@front`); needs a spec line and a core/notify rule. Default: not built; `@front` shown with the Figma label.
- **F5.20 original chat name hidden vs current behaviour**: today the snapshot carries `channel_name`; fix is client-only (omit it when the target space differs). Treat as a bug fix, no decision needed.

### Owner questions (defaults assumed)
1. **Handover and Notes to ourselves (F5.15, F5.19).** Should these be real chat kinds? Default: yes, as a role on a normal channel (account pref `chat_role:<channel>` = `handover`/`notes`, no schema change), with Acknowledge as a reaction shown as a service line, "since X last fronted" divider, pinned handover bar, hashtag chips, and Forward → Notes as the first destination. **Checklist** (tappable `- [ ]` lines) deferred; **"At next switch"** (a message that waits for the next switch) dropped as it needs server scheduling.
2. **Shared-space permissions (F5.11).** Figma lists permissions the server does not have (send photos/files, create polls, invite systems, member names on messages, fronting-status visibility, history before joining, allow asides). Default: keep the six D-047 permissions, restyle as toggles, build none of the new ones until you say which you want.
3. **What the ticks mean (F5.1, F5.2, F5.17).** Default: clock = not yet on the server, one tick = saved on the server, two ticks = another member of our own system has read it (never "your friend read it": reads are not shared across accounts and should not be). Say if you want something else.
4. **Attach tiles "Journal" and "Front card" (F5.8).** Default: Journal = pick a post or entry and send its link (D-109 renders it as an embed); Front card dropped (no spec; the front is already visible to followers through Friends).

## 6. Web (`web/src`)

- F5.1/22/23: desktop rail + here strip + folder tabs exist (`ChatList.svelte`, `ChatFolderRail.svelte`); no handover/notes icons, no ticks in the list, no "• Garden" suffix check done.
- F5.2/F5.5/F5.7: close; web unread divider is the generic "Unread messages" (`Chat.svelte:1367`) not "12 unread for Amarynthe", and no reading-as banner or `@` jump button; no "Also send to" (Q22 says web unchanged).
- F5.3: no "Send message as…" picker; speakers are chips (D-103); the Figma sheet is Android only so far.
- F5.4/F5.16: audience is a `<select>` (`Chat.svelte:1486`); no Aside bar/pill, no dashed bubbles (`Message.svelte` has an `aside()` snippet for the edited mark, unrelated).
- F5.6: ⋯ menu / context menu (D-120); no "Seen by" row in the Figma position; "Select for bundle" is worded differently. Not audited deeply.
- F5.8: no recent-photo grid sheet; plain file input.
- F5.9: old style (`Search.svelte:159`, "Search" + "Close search"), no Chats/Media/Files/Links tabs, no filter chips.
- F5.10/F5.18: `NewChat.svelte` is a choose/direct/group stepper, not the F5.18 list.
- F5.13: mention suggestions via `mentionChoices`; no `@everyone`, no role text.
- F5.15/F5.19: absent (only a folder-name regex suggestion in `chat-folders.ts`).
- F5.17: web lists "Not sent: …" rows above the composer (`Chat.svelte:1438`) with Copy/Dismiss, but no clock, no ticks per bubble, no retry.
- F5.20: forward is a `<select>` in a bar (`Chat.svelte:1426`): diverges badly; same channel-name leak.
- F5.25: `ChatFolders.svelte` has the sections; visual parity unchecked.
- F5.26: room-info has Pinned (a good model for Android), Members by layer with "N unread", Media, Files, Links; no Polls tab, no Folder tile.
- F5.11: `ChannelPermissions.svelte` is the six-permission model, same as Android.
- F5.12: custom-emoji upload/crop and picker exist (`Chat.svelte:316-390`); matches the idea of the frame.
- F5.21: web desktop three-pane exists (W1); it is the reference for the emoji and pinned panel behaviour.

## 7. Proposed slices (each shippable alone, most value first)

0. **Trim and quick fixes** (S): every deletion in section 3, remove "Message options" and "New chat" from the chat ⋮, remove the folder-tab pencil, round label-less FAB, Mute in the row menu, header tap opens info, `#` before channel names, forward snapshot omits the source chat name outside its space. Fixes the "what does this do" and privacy items at once.
1. **New message page** (M-L): F5.18 + F5.10, folder-aware FAB, two-tap DM, shared-space picker with chips and search. Biggest tap saving.
2. **Aside and audience bar** (M): F5.4/F5.16, one pill, dashed composer and bubbles, "Everyone here" bar in shared spaces.
3. **Message actions** (S-M): F5.6 eight rows + More, one date line; mentions list F5.13 in the same slice (both are drawer/list restyles in `Chat.kt`).
4. **Forward and Reply-in sheets** (M): F5.20 grid, search, inline warning, comment, original-author toggle; Reply in… and bundle reuse it; Notes slot follows slice 8.
5. **Attach sheet with caption** (M): F5.8 caption row, eye toggle, send in sheet, Journal link tile.
6. **Search** (L): F5.9 top bar, tabs, filter chips, tappable cards, footer line.
7. **Chat info** (M): F5.26 Pinned and Folder tiles, Polls tab, per-member unread, sticky tabs; shared-space people rows restyle.
8. **Offline and ticks** (M-L, needs FFI `pending_ids` and `retry_issue`): F5.17 clock/tick/"Not sent" + the same ticks in the list (F5.1/F5.23). After owner question 3.
9. **Folders screen and chat forms** (M): F5.25 visual (left grip, chevrons), folder editor to F3.5/F6.7 form style, Read-as and quote drawers to `Drawer`, permissions panel restyle.
10. **Handover and Notes** (L): F5.15/F5.19 after owner question 1 and a D-xxx.
11. **Custom emoji** (L): F5.12 on Android (model projection, picker tab, manager drawer, bubble rendering).
12. **Permissions** (M-L, only after owner question 2).
13. **Tablet three-pane polish** (S-M): F5.21 widths and an icon rail.
