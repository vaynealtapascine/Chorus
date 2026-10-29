//! D-090/D-091 Members-only position key. Explicit UTF-16 units permit conformance
//! with JS/JVM strings, including isolated surrogates. No runtime Unicode tables.

pub fn key(id: &[u16], shown_name: &[u16], stored: Option<&[u16]>) -> String {
    const PREFIX: &[u16] = &[126, 117, 105, 54, 58];
    fn hex(units: impl Iterator<Item = u16>) -> String {
        units.map(|u| format!("{u:04x}")).collect()
    }
    let position = match stored {
        Some(s)
            if s.starts_with(PREFIX)
                && s.len() > PREFIX.len()
                && s[PREFIX.len()..].iter().all(|u| (32..=126).contains(u))
                && s.last() != Some(&32) =>
        {
            String::from_utf16_lossy(&s[PREFIX.len()..])
        }
        Some(s) => hex(s.iter().copied()),
        None => hex(shown_name.iter().map(|&u| if (65..=90).contains(&u) { u + 32 } else { u })),
    };
    format!("{position}!{}", hex(id.iter().copied()))
}
