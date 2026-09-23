//! A small QR code encoder for invite links (API.md §4 `{url, qr_svg}`; CLIENTS.md device
//! linking): byte mode, error correction level M, versions 1–10 (up to 213 bytes, plenty for a
//! URL). No dependency: the algorithm follows ISO/IEC 18004 as laid out in Project Nayuki's
//! reference implementation (Reed–Solomon over GF(256), zigzag placement, all eight masks scored
//! with the four penalty rules).

/// ECC codewords per block and number of blocks at level M, versions 1–10 (index = version).
const ECC_PER_BLOCK: [usize; 11] = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
const BLOCKS: [usize; 11] = [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
const MAX_VERSION: usize = 10;

/// A square of modules, `true` = dark. `modules[y][x]`.
pub struct Qr {
    pub size: usize,
    pub modules: Vec<Vec<bool>>,
}

fn raw_data_modules(ver: usize) -> usize {
    let mut r = (16 * ver + 128) * ver + 64;
    if ver >= 2 {
        let align = ver / 7 + 2;
        r -= (25 * align - 10) * align - 55;
        if ver >= 7 {
            r -= 36;
        }
    }
    r
}

fn data_codewords(ver: usize) -> usize {
    raw_data_modules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]
}

fn gf_mul(x: u8, y: u8) -> u8 {
    let mut z: u16 = 0;
    for i in (0..8).rev() {
        z = (z << 1) ^ ((z >> 7) * 0x11d);
        z ^= ((y as u16 >> i) & 1) * x as u16;
    }
    z as u8
}

fn rs_divisor(degree: usize) -> Vec<u8> {
    let mut r = vec![0u8; degree];
    r[degree - 1] = 1;
    let mut root = 1u8;
    for _ in 0..degree {
        for j in 0..degree {
            r[j] = gf_mul(r[j], root);
            if j + 1 < degree {
                r[j] ^= r[j + 1];
            }
        }
        root = gf_mul(root, 0x02);
    }
    r
}

fn rs_remainder(data: &[u8], divisor: &[u8]) -> Vec<u8> {
    let mut r = vec![0u8; divisor.len()];
    for &b in data {
        let factor = b ^ r[0];
        r.remove(0);
        r.push(0);
        for (x, &d) in r.iter_mut().zip(divisor) {
            *x ^= gf_mul(d, factor);
        }
    }
    r
}

/// Data bits → codewords with ECC, interleaved across blocks.
fn codewords(text: &[u8], ver: usize) -> Vec<u8> {
    let cap = data_codewords(ver);
    let mut bits: Vec<bool> = Vec::new();
    let push = |v: usize, n: usize, bits: &mut Vec<bool>| (0..n).rev().for_each(|i| bits.push((v >> i) & 1 == 1));
    push(0b0100, 4, &mut bits);
    push(text.len(), if ver <= 9 { 8 } else { 16 }, &mut bits);
    for &b in text {
        push(b as usize, 8, &mut bits);
    }
    let room = cap * 8;
    let term = (room - bits.len()).min(4);
    push(0, term, &mut bits);
    let pad = (8 - bits.len() % 8) % 8;
    push(0, pad, &mut bits);
    let mut data: Vec<u8> = bits.chunks(8).map(|c| c.iter().fold(0u8, |a, &b| (a << 1) | b as u8)).collect();
    let mut fill = [0xec, 0x11].into_iter().cycle();
    while data.len() < cap {
        data.push(fill.next().unwrap_or(0xec));
    }

    let (nblocks, ecc_len) = (BLOCKS[ver], ECC_PER_BLOCK[ver]);
    let raw = raw_data_modules(ver) / 8;
    let short = nblocks - raw % nblocks;
    let short_len = raw / nblocks;
    let divisor = rs_divisor(ecc_len);
    let mut blocks: Vec<Vec<u8>> = Vec::with_capacity(nblocks);
    let mut k = 0;
    for i in 0..nblocks {
        let len = short_len - ecc_len + usize::from(i >= short);
        let mut dat = data[k..k + len].to_vec();
        k += len;
        let ecc = rs_remainder(&dat, &divisor);
        if i < short {
            dat.push(0); // placeholder so all blocks line up; skipped when interleaving
        }
        dat.extend(ecc);
        blocks.push(dat);
    }
    let mut out = Vec::with_capacity(raw);
    for i in 0..blocks[0].len() {
        for (j, b) in blocks.iter().enumerate() {
            if i != short_len - ecc_len || j >= short {
                out.push(b[i]);
            }
        }
    }
    out
}

