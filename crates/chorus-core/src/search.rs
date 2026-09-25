//! Message search (SPEC §5.3): words and the filters `from:` `in:` `has:` `before:` `after:`
//! `is:pinned`, parsed once here so the server's search, the web's local search and Android
//! find the same messages.
//!
//! ```text
//! lunch from:Kai in:#general has:image after:2026-09-01 is:pinned
//! "trip plans" from:@june from:rin before:30d has:link
//! ```
//!
//! Every word must start a word of the message's text or content warning (prefix match, case
//! and common Latin accents ignored, like SQLite FTS5's `unicode61` tokenizer). Several `from:`
//! (a member's name or id) match any of them, likewise `in:` (a channel's name or id); every
//! `has:` must hold (`image`, `file` (not an image), `attachment`, `link`). `before:`/`after:`
//! take a date (`2026-09-01`, a day in the searcher's time zone: before its start, after its
//! end) or an age (`30d`, `12h`, `2w`: older than, newer than). Unknown `key:value` words are
//! plain words.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

pub use crate::feed::{ParseError, TimeRef};

pub const HAS: &[&str] = &["image", "file", "attachment", "link"];
const KEYS: &[&str] = &["from", "in", "has", "before", "after", "is"];

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Query {
    /// Folded words, each a prefix to find.
    pub words: Vec<String>,
    /// Member names (folded) or ids.
    pub from: Vec<String>,
    /// Channel names (folded) or ids.
    #[serde(rename = "in")]
    pub channels: Vec<String>,
    pub has: Vec<String>,
    pub before: Option<TimeRef>,
    pub after: Option<TimeRef>,
    pub pinned: bool,
}

impl Query {
    /// Nothing to search for.
    pub fn is_empty(&self) -> bool {
        self.words.is_empty()
            && self.from.is_empty()
            && self.channels.is_empty()
            && self.has.is_empty()
            && self.before.is_none()
            && self.after.is_none()
            && !self.pinned
    }

    /// SQLite FTS5 `MATCH` text for the words (each a quoted prefix token), or `None`.
    pub fn fts(&self) -> Option<String> {
        (!self.words.is_empty())
            .then(|| self.words.iter().map(|w| format!("\"{}\"*", w.replace('"', ""))).collect::<Vec<_>>().join(" "))
    }
}

/// Lowercase, with the accents Unicode decomposition takes off Latin letters (é → e, ñ → n;
/// not ß, æ, ø or ł, which don't decompose), like SQLite FTS5's `unicode61` tokenizer and the
/// web's `normalize('NFKD')` index, so all three agree on which words match.
pub fn fold(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars().flat_map(char::to_lowercase) {
        out.push(match c {
            'à'..='å' | 'ā' | 'ă' | 'ą' => 'a',
            'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' => 'c',
            'ď' => 'd',
            'è'..='ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' => 'e',
            'ĝ' | 'ğ' | 'ġ' | 'ģ' => 'g',
            'ĥ' => 'h',
            'ì'..='ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' => 'i',
            'ĵ' => 'j',
            'ķ' => 'k',
            'ĺ' | 'ļ' | 'ľ' => 'l',
            'ñ' | 'ń' | 'ņ' | 'ň' => 'n',
            'ò'..='ö' | 'ō' | 'ŏ' | 'ő' => 'o',
            'ŕ' | 'ŗ' | 'ř' => 'r',
            'ś' | 'ŝ' | 'ş' | 'š' => 's',
            'ţ' | 'ť' => 't',
            'ù'..='ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' => 'u',
            'ŵ' => 'w',
            'ý' | 'ÿ' | 'ŷ' => 'y',
            'ź' | 'ż' | 'ž' => 'z',
            other => other,
        });
    }
    out
}

/// Days since 1970-01-01 for `YYYY-MM-DD` (proleptic Gregorian).
pub fn days_from_civil(date: &str) -> Option<i64> {
    let mut parts = date.split('-');
    let (y, m, d): (i64, i64, i64) =
        (parts.next()?.parse().ok()?, parts.next()?.parse().ok()?, parts.next()?.parse().ok()?);
    if parts.next().is_some() || !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    Some(era * 146_097 + doe - 719_468)
}

/// When `date` starts in a time zone `tz_offset_min` minutes east of UTC.
pub fn date_start(date: &str, tz_offset_min: i32) -> Option<i64> {
    days_from_civil(date).map(|d| d * DAY - i64::from(tz_offset_min) * 60_000)
}

/// The words of a text as search sees them: folded runs of letters and digits.
pub fn words(s: &str) -> Vec<String> {
    fold(s).split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).map(str::to_string).collect()
}

