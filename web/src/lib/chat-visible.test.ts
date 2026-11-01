import { describe, expect, it } from 'vitest';
import { channels } from './data';
import { chatShown, privacyChannel } from './chat-visible';
import type { Projection } from './sync/client';

const row = (fields: Record<string, unknown>) => ({ exists: true, fields });
function fixture(): Projection {
  return { rows: { space: { s: row({ kind: 'dm' }) }, member: { kai: row({ name: 'Kai' }) },
    channel: { private: row({ kind: 'member_dm', space_id: 's', member_ids: ['kai'] }) }, message: {} },
    sets: {}, fronts: {}, opaque: 0 } as unknown as Projection;
}
describe('private chat ancestry', () => {
  it('fails closed for missing channels, missing parents and tombstoned private channels', () => {
    const p = fixture();
    p.rows.channel.thread = row({ kind: 'thread', space_id: 's', parent_message_id: 'missing' });
    expect(chatShown(p, 'me')(undefined)).toBe(false);
    expect(chatShown(p, 'me')(channels(p).find((c) => c.id === 'thread'))).toBe(false);
    p.rows.channel.private.fields.deleted_at = 1;
    expect(chatShown(p, 'me')(channels(p).find((c) => c.id === 'private'))).toBe(false);
  });
  it('inherits private speakers and visibility through arbitrarily deep valid ancestry', () => {
    const p = fixture();
    let parent = 'private';
    for (let i = 0; i < 8; i++) {
      p.rows.message[`m${i}`] = row({ channel_id: parent, text: 'x' });
      p.rows.channel[`t${i}`] = row({ kind: 'thread', space_id: 's', parent_message_id: `m${i}` });
      parent = `t${i}`;
    }
    const c = channels(p).find((c) => c.id === parent);
    expect(privacyChannel(p, c)?.member_ids).toEqual(['kai']);
    expect(chatShown(p, 'me')(c)).toBe(false);
    const cyclic = structuredClone(p);
    cyclic.rows.message.m0.fields.channel_id = 't7';
    expect(privacyChannel(cyclic, c)).toBeUndefined();
    expect(chatShown(cyclic, 'me')(c)).toBe(false);
  });
  it('refuses cross-space ancestry', () => {
    const p = fixture();
    p.rows.message.m = row({ channel_id: 'private', text: 'x' });
    p.rows.channel.thread = row({ kind: 'thread', space_id: 'other', parent_message_id: 'm' });
    expect(privacyChannel(p, channels(p).find((c) => c.id === 'thread'))).toBeUndefined();
  });
});
