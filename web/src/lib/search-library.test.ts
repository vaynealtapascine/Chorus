import { expect, it } from 'vitest';
import { libraryOf, setOperator } from './search-library';
import type { MessageRow } from './data';

const msg = (over: Partial<MessageRow>): MessageRow => ({ id: 'm', channel_id: 'c', authors: [], text: '', entities: [], segments: [], occurred_at: 1, sent_offline: false, edited: false, deleted: false, pinned: false, attachments: [], ...over }) as MessageRow;
const att = (id: string, filename: string, mime: string, spoiler = false) => ({ id, filename, mime, is_spoiler: spoiler }) as MessageRow['attachments'][number];

it('writes an operator into a query, replacing the old one and quoting a name with a space', () => {
  expect(setOperator('cake', 'from', 'Ren')).toBe('cake from:Ren');
  expect(setOperator('from:Ren cake in:Garden', 'from', 'Nairon Vale')).toBe('cake in:Garden from:"Nairon Vale"');
  expect(setOperator('cake from:Ren', 'from', null)).toBe('cake');
});

it('lists media, files and links apart, newest first, skipping spoilers, warnings and deleted messages', () => {
  const rows = [
    msg({ id: 'a', occurred_at: 1, attachments: [att('1', 'cat.png', 'image/png'), att('2', 'plan.pdf', 'application/pdf'), att('3', 'secret.png', 'image/png', true)] }),
    msg({ id: 'b', occurred_at: 2, attachments: [att('4', 'dog.png', 'image/png')] }),
    msg({ id: 'c', occurred_at: 3, cw: 'x', attachments: [att('5', 'no.png', 'image/png')] }),
    msg({ id: 'd', occurred_at: 4, text: 'see https://a.example/x', entities: [{ type: 'url', offset: 4, length: 19 }] }),
  ];
  expect(libraryOf(rows, 'media', []).map((i) => i.id)).toEqual(['4', '1']);
  expect(libraryOf(rows, 'media', ['cat']).map((i) => i.id)).toEqual(['1']);
  expect(libraryOf(rows, 'files', []).map((i) => i.id)).toEqual(['2']);
  expect(libraryOf(rows, 'links', []).map((i) => i.url)).toEqual(['https://a.example/x']);
});
