import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore, type SearchCandidate, type SearchQuery } from './core';
import { JournalIndex, SearchIndex, parseSearch } from './search';
import { applyDelta, type Delta } from './sync/delta';
import type { Projection } from './sync/client';

const base = (): Projection => ({
  rows: {
    member: { kai: { exists: true, fields: { name: 'Kai' } } },
    channel: { general: { exists: true, fields: { name: 'general' } } },
    message: {
      one: { exists: true, fields: { channel_id: 'general', authors: ['kai'], text: 'violet garden', occurred_at: 100, attachments: ['photo'] } },
      two: { exists: true, fields: { channel_id: 'general', authors: ['kai'], text: 'blue room', occurred_at: 200 } },
    },
    attachment: { photo: { exists: true, fields: { mime: 'image/png' } } },
  },
  sets: {}, fronts: {}, opaque: 0,
});
beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

const find = (index: SearchIndex, q: string, limit?: number) => index.find(core.searchParse(q), Date.now(), limit);
const empty = (): Delta => ({ rows: {}, sets: {}, fronts: {}, reviews: {}, opaque: 0, full: false });

describe('local message search', () => {
  it('parses filters and quoted names in core, without treating them as search words', () => {
    expect(core.searchParse('garden from:"Kai Rose" in:#general has:image before:2026-01-02 is:pinned'))
      .toEqual({ words: ['garden'], from: ['kai rose'], in: ['general'], has: ['image'],
        before: { type: 'date', date: '2026-01-02' }, after: null, pinned: true });
    expect(() => core.searchParse('has:gif')).toThrow(/has is one of/);
  });

  it('updates only delta keys, including edits, removals and late attachment metadata', () => {
    let p = base();
    const index = new SearchIndex(p);
    expect(find(index, 'vio', 10).map((h) => h.id)).toEqual(['one']);
    expect(find(index, 'violet from:Kai in:general has:image').map((h) => h.id)).toEqual(['one']);
    const changed = { ...p.rows.message.one, fields: { ...p.rows.message.one.fields, text: 'amber garden' } };
    let d: Delta = { ...empty(), rows: { message: { one: changed } } };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(find(index, 'violet')).toEqual([]);
    expect(find(index, 'amb').map((h) => h.id)).toEqual(['one']);
    d = { ...empty(), rows: { attachment: { photo: { exists: true, fields: { mime: 'application/pdf' } } } } };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(find(index, 'amber has:image')).toEqual([]);
    expect(find(index, 'amber has:file').map((h) => h.id)).toEqual(['one']);
    d = { ...empty(), rows: { message: { one: null } } };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(find(index, 'amber')).toEqual([]);
  });

  it('searches a 500 member index within the input budget after indexing', () => {
    const p = base();
    for (let i = 0; i < 500; i++) {
      p.rows.member[`m${i}`] = { exists: true, fields: { name: `Member ${i}` } };
      p.rows.message[`msg${i}`] = { exists: true, fields: { channel_id: 'general', authors: [`m${i}`], text: `garden word${i}`, occurred_at: i } };
    }
    const index = new SearchIndex(p);
    const query = core.searchParse('garden from:"Member 499"');
    for (let i = 0; i < 5; i++) index.find(query);
    const start = performance.now();
    for (let i = 0; i < 20; i++) expect(index.find(query).length).toBe(1);
    expect((performance.now() - start) / 20).toBeLessThan(16);
  });
});

// The conformance fixtures (fixtures/search, SYNC §9.1): the index narrows by words before core
// decides, so it must never drop a message core would find.
describe('local message search agrees with core (fixtures/search)', () => {
  const dir = fileURLToPath(new URL('../../../fixtures/search/', import.meta.url));
  for (const file of readdirSync(dir).filter((f) => f.startsWith('filter_'))) {
    it(file, () => {
      const fx = JSON.parse(readFileSync(dir + file, 'utf8')) as {
        args: [SearchQuery, SearchCandidate[], { now: number; tz_offset_min: number }]; expect: number[];
      };
      const [query, candidates, ctx] = fx.args;
      const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
      const p: Projection = { rows: { member: {}, channel: {}, message: {}, attachment: {} }, sets: {}, fronts: {}, opaque: 0 };
      candidates.forEach((c, i) => {
        for (const [id, name] of c.authors) p.rows.member[id] = row({ name });
        p.rows.channel[c.channel[0]] = row({ name: c.channel[1] });
        const attachments = c.mimes.map((mime, j) => { p.rows.attachment[`a${i}-${j}`] = row({ mime }); return `a${i}-${j}`; });
        p.rows.message[`m${i}`] = row({ channel_id: c.channel[0], authors: c.authors.map(([id]) => id), text: c.text,
          cw: c.cw, occurred_at: c.at, attachments, pinned_at: c.pinned ? c.at : null,
          entities: c.link ? [{ type: 'url', offset: 0, length: 1 }] : [] });
      });
      const found = new SearchIndex(p).find(query, ctx.now, 100, ctx.tz_offset_min).map((h) => Number(h.id.slice(1)));
      expect(found.sort()).toEqual(fx.expect);
    });
  }
});

