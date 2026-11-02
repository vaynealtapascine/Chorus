# Owner's three slices — 2026-11-02

Owner explicitly approved these changes. Implement in order, web and Android together.
The parent independently reviews, commits fixes and deploys each slice before starting the next.
Implementation agents do not deploy or change services/settings.

1. **Journal and singlets (UI11a).** Editable notes/entries/replies with server-derived edit
   history stored as compact diffs. Keep the append-only op log intact. Distinct repost and
   switch icons. Timeline previews stop at 300 Unicode characters instead of three lines.
   Singlets can post and reach their own complete profile editor (avatar, colour, all custom
   fields). Each relationship has an audience including selected privacy buckets. Targets may
   be own members, visible on-server members/fragments/singlets, or external labels. Check
   target visibility on writes and every shared read; never publish a hidden identity, its
   blobs or history. Use the existing visible-member checks. No new fragment account kind.
2. **Search (UI11b, pending).** Search in current channel, journal only, all chats visible to
   the current fronter, and saved/bookmarked posts. Full message cards, with one visible
   message before and after as context, and full post cards. Enforce channel/member visibility
   independently for every result and each context message; no existence leaks from counts,
   context gaps, blobs, snippets or cursors. Shared core filters and conformance where needed.
3. **Privacy buckets (UI11c, pending).** Customizable notification controls, name and icon for
   each bucket; use the same bucket presentation everywhere privacy is controlled. Preserve
   system ceilings, follower filters, delay/fuzz/digest and reveal-time invariants. Update both
   clients, specs, tests and deployment notes.

## Review/deployment protocol

- Read PROGRESS, DECISIONS, task specs and OPEN_QUESTIONS in order each session.
- Full `python scripts/verify.py` before every commit; Android changes also need `--android`.
  Windows: dot-source `F:/DunBuild/chorus-env.ps1`, use Android Studio JBR and the F: Gradle
  cache/build dirs. The old `chorus-verify.ps1` wrapper forces `--quick`; for the full gate use
  its process environment followed by `python scripts/verify.py --android --offline` directly.
- Parent review especially checks privacy across API, search, blobs, sync, export and restore;
  rebuild/convergence; offline edits; Unicode/format ranges; singlet navigation.
- Deploy using `scripts/deploy-linux.ps1` after review; backup before migrations, publish the
  matching web bundle and Android APK. Parent handles owner-phone smoke checks.

## Storage contract

The log retains complete edit payloads forever for offline convergence and restore. Compaction
only reduces the **derived** post-history duplication, not the authoritative log or backups.
Base version plus forward text splices and changed fields reconstructs history on the server;
large replacements may be stored as whole field values when smaller. Rebuild regenerates
these diffs. No new full-history text index or blob grant is created.
