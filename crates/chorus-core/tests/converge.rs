//! Convergence simulator (SYNC.md §9.2).
//!
//! Three devices on two accounts run the real client engine against the reference server over a
//! lossy, reordering-free-per-connection (TCP-like) network with random disconnects, duplicated
//! frames, server restarts and a restore from backup. Devices have skewed clocks and create random
//! ops of every merge kind, including invalid and forbidden ones. At quiescence every replica
//! must hold exactly the server's ops and project to exactly the server's state.
//!
//! `CHORUS_SIM_SEEDS=10000 cargo test -p chorus-core --test converge --release` for a long run.
//! A failing seed is printed; rerun it alone with `CHORUS_SIM_SEED=<n>`.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use chorus_core::hlc::HlcClock;
use chorus_core::id::new_id;
use chorus_core::model;
use chorus_core::op::Op;
use chorus_core::sync::{ClientEngine, ClientStore, ClockReading, Frame, MemServer, MemStore};
use chorus_core::time::TimeSource;
use serde_json::{Value, json};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
    fn chance(&mut self, pct: u64) -> bool {
        self.below(100) < pct
    }
    fn pick<'a, T>(&mut self, v: &'a [T]) -> Option<&'a T> {
        if v.is_empty() { None } else { Some(&v[self.below(v.len() as u64) as usize]) }
    }
    fn bytes10(&mut self) -> [u8; 10] {
        let a = self.next().to_le_bytes();
        let b = self.next().to_le_bytes();
        [a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], b[0], b[1]]
    }
}

struct Device {
    name: String,
    account: usize,
    store: MemStore,
    engine: ClientEngine,
    clock: HlcClock,
    skew: i64,
    boot: u32,
    connected: bool,
    to_server: VecDeque<Frame>,
    to_device: VecDeque<Frame>,
    created: Vec<String>,
}

/// Ids the "UI" of each account knows about (shared by an account's devices).
#[derive(Default)]
struct Pools {
    members: Vec<Vec<String>>,
    groups: Vec<Vec<String>>,
    front_ops: Vec<Vec<String>>,
    messages: BTreeMap<String, Vec<(String, i64)>>, // scope → (id, at)
}

struct World {
    rng: Rng,
    now: i64,
    server: MemServer,
    devices: Vec<Device>,
    /// scopes each account writes to (own account scope, spaces)
    scopes: Vec<Vec<String>>,
    pools: Pools,
    restored: bool,
}

fn scope_for(o: &Op) -> String {
    o.scope.clone()
}

impl World {
    fn new(seed: u64) -> World {
        let mut rng = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
        let id = |rng: &mut Rng| new_id(1_700_000_000_000, rng.bytes10());
        let acct_a = id(&mut rng);
        let acct_b = id(&mut rng);
        let internal = id(&mut rng);
        let shared = id(&mut rng);
        let sa = format!("account:{acct_a}");
        let sb = format!("account:{acct_b}");
        let si = format!("space:{internal}");
        let ss = format!("space:{shared}");
        let mut server = MemServer::new("sim");
        for s in [&sa, &si, &ss] {
            server.grant(&acct_a, s);
        }
        for s in [&sb, &ss] {
            server.grant(&acct_b, s);
        }
        let mut devices = Vec::new();
        for (i, (name, account)) in [("a-phone", 0), ("a-desk", 0), ("b-phone", 1)].into_iter().enumerate() {
            let dev_id = id(&mut rng);
            server.devices.insert(dev_id.clone(), [&acct_a, &acct_b][account].clone());
            let skew = (rng.below(6 * 3_600_000) as i64) - 3 * 3_600_000; // ±3 h
            devices.push(Device {
                name: name.into(),
                account,
                store: MemStore::default(),
                engine: ClientEngine::new(&dev_id),
                clock: HlcClock::new(0xa000_0000 + i as u32),
                skew,
                boot: 1,
                connected: false,
                to_server: VecDeque::new(),
                to_device: VecDeque::new(),
                created: Vec::new(),
            });
        }
        World {
            rng,
            now: 1_790_000_000_000,
            server,
            devices,
            scopes: vec![vec![sa, si, ss.clone()], vec![sb, ss]],
            pools: Pools { members: vec![vec![], vec![]], groups: vec![vec![], vec![]], front_ops: vec![vec![], vec![]], ..Pools::default() },
            restored: false,
        }
    }

