//! Member colours that stay readable (DESIGN.md §1.3).
//!
//! Members pick any colour. `adapt` returns variants that meet WCAG contrast against the theme:
//! it keeps the hue, moves lightness in OKLCH, and gives up chroma only when the colour would
//! leave the sRGB gamut.

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rgb {
    pub r: f64,
    pub g: f64,
    pub b: f64,
}

impl Rgb {
    pub fn parse(hex: &str) -> Option<Rgb> {
        let h = hex.strip_prefix('#').unwrap_or(hex);
        let h: String = match h.len() {
            3 => h.chars().flat_map(|c| [c, c]).collect(),
            6 => h.to_string(),
            _ => return None,
        };
        let v = u32::from_str_radix(&h, 16).ok()?;
        Some(Rgb {
            r: f64::from((v >> 16) & 0xff) / 255.0,
            g: f64::from((v >> 8) & 0xff) / 255.0,
            b: f64::from(v & 0xff) / 255.0,
        })
    }

    pub fn hex(self) -> String {
        let q = |x: f64| (x.clamp(0.0, 1.0) * 255.0).round() as u8;
        format!("#{:02x}{:02x}{:02x}", q(self.r), q(self.g), q(self.b))
    }

    fn in_gamut(self) -> bool {
        let ok = |x: f64| (-1e-4..=1.0 + 1e-4).contains(&x);
        ok(self.r) && ok(self.g) && ok(self.b)
    }

    pub fn mix(self, other: Rgb, t: f64) -> Rgb {
        Rgb {
            r: self.r + (other.r - self.r) * t,
            g: self.g + (other.g - self.g) * t,
            b: self.b + (other.b - self.b) * t,
        }
    }
}

fn to_linear(c: f64) -> f64 {
    if c <= 0.04045 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
}

fn from_linear(c: f64) -> f64 {
    if c <= 0.003_130_8 { c * 12.92 } else { 1.055 * c.powf(1.0 / 2.4) - 0.055 }
}

/// WCAG relative luminance.
pub fn luminance(c: Rgb) -> f64 {
    0.2126 * to_linear(c.r) + 0.7152 * to_linear(c.g) + 0.0722 * to_linear(c.b)
}

