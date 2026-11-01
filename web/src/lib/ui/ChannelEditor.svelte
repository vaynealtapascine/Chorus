<script lang="ts">
  // Make or edit a channel or a members' chat (D-088, D-099; Android's ChannelEditor): name,
  // description, icon, colour and picture, and in our own space who it is kept to. Saving writes
  // only what changed, so another device's edit to the rest survives.
  import { untrack } from 'svelte';
  import { core } from '../core';
  import { fuzzy, members, type ChannelRow, type SpaceRow } from '../data';
  import { channelChanges, channelCreate, channelSlug, draftOf, draftProblem, type ChannelDraft } from '../chat-manage';
  import { discColor } from '../chat-list';
  import { flushUploads, stageBlob } from '../sync/uploads';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';

  let { projection, dark, spaceId, spaceKind, channel, kind = 'text', canDelete = false, group, onclose, onsaved = () => {} }: {
    projection: Projection; dark: boolean; spaceId: string; spaceKind: string;
    /** The one being edited; none to make a new one of `kind`. */
    channel?: ChannelRow; kind?: 'text' | 'member_dm'; canDelete?: boolean;
    /** Edit a group (shared space) instead: its name and description; the picture, icon and colour
     * go to `channel`, its one chat, when it has just one. */
    group?: SpaceRow;
    onclose: () => void; onsaved?: (id: string) => void;
  } = $props();

  const what = $derived(channel?.kind === 'member_dm' || (!channel && kind === 'member_dm') ? 'member_dm' : 'text');
  // what it was when the dialog opened: a sync while editing doesn't reset the form, and saving
  // writes only the fields changed here
  const before = untrack(() => draftOf(channel));
  const groupBefore = untrack(() => ({ name: group?.name ?? '', description: group?.description ?? '' }));
  let draft = $state<ChannelDraft>({ ...before, member_ids: [...before.member_ids],
    ...(groupBefore.name ? { name: groupBefore.name, topic: groupBefore.description } : {}) });
  let keptToSome = $state(before.member_ids.length > 0);
  let query = $state('');
  let error = $state('');
  let busy = $state(false);

  const SWATCHES = ['#B99CE8', '#F08C7F', '#EEAA62', '#DCA3CC', '#8FB8E8', '#86BFA5', '#42A397', '#A09184'];
  const people = $derived(members(projection).filter((m) => !m.deleted && !m.archived)
    .sort((a, b) => (a.display_name ?? a.name).localeCompare(b.display_name ?? b.name)));
  const shownPeople = $derived(query.trim() ? people.filter((m) => fuzzy(query, `${m.display_name ?? ''} ${m.name} ${m.sigils.join(' ')}`) !== null) : people);
  const systemName = $derived(String(projection.rows.system?.[sync.accountId]?.fields.name ?? 'your system'));
  const final = $derived<ChannelDraft>({ ...draft, member_ids: what === 'text' && !keptToSome ? [] : draft.member_ids });
  const problem = $derived(group ? (draft.name.trim() ? null : 'Give the group a name.')
    : draftProblem(final, what) ?? (what === 'text' && keptToSome && !draft.member_ids.length ? 'Choose who it is kept to.' : null));
  const disc = $derived(core.adaptColor(final.color ?? discColor(channel?.id ?? 'new'), dark));
  const glyph = $derived(final.icon.trim() || (what === 'text' && channelSlug(final.name) === 'general' ? '#' : (final.name.match(/\p{L}|\p{N}/u)?.[0] ?? '#').toUpperCase()));

  const title = $derived(group ? 'Edit group' : channel ? (what === 'member_dm' ? 'Edit chat' : 'Edit channel') : what === 'member_dm' ? 'New members’ chat' : 'New channel');

  function toggle(id: string, on: boolean) {
    draft.member_ids = on ? [...new Set([...draft.member_ids, id])] : draft.member_ids.filter((m) => m !== id);
  }

  async function choosePicture(e: Event) {
    const file = (e.currentTarget as HTMLInputElement).files?.[0];
    (e.currentTarget as HTMLInputElement).value = '';
    if (!file) return;
    error = '';
    try {
      // a square picture, cropped to the middle like a chat avatar
      const image = await createImageBitmap(file);
      const side = Math.min(image.width, image.height);
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 256;
      canvas.getContext('2d')?.drawImage(image, (image.width - side) / 2, (image.height - side) / 2, side, side, 0, 0, 256, 256);
      image.close();
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/webp', 0.9));
      if (!blob) throw new Error('This browser could not encode the picture.');
      draft.avatar_blob = (await stageBlob(blob, blob.type, sync.accountId)).hash;
    } catch (err) {
      error = err instanceof Error ? err.message : 'This picture could not be opened.';
    }
  }

  function save() {
    if (problem || busy) return;
    busy = true;
    try {
      const scope = `space:${spaceId}`;
      if (group) {
        const changes: Record<string, unknown> = {};
        if (draft.name.trim() !== groupBefore.name) changes.name = draft.name.trim().slice(0, 80);
        if (draft.topic.trim() !== groupBefore.description) changes.description = draft.topic.trim() || null;
        if (Object.keys(changes).length) sync.create('space.set', scope, group.id, changes);
        // the face is its one chat's (a group with several chats keeps each chat's own)
        if (channel) {
          const look = channelChanges(before, { ...before, icon: draft.icon, color: draft.color, avatar_blob: draft.avatar_blob }, 'text');
          if (Object.keys(look).length) sync.create('channel.set', scope, channel.id, look);
        }
        onsaved(channel?.id ?? group.id);
      } else if (channel) {
        const changes = channelChanges(before, final, what);
        if (Object.keys(changes).length) sync.create('channel.set', scope, channel.id, changes);
        onsaved(channel.id);
      } else {
        const id = sync.newId();
        sync.create('channel.create', scope, id, channelCreate(spaceId, final, what));
        onsaved(id);
      }
      if (final.avatar_blob && sync.status === 'live') void flushUploads(sync.device);
      onclose();
    } finally {
      busy = false;
    }
  }

  function remove() {
    if (!channel) return;
    const name = what === 'member_dm' ? 'this chat' : `#${channel.name}`;
    if (!confirm(`Delete ${name}? It goes for everyone here, messages and all. It can be restored from Trash.`)) return;
    sync.create('channel.delete', `space:${spaceId}`, channel.id, {});
    onclose();
    location.hash = '#/chat';
  }