/// `word`, `key:value`, `key:"quoted value"` or `"quoted words"`, split on spaces.
fn split(src: &str) -> Result<Vec<(usize, String)>, ParseError> {
    let chars: Vec<char> = src.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        if chars[i].is_whitespace() {
            i += 1;
            continue;
        }
        let start = i;
        let mut part = String::new();
        let mut quoted_at = None;
        while i < chars.len() && (quoted_at.is_some() || !chars[i].is_whitespace()) {
            if chars[i] == '"' {
                quoted_at = if quoted_at.is_some() { None } else { Some(i) };
            } else {
                part.push(chars[i]);
            }
            i += 1;
        }
        if let Some(at) = quoted_at {
            return Err(ParseError { pos: at, message: "unclosed quote".into() });
        }
        out.push((start, part));
    }
    Ok(out)
}

fn time_ref(v: &str, pos: usize) -> Result<TimeRef, ParseError> {
    let bad = || ParseError { pos, message: format!("expected a date (2026-09-01) or an age (30d, 12h), got {v}") };
    let b = v.as_bytes();
    if b.len() == 10 && b[4] == b'-' && b[7] == b'-' {
        let ok = v.chars().enumerate().all(|(i, c)| if i == 4 || i == 7 { c == '-' } else { c.is_ascii_digit() });
        return if ok { Ok(TimeRef::Date { date: v.to_string() }) } else { Err(bad()) };
    }
    let (num, unit) = v.split_at(v.len().saturating_sub(1));
    let n: i64 = num.parse().map_err(|_| bad())?;
    let unit_ms = match unit {
        "m" => 60_000,
        "h" => 3_600_000,
        "d" => 86_400_000,
        "w" => 7 * 86_400_000,
        "y" => 365 * 86_400_000,
        _ => return Err(bad()),
    };
    Ok(TimeRef::Ago { ms: n.saturating_mul(unit_ms) })
}

/// Parse a search box. An empty query is `Ok` and [`Query::is_empty`].
pub fn parse(src: &str) -> Result<Query, ParseError> {
    let mut q = Query::default();
    for (pos, part) in split(src)? {
        let keyed = part.split_once(':').filter(|(k, _)| KEYS.contains(&k.to_ascii_lowercase().as_str()));
        let Some((key, value)) = keyed else {
            q.words.extend(words(&part));
            continue;
        };
        let key = key.to_ascii_lowercase();
        if value.is_empty() {
            return Err(ParseError { pos, message: format!("{key}: needs a value") });
        }
        match key.as_str() {
            "from" => q.from.push(fold(value.trim_start_matches('@'))),
            "in" => q.channels.push(fold(value.trim_start_matches('#'))),
            "has" => {
                let v = value.to_ascii_lowercase();
                if !HAS.contains(&v.as_str()) {
                    return Err(ParseError { pos, message: format!("has is one of {}", HAS.join(", ")) });
                }
                if !q.has.contains(&v) {
                    q.has.push(v);
                }
            }
            "before" => q.before = Some(time_ref(value, pos)?),
            "after" => q.after = Some(time_ref(value, pos)?),
            _ if value.eq_ignore_ascii_case("pinned") => q.pinned = true,
            _ => return Err(ParseError { pos, message: "is: takes pinned".into() }),
        }
    }
    Ok(q)
}

/// What a message offers the filters.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Candidate {
    pub text: String,
    #[serde(default)]
    pub cw: Option<String>,
    /// (member id, name) of each author.
    #[serde(default)]
    pub authors: Vec<(String, String)>,
    /// (channel id, name).
    pub channel: (String, String),
    pub at: i64,
    /// Attachment mime types (empty string when unknown).
    #[serde(default)]
    pub mimes: Vec<String>,
    /// A `url` or `text_link` entity.
    #[serde(default)]
    pub link: bool,
    #[serde(default)]
    pub pinned: bool,
}

/// What `before:`/`after:` mean now: the time, and the start of each date in the searcher's
/// time zone (`dates["2026-09-01"]`), which the caller knows.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Context {
    pub now: i64,
    #[serde(default)]
    pub dates: BTreeMap<String, i64>,
}

const DAY: i64 = 86_400_000;

impl Context {
    /// A context for `query` in a time zone `tz_offset_min` minutes east of UTC.
    pub fn at(now: i64, tz_offset_min: i32, query: &Query) -> Context {
        let dates = crate::sort::map([&query.before, &query.after].into_iter().flatten().filter_map(|t| match t {
            TimeRef::Date { date } => date_start(date, tz_offset_min).map(|at| (date.clone(), at)),
            TimeRef::Ago { .. } => None,
        }));
        Context { now, dates }
    }

