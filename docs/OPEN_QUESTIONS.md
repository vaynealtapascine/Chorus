# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) and Q15 (fronting feeds) were answered on 2026-09-24 (D-068, D-069). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q16 | **A release signing key for the Android app.** Builds are signed with the debug key today (fine for the owner's own phone). Publishing the APK on GitHub releases for Chorus Home users needs one permanent key: every later update must be signed with it, and a lost key means users reinstall. Make one now (kept by the owner, given to CI as a secret), and should the owner's own phone move to it (a one-time reinstall)? | The APK is not published; the Home page links to the releases page, which has only the Windows download. |
