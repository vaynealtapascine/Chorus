# perms (D-133)

`space_perms(levels)` → the permissions one account has: `chorus_core::perms::allowed`, the rule of
DATA_MODEL §4.4 with channel and space levels. The clients call the same function through wasm
(`spacePerms`) and UniFFI (`space_perms`); `levels` is described in `docs/handoff/d133-clients.md`.
The server's SQL rule is property-tested against the same function (`chorus-server/tests/permissions.rs`).