    fn new_id(&mut self) -> String {
        new_id(self.now as u64, self.rng.bytes10())
    }

    fn make_op(&mut self, d: usize) -> Op {
        let acct = self.devices[d].account;
        let own_scope = self.scopes[acct][0].clone();
        let spaces: Vec<String> = self.scopes[acct][1..].to_vec();
        let member = |w: &mut World| w.rng.pick(&w.pools.members[acct]).cloned().unwrap_or_else(|| "00000000-0000-7000-8000-000000000000".into());
        let roll = self.rng.below(100);
        let entity: Option<String>;
        let (kind, scope, payload): (&str, String, Value) = match roll {
            0..=9 => {
                let id = self.new_id();
                self.pools.members[acct].push(id.clone());
                entity = Some(id);
                ("member.create", own_scope, json!({"name": format!("m{}", self.rng.below(1000))}))
            }
            10..=17 => {
                entity = Some(member(self));
                let p = if self.rng.chance(50) { json!({"name": format!("n{}", self.rng.below(99))}) } else { json!({"color": format!("#{:06x}", self.rng.below(0xffffff))}) };
                ("member.set", own_scope, p)
            }
            18..=20 => {
                entity = Some(member(self));
                (["member.delete", "member.restore", "member.archive", "member.unarchive"][self.rng.below(4) as usize], own_scope, json!({}))
            }
            21..=23 => {
                let id = self.new_id();
                self.pools.groups[acct].push(id.clone());
                entity = Some(id);
                ("group.create", own_scope, json!({"name": "g", "kind": "subsystem"}))
            }
            24..=28 => {
                entity = Some(self.rng.pick(&self.pools.groups[acct]).cloned().unwrap_or_else(|| self.new_id()));
                let k = if self.rng.chance(60) { "group.add_member" } else { "group.remove_member" };
                (k, own_scope, json!({"member_id": member(self)}))
            }
            29..=46 => {
                let id = self.new_id();
                entity = Some(id.clone());
                let e = |w: &mut World, primary: bool| {
                    let level = ["front", "cocon", "present"][w.rng.below(3) as usize];
                    json!({"subject_type": "member", "subject_id": member(w), "level": level, "is_primary": primary})
                };
                let (k, p) = match self.rng.below(7) {
                    0 | 1 => {
                        let n = self.rng.below(3);
                        let entries: Vec<Value> = (0..n).map(|i| e(self, i == 0)).collect();
                        ("front.switch", json!({"entries": entries}))
                    }
                    2 => {
                        let primary = self.rng.chance(30);
                        ("front.add", json!({"entry": e(self, primary)}))
                    }
                    3 => ("front.remove", json!({"subject_type": "member", "subject_id": member(self)})),
                    4 => ("front.update", json!({"subject_type": "member", "subject_id": member(self), "is_primary": true})),
                    5 => {
                        let t = self.rng.pick(&self.pools.front_ops[acct]).cloned().unwrap_or_else(|| id.clone());
                        (if self.rng.chance(70) { "front.retract" } else { "front.unretract" }, json!({"target_op_id": t}))
                    }
                    _ => {
                        let t = self.rng.pick(&self.pools.front_ops[acct]).cloned().unwrap_or_else(|| id.clone());
                        let at = self.now - self.rng.below(3_600_000) as i64;
                        ("front.amend", json!({"target_op_id": t, "occurred_at": at}))
                    }
                };
                self.pools.front_ops[acct].push(id);
                (k, own_scope, p)
            }
            47..=62 => {
                let scope = self.rng.pick(&spaces).cloned().unwrap_or_default();
                let id = self.new_id();
                entity = Some(id.clone());
                let at = self.now;
                self.pools.messages.entry(scope.clone()).or_default().push((id, at));
                ("message.send", scope, json!({"channel_id": "c1", "authors": [member(self)], "text": format!("hi {}", self.rng.below(100)), "entities": []}))
            }
            63..=78 => {
                let scope = self.rng.pick(&spaces).cloned().unwrap_or_default();
                let msgs = self.pools.messages.get(&scope).cloned().unwrap_or_default();
                let (mid, mat) = self.rng.pick(&msgs).cloned().unwrap_or_else(|| (self.new_id(), 0));
                entity = Some(mid.clone());
                match self.rng.below(7) {
                    0 => ("message.edit", scope, json!({"message_id": mid, "text": format!("edit {}", self.rng.below(100)), "entities": []})),
                    1 => ("message.delete", scope, json!({})),
                    2 => ("message.restore", scope, json!({})),
                    3 => ("message.pin", scope, json!({})),
                    4 => ("reaction.add", scope, json!({"target_type": "message", "target_id": mid, "emoji": "💜", "member_id": member(self)})),
                    5 => ("reaction.remove", scope, json!({"target_type": "message", "target_id": mid, "emoji": "💜", "member_id": member(self)})),
                    _ => ("read.mark", scope, json!({"channel_id": "c1", "message_id": mid, "message_at": mat, "reader_member_id": ""})),
                }
            }
            79..=84 => {
                entity = None;
                ("field.set_value", own_scope, json!({"member_id": member(self), "field_id": "f1", "value": self.rng.below(10)}))
            }
            85..=89 => {
                // invalid: a field the kind may not set → must be rejected, not lost silently
                entity = Some(member(self));
                ("member.set", own_scope, json!({"account_id": "evil"}))
            }
            90..=93 => {
                // forbidden: writing into the other account's scope
                let other = self.scopes[1 - acct][0].clone();
                entity = Some(self.new_id());
                ("member.create", other, json!({"name": "intruder"}))
            }
            _ => {
                // unknown kind from a "newer client": stored and forwarded, not projected
                entity = Some(self.new_id());
                ("future.feature", own_scope, json!({"x": 1}))
            }
        };
        let dev = &mut self.devices[d];
        let wall = self.now + dev.skew;
        let hlc = dev.clock.tick(wall as u64);
        let id = new_id(wall as u64, self.rng.bytes10());
        let seen = dev.store.cursor(&scope);
        Op {
            id,
            kind: kind.into(),
            v: 1,
            scope,
            entity_id: entity,
            hlc,
            device_at: wall,
            tz_offset_min: 60,
            mono: Some(self.now - 1_789_000_000_000),
            boot_id: Some(self.devices[d].boot.to_string()),
            time_source: TimeSource::Auto,
            seen_seq: seen,
            member_id: None,
            payload,
            seq: None,
            account_id: None,
            device_id: None,
            occurred_at: None,
            received_at: None,
        }
    }

