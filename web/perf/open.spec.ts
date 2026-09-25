// How long a device with a big replica takes to open (R18, D-070: 100k ops in ≤ 2 s). Seeds a
// realistic mix of confirmed ops into IndexedDB, reloads, and reads the marks client.ts and
// main.ts set: chorus:loaded (the head and snapshot read), chorus:projected (the projection
// handed to the UI), chorus:mounted (the app mounted), chorus:ops-read and chorus:restored (the
// replica ready to sync, in the background). Numbers go to stdout; CLIENTS.md §4.3 records them.
import { expect, test } from '@playwright/test';

const ACCT = '0192f8c2-0000-7000-8000-0000000000a1';
const SPACE = '0192f8c2-0000-7000-8000-0000000000d1';
const T0 = 1_790_000_000_000;

function uuid7(ms: number, n: number, tag: number): string {
  const t = ms.toString(16).padStart(12, '0');
  const r = n.toString(16).padStart(12, '0');
  return `${t.slice(0, 8)}-${t.slice(8)}-7${tag.toString(16).padStart(3, '0')}-8${r.slice(0, 3)}-${r.slice(0, 12).padStart(12, '0')}`;
}

/** A plausible device history of `n` ops, all confirmed, one a second (or spread evenly
 * over `spanMs`). */
function history(n: number, spanMs?: number): unknown[] {
  const acct = `account:${ACCT}`;
  const space = `space:${SPACE}`;
  const ops: unknown[] = [];
  let seq = 0;
  const op = (kind: string, scope: string, entity: string, payload: unknown) => {
    seq += 1;
    const at = T0 + (spanMs ? Math.floor((seq * spanMs) / n) : seq * 1000);
    ops.push({
      id: uuid7(at, seq, 1), kind, v: 1, scope, entity_id: entity,
      hlc: `${at.toString(16).padStart(12, '0')}-0000-00000001`, device_at: at, tz_offset_min: 0, mono: null,
      boot_id: null, time_source: 'auto', seen_seq: 0, member_id: null, payload,
      seq, account_id: ACCT, device_id: 'dev', occurred_at: at, received_at: at,
    });
  };
  const members = Array.from({ length: 300 }, (_, i) => uuid7(T0, i, 2));
  members.forEach((m, i) => op('member.create', acct, m, { name: `Member ${i}`, color: '#C0694E' }));
  op('space.create', space, SPACE, { kind: 'internal', name: 'Home' });
  const channels = Array.from({ length: 12 }, (_, i) => uuid7(T0, i, 3));
  channels.forEach((c, i) => op('channel.create', space, c, { space_id: SPACE, kind: 'text', name: `channel-${i}` }));
  const messages: string[] = [];
  for (let i = 0; ops.length < n; i++) {
    const m = members[(i * 13) % members.length];
    const roll = i % 20;
    if (roll < 2) {
      op('front.switch', acct, uuid7(T0 + i, i, 4), { entries: [{ subject_type: 'member', subject_id: m, level: 'front', is_primary: true }] });
    } else if (roll < 3) {
      op('post.create', acct, uuid7(T0 + i, i, 5), { kind: 'note', authors: [m], text: `post ${i} about the garden`, entities: [], tags: ['garden'], visibility: { mode: 'private' } });
    } else if (roll < 4 && messages.length) {
      op('reaction.add', space, uuid7(T0 + i, i, 6), { target_type: 'message', target_id: messages[i % messages.length], emoji: '💜' });
    } else {
      const id = uuid7(T0 + i, i, 7);
      messages.push(id);
      op('message.send', space, id, { channel_id: channels[i % channels.length], authors: [m], text: `message number ${i} with a little bit of text in it`, entities: [] });
    }
  }
  return ops;
}

