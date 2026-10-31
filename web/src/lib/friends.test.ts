import { expect, it } from 'vitest';
import { accountsLabel, frontingLine, frontMembers } from './friends';

it('reads only the revealed front layer, with colours', () => {
  expect(frontMembers(null)).toEqual([]);
  expect(frontMembers({ shared: false, entries: [{ level: 'front', name: 'Secret' }] })).toEqual([]);
  expect(frontMembers({ entries: [{ level: 'cocon', name: 'Ash' }, { level: 'front', name: 'Kai', color: '#7FB3A8' }, { level: 'front', name: 'Rin' }] }))
    .toEqual([{ name: 'Kai', color: '#7FB3A8' }, { name: 'Rin', color: null }]);
});

it('names up to two fronters, then counts', () => {
  expect(frontingLine([])).toBeNull();
  expect(frontingLine(['Kai'])).toBe('Kai is fronting');
  expect(frontingLine(['Kai', 'Rin'])).toBe('Kai & Rin are fronting');
  expect(frontingLine(['Kai', 'Rin', 'Ash', 'Moss'])).toBe('Kai, Rin & 2 more are fronting');
});

it('counts singlets as people, not systems', () => {
  expect(accountsLabel([{ kind: 'system' }, { kind: 'system' }, { kind: 'system' }])).toBe('3 systems');
  expect(accountsLabel([{ kind: 'system' }, { kind: 'person' }])).toBe('1 system · 1 person');
  expect(accountsLabel([{ kind: 'person' }, { kind: 'person' }])).toBe('2 people');
  expect(accountsLabel([])).toBe('');
});