    fn clock_reading(&self, d: usize) -> ClockReading {
        let dev = &self.devices[d];
        ClockReading { wall: self.now + dev.skew, mono: Some(self.now - 1_789_000_000_000), boot_id: Some(dev.boot.to_string()) }
    }

    fn connect(&mut self, d: usize) {
        if self.devices[d].connected {
            return;
        }
        let clock = self.clock_reading(d);
        let dev = &mut self.devices[d];
        dev.connected = true;
        let hello = dev.engine.on_connect(&dev.store, clock, "t");
        dev.to_server.push_back(hello);
    }

    fn disconnect(&mut self, d: usize) {
        let dev = &mut self.devices[d];
        if !dev.connected {
            return;
        }
        dev.connected = false;
        dev.to_server.clear();
        dev.to_device.clear();
        dev.engine.on_disconnect();
        let id = dev.engine.device_id.clone();
        self.server.disconnect(&id);
    }

    fn deliver_to_server(&mut self, d: usize) -> bool {
        let Some(f) = self.devices[d].to_server.pop_front() else { return false };
        let id = self.devices[d].engine.device_id.clone();
        for (dest, frame) in self.server.on_frame(&id, f, self.now) {
            if let Some(dd) = self.devices.iter_mut().find(|x| x.engine.device_id == dest)
                && dd.connected {
                    dd.to_device.push_back(frame);
                }
        }
        true
    }

