//! A fictional, isolated chat fixture. Only identity bootstrap bypasses ops, as in `seed`.
//! Entity/authored-op IDs are fixed; production-derived tally/notification IDs remain random.

use anyhow::{Context, ensure};
use chorus_core::hlc::Hlc;
use chorus_core::time::{ClockSample, TimeSource};
use rusqlite::{Connection, params};
use serde_json::{Value, json};

use crate::{config::Config, db, ingest, now_ms};

const HOUR: i64 = 3_600_000;
const DAY: i64 = 24 * HOUR;
const OFFSET: i64 = 8 * HOUR; // The fictional systems use Asia/Manila, like the design owner.
const ACCOUNTS: [(&str, &str); 3] =
    [("lanterns", "The Lanterns"), ("junipers", "The Junipers"), ("driftwood", "Driftwood")];

pub struct Seeded {
    pub accounts: [(&'static str, String); 3],
    pub ops: usize,
}

fn id(serial: u64) -> String {
    super::id(1_790_000_000_000, serial)
}

struct Message {
    id: String,
    at: i64,
}

struct Demo<'a> {
    conn: &'a Connection,
    now: i64,
    serial: u64,
}

impl Demo<'_> {
    fn push(
        &mut self,
        who: usize,
        kind: &str,
        scope: &str,
        entity: &str,
        payload: Value,
        at: i64,
    ) -> anyhow::Result<()> {
        self.serial += 1;
        let mut op = super::op(self.serial, at, kind, scope, entity.into(), payload);
        op.id = id(10_000 + self.serial);
        op.hlc = Hlc::new(self.now as u64, self.serial as u16, 1 + who as u32);
        op.time_source = TimeSource::User;
        op.tz_offset_min = 480;
        let session = ingest::Session {
            account_id: id(1 + who as u64),
            // The production API writes follow requests on behalf of their target account.
            device_id: if kind == "follow.request" { ingest::SERVER_DEVICE.into() } else { id(11 + who as u64) },
            sample: ClockSample { server_time: self.now, mono: None, boot_id: None, offset_ms: 0 },
        };
        let (ack, accepted) = ingest::accept(self.conn, &session, op, at, false)?;
        ensure!(ack.error.is_none() && accepted.is_some(), "demo {kind} rejected: {:?}", ack.error);
        Ok(())
    }

    fn account(&mut self, who: usize, kind: &str, entity: &str, payload: Value, at: i64) -> anyhow::Result<()> {
        self.push(who, kind, &format!("account:{}", id(1 + who as u64)), entity, payload, at)
    }

    fn space(&mut self, serial: u64, kind: &str, name: &str, invitees: &[usize], at: i64) -> anyhow::Result<String> {
        let space = id(serial);
        let scope = format!("space:{space}");
        // Exactly the initial scope grant used by the normal space-creation route.
        ingest::grant(self.conn, &id(1), &scope)?;
        self.push(0, "space.create", &scope, &space, json!({"kind":kind,"name":name}), at)?;
        for who in std::iter::once(&0).chain(invitees) {
            self.push(0, "space.join", &scope, &space, json!({"account_id":id(1 + *who as u64)}), at)?;
        }
        Ok(space)
    }

    fn channel(&mut self, serial: u64, space: &str, name: &str, at: i64) -> anyhow::Result<String> {
        let channel = id(serial);
        self.push(
            0,
            "channel.create",
            &format!("space:{space}"),
            &channel,
            json!({"space_id":space,"kind":"text","name":name,"sort_key":format!("{serial:04}")}),
            at,
        )?;
        Ok(channel)
    }

    fn send_payload(&mut self, who: usize, space: &str, payload: Value, at: i64) -> anyhow::Result<Message> {
        let message = Message { id: id(20_000 + self.serial), at };
        self.push(who, "message.send", &format!("space:{space}"), &message.id, payload, at)?;
        Ok(message)
    }

    fn send(
        &mut self,
        who: usize,
        space: &str,
        channel: &str,
        member: &str,
        text: &str,
        at: i64,
    ) -> anyhow::Result<Message> {
        self.send_payload(who, space, json!({"channel_id":channel,"authors":[member],"text":text,"entities":[]}), at)
    }

