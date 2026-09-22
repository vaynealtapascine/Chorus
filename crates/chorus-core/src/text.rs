//! Rich text: plain text + entity ranges (D-041, DATA_MODEL.md §1).
//!
//! Offsets and lengths are UTF-16 code units, like Telegram, Android and JavaScript.
//!
//! ## Chorus markup (what people type)
//!
//! | Markup | Entity |
//! | --- | --- |
//! | `**bold**` | bold |
//! | `*italic*` or `_italic_` | italic (`_` only at word boundaries, so snake_case is safe) |
//! | `__underline__` | underline |
//! | `~~strike~~` | strikethrough |
//! | `\|\|spoiler\|\|` | spoiler |
//! | `` `code` `` | code |
//! | ```` ```lang⏎…⏎``` ```` | pre (with optional language) |
//! | `[label](https://…)` | text_link |
//! | `> line` | blockquote (consecutive lines merge) |
//! | `>> line` | expandable blockquote |
//! | `@name` | mention, if the resolver knows the name |
//! | `:name:` | custom_emoji, if the resolver knows it (text keeps `:name:`) |
//! | `https://…` | url (auto-detected) |
//!
//! A backslash escapes the next markup character. `\&` is an empty separator (like troff's): it
//! produces no text and lets the serializer write `**bold *it*\&**` unambiguously.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MentionTarget {
    Member,
    Group,
    Account,
    Front,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum EntityKind {
    Blockquote,
    ExpandableBlockquote,
    Pre {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        language: Option<String>,
    },
    TextLink { url: String },
    Bold,
    Italic,
    Underline,
    Strikethrough,
    Spoiler,
    Code,
    Url,
    Mention {
        target_type: MentionTarget,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        target_id: Option<String>,
    },
    CustomEmoji { emoji_id: String },
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Entity {
    #[serde(flatten)]
    pub kind: EntityKind,
    pub offset: u32,
    pub length: u32,
}

impl Entity {
    pub fn end(&self) -> u32 {
        self.offset + self.length
    }
}

/// Canonical order: by offset, then longer first, then kind. Parsers and projections store
/// entities in this order so equal content compares equal.
pub fn sort_entities(e: &mut [Entity]) {
    e.sort_by(|a, b| (a.offset, std::cmp::Reverse(a.length), &a.kind).cmp(&(b.offset, std::cmp::Reverse(b.length), &b.kind)));
}

/// Canonical form: overlapping or touching ranges of the same simple style (bold, italic,
/// underline, strikethrough, spoiler) merge into one, then entities are sorted.
pub fn normalize_entities(e: &mut Vec<Entity>) {
    let simple = |k: &EntityKind| {
        matches!(k, EntityKind::Bold | EntityKind::Italic | EntityKind::Underline | EntityKind::Strikethrough | EntityKind::Spoiler)
    };
    let mut others: Vec<Entity> = e.iter().filter(|x| !simple(&x.kind)).cloned().collect();
    let mut styled: Vec<Entity> = e.iter().filter(|x| simple(&x.kind)).cloned().collect();
    styled.sort_by(|a, b| (&a.kind, a.offset).cmp(&(&b.kind, b.offset)));
    let mut merged: Vec<Entity> = Vec::new();
    for x in styled {
        match merged.last_mut() {
            Some(m) if m.kind == x.kind && x.offset <= m.end() => {
                let end = m.end().max(x.end());
                m.length = end - m.offset;
            }
            _ => merged.push(x),
        }
    }
    others.extend(merged);
    sort_entities(&mut others);
    *e = others;
}

/// Names the parser can turn into mentions / custom emoji.
pub trait Resolver {
    fn mention(&self, name: &str) -> Option<(MentionTarget, Option<String>)>;
    fn emoji(&self, name: &str) -> Option<String>;
}

/// A resolver that knows nothing (only `@front` resolves).
pub struct NoNames;
impl Resolver for NoNames {
    fn mention(&self, name: &str) -> Option<(MentionTarget, Option<String>)> {
        (name == "front").then_some((MentionTarget::Front, None))
    }
    fn emoji(&self, _: &str) -> Option<String> {
        None
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Rich {
    pub text: String,
    pub entities: Vec<Entity>,
}

pub fn utf16_len(s: &str) -> u32 {
    s.encode_utf16().count() as u32
}

/// Substring by UTF-16 range (clamped; never splits a surrogate pair).
pub fn utf16_slice(s: &str, offset: u32, length: u32) -> &str {
    let (mut start, mut end) = (s.len(), s.len());
    let mut u = 0u32;
    for (i, c) in s.char_indices() {
        if u >= offset && start == s.len() {
            start = i;
        }
        if u >= offset + length {
            end = i;
            break;
        }
        u += c.len_utf16() as u32;
    }
    if start > end {
        return "";
    }
    &s[start..end]
}

// ─── parser ──────────────────────────────────────────────────────────────────

const ESCAPABLE: &[char] = &['\\', '*', '_', '~', '|', '`', '[', ']', '(', ')', '>', '@', ':'];

struct P<'a> {
    s: Vec<char>,
    out: String,
    u16: u32,
    ents: Vec<Entity>,
    r: &'a dyn Resolver,
    /// Nesting depth inside inline entities; code blocks only parse at depth 0.
    depth: usize,
}

fn is_word(c: char) -> bool {
    c.is_alphanumeric()
}

impl P<'_> {
    fn push(&mut self, c: char) {
        self.out.push(c);
        self.u16 += c.len_utf16() as u32;
    }

    fn push_str(&mut self, s: &str) {
        for c in s.chars() {
            self.push(c);
        }
    }

    fn at(&self, i: usize, pat: &str) -> bool {
        (i..).zip(pat.chars()).all(|(j, c)| self.s.get(j) == Some(&c))
    }

    /// A backslash at `j` that escapes the next char (or is the `\&` separator).
    fn is_escape(&self, j: usize) -> bool {
        self.s[j] == '\\' && self.s.get(j + 1).is_some_and(|n| *n == '&' || ESCAPABLE.contains(n))
    }

    /// Index of the unescaped closing `delim` in `[from, to)`, skipping code spans.
    fn find_close(&self, delim: &str, from: usize, to: usize) -> Option<usize> {
        let n = delim.chars().count();
        let d0 = delim.chars().next()?;
        let mut j = from;
        while j < to {
            let c = self.s[j];
            if self.is_escape(j) {
                j += 2;
                continue;
            }
            if c == '`' && delim != "`" {
                // skip a code span if it closes before `to`
                if let Some(k) = self.find_code_end(j + 1, to) {
                    j = k + 1;
                    continue;
                }
            }
            if self.at(j, delim) && j + n <= to && j > from {
                let before = self.s[j - 1];
                let after = self.s.get(j + n).copied();
                let single = n == 1;
                let ok_space = !before.is_whitespace();
                // a single `*`/`_` must not be half of a double
                let ok_single = !single || (after != Some(d0) && before != d0);
                let ok_word = d0 != '_' || !single || after.is_none_or(|a| !is_word(a));
                if ok_space && ok_single && ok_word {
                    return Some(j);
                }
            }
            j += 1;
        }
        None
    }

    fn find_code_end(&self, from: usize, to: usize) -> Option<usize> {
        let mut j = from;
        while j < to {
            match self.s[j] {
                '\\' if matches!(self.s.get(j + 1), Some('`' | '\\')) => j += 2,
                '`' => return Some(j),
                '\n' => return None,
                _ => j += 1,
            }
        }
        None
    }

    fn wrap(&mut self, kind: EntityKind, from: usize, to: usize) {
        let start = self.u16;
        self.depth += 1;
        self.inline(from, to);
        self.depth -= 1;
        let length = self.u16 - start;
        if length > 0 {
            self.ents.push(Entity { kind, offset: start, length });
        }
    }

    fn inline(&mut self, from: usize, to: usize) {
        let mut i = from;
        while i < to {
            let c = self.s[i];
            let prev = if i > from { Some(self.s[i - 1]) } else { None };
            // judged on emitted text, so separators and escapes can't fake a boundary
            let word_start = self.out.chars().last().is_none_or(|p| !is_word(p));

            if c == '\\' && i + 1 < to && self.s[i + 1] == '&' {
                i += 2;
                continue;
            }
            if c == '\\' && i + 1 < to && ESCAPABLE.contains(&self.s[i + 1]) {
                self.push(self.s[i + 1]);
                i += 2;
                continue;
            }
            // pre block
            if self.depth == 0 && self.at(i, "```")
                && let Some(end) = self.find_fence(i + 3, to) {
                    self.pre(i + 3, end);
                    i = end + 3;
                    continue;
                }
            if c == '`'
                && let Some(end) = self.find_code_end(i + 1, to)
                    && end > i + 1 {
                        let start = self.u16;
                        let mut j = i + 1;
                        while j < end {
                            if self.s[j] == '\\' && matches!(self.s.get(j + 1), Some('`' | '\\')) {
                                j += 1;
                            }
                            self.push(self.s[j]);
                            j += 1;
                        }
                        self.ents.push(Entity { kind: EntityKind::Code, offset: start, length: self.u16 - start });
                        i = end + 1;
                        continue;
                    }
            let mut matched = false;
            for (delim, kind) in [
                ("**", EntityKind::Bold),
                ("__", EntityKind::Underline),
                ("~~", EntityKind::Strikethrough),
                ("||", EntityKind::Spoiler),
                ("*", EntityKind::Italic),
                ("_", EntityKind::Italic),
            ] {
                if !self.at(i, delim) {
                    continue;
                }
                let n = delim.len();
                let next = self.s.get(i + n).copied();
                if next.is_none_or(char::is_whitespace) {
                    continue;
                }
                if n == 1 && (next == Some(c) || prev == Some(c)) {
                    continue;
                }
                if delim == "_" && !word_start {
                    continue;
                }
                if let Some(end) = self.find_close(delim, i + n, to) {
                    self.wrap(kind, i + n, end);
                    i = end + n;
                    matched = true;
                    break;
                }
            }
            if matched {
                continue;
            }
            if c == '['
                && let Some((label_end, url_start, url_end)) = self.find_link(i, to) {
                    let url: String = self.s[url_start..url_end].iter().collect();
                    self.wrap(EntityKind::TextLink { url }, i + 1, label_end);
                    i = url_end + 1;
                    continue;
                }
            if c == '@' && word_start {
                let mut j = i + 1;
                while j < to && (is_word(self.s[j]) || matches!(self.s[j], '_' | '.' | '-')) {
                    j += 1;
                }
                while j > i + 1 && matches!(self.s[j - 1], '.' | '-') {
                    j -= 1;
                }
                if j > i + 1 {
                    let name: String = self.s[i + 1..j].iter().collect();
                    if let Some((target_type, target_id)) = self.r.mention(&name) {
                        let start = self.u16;
                        self.push('@');
                        self.push_str(&name);
                        self.ents.push(Entity { kind: EntityKind::Mention { target_type, target_id }, offset: start, length: self.u16 - start });
                        i = j;
                        continue;
                    }
                }
            }
            if c == ':' {
                let mut j = i + 1;
                while j < to && j - i <= 33 && (self.s[j].is_ascii_lowercase() || self.s[j].is_ascii_digit() || self.s[j] == '_') {
                    j += 1;
                }
                if j < to && self.s[j] == ':' && j - i > 2 {
                    let name: String = self.s[i + 1..j].iter().collect();
                    if let Some(emoji_id) = self.r.emoji(&name) {
                        let start = self.u16;
                        self.push(':');
                        self.push_str(&name);
                        self.push(':');
                        self.ents.push(Entity { kind: EntityKind::CustomEmoji { emoji_id }, offset: start, length: self.u16 - start });
                        i = j + 1;
                        continue;
                    }
                }
            }
            if word_start && (self.at(i, "https://") || self.at(i, "http://")) {
                let mut j = i;
                // stop at characters that are markup, so a url never swallows a delimiter
                while j < to
                    && !self.s[j].is_whitespace()
                    && !matches!(self.s[j], '\\' | '*' | '|' | '`' | '[' | ']')
                    && !self.at(j, "~~")
                    && !self.at(j, "__")
                {
                    j += 1;
                }
                while j > i && matches!(self.s[j - 1], '.' | ',' | ';' | ':' | '!' | '?' | ')' | '"' | '\'') {
                    j -= 1;
                }
                let scheme_len = if self.at(i, "https://") { 8 } else { 7 };
                if j > i + scheme_len {
                    let start = self.u16;
                    for k in i..j {
                        self.push(self.s[k]);
                    }
                    self.ents.push(Entity { kind: EntityKind::Url, offset: start, length: self.u16 - start });
                    i = j;
                    continue;
                }
            }
            self.push(c);
            i += 1;
        }
    }

    fn find_fence(&self, from: usize, to: usize) -> Option<usize> {
        let mut j = from;
        while j + 3 <= to {
            if self.is_escape(j) {
                j += 2;
                continue;
            }
            if self.at(j, "```") {
                return Some(j);
            }
            j += 1;
        }
        None
    }

    fn pre(&mut self, from: usize, to: usize) {
        // optional language on the first line
        let mut body = from;
        let mut language = None;
        let line_end = (from..to).find(|&k| self.s[k] == '\n');
        if let Some(le) = line_end {
            let first: String = self.s[from..le].iter().collect();
            if first.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '#' | '.' | '_')) {
                if !first.is_empty() {
                    language = Some(first);
                }
                body = le + 1;
            }
        }
        let mut end = to;
        if end > body && self.s[end - 1] == '\n' {
            end -= 1;
        }
        let start = self.u16;
        let mut j = body;
        while j < end {
            if self.s[j] == '\\' && matches!(self.s.get(j + 1), Some('`' | '\\')) {
                j += 1;
            }
            self.push(self.s[j]);
            j += 1;
        }
        if self.u16 > start {
            self.ents.push(Entity { kind: EntityKind::Pre { language }, offset: start, length: self.u16 - start });
        }
    }

    fn find_link(&self, open: usize, to: usize) -> Option<(usize, usize, usize)> {
        let mut depth = 0;
        let mut j = open + 1;
        while j < to {
            match self.s[j] {
                '\\' if self.is_escape(j) => j += 1,
                '`' => {
                    if let Some(k) = self.find_code_end(j + 1, to) {
                        j = k;
                    }
                }
                '[' => depth += 1,
                ']' if depth > 0 => depth -= 1,
                ']' => break,
                '\n' => return None,
                _ => {}
            }
            j += 1;
        }
        if j >= to || j == open + 1 || self.s.get(j + 1) != Some(&'(') {
            return None;
        }
        let url_start = j + 2;
        let url_end = (url_start..to).find(|&k| self.s[k] == ')' || self.s[k].is_whitespace())?;
        if self.s[url_end] != ')' {
            return None;
        }
        let url: String = self.s[url_start..url_end].iter().collect();
        let ok = ["https://", "http://", "mailto:", "chorus://"].iter().any(|p| url.starts_with(p) && url.len() > p.len());
        ok.then_some((j, url_start, url_end))
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum LineKind {
    Normal,
    Quote,
    Expandable,
}

/// Unescaped ``` runs in a line (for tracking code fences across lines).
fn count_fences(line: &[char]) -> usize {
    let (mut i, mut n) = (0, 0);
    while i < line.len() {
        if line[i] == '\\' && line.get(i + 1).is_some_and(|c| *c == '&' || ESCAPABLE.contains(c)) {
            i += 2;
        } else if line[i..].starts_with(&['`', '`', '`']) {
            n += 1;
            i += 3;
        } else {
            i += 1;
        }
    }
    n
}

/// Parse Chorus markup into plain text + entities.
pub fn parse(src: &str, r: &dyn Resolver) -> Rich {
    let s: Vec<char> = src.chars().collect();
    // Split into lines (char ranges without the newline), classify, respecting code fences.
    let mut lines: Vec<(LineKind, usize, usize)> = Vec::new();
    let mut start = 0;
    let mut in_fence = false;
    for i in 0..=s.len() {
        if i == s.len() || s[i] == '\n' {
            let line = &s[start..i];
            let fence_toggles = count_fences(line) % 2 == 1;
            let (kind, content_start) = if in_fence {
                (LineKind::Normal, start)
            } else if line.starts_with(&['>', '>', ' ']) {
                (LineKind::Expandable, start + 3)
            } else if line.starts_with(&['>', '>']) && line.len() == 2 {
                (LineKind::Expandable, start + 2)
            } else if line.starts_with(&['>', ' ']) {
                (LineKind::Quote, start + 2)
            } else if line == ['>'] {
                (LineKind::Quote, start + 1)
            } else {
                (LineKind::Normal, start)
            };
            if fence_toggles {
                in_fence = !in_fence;
            }
            lines.push((kind, content_start, i));
            start = i + 1;
        }
    }
    let mut p = P { s, out: String::new(), u16: 0, ents: Vec::new(), r, depth: 0 };
    let mut k = 0;
    while k < lines.len() {
        let kind = lines[k].0;
        let mut g = k;
        while g + 1 < lines.len() && lines[g + 1].0 == kind {
            g += 1;
        }
        if k > 0 {
            p.push('\n');
        }
        match kind {
            LineKind::Normal => {
                let (from, to) = (lines[k].1, lines[g].2);
                p.inline(from, to);
            }
            LineKind::Quote | LineKind::Expandable => {
                let start = p.u16;
                for (n, &(_, from, to)) in lines[k..=g].iter().enumerate() {
                    if n > 0 {
                        p.push('\n');
                    }
                    p.inline(from, to);
                }
                let length = p.u16 - start;
                if length > 0 {
                    let kind = if kind == LineKind::Quote { EntityKind::Blockquote } else { EntityKind::ExpandableBlockquote };
                    p.ents.push(Entity { kind, offset: start, length });
                }
            }
        }
        k = g + 1;
    }
    let mut entities = p.ents;
    normalize_entities(&mut entities);
    Rich { text: p.out, entities }
}

// ─── serializer ──────────────────────────────────────────────────────────────

fn open_tok(k: &EntityKind) -> String {
    match k {
        EntityKind::Bold => "**".into(),
        EntityKind::Italic => "*".into(),
        EntityKind::Underline => "__".into(),
        EntityKind::Strikethrough => "~~".into(),
        EntityKind::Spoiler => "||".into(),
        EntityKind::Code => "`".into(),
        EntityKind::Pre { language } => format!("```{}\n", language.as_deref().unwrap_or("")),
        EntityKind::TextLink { .. } => "[".into(),
        _ => String::new(),
    }
}

fn is_delim(k: &EntityKind) -> bool {
    matches!(k, EntityKind::Bold | EntityKind::Italic | EntityKind::Underline | EntityKind::Strikethrough | EntityKind::Spoiler)
}

/// Append a delimiter token, separating it from an identical delimiter char with `\&`.
fn push_tok(out: &mut String, tok: &str) {
    if let (Some(last), Some(first)) = (out.chars().last(), tok.chars().next())
        && last == first && matches!(first, '*' | '_' | '~' | '|' | '`') {
            out.push_str("\\&");
        }
    out.push_str(tok);
}

fn close_tok(k: &EntityKind) -> String {
    match k {
        EntityKind::Pre { .. } => "\n```".into(),
        EntityKind::TextLink { url } => format!("]({url})"),
        other => open_tok(other),
    }
}

/// Serialize text + entities back to Chorus markup (for editing). Mentions, custom emoji and
/// urls need no markup: their text re-parses into the same entities. Crossing entities are
/// split so the output nests.
pub fn to_markup(rich: &Rich) -> String {
    let text: Vec<u16> = rich.text.encode_utf16().collect();
    let len = text.len() as u32;
    let mut ents: Vec<&Entity> = rich.entities.iter().filter(|e| e.end() <= len && e.length > 0).collect();
    ents.sort_by_key(|a| (a.offset, std::cmp::Reverse(a.length)));
    let is_quote = |k: &EntityKind| matches!(k, EntityKind::Blockquote | EntityKind::ExpandableBlockquote);
    let is_literal = |k: &EntityKind| matches!(k, EntityKind::Code | EntityKind::Pre { .. });
    let quotes: Vec<&Entity> = ents.iter().copied().filter(|e| is_quote(&e.kind)).collect();
    let inline: Vec<&Entity> = ents.iter().copied().filter(|e| !is_quote(&e.kind) && !open_tok(&e.kind).is_empty()).collect();

    let mut out = String::new();
    let mut stack: Vec<&Entity> = Vec::new();
    let mut line_start = true;
    let mut literal_depth = 0;
    let chars: Vec<(u32, char)> = {
        let mut v = Vec::new();
        let mut u = 0u32;
        for c in rich.text.chars() {
            v.push((u, c));
            u += c.len_utf16() as u32;
        }
        v
    };
    // A quote ending right after a newline has an empty last line, which still needs a prefix.
    let quote_at = |u: u32| {
        quotes
            .iter()
            .find(|q| {
                (q.offset <= u && u < q.end())
                    || (q.offset < u && q.end() == u && utf16_slice(&rich.text, u - 1, 1) == "\n")
            })
            .map(|q| &q.kind)
    };
    // single-line code blocks without a language use the inline ```x``` form, which also works
    // inside quotes
    let inline_pre = |e: &Entity| {
        matches!(&e.kind, EntityKind::Pre { language: None }) && !utf16_slice(&rich.text, e.offset, e.length).contains('\n')
    };
    let open_of = |e: &Entity| if inline_pre(e) { "```".to_string() } else { open_tok(&e.kind) };
    let close_of = |e: &Entity| if inline_pre(e) { "```".to_string() } else { close_tok(&e.kind) };
    let mut idx = 0;
    loop {
        let u = chars.get(idx).map_or(len, |c| c.0);
        // close entities ending here (reopen any we must pop through)
        let mut reopen = Vec::new();
        while stack.iter().any(|e| e.end() <= u) {
            let top = stack.pop().unwrap_or_else(|| unreachable!());
            // a closer can't follow whitespace; the separator makes it legal
            if is_delim(&top.kind) && out.ends_with(char::is_whitespace) {
                out.push_str("\\&");
            }
            if is_literal(&top.kind) {
                out.push_str(&close_of(top));
            } else {
                push_tok(&mut out, &close_of(top));
            }
            if is_literal(&top.kind) {
                literal_depth -= 1;
            }
            if top.end() > u {
                reopen.push(top);
            }
        }
        for e in reopen.into_iter().rev() {
            push_tok(&mut out, &open_of(e));
            if is_delim(&e.kind) && chars.get(idx).is_some_and(|c| c.1.is_whitespace()) {
                out.push_str("\\&");
            }
            if is_literal(&e.kind) {
                literal_depth += 1;
            }
            stack.push(e);
        }
        if idx >= chars.len() {
            // an empty final line inside a quote still needs its prefix
            if line_start && len > 0 {
                match quotes.iter().find(|q| q.end() == len).map(|q| &q.kind) {
                    Some(EntityKind::Blockquote) => out.push_str("> "),
                    Some(EntityKind::ExpandableBlockquote) => out.push_str(">> "),
                    _ => {}
                }
            }
            break;
        }
        let c = chars[idx].1;
        // auto-detected urls run to whitespace; stop them where the entity stopped
        let continues_atom = rich.entities.iter().any(|e| {
            e.end() == u
                && match e.kind {
                    EntityKind::Url => !c.is_whitespace(),
                    EntityKind::Mention { .. } => is_word(c) || matches!(c, '_' | '.' | '-'),
                    _ => false,
                }
        });
        if continues_atom {
            out.push_str("\\&");
        }
        if line_start {
            match quote_at(u) {
                Some(EntityKind::Blockquote) => out.push_str("> "),
                Some(EntityKind::ExpandableBlockquote) => out.push_str(">> "),
                _ => {}
            }
        }
        let len_before_tokens = out.len();
        for e in inline.iter().filter(|e| e.offset == u) {
            // entities inside code/pre can't be expressed; drop them
            if literal_depth > 0 {
                continue;
            }
            push_tok(&mut out, &open_of(e));
            // an opener can't precede whitespace; the separator makes it legal
            if is_delim(&e.kind) && c.is_whitespace() {
                out.push_str("\\&");
            }
            if is_literal(&e.kind) {
                literal_depth += 1;
            }
            stack.push(e);
        }
        let prev = if idx > 0 { Some(chars[idx - 1].1) } else { None };
        let next = chars.get(idx + 1).map(|c| c.1);
        if literal_depth > 0 {
            if c == '`' || c == '\\' {
                out.push('\\');
            }
        } else {
            let in_entity = |pred: &dyn Fn(&EntityKind) -> bool| {
                rich.entities.iter().any(|e| pred(&e.kind) && e.offset <= u && u < e.end())
            };
            // mentions, custom emoji and urls re-parse from their literal text
            let in_atom = in_entity(&|k| {
                matches!(k, EntityKind::Mention { .. } | EntityKind::CustomEmoji { .. } | EntityKind::Url)
            });
            // a '>' that would start a markup line must not read as a quote
            let quote_like = c == '>' && line_start && quote_at(u).is_none() && out.len() == len_before_tokens;
            let escape = quote_like || !in_atom && match c {
                '\\' | '*' | '_' | '~' | '|' | '`' | '[' | ']' => true,
                '@' => prev.is_none_or(|p| !is_word(p)) && next.is_some_and(|n| is_word(n) || n == '_')
                    && !in_entity(&|k| matches!(k, EntityKind::Mention { .. })),
                ':' => next.is_some_and(|n| n.is_ascii_lowercase() || n.is_ascii_digit() || n == '_')
                    && !in_entity(&|k| matches!(k, EntityKind::CustomEmoji { .. } | EntityKind::Url)),
                '(' | ')' => stack.iter().any(|e| matches!(e.kind, EntityKind::TextLink { .. })),
                _ => false,
            };
            if escape {
                out.push('\\');
            }
        }
        out.push(c);
        line_start = c == '\n';
        idx += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Names;
    impl Resolver for Names {
        fn mention(&self, name: &str) -> Option<(MentionTarget, Option<String>)> {
            match name {
                "kai" => Some((MentionTarget::Member, Some("m-kai".into()))),
                "stars" => Some((MentionTarget::Group, Some("g-stars".into()))),
                "front" => Some((MentionTarget::Front, None)),
                _ => None,
            }
        }
        fn emoji(&self, name: &str) -> Option<String> {
            (name == "kai_wave").then(|| "e1".into())
        }
    }

    fn e(kind: EntityKind, offset: u32, length: u32) -> Entity {
        Entity { kind, offset, length }
    }

    #[test]
    fn basic_inline() {
        let r = parse("a **b** *c* __d__ ~~e~~ ||f|| `g`", &NoNames);
        assert_eq!(r.text, "a b c d e f g");
        assert_eq!(
            r.entities,
            vec![
                e(EntityKind::Bold, 2, 1),
                e(EntityKind::Italic, 4, 1),
                e(EntityKind::Underline, 6, 1),
                e(EntityKind::Strikethrough, 8, 1),
                e(EntityKind::Spoiler, 10, 1),
                e(EntityKind::Code, 12, 1),
            ]
        );
    }

    #[test]
    fn nested_and_snake_case() {
        let r = parse("**bold _it_** and some_snake_case", &NoNames);
        assert_eq!(r.text, "bold it and some_snake_case");
        assert_eq!(r.entities, vec![e(EntityKind::Bold, 0, 7), e(EntityKind::Italic, 5, 2)]);
    }

    #[test]
    fn unmatched_is_literal() {
        let r = parse("2 * 3 = 6 and **open", &NoNames);
        assert_eq!(r.text, "2 * 3 = 6 and **open");
        assert!(r.entities.is_empty());
    }

    #[test]
    fn escapes() {
        let r = parse(r"\*not italic\* and \\", &NoNames);
        assert_eq!(r.text, r"*not italic* and \");
        assert!(r.entities.is_empty());
    }

    #[test]
    fn utf16_offsets_with_emoji() {
        let r = parse("🌌 **hi**", &NoNames);
        assert_eq!(r.entities, vec![e(EntityKind::Bold, 3, 2)]);
        assert_eq!(utf16_slice(&r.text, 3, 2), "hi");
    }

    #[test]
    fn links_mentions_emoji_urls() {
        let r = parse("[docs](https://x.org/a) hi @kai @nobody :kai_wave: :nope: see https://a.b/c.", &Names);
        assert_eq!(r.text, "docs hi @kai @nobody :kai_wave: :nope: see https://a.b/c.");
        assert_eq!(
            r.entities,
            vec![
                e(EntityKind::TextLink { url: "https://x.org/a".into() }, 0, 4),
                e(EntityKind::Mention { target_type: MentionTarget::Member, target_id: Some("m-kai".into()) }, 8, 4),
                e(EntityKind::CustomEmoji { emoji_id: "e1".into() }, 21, 10),
                e(EntityKind::Url, 43, 13),
            ]
        );
    }

    #[test]
    fn link_needs_safe_scheme() {
        let r = parse("[x](javascript:alert(1))", &NoNames);
        assert!(r.entities.is_empty());
    }

    #[test]
    fn quotes_and_expandable() {
        let r = parse("> one\n> **two**\nplain\n>> more", &NoNames);
        assert_eq!(r.text, "one\ntwo\nplain\nmore");
        assert_eq!(
            r.entities,
            vec![e(EntityKind::Blockquote, 0, 7), e(EntityKind::Bold, 4, 3), e(EntityKind::ExpandableBlockquote, 14, 4)]
        );
    }

    #[test]
    fn pre_block_with_language_and_quote_char_inside() {
        let r = parse("look:\n```rust\n> not a quote\nlet x = `y`;\n```\nend", &NoNames);
        assert_eq!(r.text, "look:\n> not a quote\nlet x = `y`;\nend");
        assert_eq!(r.entities, vec![e(EntityKind::Pre { language: Some("rust".into()) }, 6, 26)]);
    }

    #[test]
    fn entity_json_shape() {
        let j = serde_json::to_string(&e(EntityKind::TextLink { url: "https://a".into() }, 1, 2)).unwrap();
        assert_eq!(j, r#"{"type":"text_link","url":"https://a","offset":1,"length":2}"#);
        let back: Entity = serde_json::from_str(&j).unwrap();
        assert_eq!(back.offset, 1);
        let j = serde_json::to_string(&e(EntityKind::Bold, 0, 1)).unwrap();
        assert_eq!(j, r#"{"type":"bold","offset":0,"length":1}"#);
    }

    #[test]
    fn markup_roundtrip_examples() {
        for src in [
            "a **b** *c* __d__ ~~e~~ ||f|| `g`",
            "**bold _it_** and snake_case",
            "> one\n> **two**\nplain\n>> more",
            "```rust\nlet x = `y`;\n```",
            "[docs](https://x.org/a) hi @kai :kai_wave: https://a.b/c",
            r"literal \*stars\* and \[brackets\] and a\\b",
            "2 * 3 and @nobody and :nope: ok",
        ] {
            let a = parse(src, &Names);
            let m = to_markup(&a);
            let b = parse(&m, &Names);
            assert_eq!(a, b, "src={src:?} markup={m:?}");
        }
    }

    #[test]
    fn crossing_entities_are_split() {
        let rich = Rich { text: "abcdef".into(), entities: vec![e(EntityKind::Bold, 0, 4), e(EntityKind::Italic, 2, 4)] };
        let m = to_markup(&rich);
        let back = parse(&m, &NoNames);
        assert_eq!(back.text, "abcdef");
        // same formatting coverage, possibly split into more pieces
        let covered = |r: &Rich, k: EntityKind, i: u32| r.entities.iter().any(|x| x.kind == k && x.offset <= i && i < x.end());
        for i in 0..6 {
            assert_eq!(covered(&rich, EntityKind::Bold, i), covered(&back, EntityKind::Bold, i), "bold at {i}, markup {m}");
            assert_eq!(covered(&rich, EntityKind::Italic, i), covered(&back, EntityKind::Italic, i), "italic at {i}, markup {m}");
        }
    }
}
