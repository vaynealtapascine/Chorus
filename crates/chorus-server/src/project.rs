//! SQL projections (docs/DATA_MODEL.md §4). Filled in by M2.4: every accepted op re-projects the
//! entities it touches by running `chorus_core::model` over their ops.

use chorus_core::op::Op;
use rusqlite::Connection;

pub fn after_insert(_conn: &Connection, _op: &Op) -> anyhow::Result<()> {
    Ok(())
}