    fn deliver_to_device(&mut self, d: usize) -> bool {
        let dev = &mut self.devices[d];
        let Some(f) = dev.to_device.pop_front() else { return false };
        if let Frame::Ops { ops, .. } = &f {
            for o in ops {
                dev.clock.observe(o.hlc, (self.now + dev.skew) as u64);
            }
        }
        let out = dev.engine.on_frame(&mut dev.store, f);
        dev.to_server.extend(out);
        true
    }

    fn step(&mut self) {
        self.now += self.rng.below(5_000) as i64;
        let n = self.devices.len() as u64;
        let d = self.rng.below(n) as usize;
        match self.rng.below(100) {
            0..=29 => {
                let op = self.make_op(d);
                let dev = &mut self.devices[d];
                dev.created.push(op.id.clone());
                dev.store.add_local(op);
                let out = dev.engine.pump(&dev.store);
                if dev.connected {
                    dev.to_server.extend(out);
                }
            }
            30..=59 => {
                self.deliver_to_server(d);
            }
            60..=84 => {
                self.deliver_to_device(d);
            }
            85..=90 => self.disconnect(d),
            91..=95 => self.connect(d),
            96 => {
                // duplicated delivery of the next frame to the server
                if let Some(f) = self.devices[d].to_server.front().cloned() {
                    self.devices[d].to_server.push_front(f);
                }
            }
            97 => {
                // reboot: monotonic clock restarts
                self.disconnect(d);
                self.devices[d].boot += 1;
            }
            98 => {
                for i in 0..self.devices.len() {
                    self.disconnect(i);
                }
                self.server.disconnect_all();
            }
            _ => {
                if !self.restored && !self.server.log.is_empty() && self.rng.chance(30) {
                    for i in 0..self.devices.len() {
                        self.disconnect(i);
                    }
                    let keep = self.rng.below(self.server.log.len() as u64) as usize;
                    self.server.restore(keep);
                    self.restored = true;
                }
            }
        }
    }

    fn quiesce(&mut self) {
        for _round in 0..4 {
            for d in 0..self.devices.len() {
                self.connect(d);
            }
            let mut guard = 0;
            loop {
                let mut moved = false;
                for d in 0..self.devices.len() {
                    moved |= self.deliver_to_server(d);
                    moved |= self.deliver_to_device(d);
                }
                if !moved {
                    break;
                }
                guard += 1;
                assert!(guard < 200_000, "no quiescence");
            }
        }
    }
}

