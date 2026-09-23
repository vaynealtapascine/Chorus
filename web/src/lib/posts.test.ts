import { describe, expect, it } from 'vitest';
import { canWriteAs, memberPosts, postReaction, posts } from './posts';
import type { Projection } from './sync/client';

describe('journal projection', () => {
  it('keeps posts, replies and tombstones distinct for a member profile', () => {
    const p = { rows: { post: {
      a: { exists: true, fields: { kind: 'entry', authors: ['kai'], title: 'Morning', text: 'A day', occurred_at: 4 } },
      b: { exists: true, fields: { kind: 'note', authors: ['kai', 'rin'], text: 'Reply', reply_to: 'a', occurred_at: 5 } },
      c: { exists: true, fields: { kind: 'note', authors: ['kai'], text: 'Gone', deleted_at: 6, occurred_at: 6 } },
      d: { exists: true, fields: { kind: 'note', authors: ['rin'], text: 'Other', occurred_at: 7 } },
    } }, sets: {}, fronts: {}, opaque: 0 } as unknown as Projection;
    const all = posts(p);
    expect(all.map((post) => post.id)).toEqual(['d', 'c', 'b', 'a']);
    expect(memberPosts(all, 'kai', false).map((post) => post.id)).toEqual(['a']);
    expect(memberPosts(all, 'kai', true).map((post) => post.id)).toEqual(['b']);
    expect(all.find((post) => post.id === 'a')?.visibility).toEqual({ mode: 'private' });
  });

  it('uses one reaction element for add and remove', () => {
    expect(postReaction('p', '💜', 'kai')).toEqual({ target_type: 'post', target_id: 'p', emoji: '💜', member_id: 'kai' });
  });

  it('does not offer a foreign or archived member as a post author', () => {
    const p = { rows: { member: {
      own: { exists: true, fields: { created_by_account_id: 'a' } },
      other: { exists: true, fields: { created_by_account_id: 'b' } },
      archived: { exists: true, fields: { created_by_account_id: 'a', archived_at: 1 } },
    } } } as unknown as Projection;
    expect(canWriteAs(p, 'own', 'a')).toBe(true);
    expect(canWriteAs(p, 'other', 'a')).toBe(false);
    expect(canWriteAs(p, 'archived', 'a')).toBe(false);
  });
});
