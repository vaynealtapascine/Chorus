//! Message ticks (D-131): the clock, one tick and two ticks on our own messages, and the clamp
//! the server puts on another account's read mark.
//!
//! A message shows a clock until the server has stamped it, one tick once it is stored, and two
//! once someone other than its authors has read it: a member of our own system (their mark comes
//! from per-member reading, `reading`), or, when both accounts opted in, another account (its
//! mark arrives already clamped by [`upto`]). The account's own row never counts: reading your own
//! message is not a receipt. Pure: no IO, no clocks.

use serde::{Deserialize, Serialize};

use crate::reading::Mark;

/// One of our own messages, as far as ticks care.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct Msg {
    pub at: i64,
    pub id: String,
    /// Members the message is written as (its speakers); their own marks don't count.
    #[serde(default)]
    pub authors: Vec<String>,
    /// The server has stamped it.
    pub acked: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Tick {
    Pending,
    Saved,
    Read,
}

/// The tick of one message. `member_marks` are our own members' marks in the channel (rows of the
/// account itself, `member == ""`, are ignored); `foreign` are other accounts' marks, one per
/// account, already clamped to our messages (`member` holds the account id).
pub fn tick(msg: &Msg, member_marks: &[Mark], foreign: &[Mark]) -> Tick {
    if !msg.acked {
        return Tick::Pending;
    }
    let reached = |m: &Mark| (m.at, m.id.as_str()) >= (msg.at, msg.id.as_str());
    let by_member = member_marks.iter().any(|m| !m.member.is_empty() && !msg.authors.contains(&m.member) && reached(m));
    if by_member || foreign.iter().any(reached) { Tick::Read } else { Tick::Saved }
}

/// The ticks of a list of messages, in the same order.
pub fn ticks_for(msgs: &[Msg], member_marks: &[Mark], foreign: &[Mark]) -> Vec<Tick> {
    msgs.iter().map(|m| tick(m, member_marks, foreign)).collect()
}

/// The latest of `own` (`(at, id)` pairs, any order) at or before the reader's mark: all that a
/// receipt may say. The reader's raw mark is never returned, since it could point at their own
/// aside or at a message the asker may not see.
pub fn upto(mark: (i64, &str), own: &[(i64, String)]) -> Option<(i64, String)> {
    own.iter().filter(|(at, id)| (*at, id.as_str()) <= mark).max().cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(at: i64, id: &str, authors: &[&str], acked: bool) -> Msg {
        Msg { at, id: id.into(), authors: authors.iter().map(|s| (*s).into()).collect(), acked }
    }
    fn mark(member: &str, at: i64, id: &str) -> Mark {
        Mark { member: member.into(), at, id: id.into() }
    }

    #[test]
    fn pending_saved_read() {
        assert_eq!(tick(&msg(10, "a", &["kai"], false), &[mark("rin", 99, "z")], &[]), Tick::Pending);
        assert_eq!(tick(&msg(10, "a", &["kai"], true), &[], &[]), Tick::Saved);
        assert_eq!(tick(&msg(10, "a", &["kai"], true), &[mark("rin", 10, "a")], &[]), Tick::Read);
    }

    #[test]
    fn an_author_or_the_account_row_does_not_count() {
        let m = msg(10, "a", &["kai"], true);
        assert_eq!(tick(&m, &[mark("kai", 50, "z")], &[]), Tick::Saved);
        assert_eq!(tick(&m, &[mark("", 50, "z")], &[]), Tick::Saved);
        assert_eq!(tick(&m, &[mark("kai", 50, "z"), mark("rin", 9, "z")], &[]), Tick::Saved, "rin is behind");
    }

    #[test]
    fn equal_time_is_broken_by_id() {
        let m = msg(10, "b", &[], true);
        assert_eq!(tick(&m, &[mark("rin", 10, "a")], &[]), Tick::Saved);
        assert_eq!(tick(&m, &[mark("rin", 10, "b")], &[]), Tick::Read);
        assert_eq!(tick(&m, &[mark("rin", 10, "c")], &[]), Tick::Read);
    }

    #[test]
    fn a_foreign_mark_reads_what_it_reaches() {
        let own = [msg(10, "a", &[], true), msg(20, "b", &[], true)];
        let theirs = [mark("acct", 10, "a")];
        assert_eq!(ticks_for(&own, &[], &theirs), vec![Tick::Read, Tick::Saved]);
    }

    #[test]
    fn upto_clamps_to_our_latest_message() {
        let own = vec![(30, "c".to_string()), (10, "a".to_string()), (20, "b".to_string())];
        assert_eq!(upto((25, "x"), &own), Some((20, "b".into())));
        assert_eq!(upto((30, "c"), &own), Some((30, "c".into())));
        assert_eq!(upto((99, "z"), &own), Some((30, "c".into())), "never beyond our messages");
        assert_eq!(upto((9, "z"), &own), None, "a mark over only their messages names nothing");
        assert_eq!(upto((10, "A"), &own), None, "equal time: ids decide");
        assert_eq!(upto((10, "a"), &own), Some((10, "a".into())));
        assert_eq!(upto((5, "a"), &[]), None);
    }
}
