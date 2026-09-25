//! Chorus core: every rule that must behave identically on the server, Android and web.
//!
//! This crate is pure — no IO, no clocks, no randomness. Callers pass in the current time and
//! random bytes. See `docs/SYNC.md` and `docs/DATA_MODEL.md`.

pub mod api;
pub mod color;
pub mod emoji;
pub mod feed;
pub mod front;
pub mod hlc;
pub mod id;
pub mod import;
pub mod lww;
pub mod mentions;
pub mod model;
pub mod notify;
pub mod op;
pub mod projector;
pub mod reading;
pub mod replica;
pub mod restore;
pub mod revisions;
pub mod search;
pub mod sort;
pub mod speaker;
pub mod stage;
pub mod sync;
pub mod tagged;
pub mod text;
pub mod time;
