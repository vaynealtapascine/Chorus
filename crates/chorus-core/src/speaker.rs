//! Who is speaking: sigils, proxy tags, multi-author messages and segments
//! (SPEC.md §5.2, D-007, D-045).
//!
//! `compose` turns what someone typed into the message that gets sent: rich text, the segments
//! (who said which part) and the union of authors.
//!
//! - A leading **annotation** — a run of sigils and/or prefix proxy tags, then whitespace or `=>` —
//!   names the authors. Several = they say it together.
//! - With segments on, a later line starting with an annotation starts a new segment.
//! - Without any annotation, a single PK-style prefix+suffix pair around the whole message
//!   (`[hello]`) picks one author; otherwise the default (speaker chip) is used.
//! - `\` before a line-start sigil keeps it as text.

use serde::{Deserialize, Serialize};

use crate::text::{self, Entity, Resolver, Rich};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProxyTag {
    #[serde(default)]
    pub prefix: String,
    #[serde(default)]
    pub suffix: String,
}

/// What the parser needs to know about a member.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Speaker {
    pub member_id: String,
    pub sigils: Vec<String>,
    pub proxy_tags: Vec<ProxyTag>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Options {
    pub sigils: bool,
    pub segments: bool,
    pub tags_case_sensitive: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options { sigils: true, segments: true, tags_case_sensitive: false }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Segment {
    /// UTF-16 range in the composed text (like entities).
    pub offset: u32,
    pub length: u32,
    pub authors: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Composed {
    pub rich: Rich,
    /// Always ≥ 1 segment. A joint message has exactly one.
    pub segments: Vec<Segment>,
    /// Union of segment authors, in order of first appearance.
    pub authors: Vec<String>,
    /// True if the authors came from an annotation or tag rather than the default.
    pub explicit: bool,
}

struct Matcher<'a> {
    speakers: &'a [Speaker],
    opts: Options,
}

impl Matcher<'_> {
    /// Longest sigil or prefix tag at the start of `s`: (member, bytes consumed, is a tag).
    fn one(&self, s: &str) -> Option<(&str, usize, bool)> {
        let mut best: Option<(&str, usize, bool)> = None;
        for sp in self.speakers {
            if self.opts.sigils {
                for sig in &sp.sigils {
                    if !sig.is_empty() && s.starts_with(sig.as_str()) && best.is_none_or(|b| sig.len() > b.1) {
                        best = Some((&sp.member_id, sig.len(), false));
                    }
                }
            }
            for t in &sp.proxy_tags {
                if t.prefix.is_empty() || !t.suffix.is_empty() {
                    continue; // prefix+suffix pairs only work around a whole message
                }
                if self.starts_with_tag(s, &t.prefix) && best.is_none_or(|b| t.prefix.len() > b.1) {
                    best = Some((&sp.member_id, t.prefix.len(), true));
                }
            }
        }
        best
    }

    fn starts_with_tag(&self, s: &str, tag: &str) -> bool {
        if self.opts.tags_case_sensitive {
            s.starts_with(tag)
        } else {
            s.get(..tag.len()).is_some_and(|h| h.eq_ignore_ascii_case(tag))
        }
    }

    /// A full annotation at the start of a line: authors and the byte index where the body
    /// starts. Requires whitespace, `=>` or end of line after the run.
    fn annotation(&self, line: &str) -> Option<(Vec<String>, usize)> {
        let mut authors: Vec<String> = Vec::new();
        let mut i = 0;
        let mut last_was_tag = false;
        loop {
            let rest = &line[i..];
            let mut hit = self.one(rest).map(|h| (h, 0));
            // after a tag, spaces may separate the next tag or sigil: "k: a: text"
            if hit.is_none() && last_was_tag {
                let trimmed = rest.trim_start_matches([' ', '\t']);
                hit = self.one(trimmed).map(|h| (h, rest.len() - trimmed.len()));
            }
            let Some(((m, n, is_tag), skipped)) = hit else { break };
            if !authors.iter().any(|a| a == m) {
                authors.push(m.to_string());
            }
            i += skipped + n;
            last_was_tag = is_tag;
        }
        if authors.is_empty() {
            return None;
        }
        let rest = &line[i..];
        let body = if let Some(r) = rest.strip_prefix("=>") {
            i + 2 + (r.len() - r.trim_start().len())
        } else if rest.is_empty() {
            i
        } else if rest.starts_with(char::is_whitespace) || line[..i].ends_with(char::is_whitespace) {
            i + (rest.len() - rest.trim_start().len())
        } else if self.ends_with_tag(&line[..i]) {
            // "k:hello" — a PK prefix tag needs no space after it
            i
        } else {
            return None;
        };
        Some((authors, body))
    }

    fn ends_with_tag(&self, head: &str) -> bool {
        self.speakers.iter().flat_map(|s| &s.proxy_tags).any(|t| {
            !t.prefix.is_empty()
                && t.suffix.is_empty()
                && head.len() >= t.prefix.len()
                && head.is_char_boundary(head.len() - t.prefix.len())
                && {
                    let tail = &head[head.len() - t.prefix.len()..];
                    if self.opts.tags_case_sensitive { tail == t.prefix } else { tail.eq_ignore_ascii_case(&t.prefix) }
                }
        })
    }

    fn escaped_sigil(&self, line: &str) -> bool {
        line.strip_prefix('\\').is_some_and(|r| self.one(r).is_some())
    }

    /// PK-style prefix+suffix around the whole message.
    fn pair(&self, s: &str) -> Option<(String, String)> {
        let mut best: Option<(&str, &ProxyTag)> = None;
        for sp in self.speakers {
            for t in &sp.proxy_tags {
                if t.suffix.is_empty() {
                    continue;
                }
                let ok = s.len() >= t.prefix.len() + t.suffix.len()
                    && self.starts_with_tag(s, &t.prefix)
                    && s.is_char_boundary(s.len() - t.suffix.len())
                    && {
                        let tail = &s[s.len() - t.suffix.len()..];
                        if self.opts.tags_case_sensitive {
                            tail == t.suffix
                        } else {
                            tail.eq_ignore_ascii_case(&t.suffix)
                        }
                    };
                let len = t.prefix.len() + t.suffix.len();
                if ok && best.is_none_or(|(_, b)| len > b.prefix.len() + b.suffix.len()) {
                    best = Some((&sp.member_id, t));
                }
            }
        }
        best.map(|(m, t)| (m.to_string(), s[t.prefix.len()..s.len() - t.suffix.len()].trim().to_string()))
    }
}

