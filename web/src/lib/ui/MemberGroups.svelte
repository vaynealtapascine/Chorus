<script lang="ts">
  // The Groups tab (F4.4): every group as a card that opens on its members. Any group, plain or
  // subsystem, can be filled and emptied here; edit, recolour or remove from the card.
  import TextField from './kit/TextField.svelte';
  import Button from './kit/Button.svelte';
  import { groupPath, groups } from '../data';
  import { actingOpts } from '../front-view';
  import { sync, type Projection } from '../sync/client';
  import { groupLine, groupMembers } from '../member-directory';
  import { core } from '../core';
  import AvatarImage from './AvatarImage.svelte';
  import GroupMembersSheet from './GroupMembersSheet.svelte';
  import GroupTile from './GroupTile.svelte';
  import Icon from './Icon.svelte';
  import NewGroupSheet from './NewGroupSheet.svelte';
  import Sheet from './Sheet.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import Swatches from './kit/Swatches.svelte';

  let { projection, dark, creating = $bindable(false) }: { projection: Projection; dark: boolean; creating?: boolean } = $props();
  const gs = $derived(groups(projection));
  let open = $state(new Set<string>());
  let adding = $state<string | null>(null);
  let editing = $state<string | null>(null);
  const toggle = (id: string) => { const next = new Set(open); if (!next.delete(id)) next.add(id); open = next; };

  let name = $state('');
  let color = $state('#42A397');
  let removing = $state(false);
  const editGroup = $derived(gs.find((g) => g.id === editing));
  $effect(() => { if (editGroup) { name = editGroup.name; color = editGroup.color ?? '#42A397'; } });
  function save() {
    const g = editGroup;
    if (!g || !name.trim()) return;
    const fields: Record<string, unknown> = {};
    if (name.trim() !== g.name) fields.name = name.trim();
    if (color !== g.color) fields.color = color;
    if (Object.keys(fields).length) sync.create('group.set', sync.accountScope, g.id, fields);
    editing = null;
  }
</script>

<div class="groups">
  {#if !gs.length}<EmptyState icon="users" label="No groups yet" />{/if}
  {#each gs as g (g.id)}
    {@const inside = groupMembers(projection, g.id)}
    <div class="card">
      <button class="head" aria-expanded={open.has(g.id)} onclick={() => toggle(g.id)}>
        <GroupTile group={g} />
        <span class="info"><strong>{groupPath(g, gs)}</strong><span class="sub">{groupLine(inside.length, g.visibility)}</span></span>
        <span class="chev" class:up={open.has(g.id)}><Icon name="chevron" size={20} /></span>
      </button>
      {#if open.has(g.id)}
        {#each inside as m (m.id)}
          {@const c = core.adaptColor(m.color, dark)}
          <div class="member" style="--ring: {c.ring}; --avatar-ink: {c.avatar_ink}">
            <a href="#/members/{m.id}">
              <span class="avatar" aria-hidden="true"><AvatarImage hash={m.avatar_blob} glyph={m.sigils[0] ?? m.name[0]} name={m.name} /></span>
              <span class="info"><strong>{m.display_name ?? m.name}</strong>{#if m.pronouns}<span class="sub">{m.pronouns}</span>{/if}</span>
            </a>
            <button class="icon" aria-label="Take {m.display_name ?? m.name} out of {g.name}" onclick={() => sync.create('group.remove_member', sync.accountScope, g.id, { member_id: m.id })}><Icon name="close" size={18} /></button>
          </div>
        {/each}
        <div class="foot">
          <button class="link" onclick={() => (adding = g.id)}><Icon name="user-plus" size={18} />{inside.length ? 'Add or remove' : 'Add members'}</button>
          <button class="icon" aria-label="Edit {g.name}" onclick={() => (editing = g.id)}><Icon name="pencil" size={18} /></button>
        </div>
      {/if}
    </div>
  {/each}
  <button class="dashed" onclick={() => (creating = true)}><Icon name="plus" size={18} />New group</button>
</div>

{#if creating}<NewGroupSheet {projection} onclose={() => (creating = false)} oncreated={(id) => { open = new Set([...open, id]); adding = id; }} />{/if}
{#if adding}<GroupMembersSheet {projection} {dark} groupId={adding} onclose={() => (adding = null)} />{/if}
{#if editGroup}
  <Sheet title={editGroup.name} subtitle={groupLine(groupMembers(projection, editGroup.id).length, editGroup.visibility)} onclose={() => (editing = null)}>
    <TextField bind:value={name} aria-label="Name" maxlength={60} />
    <Swatches value={color} onpick={(c) => (color = c)} />
    <button class="remove" onclick={() => (removing = true)}><Icon name="delete" size={20} />Remove {editGroup.name}</button>
    {#snippet footer()}<Button variant="primary" grow class="go" disabled={!name.trim()} onclick={save}>Save</Button>{/snippet}
  </Sheet>
{/if}
{#if removing && editGroup}
  <ConfirmSheet title="Remove {editGroup.name}?" message="Its members stay." confirm="Remove" danger layer={1} onclose={() => (removing = false)}
    onconfirm={() => { sync.create('group.delete', sync.accountScope, editGroup.id, {}, actingOpts(projection, sync.accountId)); removing = false; editing = null; }} />
{/if}

<style>
  .groups { display: grid; gap: var(--s-3); }
  .card { display: grid; border-radius: var(--r-md); background: var(--surface-2); overflow: hidden; }
  .head { display: flex; align-items: center; gap: var(--s-3); padding: 14px; border: 0; background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .info { display: grid; flex: 1; min-width: 0; }
  .info strong { font-size: var(--fs-base); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sub { color: var(--ink-3); font-size: var(--fs-sm); line-height: 18px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .chev { display: inline-flex; color: var(--ink-2); transform: rotate(90deg); transition: transform var(--t-fast) var(--ease); }
  .chev.up { transform: rotate(-90deg); }
  .member { display: flex; align-items: center; padding: var(--s-1) var(--s-1) var(--s-1) 30px; }
  .member a { display: flex; align-items: center; gap: var(--s-3); flex: 1; min-width: 0; padding: var(--s-1) 0; color: var(--ink); text-decoration: none; }
  .member .info strong { font-size: var(--fs-md); }
  .avatar { width: 32px; height: 32px; display: grid; place-items: center; flex: none; border-radius: 50%; overflow: hidden; background: var(--ring); color: var(--avatar-ink); font-weight: 700; font-size: 14px; }
  .icon { display: grid; place-items: center; width: 40px; height: 40px; border: 0; background: none; color: var(--ink-3); cursor: pointer; border-radius: 50%; }
  .foot { display: flex; align-items: center; padding: 0 var(--s-1) 6px 18px; }
  .link { display: inline-flex; align-items: center; gap: var(--s-2); flex: 1; justify-content: flex-start; min-height: 40px; border: 0; background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .dashed { display: flex; align-items: center; justify-content: center; gap: var(--s-2); min-height: 52px; border: 1.5px dashed var(--line); border-radius: var(--r-md); background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .remove { display: flex; align-items: center; gap: var(--s-3); min-height: 48px; border: 0; background: none; color: var(--danger); font: inherit; cursor: pointer; padding: 0 var(--s-1); }
</style>
