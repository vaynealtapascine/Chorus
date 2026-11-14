import { readFileSync } from 'node:fs';
import { render } from 'svelte/server';
import { beforeAll, expect, it, vi } from 'vitest';
import { loadCore } from '../core';
import { project } from '../core/pkg/chorus_wasm.js';
import { sync, type Projection } from '../sync/client';
import SpacePermissions from './SpacePermissions.svelte';

beforeAll(async () => loadCore(readFileSync(new URL('../core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

const id = (n: number) => `0192f8c2-0000-7000-8000-${String(n).padStart(12, '0')}`;
const owner = id(1), member = id(2), SPACE = id(100);
const ops = [
  { kind: 'space.create', by: owner, entity: SPACE, payload: { kind: 'shared', name: 'Lanterns' } },
  { kind: 'space.join', by: owner, entity: SPACE, payload: { account_id: owner } },
  { kind: 'space.join', by: owner, entity: SPACE, payload: { account_id: member } },
  { kind: 'space.set_permission', by: owner, entity: SPACE, payload: { target_type: 'role', target_id: 'everyone', allow: [], deny: ['history'] } },
  { kind: 'space.set_sharing', by: member, entity: SPACE, payload: { fronts: true, fronts_delay_min: 60 } },
].map((o, i) => ({ id: id(1000 + i), kind: o.kind, scope: `space:${SPACE}`, entity_id: o.entity, hlc: `${(0x1a0c4506c00 + i).toString(16).padStart(12, '0')}-0000-00000001`,
  device_at: 1790000000000 + i, payload: o.payload, account_id: o.by, seq: i + 1 }));
const info = { id: SPACE, kind: 'shared' as const, name: 'Lanterns', owner_account_id: owner, accounts: [
  { id: owner, handle: 'o', display_name: 'Owner', kind: 'system' }, { id: member, handle: 'm', display_name: 'Member', kind: 'system' }] };

function show(account: string) {
  vi.spyOn(sync, 'accountId', 'get').mockReturnValue(account);
  const projection = JSON.parse(project(JSON.stringify(ops))) as Projection;
  return render(SpacePermissions, { props: { projection, space: { id: SPACE, kind: 'shared', name: 'Lanterns' }, info, onclose: () => {} } }).body;
}

it('gives an admin the permission rows, our sharing rows and the exceptions', () => {
  const html = show(owner);
  for (const label of ['Permissions', 'you manage this space', 'Send photos and files', 'Create polls', 'Invite other systems', 'Allow system asides',
    'Member names on each message', 'Our fronting status', 'History before they joined', 'From when they join', 'Exceptions']) expect(html).toContain(label);
  expect(html).toMatch(/Send messages[\s\S]*?aria-checked="true"/);
  expect(html).toMatch(/Invite other systems[\s\S]*?aria-checked="false"/);
  expect(html).toContain('Save permissions');
});

it('gives a member only their own sharing, with the delay they chose', () => {
  const html = show(member);
  expect(html).toContain('What we share');
  expect(html).toContain('Member names on each message');
  expect(html).toContain('Our fronting status');
  expect(html).toMatch(/aria-checked="true"[^>]*>\s*1 hour|1 hour/);
  for (const label of ['Send photos and files', 'Create polls', 'Invite other systems', 'History before they joined', 'Allow system asides', 'Exceptions']) expect(html).not.toContain(label);
});
