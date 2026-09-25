//! Reading per member (SPEC §5.3, Advanced "track reading per member"): who marks a channel read
//! when the account reads it, and which members haven't seen a message.
//!
//! A `read.mark` names its reader in `reader_member_id` (`""` = the account). With tracking on,
//! reading a channel marks it for the account and for each member fronting or co-conscious
//! then; a message is unseen by a member whose mark is older than it. Marks never leave the
//! account (SYNC §4.2).

use serde::{Deserialize, Serialize};

use crate::speaker::Fronter;

/// The readers a read marks: the account (`""`) and, with per-member tracking, the members
/// fronting or co-conscious, in fronting order.
pub fn readers(per_member: bool, fronting: &[Fronter]) -> Vec<String> {
    let mut v = vec![String::new()];
    if per_member {
        for f in fronting.iter().filter(|f| matches!(f.level.as_str(), "front" | "cocon")) {
            if !v.contains(&f.member_id) {
                v.push(f.member_id.clone());
            }
        }
    }
    v
}

/// Where a member has read up to in a channel: the marked message's time and id.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Mark {
    pub member: String,
    pub at: i64,
    pub id: String,
}

/// Members (not the account) whose mark is before the message `(at, id)`: who hasn't seen it.
/// Members without a mark in the channel aren't tracked there and aren't listed.
pub fn unseen_by(at: i64, id: &str, marks: &[Mark]) -> Vec<String> {
    marks
        .iter()
        .filter(|m| !m.member.is_empty() && (m.at, m.id.as_str()) < (at, id))
        .map(|m| m.member.clone())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_front_reads_and_a_later_message_is_unseen() {
        let f = |m: &str, level: &str| Fronter { member_id: m.into(), is_primary: false, level: level.into() };
        let front = [f("kai", "front"), f("rin", "cocon"), f("june", "present"), f("kai", "front")];
        assert_eq!(readers(false, &front), vec![""]);
        assert_eq!(readers(true, &front), vec!["", "kai", "rin"], "present members don't read");
        let marks = [
            Mark { member: String::new(), at: 10, id: "m1".into() },
            Mark { member: "kai".into(), at: 10, id: "m1".into() },
            Mark { member: "rin".into(), at: 5, id: "m0".into() },
        ];
        assert_eq!(unseen_by(10, "m1", &marks), vec!["rin"]);
        assert_eq!(unseen_by(10, "m2", &marks), vec!["kai", "rin"], "same time, later id");
        assert!(unseen_by(4, "m", &marks).is_empty());
    }
}