struct Grid {
    size: usize,
    m: Vec<Vec<bool>>,
    function: Vec<Vec<bool>>,
}

impl Grid {
    fn set(&mut self, x: usize, y: usize, dark: bool) {
        self.m[y][x] = dark;
        self.function[y][x] = true;
    }

    fn finder(&mut self, cx: i32, cy: i32) {
        for dy in -4i32..=4 {
            for dx in -4i32..=4 {
                let (x, y) = (cx + dx, cy + dy);
                if (0..self.size as i32).contains(&x) && (0..self.size as i32).contains(&y) {
                    let d = dx.abs().max(dy.abs());
                    self.set(x as usize, y as usize, d != 2 && d != 4);
                }
            }
        }
    }

    fn alignment(&mut self, cx: usize, cy: usize) {
        for dy in -2i32..=2 {
            for dx in -2i32..=2 {
                let d = dx.abs().max(dy.abs());
                self.set((cx as i32 + dx) as usize, (cy as i32 + dy) as usize, d != 1);
            }
        }
    }

    fn format(&mut self, mask: usize) {
        let data = mask; // level M's format bits are 00
        let mut rem = data;
        for _ in 0..10 {
            rem = (rem << 1) ^ ((rem >> 9) * 0x537);
        }
        let bits = ((data << 10) | rem) ^ 0x5412;
        let bit = |i: usize| (bits >> i) & 1 == 1;
        for i in 0..=5 {
            self.set(8, i, bit(i));
        }
        self.set(8, 7, bit(6));
        self.set(8, 8, bit(7));
        self.set(7, 8, bit(8));
        for i in 9..15 {
            self.set(14 - i, 8, bit(i));
        }
        let s = self.size;
        for i in 0..8 {
            self.set(s - 1 - i, 8, bit(i));
        }
        for i in 8..15 {
            self.set(8, s - 15 + i, bit(i));
        }
        self.set(8, s - 8, true);
    }

    fn version(&mut self, ver: usize) {
        if ver < 7 {
            return;
        }
        let mut rem = ver;
        for _ in 0..12 {
            rem = (rem << 1) ^ ((rem >> 11) * 0x1f25);
        }
        let bits = (ver << 12) | rem;
        for i in 0..18 {
            let dark = (bits >> i) & 1 == 1;
            let (a, b) = (self.size - 11 + i % 3, i / 3);
            self.set(a, b, dark);
            self.set(b, a, dark);
        }
    }
}

fn alignment_positions(ver: usize) -> Vec<usize> {
    if ver == 1 {
        return Vec::new();
    }
    let size = ver * 4 + 17;
    let n = ver / 7 + 2;
    let step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
    let mut out = vec![6];
    let mut pos = size - 7;
    let mut rest = Vec::new();
    while rest.len() + 1 < n {
        rest.push(pos);
        pos -= step;
    }
    rest.reverse();
    out.extend(rest);
    out
}

fn masked(mask: usize, x: usize, y: usize) -> bool {
    match mask {
        0 => (x + y).is_multiple_of(2),
        1 => y.is_multiple_of(2),
        2 => x.is_multiple_of(3),
        3 => (x + y).is_multiple_of(3),
        4 => (x / 3 + y / 2).is_multiple_of(2),
        5 => x * y % 2 + x * y % 3 == 0,
        6 => (x * y % 2 + x * y % 3).is_multiple_of(2),
        _ => ((x + y) % 2 + x * y % 3).is_multiple_of(2),
    }
}