for (const n of [10_000, 100_000]) {
  test(`open with ${n} ops`, async ({ page }) => {
    page.on('pageerror', (e) => console.log('pageerror:', e.message.slice(0, 300)));
    // the longest main-thread task while opening: the background slices must stay short
    await page.addInitScript(() => {
      (window as unknown as { longest: number }).longest = 0;
      new PerformanceObserver((l) => {
        for (const e of l.getEntries()) {
          const w = window as unknown as { longest: number };
          const mounted = performance.getEntriesByName('chorus:mounted')[0]?.startTime;
          if (mounted !== undefined && e.startTime >= mounted) w.longest = Math.max(w.longest, e.duration);
        }
      }).observe({ type: 'longtask', buffered: false });
    });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    const ops = history(n);
    await page.evaluate(async ({ ops, ACCT, SPACE }) => {
      const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
      const db: IDBDatabase = await new Promise((ok, fail) => {
        const r = indexedDB.open('chorus', 2);
        r.onupgradeneeded = () => {
          for (const name of ['ops', 'kv', 'blob_uploads']) {
            if (r.result.objectStoreNames.contains(name)) continue;
            if (name === 'kv') r.result.createObjectStore(name);
            else r.result.createObjectStore(name, { keyPath: name === 'ops' ? 'id' : 'hash' });
          }
        };
        r.onsuccess = () => ok(r.result);
        r.onerror = () => fail(r.error);
      });
      const tx = db.transaction(['ops', 'kv'], 'readwrite');
      const kv = tx.objectStore('kv');
      kv.put({ device_id: 'dev', short_id: '0000beef', account_id: ACCT, is_admin: false, session: 'x',
        expires_at: Date.now() + 86_400_000, keys }, 'device');
      kv.put({ epoch: '1', cursors: { [`account:${ACCT}`]: ops.length, [`space:${SPACE}`]: ops.length },
        scopes: [`account:${ACCT}`, `space:${SPACE}`], local_order: [], rejected: {}, restoring: [] }, 'meta');
      kv.put('0000000000ff-0000-0000beef', 'hlc');
      const store = tx.objectStore('ops');
      for (const o of ops) store.put(o);
      await new Promise((ok, fail) => { tx.oncomplete = ok; tx.onerror = () => fail(tx.error); });
      db.close();
    }, { ops, ACCT, SPACE });
    const marks = async () => page.evaluate(() => Object.fromEntries(
      ['chorus:start', 'chorus:loaded', 'chorus:projected', 'chorus:mounted', 'chorus:ops-read', 'chorus:restored']
        .map((m) => [m, performance.getEntriesByName(m)[0]?.startTime ?? 0])));
    const ready = () => page.waitForFunction(() => performance.getEntriesByName('chorus:restored').length > 0
      && performance.getEntriesByName('chorus:projected').length > 0, null, { timeout: 120_000 });
    // cold: no snapshot yet, the app waits for every op (as before R18)
    page.on('console', (m) => { if (m.type() === 'error' && !m.text().includes('WebSocket')) console.log('console:', m.text().slice(0, 400)); });
    await page.reload();
    await ready();
    const cold = await marks();
    console.log(`${n} ops, no snapshot: open ${Math.max(cold['chorus:projected'], cold['chorus:restored']).toFixed(0)} ms`);
    // the snapshot is written 10 s after the last change, or when the page is hidden
    await expect.poll(() => page.evaluate(() => new Promise<boolean>((ok) => {
      const r = indexedDB.open('chorus');
      r.onsuccess = () => {
        const get = r.result.transaction('kv').objectStore('kv').get('snapshot');
        get.onsuccess = () => { ok(!!get.result); r.result.close(); };
      };
    })), { timeout: 60_000, intervals: [1000] }).toBe(true);
    for (let run = 0; run < 3; run++) {
      await page.reload();
      await ready();
      const m = await marks();
      const longest = await page.evaluate(() => (window as unknown as { longest: number }).longest);
      console.log(`${n} ops, from the snapshot, run ${run + 1}: mounted ${m['chorus:mounted'].toFixed(0)} ms `
        + `(snapshot read ${(m['chorus:loaded'] - m['chorus:start']).toFixed(0)}), ops read ${m['chorus:ops-read'].toFixed(0)} ms, `
        + `ready to sync ${m['chorus:restored'].toFixed(0)} ms; longest task after mounting ${longest.toFixed(0)} ms`);
      expect(m['chorus:mounted']).toBeLessThan(2000);
      expect(m['chorus:projected']).toBeLessThan(m['chorus:restored']);
    }
  });
}

