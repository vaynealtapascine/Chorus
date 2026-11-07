<script lang="ts">
  import { onDestroy } from 'svelte';
  import { core } from '../core';
  import BucketLabel from './BucketLabel.svelte';
  import { buckets, fieldDefs, fieldValues, groupPath, groups, members, membership, selfMember, type ProxyTag } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import ProfileFields from './ProfileFields.svelte';
  import { flushUploads, stageBlob } from '../sync/uploads';
  import { addFolder, FOLDER_OF, FOLDERS, memberListPrefs } from '../member-list';
  import { claimedHere, MAX_CLAIMS, memberHandles, normalizeClaim, validClaim } from '../handles';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();

  const all = $derived(members(projection));
  const m = $derived(all.find((x) => x.id === id));
  const person = $derived(!!selfMember(projection));
  const raw = $derived((projection.rows.member?.[id]?.fields ?? {}) as Record<string, unknown>);
  const gs = $derived(groups(projection));
  const inGroup = $derived(membership(projection));
  const sharingBuckets = $derived(buckets(projection));
  const defs = $derived(fieldDefs(projection));
  const values = $derived(fieldValues(projection).get(id) ?? new Map());
  const colors = $derived(m ? core.adaptColor(m.color, dark) : null);

  // when this member's mentions and member DMs ping the system (NOTIFICATIONS §7), a pref the server reads
  const notifyRule = $derived.by(() => {
    const rows = projection.rows.pref ?? {};
    const key = `notify_member:${id}`;
    const v = (rows[`${sync.accountId}||${key}`] ?? rows[`||${key}`])?.fields.value;
    return (v && typeof v === 'object' ? v : {}) as { mentions?: string; dms?: string };
  });
  function setNotifyRule(which: 'mentions' | 'dms', rule: string) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: `notify_member:${id}`, value: { ...notifyRule, [which]: rule } });
  }

  let cropBitmap: ImageBitmap | null = null;
  let cropReady = $state(false);
  let cropCanvas: HTMLCanvasElement | undefined = $state();
  let cropZoom = $state(1);
  let cropX = $state(0);
  let cropY = $state(0);
  let avatarError = $state('');
  onDestroy(() => cropBitmap?.close());
  async function chooseAvatar(e: Event) {
    const file = (e.currentTarget as HTMLInputElement).files?.[0];
    if (!file) return;
    try {
      cropBitmap?.close();
      cropBitmap = await createImageBitmap(file);
      cropZoom = 1; cropX = 0; cropY = 0;
      cropReady = true;
      avatarError = '';
    } catch { avatarError = 'This image could not be opened.'; }
  }
  $effect(() => {
    void cropReady;
    const bitmap = cropBitmap;
    const canvas = cropCanvas;
    if (!bitmap || !canvas) return;
    const scale = Math.max(256 / bitmap.width, 256 / bitmap.height) * cropZoom;
    const width = bitmap.width * scale;
    const height = bitmap.height * scale;
    const dx = (256 - width) / 2 + cropX * (width - 256) / 2;
    const dy = (256 - height) / 2 + cropY * (height - 256) / 2;
    const ctx = canvas.getContext('2d');
    ctx?.clearRect(0, 0, 256, 256);
    ctx?.drawImage(bitmap, dx, dy, width, height);
  });
  async function saveAvatar() {
    if (!cropCanvas) return;
    try {
      const blob = await new Promise<Blob | null>((resolve) => cropCanvas!.toBlob(resolve, 'image/webp', 0.9));
      if (!blob) throw new Error('Could not encode cropped image');
      const upload = await stageBlob(blob, blob.type, sync.accountId);
      set({ avatar_blob: upload.hash });
      if (sync.status === 'live') void flushUploads(sync.device);
      cropBitmap?.close(); cropBitmap = null; cropReady = false;
      avatarError = '';
    } catch (error) { avatarError = String(error); }
  }

  /** Send only fields that changed (field-level LWW merges them with other devices' edits). */
  function set(fields: Record<string, unknown>) {
    const changed = Object.fromEntries(
      Object.entries(fields).filter(([k, v]) => JSON.stringify(raw[k] ?? null) !== JSON.stringify(v ?? null)),
    );
    if (Object.keys(changed).length) sync.create('member.set', sync.accountScope, id, changed);
  }

  const text = (e: Event) => (e.currentTarget as HTMLInputElement).value;


  const sigilText = $derived(m?.sigils.join(' ') ?? '');
  const sigilClash = $derived(
    (m?.sigils ?? []).filter((s) => all.some((o) => o.id !== id && !o.deleted && o.sigils.includes(s))),
  );

  let tags = $state<ProxyTag[]>([]);
  $effect(() => {
    tags = m?.proxy_tags.map((t) => ({ ...t })) ?? [];
  });
  function saveTags() {
    set({ proxy_tags: tags.filter((t) => t.prefix || t.suffix) });
  }

  // handles (D-126): the default is theirs for good; claims are checked by the server, which
  // says so (a sync issue) if another account took one first
  const handles = $derived(memberHandles(projection, sync.accountId).get(id));
  let claim = $state('');
  let claimError = $state('');
  function addClaim() {
    const h = normalizeClaim(claim);
    const list = m?.handles ?? [];
    if (!h) return;
    if (!validClaim(h)) { claimError = 'Use 2–32 lowercase letters, digits or _.'; return; }
    if (list.includes(h)) { claimError = `@${h} is already theirs.`; return; }
    const other = claimedHere(projection, sync.accountId, h, id);
    if (other) { claimError = `@${h} belongs to ${other}.`; return; }
    if (list.length >= MAX_CLAIMS) { claimError = `A member can claim up to ${MAX_CLAIMS} handles.`; return; }
    set({ handles: [...list, h] });
    claim = '';
    claimError = '';
  }
  function moveClaim(h: string, by: number) {
    const list = [...(m?.handles ?? [])];
    const i = list.indexOf(h);
    const j = i + by;
    if (i < 0 || j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    set({ handles: list });
  }

  function toggleGroup(gid: string, on: boolean) {
    sync.create(on ? 'group.add_member' : 'group.remove_member', sync.accountScope, gid, { member_id: id });
  }


  // which Members list folder they sit in (D-100): only sorts the list
  const listPrefs = $derived(memberListPrefs((projection.rows.pref ?? {}) as never, sync.accountId));
  function setFolder(value: string) {
    let folder: string | null = value || null;
    if (value === '__new') {
      const name = prompt('New folder name')?.trim();
      if (!name) return;
      folder = sync.newId();
      sync.create('pref.set', sync.accountScope, null, { device: '', key: FOLDERS, value: addFolder(listPrefs.folders, folder, name) });
    }
    sync.create('pref.set', sync.accountScope, null, { device: '', key: FOLDER_OF + id, value: folder });
  }

  // who hears when this member fronts (NOTIFICATIONS.md §2.4; "nobody" hides them from follower views)
  const announce = $derived.by(() => {
    const p = raw.notify_policy as { announce?: unknown } | undefined;
    return p?.announce === 'nobody' ? 'nobody' : typeof p?.announce === 'object' ? 'buckets' : 'everyone';
  });
  const announceBucketIds = $derived.by(() => {
    const p = raw.notify_policy as { announce?: { buckets?: unknown } } | undefined;
    return Array.isArray(p?.announce?.buckets) ? p.announce.buckets.filter((id): id is string => typeof id === 'string') : [];
  });
  function setAnnounce(v: string) {
    const p = (raw.notify_policy as Record<string, unknown> | undefined) ?? {};
    set({ notify_policy: { ...p, announce: v === 'buckets' ? { buckets: announceBucketIds } : v } });
  }
  function setAnnounceBucket(bucketId: string, enabled: boolean) {
    const p = (raw.notify_policy as Record<string, unknown> | undefined) ?? {};
    const ids = new Set(announceBucketIds);
    if (enabled) ids.add(bucketId); else ids.delete(bucketId);
    set({ notify_policy: { ...p, announce: { buckets: [...ids] } } });
  }


  function switchIn() {
    if (person) return;
    sync.create('front.switch', sync.accountScope, sync.newId(), {
      entries: [{ subject_type: 'member', subject_id: id, level: 'front', is_primary: true }],
    });
    router.go('/');
  }
</script>

{#if !m}
  <p class="muted">This member doesn't exist (yet). <a href="#/members">Back to members</a></p>
{:else}
  <article class="editor" style="--ring: {colors?.ring}; --tint: {colors?.tint}">
    <header>
      <a class="back" href={person ? '#/settings' : '#/members'} aria-label={person ? 'Back to settings' : 'Back to members'}>‹ {person ? 'Settings' : 'Members'}</a>
      <div class="identity">
        <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
        <div>
          <h1 class="display" style="color: {colors?.name}">{m.display_name ?? m.name}</h1>
          <p class="muted">{m.pronouns ?? ''}{m.archived ? ' · archived' : ''}{m.deleted ? ' · in Trash' : ''}</p>
        </div>
      </div>
      <a class="back" href="#/profile/{id}">View profile</a>
      {#if !person}<button class="primary" onclick={switchIn}>Switch in</button>{/if}
    </header>

    <section>
      <h2>Colour &amp; avatar</h2>
      <div class="fields">
        <div class="wide avatar-picker">
          <label>Avatar <input type="file" accept="image/*" onchange={chooseAvatar} aria-label="Choose member avatar" /></label>
          {#if cropReady}
            <div class="crop-controls">
              <canvas width="256" height="256" bind:this={cropCanvas} aria-label="Avatar crop preview"></canvas>
              <label>Zoom <input type="range" min="1" max="3" step="0.05" bind:value={cropZoom} /></label>
              <label>Left/right <input type="range" min="-1" max="1" step="0.05" bind:value={cropX} /></label>
              <label>Up/down <input type="range" min="-1" max="1" step="0.05" bind:value={cropY} /></label>
              <button class="primary" onclick={saveAvatar}>Use this crop</button>
            </div>
          {/if}
          {#if avatarError}<p role="alert">{avatarError}</p>{/if}
        </div>
        <label class="color">Colour <input type="color" value={m.color} onchange={(e) => set({ color: text(e) })} /></label>
      </div>
      <p class="hint">Colour and avatar show wherever {m.display_name ?? m.name} does: in your chats, and to friends who can open their profile or see them in a shared chat. Friends call them by their display name; the full name stays with you unless you share it below.</p>
    </section>

    <section aria-labelledby="handles-heading">
      <h2 id="handles-heading">Handles</h2>
      {#if handles}
        <ul class="handles">
          {#each m.handles ?? [] as h, i (h)}
            <li>
              <span class="h">@{h}</span>
              {#if i === 0}<span class="shown">Shown</span>{/if}
              <span class="handle-actions">
                {#if i > 0}<button class="ghost" onclick={() => moveClaim(h, -1)} aria-label="Show @{h} first">Show first</button>{/if}
                <button class="ghost" onclick={() => set({ handles: (m!.handles ?? []).filter((x) => x !== h) })} aria-label="Let go of @{h}">Remove</button>
              </span>
            </li>
          {/each}
          <li>
            <span class="h">@{handles.default}</span>
            {#if !(m.handles ?? []).length}<span class="shown">Shown</span>{/if}
            <span class="hint">{person ? 'your account' : 'reserved for them'}</span>
          </li>
        </ul>
      {/if}
      {#if (m.handles ?? []).length < MAX_CLAIMS}
        <form class="claim" onsubmit={(e) => { e.preventDefault(); addClaim(); }}>
          <label>Claim a handle <input bind:value={claim} placeholder={(m.display_name ?? m.name).toLowerCase().replace(/[^a-z0-9_]/g, '')} autocomplete="off" spellcheck="false" maxlength="33" /></label>
          <button class="primary" disabled={!claim.trim()}>Claim</button>
        </form>
      {/if}
      {#if claimError}<p class="warn" role="alert">{claimError}</p>{/if}
      <p class="hint">
        {#if person}
          @{handles?.default ?? ''} is your account's handle. A handle you claim is yours alone on this server; the first one is what friends see.
        {:else}
          @{handles?.default ?? ''} is made from their display name, the subsystems they sit in (innermost first) and your system, and stays reserved for them.
          A claimed handle is theirs alone on this server; the first one is what friends see, so claiming one also keeps their subsystems out of sight.
        {/if}
      </p>
    </section>

    <section>
      <ProfileFields {m} {raw} {defs} {values} buckets={sharingBuckets} {set} />
    </section>

    {#if !person}
    <section>
      <h2>Fronting</h2>
      <div class="fields">
        <label class="wide">
          Followers hear when {m.name} fronts
          <select value={announce} onchange={(e) => setAnnounce(e.currentTarget.value)}>
            <option value="everyone">Yes, as your follower settings allow</option>
            {#if sharingBuckets.length}<option value="buckets">Only selected buckets</option>{/if}
            <option value="nobody">No, keep {m.name} private</option>
          </select>
        </label>
        {#if announce === 'buckets'}
          <div class="wide checks" aria-label={`Announce ${m.name} to buckets`}>
            {#each sharingBuckets as bucket (bucket.id)}
              <label class="check"><input type="checkbox" checked={announceBucketIds.includes(bucket.id)} onchange={(e) => setAnnounceBucket(bucket.id, e.currentTarget.checked)} /> <BucketLabel {bucket} /></label>
            {/each}
            {#if !sharingBuckets.length}<span class="hint">Create a sharing bucket on the People page first.</span>{/if}
          </div>
        {/if}
      </div>
    </section>
    <section>
      <h2>Speaking</h2>
      <div class="fields">
        <label class="wide">
          Sigils <span class="hint">representative characters or emoji; separate alternatives with spaces. Start a message with one or more members' sigils, then a space.</span>
          <input
            value={sigilText}
            onchange={(e) => set({ sigils: text(e).split(/\s+/).filter(Boolean) })}
            placeholder="🌌"
          />
          {#if sigilClash.length}<span class="warn">Also used by someone else: {sigilClash.join(' ')}</span>{/if}
        </label>
        <div class="wide">
          <span class="label">Proxy tags <span class="hint">like PluralKit: <code>k:</code> or <code>[</code>…<code>]</code></span></span>
          {#each tags as t, i (i)}
            <div class="tag">
              <input bind:value={t.prefix} placeholder="prefix" onchange={saveTags} aria-label="Prefix" />
              <span class="muted">text</span>
              <input bind:value={t.suffix} placeholder="suffix" onchange={saveTags} aria-label="Suffix" />
              <button class="ghost" onclick={() => { tags = tags.filter((_, j) => j !== i); saveTags(); }}>Remove</button>
            </div>
          {/each}
          <button class="ghost" onclick={() => (tags = [...tags, { prefix: '', suffix: '' }])}>Add proxy tag</button>
        </div>
      </div>
    </section>

    <section>
      <h2>Chat notifications</h2>
      <div class="fields">
        <label>
          When someone mentions {m.name}
          <select value={notifyRule.mentions ?? 'always'} onchange={(e) => setNotifyRule('mentions', text(e))}>
            <option value="always">always ping</option>
            <option value="fronting">only while {m.name} is fronting</option>
            <option value="never">never ping</option>
          </select>
        </label>
        <label>
          Member DMs to {m.name}
          <select value={notifyRule.dms ?? 'fronting'} onchange={(e) => setNotifyRule('dms', text(e))}>
            <option value="always">always ping</option>
            <option value="fronting">only while {m.name} is fronting</option>
            <option value="never">never ping</option>
          </select>
        </label>
      </div>
      <p class="hint">Pings go to your other devices, never the one you wrote on. "Fronting" includes co-con.</p>
    </section>

    <section>
      <h2>Members list</h2>
      <div class="fields">
        <label>
          Folder
          <select value={listPrefs.folderOf.get(id) ?? ''} onchange={(e) => { setFolder(text(e)); e.currentTarget.value = listPrefs.folderOf.get(id) ?? ''; }}>
            <option value="">Not in a folder</option>
            {#each listPrefs.folders as f (f.id)}<option value={f.id}>{f.name}</option>{/each}
            <option value="__new">New folder…</option>
          </select>
        </label>
      </div>
      <p class="hint">Folders only sort the Members list; they don't change groups, fronting or who sees what.</p>
    </section>

    {#if gs.length}
      <section>
        <h2>Subsystems &amp; groups</h2>
        <div class="checks">
          {#each gs as g (g.id)}
            <label class="check">
              <input type="checkbox" checked={inGroup.get(g.id)?.has(id) ?? false} onchange={(e) => toggleGroup(g.id, (e.currentTarget as HTMLInputElement).checked)} />
              {groupPath(g, gs)}
            </label>
          {/each}
        </div>
      </section>
    {/if}


    <section class="danger">
      {#if m.archived}
        <button class="ghost" onclick={() => sync.create('member.unarchive', sync.accountScope, id, {})}>Unarchive</button>
      {:else}
        <button class="ghost" onclick={() => sync.create('member.archive', sync.accountScope, id, {})}>Archive</button>
      {/if}
      {#if m.deleted}
        <button class="ghost" onclick={() => sync.create('member.restore', sync.accountScope, id, {})}>Restore from Trash</button>
      {:else}
        <button class="ghost del" onclick={() => sync.create('member.delete', sync.accountScope, id, {})}>Move to Trash</button>
      {/if}
      <span class="hint">Nothing is ever erased; Trash can always restore (D-053).</span>
    </section>
    {/if}
  </article>
{/if}

<style>
  .avatar-picker { display: grid; gap: var(--s-2); }
  .handles { list-style: none; margin: 0 0 var(--s-3); padding: 0; display: grid; }
  .handles li { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-2) 0; border-top: 1px solid var(--line); min-height: 44px; flex-wrap: wrap; }
  .handles .h { font-weight: 600; overflow-wrap: anywhere; }
  .handles .shown { font-size: var(--fs-xs); color: var(--accent); background: var(--accent-soft); border-radius: var(--r-full); padding: 1px 8px; }
  .handle-actions { margin-left: auto; display: flex; gap: var(--s-1); }
  .claim { display: flex; align-items: flex-end; gap: var(--s-2); flex-wrap: wrap; }
  .claim label { display: grid; gap: var(--s-1); flex: 1 1 220px; }
  .crop-controls { display: grid; gap: var(--s-2); justify-items: start; }
  .crop-controls canvas { width: 192px; height: 192px; border-radius: var(--r-md); border: 1px solid var(--line); }
  .crop-controls label { display: flex; align-items: center; gap: var(--s-2); }
  .editor {
    display: grid;
    gap: var(--s-6);
  }
  header {
    display: grid;
    gap: var(--s-4);
  }
  .back {
    color: var(--ink-2);
    text-decoration: none;
    font-size: var(--fs-sm);
  }
  .identity {
    display: flex;
    gap: var(--s-4);
    align-items: center;
  }
  .avatar {
    width: 72px;
    height: 72px;
    display: grid;
    place-items: center;
    font-size: 34px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 3px var(--ring), 0 0 0 6px var(--bg);
  }
  h1 {
    font-size: var(--fs-2xl);
  }
  h2 {
    font-size: var(--fs-sm);
    font-weight: 600;
    color: var(--ink-2);
    margin-bottom: var(--s-3);
  }
  section {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
    padding: var(--s-5);
  }
  .fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: var(--s-4);
  }
  label,
  .label {
    display: grid;
    gap: var(--s-1);
    font-size: var(--fs-sm);
    color: var(--ink-2);
  }
  .wide {
    grid-column: 1 / -1;
  }
  input,
  select {
    font: inherit;
    font-size: var(--fs-base);
    color: var(--ink);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-2) var(--s-3);
  }
  input[type='color'] {
    padding: 2px;
    height: 40px;
    width: 64px;
  }
  input[type='checkbox'] {
    width: 18px;
    height: 18px;
    accent-color: var(--accent);
  }
  .hint {
    color: var(--ink-3);
    font-size: var(--fs-xs);
  }
  .warn {
    color: var(--warn);
    font-size: var(--fs-xs);
  }
  .tag {
    display: flex;
    gap: var(--s-2);
    align-items: center;
  }
  .tag input {
    width: 7em;
  }
  .checks {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s-2) var(--s-5);
  }
  .check {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    color: var(--ink);
  }
  .primary {
    justify-self: start;
    background: var(--accent);
    color: var(--on-accent);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-5);
    cursor: pointer;
    font-weight: 600;
  }
  .ghost {
    background: none;
    border: 0;
    color: var(--accent);
    cursor: pointer;
    padding: var(--s-1) 0;
    justify-self: start;
  }
  .danger {
    display: flex;
    gap: var(--s-5);
    align-items: center;
    flex-wrap: wrap;
    background: none;
    border-style: dashed;
  }
  .del {
    color: var(--danger);
  }
  .muted {
    color: var(--ink-3);
    margin: 0;
  }
</style>