/// WCAG contrast ratio (1–21).
pub fn contrast(a: Rgb, b: Rgb) -> f64 {
    let (la, lb) = (luminance(a), luminance(b));
    let (hi, lo) = if la > lb { (la, lb) } else { (lb, la) };
    (hi + 0.05) / (lo + 0.05)
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Oklch {
    pub l: f64,
    pub c: f64,
    pub h: f64,
}

pub fn to_oklch(c: Rgb) -> Oklch {
    let (r, g, b) = (to_linear(c.r), to_linear(c.g), to_linear(c.b));
    let l = (0.412_221_470_8 * r + 0.536_332_536_3 * g + 0.051_445_992_9 * b).cbrt();
    let m = (0.211_903_498_2 * r + 0.680_699_545_1 * g + 0.107_396_956_6 * b).cbrt();
    let s = (0.088_302_461_9 * r + 0.281_718_837_6 * g + 0.629_978_700_5 * b).cbrt();
    let ll = 0.210_454_255_3 * l + 0.793_617_785 * m - 0.004_072_046_8 * s;
    let aa = 1.977_998_495_1 * l - 2.428_592_205 * m + 0.450_593_709_9 * s;
    let bb = 0.025_904_037_1 * l + 0.782_771_766_2 * m - 0.808_675_766 * s;
    Oklch { l: ll, c: (aa * aa + bb * bb).sqrt(), h: bb.atan2(aa) }
}

pub fn from_oklch(o: Oklch) -> Rgb {
    let (a, b) = (o.c * o.h.cos(), o.c * o.h.sin());
    let l = (o.l + 0.396_337_777_4 * a + 0.215_803_757_3 * b).powi(3);
    let m = (o.l - 0.105_561_345_8 * a - 0.063_854_172_8 * b).powi(3);
    let s = (o.l - 0.089_484_177_5 * a - 1.291_485_548 * b).powi(3);
    Rgb {
        r: from_linear(4.076_741_662_1 * l - 3.307_711_591_3 * m + 0.230_969_929_2 * s),
        g: from_linear(-1.268_438_004_6 * l + 2.609_757_401_1 * m - 0.341_319_396_5 * s),
        b: from_linear(-0.004_196_086_3 * l - 0.703_418_614_7 * m + 1.707_614_701 * s),
    }
}

/// Same hue and lightness, chroma reduced until in gamut.
fn gamut_map(mut o: Oklch) -> Rgb {
    let mut rgb = from_oklch(o);
    for _ in 0..40 {
        if rgb.in_gamut() {
            break;
        }
        o.c *= 0.9;
        rgb = from_oklch(o);
    }
    Rgb { r: rgb.r.clamp(0.0, 1.0), g: rgb.g.clamp(0.0, 1.0), b: rgb.b.clamp(0.0, 1.0) }
}

/// The lightness closest to the original that reaches `target` contrast against every
/// background, searching toward darker (light theme) or lighter (dark theme).
fn with_contrast(color: Rgb, backgrounds: &[Rgb], target: f64, dark_theme: bool) -> Rgb {
    let ok = |c: Rgb| backgrounds.iter().all(|b| contrast(c, *b) >= target);
    if ok(color) {
        return color; // untouched, not even round-tripped
    }
    // aim a little above the target so rounding to #rrggbb can't drop below it
    let ok = |c: Rgb| backgrounds.iter().all(|b| contrast(c, *b) >= target + 0.08);
    let o = to_oklch(color);
    let (mut lo, mut hi) = if dark_theme { (o.l, 1.0) } else { (0.0, o.l) };
    let mut best = if dark_theme { gamut_map(Oklch { l: 1.0, ..o }) } else { gamut_map(Oklch { l: 0.0, ..o }) };
    for _ in 0..30 {
        let mid = (lo + hi) / 2.0;
        let c = gamut_map(Oklch { l: mid, ..o });
        if ok(c) {
            best = c;
            // passes: move back toward the original
            if dark_theme { hi = mid } else { lo = mid }
        } else if dark_theme {
            lo = mid
        } else {
            hi = mid
        }
    }
    best
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Intensity {
    Off,
    Subtle,
    Vivid,
}

#[derive(Clone, Copy, Debug)]
pub struct Theme {
    pub dark: bool,
    pub bg: Rgb,
    pub surface: Rgb,
    pub ink: Rgb,
}

impl Theme {
    /// DESIGN.md §1.1 "Paper".
    pub fn paper() -> Theme {
        Theme { dark: false, bg: hex("#FBF7F2"), surface: hex("#FFFFFF"), ink: hex("#2B2521") }
    }
    /// DESIGN.md §1.2 "Ink".
    pub fn ink() -> Theme {
        Theme { dark: true, bg: hex("#1B1714"), surface: hex("#231E1A"), ink: hex("#F1E9E0") }
    }
}

fn hex(s: &str) -> Rgb {
    Rgb::parse(s).unwrap_or(Rgb { r: 0.5, g: 0.5, b: 0.5 })
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MemberColors {
    /// Names in chat/posts: ≥ 4.5:1 on bg and surface.
    pub name: String,
    /// Avatar ring, front bars: ≥ 3:1 on bg.
    pub ring: String,
    /// Soft wash for selected rows and own bubbles.
    pub tint: String,
}

pub const NAME_CONTRAST: f64 = 4.5;
pub const RING_CONTRAST: f64 = 3.0;

/// Variants of a member colour for a theme. Unparseable colours fall back to the ink colour.
pub fn adapt(color: &str, theme: Theme, intensity: Intensity) -> MemberColors {
    let Some(c) = Rgb::parse(color) else {
        let ink = theme.ink.hex();
        return MemberColors { name: ink.clone(), ring: ink, tint: theme.surface.hex() };
    };
    if intensity == Intensity::Off {
        return MemberColors {
            name: theme.ink.hex(),
            ring: with_contrast(c, &[theme.bg], RING_CONTRAST, theme.dark).hex(),
            tint: theme.surface.hex(),
        };
    }
    let name = with_contrast(c, &[theme.bg, theme.surface], NAME_CONTRAST, theme.dark);
    let ring = with_contrast(c, &[theme.bg], RING_CONTRAST, theme.dark);
    let t = match (intensity, theme.dark) {
        (Intensity::Vivid, false) => 0.18,
        (Intensity::Vivid, true) => 0.24,
        (_, false) => 0.10,
        (_, true) => 0.14,
    };
    let tint = theme.surface.mix(c, t);
    MemberColors { name: name.hex(), ring: ring.hex(), tint: tint.hex() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    #[test]
    fn parse_and_hex() {
        assert_eq!(Rgb::parse("#abc").unwrap().hex(), "#aabbcc");
        assert_eq!(Rgb::parse("C0694E").unwrap().hex(), "#c0694e");
        assert!(Rgb::parse("#12345").is_none());
    }

    #[test]
    fn contrast_known_values() {
        let w = Rgb::parse("#fff").unwrap();
        let k = Rgb::parse("#000").unwrap();
        assert!((contrast(w, k) - 21.0).abs() < 1e-9);
        assert!((contrast(w, w) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn oklch_roundtrip() {
        for h in ["#c0694e", "#123456", "#ffffff", "#000000", "#5e8c61", "#ff00ff"] {
            let c = Rgb::parse(h).unwrap();
            assert_eq!(from_oklch(to_oklch(c)).hex(), h);
        }
    }

    #[test]
    fn pale_yellow_gets_darkened_on_paper_and_kept_on_ink() {
        let light = adapt("#fff27a", Theme::paper(), Intensity::Subtle);
        let n = Rgb::parse(&light.name).unwrap();
        assert!(contrast(n, Theme::paper().bg) >= NAME_CONTRAST);
        let dark = adapt("#fff27a", Theme::ink(), Intensity::Subtle);
        assert_eq!(dark.name, "#fff27a");
    }

    #[test]
    fn keeps_hue_roughly() {
        let a = adapt("#ffd1dc", Theme::paper(), Intensity::Subtle);
        let before = to_oklch(Rgb::parse("#ffd1dc").unwrap());
        let after = to_oklch(Rgb::parse(&a.name).unwrap());
        let dh = (before.h - after.h).abs();
        assert!(dh < 0.2 || (std::f64::consts::TAU - dh) < 0.2, "{before:?} → {after:?}");
    }

    proptest! {
        #[test]
        fn names_and_rings_always_meet_contrast(r in 0u8.., g in 0u8.., b in 0u8.., dark in any::<bool>()) {
            let hex = format!("#{r:02x}{g:02x}{b:02x}");
            let theme = if dark { Theme::ink() } else { Theme::paper() };
            let a = adapt(&hex, theme, Intensity::Subtle);
            let name = Rgb::parse(&a.name).unwrap();
            let ring = Rgb::parse(&a.ring).unwrap();
            // hex rounding can cost a hair of contrast
            prop_assert!(contrast(name, theme.bg)   >= NAME_CONTRAST, "{} → {}", hex, a.name);
            prop_assert!(contrast(name, theme.surface)   >= NAME_CONTRAST, "{} → {}", hex, a.name);
            prop_assert!(contrast(ring, theme.bg)   >= RING_CONTRAST, "{} → {}", hex, a.ring);
        }
    }
}
