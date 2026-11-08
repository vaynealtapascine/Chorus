<script lang="ts">
  // One profile editor (D-101): every part of a member's profile in one list, each edited in
  // place (click the value; Enter or leaving the field saves, Escape cancels) with its own
  // audience chip. Adding, renaming and removing custom fields happen here too, and ask first.
  import { core } from '../core';
  import type { BucketRow, FieldDef, MemberRow } from '../data';
  import { roleColor, rolesOf } from '../member-list';
  import { audienceOf, effectiveAudience, isPrivate, ownAudience, withAudience, type Audience, type Part } from '../profile-audience';
  import { sync } from '../sync/client';
  import { flushUploads, stageBlob } from '../sync/uploads';
  import AudienceChip from './AudienceChip.svelte';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import RichText from './RichText.svelte';

  let {
    m,
    raw,
    defs,
    values,
    buckets,
    set,
  }: {
    m: MemberRow;
    raw: Record<string, unknown>;
    defs: FieldDef[];
    values: Map<string, unknown>;
    buckets: BucketRow[];
    /** member.set with only what changed */
    set: (fields: Record<string, unknown>) => void;
  } = $props();

  const live = $derived(new Set(buckets.map((b) => b.id)));
  const profile = $derived(audienceOf(raw.visibility));
  const closed = $derived(isPrivate(profile, live));
  const parts = $derived(raw.field_visibility);
  const defaults = $derived(new Map(defs.map((d) => [d.id, defaultOf(d)])));

  const defaultOf = (d: FieldDef): Audience => audienceOf(d.default_visibility);
  function setPartAudience(key: string, a: Audience | null) {
    set({ field_visibility: withAudience(parts, key, a) });
  }

  // ─── editing one value in place ─────────────────────────────────────────
  let editing = $state<string | null>(null);
  let draft = $state('');
  let error = $state('');
  function begin(key: string, current: string) {
    editing = key;
    draft = current;
    error = '';
  }
  function focus(node: HTMLElement) {
    node.focus();
    if (node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement) node.select?.();
  }
  function keys(e: KeyboardEvent, save: () => void, multiline = false) {
    if (e.key === 'Escape') { e.preventDefault(); editing = null; }
    else if (e.key === 'Enter' && (!multiline || e.ctrlKey || e.metaKey)) { e.preventDefault(); save(); }
  }

  // the display name has no audience of its own: whoever may see the member sees it (D-126)
  type TextPart = 'display_name' | 'name' | 'pronouns';
  const TEXT_LABELS: Record<TextPart, string> = { display_name: 'Display name', name: 'Full name', pronouns: 'Pronouns' };
  function partValue(p: Part | TextPart): string {
    if (p === 'display_name') return m.display_name ?? '';
    if (p === 'name') return m.name;
    if (p === 'pronouns') return m.pronouns ?? '';
    if (p === 'birthday') return m.birthday ?? '';
    return '';
  }
  function savePart(p: Part | TextPart) {
    const v = draft.trim();
    if ((p === 'display_name' || p === 'name' || p === 'pronouns') && v.length > 100) { error = 'Keep it under 100 characters.'; return; }
    if (p === 'name' && !v) { error = 'Everyone needs a name.'; return; }
    if (p === 'birthday' && v && !/^(\d{4}|-)-\d{2}-\d{2}$/.test(v)) { error = 'Use a date, or --MM-DD without a year.'; return; }
    set({ [p]: v || null });
    editing = null;
  }

  // bio: edited as markup, stored as plain text + entities (D-041)
  const bioMarkup = $derived(m.description ? core.toMarkup({ text: m.description, entities: (raw.description_entities as never) ?? [] }) : '');
  function saveBio() {
    if (draft.length > 10000) { error = 'Keep the bio under 10,000 characters.'; return; }
    const rich = core.parseMarkup(draft.trim());
    set({ description: rich.text || null, description_entities: rich.entities.length ? rich.entities : null });
    editing = null;
  }

  let bannerError = $state('');
  async function chooseBanner(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (!file.type.startsWith('image/') || file.size > 4 * 1024 * 1024) { bannerError = 'Choose an image smaller than 4 MB.'; return; }
    try {
      const upload = await stageBlob(file, file.type, sync.accountId);
      set({ banner_blob: upload.hash });
      if (sync.status === 'live') void flushUploads(sync.device);
      bannerError = '';
    } catch (err) { bannerError = String(err); }
  }

  // ─── custom fields ──────────────────────────────────────────────────────
  function setValue(d: FieldDef, value: unknown) {
    sync.create('field.set_value', sync.accountScope, null, { member_id: m.id, field_id: d.id, value });
  }
  function shown(d: FieldDef, v: unknown): string {
    if (v == null || v === '') return '';
    if (d.type === 'boolean') return v === true ? 'Yes' : 'No';
    if (Array.isArray(v)) return v.join(', ');
    return String(v);
  }
  function saveField(d: FieldDef) {
    const v = draft.trim();
    if (!v) { setValue(d, null); editing = null; return; }
    if (d.type === 'number') {
      const n = Number(v);
      if (!Number.isFinite(n)) { error = 'Use a number.'; return; }
      setValue(d, n);
    } else if (d.type === 'url') {
      let ok = false;
      try { const u = new URL(v); ok = (u.protocol === 'https:' || u.protocol === 'http:') && !!u.hostname && !u.username; } catch { ok = false; }
      if (!ok || /\s/.test(v)) { error = 'Use a full link starting with https://'; return; }
      setValue(d, v);
    } else if (d.type === 'text' && v.length > 500) { error = 'Keep it under 500 characters.'; return; }
    else setValue(d, v);
    editing = null;
  }
  function toggleChoice(d: FieldDef, chosen: string[], c: string) {
    if (d.type === 'select') setValue(d, chosen[0] === c ? null : c);
    else setValue(d, chosen.includes(c) ? chosen.filter((x) => x !== c) : [...chosen, c]);
  }
  function addChoice(d: FieldDef, chosen: string[], input: HTMLInputElement) {
    const c = input.value.trim();
    input.value = '';
    if (!c) return;
    const choices = d.options.choices ?? [];
    const existing = choices.find((x) => x.toLowerCase() === c.toLowerCase());
    if (!existing) sync.create('field.set_def', sync.accountScope, d.id, { options: { ...d.options, choices: [...choices, c] } });
    const pick = existing ?? c;
    if (!chosen.includes(pick)) toggleChoice(d, chosen, pick);
  }

  const TYPES: { type: FieldDef['type']; label: string; example: string }[] = [
    { type: 'text', label: 'Text', example: 'Location' },
    { type: 'long_text', label: 'Long text', example: 'Likes & dislikes' },
    { type: 'multi_select', label: 'Several choices', example: 'Roles' },
    { type: 'select', label: 'One choice', example: 'Age range' },
    { type: 'date', label: 'Date', example: 'Split date' },
    { type: 'number', label: 'Number', example: 'Age' },
    { type: 'boolean', label: 'Yes / no', example: 'Can drive' },
    { type: 'url', label: 'Link', example: 'Website' },
    { type: 'color', label: 'Colour', example: 'Favourite colour' },
  ];
  const typeLabel = (t: string) => TYPES.find((x) => x.type === t)?.label ?? t;

  let adding = $state(false);
  let newName = $state('');
  let newType = $state<FieldDef['type']>('text');
  let newAudience = $state<Audience>({ mode: 'private' });
  let confirmAdd: HTMLDialogElement | undefined = $state();
  const nameTaken = $derived(defs.some((d) => d.name.trim().toLowerCase() === newName.trim().toLowerCase()));
  function askAdd(e: SubmitEvent) {
    e.preventDefault();
    if (!newName.trim() || nameTaken) return;
    confirmAdd?.showModal();
  }
  function add() {
    const choice = newType === 'select' || newType === 'multi_select';
    sync.create('field.define', sync.accountScope, sync.newId(), {
      name: newName.trim(), type: newType, default_visibility: newAudience, ...(choice ? { options: { choices: [] } } : {}),
    });
    confirmAdd?.close();
    adding = false;
    newName = '';
    newType = 'text';
    newAudience = { mode: 'private' };
  }

  let menuFor = $state<string | null>(null);
  let renaming = $state<string | null>(null);
  let removing = $state<FieldDef | null>(null);
  let confirmRemove: HTMLDialogElement | undefined = $state();
  function rename(d: FieldDef) {
    const v = draft.trim();
    if (v && v !== d.name) {
      if (defs.some((o) => o.id !== d.id && o.name.trim().toLowerCase() === v.toLowerCase())) { error = 'Another field already has that name.'; return; }
      sync.create('field.set_def', sync.accountScope, d.id, { name: v });
    }
    renaming = null;
  }
  function askRemove(d: FieldDef) {
    removing = d;
    menuFor = null;
    confirmRemove?.showModal();
  }
  function remove() {
    if (removing) sync.create('field.delete_def', sync.accountScope, removing.id, {});
    confirmRemove?.close();
    removing = null;
  }
