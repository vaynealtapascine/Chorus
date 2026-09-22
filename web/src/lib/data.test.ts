import { describe, expect, it } from 'vitest';
import { fuzzy, groupPath, membership, type GroupRow } from './data';
import type { Projection } from './sync/client';

describe('data helpers', () => {
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
});
