# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) and Q15 (fronting feeds) were answered on 2026-09-24 (D-068, D-069); Q16 (Android release key) on 2026-09-25 (D-072); Q17 (slow mode offline) on 2026-09-26 (D-076). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q18 | When editing or undoing a past entry in a front that is currently following, should following continue or pause until Resume following? Asked 2026-10-25. | No runtime behavior change while pending. The not-yet-integrated core history fold supports both choices through a required caller argument; no client default has been chosen. |
