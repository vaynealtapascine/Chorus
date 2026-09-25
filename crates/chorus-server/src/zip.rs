//! A ZIP writer for the export bundle (D-068): *stored* entries (no compression — media doesn't
//! compress, and it keeps the dependency list as it is), streamed in one pass. Each entry's CRC-32
//! and size go in a data descriptor after its bytes, so nothing is buffered; ZIP64 records are
//! written when an entry, an offset or the entry count outgrows the classic fields. Tested by
//! reading archives back with Python's `zipfile` (tests below and `tests/export_bundle.rs`).

use std::io::{self, Read, Write};

const LOCAL: u32 = 0x0403_4b50;
const DESCRIPTOR: u32 = 0x0807_4b50;
const CENTRAL: u32 = 0x0201_4b50;
const END: u32 = 0x0605_4b50;
const END64: u32 = 0x0606_4b50;
const LOCATOR64: u32 = 0x0706_4b50;
/// bit 3: sizes and CRC follow in a data descriptor; bit 11: names are UTF-8
const FLAGS: u16 = (1 << 3) | (1 << 11);
const MAX32: u64 = 0xFFFF_FFFF;

fn crc_table() -> [u32; 256] {
    let mut table = [0u32; 256];
    for (i, slot) in table.iter_mut().enumerate() {
        let mut c = i as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
        }
        *slot = c;
    }
    table
}

/// CRC-32 (IEEE), fed in pieces.
pub struct Crc32 {
    table: [u32; 256],
    value: u32,
}

impl Default for Crc32 {
    fn default() -> Self {
        Crc32 { table: crc_table(), value: 0xFFFF_FFFF }
    }
}

impl Crc32 {
    pub fn update(&mut self, bytes: &[u8]) {
        for b in bytes {
            self.value = self.table[((self.value ^ u32::from(*b)) & 0xFF) as usize] ^ (self.value >> 8);
        }
    }

    pub fn finish(&self) -> u32 {
        self.value ^ 0xFFFF_FFFF
    }
}

struct Entry {
    name: String,
    crc: u32,
    size: u64,
    offset: u64,
    zip64: bool,
}

/// Counts what goes through, so offsets are known without seeking.
struct Counted<W> {
    out: W,
    written: u64,
}

impl<W: Write> Write for Counted<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.out.write(buf)?;
        self.written += n as u64;
        Ok(n)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.out.flush()
    }
}

/// An entry's bytes on their way out: CRC and size as they pass.
struct Body<'a, W> {
    out: &'a mut Counted<W>,
    crc: Crc32,
    size: u64,
}

impl<W: Write> Write for Body<'_, W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.out.write(buf)?;
        self.crc.update(&buf[..n]);
        self.size += n as u64;
        Ok(n)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.out.flush()
    }
}

pub struct ZipWriter<W: Write> {
    out: Counted<W>,
    entries: Vec<Entry>,
    /// MS-DOS time and date stamped on every entry
    dos: (u16, u16),
    /// write ZIP64 records even when they aren't needed (tests)
    force_zip64: bool,
}

/// MS-DOS time and date for a Unix time in milliseconds (UTC), clamped to what DOS can say.
pub fn dos_time(unix_ms: i64) -> (u16, u16) {
    let secs = unix_ms.div_euclid(1000).max(315_532_800); // 1980-01-01
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // civil date from days since 1970 (Howard Hinnant's algorithm)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = (yoe + era * 400 + i64::from(month <= 2)).min(2107);
    let time = ((rem / 3600) << 11) | (((rem % 3600) / 60) << 5) | ((rem % 60) / 2);
    let date = ((year - 1980) << 9) | (month << 5) | day;
    (time as u16, date as u16)
}

impl<W: Write> ZipWriter<W> {
    pub fn new(out: W, unix_ms: i64) -> ZipWriter<W> {
        ZipWriter { out: Counted { out, written: 0 }, entries: Vec::new(), dos: dos_time(unix_ms), force_zip64: false }
    }

    #[cfg(test)]
    fn forcing_zip64(mut self) -> Self {
        self.force_zip64 = true;
        self
    }

    /// Bytes written so far.
    pub fn written(&self) -> u64 {
        self.out.written
    }

    /// Add one entry, copying `data` through (`size_hint`: its length if known, to decide on
    /// ZIP64 up front). Returns the bytes copied. `on_chunk` sees every chunk (hashing, progress).
    pub fn add(
        &mut self,
        name: &str,
        mut data: impl Read,
        size_hint: u64,
        mut on_chunk: impl FnMut(&[u8]),
    ) -> io::Result<u64> {
        self.add_with(name, size_hint, |w| {
            let mut buf = vec![0u8; 256 * 1024];
            loop {
                let n = data.read(&mut buf)?;
                if n == 0 {
                    return Ok(());
                }
                on_chunk(&buf[..n]);
                w.write_all(&buf[..n])?;
            }
        })
    }

