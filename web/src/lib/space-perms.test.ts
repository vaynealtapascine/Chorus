import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from './core';
import { project } from './core/pkg/chorus_wasm.js';
import { roleIn } from './chat-manage';
import type { Projection } from './sync/client';
import { canSetPermissions, canShare, delayOptions, everyonePayload, gatesFrom, memberSwitches, NEW_SPACE_EVERYONE, OPEN, permsFor, sharingChanges, sharingOf, SHARING_DEFAULT } from './space-perms';

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

const id = (n: number) => `0192f8c2-0000-7000-8000-${String(n).padStart(12, '0')}`;
const owner = id(1), me = id(2), SPACE = id(100), CHAN = id(200);

/** A shared space owned by `owner` with `me` in it, then whatever `more` writes; the projection is core's. */
function build(more: (op: (kind: string, entity: string, payload: object, by?: string) => void) => void): Projection {
  const ops: unknown[] = [];
  const op = (kind: string, entity: string, payload: object, by = owner) => {
    const n = ops.length + 1;
    ops.push({ id: id(1000 + n), kind, scope: `space:${SPACE}`, entity_id: entity, hlc: `${(0x1a0c4506c00 + n).toString(16).padStart(12, '0')}-0000-00000001`,
      device_at: 1790000000000 + n, payload, account_id: by, seq: n });
  };
  op('space.create', SPACE, { kind: 'shared', name: 'Lanterns' });
  op('space.join', SPACE, { account_id: owner });
  op('space.join', SPACE, { account_id: me });
  op('channel.create', CHAN, { space_id: SPACE, kind: 'text', name: 'general' });
  more(op);
  return JSON.parse(project(JSON.stringify(ops))) as Projection;
}
const info = { owner_account_id: owner, accounts: [{ id: owner }, { id: me }] };
const who = (p: Projection, by: string, channelId?: string) => ({ spaceId: SPACE, me: by, channelId, role: roleIn(p.rows.space_member, SPACE, info, by) });

describe('space permissions through core', () => {
  it('keeps what a member could do before, and invite off', () => {
    const p = build(() => {});
    const perms = permsFor(p.rows, who(p, me, CHAN));
    expect(perms).toEqual(expect.arrayContaining(['send', 'attach', 'poll', 'aside', 'history']));
    expect(perms).not.toContain('invite');
    expect(gatesFrom(perms)).toEqual({ attach: true, poll: true, aside: true, invite: false });
  });
  it('lets the owner do everything whatever the overrides say', () => {
    const p = build((op) => op('space.set_permission', SPACE, { target_type: 'role', target_id: 'everyone', allow: [], deny: ['attach', 'send'] }));
    expect(gatesFrom(permsFor(p.rows, who(p, owner, CHAN)))).toEqual({ attach: true, poll: true, aside: true, invite: true });
  });
  it('hides what the everyone override denies, and a channel override beats the space', () => {
    const p = build((op) => {
      op('space.set_permission', SPACE, { target_type: 'role', target_id: 'everyone', allow: ['invite'], deny: ['attach', 'poll'] });
      op('channel.set_permission', CHAN, { target_type: 'role', target_id: 'everyone', allow: ['poll'], deny: [] });
    });
    expect(gatesFrom(permsFor(p.rows, who(p, me, CHAN)))).toEqual({ attach: false, poll: true, aside: true, invite: true });
    // asked about the whole space, the channel's allow doesn't count
    expect(gatesFrom(permsFor(p.rows, who(p, me)))).toMatchObject({ attach: false, poll: false });
  });
  it('lets an account override beat the role and everyone', () => {
    const p = build((op) => {
      op('space.set_permission', SPACE, { target_type: 'role', target_id: 'everyone', allow: [], deny: ['aside'] });
      op('space.set_permission', SPACE, { target_type: 'account', target_id: me, allow: ['aside'], deny: [] });
    });
    expect(gatesFrom(permsFor(p.rows, who(p, me, CHAN))).aside).toBe(true);
  });
  it('shows nothing a read-only member cannot send, and everything when ungated', () => {
    const p = build((op) => op('space.set_role', SPACE, { account_id: me, role: 'read_only' }));
    expect(gatesFrom(permsFor(p.rows, who(p, me, CHAN)))).toEqual({ attach: false, poll: false, aside: false, invite: false });
    expect(gatesFrom(null)).toEqual(OPEN);
  });
  it('knows who may open which part of the dialog', () => {
    expect([canSetPermissions('shared', 'owner'), canSetPermissions('shared', 'admin'), canSetPermissions('shared', 'member'), canSetPermissions('dm', 'owner')]).toEqual([true, true, false, false]);
    expect([canShare('shared', 'member'), canShare('shared', null), canShare('internal', 'owner')]).toEqual([true, false, false]);
  });
});

