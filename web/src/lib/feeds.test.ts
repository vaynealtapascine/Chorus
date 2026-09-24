import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore } from './core';
import { feedCandidates, feedContext, feedDateStart, filterFeed, savedFeeds } from './feeds';
import type { Projection } from './sync/client';

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

const row = (fields: Record<string, unknown>) => ({ exists: true, fields });

describe('feed views', () => {
  it('uses core to filter local posts and messages by list, kind and channel', () => {
    const p = { rows: {
      member: { kai: row({ name: 'Kai' }), june: row({ name: 'June' }) },
      member_list: { close: row({ name: 'Close friends', visibility: { mode: 'private' } }) },
      feed: { saved: row({ name: 'Entries', query: 'kind:entry', query_ast: core.feedParse('kind:entry') }),
        gone: row({ name: 'Old', query: 'kind:note', query_ast: core.feedParse('kind:note'), deleted_at: 10 }) },
      post: { entry: row({ kind: 'entry', authors: ['kai'], title: 'Day', text: 'sunny', occurred_at: 20 }),
        note: row({ kind: 'note', authors: ['june'], text: 'windy', occurred_at: 21 }) },
      channel: { general: row({ space_id: 'space', kind: 'text', name: 'general' }) },
      message: { chat: row({ channel_id: 'general', authors: ['kai'], text: 'hello', occurred_at: 22 }) },
    }, sets: { member_list_item: { 'close|{"member_id":"kai"}': true } }, fronts: {}, opaque: 0 } as unknown as Projection;
    expect(savedFeeds(p).map((feed) => feed.name)).toEqual(['Entries']);
    const candidates = feedCandidates(p, 30);
    const ast = core.feedParse('from:list:"Close friends" (kind:entry or in:general)');
    expect(filterFeed(candidates, ast, feedContext(p, ast, 30)).map((row) => row.id)).toEqual(['chat', 'entry']);
  });

  it('resolves absolute dates at DST-aware local midnight', () => {
    expect(feedDateStart('2026-03-08', 'America/New_York')).toBe(Date.parse('2026-03-08T05:00:00Z'));
    expect(feedDateStart('2026-03-09', 'America/New_York')).toBe(Date.parse('2026-03-09T04:00:00Z'));
  });
});