// R23 (SYNC §6.5): a year of history, as a full replica holds it and as a browser tab's 90-day
// window holds it (the ops `sync::outside_window` keeps: message-family ops received in the last
// 90 days, and everything else). IndexedDB size and cold open time, before and after.
const YEAR = 365 * 86_400_000;
const WINDOW = 90 * 86_400_000;
const windowed = (ops: unknown[]) => {
  const end = T0 + YEAR;
  return ops.filter((o) => {
    const { kind, received_at } = o as { kind: string; received_at: number };
    const family = /^(message|reaction|attachment|read)\./.test(kind);
    return !family || received_at >= end - WINDOW;
  });
};
for (const [label, pick] of [['full replica', (ops: unknown[]) => ops], ['windowed tab', windowed]] as const) {
  test(`a year of 100000 ops: ${label}`, async ({ page }) => {
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    const ops = pick(history(100_000, YEAR));
    const before = await page.evaluate(async () => (await navigator.storage.estimate()).usage ?? 0);
    await page.evaluate(async ({ ops, ACCT, SPACE }) => {
      const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
      const db: IDBDatabase = await new Promise((ok, fail) => {
        const r = indexedDB.open('chorus', 2);
        r.onupgradeneeded = () => {
          for (const name of ['ops', 'kv', 'blob_uploads']) {
            if (r.result.objectStoreNames.contains(name)) continue;
            if (name === 'kv') r.result.createObjectStore(name);
            else r.result.createObjectStore(name, { keyPath: name === 'ops' ? 'id' : 'hash' });
          }
        };
        r.onsuccess = () => ok(r.result);
        r.onerror = () => fail(r.error);
      });
      const tx = db.transaction(['ops', 'kv'], 'readwrite');
      const kv = tx.objectStore('kv');
      kv.put({ device_id: 'dev', short_id: '0000beef', account_id: ACCT, is_admin: false, session: 'x',
        expires_at: Date.now() + 86_400_000, keys }, 'device');
      kv.put({ epoch: '1', cursors: { [`account:${ACCT}`]: 100_000, [`space:${SPACE}`]: 100_000 },
        scopes: [`account:${ACCT}`, `space:${SPACE}`], local_order: [], rejected: {}, restoring: [] }, 'meta');
      kv.put('0000000000ff-0000-0000beef', 'hlc');
      const store = tx.objectStore('ops');
      for (const o of ops) store.put(o);
      await new Promise((ok, fail) => { tx.oncomplete = ok; tx.onerror = () => fail(tx.error); });
      db.close();
    }, { ops, ACCT, SPACE });
    const size = (await page.evaluate(async () => (await navigator.storage.estimate()).usage ?? 0)) - before;
    await page.reload();
    await page.waitForFunction(() => performance.getEntriesByName('chorus:restored').length > 0
      && performance.getEntriesByName('chorus:projected').length > 0, null, { timeout: 120_000 });
    const open = await page.evaluate(() => Math.max(
      performance.getEntriesByName('chorus:projected')[0].startTime,
      performance.getEntriesByName('chorus:restored')[0].startTime));
    console.log(`a year of 100000 ops, ${label}: ${ops.length} ops kept, IndexedDB ~${(size / 1e6).toFixed(1)} MB, `
      + `cold open ${open.toFixed(0)} ms`);
  });
}