fn penalty(m: &[Vec<bool>]) -> usize {
    let n = m.len();
    let mut score = 0;
    let line = |get: &dyn Fn(usize) -> bool| -> usize {
        let mut s = 0;
        let mut run = 1;
        for i in 1..n {
            if get(i) == get(i - 1) {
                run += 1;
            } else {
                if run >= 5 {
                    s += 3 + run - 5;
                }
                run = 1;
            }
        }
        if run >= 5 {
            s += 3 + run - 5;
        }
        // finder-like 1011101 with four light modules on one side (light outside the symbol counts)
        let at = |i: isize| i >= 0 && (i as usize) < n && get(i as usize);
        for i in -4isize..n as isize {
            let core = [true, false, true, true, true, false, true];
            if (0..7).all(|k| at(i + k as isize) == core[k]) {
                let before = (1..=4).all(|k| !at(i - k));
                let after = (7..11).all(|k| !at(i + k));
                if before || after {
                    s += 40;
                }
            }
        }
        s
    };
    score += m.iter().map(|row| line(&|x| row[x])).sum::<usize>();
    score += (0..n).map(|x| line(&|y| m[y][x])).sum::<usize>();
    for y in 0..n - 1 {
        for x in 0..n - 1 {
            let c = m[y][x];
            if c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1] {
                score += 3;
            }
        }
    }
    let dark = m.iter().flatten().filter(|&&d| d).count();
    let total = n * n;
    let k = (dark * 20).abs_diff(total * 10).div_ceil(total).saturating_sub(1);
    score + k * 10
}