    /// Add one entry whose bytes `write` produces. `size_hint` ≥ 4 GiB (or unknown and possibly
    /// that big: pass `u64::MAX`) makes it a ZIP64 entry. Returns the bytes written.
    pub fn add_with(
        &mut self,
        name: &str,
        size_hint: u64,
        write: impl FnOnce(&mut dyn Write) -> io::Result<()>,
    ) -> io::Result<u64> {
        let zip64 = self.force_zip64 || size_hint >= MAX32;
        let offset = self.out.written;
        let (time, date) = self.dos;
        let w = &mut self.out;
        w.write_all(&LOCAL.to_le_bytes())?;
        w.write_all(&(if zip64 { 45u16 } else { 20u16 }).to_le_bytes())?; // version needed
        w.write_all(&FLAGS.to_le_bytes())?;
        w.write_all(&0u16.to_le_bytes())?; // stored
        w.write_all(&time.to_le_bytes())?;
        w.write_all(&date.to_le_bytes())?;
        w.write_all(&0u32.to_le_bytes())?; // crc: in the descriptor
        let placeholder: u32 = if zip64 { 0xFFFF_FFFF } else { 0 };
        w.write_all(&placeholder.to_le_bytes())?; // compressed size
        w.write_all(&placeholder.to_le_bytes())?; // size
        w.write_all(&(name.len() as u16).to_le_bytes())?;
        w.write_all(&(if zip64 { 20u16 } else { 0u16 }).to_le_bytes())?; // extra length
        w.write_all(name.as_bytes())?;
        if zip64 {
            w.write_all(&1u16.to_le_bytes())?; // ZIP64 extra, sizes follow in the descriptor
            w.write_all(&16u16.to_le_bytes())?;
            w.write_all(&0u64.to_le_bytes())?;
            w.write_all(&0u64.to_le_bytes())?;
        }
        let mut body = Body { out: w, crc: Crc32::default(), size: 0 };
        write(&mut body)?;
        let (crc, size) = (body.crc.finish(), body.size);
        if !zip64 && size >= MAX32 {
            return Err(io::Error::other(format!("{name} is larger than its size hint said")));
        }
        w.write_all(&DESCRIPTOR.to_le_bytes())?;
        w.write_all(&crc.to_le_bytes())?;
        if zip64 {
            w.write_all(&size.to_le_bytes())?;
            w.write_all(&size.to_le_bytes())?;
        } else {
            w.write_all(&(size as u32).to_le_bytes())?;
            w.write_all(&(size as u32).to_le_bytes())?;
        }
        self.entries.push(Entry { name: name.to_string(), crc, size, offset, zip64 });
        Ok(size)
    }