describe('the everyone switches', () => {
  it('reads what a plain member gets and writes only what changed, keeping the rest of the lists', () => {
    const p = build((op) => op('space.set_permission', SPACE, { target_type: 'role', target_id: 'everyone', allow: ['invite'], deny: ['pin', 'react'] }));
    const before = memberSwitches(p.rows, SPACE);
    expect(before).toMatchObject({ send: true, attach: true, pin: false, invite: true, history: true });
    expect(everyonePayload(p.rows, SPACE, before, before)).toBeNull();
    expect(everyonePayload(p.rows, SPACE, before, { ...before, pin: true, history: false })).toEqual({
      target_type: 'role', target_id: 'everyone', allow: ['invite', 'pin'], deny: ['react', 'history'],
    });
  });
  it('writes the first override from nothing, and the new-space defaults deny invite and history', () => {
    const p = build(() => {});
    const before = memberSwitches(p.rows, SPACE);
    expect(everyonePayload(p.rows, SPACE, before, { ...before, attach: false })).toMatchObject({ allow: [], deny: ['attach'] });
    expect(NEW_SPACE_EVERYONE).toMatchObject({ target_type: 'role', target_id: 'everyone', allow: [], deny: ['invite', 'history'] });
    const q = build((op) => op('space.set_permission', SPACE, NEW_SPACE_EVERYONE));
    expect(memberSwitches(q.rows, SPACE)).toMatchObject({ invite: false, history: false, send: true });
  });
});

describe('our own sharing', () => {
  it('defaults to names on and fronting off at 15 minutes, and reads only what each account wrote', () => {
    const p = build((op) => {
      op('space.set_sharing', SPACE, { member_names: false }, me);
      op('space.set_sharing', SPACE, { fronts: true, fronts_delay_min: 30 }, owner);
    });
    expect(sharingOf(p.rows, SPACE, me)).toEqual({ member_names: false, fronts: false, fronts_delay_min: 15 });
    expect(sharingOf(p.rows, SPACE, owner)).toEqual({ member_names: true, fronts: true, fronts_delay_min: 30 });
    expect(sharingOf(p.rows, SPACE, id(9))).toEqual(SHARING_DEFAULT);
  });
  it('sends only the changed fields', () => {
    expect(sharingChanges(SHARING_DEFAULT, SHARING_DEFAULT)).toBeNull();
    expect(sharingChanges(SHARING_DEFAULT, { ...SHARING_DEFAULT, fronts: true })).toEqual({ fronts: true });
    expect(sharingChanges(SHARING_DEFAULT, { member_names: false, fronts: true, fronts_delay_min: 60 })).toEqual({ member_names: false, fronts: true, fronts_delay_min: 60 });
  });
  it('lists the delays, keeping one another client set', () => {
    expect(delayOptions(15).map((o) => o.label)).toEqual(['Now', '15 min', '1 hour', '6 hours', '1 day']);
    expect(delayOptions(30).map((o) => o.label)).toEqual(['Now', '15 min', '30 min', '1 hour', '6 hours', '1 day']);
  });
});