/// Compose a message from what was typed.
pub fn compose(
    src: &str,
    speakers: &[Speaker],
    opts: Options,
    default_authors: &[String],
    r: &dyn Resolver,
) -> Composed {
    let m = Matcher { speakers, opts };
    // Split into (authors, body markup) segments.
    let mut segs: Vec<(Vec<String>, String)> = Vec::new();
    let mut explicit = false;
    let mut in_fence = false;
    for (n, raw_line) in src.split('\n').enumerate() {
        let mut line = raw_line.to_string();
        let can_annotate = !in_fence && (n == 0 || opts.segments);
        let mut started = false;
        if can_annotate {
            if let Some((authors, body)) = m.annotation(&line) {
                line = line[body..].to_string();
                segs.push((authors, String::new()));
                explicit = true;
                started = true;
            } else if m.escaped_sigil(&line) {
                line.remove(0);
            }
        }
        if !started && segs.is_empty() {
            segs.push((default_authors.to_vec(), String::new()));
        }
        let last = segs.last_mut().unwrap_or_else(|| unreachable!());
        if !started && !(last.1.is_empty() && n == 0) {
            last.1.push('\n');
        }
        last.1.push_str(&line);
        if text::count_fences_str(raw_line) % 2 == 1 {
            in_fence = !in_fence;
        }
    }
    // Suffix pair around the whole message, when nothing else named an author.
    if !explicit
        && segs.len() == 1
        && let Some((member, inner)) = m.pair(src.trim())
    {
        segs = vec![(vec![member], inner)];
        explicit = true;
    }
    // Parse each segment's markup and concatenate.
    let mut rich = Rich::default();
    let mut segments = Vec::new();
    let mut authors: Vec<String> = Vec::new();
    for (i, (who, body)) in segs.iter().enumerate() {
        if i > 0 {
            rich.text.push('\n');
        }
        let offset = text::utf16_len(&rich.text);
        let part = text::parse(body, r);
        rich.entities.extend(part.entities.into_iter().map(|e| Entity { offset: e.offset + offset, ..e }));
        rich.text.push_str(&part.text);
        segments.push(Segment { offset, length: text::utf16_len(&part.text), authors: who.clone() });
        for a in who {
            if !authors.contains(a) {
                authors.push(a.clone());
            }
        }
    }
    text::sort_entities(&mut rich.entities);
    Composed { rich, segments, authors, explicit }
}

// ─── default speaker (SPEC §5.2, D-074) ───────────────────────────────────────

/// A channel's autoproxy mode, set per account (`pref` key `autoproxy:<channel id>`, D-074).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Autoproxy {
    /// Nobody by default: pick in the chip, or use sigils or proxy tags.
    Off,
    /// The primary fronter (else the first member fronting): the default.
    #[default]
    Front,
    /// Whoever the account last spoke as in this channel.
    Latch,
    /// Always one member (the channel's sticky speaker).
    Member,
}

