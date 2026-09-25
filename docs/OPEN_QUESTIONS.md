# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) and Q15 (fronting feeds) were answered on 2026-09-24 (D-068, D-069); Q16 (Android release key) on 2026-09-25 (D-072). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q17 | **Slow mode with offline writes** (SPEC §5.1, R22.7): a channel lets each account send one message every N seconds. A message written offline at 10:00 and synced at 12:00, or five written offline and synced together: which count, and when? | Built as: counted when the server **receives** the send (`received_at`), per account per channel; a send too soon is refused (`slow_mode`, "wait N s") and shows in *Sync issues* with the text kept to copy; so of several queued offline sends only the first gets through. Owners, admins and anyone with `manage` on the channel are exempt; restore pushes too. Set per channel in its ⋯ menu (`settings.slow_mode_s`, at most 6 h). Alternatives: count by when it was written (`occurred_at`, easy to game offline), or hold refused sends and retry them when allowed. |