    /// Write the central directory (and ZIP64 end records when needed); returns the writer.
    pub fn finish(mut self) -> io::Result<W> {
        let cd_start = self.out.written;
        let (time, date) = self.dos;
        for e in &self.entries {
            let big_size = e.zip64 || e.size >= MAX32;
            let big_offset = self.force_zip64 || e.offset >= MAX32;
            let mut extra = Vec::new();
            if big_size {
                extra.extend_from_slice(&e.size.to_le_bytes());
                extra.extend_from_slice(&e.size.to_le_bytes());
            }
            if big_offset {
                extra.extend_from_slice(&e.offset.to_le_bytes());
            }
            let w = &mut self.out;
            w.write_all(&CENTRAL.to_le_bytes())?;
            w.write_all(&(0x0300u16 | 45).to_le_bytes())?; // made by: Unix, 4.5
            w.write_all(&(if big_size || big_offset { 45u16 } else { 20u16 }).to_le_bytes())?;
            w.write_all(&FLAGS.to_le_bytes())?;
            w.write_all(&0u16.to_le_bytes())?;
            w.write_all(&time.to_le_bytes())?;
            w.write_all(&date.to_le_bytes())?;
            w.write_all(&e.crc.to_le_bytes())?;
            let size32 = if big_size { MAX32 as u32 } else { e.size as u32 };
            w.write_all(&size32.to_le_bytes())?;
            w.write_all(&size32.to_le_bytes())?;
            w.write_all(&(e.name.len() as u16).to_le_bytes())?;
            w.write_all(&(if extra.is_empty() { 0 } else { extra.len() as u16 + 4 }).to_le_bytes())?;
            w.write_all(&0u16.to_le_bytes())?; // comment
            w.write_all(&0u16.to_le_bytes())?; // disk
            w.write_all(&0u16.to_le_bytes())?; // internal attributes
            w.write_all(&(0o100644u32 << 16).to_le_bytes())?; // a regular file, rw-r--r--
            w.write_all(&(if big_offset { MAX32 as u32 } else { e.offset as u32 }).to_le_bytes())?;
            w.write_all(e.name.as_bytes())?;
            if !extra.is_empty() {
                w.write_all(&1u16.to_le_bytes())?;
                w.write_all(&(extra.len() as u16).to_le_bytes())?;
                w.write_all(&extra)?;
            }
        }
        let cd_end = self.out.written;
        let count = self.entries.len() as u64;
        let cd_size = cd_end - cd_start;
        let need64 = self.force_zip64 || count >= 0xFFFF || cd_start >= MAX32 || cd_size >= MAX32;
        let w = &mut self.out;
        if need64 {
            w.write_all(&END64.to_le_bytes())?;
            w.write_all(&44u64.to_le_bytes())?; // size of the rest of this record
            w.write_all(&(0x0300u16 | 45).to_le_bytes())?;
            w.write_all(&45u16.to_le_bytes())?;
            w.write_all(&0u32.to_le_bytes())?; // this disk
            w.write_all(&0u32.to_le_bytes())?; // disk with the central directory
            w.write_all(&count.to_le_bytes())?;
            w.write_all(&count.to_le_bytes())?;
            w.write_all(&cd_size.to_le_bytes())?;
            w.write_all(&cd_start.to_le_bytes())?;
            w.write_all(&LOCATOR64.to_le_bytes())?;
            w.write_all(&0u32.to_le_bytes())?;
            w.write_all(&cd_end.to_le_bytes())?; // where the ZIP64 end record starts
            w.write_all(&1u32.to_le_bytes())?; // disks
        }
        let count16 = if need64 { 0xFFFF } else { count as u16 };
        w.write_all(&END.to_le_bytes())?;
        w.write_all(&0u16.to_le_bytes())?;
        w.write_all(&0u16.to_le_bytes())?;
        w.write_all(&count16.to_le_bytes())?;
        w.write_all(&count16.to_le_bytes())?;
        w.write_all(&(if need64 { MAX32 as u32 } else { cd_size as u32 }).to_le_bytes())?;
        w.write_all(&(if need64 { MAX32 as u32 } else { cd_start as u32 }).to_le_bytes())?;
        w.write_all(&0u16.to_le_bytes())?; // comment
        w.flush()?;
        Ok(self.out.out)
    }
}

/// One entry of an archive read by [`ZipReader`].
#[derive(Clone, Debug)]
pub struct ReadEntry {
    pub name: String,
    pub crc: u32,
    pub size: u64,
    /// where its local header starts
    offset: u64,
}

/// Reads archives of *stored* entries, as [`ZipWriter`] makes them (account import, R27): the
/// central directory (ZIP64 included), then each entry's bytes, their CRC checked as they pass.
/// Compressed entries are refused rather than guessed at.
pub struct ZipReader<R> {
    file: R,
    entries: Vec<ReadEntry>,
}

fn u16_at(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}
fn u32_at(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap_or_default())
}
fn u64_at(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap_or_default())
}

fn bad(why: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, why.into())
}

