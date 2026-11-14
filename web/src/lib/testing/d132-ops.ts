// Ops for tests of Handover and Notes (D-132, D-162), projected by the real core (never hand-written
// rows: NOTES, Fixtures). `build` numbers the ops, gives each a clock, and returns the projection.
import { project } from '../core/pkg/chorus_wasm.js';
import type { Projection } from '../sync/client';

export const T0 = 1_800_000_000_000;

/** A valid id (the core checks them): `u(5)` is always the same one. */
export const u = (n: number): string => `0192f8c2-0000-7000-8000-${n.toString(16).padStart(12, '0')}`;

export const ME = u(0x001);
export const OTHER = u(0x002);

export const ID = {
  me: u(0x101), fox: u(0x102), amy: u(0x103), nai: u(0x104),
  home: u(0x201), lanterns: u(0x202), theirs: u(0x203),
  general: u(0x301), handover: u(0x302), notes: u(0x303), mine: u(0x304), theirNotes: u(0x305),
};

export interface Spec { kind: string; scope: string; entity?: string | null; payload: Record<string, unknown>; at?: number; account?: string; member?: string }

export function ops(specs: Spec[]): Record<string, unknown>[] {
  return specs.map((s, i) => {
    const at = s.at ?? T0 + i * 1000;
    return {
      id: u(0x1000 + i),
      scope: s.scope, kind: s.kind, entity_id: s.entity ?? null, payload: s.payload, v: 1,
      device_at: at, occurred_at: at, tz_offset_min: 0, account_id: s.account ?? ME, device_id: 'dev',
      member_id: s.member ?? null, seq: i + 1,
      hlc: `${at.toString(16).padStart(12, '0')}-${i.toString(16).padStart(4, '0')}-00000001`,
    };
  });
}

export function build(specs: Spec[]): Projection {
  return JSON.parse(project(JSON.stringify(ops(specs)))) as Projection;
}

const home = `space:${ID.home}`;

/** A person's account: its one member (D-003), its internal space "Notes" holding only the Notes
 * channel the server makes (D-162), and a shared space with one chat. */
export function personSpecs(): Spec[] {
  return [
    { kind: 'member.create', scope: `account:${ME}`, entity: ID.me, payload: { name: 'Violet', is_self: true, color: '#B99CE8' } },
    { kind: 'space.create', scope: home, entity: ID.home, payload: { kind: 'internal', name: 'Notes' } },
    { kind: 'channel.create', scope: home, entity: ID.notes, payload: { space_id: ID.home, kind: 'text', name: 'Notes to self', special: 'notes' } },
    { kind: 'space.create', scope: `space:${ID.lanterns}`, entity: ID.lanterns, payload: { kind: 'shared', name: 'The Lanterns' } },
    { kind: 'channel.create', scope: `space:${ID.lanterns}`, entity: ID.general, payload: { space_id: ID.lanterns, kind: 'text', name: 'general' } },
  ];
}

/** A system's account: members Foxglove and Amarynthe, its internal space with General, Handover and
 * Notes to ourselves. */
export function systemSpecs(): Spec[] {
  return [
    { kind: 'member.create', scope: `account:${ME}`, entity: ID.fox, payload: { name: 'Foxglove', color: '#B99CE8' } },
    { kind: 'member.create', scope: `account:${ME}`, entity: ID.amy, payload: { name: 'Amarynthe', color: '#F08C7F' } },
    { kind: 'space.create', scope: home, entity: ID.home, payload: { kind: 'internal', name: 'The Lanterns' } },
    { kind: 'channel.create', scope: home, entity: ID.general, payload: { space_id: ID.home, kind: 'text', name: 'general' } },
    { kind: 'channel.create', scope: home, entity: ID.handover, payload: { space_id: ID.home, kind: 'text', name: 'Handover', special: 'handover' } },
    { kind: 'channel.create', scope: home, entity: ID.notes, payload: { space_id: ID.home, kind: 'text', name: 'Notes to ourselves', special: 'notes' } },
  ];
}

export const send = (channel: string, id: string, text: string, authors: string[], extra: Record<string, unknown> = {}, at?: number): Spec =>
  ({ kind: 'message.send', scope: home, entity: id, at, payload: { channel_id: channel, authors, text, entities: [], ...extra } });
