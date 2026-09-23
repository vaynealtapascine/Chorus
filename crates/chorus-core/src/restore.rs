//! Restore authorization shared by the reference server and SQLite server (D-053).

use crate::op::{self, Action, Op};

/// The creator or the latest deleting account may restore a Trash item. Other restore kinds
/// retain their existing permissions until their own Trash surfaces are implemented.
///
/// Ops can arrive out of order (offline devices), so a restore for an item whose create and
/// delete are both unknown yet is allowed: it is harmless, and LWW settles it against the
/// delete when that arrives. An account's own scope is only writable by itself anyway.
pub fn allowed<'a>(
    kind: &str,
    scope: &str,
    entity_id: &str,
    account_id: &str,
    ops: impl IntoIterator<Item = &'a Op>,
) -> bool {
    if !matches!(kind, "message.restore" | "post.restore" | "member.restore" | "group.restore" | "channel.restore") {
        return true;
    }
    if scope.strip_prefix("account:") == Some(account_id) {
        return true;
    }
    let Some(table) = op::spec(kind).map(|s| s.table) else { return false };
    let relevant: Vec<&Op> = ops
        .into_iter()
        .filter(|o| {
            o.scope == scope && o.entity() == Some(entity_id) && op::spec(&o.kind).is_some_and(|s| s.table == table)
        })
        .collect();
    let creator = relevant
        .iter()
        .copied()
        .filter(|o| op::spec(&o.kind).is_some_and(|s| matches!(s.action, Action::Create | Action::Append)))
        .min_by_key(|o| (o.hlc, &o.id));
    let deleter = relevant
        .iter()
        .copied()
        .filter(|o| op::spec(&o.kind).is_some_and(|s| s.action == Action::Delete))
        .max_by_key(|o| (o.hlc, &o.id));
    if creator.is_none() && deleter.is_none() {
        return true;
    }
    creator.is_some_and(|o| o.account_id.as_deref() == Some(account_id))
        || deleter.is_some_and(|o| o.account_id.as_deref() == Some(account_id))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{hlc::Hlc, id::new_id, time::TimeSource};

    fn op(n: u8, kind: &str, account: &str, entity: &str) -> Op {
        Op {
            id: new_id(n as u64, [n; 10]),
            kind: kind.into(),
            v: 1,
            scope: "space:00000000-0000-7000-8000-000000000001".into(),
            entity_id: Some(entity.into()),
            hlc: Hlc::new(n as u64, 0, 1),
            device_at: n as i64,
            tz_offset_min: 0,
            mono: None,
            boot_id: None,
            time_source: TimeSource::Auto,
            seen_seq: 0,
            member_id: None,
            payload: serde_json::json!({}),
            seq: Some(n as i64),
            account_id: Some(account.into()),
            device_id: Some("dev".into()),
            occurred_at: Some(n as i64),
            received_at: Some(n as i64),
        }
    }

    #[test]
    fn creator_or_latest_deleter_can_restore_and_entity_tables_do_not_collide() {
        let id = new_id(1, [1; 10]);
        let scope = "space:00000000-0000-7000-8000-000000000001";
        let ops = [
            op(1, "message.send", "alice", &id),
            op(2, "message.delete", "bob", &id),
            op(3, "channel.create", "charlie", &id),
            op(4, "channel.delete", "charlie", &id),
        ];
        for account in ["alice", "bob"] {
            assert!(allowed("message.restore", scope, &id, account, ops.iter()));
        }
        assert!(!allowed("message.restore", scope, &id, "charlie", ops.iter()));
        assert!(allowed("channel.restore", scope, &id, "charlie", ops.iter()));
        assert!(!allowed("channel.restore", scope, &id, "bob", ops.iter()));
    }

    #[test]
    fn early_restores_and_own_scope_are_allowed() {
        let id = new_id(1, [1; 10]);
        let scope = "space:00000000-0000-7000-8000-000000000001";
        assert!(allowed("message.restore", scope, &id, "anyone", std::iter::empty()));
        let theirs = [op(2, "member.delete", "bob", &id)];
        assert!(allowed("member.restore", "account:alice", &id, "alice", theirs.iter()));
    }
}
