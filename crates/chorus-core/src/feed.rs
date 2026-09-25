//! Feed filter language (SPEC.md §6.4).
//!
//! ```text
//! from:@stars kind:entry mood:tired -tag:vent since:30d
//! from:list:"close friends" has:image
//! (from:@kai or from:@juniper) reply:false
//! ```
//!
//! Space = AND, `or` (lowest precedence), parentheses, leading `-` = NOT. Keys: `from`, `kind`,
//! `tag`, `mood`, `has`, `reply`, `in`, `since`, `until`, `fronting`. Anything else is free text.
//! The AST is stored as JSON in `feed.query_ast`. Names are resolved to ids at evaluation time
//! (by the caller's `Context`), so a feed reads the way it was written.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(remote = "Self", rename_all = "snake_case")]
pub enum Expr {
    And { args: Vec<Expr> },
    Or { args: Vec<Expr> },
    Not { arg: Box<Expr> },
    From { from: FromRef },
    Kind { value: String },
    Tag { value: String },
    Mood { value: String },
    Has { value: String },
    Reply { value: bool },
    In { value: String },
    Since { at: TimeRef },
    Until { at: TimeRef },
    Fronting { value: bool },
    Text { value: String },
}
crate::tagged!(Expr, "op");

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(remote = "Self", rename_all = "snake_case")]
pub enum FromRef {
    /// `@name` — a member or group name (or short id), resolved by the caller.
    Name { name: String },
    /// `list:"name"`.
    List { name: String },
}
crate::tagged!(FromRef, "type");

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(remote = "Self", rename_all = "snake_case")]
pub enum TimeRef {
    /// Relative to now, in milliseconds (`30d`, `12h`, `2w`, `45m`).
    Ago { ms: i64 },
    /// A local date `YYYY-MM-DD` (start of day in the viewer's timezone).
    Date { date: String },
}
crate::tagged!(TimeRef, "type");

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{message} (at character {pos})")]
pub struct ParseError {
    pub pos: usize,
    pub message: String,
}

pub const KINDS: &[&str] = &["note", "entry", "message"];
pub const HAS: &[&str] = &["image", "attachment", "link", "video", "audio", "poll"];

// ─── tokenizer ───────────────────────────────────────────────────────────────

#[derive(Clone, Debug, PartialEq)]
enum Tok {
    LParen,
    RParen,
    Or,
    Not,
    /// key:value or bare word; `quoted` marks a quoted value/word.
    Term {
        key: Option<String>,
        value: String,
    },
}

fn tokenize(src: &str) -> Result<Vec<(usize, Tok)>, ParseError> {
    let chars: Vec<char> = src.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    let read_quoted = |i: &mut usize| -> Result<String, ParseError> {
        let start = *i;
        *i += 1;
        let mut s = String::new();
        while *i < chars.len() && chars[*i] != '"' {
            if chars[*i] == '\\' && *i + 1 < chars.len() {
                *i += 1;
            }
            s.push(chars[*i]);
            *i += 1;
        }
        if *i >= chars.len() {
            return Err(ParseError { pos: start, message: "unclosed quote".into() });
        }
        *i += 1;
        Ok(s)
    };
    while i < chars.len() {
        let c = chars[i];
        if c.is_whitespace() {
            i += 1;
            continue;
        }
        let pos = i;
        match c {
            '(' => {
                out.push((pos, Tok::LParen));
                i += 1;
            }
            ')' => {
                out.push((pos, Tok::RParen));
                i += 1;
            }
            '-' if chars.get(i + 1).is_some_and(|n| !n.is_whitespace()) => {
                out.push((pos, Tok::Not));
                i += 1;
            }
            '"' => {
                let v = read_quoted(&mut i)?;
                out.push((pos, Tok::Term { key: None, value: v }));
            }
            _ => {
                let mut word = String::new();
                while i < chars.len() && !chars[i].is_whitespace() && !matches!(chars[i], '(' | ')' | '"') {
                    word.push(chars[i]);
                    i += 1;
                }
                if let Some((k, v)) = word.split_once(':').filter(|(k, _)| is_key(k)) {
                    let mut value = v.to_string();
                    // key:"quoted value" and from:list:"quoted"
                    if i < chars.len() && chars[i] == '"' && (value.is_empty() || value.ends_with(':')) {
                        value.push_str(&read_quoted(&mut i)?);
                    }
                    if value.is_empty() {
                        return Err(ParseError { pos, message: format!("{k}: needs a value") });
                    }
                    out.push((pos, Tok::Term { key: Some(k.to_ascii_lowercase()), value }));
                } else if word.eq_ignore_ascii_case("or") {
                    out.push((pos, Tok::Or));
                } else {
                    out.push((pos, Tok::Term { key: None, value: word }));
                }
            }
        }
    }
    Ok(out)
}

