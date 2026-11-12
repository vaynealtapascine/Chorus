import { render } from 'svelte/server';
import { expect, it } from 'vitest';
import DragListHost from './DragListHost.test.svelte';
import { createRawSnippet } from 'svelte';
import Button from './Button.svelte';
import ConfirmSheet from './ConfirmSheet.svelte';
import EmptyState from './EmptyState.svelte';
import FormNote from './FormNote.svelte';
import Segmented from './Segmented.svelte';
import SectionLabel from './SectionLabel.svelte';
import Switch from './Switch.svelte';
import UnderlineTabs from './UnderlineTabs.svelte';
import ValueRow from './ValueRow.svelte';

it('draws an empty state with at most one action', () => {
  const bare = render(EmptyState, { props: { icon: 'chat', label: 'No chats yet' } }).body;
  expect(bare).toContain('No chats yet');
  expect(bare).not.toContain('<button');
  expect(render(EmptyState, { props: { icon: 'chat', label: 'No chats yet', action: 'New chat', onaction: () => {} } }).body).toMatch(/<button[^>]*>New chat<\/button>/);
  expect(render(EmptyState, { props: { icon: 'chat', label: 'No chats yet', action: 'New chat', href: '#/chat/new' } }).body).toContain('href="#/chat/new"');
});

it('value rows show the value and become links', () => {
  const row = render(ValueRow, { props: { label: 'Theme', value: 'Dark', href: '#/settings/appearance' } }).body;
  expect(row).toMatch(/<a class="value-row[^"]*" href="#\/settings\/appearance"/);
  expect(row).toContain('Dark');
  expect(render(ValueRow, { props: { label: 'Theme', value: 'Dark', href: '#/x', disabled: true } }).body).toMatch(/<button[^>]*disabled/);
});

it('switches, tabs and segments say which is on', () => {
  expect(render(Switch, { props: { label: 'Clear when Kai stops fronting', checked: true } }).body).toMatch(/role="switch" aria-checked="true"/);
  expect(render(Switch, { props: { aria: 'Mute', checked: false } }).body).toMatch(/aria-checked="false" aria-label="Mute"/);
  const tabs = render(UnderlineTabs, { props: { label: 'Members', value: 'groups', tabs: [{ id: 'members', label: 'Members', count: 4 }, { id: 'groups', label: 'Groups' }] } }).body;
  expect(tabs).toMatch(/aria-selected="false"[^>]*>\s*Members(<!--[^>]*-->)?<span class="count[^"]*">4<\/span>/);
  expect(tabs).toMatch(/aria-selected="true"[^>]*>\s*Groups/);
  const seg = render(Segmented, { props: { label: 'Range', value: 'month', options: [{ id: 'week', label: 'Week' }, { id: 'month', label: 'Month' }] } }).body;
  expect(seg).toMatch(/aria-checked="true"[^>]*>Month/);
});

it('labels, notes and confirmations', () => {
  expect(render(SectionLabel, { props: { label: 'Friends right now', link: 'All friends', href: '#/people' } }).body).toContain('All friends');
  expect(render(FormNote, { props: { text: 'The Junipers will see it.', warning: true } }).body).toContain('role="alert"');
  const confirm = render(ConfirmSheet, { props: { title: 'Archive Nairon?', confirm: 'Archive', danger: true, onconfirm: () => {}, onclose: () => {} } }).body;
  expect(confirm).toContain('Archive Nairon?');
  expect(confirm).toMatch(/class="btn danger md go[^"]*"[^>]*>(<!---->)?Archive/);
  expect(confirm).toContain('Cancel');
});

it('a drag list draws a grip on each row, in the given order', () => {
  const html = render(DragListHost, { props: { items: [{ id: 'a' }, { id: 'b' }], onreorder: () => {} } }).body;
  expect(html.match(/class="grip/g)).toHaveLength(2);
  expect(html.indexOf('row a')).toBeLessThan(html.indexOf('row b'));
});

it('the install card shows nothing where the browser cannot install', async () => {
  const { default: InstallCard } = await import('./InstallCard.svelte');
  expect(render(InstallCard).body).not.toContain('Install Chorus');
});

it('buttons carry their variant and size, and link when given an href', () => {
  const children = createRawSnippet(() => ({ render: () => 'Save' }));
  const primary = render(Button, { props: { variant: 'primary', grow: true, class: 'go', disabled: true, children } }).body;
  expect(primary).toMatch(/<button class="btn primary md go[^"]*grow"[^>]*disabled[^>]*>Save/);
  expect(render(Button, { props: { size: 'sm', children } }).body).toMatch(/class="btn neutral sm[ "]/);
  expect(render(Button, { props: { href: '#/x', children } }).body).toMatch(/<a class="btn neutral md[ "][^>]*href="#\/x"/);
});

it('chips toggle and link, pills only label', async () => {
  const { default: Chip } = await import('./Chip.svelte');
  const { default: Pill } = await import('./Pill.svelte');
  expect(render(Chip, { props: { label: 'Kai', on: true } }).body).toMatch(/role="checkbox" aria-checked="true"/);
  expect(render(Chip, { props: { label: 'Kai', role: 'radio', 'aria-checked': false, on: false } }).body).toMatch(/role="radio"/);
  expect(render(Chip, { props: { label: 'Bucket', href: '#/people/bucket/b', on: true } }).body).toMatch(/<a class="chip[^"]* on[^"]*" href="#\/people\/bucket\/b"/);
  const pill = render(Pill, { props: { tone: 'accent', children: createRawSnippet(() => ({ render: () => 'System' })) } }).body;
  expect(pill).toMatch(/<span class="pill accent[^"]*"[^>]*>System/);
});
