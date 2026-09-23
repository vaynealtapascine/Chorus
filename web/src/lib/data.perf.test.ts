// SPEC §9 on the data path of the chat view: open a channel with 50k messages, then send one.
// Timing only means something on a quiet machine: `PERF=1 npx vitest run src/lib/data.perf.test.ts`.
import { describe, expect, it } from 'vitest';
import { messageById, messages, unread } from './data';
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
    let t = performance.now();
    const all = messages(p, 'big');
    const open = ms(t);
    expect(all).toHaveLength(N);
    // the view renders the newest page; each reply looks up its parent
    t = performance.now();
    for (const m of all.slice(-100)) if (m.reply_to) messageById(p, m.reply_to);
    unread(p, 'big', 'b');
    const page = ms(t);

    // send: a delta with one new row, then what the view reads again
    t = performance.now();
    p = applyDelta(p, {
      rows: { message: { mnew: { exists: true, fields: { channel_id: 'big', authors: ['kai'], text: 'hi', entities: [], occurred_at: 1_800_000_000_000 } } } },
      sets: {}, fronts: {}, reviews: {}, opaque: 1, full: false,
    });
    const after = messages(p, 'big');
    const send = ms(t);
    expect(after.at(-1)?.id).toBe('mnew');
    console.log(`open ${open.toFixed(1)} ms · page lookups ${page.toFixed(1)} ms · send ${send.toFixed(1)} ms`);
    expect(open + page).toBeLessThan(150);
    expect(send).toBeLessThan(50);
  });
});
