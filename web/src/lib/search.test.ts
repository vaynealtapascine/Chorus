import { describe, expect, it } from 'vitest';
import { SearchIndex, parseSearch } from './search';
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
const empty = (): Delta => ({ rows: {}, sets: {}, fronts: {}, reviews: {}, opaque: 0, full: false });

describe('local message search', () => {
  it('parses filters and quoted names without treating them as search words', () => {
    expect(parseSearch('garden from:"Kai Rose" in:general has:image before:2026-01-02 after:100'))
      .toEqual({ terms: ['garden'], from: 'Kai Rose', in: 'general', has: 'image', before: Date.parse('2026-01-02'), after: 100 });
  });

  it('updates only delta keys, including edits, removals and late attachment metadata', () => {
    let p = base();
    const index = new SearchIndex(p);
    expect(index.search(parseSearch('vio'), 10).map((h) => h.id)).toEqual(['one']);
    expect(index.search(parseSearch('violet from:Kai in:general has:image')).map((h) => h.id)).toEqual(['one']);
    const changed = { ...p.rows.message.one, fields: { ...p.rows.message.one.fields, text: 'amber garden' } };
    let d: Delta = { ...empty(), rows: { message: { one: changed } } };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(index.search(parseSearch('violet'))).toEqual([]);
    expect(index.search(parseSearch('amb')).map((h) => h.id)).toEqual(['one']);
    d = { ...empty(), rows: { attachment: { photo: { exists: true, fields: { mime: 'application/pdf' } } } } };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(index.search(parseSearch('amber has:image'))).toEqual([]);
    expect(index.search(parseSearch('amber has:file')).map((h) => h.id)).toEqual(['one']);
    d = { ...empty(), rows: { message: { one: null } } };
    p = applyDelta(p, d);
    index.apply(p, d);
    expect(index.search(parseSearch('amber'))).toEqual([]);
  });

  it('searches a 500 member index within the input budget after indexing', () => {
    const p = base();
    for (let i = 0; i < 500; i++) {
      p.rows.member[`m${i}`] = { exists: true, fields: { name: `Member ${i}` } };
      p.rows.message[`msg${i}`] = { exists: true, fields: { channel_id: 'general', authors: [`m${i}`], text: `garden word${i}`, occurred_at: i } };
    }
    const index = new SearchIndex(p);
    const query = parseSearch('garden from:"Member 499"');
    for (let i = 0; i < 5; i++) index.search(query);
    const start = performance.now();
    for (let i = 0; i < 20; i++) expect(index.search(query).length).toBe(1);
    expect((performance.now() - start) / 20).toBeLessThan(16);
  });
});
