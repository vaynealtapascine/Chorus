import { describe, expect, it } from 'vitest';
import { bucketAssignments, buckets, customEmojis, fuzzy, groupPath, membership, messages, segmentParsing, threadSummaries, type GroupRow } from './data';
import type { Projection } from './sync/client';

describe('data helpers', () => {
  it('reads live buckets and only present follower assignments', () => {
    const p = { rows: { bucket: {
      close: { exists: true, fields: { name: 'Close', ceiling: { delay: { min_s: 0, max_s: 0 } } } },
      retired: { exists: true, fields: { name: 'Old', deleted_at: 1 } },
    } }, sets: { bucket_assignment: {
      'close|{"follower_account_id":"friend"}': true,
      'close|{"follower_account_id":"gone"}': false,
    } }, fronts: {}, opaque: 0 } as unknown as Projection;
    expect(buckets(p).map((b) => b.name)).toEqual(['Close']);
    expect([...bucketAssignments(p).get('close')!]).toEqual(['friend']);
  });
  it('keeps retired emoji addressable while excluding them from the active picker', () => {
    const p = { rows: { custom_emoji: {
      live: { exists: true, fields: { name: 'wave', aliases: ['hi'], blob_hash: 'one' } },
      retired: { exists: true, fields: { name: 'old', blob_hash: 'two', deleted_at: 1 } },
    } }, sets: {}, fronts: {}, opaque: 0 } as unknown as Projection;
    expect(customEmojis(p).map((e) => [e.name, e.deleted])).toEqual([['old', true], ['wave', false]]);
  });
  it('fuzzy prefers direct substrings', () => {
    expect(fuzzy('ka', 'Kai')).toBe(0);
    expect(fuzzy('ki', 'Kai')).toBeGreaterThanOrEqual(100);
    expect(fuzzy('zz', 'Kai')).toBeNull();
    expect(fuzzy('', 'anything')).toBe(0);
  });

  it('reads group membership from element-set keys', () => {
    const p = {
      rows: {},
      fronts: {},
      opaque: 0,
      sets: { group_membership: { 'g1|{"member_id":"m1"}': true, 'g1|{"member_id":"m2"}': false } },
    } as unknown as Projection;
    expect([...membership(p).get('g1')!]).toEqual(['m1']);
  });

  it('resolves message attachments from the replica, preserving alt text and spoilers', () => {
    const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
    const p = {
      rows: {
        message: { m: row({ channel_id: 'c', authors: ['kai'], text: '', attachments: ['a', 'missing'] }) },
        attachment: { a: row({ blob_hash: 'abcd', thumb_blob_hash: 'efgh', mime: 'image/png', filename: 'pic.png', alt_text: 'A cat', is_spoiler: true }) },
      }, fronts: {}, sets: {}, opaque: 0,
    } as unknown as Projection;
    expect(messages(p, 'c')[0].attachments).toEqual([{
      id: 'a', blob_hash: 'abcd', thumb_blob_hash: 'efgh', mime: 'image/png', filename: 'pic.png',
      size: 0, alt_text: 'A cat', is_spoiler: true,
    }]);
  });

  it('builds subsystem paths and survives cycles', () => {
    const gs: GroupRow[] = [
      { id: 'a', name: 'Stars', kind: 'subsystem', deleted: false },
      { id: 'b', name: 'Inner', kind: 'subsystem', parent_id: 'a', deleted: false },
      { id: 'c', name: 'Loop', kind: 'subsystem', parent_id: 'd', deleted: false },
      { id: 'd', name: 'Pool', kind: 'subsystem', parent_id: 'c', deleted: false },
    ];
    expect(groupPath(gs[1], gs)).toBe('Stars / Inner');
    expect(groupPath(gs[2], gs)).toBe('Pool / Loop');
  });

  it('summarizes visible thread replies and recent repliers', () => {
    const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
    const p = {
      rows: {
        channel: {
          source: row({ kind: 'text', space_id: 'space', name: 'general' }),
          thread: row({ kind: 'thread', space_id: 'space', parent_message_id: 'parent', name: 'Thread' }),
          archived: row({ kind: 'thread', space_id: 'space', parent_message_id: 'other', archived_at: 1 }),
          wrongSpace: row({ kind: 'thread', space_id: 'other-space', parent_message_id: 'parent', name: 'Wrong space' }),
        },
        message: {
          parent: row({ channel_id: 'source', authors: ['kai'], occurred_at: 1 }),
          first: row({ channel_id: 'thread', authors: ['kai'], occurred_at: 10 }),
          second: row({ channel_id: 'thread', authors: ['juniper', 'kai'], occurred_at: 20 }),
          removed: row({ channel_id: 'thread', authors: ['hidden'], occurred_at: 30, deleted_at: 31 }),
        },
      },
      fronts: {}, sets: {}, opaque: 0,
    } as unknown as Projection;
    const summary = threadSummaries(p).get('parent');
    expect(summary?.channel.id).toBe('thread');
    expect(summary?.replyCount).toBe(2);
    expect(summary?.lastRepliers).toEqual(['juniper', 'kai']);
    expect(threadSummaries(p).has('other')).toBe(false);
    expect(summary?.channel.id).not.toBe('wrongSpace');
  });

  it('handles a channel with 50k replies without retaining every reply for previews', () => {
    const message = Object.fromEntries(Array.from({ length: 50_000 }, (_, i) => [
      `m${i}`, { exists: true, fields: { channel_id: 'thread', occurred_at: i, authors: [`author${i % 5}`] } },
    ]));
    const p = {
      rows: {
        channel: {
          source: { exists: true, fields: { kind: 'text', space_id: 'space' } },
          thread: { exists: true, fields: { kind: 'thread', space_id: 'space', parent_message_id: 'parent' } },
        },
        message: { parent: { exists: true, fields: { channel_id: 'source' } }, ...message },
      },
      fronts: {}, sets: {}, opaque: 0,
    } as unknown as Projection;
    const summary = threadSummaries(p).get('parent');
    expect(summary?.replyCount).toBe(50_000);
    expect(summary?.lastRepliers).toEqual(['author4', 'author3', 'author2']);
  });

  it('uses pending local segment preference until the server confirms it', () => {
    const p = { rows: { pref: {
      'account||chat.segment_parsing': { exists: true, fields: { value: true } },
      '||chat.segment_parsing': { exists: true, fields: { value: false } },
    } } } as unknown as Projection;
    expect(segmentParsing(p, 'account')).toBe(false);
    delete p.rows.pref['||chat.segment_parsing'];
    expect(segmentParsing(p, 'account')).toBe(true);
  });
});
