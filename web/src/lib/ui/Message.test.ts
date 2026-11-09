import { readFileSync } from 'node:fs';
import { render } from 'svelte/server';
import { beforeAll, expect, it } from 'vitest';
import { loadCore } from '../core';
import type { MessageRow } from '../data';
import Message, { forwardedFrom } from './Message.svelte';

beforeAll(async () => loadCore(readFileSync(new URL('../core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

const name = (id: string) => ({ fox: 'Foxglove', ama: 'Amarynthe' })[id] ?? 'Someone';

it('names a forward’s authors only when the forwarder kept them (F5.20)', () => {
  expect(forwardedFrom([], name)).toBe('Forwarded');
  expect(forwardedFrom([], name, 'garden')).toBe('Forwarded · #garden');
  expect(forwardedFrom(['fox', 'ama'], name)).toBe('Forwarded from Foxglove & Amarynthe');
  expect(forwardedFrom(['fox'], name, 'garden')).toBe('Forwarded from Foxglove · #garden');
});

it('renders a forward with the author hidden as “Forwarded”', () => {
  const noop = () => {};
  const m = { id: 'm1', channel_id: 'c1', authors: ['fox'], text: '', entities: [], occurred_at: 1, segments: [], attachments: [], reactions: {},
    forward_snapshot: [{ message_id: 'o1', text: 'the original', authors: [], entities: [], occurred_at: 0 }] } as unknown as MessageRow;
  const props = { m, cont: false, people: new Map(), dark: true, lookup: () => undefined, mine: false, onreply: noop, onreplyin: noop, onquote: noop,
    onedit: noop, ondelete: noop, onrestore: noop, onpin: noop, onforward: noop, onselect: noop, selecting: false, selected: false, onthread: noop,
    reacts: undefined, speaker: null, onreact: noop, emojiById: new Map(), cwAutoExpand: false, readOnly: true };
  const html = render(Message, { props }).body;
  expect(html).toContain('the original');
  expect(html).toMatch(/class="fwd[^"]*">Forwarded</);
  expect(html).not.toContain('Forwarded from');
});
