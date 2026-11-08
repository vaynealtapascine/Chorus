# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) and Q15 (fronting feeds) were answered on 2026-09-24 (D-068, D-069); Q16 (Android release key) on 2026-09-25 (D-072); Q17 (slow mode offline) on 2026-09-26 (D-076). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q21 | Confirm the shared-space/DM typing indicator default: should other accounts see typing only after “Show when we're typing in shared chats” is enabled? (Owner request, 2026-10-27; D-087.) | **Off.** Same-account other devices still receive it; internal spaces never send it across accounts. |
| Q22 | Figma F5.7 shows “Also send to General” under a thread's composer; the spec has no such option. Android implements it as: when ticked, the reply is posted in the thread *and* as a separate message in the parent channel, replying to the thread's parent. Keep, change (e.g. one message shown in both places), or drop? (2026-10-28, Android chat parity.) | **Two messages**, unticked each time; web unchanged until answered. |
| Q23 | When a member who voted in a poll is deleted or archived, should their vote still count? POLLS.md only checks the member at voting time; deleting is a restorable tombstone, so dropping the vote would bring it back on restore. (Gemini review BUG-04, 2026-11-06.) | **Keeps counting**, as now. |

Q18 (past-history corrections while following) was answered 2026-10-25: keep following (D-083).
Q19 (additional-front follower access) was answered 2026-10-25: explicit grants are required (D-084).
Q24 (a friend's member count) was answered 2026-11-08: only what is shared (D-138).
Q20 (mentions, reading and chat availability) was answered 2026-10-25: separate per-front layer selections using the channel's selected front, preserving existing defaults (D-085).
