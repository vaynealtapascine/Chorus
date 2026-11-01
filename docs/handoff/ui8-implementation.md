# UI8 Android slices 4–5 implementation

Inherited implementation completed in the main checkout by GPT-6.1-sol, 2026-11-01.

Android: shared-space management (name/description, participants, roles, leave and tombstone delete), channel member editing, member-DM people editing, outside-account private chat creation, per-side titles, speaker selection constraints and privacy disclosure. Channel visibility is shared by list/search/pickers/Trash/Stage and the open conversation. Threads resolve parent visibility; missing parents, unknown channels and cyclic/deep thread chains fail closed. Web default DM opening prefers an unrestricted non-thread channel.

Tests cover private visibility versus Read as/hiding preferences, space permissions, field-level payloads, private chat lookup and speaker fallback. No owner data mutations, deploy, push, merge or system changes.

Validation PASSED: wrapper gate with --android (verify.py --quick --offline --android), log F:/DunBuild/chorus-verify-Chorus.log, exit 0. Rust suite, web checks/tests/build budgets, Android lint and unit tests passed; ChatManageTest has 5 passing tests. First attempt failed LNK1104 opening the projection test executable while inherited checks were running; retry passed. Inherited Android lint produced all three reports. No interaction validation has been completed in this takeover.

Remaining: independent Sol 6.1 review, slices 6–8, demo interaction validation and final full verify.py --android --offline. UI8 remains in progress. Commit: feat(android): complete UI8 space management and private chats (use git log for SHA). Includes inherited Android WIP and web default-channel fix.


Slice 6 added 2026-11-01 (GPT-6.1-sol): swipe-to-reply, double-tap heart through existing
member authorship, haptics, accessible Reply, and live Draft:/typing previews restricted to
visible chat-list rows. Manual port from parked UI7, with synchronous drag updates and cancelled
spring jobs on re-grab. Five gesture tests and the preview-priority regression pass. Targeted
Android compile/lint/JVM passed, as did wrapper quick offline gate with Android (exit 0).
Independent review, demo interactions and slices 7–8 remain; no deployment or owner data changes.


Review correction pass (2026-11-01): Android revalidates retained edit/forward source actions
against current D-098 channel and message visibility, hides overlays immediately when access
ends, and rechecks the current replica before writes. Restricted channel audiences (including
thread inheritance) now trigger outward-forward confirmation. Channel/group sheets compare
icon and group name/description against their opening baseline so unrelated remote edits stay
untouched. Separate server correction rejects re-create structural/scope bypasses and protects
foreign member entries on repeated channel.create; focused server regressions passed 5/5.
