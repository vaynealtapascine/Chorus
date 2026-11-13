import { readFileSync } from 'node:fs';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { loadCore } from './core';
import { sync, type Projection } from './sync/client';
import { emptyDraft } from './journal-draft';
import { Composer, composerFor, contextOf, hasContent, postPayload } from './journal-composer.svelte';

vi.mock('./sync/uploads', () => ({ flushUploads: vi.fn(), imageThumbnail: vi.fn(async () => null), stageBlob: vi.fn(async (_b: Blob, mime: string) => ({ hash: 'h', mime })) }));

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
afterEach(() => { Object.assign(sync, { device: null }); vi.restoreAllMocks(); });
const row = (fields: Record<string, unknown>) => ({ exists: true, fields, clocks: {} });
function fixture(extra: Record<string, unknown> = {}): Projection {
  Object.assign(sync, { device: { account_id: 'acct' } });
  return { rows: {
    member: { kai: row({ name: 'Kai', created_by_account_id: 'acct' }), ash: row({ name: 'Ash', display_name: 'Ashen' }), gone: row({ name: 'Gone', archived_at: 1 }) },
    ...extra,
  }, sets: {}, fronts: {} } as unknown as Projection;
}

it('builds the post payload from a draft: title only for entries, tags trimmed, audience, reply, quote, files, poll, alerts', () => {
  const d = { ...emptyDraft('buckets', ['b1']), kind: 'entry' as const, title: ' A day ', tags: '#calm, garden ,', cw: ' ', mood: 'ok', quote: 'q', alerts: 'delayed' as const };
  const p = postPayload(d, ['kai'], 'parent', { text: 'hi', entities: [] }, ['f1'], { options: [] });
  expect(p).toEqual({ kind: 'entry', authors: ['kai'], title: 'A day', text: 'hi', entities: [], mood: 'ok', tags: ['calm', 'garden'], cw: null,
    visibility: { mode: 'buckets', bucket_ids: ['b1'] }, reply_to: 'parent', quote: { post_id: 'q' }, attachments: ['f1'], poll: { options: [] }, alerts: 'delayed' });
  const note = postPayload({ ...emptyDraft(), title: 'ignored' }, ['kai'], null, { text: 'x', entities: [] }, [], undefined);
  expect(note).toEqual({ kind: 'note', authors: ['kai'], title: null, text: 'x', entities: [], mood: null, tags: [], cw: null, visibility: { mode: 'private' } });
});

it('counts words, files, a quote or a poll as content, not a remembered audience or author', () => {
  expect(hasContent({ ...emptyDraft('friends'), authors: ['kai'] }, 0)).toBe(false);
  expect(hasContent(emptyDraft(), 1)).toBe(true);
  expect(hasContent({ ...emptyDraft(), body: ' hi ' }, 0)).toBe(true);
  expect(hasContent({ ...emptyDraft(), pollOn: true }, 0)).toBe(true);
});

it('shares one composer per context and account', () => {
  expect(composerFor('main', 'a')).toBe(composerFor('main', 'a'));
  expect(composerFor('main', 'a')).not.toBe(composerFor('main', 'b'));
  expect(contextOf('p1')).toBe('reply:p1');
  expect(contextOf(null)).toBe('main');
  expect(new Composer('a', 'reply:p1').replyTo).toBe('p1');
});

it('follows the suggested author until someone picks, and posts only with words or files', () => {
  const c = new Composer('acct', 'main');
  c.attach(fixture(), { authors: ['kai'] });
  expect(c.writers.map((m) => m.id).sort()).toEqual(['ash', 'kai']);
  expect(c.authors).toEqual(['kai']);
  expect(c.firstName).toBe('Kai');
  expect(c.canPost).toBe(false);
  c.draft.body = 'hello';
  expect(c.canPost).toBe(true);
  c.setAuthors(['ash', 'kai']);
  expect(c.firstName).toBe('Ashen');
  c.setAuthors([]);
  expect(c.canPost).toBe(false);
});

it('a reply starts with the thread’s audience; a new post with the account default (D-117)', () => {
  const reply = new Composer('acct', 'reply:p1');
  reply.attach(fixture(), { replyVisibility: 'server' });
  expect(reply.draft.visibility).toBe('server');
  const main = new Composer('acct', 'main');
  main.attach(fixture({ pref: { 'acct||journal.default_audience': row({ value: { mode: 'friends' } }) } }));
  expect(main.draft.visibility).toBe('friends');
});

it('sends a post op and keeps author and audience, clearing what was said', async () => {
  const create = vi.spyOn(sync, 'create').mockImplementation(() => undefined as never);
  vi.spyOn(sync, 'newId').mockReturnValue('new');
  const c = new Composer('acct', 'reply:p1');
  c.attach(fixture(), { authors: ['kai'], replyVisibility: 'followers' });
  c.draft.body = 'a **reply**';
  c.setAuthors(['kai']);
  expect(await c.send()).toBe(true);
  const [kind, , , payload] = create.mock.calls.at(-1)!;
  expect(kind).toBe('post.create');
  expect(payload).toMatchObject({ authors: ['kai'], reply_to: 'p1', visibility: { mode: 'followers' }, text: 'a reply' });
  expect(c.draft.body).toBe('');
  expect(c.draft.authors).toEqual(['kai']);
  expect(c.draft.visibility).toBe('followers');
});

it('refuses a poll with too few options and keeps the draft', async () => {
  const create = vi.spyOn(sync, 'create').mockImplementation(() => undefined as never);
  const c = new Composer('acct', 'main');
  c.attach(fixture(), { authors: ['kai'] });
  c.draft.body = 'which?';
  c.draft.pollOn = true;
  expect(await c.send()).toBe(false);
  expect(c.error).not.toBe('');
  expect(c.draft.body).toBe('which?');
  expect(create).not.toHaveBeenCalled();
});
