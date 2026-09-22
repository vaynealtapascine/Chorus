# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

| # | Question | Default until answered |
| --- | --- | --- |
| Q1 | Multi-author messages: is it always "all of us say this together", or should one message also support **segments** (different members speaking different lines)? | Joint authorship only. Schema could add `message_segment` later without migrating existing rows. |
| Q2 | Should person (singlet) accounts be able to write journal posts and have profiles like members? | Yes — their single member has a normal profile and posts. |
| Q3 | Should friends be able to reply to/react to a system's posts, or only view? | Reply and react if the post is visible to them; systems can turn replies off per post or per account. |
| Q4 | Should the system's **internal space** ever be shareable read-only (e.g. a partner can read #announcements)? | No in v1; use a shared space instead. |
| Q5 | Is a member-level "short id" (PK-style 5-letter id) useful to you, e.g. for commands/Tasker? | Generate one (`short_id`, 5 lowercase letters) and show it in Advanced only. |
| Q6 | Custom emoji: server-wide set, per-space sets, or per-account? | Server-wide set managed by admin + per-space sets. |
| Q7 | Should switch notifications ever go to a follower's *specific* members (another system's members), e.g. "notify only when Kai from our system is fronting"? | No — follows are account-to-account; a following system's own front can filter delivery in Advanced later. |
| Q8 | Stage mode: do you want a "fake timestamps / fake names" option, or only hide/blur? | Only hide/blur/placeholder. Nothing that fabricates content. |
| Q9 | Android distribution to friends: direct APK download + Obtainium, or also F-Droid-style repo? | APK download + in-app update check. |
| Q10 | Which app name/icon style? ("Chorus" with a small overlapping-circles mark in the accent colour.) | As stated; icon made in M12. |
| Q11 | Should Insights compare members against each other (rankings), or only show each member's own data? Some systems find rankings unkind. | Show totals per member without "top/bottom" framing; rankings off by default (Advanced toggle). |
| Q12 | Retention: keep everything forever by default? | Yes, forever. Purge tools in Advanced. |