    /// `occurred_at` must be below this (exclusive).
    pub fn before(&self, t: &TimeRef) -> Option<i64> {
        match t {
            TimeRef::Ago { ms } => Some(self.now.saturating_sub(*ms)),
            TimeRef::Date { date } => self.dates.get(date).copied(),
        }
    }
    /// `occurred_at` must be at or above this.
    pub fn after(&self, t: &TimeRef) -> Option<i64> {
        match t {
            TimeRef::Ago { ms } => Some(self.now.saturating_sub(*ms)),
            TimeRef::Date { date } => self.dates.get(date).map(|start| start + DAY),
        }
    }
}

/// A name or id filter value matches an (id, name) pair.
pub fn names(filter: &str, id: &str, name: &str) -> bool {
    filter == id || filter == fold(name)
}

pub fn matches(q: &Query, c: &Candidate, ctx: &Context) -> bool {
    if !q.words.is_empty() {
        let mut have = words(&c.text);
        if let Some(cw) = &c.cw {
            have.extend(words(cw));
        }
        if !q.words.iter().all(|w| have.iter().any(|h| h.starts_with(w.as_str()))) {
            return false;
        }
    }
    if !q.from.is_empty() && !q.from.iter().any(|f| c.authors.iter().any(|(id, name)| names(f, id, name))) {
        return false;
    }
    if !q.channels.is_empty() && !q.channels.iter().any(|f| names(f, &c.channel.0, &c.channel.1)) {
        return false;
    }
    let image = |m: &String| m.starts_with("image/");
    for h in &q.has {
        let ok = match h.as_str() {
            "image" => c.mimes.iter().any(image),
            "file" => c.mimes.iter().any(|m| !image(m)),
            "attachment" => !c.mimes.is_empty(),
            "link" => c.link,
            _ => false,
        };
        if !ok {
            return false;
        }
    }
    if let Some(t) = &q.before
        && ctx.before(t).is_none_or(|b| c.at >= b)
    {
        return false;
    }
    if let Some(t) = &q.after
        && ctx.after(t).is_none_or(|a| c.at < a)
    {
        return false;
    }
    !q.pinned || c.pinned
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cand() -> Candidate {
        Candidate {
            text: "Lunch at the Café, see https://x.test".into(),
            cw: Some("food".into()),
            authors: vec![("m1".into(), "Kai".into())],
            channel: ("c1".into(), "general".into()),
            at: 1_000 * DAY,
            mimes: vec!["image/png".into()],
            link: true,
            pinned: false,
        }
    }

    #[test]
    fn parses_filters_and_words() {
        let q = parse(
            r#"lunch From:@Kai in:#general has:image has:image "café plans" before:2026-09-01 after:30d is:pinned x:y"#,
        )
        .unwrap();
        assert_eq!(q.words, vec!["lunch", "cafe", "plans", "x", "y"]);
        assert_eq!(
            (q.from.clone(), q.channels.clone(), q.has.clone()),
            (vec!["kai".into()], vec!["general".into()], vec!["image".into()])
        );
        assert_eq!(q.before, Some(TimeRef::Date { date: "2026-09-01".into() }));
        assert_eq!(q.after, Some(TimeRef::Ago { ms: 30 * DAY }));
        assert!(q.pinned);
        assert_eq!(q.fts().unwrap(), r#""lunch"* "cafe"* "plans"* "x"* "y"*"#);
        assert!(parse("").unwrap().is_empty());
        for bad in ["has:gif", "before:yesterday", "from:", "is:starred", "\"open"] {
            assert!(parse(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn matches_like_the_server() {
        let ctx = Context { now: 1_010 * DAY, dates: BTreeMap::from([("2026-09-01".into(), 1_001 * DAY)]) };
        let yes = |s: &str| matches(&parse(s).unwrap(), &cand(), &ctx);
        for s in [
            "lun",
            "cafe",
            "CAFÉ",
            "foo",
            "from:kai",
            "from:m1 from:june",
            "in:general",
            "has:image has:link",
            "has:attachment",
            "before:2026-09-01",
            "before:5d",
            "after:30d",
            "",
        ] {
            assert!(yes(s), "{s}");
        }
        for s in [
            "dinner",
            "unch",
            "from:june",
            "in:random",
            "has:file",
            "after:2026-09-01",
            "after:5d",
            "is:pinned",
            "before:2027-01-01",
        ] {
            assert!(!yes(s), "{s}");
        }
        assert_eq!(fold("Ærø Straße ÉCOLE"), "ærø straße ecole", "only what decomposes");
        assert_eq!(date_start("1970-01-02", 60), Some(DAY - 3_600_000));
        let q = parse("before:2026-09-01").unwrap();
        assert_eq!(Context::at(0, 0, &q).dates["2026-09-01"], days_from_civil("2026-09-01").unwrap() * DAY);
    }
}