fn run(seed: u64, steps: usize) -> Result<(), String> {
    let mut w = World::new(seed);
    for _ in 0..steps {
        w.step();
    }
    w.quiesce();

    let server_ids: BTreeSet<&str> = w.server.log.iter().map(|o| o.id.as_str()).collect();
    // seqs are gapless
    for (i, o) in w.server.log.iter().enumerate() {
        if o.seq != Some(i as i64 + 1) {
            return Err(format!("seq gap at {i}"));
        }
    }
    for dev in &w.devices {
        // 1. nothing created is lost: each op is on the server or was rejected with a reason
        for id in &dev.created {
            if !server_ids.contains(id.as_str()) && !dev.store.rejected.contains_key(id) {
                return Err(format!("{}: op {id} lost", dev.name));
            }
        }
        // 2. no pending ops remain
        let mut pending = dev.store.pending(&BTreeSet::new(), usize::MAX, false);
        pending.extend(dev.store.pending(&BTreeSet::new(), usize::MAX, true));
        if !pending.is_empty() {
            return Err(format!("{}: {} ops still pending", dev.name, pending.len()));
        }
        // 3. per scope: same op set and digest as the server
        let scopes = &w.scopes[dev.account];
        for s in scopes {
            let mine: BTreeSet<&str> = dev.store.confirmed().filter(|o| &scope_for(o) == s).map(|o| o.id.as_str()).collect();
            let theirs: BTreeSet<&str> = w.server.log.iter().filter(|o| &o.scope == s).map(|o| o.id.as_str()).collect();
            if mine != theirs {
                let missing: Vec<_> = theirs.difference(&mine).take(3).collect();
                let extra: Vec<_> = mine.difference(&theirs).take(3).collect();
                return Err(format!("{}: scope {s} differs: missing {missing:?} extra {extra:?}", dev.name));
            }
            if dev.store.digest(s) != w.server.digest(s) {
                return Err(format!("{}: digest differs for {s}", dev.name));
            }
            // stamps agree
            for o in dev.store.confirmed().filter(|o| &o.scope == s) {
                let so = w.server.log.iter().find(|x| x.id == o.id).ok_or("vanished")?;
                if (o.seq, o.occurred_at) != (so.seq, so.occurred_at) {
                    return Err(format!("{}: stale stamp on {}", dev.name, o.id));
                }
            }
        }
        // 4. identical projections
        let mine = model::project(dev.store.confirmed().filter(|o| scopes.contains(&o.scope)));
        let theirs = model::project(w.server.log.iter().filter(|o| scopes.contains(&o.scope)));
        if mine.canonical() != theirs.canonical() {
            return Err(format!("{}: projection differs", dev.name));
        }
    }
    // 5. projection is independent of op order
    let base = model::project(w.server.log.iter()).canonical();
    let mut shuffled: Vec<&Op> = w.server.log.iter().collect();
    let mut r = Rng(seed | 3);
    for i in (1..shuffled.len()).rev() {
        shuffled.swap(i, r.below(i as u64 + 1) as usize);
    }
    if model::project(shuffled).canonical() != base {
        return Err("projection depends on op order".into());
    }
    if std::env::var("CHORUS_SIM_STATS").is_ok() {
        let rejected: usize = w.devices.iter().map(|d| d.store.rejected.len()).sum();
        let repairs: u64 = w.devices.iter().map(|d| d.engine.repairs).sum();
        eprintln!("seed {seed}: ops {} rejected {rejected} repairs {repairs} restored {}", w.server.log.len(), w.restored);
    }
    // 6. the invalid and forbidden ops were rejected, not accepted
    for o in &w.server.log {
        if o.kind == "member.set" && o.payload.get("account_id").is_some() {
            return Err("invalid op accepted".into());
        }
        if o.payload.get("name").and_then(Value::as_str) == Some("intruder") {
            return Err("forbidden op accepted".into());
        }
    }
    Ok(())
}

#[test]
fn replicas_converge() {
    if let Ok(seed) = std::env::var("CHORUS_SIM_SEED") {
        let seed: u64 = seed.parse().unwrap_or(1);
        if let Err(e) = run(seed, 800) {
            panic!("seed {seed}: {e}");
        }
        return;
    }
    let seeds: u64 = std::env::var("CHORUS_SIM_SEEDS").ok().and_then(|s| s.parse().ok()).unwrap_or(300);
    let mut failures = Vec::new();
    for seed in 1..=seeds {
        let steps = 200 + (seed as usize * 37) % 900;
        if let Err(e) = run(seed, steps) {
            failures.push(format!("seed {seed}: {e}"));
            if failures.len() >= 5 {
                break;
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
