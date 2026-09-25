<script lang="ts">
  // Space roles (Advanced; SPEC §5.1, D-047): the owner and admins give each account in a shared
  // space a role (admin, member, read-only or a custom one) and define custom roles with their
  // permissions. Channel overrides (ChannelPermissions) then apply per role. The server checks
  // who may (perms::write_denied): only the owner or an admin, never in a DM.
  import { sync, type Projection } from '../sync/client';
  import type { SpaceInfo } from '../spaces';

  let { projection, spaceId, info }: { projection: Projection; spaceId: string; info: SpaceInfo | undefined } = $props();

  const PERMS = [
    { id: 'view', label: 'See' }, { id: 'send', label: 'Write' }, { id: 'react', label: 'React' },
    { id: 'thread', label: 'Threads' }, { id: 'pin', label: 'Pin' },
  ] as const;
  interface Role { id: string; name: string; perms: string[] }
  const scope = $derived(`space:${spaceId}`);

  const custom = $derived.by((): Role[] => {
    const raw = projection.rows.space?.[spaceId]?.fields.roles;
    if (!Array.isArray(raw)) return [];
    return raw
      .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object' && typeof (r as { id?: unknown }).id === 'string')
      .map((r) => ({ id: r.id as string, name: typeof r.name === 'string' ? r.name : (r.id as string),
        perms: Array.isArray(r.perms) ? r.perms.filter((p): p is string => typeof p === 'string') : [] }));
  });
  const roleOf = (account: string) => {
    const role = projection.rows.space_member?.[`${spaceId}|${account}`]?.fields.role;
    return typeof role === 'string' ? role : 'member';
  };
  const people = $derived((info?.accounts ?? []).filter((a) => a.id !== info?.owner_account_id));
  const name = (a: { display_name: string | null; handle: string | null }) => a.display_name ?? `@${a.handle ?? '?'}`;

  function setRole(account: string, role: string) {
    sync.create('space.set_role', scope, spaceId, { account_id: account, role });
  }
  function saveRoles(roles: Role[]) {
    sync.create('space.set_roles', scope, spaceId, { roles });
  }
  let newRole = $state('');
  function addRole(e: SubmitEvent) {
    e.preventDefault();
    const label = newRole.trim();
    if (!label) return;
    saveRoles([...custom, { id: `r_${sync.newId().slice(-8)}`, name: label, perms: ['view', 'react'] }]);
    newRole = '';
  }
  function togglePerm(role: Role, perm: string, on: boolean) {
    saveRoles(custom.map((r) => (r.id === role.id ? { ...r, perms: on ? [...r.perms, perm] : r.perms.filter((p) => p !== perm) } : r)));
  }
</script>

<details class="space-roles">
  <summary>Space roles…</summary>
  <p class="hint">Channel permissions apply per role. Admins can do everything, like you.</p>
  <ul aria-label="Roles in this space">
    {#each people as a (a.id)}
      <li>
        <span>{name(a)}</span>
        <select aria-label="Role of {name(a)}" value={roleOf(a.id)} onchange={(e) => setRole(a.id, e.currentTarget.value)}>
          <option value="member">Member</option>
          <option value="admin">Admin</option>
          <option value="read_only">Read-only</option>
          {#each custom as r (r.id)}<option value={r.id}>{r.name}</option>{/each}
        </select>
      </li>
    {:else}
      <li class="hint">Nobody else is in this space yet.</li>
    {/each}
  </ul>
  <h4>Custom roles</h4>
  {#each custom as r (r.id)}
    <fieldset>
      <legend>{r.name}</legend>
      {#each PERMS as p (p.id)}
        <label><input type="checkbox" checked={r.perms.includes(p.id)} onchange={(e) => togglePerm(r, p.id, e.currentTarget.checked)} /> {p.label}</label>
      {/each}
      <button type="button" onclick={() => saveRoles(custom.filter((x) => x.id !== r.id))}>Remove</button>
    </fieldset>
  {/each}
  <form onsubmit={addRole}>
    <input bind:value={newRole} placeholder="new role" aria-label="New role name" />
    <button>Add role</button>
  </form>
</details>

<style>
  .space-roles { display: grid; gap: var(--s-2); }
  .space-roles summary { cursor: pointer; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-1); }
  li { display: flex; align-items: center; justify-content: space-between; gap: var(--s-2); }
  fieldset { border: 1px solid var(--line); border-radius: var(--r-sm); display: flex; flex-wrap: wrap; gap: var(--s-2); }
  h4 { margin: var(--s-2) 0 0; font-size: var(--fs-sm); }
  select, input { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1); }
  .hint { color: var(--ink-3); font-size: var(--fs-sm); margin: 0; }
</style>