</script>

<svelte:window onclick={() => (menuFor = null)} />

<section class="profile" aria-labelledby="profile-heading">
  <div class="head">
    <h2 id="profile-heading">Profile</h2>
    <span class="who">
      <span class="muted">Who can open it</span>
      <AudienceChip label="{m.name}'s profile" value={profile} own buckets={buckets} onchange={(a) => set({ visibility: a ?? { mode: 'private' } })} />
    </span>
  </div>
  <p class="hint">
    {#if closed}
      Only your system can see {m.name}'s profile right now. Share it with friends and each part below can be opened up or kept to yourselves.
    {:else}
      Friends who can open it see {m.display_name ?? m.name}'s display name, handle, colour and avatar, plus each part below that's shared with them.
    {/if}
  </p>
  {#if error}<p class="error" role="alert">{error}</p>{/if}

  <ul class="rows">
    {#each ['display_name', 'name', 'pronouns'] as const as p (p)}
      <li class="row">
        <span class="label">{TEXT_LABELS[p]}</span>
        {#if editing === p}
          <input class="value-edit" use:focus bind:value={draft} maxlength="100" aria-label={TEXT_LABELS[p]}
            onkeydown={(e) => keys(e, () => savePart(p))} onblur={() => editing === p && savePart(p)} />
        {:else}
          <button class="value" class:empty={!partValue(p)} onclick={() => begin(p, partValue(p))}>{partValue(p) || `Add ${TEXT_LABELS[p].toLowerCase()}`}</button>
        {/if}
        {#if p === 'display_name'}
          <span class="follows" title="Whoever can see {m.display_name ?? m.name} sees their display name">Same as profile</span>
        {:else if p === 'name' && !m.display_name}
          <span class="follows" title="Without a display name, the full name is what everyone sees">Same as profile</span>
        {:else}
          <AudienceChip label={TEXT_LABELS[p]} value={effectiveAudience(raw.visibility, parts, p)} own={!!ownAudience(parts, p)} inherit={p === 'name' ? undefined : 'Same as profile'} {buckets} limited={closed} onchange={(a) => setPartAudience(p, a)} />
        {/if}
      </li>
      {#if p === 'name' && m.display_name}
        <li class="note">{effectiveAudience(raw.visibility, parts, 'name').mode === 'private' ? `Friends see ${m.display_name}, never the full name.` : `Shared on purpose: friends it reaches see the full name next to ${m.display_name}.`}</li>
      {/if}
    {/each}

    <li class="row tall">
      <span class="label">Bio</span>
      {#if editing === 'description'}
        <div class="value-edit-wrap">
          <textarea class="value-edit" use:focus rows="5" bind:value={draft} aria-label="Bio"
            onkeydown={(e) => keys(e, saveBio, true)} onblur={() => editing === 'description' && saveBio()}></textarea>
          <span class="hint">**bold**, *italic*, ||spoiler||, [link](https://…) · Ctrl+Enter saves</span>
        </div>
      {:else}
        <button class="value bio" class:empty={!m.description} onclick={() => begin('description', bioMarkup)}>
          {#if m.description}<RichText text={m.description} entities={(raw.description_entities as never) ?? []} />{:else}Add a bio{/if}
        </button>
      {/if}
      <AudienceChip label="Bio" value={effectiveAudience(raw.visibility, parts, 'description')} own={!!ownAudience(parts, 'description')} inherit="Same as profile" {buckets} limited={closed} onchange={(a) => setPartAudience('description', a)} />
    </li>

    <li class="row">
      <span class="label">Birthday</span>
      {#if editing === 'birthday'}
        <input class="value-edit" use:focus type="date" bind:value={draft} aria-label="Birthday"
          onkeydown={(e) => keys(e, () => savePart('birthday'))} onblur={() => editing === 'birthday' && savePart('birthday')} />
      {:else}
        <button class="value" class:empty={!m.birthday} onclick={() => begin('birthday', m.birthday ?? '')}>{m.birthday ? (m.birthday.startsWith('--') ? m.birthday.slice(2) : m.birthday) : 'Add a birthday'}</button>
      {/if}
      <AudienceChip label="Birthday" value={effectiveAudience(raw.visibility, parts, 'birthday')} own={!!ownAudience(parts, 'birthday')} inherit="Same as profile" {buckets} limited={closed} onchange={(a) => setPartAudience('birthday', a)} />
    </li>

    <li class="row tall">
      <span class="label">Banner</span>
      <div class="banner">
        {#if m.banner_blob}<div class="banner-preview"><AvatarImage hash={m.banner_blob} glyph="" name="Profile banner" /></div>{/if}
        <span class="banner-actions">
          <label class="ghost file">{m.banner_blob ? 'Change' : 'Add a banner'}<input type="file" accept="image/*" onchange={chooseBanner} aria-label="Choose member banner" /></label>
          {#if m.banner_blob}<button class="ghost" onclick={() => set({ banner_blob: null })}>Remove</button>{/if}
        </span>
        {#if bannerError}<p class="error" role="alert">{bannerError}</p>{/if}
      </div>
      <AudienceChip label="Banner" value={effectiveAudience(raw.visibility, parts, 'banner')} own={!!ownAudience(parts, 'banner')} inherit="Same as profile" {buckets} limited={closed} onchange={(a) => setPartAudience('banner', a)} />
    </li>

    {#each defs as d (d.id)}
      {@const v = values.get(d.id)}
      {@const own = ownAudience(parts, d.id)}
      {@const fallback = defaults.get(d.id) ?? { mode: 'private' }}
      <li class="row" class:tall={d.type === 'long_text' || d.type === 'select' || d.type === 'multi_select'}>
        {#if renaming === d.id}
          <input class="label-edit" use:focus bind:value={draft} aria-label="Rename {d.name}"
            onkeydown={(e) => { if (e.key === 'Escape') renaming = null; else if (e.key === 'Enter') rename(d); }} onblur={() => renaming === d.id && rename(d)} />
        {:else}
          <span class="label field-label">
            {d.name}
            <span class="field-menu">
              <button class="dots" aria-label="{d.name} field options" aria-haspopup="menu" aria-expanded={menuFor === d.id}
                onclick={(e) => { e.stopPropagation(); menuFor = menuFor === d.id ? null : d.id; }}><Icon name="more" size={16} /></button>
              {#if menuFor === d.id}
                <div class="menu" role="menu" tabindex="-1" onclick={(e) => e.stopPropagation()} onkeydown={() => {}}>
                  <p class="menu-title">{typeLabel(d.type)} · on every member</p>
                  <button role="menuitem" onclick={() => { renaming = d.id; draft = d.name; menuFor = null; }}>Rename…</button>
                  <div class="menu-row"><span>For members who haven't chosen</span>
                    <AudienceChip label="{d.name} by default" value={fallback} own {buckets} onchange={(a) => sync.create('field.set_def', sync.accountScope, d.id, { default_visibility: a ?? { mode: 'private' } })} />
                  </div>
                  <button role="menuitem" class="del" onclick={() => askRemove(d)}>Remove from every member…</button>
                </div>
              {/if}
            </span>
          </span>
        {/if}
        {#if d.type === 'select' || d.type === 'multi_select'}
          {@const chosen = rolesOf(v)}
          <div class="choices" role="group" aria-label={d.name}>
            {#each d.options.choices ?? [] as c (c)}
              <button type="button" class="choice" class:on={chosen.includes(c)} aria-pressed={chosen.includes(c)} style="--pill: {roleColor(c)}" onclick={() => toggleChoice(d, chosen, c)}>{c}</button>
            {/each}
            {#each chosen.filter((c) => !(d.options.choices ?? []).includes(c)) as c (c)}
              <button type="button" class="choice on" aria-pressed="true" style="--pill: {roleColor(c)}" onclick={() => toggleChoice(d, chosen, c)}>{c}</button>
            {/each}
            <input class="add-choice" placeholder="Add…" aria-label="Add a choice to {d.name}"
              onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addChoice(d, chosen, e.currentTarget); } }}
              onblur={(e) => addChoice(d, chosen, e.currentTarget)} />
          </div>
        {:else if d.type === 'boolean'}
          <div class="seg" role="group" aria-label={d.name}>
            <button aria-pressed={v === true} onclick={() => setValue(d, v === true ? null : true)}>Yes</button>
            <button aria-pressed={v === false} onclick={() => setValue(d, v === false ? null : false)}>No</button>
          </div>
        {:else if d.type === 'color'}
          <span class="colour">
            <input type="color" value={typeof v === 'string' ? v : '#888888'} aria-label={d.name} onchange={(e) => setValue(d, e.currentTarget.value)} />
            {#if typeof v === 'string'}<button class="ghost" onclick={() => setValue(d, null)}>Clear</button>{:else}<span class="muted">Not set</span>{/if}
          </span>
        {:else if editing === d.id}
          {#if d.type === 'long_text'}
            <textarea class="value-edit" use:focus rows="4" bind:value={draft} aria-label={d.name}
              onkeydown={(e) => keys(e, () => saveField(d), true)} onblur={() => editing === d.id && saveField(d)}></textarea>
          {:else}
            <input class="value-edit" use:focus bind:value={draft} aria-label={d.name}
              type={d.type === 'number' ? 'number' : d.type === 'date' ? 'date' : d.type === 'url' ? 'url' : 'text'}
              onkeydown={(e) => keys(e, () => saveField(d))} onblur={() => editing === d.id && saveField(d)} />
          {/if}
        {:else}
          <button class="value" class:empty={!shown(d, v)} class:bio={d.type === 'long_text'} onclick={() => begin(d.id, shown(d, v))}>{shown(d, v) || `Add ${d.name.toLowerCase()}`}</button>
        {/if}
        <AudienceChip label={d.name} value={own ?? fallback} own={!!own} inherit="Field default" {buckets} limited={closed} onchange={(a) => setPartAudience(d.id, a)} />
      </li>
    {/each}
  </ul>

  {#if adding}
    <form class="addfield" onsubmit={askAdd}>
      <label>Name <input bind:value={newName} use:focus maxlength="60" placeholder={TYPES.find((t) => t.type === newType)?.example} /></label>
      <label>Kind
        <select bind:value={newType}>{#each TYPES as t (t.type)}<option value={t.type}>{t.label}</option>{/each}</select>
      </label>
      <span class="label">Starts as <AudienceChip label="New field" value={newAudience} own {buckets} onchange={(a) => (newAudience = a ?? { mode: 'private' })} /></span>
      {#if nameTaken}<p class="error">There's already a field called that.</p>{/if}
      <span class="actions">
        <button class="primary" disabled={!newName.trim() || nameTaken}>Add field…</button>
        <button type="button" class="ghost" onclick={() => (adding = false)}>Cancel</button>
      </span>
    </form>
  {:else}
    <button class="ghost add" onclick={() => (adding = true)}><Icon name="plus" size={16} /> Add a field</button>
  {/if}
</section>

<dialog bind:this={confirmAdd} aria-labelledby="add-title">
  <h3 id="add-title">Add “{newName.trim()}” to every member?</h3>
  <p>It's a {typeLabel(newType).toLowerCase()} field. Every member gets an empty one to fill in, and it starts as <strong>{newAudience.mode === 'private' ? 'only us' : newAudience.mode === 'friends' ? 'shared with friends' : newAudience.mode === 'followers' ? 'shared with followers' : newAudience.mode === 'following' ? 'shared with who you follow' : newAudience.mode === 'server' ? 'shared with everyone here' : 'shared with chosen buckets'}</strong>; each member can change that for themselves.</p>
  <div class="actions">
    <button class="primary" onclick={add}>Add field</button>
    <button class="ghost" onclick={() => confirmAdd?.close()}>Not yet</button>
  </div>
</dialog>

<dialog bind:this={confirmRemove} aria-labelledby="remove-title" onclose={() => (removing = null)}>
  <h3 id="remove-title">Remove “{removing?.name}” from every member?</h3>
  <p>It disappears from every profile, including any friend who could see it. Nothing is erased: it stays in your history and exports.</p>
  <div class="actions">
    <button class="primary danger" onclick={remove}>Remove field</button>
    <button class="ghost" onclick={() => confirmRemove?.close()}>Keep it</button>
  </div>
</dialog>

<style>
  .profile { display: grid; gap: var(--s-3); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); flex-wrap: wrap; }
  .head h2 { margin: 0; }
  .who { display: inline-flex; align-items: center; gap: var(--s-2); font-size: var(--fs-sm); }
  .rows { list-style: none; margin: 0; padding: 0; display: grid; }
  .row { display: grid; grid-template-columns: minmax(110px, 160px) 1fr auto; align-items: center; gap: var(--s-3); padding: var(--s-2) 0; border-top: 1px solid var(--line); min-height: 48px; }
  .row.tall { align-items: start; }
  .label { font-size: var(--fs-sm); color: var(--ink-2); }
  .follows { font-size: var(--fs-xs); color: var(--ink-3); padding: 0 var(--s-2); white-space: nowrap; }
  .note { font-size: var(--fs-xs); color: var(--ink-3); padding: 0 0 var(--s-2) calc(min(160px, 30%) + var(--s-3)); }
  .field-label { display: inline-flex; align-items: center; gap: 2px; }
  .value { font: inherit; text-align: left; color: var(--ink); background: none; border: 1px solid transparent; border-radius: var(--r-sm); padding: var(--s-2); cursor: text; min-width: 0; overflow-wrap: anywhere; }
  .value:hover, .value:focus-visible { border-color: var(--line); background: var(--surface-2); }
  .value.empty { color: var(--ink-3); }
  .value.bio { white-space: pre-wrap; }
  .value-edit, .label-edit, .addfield input, .addfield select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--accent); border-radius: var(--r-sm); padding: var(--s-2); min-width: 0; }
  .value-edit-wrap { display: grid; gap: var(--s-1); }
  textarea.value-edit { resize: vertical; }
  .banner { display: grid; gap: var(--s-2); }
  .banner-preview { height: 96px; border-radius: var(--r-md); overflow: hidden; background: var(--surface-2); }
  .banner-preview :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .banner-actions { display: flex; gap: var(--s-4); }
  .file input { display: none; }
  .choices { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding: var(--s-1) 0; }
  .choice { font: inherit; font-size: var(--fs-sm); padding: 2px 10px; border-radius: var(--r-full); cursor: pointer; color: var(--ink-2); background: none; border: 1px dashed var(--line); }
  .choice.on { color: var(--ink); font-weight: 600; background: color-mix(in srgb, var(--pill) 28%, var(--surface)); border: 1px solid color-mix(in srgb, var(--pill) 55%, transparent); }
  .add-choice { font: inherit; font-size: var(--fs-sm); width: 8em; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line); padding: 2px 10px; border-radius: var(--r-full); }
  .seg { display: inline-flex; gap: 2px; padding: 2px; border-radius: var(--r-full); background: var(--surface-2); justify-self: start; }
  .seg button { font: inherit; font-size: var(--fs-sm); border: 0; background: none; color: var(--ink-2); padding: 4px 14px; border-radius: var(--r-full); cursor: pointer; }
  .seg button[aria-pressed='true'] { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
  .colour { display: inline-flex; align-items: center; gap: var(--s-3); }
  .colour input { width: 48px; height: 32px; padding: 2px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); }
  .field-menu { position: relative; display: inline-flex; }
  .dots { border: 0; background: none; color: var(--ink-3); cursor: pointer; padding: 4px; border-radius: 6px; display: inline-grid; }
  .dots:hover { background: var(--surface-2); color: var(--ink); }
  .menu { position: absolute; left: 0; top: calc(100% + 4px); z-index: 15; width: 270px; display: grid; gap: 2px; padding: 6px; background: var(--surface); border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow-pop); }
  .menu-title { margin: 4px 8px; font-size: var(--fs-xs); color: var(--ink-3); }
  .menu > button { text-align: left; border: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-sm); padding: 8px; border-radius: 8px; cursor: pointer; }
  .menu > button:hover { background: var(--surface-2); }
  .menu-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 8px; font-size: var(--fs-xs); color: var(--ink-2); }
  .del { color: var(--danger) !important; }
  .addfield { display: grid; grid-template-columns: 1fr auto; gap: var(--s-3); align-items: end; padding: var(--s-3); border: 1px dashed var(--line); border-radius: var(--r-md); }
  .addfield label, .addfield .label { display: grid; gap: var(--s-1); font-size: var(--fs-sm); color: var(--ink-2); }
  .addfield .label { display: flex; align-items: center; gap: var(--s-2); }
  .actions { display: flex; gap: var(--s-4); align-items: center; grid-column: 1 / -1; }
  .add { display: inline-flex; align-items: center; gap: 6px; }
  .hint, .muted { color: var(--ink-3); font-size: var(--fs-xs); margin: 0; }
  .error { color: var(--danger); font-size: var(--fs-sm); margin: 0; }
  .primary { background: var(--accent); color: var(--on-accent); border: 0; border-radius: var(--r-full); padding: var(--s-2) var(--s-5); cursor: pointer; font-weight: 600; font: inherit; }
  .primary:disabled { opacity: .5; cursor: default; }
  .primary.danger { background: var(--danger); }
  .ghost { background: none; border: 0; color: var(--accent); cursor: pointer; padding: var(--s-1) 0; font: inherit; }
  dialog { max-width: 420px; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--surface); color: var(--ink); padding: var(--s-5); box-shadow: var(--shadow-pop); }
  dialog::backdrop { background: rgb(0 0 0 / .45); }
  dialog h3 { margin: 0 0 var(--s-2); font-size: var(--fs-lg); }
  dialog p { color: var(--ink-2); margin: 0 0 var(--s-4); }
  @media (max-width: 600px) {
    .row { grid-template-columns: 1fr auto; }
    .row > .label, .row > .label-edit { grid-column: 1 / -1; }
    .addfield { grid-template-columns: 1fr; }
  }
</style>
