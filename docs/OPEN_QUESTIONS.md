# Open questions

Non-blocking. Each has a **default** that implementers use until the owner answers. When answered,
move the answer into DECISIONS.md and delete it here.

All questions from the 2026-09-23 spec round are answered (D-045 to D-054). Q14 (export bundle) was answered on 2026-09-24 (D-068). Add new ones below.

| # | Question | Default until answered |
| --- | --- | --- |
| Q15 | A shared feed whose filter uses `fronting:` would tell followers when members fronted, which follow ceilings (NOTIFICATIONS §3) otherwise govern. Share it anyway (evaluated with the follower's ceiling), or keep such feeds private? | Kept private: the web app won't share them and `GET /feeds/{id}/items` answers 400 to anyone but the owner (remote batch R10). |