fn is_key(k: &str) -> bool {
    matches!(
        k.to_ascii_lowercase().as_str(),
        "from" | "kind" | "tag" | "mood" | "has" | "reply" | "in" | "since" | "until" | "fronting"
    )
}

// ─── parser ──────────────────────────────────────────────────────────────────

struct Parser {
    toks: Vec<(usize, Tok)>,
    i: usize,
    len: usize,
}

impl Parser {
    fn peek(&self) -> Option<&Tok> {
        self.toks.get(self.i).map(|t| &t.1)
    }
    fn pos(&self) -> usize {
        self.toks.get(self.i).map_or(self.len, |t| t.0)
    }
    fn err<T>(&self, m: &str) -> Result<T, ParseError> {
        Err(ParseError { pos: self.pos(), message: m.into() })
    }

    fn or(&mut self) -> Result<Expr, ParseError> {
        let mut args = vec![self.and()?];
        while self.peek() == Some(&Tok::Or) {
            self.i += 1;
            args.push(self.and()?);
        }
        Ok(if args.len() == 1 { args.pop().unwrap_or_else(|| unreachable!()) } else { Expr::Or { args } })
    }

    fn and(&mut self) -> Result<Expr, ParseError> {
        let mut args = Vec::new();
        while let Some(t) = self.peek() {
            if matches!(t, Tok::Or | Tok::RParen) {
                break;
            }
            args.push(self.unary()?);
        }
        match args.len() {
            0 => self.err("expected a filter"),
            1 => Ok(args.pop().unwrap_or_else(|| unreachable!())),
            _ => Ok(Expr::And { args }),
        }
    }

    fn unary(&mut self) -> Result<Expr, ParseError> {
        if self.peek() == Some(&Tok::Not) {
            self.i += 1;
            return Ok(Expr::Not { arg: Box::new(self.unary()?) });
        }
        self.atom()
    }

    fn atom(&mut self) -> Result<Expr, ParseError> {
        let pos = self.pos();
        match self.toks.get(self.i).cloned() {
            Some((_, Tok::LParen)) => {
                self.i += 1;
                let e = self.or()?;
                if self.peek() != Some(&Tok::RParen) {
                    return self.err("missing )");
                }
                self.i += 1;
                Ok(e)
            }
            Some((_, Tok::Term { key, value })) => {
                self.i += 1;
                term(key.as_deref(), value, pos)
            }
            _ => self.err("expected a filter"),
        }
    }
}

fn boolean(v: &str, pos: usize) -> Result<bool, ParseError> {
    match v.to_ascii_lowercase().as_str() {
        "true" | "yes" | "1" => Ok(true),
        "false" | "no" | "0" => Ok(false),
        _ => Err(ParseError { pos, message: format!("expected true or false, got {v}") }),
    }
}

