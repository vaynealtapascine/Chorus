//! Chorus core: every rule that must behave identically on the server, Android and web.
//!
//! This crate is pure — no IO, no clocks, no randomness. Callers pass in the current time and
//! random bytes. See `docs/SYNC.md` and `docs/DATA_MODEL.md`.

pub mod hlc;
pub mod id;
pub mod time;
pub mod op;
pub mod lww;
pub mod front;
pub mod text;
pub mod speaker;
pub mod feed;
pub mod color;
pub mod model;
pub mod sync;
pub mod api;