/// Who's fronting, as the composer sees it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Fronter {
    pub member_id: String,
    #[serde(default)]
    pub is_primary: bool,
    /// `front`, `cocon` or `present`.
    #[serde(default)]
    pub level: String,
}

/// What [`default_speaker`] looks at.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpeakerContext {
    #[serde(default)]
    pub mode: Autoproxy,
    /// The `member` mode's member.
    #[serde(default)]
    pub member: Option<String>,
    /// The account's current front, in fronting order.
    #[serde(default)]
    pub fronting: Vec<Fronter>,
    /// A person account's own member (D-003): always the speaker.
    #[serde(default)]
    pub self_member: Option<String>,
    /// The authors of the account's latest message in the channel (for `latch`).
    #[serde(default)]
    pub last_authors: Vec<String>,
    /// Members that can speak (not deleted); anyone else is skipped.
    pub members: Vec<String>,
}

/// Who the speaker chip shows before anyone picks (SPEC §5.2), as authors in order; empty =
/// nobody (the composer asks). A person speaks as itself; else `member` → that member,
/// `latch` → who spoke last here, `off` → nobody; and everything that finds nobody falls back to
/// the primary fronter, else the first member fronting (`off` excepted).
pub fn default_speaker(c: &SpeakerContext) -> Vec<String> {
    let alive = |m: &String| c.members.contains(m);
    if let Some(me) = c.self_member.as_ref().filter(|m| alive(m)) {
        return vec![me.clone()];
    }
    match c.mode {
        Autoproxy::Off => return Vec::new(),
        Autoproxy::Member => {
            if let Some(m) = c.member.as_ref().filter(|m| alive(m)) {
                return vec![m.clone()];
            }
        }
        Autoproxy::Latch => {
            let last: Vec<String> = c.last_authors.iter().filter(|m| alive(m)).cloned().collect();
            if !last.is_empty() {
                return last;
            }
        }
        Autoproxy::Front => {}
    }
    let fronting = c.fronting.iter().filter(|f| alive(&f.member_id));
    let primary = fronting.clone().find(|f| f.is_primary);
    let first = fronting.clone().find(|f| f.level == "front").or_else(|| fronting.clone().next());
    primary.or(first).map(|f| vec![f.member_id.clone()]).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::text::{EntityKind, NoNames};

    fn sp(id: &str, sigils: &[&str], tags: &[(&str, &str)]) -> Speaker {
        Speaker {
            member_id: id.into(),
            sigils: sigils.iter().map(|s| s.to_string()).collect(),
            proxy_tags: tags.iter().map(|(p, s)| ProxyTag { prefix: p.to_string(), suffix: s.to_string() }).collect(),
        }
    }

    fn dir() -> Vec<Speaker> {
        vec![
            sp("sky", &["🌌"], &[("s:", "")]),
            sp("mark", &["🔖"], &[("m:", "")]),
            sp("fire", &["❤️‍🔥"], &[("[", "]")]),
            sp("kai", &["k"], &[("k:", ""), ("", "-k")]),
        ]
    }

    fn c(src: &str) -> Composed {
        compose(src, &dir(), Options::default(), &["def".to_string()], &NoNames)
    }

    fn ids(v: &[String]) -> Vec<&str> {
        v.iter().map(String::as_str).collect()
    }

    #[test]
    fn default_speaker() {
        let r = c("hello there");
        assert_eq!(ids(&r.authors), ["def"]);
        assert!(!r.explicit);
        assert_eq!(r.rich.text, "hello there");
    }

    #[test]
    fn joint_sigils() {
        let r = c("🌌🔖❤️‍🔥 we all agree");
        assert_eq!(ids(&r.authors), ["sky", "mark", "fire"]);
        assert_eq!(r.segments.len(), 1);
        assert_eq!(r.rich.text, "we all agree");
    }

    #[test]
    fn sigil_needs_a_separator() {
        let r = c("🌌wow");
        assert_eq!(ids(&r.authors), ["def"]);
        assert_eq!(r.rich.text, "🌌wow");
    }

    #[test]
    fn consecutive_prefix_tags_and_no_space_tag() {
        let r = c("s: m: hi");
        assert_eq!(ids(&r.authors), ["sky", "mark"]);
        assert_eq!(r.rich.text, "hi");
        let r = c("k:hello");
        assert_eq!(ids(&r.authors), ["kai"]);
        assert_eq!(r.rich.text, "hello");
        let r = c("K: case insensitive");
        assert_eq!(ids(&r.authors), ["kai"]);
    }

    #[test]
    fn suffix_pairs() {
        let r = c("[hello]");
        assert_eq!((ids(&r.authors), r.rich.text.as_str()), (vec!["fire"], "hello"));
        let r = c("hello -k");
        assert_eq!((ids(&r.authors), r.rich.text.as_str()), (vec!["kai"], "hello"));
    }

    #[test]
    fn segments_with_arrow_and_continuation() {
        let r = c("🌌 I think we should go\n🔖❤️‍🔥=> we don't\nand this line is still ours");
        assert_eq!(r.segments.len(), 2);
        assert_eq!(ids(&r.segments[0].authors), ["sky"]);
        assert_eq!(ids(&r.segments[1].authors), ["mark", "fire"]);
        assert_eq!(r.rich.text, "I think we should go\nwe don't\nand this line is still ours");
        let s1 = &r.segments[1];
        assert_eq!(text::utf16_slice(&r.rich.text, s1.offset, s1.length), "we don't\nand this line is still ours");
        assert_eq!(ids(&r.authors), ["sky", "mark", "fire"]);
    }

    #[test]
    fn first_line_unannotated_uses_default_then_segment() {
        let r = c("hi\n🌌 hello");
        assert_eq!(r.segments.len(), 2);
        assert_eq!(ids(&r.segments[0].authors), ["def"]);
        assert_eq!(ids(&r.segments[1].authors), ["sky"]);
    }

    #[test]
    fn segments_off_keeps_lines() {
        let o = Options { segments: false, ..Options::default() };
        let r = compose("🌌 a\n🔖 b", &dir(), o, &[], &NoNames);
        assert_eq!(r.segments.len(), 1);
        assert_eq!(r.rich.text, "a\n🔖 b");
    }

    #[test]
    fn escaped_sigil_and_fenced_lines() {
        let r = c("\\🌌 not an annotation");
        assert_eq!(ids(&r.authors), ["def"]);
        assert_eq!(r.rich.text, "🌌 not an annotation");
        let r = c("🌌 code:\n```\n🔖 inside\n```");
        assert_eq!(r.segments.len(), 1);
        assert!(r.rich.text.contains("🔖 inside"));
    }

    #[test]
    fn entities_are_shifted_per_segment() {
        let r = c("🌌 **a**\n🔖 *b*");
        assert_eq!(r.rich.text, "a\nb");
        assert_eq!(r.rich.entities.len(), 2);
        assert_eq!((r.rich.entities[1].kind.clone(), r.rich.entities[1].offset), (EntityKind::Italic, 2));
    }

    #[test]
    fn longest_sigil_wins() {
        let d = vec![sp("a", &["❤️"], &[]), sp("b", &["❤️‍🔥"], &[])];
        let r = compose("❤️‍🔥 hot", &d, Options::default(), &[], &NoNames);
        assert_eq!(ids(&r.authors), ["b"]);
    }

    #[test]
    fn default_speaker_follows_the_channels_autoproxy() {
        let f = |m: &str, primary: bool, level: &str| Fronter {
            member_id: m.into(),
            is_primary: primary,
            level: level.into(),
        };
        let members: Vec<String> = ["kai", "rin", "june", "self"].iter().map(|s| s.to_string()).collect();
        let base = SpeakerContext {
            fronting: vec![f("rin", false, "cocon"), f("kai", false, "front"), f("june", true, "front")],
            members: members.clone(),
            ..Default::default()
        };
        let who = |c: &SpeakerContext| super::default_speaker(c);
        assert_eq!(who(&base), vec!["june"], "front (the default): the primary fronter");
        let no_primary =
            SpeakerContext { fronting: vec![f("rin", false, "cocon"), f("kai", false, "front")], ..base.clone() };
        assert_eq!(who(&no_primary), vec!["kai"], "else the first one fronting, not a co-con");
        assert_eq!(who(&SpeakerContext { mode: Autoproxy::Off, ..base.clone() }), Vec::<String>::new());
        let member = SpeakerContext { mode: Autoproxy::Member, member: Some("rin".into()), ..base.clone() };
        assert_eq!(who(&member), vec!["rin"]);
        let gone = SpeakerContext { member: Some("deleted".into()), ..member };
        assert_eq!(who(&gone), vec!["june"], "a sticky member that's gone falls back to the front");
        let latch =
            SpeakerContext { mode: Autoproxy::Latch, last_authors: vec!["kai".into(), "rin".into()], ..base.clone() };
        assert_eq!(who(&latch), vec!["kai", "rin"], "the last message's authors, together");
        assert_eq!(who(&SpeakerContext { last_authors: vec![], ..latch }), vec!["june"]);
        let person = SpeakerContext { self_member: Some("self".into()), mode: Autoproxy::Off, ..base };
        assert_eq!(who(&person), vec!["self"], "a person always speaks as itself");
    }
}
