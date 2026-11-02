import { describe, expect, it } from 'vitest';
import { bookmarkPayload, repostBy, repostOriginal, repostPayload, savedList, savedPosts, shareText, socialIndex } from './journal-social';
import { entryMeta, postMediaPlan, shortAgo } from './journal-presentation';
import type { AttachmentRow } from './data';
import type { PostRow } from './posts';

const row = (id: string, extra: Partial<PostRow> = {}): PostRow => ({
  id, kind: 'note', authors: ['kai'], text: id, entities: [], occurred_at: 1, tags: [],
  visibility: { mode: 'private' }, deleted: false, ...extra,
});

describe('journal social index', () => {
  it('counts live replies and reposts, and finds the acting member’s repost', () => {
    const index = socialIndex([
      row('a'), row('r1', { reply_to: 'a' }), row('r2', { reply_to: 'a', deleted: true }),
      row('p1', { repost_of: 'a', authors: ['rin'], text: '' }), row('p2', { repost_of: 'a', deleted: true, text: '' }),
    ]);
    expect(index.replies.get('a')).toBe(1);
    expect(index.reposts.get('a')?.map((p) => p.id)).toEqual(['p1']);
    expect(repostBy(index, 'a', 'rin')?.id).toBe('p1');
    expect(repostBy(index, 'a', 'kai')).toBeUndefined();
  });

  it('shows an original one level deep and never a gone one', () => {
    const index = socialIndex([row('a'), row('gone', { deleted: true }), row('p', { repost_of: 'a' }),
      row('pp', { repost_of: 'p' }), row('pg', { repost_of: 'gone' })]);
    expect(repostOriginal(index, index.byId.get('p')!)?.id).toBe('a');
    expect(repostOriginal(index, index.byId.get('pp')!)).toBeNull();
    expect(repostOriginal(index, index.byId.get('pg')!)).toBeNull();
  });

  it('writes a textless private repost by default', () => {
    expect(repostPayload('kai', 'a')).toMatchObject({ authors: ['kai'], text: '', visibility: { mode: 'private' }, repost_of: 'a' });
  });
});

describe('saved posts', () => {
  it('reads account-wide bookmarks, a pending change winning', () => {
    const prefs = {
      'acc||bookmark:a': { fields: { value: true } },
      'acc||bookmark:b': { fields: { value: true } },
      '||bookmark:b': { fields: { value: null } },
      '||bookmark:c': { fields: { value: true } },
      'acc|phone|bookmark:d': { fields: { value: true } },
      'other||bookmark:e': { fields: { value: true } },
    };
    expect([...savedPosts(prefs, 'acc')].sort()).toEqual(['a', 'c']);
    expect(bookmarkPayload('a', false)).toEqual({ device: '', key: 'bookmark:a', value: null });
    expect(savedList([row('a', { occurred_at: 1 }), row('c', { occurred_at: 2 }), row('x')], new Set(['a', 'c'])).map((p) => p.id)).toEqual(['c', 'a']);
  });
});

describe('journal presentation', () => {
  it('shares the content warning first', () => {
    expect(shareText('Title', 'heavy', 'Body')).toBe('Content warning: heavy\n\nTitle\n\nBody');
    expect(shareText(undefined, ' ', 'Body')).toBe('Body');
  });

  it('formats compact times and entry metadata like Android', () => {
    const now = 10 * 86_400_000;
    expect(shortAgo(now - 30_000, now)).toBe('now');
    expect(shortAgo(now - 25 * 60_000, now)).toBe('25m');
    expect(shortAgo(now - 3 * 3_600_000, now)).toBe('3h');
    expect(shortAgo(now - 2 * 86_400_000, now)).toBe('2d');
    expect(shortAgo(now - 8 * 86_400_000, now, () => 'Jan 2')).toBe('Jan 2');
    expect(entryMeta('one two', ['a', ' '])).toBe('Journal entry · 1 min read · #a');
  });

  it('splits images (four shown, the rest counted) from files', () => {
    const file = (id: string, mime: string): AttachmentRow => ({ id, blob_hash: id, filename: id, mime, size: 1, alt_text: '', is_spoiler: false });
    const plan = postMediaPlan(['1', '2', '3', '4', '5'].map((id) => file(id, 'image/png')).concat(file('f', 'application/pdf')));
    expect(plan.shownImages.map((a) => a.id)).toEqual(['1', '2', '3', '4']);
    expect(plan.moreCount).toBe(1);
    expect(plan.files.map((a) => a.id)).toEqual(['f']);
  });
});
