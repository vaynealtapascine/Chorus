// SPEC §9 on the data path of the chat view: open a channel with 50k messages, then send one.
// Timing only means something on a quiet machine: `PERF=1 npx vitest run src/lib/data.perf.test.ts`.
import { describe, expect, it } from 'vitest';
import { messageById, messagePage, messages, unread } from './data';
import { applyDelta } from './sync/delta';
import type { Projection } from './sync/client';

const N = 50_000;

function projection(): Projection {
  const message: Projection['rows'][string] = {};
  for (let i = 0; i < N + 1000; i++) {
    const id = `m${String(i).padStart(6, '0')}`;
    message[id] = {
      exists: true,
      fields: {
        channel_id: i < N ? 'big' : 'small', authors: ['kai'], text: `message ${i}`, entities: [],
        occurred_at: 1_790_000_000_000 + i * 1000, account_id: 'a', ...(i % 10 === 0 ? { reply_to: `m${String(i - 1).padStart(6, '0')}` } : {}),
      },
    };
  }
  return { rows: { message }, sets: {}, fronts: {}, opaque: 0 };
}

const ms = (t: number) => performance.now() - t;

describe.skipIf(!process.env.PERF)('chat data budgets', () => {
  it('opens a 50k channel and sends into it within budget', () => {
    let p = projection();
    // the per-channel index is built once, when the app starts (the channel list's unread counts)
    let t = performance.now();
    unread(p, 'small', 'b');
    const index = ms(t);
    // the view builds only the newest page (messagePage); each reply looks up its parent
    t = performance.now();
    const shown = messagePage(p, 'big', 100);
    const open = ms(t);
    expect(shown.total).toBe(N);
    expect(shown.rows).toHaveLength(100);
    t = performance.now();
    for (const m of shown.rows) if (m.reply_to) messageById(p, m.reply_to);
    const page = ms(t);
    // (the open channel has no badge; another one with all 50k unread walks them all)
    t = performance.now();
    unread(p, 'big', 'b');
    const badge = ms(t);

    // send: a delta with one new row, then what the view reads again
    t = performance.now();
    p = applyDelta(p, {
      rows: { message: { mnew: { exists: true, fields: { channel_id: 'big', authors: ['kai'], text: 'hi', entities: [], occurred_at: 1_800_000_000_000 } } } },
      sets: {}, fronts: {}, reviews: {}, opaque: 1, full: false,
    });
    const after = messagePage(p, 'big', 100);
    const send = ms(t);
    expect(after.rows.at(-1)?.id).toBe('mnew');
    t = performance.now();
    expect(messages(p, 'big').length).toBe(N + 1);
    const whole = ms(t);
    console.log(`index at start ${index.toFixed(1)} ms · open ${open.toFixed(1)} ms · page lookups ${page.toFixed(1)} ms · 50k-unread badge ${badge.toFixed(1)} ms · send ${send.toFixed(1)} ms · every row ${whole.toFixed(1)} ms`);
    expect(open + page).toBeLessThan(150);
    expect(send).toBeLessThan(50);
  });
});
