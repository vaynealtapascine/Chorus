import { describe, expect, it } from 'vitest';
import fixture from '../../../fixtures/ui_member_list.json';
import type { FieldDef, MemberRow } from './data';
import { addFolder, bioLine, memberListPrefs, memberSections, moveFolder, pillsFor, removeFolder, renameFolder, roleColor, roleField, rolesOf, DEFAULT_VIEW } from './member-list';

const member = (id: string, sort_key?: string): MemberRow => ({ id, name: id, color: '#123456', sigils: [], proxy_tags: [], archived: false, deleted: false, sort_key });
const field = (id: string, name: string, type: FieldDef['type']): FieldDef => ({ id, name, type, options: {}, deleted: false });

describe('members list prefs (shared fixture)', () => {
  it('reads the view, folders and filing like Android', () => {
    const p = memberListPrefs(fixture.prefs as never, fixture.account);
    expect(p.view).toEqual(fixture.view);
    expect(p.folders).toEqual(fixture.folders);
    expect(Object.fromEntries(p.folderOf)).toEqual(fixture.folder_of);
    expect(memberListPrefs({}, fixture.account).view).toEqual(fixture.default_view);
    expect(DEFAULT_VIEW).toEqual(fixture.default_view);
  });
  it('reads roles and the bio line like Android', () => {
    for (const r of fixture.roles) expect(rolesOf(r.value)).toEqual(r.roles);
    for (const b of fixture.bio) expect(bioLine(b.text ?? undefined) ?? null).toBe(b.line);
  });
});

describe('role pills', () => {
  const defs = [field('a', 'Age', 'number'), field('n', 'Notes', 'text'), field('m', 'Kinds', 'multi_select'), field('r', ' roles ', 'select')];
  it('use the chosen field, else one named Role(s), else a several-choice field', () => {
    expect(roleField({ ...DEFAULT_VIEW, role_field: 'n' }, defs)?.id).toBe('n');
    expect(roleField({ ...DEFAULT_VIEW, role_field: 'a' }, defs)?.id).toBe('r'); // numbers can't be pills
    expect(roleField(DEFAULT_VIEW, defs)?.id).toBe('r');
    expect(roleField(DEFAULT_VIEW, defs.slice(0, 3))?.id).toBe('m');
    expect(roleField(DEFAULT_VIEW, defs.slice(0, 2))).toBeUndefined();
  });
  it('show none, the first or all', () => {
    expect(pillsFor(['Host', 'Protector'], 'off')).toEqual([]);
    expect(pillsFor(['Host', 'Protector'], 'first')).toEqual(['Host']);
    expect(pillsFor(['Host', 'Protector'], 'all')).toEqual(['Host', 'Protector']);
  });
  it('keep a colour per role whatever its case', () => {
    expect(roleColor('Host')).toBe(roleColor('host'));
  });
});

describe('folders', () => {
  it('section members by folder in order, then the rest, each in member order', () => {
    const folders = [{ id: 'f2', name: 'Two' }, { id: 'f1', name: 'One' }, { id: 'f3', name: 'Empty' }];
    const ms = [member('c'), member('a'), member('b'), member('d')];
    const s = memberSections(ms, folders, new Map([['a', 'f1'], ['c', 'f1'], ['d', 'f2']]));
    expect(s.map((x) => [x.folder?.id ?? null, x.members.map((m) => m.id)])).toEqual([['f2', ['d']], ['f1', ['a', 'c']], ['f3', []], [null, ['b']]]);
  });
  it('are added, renamed, removed and moved', () => {
    let f = addFolder([], 'x', '  Littles ');
    f = addFolder(f, 'y', '');
    expect(f).toEqual([{ id: 'x', name: 'Littles' }, { id: 'y', name: 'Folder' }]);
    expect(renameFolder(f, 'y', 'Protectors')[1].name).toBe('Protectors');
    expect(renameFolder(f, 'y', ' ')[1].name).toBe('Folder');
    expect(moveFolder(f, 'y', -1).map((x) => x.id)).toEqual(['y', 'x']);
    expect(moveFolder(f, 'x', -1)).toBe(f);
    expect(removeFolder(f, 'x')).toEqual([{ id: 'y', name: 'Folder' }]);
  });
});
