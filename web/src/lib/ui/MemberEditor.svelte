<script lang="ts">
  // Edit member (F4.2): the picture, eight colour circles, Name, Pronouns beside Display name and
  // Description, then the More rows (Groups, Who can see, Custom fields, Handles, Banner, ...), and
  // Archive at the foot. The top fields are a draft that Save writes; the rows open their own sheets
  // or pages and save as they are used.
  import Button from './kit/Button.svelte';
  import { onDestroy, untrack } from 'svelte';
  import { actingOpts } from '../front-view';
  import { core } from '../core';
  import { audienceLabel, audienceOf } from '../profile-audience';
  import { buckets, fieldDefs, fieldValues, members, selfMember, type ProxyTag } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import { flushUploads, stageBlob } from '../sync/uploads';
  import { claimedHere, MAX_CLAIMS, memberHandles, normalizeClaim, validClaim } from '../handles';
  import { addFolder, FOLDER_OF, FOLDERS, memberListPrefs } from '../member-list';
  import { groupsOf, subsystemOf } from '../member-directory';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import MemberAudience from './MemberAudience.svelte';
  import MemberFieldsSheet from './MemberFieldsSheet.svelte';
  import MemberGroupsSheet from './MemberGroupsSheet.svelte';
  import Sheet from './Sheet.svelte';
  import SubsystemSheet from './SubsystemSheet.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import MenuRow from './kit/MenuRow.svelte';
  import MenuSheet from './kit/MenuSheet.svelte';
  import SectionLabel from './kit/SectionLabel.svelte';
  import Swatches from './kit/Swatches.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let { projection, id, dark, page }: { projection: Projection; id: string; dark: boolean; page?: string } = $props();

  const all = $derived(members(projection));
  const m = $derived(all.find((x) => x.id === id));
  const person = $derived(!!selfMember(projection));
  const raw = $derived((projection.rows.member?.[id]?.fields ?? {}) as Record<string, unknown>);
  const defs = $derived(fieldDefs(projection));
  const values = $derived(fieldValues(projection).get(id) ?? new Map());
  const bucketNames = $derived(new Map(buckets(projection).map((b) => [b.id, b.name])));

  /** Send only fields that changed (field-level LWW merges them with other devices' edits). */
  function set(fields: Record<string, unknown>) {
    const changed = Object.fromEntries(Object.entries(fields).filter(([k, v]) => JSON.stringify(raw[k] ?? null) !== JSON.stringify(v ?? null)));
    if (Object.keys(changed).length) sync.create('member.set', sync.accountScope, id, changed);
  }

  // ── the draft: Name, Pronouns, Display name, Description and colour, written by Save ──
  const bioMarkup = $derived(m?.description ? core.toMarkup({ text: m.description, entities: (raw.description_entities as never) ?? [] }) : '');
  let draft = $state({ name: '', pronouns: '', display: '', bio: '', color: '#888888' });
  let loaded = '';
  $effect(() => {
    if (!m || loaded === m.id) return;
    loaded = m.id;
    untrack(() => { draft = { name: m.name, pronouns: m.pronouns ?? '', display: m.display_name ?? '', bio: bioMarkup, color: m.color }; });
  });
  const dirty = $derived(!!m && (draft.name !== m.name || draft.pronouns !== (m.pronouns ?? '') || draft.display !== (m.display_name ?? '') || draft.bio !== bioMarkup || draft.color !== m.color));
  let error = $state('');
  function save(): boolean {
    const name = draft.name.trim();
    if (!name) { error = 'Everyone needs a name.'; return false; }
    if (draft.bio.length > 10000) { error = 'Keep the description under 10,000 characters.'; return false; }
    error = '';
    const rich = core.parseMarkup(draft.bio.trim());
    set({
      name, pronouns: draft.pronouns.trim() || null, display_name: draft.display.trim() || null, color: draft.color,
      ...(draft.bio !== bioMarkup ? { description: rich.text || null, description_entities: rich.entities.length ? rich.entities : null } : {}),
    });
    return true;
  }
  function saveAndClose() { if (!dirty || save()) router.go(person ? '/settings' : '/members'); }
  const colors = $derived(m ? core.adaptColor(draft.color, dark) : null);

  // ── picture ──
  let cropBitmap: ImageBitmap | null = null;
  let cropReady = $state(false);
  let cropCanvas: HTMLCanvasElement | undefined = $state();
  let cropZoom = $state(1);
  let cropX = $state(0);
  let cropY = $state(0);
  let avatarError = $state('');
  let picture = $state(false);
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
      cropBitmap?.close(); cropBitmap = null; cropReady = false; picture = false;
      avatarError = '';
    } catch (e) { avatarError = String(e); }
  }

  // ── banner ──
  let banner = $state(false);
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

  // ── handles (D-126): the default is theirs for good; claims are checked by the server ──
  let handlesOpen = $state(false);
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
  function firstClaim(h: string) {
    const list = (m?.handles ?? []).filter((x) => x !== h);
    set({ handles: [h, ...list] });
  }

  // ── speaking: sigils and proxy tags ──
  let speaking = $state(false);
  const sigilText = $derived(m?.sigils.join(' ') ?? '');
  const sigilClash = $derived((m?.sigils ?? []).filter((s) => all.some((o) => o.id !== id && !o.deleted && o.sigils.includes(s))));
  let tags = $state<ProxyTag[]>([]);
  $effect(() => { tags = m?.proxy_tags.map((t) => ({ ...t })) ?? []; });
  const saveTags = () => set({ proxy_tags: tags.filter((t) => t.prefix || t.suffix) });

  // ── chat notifications: when this member's mentions and member DMs ping the system (NOTIFICATIONS §7) ──
  let notifying = $state(false);
  const notifyRule = $derived.by(() => {
    const rows = projection.rows.pref ?? {};
    const key = `notify_member:${id}`;
    const v = (rows[`${sync.accountId}||${key}`] ?? rows[`||${key}`])?.fields.value;
    return (v && typeof v === 'object' ? v : {}) as { mentions?: string; dms?: string };
  });
  const setNotifyRule = (which: 'mentions' | 'dms', rule: string) =>
    sync.create('pref.set', sync.accountScope, null, { device: '', key: `notify_member:${id}`, value: { ...notifyRule, [which]: rule } });
  const RULES = [{ id: 'always', label: 'Always' }, { id: 'fronting', label: 'While fronting' }, { id: 'never', label: 'Never' }];

  // ── which Members list folder they sit in (D-100): only sorts the list ──
  let filing = $state(false);
  let newFolder = $state('');
  const listPrefs = $derived(memberListPrefs((projection.rows.pref ?? {}) as never, sync.accountId));
  const folderName = $derived(listPrefs.folders.find((f) => f.id === listPrefs.folderOf.get(id))?.name ?? '');
  const setFolder = (folder: string | null) => { sync.create('pref.set', sync.accountScope, null, { device: '', key: FOLDER_OF + id, value: folder }); filing = false; };
  function folderNew() {
    const name = newFolder.trim();
    if (!name) return;
    const fid = sync.newId();
    sync.create('pref.set', sync.accountScope, null, { device: '', key: FOLDERS, value: addFolder(listPrefs.folders, fid, name) });
    newFolder = '';
    setFolder(fid);
  }

  // ── the rows' values ──
  let groupsOpen = $state(false);
  let fieldsOpen = $state(false);
  let subsystemOpen = $state(false);
  let archiving = $state(false);
  let trashing = $state(false);
  const groupNames = $derived(m ? groupsOf(projection, id).map((g) => g.name).join(', ') : '');
  const subsystem = $derived(subsystemOf(projection, sync.accountId, id));
  const filled = $derived([m?.birthday ? 'Birthday' : '', ...defs.filter((d) => { const v = values.get(d.id); return v != null && v !== '' && !(Array.isArray(v) && !v.length); }).map((d) => d.name)].filter(Boolean).slice(0, 3).join(', '));
  const who = $derived(audienceLabel(audienceOf(raw.visibility), bucketNames));
  const shownName = $derived(m?.display_name ?? m?.name ?? '');

  function switchIn() {
    if (person) return;
    sync.create('front.switch', sync.accountScope, sync.newId(), {
      entries: [{ subject_type: 'member', subject_id: id, level: 'front', is_primary: true }],
    });
    router.go('/fronting');
  }
  function archive() {
    if (dirty && !save()) { archiving = false; return; }
    sync.create(m?.archived ? 'member.unarchive' : 'member.archive', sync.accountScope, id, {});
    archiving = false;
    if (!m?.archived) router.go('/members');
  }