/// Encode `text` (as UTF-8 bytes). `None` if it doesn't fit in version 10 at level M.
pub fn encode(text: &str) -> Option<Qr> {
    let bytes = text.as_bytes();
    let ver = (1..=MAX_VERSION).find(|&v| {
        let count_bits = if v <= 9 { 8 } else { 16 };
        4 + count_bits + bytes.len() * 8 <= data_codewords(v) * 8
    })?;
    let size = ver * 4 + 17;
    let mut g = Grid { size, m: vec![vec![false; size]; size], function: vec![vec![false; size]; size] };
    for i in 0..size {
        g.set(6, i, i % 2 == 0);
        g.set(i, 6, i % 2 == 0);
    }
    g.finder(3, 3);
    g.finder(size as i32 - 4, 3);
    g.finder(3, size as i32 - 4);
    let pos = alignment_positions(ver);
    for (i, &a) in pos.iter().enumerate() {
        for (j, &b) in pos.iter().enumerate() {
            let last = pos.len() - 1;
            if !((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) {
                g.alignment(a, b);
            }
        }
    }
    g.format(0); // reserve the format areas (redrawn per mask)
    g.version(ver);

    let data = codewords(bytes, ver);
    let mut i = 0;
    let mut right = size as isize - 1;
    while right >= 1 {
        if right == 6 {
            right = 5;
        }
        for vert in 0..size {
            for j in 0..2 {
                let x = (right - j) as usize;
                let upward = (right + 1) & 2 == 0;
                let y = if upward { size - 1 - vert } else { vert };
                if !g.function[y][x] && i < data.len() * 8 {
                    g.m[y][x] = (data[i >> 3] >> (7 - (i & 7))) & 1 == 1;
                    i += 1;
                }
            }
        }
        right -= 2;
    }

    let mut best: Option<(usize, Vec<Vec<bool>>)> = None;
    for mask in 0..8 {
        let mut t = Grid { size, m: g.m.clone(), function: g.function.clone() };
        for y in 0..size {
            for x in 0..size {
                if !t.function[y][x] && masked(mask, x, y) {
                    t.m[y][x] = !t.m[y][x];
                }
            }
        }
        t.format(mask);
        let p = penalty(&t.m);
        if best.as_ref().is_none_or(|(b, _)| p < *b) {
            best = Some((p, t.m));
        }
    }
    best.map(|(_, modules)| Qr { size, modules })
}

impl Qr {
    /// A standalone SVG with a four-module quiet zone; `currentColor` so it follows the theme's ink
    /// on a light tile.
    pub fn svg(&self) -> String {
        let q = 4;
        let n = self.size + 2 * q;
        let mut path = String::new();
        for (y, row) in self.modules.iter().enumerate() {
            // one rectangle per horizontal run of dark modules
            let mut x = 0;
            while x < row.len() {
                if !row[x] {
                    x += 1;
                    continue;
                }
                let start = x;
                while x < row.len() && row[x] {
                    x += 1;
                }
                let w = x - start;
                path.push_str(&format!("M{},{}h{w}v1h-{w}z", start + q, y + q));
            }
        }
        format!(
            r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" shape-rendering="crispEdges"><rect width="{n}" height="{n}" fill="#fff"/><path d="{path}" fill="#000"/></svg>"##
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capacities_match_the_standard() {
        // byte-mode capacity at level M for versions 1–10 (ISO/IEC 18004 table 7)
        let cap: Vec<usize> = (1..=10).map(|v| (data_codewords(v) * 8 - 4 - if v <= 9 { 8 } else { 16 }) / 8).collect();
        assert_eq!(cap, [14, 26, 42, 62, 84, 106, 122, 152, 180, 213]);
    }

    #[test]
    fn rs_matches_a_known_codeword() {
        // "HELLO WORLD" 1-M example: data codewords and their 10 ECC codewords (thonky.com tutorial)
        let data = [32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17];
        assert_eq!(rs_remainder(&data, &rs_divisor(10)), [196, 35, 39, 119, 235, 215, 231, 226, 93, 23]);
    }

    #[test]
    fn picks_the_smallest_version_and_draws_finders() {
        let q = encode("https://chorus.example.ts.net/i/ABCD-EFGH-JKLM").unwrap();
        assert_eq!(q.size, 33); // 47 bytes → version 4
        // finder pattern corners are dark, their separators light
        assert!(q.modules[0][0] && q.modules[0][q.size - 1] && q.modules[q.size - 1][0]);
        assert!(!q.modules[7][7] && !q.modules[q.size - 8][7]);
        assert!(q.modules[q.size - 8][8], "the always-dark module");
        assert!(encode(&"x".repeat(214)).is_none());
        // the SVG draws exactly the dark modules
        let svg = q.svg();
        let drawn: usize = svg
            .split('M')
            .skip(1)
            .map(|p| p.split('h').nth(1).and_then(|w| w.split('v').next()).unwrap().parse::<usize>().unwrap())
            .sum();
        assert_eq!(drawn, q.modules.iter().flatten().filter(|&&d| d).count());
    }

    /// Writes PGM images for a decoder check (`python scripts/qr-check.py <dir>`, OpenCV).
    #[test]
    fn writes_samples_when_asked() {
        let Ok(dir) = std::env::var("CHORUS_QR_SAMPLES") else { return };
        for (i, text) in
            ["https://chorus.vayne.garden/i/7Q2M-KX4P-99TA", "hi", &"a".repeat(150), &"é".repeat(60)].iter().enumerate()
        {
            let q = encode(text).unwrap();
            let (scale, quiet) = (8, 4);
            let n = (q.size + 2 * quiet) * scale;
            let mut px = vec![255u8; n * n];
            for (y, row) in q.modules.iter().enumerate() {
                for (x, &d) in row.iter().enumerate() {
                    if d {
                        for dy in 0..scale {
                            for dx in 0..scale {
                                px[((y + quiet) * scale + dy) * n + (x + quiet) * scale + dx] = 0;
                            }
                        }
                    }
                }
            }
            let mut out = format!("P5\n{n} {n}\n255\n").into_bytes();
            out.extend(px);
            std::fs::write(format!("{dir}/qr{i}.pgm"), out).unwrap();
            std::fs::write(format!("{dir}/qr{i}.txt"), text).unwrap();
        }
    }
}