// R24 (SPEC §9): opening a channel of 50 000 messages in a running app, from the snapshot:
// the first screen (the newest page) is up within 150 ms.
test('open a channel of 50000 messages', async ({ page }) => {
  await page.goto('/');
  await page.waitForLoadState('networkidle');
  const acct = `account:${ACCT}`;
  const space = `space:${SPACE}`;
  const channel = uuid7(T0, 1, 3);
  const other = uuid7(T0, 2, 3);
  const ops: unknown[] = [];
  let seq = 0;
  const op = (kind: string, scope: string, entity: string, payload: unknown) => {
    seq += 1;
    const at = T0 + seq * 1000;
    ops.push({
      id: uuid7(at, seq, 1), kind, v: 1, scope, entity_id: entity,
      hlc: `${at.toString(16).padStart(12, '0')}-0000-00000001`, device_at: at, tz_offset_min: 0, mono: null,
      boot_id: null, time_source: 'auto', seen_seq: 0, member_id: null, payload,
      seq, account_id: ACCT, device_id: 'dev', occurred_at: at, received_at: at,
    });
  };
  const members = Array.from({ length: 20 }, (_, i) => uuid7(T0, i, 2));
  members.forEach((m, i) => op('member.create', acct, m, { name: `Member ${i}` }));
  op('front.switch', acct, uuid7(T0, 99, 4), { entries: [{ subject_type: 'member', subject_id: members[0], level: 'front', is_primary: true }] });
  op('space.create', space, SPACE, { kind: 'internal', name: 'Home' });
  op('channel.create', space, other, { space_id: SPACE, kind: 'text', name: 'quiet' });
  op('channel.create', space, channel, { space_id: SPACE, kind: 'text', name: 'busy' });
  for (let i = 0; i < 50_000; i++) {
    op('message.send', space, uuid7(T0 + i, i, 7), { channel_id: channel, authors: [members[i % 20]], text: `message ${i} in the busy channel`, entities: [] });
  }
  await page.evaluate(async ({ ops, ACCT, SPACE }) => {
    const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
    const db: IDBDatabase = await new Promise((ok, fail) => {
      const r = indexedDB.open('chorus', 2);
      r.onupgradeneeded = () => {
        for (const name of ['ops', 'kv', 'blob_uploads']) {
          if (r.result.objectStoreNames.contains(name)) continue;
          if (name === 'kv') r.result.createObjectStore(name);
          else r.result.createObjectStore(name, { keyPath: name === 'ops' ? 'id' : 'hash' });
        }
      };
      r.onsuccess = () => ok(r.result);
      r.onerror = () => fail(r.error);
    });
    const tx = db.transaction(['ops', 'kv'], 'readwrite');
    const kv = tx.objectStore('kv');
    kv.put({ device_id: 'dev', short_id: '0000beef', account_id: ACCT, is_admin: false, session: 'x',
      expires_at: Date.now() + 86_400_000, keys }, 'device');
    kv.put({ epoch: '1', cursors: { [`account:${ACCT}`]: ops.length, [`space:${SPACE}`]: ops.length },
      scopes: [`account:${ACCT}`, `space:${SPACE}`], local_order: [], rejected: {}, restoring: [] }, 'meta');
    kv.put('0000000000ff-0000-0000beef', 'hlc');
    const store = tx.objectStore('ops');
    for (const o of ops) store.put(o);
    await new Promise((ok, fail) => { tx.oncomplete = ok; tx.onerror = () => fail(tx.error); });
    db.close();
  }, { ops, ACCT, SPACE });
  const ready = () => page.waitForFunction(() => performance.getEntriesByName('chorus:restored').length > 0, null, { timeout: 120_000 });
  await page.reload();
  await ready();
  // from the snapshot, like every open after the first
  await expect.poll(() => page.evaluate(() => new Promise<boolean>((ok) => {
    const r = indexedDB.open('chorus');
    r.onsuccess = () => {
      const get = r.result.transaction('kv').objectStore('kv').get('snapshot');
      get.onsuccess = () => { ok(!!get.result); r.result.close(); };
    };
  })), { timeout: 60_000, intervals: [1000] }).toBe(true);
  const runs: number[] = [];
  for (let run = 0; run < 3; run++) {
    await page.goto(`/#/chat/${other}`);
    await page.reload();
    await ready();
    await page.waitForTimeout(500);
    const took = await page.evaluate(async (channel) => {
      const t0 = performance.now();
      location.hash = `#/chat/${channel}`;
      const name = `chorus:channel-shown:${channel}`;
      while (!performance.getEntriesByName(name).length) await new Promise((ok) => setTimeout(ok, 1));
      return performance.getEntriesByName(name)[0].startTime - t0;
    }, channel);
    console.log(`open a channel of 50000 messages, run ${run + 1}: first screen in ${took.toFixed(0)} ms`);
    runs.push(took);
  }
  expect(runs.sort((a, b) => a - b)[1]).toBeLessThanOrEqual(150);

  // and a send into it is on the page within 50 ms (SPEC §9)
  const box = page.getByPlaceholder('Message #busy');
  const sends: number[] = [];
  for (let run = 0; run < 3; run++) {
    const text = `hello number ${run}`;
    await box.fill(text);
    await expect(page.locator('button.send')).toBeEnabled();
    const [shown, painted] = await page.evaluate(async (text) => {
      // on the page: the node with the text is in the DOM; painted: the frame after that
      const done = new Promise<[number, number]>((ok) => {
        const seen = new MutationObserver((records) => {
          const hit = records.some((r) => [...r.addedNodes].some((n) => n.textContent?.includes(text)));
          if (!hit) return;
          seen.disconnect();
          const at = performance.now();
          requestAnimationFrame(() => setTimeout(() => ok([at, performance.now()])));
        });
        seen.observe(document.body, { childList: true, subtree: true });
      });
      const t0 = performance.now();
      (document.querySelector('button.send') as HTMLButtonElement).click();
      const [at, frame] = await done;
      return [at - t0, frame - t0];
    }, text);
    console.log(`send into a channel of 50000 messages, run ${run + 1}: on the page in ${shown.toFixed(0)} ms, painted by ${painted.toFixed(0)} ms`);
    sends.push(shown);
  }
  expect(sends.sort((a, b) => a - b)[1]).toBeLessThanOrEqual(50);
});
