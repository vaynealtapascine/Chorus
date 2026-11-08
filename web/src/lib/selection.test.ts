import { describe, expect, it } from 'vitest';
import type { MessageRow } from './data';
import { forTarget, quoteForTarget, snapshot } from './selection';

describe('message snapshots', () => {
  it('captures a UTF-16 text range with clipped rich-text entities', () => {
    const message = {
      id: 'm', text: 'A😀bold tail', authors: ['kai'], occurred_at: 10,
      entities: [
        { type: 'bold', offset: 3, length: 4 },
        { type: 'italic', offset: 5, length: 6 },
      ],
    } as MessageRow;
    const result = snapshot(message, 'general', { message_id: 'm', offset: 4, length: 5, text: 'old t' });
    expect(result.text).toBe('old t');
    expect(result.offset).toBe(4);
    expect(result.length).toBe(5);
    expect(result.entities.map((e) => [e.type, e.offset, e.length])).toEqual([
      ['bold', 0, 3], ['italic', 1, 4],
    ]);
    message.text = 'changed';
    expect(result.text).toBe('old t');
  });

  it('hides the source chat name when the copy leaves its space', () => {
    const message = { id: 'm', text: 'hi', authors: ['kai'], occurred_at: 1, entities: [], attachments: [] } as unknown as MessageRow;
    const items = [snapshot(message, 'plans for kai')];
    expect(forTarget(items, 'home', 'home')[0].channel_name).toBe('plans for kai');
    expect('channel_name' in forTarget(items, 'home', 'shared')[0]).toBe(false);
    const quote = quoteForTarget({ items }, 'home', 'shared');
    expect('items' in quote && quote.items.every((i) => !('channel_name' in i))).toBe(true);
    expect(items[0].channel_name).toBe('plans for kai');
  });
});
