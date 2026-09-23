<script lang="ts">
  // Follows (M6.1): follow someone by handle; accept followers and choose how much they see.
  // Follows live in the followed account's scope, so accepting and the privacy preset are ordinary
  // ops; asking to follow, unfollowing and your own prefs go through /api/v1/follows.
  import { notifyPreset } from '../core/pkg/chorus_wasm.js';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';

  let { projection }: { projection: Projection } = $props();

  interface Person { id: string; handle: string | null; display_name: string | null; kind: string }
  interface FollowRow { id: string; account: Person; status: string; created_at: number }

  let following = $state<FollowRow[]>([]);
  let followers = $state<FollowRow[]>([]);
  let target = $state('');
  let error = $state('');
  let busy = $state(false);

  const PRESETS = [
    { id: 'close', label: 'Close', hint: 'right away, exact times, switch-outs too' },
    { id: 'gentle', label: 'Gentle', hint: '5–20 min later, time rounded to 15 min' },
    { id: 'private', label: 'Private', hint: '30–90 min later, only "this evening"' },
    { id: 'digest', label: 'Digest', hint: 'one summary a day' },
    { id: 'off', label: 'Off', hint: 'no switch notifications' },
  ] as const;
  const presetJson = Object.fromEntries(PRESETS.map((p) => [p.id, notifyPreset(p.id)]));

  async function api(path: string, init: RequestInit = {}) {
    const r = await fetch(`${apiBase()}${path}`, {
      ...init,
      headers: { 'content-type': 'application/json', authorization: `Bearer ${sync.device?.session ?? ''}` },
    });
    const text = await r.text();
    const j = text ? JSON.parse(text) : {};
    if (!r.ok) throw new Error(j?.error?.message ?? `HTTP ${r.status}`);
    return j;
  }

  async function load() {
    try {
      const j = await api('/follows');
      following = j.following;
      followers = j.followers;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }

  // reload when the synced follow rows change (a request arrived, another device accepted…)
  const followKey = $derived(JSON.stringify(projection.rows.follow ?? {}));
  $effect(() => {
    void followKey;
    const t = setTimeout(load, 150);
    return () => clearTimeout(t);
  });

  async function follow(e: SubmitEvent) {
    e.preventDefault();
    if (!target.trim()) return;
    busy = true;
    error = '';
    try {
      await api('/follows', { method: 'POST', body: JSON.stringify({ target: target.trim() }) });
      target = '';
      await load();
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }

  async function unfollow(id: string) {
    await api(`/follows/${id}`, { method: 'DELETE' });
    await load();
  }

  function ceilingOf(id: string): string {
    const c = projection.rows.follow?.[id]?.fields.ceiling;
    const s = JSON.stringify(c ?? {});
    return PRESETS.find((p) => JSON.stringify(JSON.parse(presetJson[p.id])) === s)?.id ?? (s === '{}' ? 'gentle' : 'custom');
  }

  function setPreset(id: string, preset: string) {
    sync.create('follow.set_ceiling', sync.accountScope, id, { ceiling: JSON.parse(presetJson[preset]) });
  }

  function accept(id: string, preset: string) {
    sync.create('follow.accept', sync.accountScope, id, {});
    setPreset(id, preset);
  }

  function remove(id: string) {
    sync.create('follow.end', sync.accountScope, id, {});
  }

  const name = (p: Person) => p.display_name ?? (p.handle ? `@${p.handle}` : 'Someone');
  let choice = $state<Record<string, string>>({});
</script>

<section class="page">
  <h1 class="display">People</h1>

  <form class="follow" onsubmit={follow}>
    <input bind:value={target} placeholder="@handle" aria-label="Handle to follow" autocomplete="off" />
    <button class="primary" disabled={busy}>Follow</button>
  </form>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if followers.some((f) => f.status === 'requested')}
    <h2>Requests</h2>
    <ul>
      {#each followers.filter((f) => f.status === 'requested') as f (f.id)}
        <li class="card">
          <div class="who"><strong>{name(f.account)}</strong><span>wants to follow you</span></div>
          <label class="preset">
            <span>They'll see switches</span>
            <select value={choice[f.id] ?? 'gentle'} onchange={(e) => (choice[f.id] = e.currentTarget.value)}>
              {#each PRESETS as p (p.id)}<option value={p.id}>{p.label} — {p.hint}</option>{/each}
            </select>
          </label>
          <div class="actions">
            <button class="primary" onclick={() => accept(f.id, choice[f.id] ?? 'gentle')}>Accept</button>
            <button class="ghost" onclick={() => remove(f.id)}>Decline</button>
          </div>
        </li>
      {/each}
    </ul>
  {/if}

  <h2>Followers</h2>
  <p class="hint">Delay hides <em>when</em> you switched as it happens; fuzzing hides the exact time afterwards.</p>
  <ul>
    {#each followers.filter((f) => f.status === 'active') as f (f.id)}
      {@const current = ceilingOf(f.id)}
      <li class="card">
        <div class="who"><strong>{name(f.account)}</strong>{#if f.account.handle}<span>@{f.account.handle}</span>{/if}</div>
        <label class="preset">
          <span>Sees switches</span>
          <select value={current} onchange={(e) => setPreset(f.id, e.currentTarget.value)}>
            {#if current === 'custom'}<option value="custom" disabled>Custom (Advanced)</option>{/if}
            {#each PRESETS as p (p.id)}<option value={p.id}>{p.label} — {p.hint}</option>{/each}
          </select>
        </label>
        <div class="actions"><button class="ghost" onclick={() => remove(f.id)}>Remove</button></div>
      </li>
    {:else}
      <li class="muted">No followers yet. Share your handle with friends.</li>
    {/each}
  </ul>

  <h2>Following</h2>
  <ul>
    {#each following as f (f.id)}
      <li class="card">
        <div class="who">
          <strong>{name(f.account)}</strong>
          <span>{f.status === 'requested' ? 'waiting for them to accept' : f.account.handle ? `@${f.account.handle}` : ''}</span>
        </div>
        <div class="actions">
          <button class="ghost" onclick={() => unfollow(f.id)}>{f.status === 'requested' ? 'Cancel' : 'Unfollow'}</button>
        </div>
      </li>
    {:else}
      <li class="muted">You're not following anyone yet.</li>
    {/each}
  </ul>
</section>

<style>
  .page { display: grid; gap: var(--s-4); }
  h1 { font-size: var(--fs-2xl); }
  h2 { font-size: var(--fs-sm); font-weight: 600; color: var(--ink-2); margin: var(--s-2) 0 0; }
  .follow { display: flex; gap: var(--s-2); }
  .follow input {
    flex: 1; font: inherit; color: var(--ink); background: var(--surface);
    border: 1px solid var(--line); border-radius: var(--r-full); padding: var(--s-2) var(--s-4);
  }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--s-2); }
  .card {
    display: flex; flex-wrap: wrap; gap: var(--s-3); align-items: center; justify-content: space-between;
    padding: var(--s-3) var(--s-4); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md);
  }
  .who { display: grid; }
  .who span, .preset span, .hint, .muted { color: var(--ink-3); font-size: var(--fs-sm); }
  .hint { margin: 0; }
  .preset { display: grid; gap: 2px; flex: 1; min-width: 14em; }
  select {
    font: inherit; font-size: var(--fs-sm); color: var(--ink); background: var(--surface-2);
    border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-1) var(--s-2);
  }
  .actions { display: flex; gap: var(--s-2); }
  .primary {
    background: var(--accent); color: var(--surface); border: 0; border-radius: var(--r-full);
    padding: var(--s-2) var(--s-4); font-weight: 600; cursor: pointer;
  }
  .ghost { background: none; border: 0; color: var(--accent); cursor: pointer; }
  .error { color: var(--danger); margin: 0; }
</style>
