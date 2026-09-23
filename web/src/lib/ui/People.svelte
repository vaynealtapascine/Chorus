<script lang="ts">
  // Follows (M6.1): follow someone by handle; accept followers and choose how much they see.
  // Follows live in the followed account's scope, so accepting and the privacy preset are ordinary
  // ops; asking to follow, unfollowing and your own prefs go through /api/v1/follows.
  import { notifyPreset } from '../core/pkg/chorus_wasm.js';
  import { bucketAssignments, buckets } from '../data';
  import { apiBase } from '../sync/device';
  import { sync, type Projection } from '../sync/client';
  import { fuzzyWhen, precisionOfRule, type Part, type Precision } from '../fuzz';
  import AvatarImage from './AvatarImage.svelte';

  let { projection }: { projection: Projection } = $props();

  interface Person { id: string; handle: string | null; display_name: string | null; kind: string; avatar_blob?: string | null }
  interface FollowRow { id: string; account: Person; status: string; created_at: number }

  interface ViewEntry { t: string; name: string; level: string; color?: string | null; glyph?: string | null; avatar_blob?: string | null }
  interface View { entries: ViewEntry[]; since: number | null; time?: { mode?: string }; shared?: boolean }
  interface Note {
    id: string;
    text: string;
    time: { at: number | null; precision: Precision; part?: Part | null };
    account: { handle: string | null; display_name: string | null };
  }

  let views = $state<Record<string, View>>({});
  let notes = $state<Note[]>([]);
  let seenNotes = new Set<string>();
  let canNotify = $state(typeof Notification !== 'undefined' && Notification.permission === 'granted');
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
  const ownBuckets = $derived(buckets(projection));
  const assignments = $derived(bucketAssignments(projection));
  const accountSettings = $derived((projection.rows.account?.[sync.accountId]?.fields.settings ?? {}) as Record<string, unknown>);
  const defaultCeiling = $derived((accountSettings.follow_ceiling ?? {}) as Record<string, unknown>);
  const defaultPreset = $derived(presetOf(defaultCeiling));
  let newBucket = $state('');
  let newBucketPreset = $state('gentle');

  function presetOf(ceiling: unknown): string {
    const raw = JSON.stringify(ceiling ?? {});
    return PRESETS.find((p) => JSON.stringify(JSON.parse(presetJson[p.id])) === raw)?.id ?? (raw === '{}' ? 'gentle' : 'custom');
  }

  function setDefault(preset: string) {
    sync.create('account.set', sync.accountScope, sync.accountId, {
      settings: { ...accountSettings, follow_ceiling: JSON.parse(presetJson[preset]) },
    });
  }

  function addBucket(e: SubmitEvent) {
    e.preventDefault();
    if (!newBucket.trim()) return;
    sync.create('bucket.set', sync.accountScope, sync.newId(), {
      name: newBucket.trim(), ceiling: JSON.parse(presetJson[newBucketPreset]),
    });
    newBucket = '';
  }

  function assign(bucketId: string, followerId: string, present: boolean) {
    sync.create(present ? 'bucket.assign' : 'bucket.unassign', sync.accountScope, bucketId, {
      follower_account_id: followerId,
    });
  }

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
      await loadViews();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }
  }

  /** What we may see of the accounts we follow, and our switch notifications (polled). */
  async function loadViews() {
    const next: Record<string, View> = {};
    for (const f of following.filter((x) => x.status === 'active')) {
      try {
        next[f.account.id] = await api(`/accounts/${f.account.id}/view`);
      } catch {
        /* not shared (yet) */
      }
    }
    views = next;
    const j = await api('/notifications');
    const fresh = (j.items as Note[]).filter((n) => !seenNotes.has(n.id));
    // a desktop notification for anything new while this page is open (not on first load)
    if (seenNotes.size && canNotify && document.hidden) {
      for (const n of fresh) new Notification(n.account.display_name ?? 'Chorus', { body: n.text, tag: n.id });
    }
    for (const n of j.items as Note[]) seenNotes.add(n.id);
    notes = j.items;
  }

  $effect(() => {
    const t = setInterval(() => void loadViews().catch(() => {}), 30_000);
    return () => clearInterval(t);
  });

  async function enableNotifications() {
    canNotify = (await Notification.requestPermission()) === 'granted';
  }

  function viewLine(v: View | undefined): string {
    if (!v) return '';
    if (v.shared === false) return "They don't share who's fronting";
    const front = v.entries.filter((e) => e.level === 'front').map((e) => e.name);
    if (!front.length) return v.since == null ? 'Nothing shared yet' : 'No one fronting';
    const who = front.length > 1 ? `${front.slice(0, -1).join(', ')} & ${front.at(-1)}` : front[0];
    const when = fuzzyWhen(v.since, precisionOfRule(v.time)).replace(/^at /, '');
    return `${who} ${front.length > 1 ? 'are' : 'is'} fronting${when ? ` · since ${when}` : ''}`;
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
    return s === '{}' ? 'inherit' : presetOf(c);
  }

  function setPreset(id: string, preset: string) {
    sync.create('follow.set_ceiling', sync.accountScope, id, { ceiling: preset === 'inherit' ? {} : JSON.parse(presetJson[preset]) });
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
        <span class="person-avatar"><AvatarImage hash={f.account.avatar_blob} glyph={(f.account.display_name ?? f.account.handle ?? '?')[0]} name={name(f.account)} /></span>
        <div class="who"><strong>{name(f.account)}</strong><span>wants to follow you</span></div>
          <label class="preset">
            <span>They'll see switches</span>
            <select value={choice[f.id] ?? 'inherit'} onchange={(e) => (choice[f.id] = e.currentTarget.value)}>
              <option value="inherit">Default ({defaultPreset})</option>
              {#each PRESETS as p (p.id)}<option value={p.id}>{p.label} — {p.hint}</option>{/each}
            </select>
          </label>
          <div class="actions">
            <button class="primary" onclick={() => accept(f.id, choice[f.id] ?? 'inherit')}>Accept</button>
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
        <span class="person-avatar"><AvatarImage hash={f.account.avatar_blob} glyph={(f.account.display_name ?? f.account.handle ?? '?')[0]} name={name(f.account)} /></span>
        <div class="who"><strong>{name(f.account)}</strong>{#if f.account.handle}<span>@{f.account.handle}</span>{/if}</div>
        <label class="preset">
          <span>Sees switches</span>
          <select value={current} onchange={(e) => setPreset(f.id, e.currentTarget.value)}>
            {#if current === 'custom'}<option value="custom" disabled>Custom (Advanced)</option>{/if}
            <option value="inherit">Default ({defaultPreset})</option>
            {#each PRESETS as p (p.id)}<option value={p.id}>{p.label} — {p.hint}</option>{/each}
          </select>
        </label>
        {#if ownBuckets.length}
          <div class="bucket-checks" aria-label={`Buckets for ${name(f.account)}`}>
            {#each ownBuckets as bucket (bucket.id)}
              <label><input type="checkbox" checked={assignments.get(bucket.id)?.has(f.account.id) ?? false}
                onchange={(e) => assign(bucket.id, f.account.id, e.currentTarget.checked)} /> {bucket.name}</label>
            {/each}
          </div>
        {/if}
        <div class="actions"><button class="ghost" onclick={() => remove(f.id)}>Remove</button></div>
      </li>
    {:else}
      <li class="muted">No followers yet. Share your handle with friends.</li>
    {/each}
  </ul>

  <section class="bucket-section" aria-label="Sharing buckets">
    <h2>Sharing buckets</h2>
    <p class="hint">Group followers to choose what each group can hear. A follower in several buckets gets the most open setting for each detail.</p>
    <form class="bucket-new" onsubmit={addBucket}>
      <input bind:value={newBucket} placeholder="Bucket name" aria-label="New bucket name" />
      <select bind:value={newBucketPreset} aria-label="New bucket ceiling">
        {#each PRESETS as p (p.id)}<option value={p.id}>{p.label}</option>{/each}
      </select>
      <button class="primary">Create bucket</button>
    </form>
    {#each ownBuckets as bucket (bucket.id)}
      {@const preset = presetOf(bucket.ceiling)}
      <div class="card bucket-row">
        <label>Name <input value={bucket.name} aria-label={`Rename ${bucket.name}`} onchange={(e) => {
          const name = e.currentTarget.value.trim();
          if (name && name !== bucket.name) sync.create('bucket.set', sync.accountScope, bucket.id, { name });
        }} /></label>
        <label>Ceiling <select value={preset} aria-label={`Ceiling for ${bucket.name}`} onchange={(e) => sync.create('bucket.set', sync.accountScope, bucket.id, {
          ceiling: JSON.parse(presetJson[e.currentTarget.value]),
        })}>
          {#if preset === 'custom'}<option value="custom" disabled>Custom (Advanced)</option>{/if}
          {#each PRESETS as p (p.id)}<option value={p.id}>{p.label}</option>{/each}
        </select></label>
        <button class="ghost" onclick={() => sync.create('bucket.delete', sync.accountScope, bucket.id, {})}>Delete</button>
      </div>
    {/each}
    <details>
      <summary>Advanced sharing default</summary>
      <label>Default ceiling for new followers
        <select value={defaultPreset} aria-label="Default follower ceiling" onchange={(e) => setDefault(e.currentTarget.value)}>
          {#if defaultPreset === 'custom'}<option value="custom" disabled>Custom</option>{/if}
          {#each PRESETS as p (p.id)}<option value={p.id}>{p.label} — {p.hint}</option>{/each}
        </select>
      </label>
    </details>
  </section>

  <h2>Following</h2>
  <ul>
    {#each following as f (f.id)}
      <li class="card">
        <span class="person-avatar"><AvatarImage hash={f.account.avatar_blob} glyph={(f.account.display_name ?? f.account.handle ?? '?')[0]} name={name(f.account)} /></span>
        <div class="who">
          <strong>{name(f.account)}</strong>
          <span>{f.status === 'requested' ? 'waiting for them to accept' : viewLine(views[f.account.id]) || (f.account.handle ? `@${f.account.handle}` : '')}</span>
          {#if views[f.account.id]?.entries?.length}
            <span class="front-avatars">
              {#each views[f.account.id].entries.filter((e) => e.t === 'subject') as e, i (`${e.name}:${i}`)}
                <span class="front-avatar" title={e.name}><AvatarImage hash={e.avatar_blob} glyph={e.glyph ?? e.name[0]} name={e.name} /></span>
              {/each}
            </span>
          {/if}
        </div>
        <div class="actions">
          <button class="ghost" onclick={() => unfollow(f.id)}>{f.status === 'requested' ? 'Cancel' : 'Unfollow'}</button>
        </div>
      </li>
    {:else}
      <li class="muted">You're not following anyone yet.</li>
    {/each}
  </ul>

  {#if notes.length || following.length}
    <div class="notes-head">
      <h2>Recent switches</h2>
      {#if typeof Notification !== 'undefined' && !canNotify}
        <button class="ghost" onclick={enableNotifications}>Notify me in this browser</button>
      {/if}
    </div>
    <ul>
      {#each notes as n (n.id)}
        <li class="note">
          <strong>{n.account.display_name ?? `@${n.account.handle}`}</strong>
          <span>{n.text}</span>
          <time>{fuzzyWhen(n.time.at, n.time.precision, n.time.part)}</time>
        </li>
      {:else}
        <li class="muted">Nothing yet. Switches arrive after the delay each system chose.</li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .person-avatar, .front-avatar { display: grid; place-items: center; overflow: hidden; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); flex: none; }
  .person-avatar { width: 40px; height: 40px; }
  .front-avatar { width: 28px; height: 28px; }
  .front-avatars { display: flex; gap: var(--s-1); margin-top: var(--s-1); }
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
  .bucket-section { display: grid; gap: var(--s-2); }
  .bucket-new, .bucket-row, .bucket-checks { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); }
  .bucket-new input, .bucket-row input { font: inherit; min-width: 10ch; padding: var(--s-1) var(--s-2); color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); }
  .bucket-row label { display: grid; gap: 2px; }
  .bucket-checks { width: 100%; font-size: var(--fs-sm); }
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
  .notes-head { display: flex; justify-content: space-between; align-items: baseline; }
  .note { display: flex; flex-wrap: wrap; gap: var(--s-2); align-items: baseline; padding: var(--s-2) 0; border-bottom: 1px solid var(--line); }
  .note time { margin-left: auto; color: var(--ink-3); font-size: var(--fs-sm); }
</style>