</script>

<svelte:window onkeydown={(e) => { if (e.key === 'Escape') onclose(); }} />
<div class="scrim" onclick={onclose} role="presentation"></div>
<div class="sheet" role="dialog" aria-modal="true" aria-label={title}>
  <div class="top">
    <h2>{title}</h2>
    <button class="ghost" onclick={onclose}>Cancel</button>
  </div>

  {#if !group || channel}
  <div class="face">
    <span class="disc" style="background: {disc.ring}; color: {disc.avatar_ink}">
      {#if final.avatar_blob}<AvatarImage hash={final.avatar_blob} {glyph} name={final.name} />{:else}{glyph}{/if}
    </span>
    <div class="face-actions">
      <label class="link">Choose a picture<input type="file" accept="image/*" onchange={choosePicture} /></label>
      {#if draft.avatar_blob}<button class="link" onclick={() => (draft.avatar_blob = null)}>Remove picture</button>{/if}
    </div>
  </div>
  {/if}

  <label class="field">Name
    <input bind:value={draft.name} maxlength="80" placeholder={what === 'member_dm' ? 'Kai & June' : 'movie-night'} />
    {#if what === 'text' && !group && draft.name.trim()}<small>#{channelSlug(draft.name)}</small>{/if}
  </label>
  <label class="field">Description
    <textarea bind:value={draft.topic} rows="2" maxlength="500" placeholder="What it's for (optional)"></textarea>
  </label>
  {#if !group || channel}
  <div class="row2">
    <label class="field icon">Icon<input bind:value={draft.icon} placeholder="emoji" maxlength="16" /></label>
    <fieldset class="field colours">
      <legend>Colour</legend>
      <div class="swatches">
        {#each SWATCHES as c (c)}
          {@const a = core.adaptColor(c, dark)}
          <button class="swatch" class:on={draft.color === c} style="background: {a.ring}" aria-label="Colour {c}" aria-pressed={draft.color === c} onclick={() => (draft.color = c)}></button>
        {/each}
        <input type="color" aria-label="Another colour" value={draft.color ?? '#42A397'} onchange={(e) => (draft.color = e.currentTarget.value.toUpperCase())} />
        {#if draft.color}<button class="link" onclick={() => (draft.color = null)}>None</button>{/if}
      </div>
    </fieldset>
  </div>
  {/if}

  {#if spaceKind === 'internal'}
    {#if what === 'text'}
      <fieldset class="field">
        <legend>Who it's for</legend>
        <label class="radio"><input type="radio" name="kept" checked={!keptToSome} onchange={() => (keptToSome = false)} /> Everyone in {systemName}</label>
        <label class="radio"><input type="radio" name="kept" checked={keptToSome} onchange={() => (keptToSome = true)} /> Only some members</label>
      </fieldset>
    {/if}
    {#if what === 'member_dm' || keptToSome}
      <p class="hint">{what === 'member_dm' ? 'People in this chat.' : 'It shows only while one of them is fronting or co-conscious.'} This is privacy within your system, not a lock: you all share this account.</p>
      {#if people.length > 8}<input class="search" bind:value={query} placeholder="Search members" aria-label="Search members" />{/if}
      <div class="people" role="group" aria-label="Members">
        {#each shownPeople as m (m.id)}
          {@const c = core.adaptColor(m.color, dark)}
          <label class="person">
            <input type="checkbox" checked={draft.member_ids.includes(m.id)} onchange={(e) => toggle(m.id, e.currentTarget.checked)} />
            <span class="avatar" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
            <span style="color: {c.name}">{m.display_name ?? m.name}</span>
          </label>
        {:else}
          <p class="hint">No members match.</p>
        {/each}
      </div>
    {/if}
  {/if}

  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <div class="actions">
    {#if channel && canDelete}<button class="danger" onclick={remove}>Delete</button>{/if}
    <span class="why">{problem ?? ''}</span>
    <button class="primary" disabled={!!problem || busy} onclick={save}>{channel ? 'Save' : 'Create'}</button>
  </div>
</div>

<style>
  .scrim { position: fixed; inset: 0; background: rgb(0 0 0 / 0.45); z-index: 30; }
  .sheet { position: fixed; z-index: 31; inset: auto 0 0 0; max-height: 90vh; overflow: auto; margin: 0 auto; max-width: 520px;
    background: var(--surface); color: var(--ink); border-radius: var(--r-lg) var(--r-lg) 0 0; box-shadow: var(--shadow-pop);
    padding: 20px 20px calc(20px + env(safe-area-inset-bottom)); display: grid; gap: 14px; animation: rise var(--t-med, 220ms) var(--ease); }
  @media (min-width: 641px) { .sheet { inset: 8vh 0 auto 0; border-radius: var(--r-lg); } }
  @keyframes rise { from { transform: translateY(16px); opacity: 0; } }
  @media (prefers-reduced-motion: reduce) { .sheet { animation: none; } }
  .top { display: flex; justify-content: space-between; align-items: center; }
  h2 { margin: 0; font-size: var(--fs-lg); }
  .ghost, .link { border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; padding: 4px 0; }
  .link input { display: none; }
  .face { display: flex; align-items: center; gap: 16px; }
  .disc { width: 72px; height: 72px; flex: none; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-size: 28px; font-weight: 700; }
  .face-actions { display: grid; gap: 2px; justify-items: start; }
  .field { display: grid; gap: 6px; font-size: var(--fs-sm); color: var(--ink-2); border: 0; margin: 0; padding: 0; min-width: 0; }
  .field legend { padding: 0; margin-bottom: 6px; }
  .field small { color: var(--ink-3); }
  input:not([type]), textarea, .search { font: inherit; font-size: 15px; color: var(--ink); background: var(--surface-2);
    border: 1px solid var(--line); border-radius: var(--r-sm); padding: 8px 10px; min-width: 0; }
  textarea { resize: vertical; }
  .row2 { display: grid; grid-template-columns: 96px 1fr; gap: 12px; }
  .swatches { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
  .swatch { width: 26px; height: 26px; border-radius: 50%; border: 2px solid transparent; cursor: pointer; padding: 0; }
  .swatch.on { border-color: var(--ink); }
  input[type='color'] { width: 30px; height: 30px; padding: 0; border: 0; background: none; cursor: pointer; }
  .radio { display: flex; align-items: center; gap: 8px; color: var(--ink); padding: 4px 0; }
  .people { display: grid; gap: 2px; max-height: 260px; overflow: auto; }
  .person { display: flex; align-items: center; gap: 10px; padding: 6px 4px; border-radius: var(--r-sm); cursor: pointer; }
  .person:hover { background: var(--surface-2); }
  .avatar { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; overflow: hidden; font-size: 13px; font-weight: 700; flex: none; }
  .hint { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
  .actions { display: flex; align-items: center; gap: 12px; }
  .why { flex: 1; color: var(--ink-3); font-size: var(--fs-xs); }
  .primary { border: 0; border-radius: var(--r-full); padding: 9px 20px; background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .primary:disabled { opacity: .5; cursor: default; }
  .danger { border: 1px solid var(--danger); border-radius: var(--r-full); padding: 8px 16px; background: none; color: var(--danger); font: inherit; cursor: pointer; }
</style>