</script>

{#if !m}
  <p class="muted">This member doesn't exist (yet). <a href="#/members">Back to members</a></p>
{:else if page === 'who-can-see'}
  <MemberAudience {projection} {id} />
{:else}
  <article class="editor" style="--ring: {colors?.ring}; --avatar-ink: {colors?.avatar_ink}">
    <header>
      <a class="back" href={person ? '#/settings' : '#/members'} aria-label="Back">‹</a>
      <div><h1>Edit member</h1><p>{shownName}</p></div>
      {#if m.deleted}<span class="tag">In Trash</span>{/if}
      <button class="check" aria-label="Save" disabled={!dirty} onclick={saveAndClose}><Icon name="check" size={22} /></button>
    </header>

    <div class="identity">
      <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? draft.name[0] ?? '?'} name={draft.name} /></span>
      <Button size="sm" class="pill" onclick={() => (picture = true)}><Icon name="image" size={18} />Change picture</Button>
    </div>
    <Swatches value={draft.color} custom onpick={(c) => (draft.color = c)} />

    <label class="field strong"><span>Name</span><input bind:value={draft.name} maxlength="100" /></label>
    <div class="pair">
      <label class="field"><span>Pronouns</span><input bind:value={draft.pronouns} maxlength="100" /></label>
      <label class="field"><span>Display name</span><input bind:value={draft.display} maxlength="100" /></label>
    </div>
    <label class="field"><span>Description</span><textarea rows="3" bind:value={draft.bio}></textarea></label>
    {#if error}<p class="error" role="alert">{error}</p>{/if}

    <SectionLabel label="More" />
    <FormCard>
      {#if !person}<ValueRow icon="members" label="Groups" value={groupNames} onclick={() => (groupsOpen = true)} />{/if}
      <ValueRow icon="shield" label="Who can see {shownName}" value={who} href="#/members/{id}/who-can-see" />
      <ValueRow icon="menu" label="Custom fields" value={filled} onclick={() => (fieldsOpen = true)} />
      <ValueRow icon="hash" label="Handles" value={handles ? `@${(m.handles ?? [])[0] ?? handles.default}` : ''} onclick={() => (handlesOpen = true)} />
      <ValueRow icon="image" label="Banner" value={m.banner_blob ? 'Set' : ''} onclick={() => (banner = true)} />
      {#if !person}
        <ValueRow icon="layers" label="Subsystem" value={subsystem?.name ?? ''} onclick={() => (subsystemOpen = true)} />
        <ValueRow icon="smile" label="Sigils and proxy tags" value={sigilText} onclick={() => (speaking = true)} />
        <ValueRow icon="bell" label="Chat notifications" onclick={() => (notifying = true)} />
        <ValueRow icon="folder" label="Folder" value={folderName} onclick={() => (filing = true)} />
        <ValueRow icon="front" label="Switch in" onclick={switchIn} />
      {/if}
      <ValueRow icon="user" label="View profile" href="#/profile/{id}" />
    </FormCard>

    {#if !person}
      <div class="foot">
        {#if m.archived}<button class="restore" onclick={archive}>Restore {shownName}</button>
        {:else}<button class="archive" onclick={() => (archiving = true)}>Archive {shownName}</button>{/if}
        {#if m.deleted}<Button variant="quiet" class="quiet" onclick={() => sync.create('member.restore', sync.accountScope, id, {})}>Restore from Trash</Button>
        {:else}<Button variant="quiet" class="quiet" onclick={() => (trashing = true)}>Move to Trash</Button>{/if}
      </div>
    {/if}

    <div class="savebar"><button class="save" disabled={!dirty} onclick={saveAndClose}>Save</button></div>
  </article>

  {#if picture}
    <Sheet title="Change picture" onclose={() => (picture = false)}>
      <input type="file" accept="image/*" onchange={chooseAvatar} aria-label="Choose member avatar" />
      {#if cropReady}
        <div class="crop">
          <canvas width="256" height="256" bind:this={cropCanvas} aria-label="Avatar crop preview"></canvas>
          <label>Zoom <input type="range" min="1" max="3" step="0.05" bind:value={cropZoom} /></label>
          <label>Left/right <input type="range" min="-1" max="1" step="0.05" bind:value={cropX} /></label>
          <label>Up/down <input type="range" min="-1" max="1" step="0.05" bind:value={cropY} /></label>
        </div>
      {/if}
      {#if avatarError}<p class="error" role="alert">{avatarError}</p>{/if}
      {#snippet footer()}
        {#if cropReady}<button class="save" onclick={saveAvatar}>Use this crop</button>{/if}
        {#if m.avatar_blob}<Button variant="quiet" class="quiet" onclick={() => { set({ avatar_blob: null }); picture = false; }}>Remove</Button>{/if}
      {/snippet}
    </Sheet>
  {/if}
  {#if banner}
    <Sheet title="Banner" subtitle={shownName} onclose={() => (banner = false)}>
      {#if m.banner_blob}<div class="banner-preview"><AvatarImage hash={m.banner_blob} glyph="" name="Profile banner" /></div>{/if}
      <label class="pill file"><Icon name="image" size={18} />{m.banner_blob ? 'Change' : 'Add a banner'}<input type="file" accept="image/*" onchange={chooseBanner} aria-label="Choose member banner" /></label>
      {#if bannerError}<p class="error" role="alert">{bannerError}</p>{/if}
      {#snippet footer()}
        {#if m.banner_blob}<Button variant="quiet" class="quiet" onclick={() => set({ banner_blob: null })}>Remove</Button>{/if}
        <button class="save" onclick={() => (banner = false)}>Done</button>
      {/snippet}
    </Sheet>
  {/if}
  {#if handlesOpen && handles}
    <Sheet title="Handles" subtitle={shownName} onclose={() => (handlesOpen = false)}>
      <FormCard>
        {#each m.handles ?? [] as h, i (h)}
          <div class="handle"><span class="h">@{h}</span>{#if i === 0}<span class="shown">Shown</span>{/if}
            {#if i > 0}<button class="link" onclick={() => firstClaim(h)}>Make first</button>{/if}
            <button class="link" aria-label="Let go of @{h}" onclick={() => set({ handles: (m.handles ?? []).filter((x) => x !== h) })}>Remove</button></div>
        {/each}
        <div class="handle"><span class="h">@{handles.default}</span>{#if !(m.handles ?? []).length}<span class="shown">Shown</span>{/if}</div>
      </FormCard>
      {#if (m.handles ?? []).length < MAX_CLAIMS}
        <form class="claim" onsubmit={(e) => { e.preventDefault(); addClaim(); }}>
          <input bind:value={claim} placeholder="Claim a handle" aria-label="Claim a handle" autocomplete="off" spellcheck="false" maxlength="33" />
          <button class="save" disabled={!claim.trim()}>Claim</button>
        </form>
      {/if}
      {#if claimError}<p class="error" role="alert">{claimError}</p>{/if}
      {#snippet footer()}<button class="save" onclick={() => (handlesOpen = false)}>Done</button>{/snippet}
    </Sheet>
  {/if}
  {#if speaking}
    <Sheet title="Sigils and proxy tags" subtitle={shownName} onclose={() => (speaking = false)}>
      <label class="field"><span>Sigils</span><input value={sigilText} placeholder="🌌" onchange={(e) => set({ sigils: e.currentTarget.value.split(/\s+/).filter(Boolean) })} /></label>
      {#if sigilClash.length}<p class="error">Also used by someone else: {sigilClash.join(' ')}</p>{/if}
      {#each tags as t, i (i)}
        <div class="tag-row">
          <input bind:value={t.prefix} placeholder="prefix" onchange={saveTags} aria-label="Prefix" />
          <span class="muted">text</span>
          <input bind:value={t.suffix} placeholder="suffix" onchange={saveTags} aria-label="Suffix" />
          <button class="link" onclick={() => { tags = tags.filter((_, j) => j !== i); saveTags(); }}>Remove</button>
        </div>
      {/each}
      <button class="dashed" onclick={() => (tags = [...tags, { prefix: '', suffix: '' }])}><Icon name="plus" size={18} />Add proxy tag</button>
      {#snippet footer()}<button class="save" onclick={() => (speaking = false)}>Done</button>{/snippet}
    </Sheet>
  {/if}
  {#if notifying}
    <Sheet title="Chat notifications" subtitle={shownName} onclose={() => (notifying = false)}>
      <p class="label">When someone mentions {shownName}</p>
      <div class="seg" role="radiogroup" aria-label="Mentions">{#each RULES as r (r.id)}<button role="radio" aria-checked={(notifyRule.mentions ?? 'always') === r.id} onclick={() => setNotifyRule('mentions', r.id)}>{r.label}</button>{/each}</div>
      <p class="label">Member DMs to {shownName}</p>
      <div class="seg" role="radiogroup" aria-label="Member DMs">{#each RULES as r (r.id)}<button role="radio" aria-checked={(notifyRule.dms ?? 'fronting') === r.id} onclick={() => setNotifyRule('dms', r.id)}>{r.label}</button>{/each}</div>
      {#snippet footer()}<button class="save" onclick={() => (notifying = false)}>Done</button>{/snippet}
    </Sheet>
  {/if}
{/if}

{#if groupsOpen && m}<MemberGroupsSheet {projection} member={m} onclose={() => (groupsOpen = false)} />{/if}
{#if fieldsOpen && m}<MemberFieldsSheet {m} {defs} {values} {set} onclose={() => (fieldsOpen = false)} />{/if}
{#if subsystemOpen && m}<SubsystemSheet {projection} member={m} {dark} onclose={() => (subsystemOpen = false)} />{/if}
{#if filing}
  <MenuSheet title="Folder" onclose={() => (filing = false)}>
    {#each listPrefs.folders as f (f.id)}<MenuRow label={f.name} icon="folder" selected={listPrefs.folderOf.get(id) === f.id} onclick={() => setFolder(f.id)} />{/each}
    <MenuRow label="Not in a folder" selected={!listPrefs.folderOf.has(id)} onclick={() => setFolder(null)} />
    <form class="claim" onsubmit={(e) => { e.preventDefault(); folderNew(); }}>
      <input bind:value={newFolder} placeholder="New folder" aria-label="New folder name" maxlength="40" />
      <button class="save" disabled={!newFolder.trim()}>Add</button>
    </form>
  </MenuSheet>
{/if}
{#if archiving && m}
  <ConfirmSheet title="Archive {shownName}?" message="{shownName} stays in your history, chats and posts, and can be restored any time. They leave the switcher and member list."
    confirm="Archive" danger icon="archive" onconfirm={archive} onclose={() => (archiving = false)} cancel="Cancel" />
{/if}
{#if trashing && m}
  <ConfirmSheet title="Move {shownName} to Trash?" confirm="Move to Trash" danger onclose={() => (trashing = false)}
    onconfirm={() => { sync.create('member.delete', sync.accountScope, id, {}, actingOpts(projection, sync.accountId)); trashing = false; }} />
{/if}

<style>
  .editor { display: grid; gap: var(--s-3); align-content: start; padding-bottom: 72px; }
  header { display: flex; align-items: center; gap: var(--s-2); }
  header > div { flex: 1; min-width: 0; }
  h1 { margin: 0; font-size: 20px; line-height: 26px; }
  header p { margin: 0; color: var(--ink-3); font-size: var(--fs-sm); }
  .back { display: grid; place-items: center; width: 40px; height: 40px; font-size: var(--fs-2xl); color: var(--ink); text-decoration: none; }
  .check { display: grid; place-items: center; width: 40px; height: 40px; border: 0; background: none; color: var(--accent); cursor: pointer; }
  .check:disabled { color: var(--ink-3); cursor: default; }
  .tag { border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); font-size: var(--fs-xs); padding: 3px 10px; }
  .identity { display: flex; align-items: center; gap: var(--s-4); }
  .avatar { width: 84px; height: 84px; display: grid; place-items: center; border-radius: 50%; overflow: hidden; font-size: var(--fs-3xl); font-weight: 700; background: var(--ring); color: var(--avatar-ink); }
  .pill.file input { display: none; }
  .field { display: grid; gap: 2px; padding: var(--s-2) 14px; border-radius: var(--r-control); background: var(--surface-2); color: var(--ink-3); font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; }
  .field.strong:focus-within { box-shadow: inset 0 0 0 1.5px var(--accent); }
  .field.strong span { color: var(--accent); }
  .field input, .field textarea { font: inherit; font-size: var(--fs-base); letter-spacing: 0; text-transform: none; font-weight: 400; color: var(--ink); background: none; border: 0; padding: 2px 0; min-width: 0; resize: vertical; }
  .field input:focus, .field textarea:focus { outline: none; }
  .pair { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-3); }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
  .muted { color: var(--ink-3); }
  .foot { display: grid; justify-items: center; gap: var(--s-1); }
  .archive, .restore { min-height: 44px; border: 0; background: none; font: inherit; font-size: var(--fs-md); font-weight: 600; cursor: pointer; }
  .archive { color: var(--danger); }
  .restore { color: var(--accent); }
  .savebar { position: fixed; left: 0; right: 0; bottom: 0; display: flex; justify-content: center; padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: var(--bg); z-index: 5; }
  .save { flex: 1; max-width: 560px; min-height: 48px; border: 0; border-radius: var(--r-control); background: var(--accent); color: var(--on-accent); font: inherit; font-size: var(--fs-base); font-weight: 600; cursor: pointer; }
  .save:disabled { background: var(--surface-2); color: var(--ink-3); cursor: default; }
  .crop { display: grid; gap: var(--s-2); justify-items: start; }
  .crop canvas { width: 192px; height: 192px; border-radius: var(--r-md); border: 1px solid var(--line); }
  .crop label { display: flex; align-items: center; gap: var(--s-2); font-size: 14px; color: var(--ink-2); }
  .banner-preview { height: 110px; border-radius: var(--r-control); overflow: hidden; background: var(--surface-2); }
  .banner-preview :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .handle { display: flex; align-items: center; gap: 10px; min-height: 48px; padding: 6px 14px; }
  .handle .h { flex: 1; font-weight: 600; overflow-wrap: anywhere; }
  .shown { font-size: var(--fs-xs); color: var(--accent); background: var(--accent-soft); border-radius: var(--r-full); padding: 1px var(--s-2); }
  .link { border: 0; background: none; color: var(--accent); font: inherit; font-size: 14px; cursor: pointer; }
  .claim { display: flex; gap: var(--s-2); padding: var(--s-1) var(--s-3); }
  .claim input { flex: 1; min-width: 0; font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-row); padding: 10px 14px; }
  .claim .save { flex: none; min-height: 40px; padding: 0 18px; font-size: var(--fs-md); }
  .tag-row { display: flex; align-items: center; gap: var(--s-2); }
  .tag-row input { width: 7em; font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-sm); padding: var(--s-2) 10px; }
  .dashed { display: flex; align-items: center; justify-content: center; gap: var(--s-2); min-height: 48px; border: 1.5px dashed var(--line); border-radius: var(--r-md); background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .label { margin: var(--s-1) 0 0; color: var(--ink-2); font-size: 14px; }
  .seg { display: flex; gap: 3px; padding: 3px; border-radius: var(--r-row); background: var(--surface-2); }
  .seg button { flex: 1; min-height: 34px; border: 0; border-radius: 9px; background: none; color: var(--ink-3); font: inherit; font-size: var(--fs-sm); font-weight: 500; cursor: pointer; }
  .seg button[aria-checked='true'] { background: var(--accent); color: var(--on-accent); font-weight: 600; }
</style>
