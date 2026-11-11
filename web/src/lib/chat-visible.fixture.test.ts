import { describe, expect, it } from 'vitest';
import fixture from '../../../fixtures/ui_chat_visible.json';
import { isPrivate, privateVisible } from './chat-list';

// Which chats are shown (D-097, D-098, D-110, D-102), the same cases Android's
// ChatVisibleFixtureTest runs through ChatOrganize.privateVisible.

type Entry = { subject_type: string; subject_id: string; level: string };
interface Case {
  name: string;
  space: string;
  channel: { kind: string; member_ids: string[]; invalid_membership: boolean };
  own: string[];
  front: Entry[];
  front_switched: Entry[] | null;
  viewing_as: string | null;
  hide_private: boolean;
  inside: Record<string, string[]>;
  is_private: boolean;
  visible: boolean;
}

/** The listed ids and everyone inside a subsystem among them, recursively, each once. */
const including = (inside: Record<string, string[]>) => (ids: string[]): string[] => {
  const out = [...new Set(ids)];
  for (let i = 0; i < out.length; i++) for (const part of inside[out[i]] ?? []) if (!out.includes(part)) out.push(part);
  return out;
};

describe('which chats are shown (shared fixture ui_chat_visible.json)', () => {
  const cases = fixture.cases as unknown as Case[];

  it('has cases', () => expect(cases.length).toBeGreaterThan(20));

  for (const c of cases) {
    it(c.name, () => {
      const space = { kind: c.space };
      const own = new Set(c.own);
      expect(isPrivate(c.channel, space)).toBe(c.is_private);
      expect(privateVisible(c.channel, space, c.front, c.viewing_as, c.hide_private, (id) => own.has(id),
        c.front_switched ?? c.front, including(c.inside))).toBe(c.visible);
    });
  }
});
