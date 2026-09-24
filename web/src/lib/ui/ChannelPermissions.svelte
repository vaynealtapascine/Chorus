<script lang="ts">
  // Channel permissions (Advanced; D-047, server rule in perms.rs): overrides for everyone, a
  // role, or one account. Letting an account outside the space view a channel shares just that
  // channel with them (how one internal channel is shared with a partner).
  import { apiBase } from '../sync/device';
  import { apiFetch } from '../http';
  import { sync, type Projection } from '../sync/client';
  import type { SpaceInfo } from '../spaces';

  let { projection, channelId, spaceId, info }: {
    projection: Projection; channelId: string; spaceId: string; info: SpaceInfo | undefined;
  } = $props();

  const PERMS = [
    { id: 'view', label: 'See it' }, { id: 'send', label: 'Write' }, { id: 'react', label: 'React' },
    { id: 'thread', label: 'Start threads' }, { id: 'pin', label: 'Pin' }, { id: 'manage', label: 'Manage' },
  ] as const;
  type Choice = 'inherit' | 'allow' | 'deny';
  interface Person { id: string; handle: string | null; display_name: string | null }

  const customRoles = $derived.by(() => {
    const raw = projection.rows.space?.[spaceId]?.fields.roles;
    return Array.isArray(raw) ? raw.filter((r): r is { id: string; name?: string } => !!r && typeof r === 'object' && typeof (r as { id?: unknown }).id === 'string') : [];
  });
  // people you're connected to (to share with), and everyone already in the space
  let connections = $state<Person[]>([]);
  $effect(() => {
    apiFetch(`${apiBase()}/follows`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } })
      .then((r) => (r.ok ? r.json() : { following: [], followers: [] }))
      .then((j: { following: { status: string; account: Person }[]; followers: { status: string; account: Person }[] }) => {
        const seen = new Map<string, Person>();
        for (const f of [...j.following, ...j.followers]) if (f.status === 'active') seen.set(f.account.id, f.account);
        connections = [...seen.values()];
      })
      .catch(() => {});
  });
  const people = $derived.by(() => {
    const all = new Map<string, Person>();
    for (const a of info?.accounts ?? []) if (a.id !== sync.accountId) all.set(a.id, a);
    for (const a of connections) if (!all.has(a.id)) all.set(a.id, a);
    return [...all.values()];
  });
  const personName = (id: string) => {
    const p = people.find((x) => x.id === id);
    return p ? (p.display_name ?? `@${p.handle}`) : 'someone';
  };
  const roleName = (id: string) =>
    id === 'everyone' ? 'Everyone' : id === 'member' ? 'Members' : id === 'read_only' ? 'Read-only' : id === 'admin' ? 'Admins'
      : (customRoles.find((r) => r.id === id)?.name ?? id);

  const overrides = $derived(
    Object.entries(projection.rows.channel_permission ?? {})
      .filter(([key, row]) => key.startsWith(`${channelId}|`) && row.exists)
      .map(([key, row]) => {
        const [, type, target] = key.split('|');
        const list = (v: unknown) => (Array.isArray(v) ? v.map(String) : []);
        return { type, target, allow: list(row.fields.allow), deny: list(row.fields.deny) };
      })
      .filter((o) => o.allow.length || o.deny.length),
  );

  let target = $state('role:everyone');
  let choices = $state<Record<string, Choice>>({});
  $effect(() => {
    // editing an existing override starts from what it says
    const [type, id] = target.split(':');
    const found = overrides.find((o) => o.type === type && o.target === id);
    choices = Object.fromEntries(PERMS.map((p) => [p.id, found?.deny.includes(p.id) ? 'deny' : found?.allow.includes(p.id) ? 'allow' : 'inherit']));
  });
  const outsider = $derived(target.startsWith('account:') && !(info?.accounts ?? []).some((a) => a.id === target.slice(8)));

  function save(type: string, id: string, allow: string[], deny: string[]) {
    sync.create('channel.set_permission', `space:${spaceId}`, channelId, { target_type: type, target_id: id, allow, deny });
  }
  function submit(e: SubmitEvent) {
    e.preventDefault();
    const [type, id] = target.split(':');
    save(type, id, PERMS.filter((p) => choices[p.id] === 'allow').map((p) => p.id), PERMS.filter((p) => choices[p.id] === 'deny').map((p) => p.id));
  }
</script>

<details class="perms">
  <summary>Permissions</summary>
  <p class="hint">A person's own setting beats their role's, which beats Everyone's; at one level, deny beats allow. The space's owner and admins can always do everything.</p>
  {#if overrides.length}
    <ul>
      {#each overrides as o (`${o.type}|${o.target}`)}
        <li>
          <strong>{o.type === 'account' ? personName(o.target) : roleName(o.target)}</strong>
          {#if o.allow.length}<span class="allow">can {o.allow.join(', ')}</span>{/if}
          {#if o.deny.length}<span class="deny">can't {o.deny.join(', ')}</span>{/if}
          <button type="button" onclick={() => save(o.type, o.target, [], [])} aria-label={`Clear the setting for ${o.type === 'account' ? personName(o.target) : roleName(o.target)}`}>Clear</button>
        </li>
      {/each}
    </ul>
  {:else}
    <p class="hint">No overrides: everyone in the space has their role's permissions here.</p>
  {/if}
  <form onsubmit={submit}>
    <label>For
      <select bind:value={target} aria-label="Permission target">
        <option value="role:everyone">Everyone in the space</option>
        <option value="role:member">Members</option>
        <option value="role:read_only">Read-only members</option>
        {#each customRoles as r (r.id)}<option value={`role:${r.id}`}>{r.name ?? r.id}</option>{/each}
        {#each people as p (p.id)}<option value={`account:${p.id}`}>{p.display_name ?? `@${p.handle}`}</option>{/each}
      </select>
    </label>
    <div class="grid">
      {#each PERMS as p (p.id)}
        <label>{p.label}
          <select bind:value={choices[p.id]} aria-label={`${p.label} permission`}>
            <option value="inherit">—</option><option value="allow">Allow</option><option value="deny">Deny</option>
          </select>
        </label>
      {/each}
    </div>
    {#if outsider && choices.view === 'allow'}<p class="hint">They're not in this space: this shares just this channel with them.</p>{/if}
    <button class="primary">Save</button>
  </form>
</details>

<style>
  .perms { display: grid; gap: var(--s-2); padding: var(--s-2) var(--s-3); font-size: var(--fs-sm); border-top: 1px solid var(--line); }
  summary { cursor: pointer; color: var(--ink-2); }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-1); }
  li { display: flex; flex-wrap: wrap; gap: var(--s-2); align-items: baseline; }
  .allow { color: var(--ink-2); }
  .deny { color: var(--danger); }
  .hint { margin: 0; color: var(--ink-3); }
  form { display: grid; gap: var(--s-2); }
  .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--s-1) var(--s-2); }
  .grid label, form > label { display: grid; gap: 2px; color: var(--ink-2); }
  select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); padding: 2px var(--s-1); }
  button { font: inherit; cursor: pointer; background: none; border: 0; color: var(--accent); }
  .primary { justify-self: start; background: var(--accent); color: var(--surface); border-radius: var(--r-full); padding: var(--s-1) var(--s-3); }
</style>
