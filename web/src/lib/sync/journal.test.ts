import { describe, expect, it } from 'vitest';
import { mergeJournal } from './persist';

describe('the unsaved-ops journal', () => {
  it("keeps both tabs' ops when two tabs go away with unsaved work", () => {
    const first = JSON.stringify(mergeJournal(null, [{ id: 'a' }, { id: 'b' }]));
    const both = mergeJournal(first, [{ id: 'c' }]);
    expect(both.map((o) => o.id).sort()).toEqual(['a', 'b', 'c']);
  });

  it("holds one copy of an op both tabs had, this tab's", () => {
    const held = JSON.stringify([{ id: 'a', v: 1 }]);
    expect(mergeJournal(held, [{ id: 'a', v: 2 } as { id: string }])).toEqual([{ id: 'a', v: 2 }]);
  });

  it('replaces a journal it cannot read', () => {
    expect(mergeJournal('{not json', [{ id: 'a' }])).toEqual([{ id: 'a' }]);
    expect(mergeJournal('{"id":"x"}', [{ id: 'a' }])).toEqual([{ id: 'a' }]);
  });
});