impl<R: Read + io::Seek> ZipReader<R> {
    pub fn new(mut file: R) -> io::Result<ZipReader<R>> {
        use io::SeekFrom;
        let len = file.seek(SeekFrom::End(0))?;
        // the end record is in the last 22 bytes plus a comment of up to 64 KiB
        let tail_len = len.min(22 + 65_535);
        file.seek(SeekFrom::Start(len - tail_len))?;
        let mut tail = vec![0u8; tail_len as usize];
        file.read_exact(&mut tail)?;
        let end = (0..tail.len().saturating_sub(21))
            .rev()
            .find(|&i| u32_at(&tail, i) == END)
            .ok_or_else(|| bad("not a zip file (no end record)"))?;
        let mut count = u64::from(u16_at(&tail, end + 10));
        let mut cd_size = u64::from(u32_at(&tail, end + 12));
        let mut cd_start = u64::from(u32_at(&tail, end + 16));
        if count == 0xFFFF || cd_size == MAX32 || cd_start == MAX32 {
            // ZIP64: the locator sits just before the end record and points at the ZIP64 end record
            let at = end.checked_sub(20).ok_or_else(|| bad("ZIP64 locator missing"))?;
            if u32_at(&tail, at) != LOCATOR64 {
                return Err(bad("ZIP64 locator missing"));
            }
            let end64 = u64_at(&tail, at + 8);
            file.seek(SeekFrom::Start(end64))?;
            let mut rec = [0u8; 56];
            file.read_exact(&mut rec)?;
            if u32_at(&rec, 0) != END64 {
                return Err(bad("ZIP64 end record missing"));
            }
            count = u64_at(&rec, 32);
            cd_size = u64_at(&rec, 40);
            cd_start = u64_at(&rec, 48);
        }
        if cd_start.checked_add(cd_size).is_none_or(|e| e > len) || cd_size > 1 << 30 {
            return Err(bad("the central directory is out of the file"));
        }
        file.seek(SeekFrom::Start(cd_start))?;
        let mut cd = vec![0u8; cd_size as usize];
        file.read_exact(&mut cd)?;
        let mut entries = Vec::new();
        let mut at = 0usize;
        for _ in 0..count {
            if at + 46 > cd.len() || u32_at(&cd, at) != CENTRAL {
                return Err(bad("a damaged central directory"));
            }
            let method = u16_at(&cd, at + 10);
            let crc = u32_at(&cd, at + 16);
            let mut csize = u64::from(u32_at(&cd, at + 20));
            let mut size = u64::from(u32_at(&cd, at + 24));
            let name_len = usize::from(u16_at(&cd, at + 28));
            let extra_len = usize::from(u16_at(&cd, at + 30));
            let comment_len = usize::from(u16_at(&cd, at + 32));
            let mut offset = u64::from(u32_at(&cd, at + 42));
            let name_at = at + 46;
            let extra_at = name_at + name_len;
            let next = extra_at + extra_len + comment_len;
            if next > cd.len() {
                return Err(bad("a damaged central directory"));
            }
            let name =
                String::from_utf8(cd[name_at..extra_at].to_vec()).map_err(|_| bad("an entry name isn't UTF-8"))?;
            // ZIP64 extra field: the 0xFFFFFFFF fields follow in order (size, compressed, offset)
            let mut x = extra_at;
            while x + 4 <= extra_at + extra_len {
                let (id, n) = (u16_at(&cd, x), usize::from(u16_at(&cd, x + 2)));
                if id == 1 {
                    let mut f = x + 4;
                    for field in [&mut size, &mut csize, &mut offset] {
                        if *field == MAX32 && f + 8 <= x + 4 + n {
                            *field = u64_at(&cd, f);
                            f += 8;
                        }
                    }
                }
                x += 4 + n;
            }
            if method != 0 || csize != size {
                return Err(bad(format!(
                    "{name} is compressed; only stored entries are read (use the file as exported)"
                )));
            }
            entries.push(ReadEntry { name, crc, size, offset });
            at = next;
        }
        Ok(ZipReader { file, entries })
    }

    pub fn entries(&self) -> &[ReadEntry] {
        &self.entries
    }

    pub fn entry(&self, name: &str) -> Option<&ReadEntry> {
        self.entries.iter().find(|e| e.name == name)
    }

    /// Stream an entry's bytes to `each` in chunks; fails if its CRC doesn't match.
    pub fn read(&mut self, entry: &ReadEntry, mut each: impl FnMut(&[u8]) -> io::Result<()>) -> io::Result<()> {
        use io::SeekFrom;
        self.file.seek(SeekFrom::Start(entry.offset))?;
        let mut head = [0u8; 30];
        self.file.read_exact(&mut head)?;
        if u32_at(&head, 0) != LOCAL {
            return Err(bad(format!("{}: no local header where the directory says", entry.name)));
        }
        let skip = i64::from(u16_at(&head, 26)) + i64::from(u16_at(&head, 28));
        self.file.seek(SeekFrom::Current(skip))?;
        let mut crc = Crc32::default();
        let mut left = entry.size;
        let mut buf = vec![0u8; 256 * 1024];
        while left > 0 {
            let n = left.min(buf.len() as u64) as usize;
            self.file.read_exact(&mut buf[..n])?;
            crc.update(&buf[..n]);
            each(&buf[..n])?;
            left -= n as u64;
        }
        if crc.finish() != entry.crc {
            return Err(bad(format!("{}: its CRC doesn't match (a damaged file)", entry.name)));
        }
        Ok(())
    }