describe('offline post and switch search', () => {
  const journal = (): Projection => ({
    rows: {
      member: { kai: { exists: true, fields: { name: 'Kai' } }, rin: { exists: true, fields: { name: 'Rin', display_name: 'Rin Vale' } } },
      post: {
        p1: { exists: true, fields: { kind: 'note', title: 'Tomatoes', text: 'the garden is ripe', tags: ['garden'], authors: ['kai'], occurred_at: 100 } },
        p2: { exists: true, fields: { kind: 'entry', text: 'rainy day inside', tags: [], authors: ['rin'], occurred_at: 200 } },
        gone: { exists: true, fields: { text: 'garden deleted', authors: ['kai'], occurred_at: 300, deleted_at: 301 } },
      },
    },
    sets: {},
    fronts: {
      me: {
        current: [], intervals: [],
        switches: [
          { id: 's1', kind: 'switch', occurred_at: 1000, device_id: 'd', entries: [{ subject_type: 'member', subject_id: 'kai', level: 'front', is_primary: true }], resulting_front: [], note: 'after the storm', retracted: false, amended: false },
          { id: 's2', kind: 'switch', occurred_at: 2000, device_id: 'd', entries: [{ subject_type: 'member', subject_id: 'rin', level: 'front', is_primary: true }], resulting_front: [], note: null, retracted: false, amended: false },
          { id: 's3', kind: 'switch', occurred_at: 3000, device_id: 'd', entries: [{ subject_type: 'member', subject_id: 'rin', level: 'front', is_primary: true }], resulting_front: [], note: null, retracted: true, amended: false },
        ],
      },
    },
    opaque: 0,
  } as unknown as Projection);

  it('finds posts by title, text and tags, not deleted ones, and narrows by author and date', () => {
    const index = new JournalIndex(journal(), 'me');
    expect(index.searchPosts(parseSearch('garden')).map((h) => h.id)).toEqual(['p1']);
    expect(index.searchPosts(parseSearch('tomat')).map((h) => h.id)).toEqual(['p1']);
    expect(index.searchPosts(parseSearch('from:Rin')).map((h) => h.id)).toEqual(['p2']);
    expect(index.searchPosts(parseSearch('after:150')).map((h) => h.id)).toEqual(['p2']);
  });

  it('finds switches by who was in them and their note, and follows renames and new posts', () => {
    let p = journal();
    const index = new JournalIndex(p, 'me');
    expect(index.searchSwitches(parseSearch('rin')).map((h) => h.id)).toEqual(['s2']);
    expect(index.searchSwitches(parseSearch('storm')).map((h) => h.id)).toEqual(['s1']);
    expect(index.searchSwitches(parseSearch('before:1500')).map((h) => h.id)).toEqual(['s1']);
    const d: Delta = {
      ...empty(),
      rows: {
        member: { kai: { exists: true, fields: { name: 'Kai', display_name: 'Kestrel' } } },
        post: { p3: { exists: true, fields: { text: 'kestrel sighting', authors: ['kai'], occurred_at: 400 } } },
      },
    };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(index.searchSwitches(parseSearch('kestrel')).map((h) => h.id)).toEqual(['s1']);
    expect(index.searchPosts(parseSearch('sighting')).map((h) => h.id)).toEqual(['p3']);
  });

  it('searches 5 000 posts and 10 000 switches within the input budget', () => {
    const p = journal();
    const switches = p.fronts.me.switches as unknown as { id: string }[];
    for (let i = 0; i < 5000; i++) p.rows.post[`x${i}`] = { exists: true, fields: { text: `note number ${i} about things`, tags: ['daily'], authors: ['kai'], occurred_at: i } };
    for (let i = 0; i < 10000; i++) switches.push({ ...(switches[0] as object), id: `w${i}`, occurred_at: 5000 + i } as { id: string });
    const index = new JournalIndex(p, 'me');
    const q = parseSearch('things daily');
    index.searchPosts(q); index.searchSwitches(parseSearch('kai'));
    const start = performance.now();
    for (let i = 0; i < 10; i++) { index.searchPosts(q); index.searchSwitches(parseSearch('kai storm')); }
    expect((performance.now() - start) / 10).toBeLessThan(16);
  });
});
