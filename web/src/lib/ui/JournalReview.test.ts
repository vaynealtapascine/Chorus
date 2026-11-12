import { readFileSync } from 'node:fs';
import { render } from 'svelte/server';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { loadCore } from '../core';
import { posts } from '../posts';
import { sync, type Projection } from '../sync/client';
import PostCard from './PostCard.svelte';
import PostThread from './PostThread.svelte';
import Profile from './Profile.svelte';
import JournalLists from './JournalLists.svelte';
import MemberEditor from './MemberEditor.svelte';

vi.mock('../router.svelte', () => ({ router: { go: vi.fn() } }));

beforeAll(async () => loadCore(readFileSync(new URL('../core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
afterEach(() => Object.assign(sync, { device: null }));
const row = (fields: Record<string, unknown>) => ({ exists: true, fields, clocks: {} });
function fixture(person = false, text = '🪴'.repeat(300) + 'HIDDEN_SUFFIX') {
  Object.assign(sync, { device: { account_id: 'account' } });
  return { rows: {
    member: { kai: row({ name: 'Kai', is_self: person, pinned_post_id: 'post', created_by_account_id: 'account' }) },
    post: { post: row({ kind: 'note', authors: ['kai'], text, entities: [], occurred_at: 1 }) },
    member_list: { list: row({ name: 'Friends' }) },
  }, sets: { member_list_item: { 'list|{"member_id":"kai"}': true } }, fronts: {} } as unknown as Projection;
}

it('defaults every timeline card to 300 Unicode characters and leaves detail complete', () => {
  const projection = fixture();
  const post = posts(projection)[0];
  const props = { post, people: new Map(), dark: true, reactions: new Map(), speaker: null, onreply() {}, onreact() {} };
  for (const kind of ['note', 'entry'] as const) {
    const html = render(PostCard, { props: { ...props, post: { ...post, kind } } }).body;
    expect(html).toContain('🪴'.repeat(300));
    expect(html).not.toContain('HIDDEN_SUFFIX');
    expect(html).toContain('Show more');
    const detail = render(PostCard, { props: { ...props, full: true } }).body;
    expect(detail).toContain('HIDDEN_SUFFIX');
    expect(detail).not.toContain('Show more');
  }
  for (const html of [
    render(Profile, { props: { projection, id: 'kai', dark: true } }).body,
    render(JournalLists, { props: { projection, dark: true, open: 'list' } }).body,
  ]) {
    expect(html).toContain('data-post-id="post"');
    expect(html).toContain('Show more');
    expect(html).not.toContain('HIDDEN_SUFFIX');
  }
  expect(render(PostThread, { props: { projection, id: 'post', dark: true } }).body).toContain('HIDDEN_SUFFIX');
});

it('keeps short multiline and exactly 300 character posts whole without Show more', () => {
  for (const text of ['one\ntwo\nthree\nfour', '🪴'.repeat(300)]) {
    const projection = fixture(false, text);
    const html = render(PostCard, { props: { post: posts(projection)[0], people: new Map(), dark: true,
      reactions: new Map(), speaker: null, onreply() {}, onreact() {} } }).body;
    expect(html).toContain(text);
    expect(html).not.toContain('Show more');
  }
});

it('gives singlets a complete profile editor without system or self lifecycle controls', () => {
  const view = (person: boolean) => render(MemberEditor, { props: { projection: fixture(person), id: 'kai', dark: true } }).body;
  const person = view(true);
  for (const label of ['Switch in', 'Fronting', 'fronting', 'Followers hear', 'Folder', 'Subsystem', 'Archive', 'Move to Trash', 'Proxy tags', 'Member DMs']) expect(person).not.toContain(label);
  for (const label of ['Change picture', 'Colour', 'Display name', 'Pronouns', 'Description', 'Handles', 'Custom fields', 'Who can see', 'Save']) expect(person).toContain(label);
  const system = view(false);
  for (const label of ['Switch in', 'Sigils and proxy tags', 'Groups', 'Folder', 'Archive', 'Move to Trash']) expect(system).toContain(label);
});