fn time_ref(v: &str, pos: usize) -> Result<TimeRef, ParseError> {
    let bad = || ParseError { pos, message: format!("expected a date (2026-09-01) or an age (30d, 12h), got {v}") };
    if v.len() == 10 && v.as_bytes()[4] == b'-' && v.as_bytes()[7] == b'-' {
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

fn term(key: Option<&str>, value: String, pos: usize) -> Result<Expr, ParseError> {
    let lower = value.to_lowercase();
    Ok(match key {
        None => Expr::Text { value },
        Some("from") => {
            if let Some(list) = value.strip_prefix("list:") {
                Expr::From { from: FromRef::List { name: list.to_string() } }
            } else {
                Expr::From { from: FromRef::Name { name: value.trim_start_matches('@').to_string() } }
            }
        }
        Some("kind") if KINDS.contains(&lower.as_str()) => Expr::Kind { value: lower },
        Some("kind") => return Err(ParseError { pos, message: format!("kind is one of {}", KINDS.join(", ")) }),
        Some("has") if HAS.contains(&lower.as_str()) => Expr::Has { value: lower },
        Some("has") => return Err(ParseError { pos, message: format!("has is one of {}", HAS.join(", ")) }),
        Some("tag") => Expr::Tag { value: lower.trim_start_matches('#').to_string() },
        Some("mood") => Expr::Mood { value: lower },
        Some("in") => Expr::In { value: value.trim_start_matches('#').to_string() },
        Some("reply") => Expr::Reply { value: boolean(&value, pos)? },
        Some("fronting") => Expr::Fronting { value: boolean(&value, pos)? },
        Some("since") => Expr::Since { at: time_ref(&value, pos)? },
        Some("until") => Expr::Until { at: time_ref(&value, pos)? },
        Some(other) => return Err(ParseError { pos, message: format!("unknown key {other}") }),
    })
}

/// Parse a feed query. Positions in errors are character indexes for the editor.
pub fn parse(src: &str) -> Result<Expr, ParseError> {
    let toks = tokenize(src)?;
    let len = src.chars().count();
    if toks.is_empty() {
        return Err(ParseError { pos: 0, message: "empty query".into() });
    }
    let mut p = Parser { toks, i: 0, len };
    let e = p.or()?;
    if p.i < p.toks.len() {
        return p.err("unexpected )");
    }
    Ok(e)
}

// ─── evaluation ──────────────────────────────────────────────────────────────

/// A timeline item as the evaluator sees it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Item {
    pub kind: String,
    pub author_ids: Vec<String>,
    pub tags: Vec<String>,
    pub mood: Option<String>,
    /// e.g. ["image", "attachment", "link"]
    pub has: Vec<String>,
    pub is_reply: bool,
    /// Channel id and name, for messages.
    pub channel: Option<(String, String)>,
    pub occurred_at: i64,
    /// Whether any author was fronting when it was written.
    pub author_fronting: bool,
    pub text: String,
}

/// What evaluation needs from the outside world.
pub trait Context {
    fn now(&self) -> i64;
    /// Member ids a `from:` reference covers (a member, every member of a group, a list).
    fn members(&self, from: &FromRef) -> Vec<String>;
    /// Start of a local date in UTC ms.
    fn date_start(&self, date: &str) -> Option<i64>;
}

pub fn eval(e: &Expr, item: &Item, ctx: &dyn Context) -> bool {
    match e {
        Expr::And { args } => args.iter().all(|a| eval(a, item, ctx)),
        Expr::Or { args } => args.iter().any(|a| eval(a, item, ctx)),
        Expr::Not { arg } => !eval(arg, item, ctx),
        Expr::From { from } => {
            let ids = ctx.members(from);
            item.author_ids.iter().any(|a| ids.contains(a))
        }
        Expr::Kind { value } => &item.kind == value,
        Expr::Tag { value } => item.tags.iter().any(|t| t.to_lowercase().trim_start_matches('#') == value),
        Expr::Mood { value } => item.mood.as_ref().is_some_and(|m| &m.to_lowercase() == value),
        Expr::Has { value } => item.has.contains(value),
        Expr::Reply { value } => item.is_reply == *value,
        Expr::In { value } => {
            item.channel.as_ref().is_some_and(|(id, name)| id == value || name.eq_ignore_ascii_case(value))
        }
        Expr::Since { at } => resolve(at, ctx).is_some_and(|t| item.occurred_at >= t),
        Expr::Until { at } => resolve(at, ctx).is_some_and(|t| item.occurred_at < t),
        Expr::Fronting { value } => item.author_fronting == *value,
        Expr::Text { value } => item.text.to_lowercase().contains(&value.to_lowercase()),
    }
}

fn resolve(t: &TimeRef, ctx: &dyn Context) -> Option<i64> {
    match t {
        TimeRef::Ago { ms } => Some(ctx.now() - ms),
        TimeRef::Date { date } => ctx.date_start(date),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Ctx;
    impl Context for Ctx {
        fn now(&self) -> i64 {
            100 * 86_400_000
        }
        fn members(&self, from: &FromRef) -> Vec<String> {
            match from {
                FromRef::Name { name } if name == "stars" => vec!["kai".into(), "june".into()],
                FromRef::Name { name } => vec![name.clone()],
                FromRef::List { name } if name == "close friends" => vec!["rin".into()],
                FromRef::List { .. } => vec![],
            }
        }
        fn date_start(&self, date: &str) -> Option<i64> {
            (date == "1970-03-01").then_some(59 * 86_400_000)
        }
    }

    fn item(author: &str, kind: &str) -> Item {
        Item { kind: kind.into(), author_ids: vec![author.into()], occurred_at: 95 * 86_400_000, ..Item::default() }
    }

    #[test]
    fn parses_spec_examples() {
        let e = parse(r#"from:@stars kind:entry mood:tired -tag:vent since:30d"#).unwrap();
        let Expr::And { args } = &e else { panic!("{e:?}") };
        assert_eq!(args.len(), 5);
        assert_eq!(args[4], Expr::Since { at: TimeRef::Ago { ms: 30 * 86_400_000 } });
        assert!(matches!(&args[3], Expr::Not { .. }));
        let e = parse(r#"from:list:"close friends" has:image"#).unwrap();
        assert_eq!(
            e,
            Expr::And {
                args: vec![
                    Expr::From { from: FromRef::List { name: "close friends".into() } },
                    Expr::Has { value: "image".into() }
                ]
            }
        );
        let e = parse("(from:@kai or from:@juniper) reply:false").unwrap();
        let Expr::And { args } = &e else { panic!() };
        assert!(matches!(&args[0], Expr::Or { args } if args.len() == 2));
    }

    #[test]
    fn errors_have_positions() {
        assert_eq!(parse("kind:banana").unwrap_err().pos, 0);
        assert_eq!(parse("tag:x (from:@a").unwrap_err().message, "missing )");
        assert!(parse("since:soon").is_err());
        assert!(parse("\"unclosed").is_err());
        assert!(parse("   ").is_err());
        assert!(parse("a )").is_err());
    }

    #[test]
    fn free_text_and_quoted() {
        let e = parse(r#""good day" tea"#).unwrap();
        assert_eq!(
            e,
            Expr::And { args: vec![Expr::Text { value: "good day".into() }, Expr::Text { value: "tea".into() }] }
        );
        // unknown key-looking words are text
        assert_eq!(parse("http://x").unwrap(), Expr::Text { value: "http://x".into() });
    }

    #[test]
    fn evaluates() {
        let e = parse("from:@stars -kind:message since:10d").unwrap();
        assert!(eval(&e, &item("kai", "note"), &Ctx));
        assert!(!eval(&e, &item("kai", "message"), &Ctx));
        assert!(!eval(&e, &item("rin", "note"), &Ctx));
        let mut old = item("kai", "note");
        old.occurred_at = 50 * 86_400_000;
        assert!(!eval(&e, &old, &Ctx));
        let e = parse("from:list:\"close friends\" or tag:#Art").unwrap();
        let mut art = item("x", "note");
        art.tags = vec!["art".into()];
        assert!(eval(&e, &art, &Ctx));
        assert!(eval(&e, &item("rin", "entry"), &Ctx));
        let e = parse("since:1970-03-01 Hello").unwrap();
        let mut hi = item("x", "note");
        hi.text = "well hello there".into();
        assert!(eval(&e, &hi, &Ctx));
    }

    #[test]
    fn ast_json_roundtrip() {
        let e = parse("(from:@kai or -has:image) fronting:yes until:2026-01-01").unwrap();
        let j = serde_json::to_string(&e).unwrap();
        assert!(j.contains(r#""op":"and""#));
        assert_eq!(serde_json::from_str::<Expr>(&j).unwrap(), e);
    }
}
