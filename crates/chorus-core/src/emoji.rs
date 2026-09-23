//! Server-wide custom emoji names (D-054). Pure checks shared by the server and clients.

/// The user-facing `:name:` token: 2–32 lowercase ASCII letters, digits or underscores.
pub fn valid_name(name: &str) -> bool {
    (2..=32).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

/// May `id` claim `name` among projected emoji rows? Retired names can be reused.
pub fn name_available<'a>(name: &str, id: &str, rows: impl IntoIterator<Item = (&'a str, &'a str, bool)>) -> bool {
    valid_name(name)
        && rows.into_iter().all(|(other_id, other_name, deleted)| deleted || other_id == id || other_name != name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_are_unique_among_live_emoji_and_retired_names_can_be_reused() {
        assert!(valid_name("ka_wave"));
        for invalid in ["k", "A_wave", "two-words", "emoji!"] {
            assert!(!valid_name(invalid));
        }
        assert!(!valid_name(&"x".repeat(33)));
        assert!(!name_available("ka_wave", "new", [("old", "ka_wave", false)]));
        assert!(name_available("ka_wave", "old", [("old", "ka_wave", false)]));
        assert!(name_available("ka_wave", "new", [("old", "ka_wave", true)]));
    }
}
