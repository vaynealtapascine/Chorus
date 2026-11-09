import { render } from 'svelte/server';
import { expect, it, vi } from 'vitest';
import { sync, type Projection } from '../sync/client';
import { vote } from '../polls';
import PollCard from './PollCard.svelte';
import RemotePostPoll from './RemotePostPoll.svelte';

it('renders REST totals for a foreign poll with only the acting member local vote', () => {
  const projection = { rows: { poll_vote: {
    'foreign|kai': { exists: true, fields: { choices: ['b'] }, clocks: {} },
    'foreign|rin': { exists: true, fields: { choices: ['a'] }, clocks: {} },
  } }, sets: {}, fronts: {}, opaque: 0 } as unknown as Projection;
  const props = { projection, parentId: 'foreign', member: 'kai',
    poll: { options: [{ id: 'a', text: 'Soup' }, { id: 'b', text: 'Rice' }], multiple: false },
    remoteTally: { counts: { a: 2, b: 3 }, voters: 5 } };
  const html = render(PollCard, { props }).body;
  expect(html).toContain('5 votes');
  expect(html).toMatch(/aria-pressed="false"[^>]*>[\s\S]*?Soup/);
  expect(html).toMatch(/aria-pressed="true"[^>]*>[\s\S]*?Rice/);
  expect(html).toContain('Remove my vote');
  const post = { id: 'foreign', poll: props.poll, poll_tally: props.remoteTally };
  expect(render(RemotePostPoll, { props: { projection, post, member: 'kai' } }).body).toContain('5 votes');
  expect(render(RemotePostPoll, { props: { projection, post: { ...post, poll: null }, member: 'kai' } }).body).not.toContain('Choose one');
  expect(render(PollCard, { props: { ...props, member: null } }).body).toContain('disabled');
});

it('writes a foreign poll vote only into the voter account scope', () => {
  const create = vi.spyOn(sync, 'create').mockImplementation(() => 'vote');
  try {
    vote('foreign', 'kai', ['b']);
    expect(create).toHaveBeenCalledWith('poll.vote', sync.accountScope, 'foreign', { member_id: 'kai', choices: ['b'] });
  } finally { create.mockRestore(); }
});

it('shows a closed poll as final and takes no votes (D-136)', () => {
  const projection = { rows: { poll_vote: { 'closed|kai': { exists: true, fields: { choices: ['a'] }, clocks: {} } } }, sets: {}, fronts: {}, opaque: 0 } as unknown as Projection;
  const poll = { options: [{ id: 'a', text: 'Soup' }, { id: 'b', text: 'Rice' }], multiple: false, closes_at: 1 };
  const html = render(PollCard, { props: { projection, parentId: 'closed', poll, member: 'kai', remoteTally: { counts: { a: 1, b: 0 }, voters: 1 } } }).body;
  expect(html).toContain('Closed');
  expect(html).not.toContain('Remove my vote');
  expect(html.match(/disabled/g)?.length).toBe(2);
  const open = render(PollCard, { props: { projection, parentId: 'closed', poll: { ...poll, closes_at: Date.now() + 3 * 3_600_000 + 60_000 }, member: 'kai' } }).body;
  expect(open).toContain('Closes in 3 h');
  expect(open).toContain('Remove my vote');
});

