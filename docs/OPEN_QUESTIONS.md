# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) and Q15 (fronting feeds) were answered on 2026-09-24 (D-068, D-069); Q16 (Android release key) on 2026-09-25 (D-072); Q17 (slow mode offline) on 2026-09-26 (D-076). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q20 | With custom fronts/layers, should a channel's selected front also drive `@front` mentions, per-member read tracking and “only while fronting” chat alerts, with an independent layer selection for each purpose? | Keep those features on the existing Body/front+co-conscious rules (SPEC §5.3, NOTIFICATIONS §7). The channel's front selection currently affects authorship only (D-080). Do not infer custom-layer roles or reuse authorship/switch-alert selections for these other purposes without the owner's answer. |

Q18 (past-history corrections while following) was answered 2026-10-25: keep following (D-083).
Q19 (additional-front follower access) was answered 2026-10-25: explicit grants are required (D-084).
