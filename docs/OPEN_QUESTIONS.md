# Open questions

Owner planning worksheet: [Decisions to revisit](DECISIONS_REVISIT.md) covers fronting, identity, relationships, views, scripting and architecture. Its draft prompts do not change the defaults below or authorize implementation.

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) and Q15 (fronting feeds) were answered on 2026-09-24 (D-068, D-069); Q16 (Android release key) on 2026-09-25 (D-072); Q17 (slow mode offline) on 2026-09-26 (D-076). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| — | None open. | — |

Q18 (past-history corrections while following) was answered 2026-10-25: keep following (D-083).
Q19 (additional-front follower access) was answered 2026-10-25: explicit grants are required (D-084).
Q24 (a friend's member count) was answered 2026-11-08: only what is shared (D-138).
Q25 (delivered notifications whose source is gone) was answered 2026-11-11: they leave the list while it is gone (D-149).
Q26 (asking again after a no) was answered 2026-11-11: a silent 30-minute cooldown (D-150).
Q27 (forwards) was answered 2026-11-11: they notify like messages (D-151).
Q28 (a signed-out device's copy) was answered 2026-11-11: kept two days then erased, or erased at once if chosen; the device goes back to onboarding (D-152, D-153).
Q20 (mentions, reading and chat availability) was answered 2026-10-25: separate per-front layer selections using the channel's selected front, preserving existing defaults (D-085).
Q21 (typing default), Q22 (“Also send to” in threads) and Q23 (votes of archived or deleted members) were answered 2026-11-14: each default stands (D-161).
