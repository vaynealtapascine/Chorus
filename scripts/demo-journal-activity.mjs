// Demo-only: cross-account journal activity on an isolated demo server, never the real one (UI19,
// D-127): Lanterns posts, Junipers reply, heart and quote, Driftwood replies (delayed and quiet),
// and a new system "Mossbank" asks to follow. Needs a fresh `seed-demo` (docs/OPS.md §8) and
// three device invites (Lanterns, Junipers, Driftwood) plus one `--kind system` account invite.
// node scripts/demo-journal-activity.mjs <base> <lanterns code> <junipers code> <driftwood code> <account code>
import crypto from 'node:crypto';

const [base, lanternsCode, junipersCode, driftwoodCode, accountCode] = process.argv.slice(2);
const api = `${base}/api/v1`;
const LANTERNS = '01a0c450-6c00-7000-8000-000000010000';
const FOX = '01a0c450-6c00-7000-8000-000000150000';
const AMY = '01a0c450-6c00-7000-8000-000000160000';
const KIT = '01a0c450-6c00-7000-8000-000000190000';
const JUNIPER = '01a0c450-6c00-7000-8000-0000001a0000';
const REN = '01a0c450-6c00-7000-8000-0000001c0000';

function uuid7() {
  const b = crypto.randomBytes(16);
  const ms = BigInt(Date.now());
  for (let i = 0; i < 6; i++) b[i] = Number((ms >> BigInt(8 * (5 - i))) & 0xffn);
  b[6] = (b[6] & 0x0f) | 0x70; b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}
let counter = 0;
const hlc = () => `${Date.now().toString(16).padStart(12, '0')}-${(counter++).toString(16).padStart(4, '0')}-${'0000abcd'}`;

async function json(path, body, token) {
  const r = await fetch(`${api}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
  const text = await r.text();
  if (!r.ok) throw new Error(`${path} ${r.status} ${text}`);
  return text ? JSON.parse(text) : null;
}
async function enrol(code, account) {
  const { publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const spki = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  return json('/auth/redeem', { code, device: { name: 'demo script', platform: 'cli', public_key: spki }, ...(account ? { account } : {}) });
}

/** Push ops over the sync socket and wait for their acks. */
async function push(dev, ops) {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/api/v1/sync`);
  await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
  const results = new Promise((ok, fail) => {
    ws.onmessage = (m) => {
      const f = JSON.parse(m.data);
      if (f.t === 'welcome') ws.send(JSON.stringify({ t: 'push', batch: 'b1', ops }));
      if (f.t === 'ack') ok(f.results);
      if (f.t === 'error') fail(new Error(JSON.stringify(f)));
    };
  });
  ws.send(JSON.stringify({ t: 'hello', device_id: dev.device_id, token: dev.session, cursors: {}, clock: { wall: Date.now() }, core: '1.0.0', app: 'demo' }));
  const r = await results;
  ws.close();
  for (const x of r) if (x.error) console.error('refused', x.error);
  return r;
}
const op = (dev, kind, entity, payload) => ({ id: uuid7(), kind, v: 1, scope: `account:${dev.account_id}`, entity_id: entity,
  hlc: hlc(), device_at: Date.now(), tz_offset_min: 0, time_source: 'auto', seen_seq: 0, payload });
const post = (authors, text, extra = {}) => ({ kind: 'note', authors, title: null, text, entities: [], mood: null, tags: [], cw: null,
  visibility: { mode: 'server' }, ...extra });

const lanterns = await enrol(lanternsCode);
const junipers = await enrol(junipersCode);
const driftwood = await enrol(driftwoodCode);
const quiet = uuid7();
const walk = uuid7();
await push(lanterns, [
  op(lanterns, 'post.create', quiet, post([FOX], 'A quiet afternoon. Tea, the window open, nobody needing anything.')),
  op(lanterns, 'post.create', walk, post([AMY], 'Walked the long way home past the river. The herons are back.', { visibility: { mode: 'followers' } })),
]);
const kitPost = uuid7();
await push(junipers, [
  op(junipers, 'post.create', uuid7(), post([KIT], 'Missing the fast train on purpose is the best kind of late.', { reply_to: quiet })),
  op(junipers, 'post.react', quiet, { target_type: 'post', target_id: quiet, emoji: '💜', member_id: KIT }),
  op(junipers, 'post.react', quiet, { target_type: 'post', target_id: quiet, emoji: '💜', member_id: JUNIPER }),
  op(junipers, 'post.create', uuid7(), post([JUNIPER], 'This is exactly the energy for today.', { quote: { post_id: walk }, visibility: { mode: 'followers' } })),
  op(junipers, 'post.create', kitPost, post([KIT], 'Tiny painting from this morning. Blue on blue on blue.')),
]);
await push(driftwood, [
  op(driftwood, 'post.create', uuid7(), post([REN], 'Can we go together next time? I want to see the herons.', { reply_to: walk, alerts: 'delayed' })),
  op(driftwood, 'post.create', uuid7(), post([REN], 'Replying quietly: the window-open kind of day is the best kind.', { reply_to: quiet, alerts: 'none' })),
]);
// a new system asks to follow The Lanterns
const moss = await enrol(accountCode, { kind: 'system', display_name: 'Mossbank', handle: 'mossbank' });
await json('/follows', { target: LANTERNS }, moss.session);
console.log('done', { lanterns: lanterns.account_id, moss: moss.account_id });