    fn mark(&mut self, space: &str, channel: &str, reader: &str, message: &Message, at: i64) -> anyhow::Result<()> {
        self.push(
            0,
            "read.mark",
            &format!("space:{space}"),
            channel,
            json!({"channel_id":channel,"reader_member_id":reader,"message_id":message.id,"message_at":message.at}),
            at,
        )
    }

    fn pref(&mut self, key: &str, value: Value, at: i64) -> anyhow::Result<()> {
        self.account(0, "pref.set", &id(1), json!({"device":"","key":key,"value":value}), at)
    }
}

/// Always requires a new destination; never opens an existing database, even in an empty dir.
/// A failure keeps the partial directory for inspection, just like the performance seed.
pub fn run(cfg: &Config) -> anyhow::Result<Seeded> {
    if let Some(parent) = cfg.server.data_dir.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::create_dir(&cfg.server.data_dir)
        .with_context(|| format!("demo destination must be new: {}", cfg.server.data_dir.display()))?;
    let mut conn = db::open(&cfg.db_path())?;
    db::migrate(&mut conn)?;
    let now = now_ms();
    let day_start = (now + OFFSET).div_euclid(DAY) * DAY - OFFSET;
    let created = day_start - 2 * DAY;
    let yesterday = day_start - DAY + 9 * HOUR;
    // Keep today's conversation in the last three hours, including just after local midnight.
    let start = (now - 3 * HOUR).max(day_start);
    let today = |percent: i64| start + (now - start) * percent / 100;
    let tx = conn.transaction()?;
    for (who, (handle, _)) in ACCOUNTS.iter().enumerate() {
        let account = id(1 + who as u64);
        tx.execute(
            "INSERT INTO account(id,kind,handle,is_admin,created_at) VALUES (?1,'system',?2,?3,?4)",
            params![account, handle, who == 0, created],
        )?;
        tx.execute(
            "INSERT INTO device(id,account_id,short_id,name,platform,public_key,created_at)
             VALUES (?1,?2,?3,'Demo generator (not a login key)','cli','demo',?4)",
            params![id(11 + who as u64), account, format!("{who:08x}"), created],
        )?;
        ingest::grant(&tx, &account, &format!("account:{account}"))?;
    }
    let mut d = Demo { conn: &tx, now, serial: 0 };
    for (who, (handle, name)) in ACCOUNTS.iter().enumerate() {
        let account = id(1 + who as u64);
        d.account(who, "account.set", &account, json!({"display_name":name,"handle":handle}), created)?;
        d.account(who, "system.set", &account, json!({"name":name,"timezone":"Asia/Manila"}), created)?;
    }
    let members = [
        (0, "Foxglove", "they/them", "#B79CED", "foxglov"),
        (0, "Amarynthe", "she/her", "#F28B82", "amarynt"),
        (0, "Nairon", "he/they", "#F4B26B", "naironn"),
        (0, "Violet", "she/her", "#E7A4D3", "violett"),
        (1, "Kit", "they/them", "#8FBFA6", "kittttt"),
        (1, "Juniper", "she/they", "#95B58F", "juniper"),
        (2, "River", "they/them", "#89B4CA", "riverrr"),
        (2, "Ren", "he/they", "#B6ADCB", "rennnnn"),
    ];
    for (index, (who, name, pronouns, color, short)) in members.iter().enumerate() {
        d.account(
            *who,
            "member.create",
            &id(21 + index as u64),
            json!({"name":name,"pronouns":pronouns,"color":color,"short_id":short,"sort_key":index.to_string()}),
            created,
        )?;
    }
    let (fox, amarynthe, nairon, violet, kit, juniper, river) =
        (id(21), id(22), id(23), id(24), id(25), id(26), id(27));
    d.account(0, "group.create", &id(40), json!({"kind":"group","name":"Day-to-day"}), created)?;
    for member in [&fox, &amarynthe, &nairon] {
        d.account(0, "group.add_member", &id(40), json!({"member_id":member}), created)?;
    }
    for (serial, follower, target) in [(41, 0, 1), (42, 1, 0), (43, 0, 2), (44, 2, 0)] {
        d.account(
            target,
            "follow.request",
            &id(serial),
            json!({"follower_account_id":id(1 + follower),"target_account_id":id(1 + target as u64)}),
            created,
        )?;
        d.account(target, "follow.accept", &id(serial), json!({}), created + 1)?;
        d.account(
            target,
            "follow.set_ceiling",
            &id(serial),
            json!({"ceiling":chorus_core::notify::preset("close")}),
            created + 2,
        )?;
    }
    for (who, entries) in [
        (
            0,
            vec![
                (&fox, "front", true),
                (&amarynthe, "front", false),
                (&nairon, "cocon", false),
                (&violet, "present", false),
            ],
        ),
        (1, vec![(&juniper, "front", true)]),
        (2, vec![(&river, "front", true)]),
    ] {
        let entries: Vec<Value> = entries.into_iter().map(|(member, layer, primary)|
            json!({"subject_type":"member","subject_id":member,"level":layer,"is_primary":primary})).collect();
        // Legacy switches are the supported Body bridge, with the same current presence on both clients.
        d.account(who, "front.switch", &id(50 + who as u64), json!({"entries":entries}), now - 2 * HOUR)?;
    }

    let internal = d.space(101, "internal", "The Lanterns", &[], created)?;
    let general = d.channel(201, &internal, "General", created)?;
    let handover = d.channel(202, &internal, "Handover", created)?;
    let quiet = d.channel(203, &internal, "Quiet room", created)?;
    let notes = d.channel(204, &internal, "Notes to ourselves", created)?;
    let board = d.channel(205, &internal, "System board", created)?;
    d.pref(&format!("notify_channel:{quiet}"), json!("none"), created)?;
    d.pref("chat.read_per_member", json!(true), created)?;

    let pin = d.send(0, &internal, &general, &amarynthe, "Appointment at 2 pm · details in Handover", yesterday)?;
    d.push(0, "message.pin", &format!("space:{internal}"), &pin.id, json!({"message_id":pin.id}), yesterday + 1)?;
    // Six messages yesterday and six today leave exactly twelve after Amarynthe's mark.
    for (i, (member, text)) in [
        (&fox, "Let's keep tomorrow gentle."),
        (&amarynthe, "I'll write the appointment notes in Handover."),
        (&nairon, "Do we have everything for the morning?"),
        (&violet, "The blue folder is on the table."),
        (&fox, "Found it. Thank you."),
        (&nairon, "Rest well, everyone."),
    ]
    .iter()
    .enumerate()
    {
        d.send(0, &internal, &general, member, text, yesterday + (i as i64 + 1) * HOUR)?;
    }
    let morning_text = "Morning! The appointment details are in Handover.";
    let morning = d.send(0, &internal, &general, &amarynthe, morning_text, today(10))?;
    let folder = d.send(0, &internal, &general, &amarynthe, "Bring the blue folder.", today(12))?;
    let thanks = d.send_payload(
        0,
        &internal,
        json!({"channel_id":general,"authors":[nairon],"text":"Thank you. We can take it slowly.","entities":[],
            "reply_to":morning.id,"quote":{"message_id":morning.id,"offset":0,
                "length":morning_text.encode_utf16().count(),"text":morning_text}}),
        today(20),
    )?;
    for member in [&fox, &amarynthe] {
        d.push(
            0,
            "reaction.add",
            &format!("space:{internal}"),
            &thanks.id,
            json!({"target_type":"message","target_id":thanks.id,"emoji":"❤️","member_id":member}),
            today(22),
        )?;
    }
    let caught_up =
        d.send(0, &internal, &general, &fox, "Just caught up. I'll read the notes before we leave.", today(35))?;
    d.send(0, &internal, &general, &violet, "Can I come along? I'll stay quiet.", today(40))?;
    d.send(0, &internal, &general, &nairon, "Of course. We can take it slowly.", today(45))?;
    for (reader, message) in
        [("", &folder), (&amarynthe, &pin), (&fox, &caught_up), (&nairon, &thanks), (&violet, &pin)]
    {
        d.mark(&internal, &general, reader, message, today(50))?;
    }
    let handover_read = d.send(
        0,
        &internal,
        &handover,
        &amarynthe,
        "Appointment at 2 pm. The address is in the blue folder.",
        yesterday + HOUR,
    )?;
    d.mark(&internal, &handover, "", &handover_read, yesterday + 2 * HOUR)?;
    d.send(0, &internal, &handover, &fox, "Keys are by the door.", today(25))?;
    d.send(0, &internal, &handover, &nairon, "Water and headphones are packed.", today(30))?;
    d.send(0, &internal, &quiet, &violet, "Can we have a quiet evening?", today(55))?;
    d.send(0, &internal, &notes, &violet, "Library card: 2209 4471", yesterday + 3 * HOUR)?;
    let forward = id(301);
    d.push(
        0,
        "message.forward",
        &format!("space:{internal}"),
        &forward,
        json!({"channel_id":notes,"authors":[fox],"text":morning_text,"entities":[],"forward_of_id":morning.id,
            "forward_snapshot":[{"message_id":morning.id,"channel_name":"General","authors":[amarynthe],
                "text":morning_text,"entities":[],"occurred_at":morning.at}]}),
        today(60),
    )?;
    let poll = d.send_payload(
        0,
        &internal,
        json!({"channel_id":board,"authors":[fox],"text":"Dinner plan","entities":[],
            "poll":{"question":"Dinner plan","multiple":false,"options":[
                {"id":"soup","text":"Soup"},{"id":"rice","text":"Rice"},{"id":"pasta","text":"Pasta"}]}}),
        today(65),
    )?;
    for (member, choice) in [(&fox, "soup"), (&amarynthe, "soup"), (&nairon, "rice")] {
        d.account(0, "poll.vote", &poll.id, json!({"member_id":member,"choices":[choice]}), today(70))?;
    }
    let thread = id(206);
    d.push(
        0,
        "channel.create",
        &format!("space:{internal}"),
        &thread,
        json!({"space_id":internal,"kind":"thread","name":"Appointment details","parent_message_id":morning.id}),
        today(15),
    )?;
    for (member, text, percent) in [
        (&fox, "I'll bring the notes.", 16),
        (&amarynthe, "Thank you. No need to rush.", 17),
        (&violet, "I'll bring the headphones.", 18),
    ] {
        d.send(0, &internal, &thread, member, text, today(percent))?;
    }
    let member_dm = id(207);
    d.push(
        0,
        "channel.create",
        &format!("space:{internal}"),
        &member_dm,
        json!({"space_id":internal,"kind":"member_dm","name":"Amarynthe + Foxglove","member_ids":[amarynthe,fox]}),
        created,
    )?;
    d.send(0, &internal, &member_dm, &amarynthe, "Could you sit with me for a moment?", today(75))?;
    d.send(0, &internal, &member_dm, &fox, "Of course. I'm here.", today(76))?;

    for (serial, name, who, member, text) in [
        (102, "Lanterns + Friends", 1, &juniper, "Would Saturday work for you?"),
        (103, "Art corner", 2, &river, "The sketch is ready."),
        (104, "Book club", 1, &juniper, "I've reached the end of chapter 5."),
    ] {
        let space = d.space(serial, "shared", name, &[1, 2], created)?;
        let channel = d.channel(serial + 106, &space, "general", created)?;
        let earlier = d.send(0, &space, &channel, &fox, "Glad we're here together.", yesterday + 4 * HOUR)?;
        d.mark(&space, &channel, "", &earlier, yesterday + 5 * HOUR)?;
        d.send(who, &space, &channel, member, text, today(80))?;
        if name == "Book club" {
            d.send(0, &space, &channel, &fox, "Chapter 6 by Sunday?", today(85))?;
        }
    }
    let dm = d.space(105, "dm", "The Junipers", &[1], created)?;
    let dm_channel = d.channel(211, &dm, "dm", created)?;
    let earlier = d.send(0, &dm, &dm_channel, &fox, "Thinking of you both.", yesterday + 5 * HOUR)?;
    d.mark(&dm, &dm_channel, "", &earlier, yesterday + 6 * HOUR)?;
    d.send(1, &dm, &dm_channel, &kit, "Thank you for checking in.", today(90))?;
    // Settle the historical front reveals through the normal scheduler, without network IO.
    crate::notifier::process_due(&tx, now)?;
    let ops: i64 = tx.query_row("SELECT count(*) FROM op", [], |r| r.get(0))?;
    tx.commit()?;
    Ok(Seeded { accounts: std::array::from_fn(|i| (ACCOUNTS[i].0, id(1 + i as u64))), ops: ops as usize })
}
