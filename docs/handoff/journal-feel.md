# UI24: Journal on web feels like Android's (owner, 2026-11-13)

Owner: "get the feel of Journal on Web to the same level as Android. The composer in particular feels
jank. Get the Journal experience to Twitter levels of ease."

Worktree: `C:/Users/pcuser/.t3/worktrees/Chorus/ui21-web-home` (pushes to main after a rebase, `../commit.sh`).
Demo for looks: `F:/DunBuild/ui21-j-demo` (server 5341, vite 5342 run from this worktree, `node shot2.cjs 1 '<steps>'`,
shots in `shots/`; start commands in §5). References: Android `ui/Journal.kt:361-528` (the composer screen),
`ui/JournalComposer.kt`, Figma `F:/DunBuild/figma-cache/frames/F6.2`, `F6.3`, `F6.4`, `F6.13`.

## 1. What is wrong today (seen at 1360 and 390 px)

- The whole editor is always open at the top of the feed: a boxed, hand-resizable two-line textarea, then three
  rows of controls (audience and bell, eleven formatting buttons, six attach buttons). On a phone it fills more than
  half of the first screen before any post shows. Android shows the feed and a pencil button.
- No face, the author is a bare name that opens a `<details>` of checkboxes, "Note | Entry" sits on a reply box.
- The same heavy box is the thread's reply box; on a phone its controls end up under the tab bar.
- "React as" is a native `<select>` in the header.
- Nothing grows with the text, nothing posts from the keyboard, one draft per tab (lost with the tab), no Drafts.
- A new post just appears; posts arriving while scrolled down move the page (M7.2's "new posts" pill was never built).
- Right rail is five static shortcuts (F6.13 draws search, tags, feeds, formatting).

## 2. Target

One composer model, three surfaces, every control one tap away but not on screen until wanted.

| Surface | Where | Looks |
| --- | --- | --- |
| Quick box | top of the Journal timeline at ≥ 641 px (F6.13); a member's profile | Face, "What's on your mind, Violet?", image / poll / emoji, audience chip, Post. Grows with the text. Focus or text reveals the author chip, the rest of the attach icons, the T (formatting) toggle and an expand button into the full composer with the same draft. |
| Full composer | everywhere a post starts: the phone's pencil button, the sidebar's **New post**, `n`, Quote, Home's "Write something", expand | F6.2. Phone: full screen, Cancel / Drafts / Post on top, Post | Journal entry, face + "Posting as" chip, title (entries), text, files, poll; audience row, formatting row and attach row docked above the keyboard. Wide: the same in a centred dialog (600 px). |
| Reply box | under a post on its thread page | Face, "Post your reply", Reply. Expands in place like the quick box; no Post | Journal entry. On a phone it is a bar above the tab bar that opens the full composer. |

Ease rules: Ctrl/⌘+Enter posts; Ctrl/⌘+B, I, K (link), Shift+X (strike), E (code); Enter continues a list or quote
and ends it on an empty item; Esc closes the dialog and keeps the draft; pasted and dropped files attach; the text
field never shows a resize handle or a box; `@` suggests our members; the formatting row is hidden until T or a
selection (the pill); posting clears the box, closes the dialog and the new post arrives at the top with a fade.

Drafts: the main draft is kept on this device (localStorage, so it survives the tab; reply drafts stay per tab),
Cancel with words asks Save draft / Discard, Drafts lists the kept ones (as Android's shelf). Erased with the
account's other device data (`sync/erase.ts`).

## 3. Architecture

- `lib/journal-composer.svelte.ts`: `Composer` (runes class): the draft, files, derived authors / audience / canPost,
  `send()`, `discard()`, `shelve()`. No markup. One instance per context (`main`, `reply:<post>`), shared by the
  quick box and the dialog so expanding keeps everything.
- `lib/journal-compose.svelte.ts`: the app-wide opener (`openComposer({ kind?, quote?, replyTo?, author?, audience? })`,
  `closeComposer()`); `App.svelte` hosts the dialog once.
- `lib/ui/journal/`: `Composer.svelte` (surface switch), `ComposerEditor.svelte` (text field, selection pill, keys),
  `ComposerBars.svelte` (audience / formatting / attach rows), `AuthorChip.svelte` (+ sheet of faces), `AttachmentTray.svelte`,
  `PollFields.svelte`, `DraftsSheet.svelte`, `ComposerDialog.svelte`, `ReplyBox.svelte`, `MentionPopup.svelte`,
  `NewPostsPill.svelte`, `JournalRail.svelte`. `ui/PostComposer.svelte` goes away.
- Pure helpers with tests: `journal-draft.ts` (`loadDraft` / `saveDraft`), `journal-drafts.ts` (the shelf),
  `format-text.ts` (`enter`, `chord`), `compose-suggest.ts` (mentions), `feed-arrivals.ts` (the pill's rule, Android's).
- Budget: the opening script stays ≤ 300 KB gz (D-156); load the dialog's rarely used sheets on demand if needed.
- Labels the browser suite uses stay: text field "Post text", buttons "Post" / "Reply".

## 4. Slices

1. **Composer** (Opus): §3's model, the three surfaces, opener, keys, author chip, tray, poll, drafts sheet; parallel
   Sonnet agents write the pure helpers and the F6.13 right rail.
2. **Feed feel** (Sonnet): pencil button on phones, New post in the sidebar, "posting as" in the header instead of the
   React-as select, new-posts pill, own post arriving, switch markers as Android's, `@` suggestions, e2e.
3. **Review** (Opus, fresh): the diff and both widths in a browser against Android and the frames; fix what it finds.
4. Gate (`verify.py`, e2e journal specs), D-entry, CLIENTS/DESIGN notes, whats-new, deploy web (`deploy-linux.ps1`).

Status is on the board (UI24) and in the log.

## 5. Demo

```
cd F:/DunBuild/ui21-j-demo && ./chorus-server.exe --config demo.toml serve > server.log 2> server.err &
cd <worktree>/web && CHORUS_PORT=5341 npx vite --port 5342 --strictPort > F:/DunBuild/ui21-j-demo/vite.log 2>&1 &
cd F:/DunBuild/ui21-j-demo && node shot2.cjs 1 '[{"goto":"/#/journal"},{"wait":2500},{"shot":"x"},{"size":[390,844]},{"shot":"x-phone"}]'
```

Profile 1 is enrolled as The Lanterns (a system; Violet fronting). Steps: see the header of `shot.cjs`.