    /// A whole entry in memory (small ones: the manifest).
    pub fn read_all(&mut self, entry: &ReadEntry) -> io::Result<Vec<u8>> {
        let mut out = Vec::with_capacity(entry.size as usize);
        self.read(entry, |c| {
            out.extend_from_slice(c);
            Ok(())
        })?;
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32_matches_the_check_value() {
        let mut c = Crc32::default();
        c.update(b"12345");
        c.update(b"6789");
        assert_eq!(c.finish(), 0xCBF4_3926);
    }

    #[test]
    fn dos_time_of_a_known_moment() {
        // 2026-09-24 13:45:30 UTC
        let (time, date) = dos_time(1_790_257_530_000);
        assert_eq!((date >> 9) + 1980, 2026);
        assert_eq!((date >> 5) & 0xF, 9);
        assert_eq!(date & 0x1F, 24);
        assert_eq!(time >> 11, 13);
        assert_eq!((time >> 5) & 0x3F, 45);
        assert_eq!((time & 0x1F) * 2, 30);
    }

    fn python() -> Option<&'static str> {
        // `--version` must succeed: on Windows `python3` may be the Store's installer stub
        ["python3", "python"]
            .into_iter()
            .find(|p| std::process::Command::new(p).arg("--version").output().is_ok_and(|o| o.status.success()))
    }

    /// Python's zipfile reads what we write back byte for byte, classic and ZIP64 alike.
    #[test]
    fn python_reads_it_back() {
        let Some(py) = python() else {
            eprintln!("no python: skipped");
            return;
        };
        let dir = std::env::temp_dir().join(format!("chorus-zip-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        for zip64 in [false, true] {
            let path = dir.join(format!("t{zip64}.zip"));
            let mut z = ZipWriter::new(std::fs::File::create(&path).unwrap(), 1_790_257_530_000);
            if zip64 {
                z = z.forcing_zip64();
            }
            let big: Vec<u8> = (0..300_000u32).map(|i| (i * 7) as u8).collect();
            z.add("README.txt", &b"hello"[..], 5, |_| {}).unwrap();
            z.add_with("csv/members.csv", u64::MAX, |_| Ok(())).unwrap();
            z.add("blobs/ü-name", &big[..], big.len() as u64, |_| {}).unwrap();
            z.finish().unwrap();
            let script = format!(
                "import zipfile,hashlib\nz=zipfile.ZipFile(r'{}')\nassert z.testzip() is None\n\
                 print(z.read('README.txt').decode(), len(z.read('csv/members.csv')), \
                 hashlib.sha256(z.read('blobs/ü-name')).hexdigest(), sorted(z.namelist()))",
                path.display()
            );
            let out = std::process::Command::new(py)
                .env("PYTHONIOENCODING", "utf-8")
                .arg("-c")
                .arg(&script)
                .output()
                .unwrap();
            assert!(out.status.success(), "zip64={zip64}: {}", String::from_utf8_lossy(&out.stderr));
            let text = String::from_utf8(out.stdout).unwrap();
            use sha2::Digest as _;
            let want = format!("{:x}", sha2::Sha256::digest(&big));
            assert!(text.starts_with("hello 0 "), "{text}");
            assert!(text.contains(&want), "{text}");
            assert!(text.contains("'blobs/ü-name', 'csv/members.csv'"), "{text}");
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn reads_back_what_it_writes_and_notices_damage() {
        for force in [false, true] {
            let mut z = ZipWriter::new(Vec::new(), 0);
            if force {
                z = z.forcing_zip64();
            }
            let big = vec![7u8; 700_000];
            z.add("a.txt", &b"hello"[..], 5, |_| {}).unwrap();
            z.add("blobs/ü", &big[..], big.len() as u64, |_| {}).unwrap();
            z.add("empty", &b""[..], 0, |_| {}).unwrap();
            let bytes = z.finish().unwrap();
            let mut r = ZipReader::new(io::Cursor::new(bytes.clone())).unwrap();
            let names: Vec<&str> = r.entries().iter().map(|e| e.name.as_str()).collect();
            assert_eq!(names, ["a.txt", "blobs/ü", "empty"]);
            let e = r.entry("blobs/ü").unwrap().clone();
            assert_eq!(r.read_all(&e).unwrap(), big);
            let e = r.entry("a.txt").unwrap().clone();
            assert_eq!(r.read_all(&e).unwrap(), b"hello");
            // one byte of "hello" changed: the CRC says so
            let mut damaged = bytes;
            let at = damaged.windows(5).position(|w| w == b"hello").unwrap();
            damaged[at] = b'j';
            let mut r = ZipReader::new(io::Cursor::new(damaged)).unwrap();
            let e = r.entry("a.txt").unwrap().clone();
            assert!(r.read_all(&e).is_err());
        }
        assert!(ZipReader::new(io::Cursor::new(b"not a zip at all".to_vec())).is_err());
    }
}
