import { expect, it } from 'vitest';
import { groupedPages, pageFor, visiblePages } from './settings-pages';

it('shows what applies: no Sharing for a person, Server health for an admin, This computer on a home install', () => {
  const ids = (c: { person: boolean; admin: boolean; home: boolean }) => visiblePages(c).map((p) => p.id);
  expect(ids({ person: false, admin: false, home: false })).toContain('sharing');
  expect(ids({ person: false, admin: false, home: false })).not.toContain('health');
  expect(ids({ person: false, admin: false, home: false })).not.toContain('computer');
  expect(ids({ person: true, admin: true, home: true })).toEqual(expect.arrayContaining(['health', 'computer']));
  expect(ids({ person: true, admin: true, home: true })).not.toContain('sharing');
});

it('lists Sync issues only while some are waiting', () => {
  const ids = (issues?: number) => visiblePages({ person: false, admin: false, home: false, issues }).map((p) => p.id);
  expect(ids()).not.toContain('issues');
  expect(ids(2)).toContain('issues');
});

it('groups in the frame order and falls back to Account', () => {
  const pages = visiblePages({ person: false, admin: true, home: true });
  expect(groupedPages(pages).map((g) => g.group)).toEqual(['You', 'Data', 'This computer']);
  expect(pageFor(undefined, pages)).toBe('account');
  expect(pageFor('nope', pages)).toBe('account');
  expect(pageFor('trash', pages)).toBe('trash');
  expect(pageFor('advanced', pages)).toBe('advanced');
});
