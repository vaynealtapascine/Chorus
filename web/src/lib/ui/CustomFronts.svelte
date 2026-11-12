<script lang="ts">
  // Custom fronts (F4.1's third tab): the states that can front instead of a member, e.g. "Blurry".
  // Removing one keeps its history.
  import Button from './kit/Button.svelte';
  import { sync, type Projection } from '../sync/client';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import Swatches from './kit/Swatches.svelte';

  let { projection, creating = $bindable(false) }: { projection: Projection; creating?: boolean } = $props();
  interface State { id: string; name: string; description: string; color: string | null; icon: string | null }
  const states = $derived<State[]>(Object.entries(projection.rows.custom_state ?? {})
    .filter(([, r]) => r.exists && !r.fields.deleted_at)
    .map(([id, r]) => ({
      id, name: String(r.fields.name ?? 'Front'), description: typeof r.fields.description === 'string' ? r.fields.description : '',
      color: typeof r.fields.color === 'string' && /^#[0-9a-f]{6}$/i.test(r.fields.color) ? r.fields.color : null,
      icon: typeof r.fields.icon === 'string' && r.fields.icon && r.fields.icon !== '?' ? r.fields.icon : null,
    }))
    .sort((a, b) => a.name.localeCompare(b.name)));

  let editing = $state<string | null>(null);
  let removing = $state(false);
  let name = $state('');
  let about = $state('');
  let color = $state<string | null>(null);
  const current = $derived(states.find((s) => s.id === editing));
  const open = $derived(creating || !!current);
  $effect(() => { if (current) { name = current.name; about = current.description; color = current.color; } else if (creating) { name = ''; about = ''; color = null; } });
  const close = () => { creating = false; editing = null; };

  function save() {
    const title = name.trim();
    if (!title) return;
    const fields: Record<string, unknown> = {};
    if (!current || title !== current.name) fields.name = title;
    if (about.trim() !== (current?.description ?? '')) fields.description = about.trim() || null;
    if (color !== (current?.color ?? null) && color) fields.color = color;
    if (!current) sync.create('state.create', sync.accountScope, sync.newId(), { ...fields, icon: '?' });
    else if (Object.keys(fields).length) sync.create('state.set', sync.accountScope, current.id, fields);
    close();
  }
</script>

<div class="list">
  {#if !states.length}<EmptyState icon="layers" label="None yet" />{/if}
  {#each states as s (s.id)}
    <button class="row" onclick={() => (editing = s.id)}>
      <span class="tile" style={s.color ? `--c: ${s.color}` : undefined}>{#if s.icon}{s.icon}{:else}<Icon name="layers" size={20} />{/if}</span>
      <span class="info"><strong>{s.name}</strong>{#if s.description}<span>{s.description}</span>{/if}</span>
      <Icon name="chevron-right" size={18} />
    </button>
  {/each}
  <button class="dashed" onclick={() => (creating = true)}><Icon name="plus" size={18} />New custom front</button>
</div>

{#if open}
  <Sheet title={current?.name ?? 'New custom front'} onclose={close}>
    <input class="field" bind:value={name} placeholder="Name" aria-label="Name" maxlength="40" />
    <input class="field" bind:value={about} placeholder="Description" aria-label="Description" maxlength="200" />
    <Swatches value={color} onpick={(c) => (color = c)} />
    {#if current}<button class="remove" onclick={() => (removing = true)}><Icon name="delete" size={20} />Remove {current.name}</button>{/if}
    {#snippet footer()}<Button variant="primary" grow class="go" disabled={!name.trim()} onclick={save}>{current ? 'Save' : 'Add'}</Button>{/snippet}
  </Sheet>
{/if}
{#if removing && current}
  <ConfirmSheet title="Remove {current.name}?" message="Its history stays." confirm="Remove" danger layer={1} onclose={() => (removing = false)}
    onconfirm={() => { sync.create('state.delete', sync.accountScope, current.id, {}); removing = false; close(); }} />
{/if}

<style>
  .list { display: grid; gap: var(--s-1); }
  .row { display: flex; align-items: center; gap: var(--s-3); min-height: 60px; padding: var(--s-2) var(--s-2) var(--s-2) 6px; border: 0; border-radius: var(--r-row); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .row:hover { background: var(--surface-2); }
  .row > :global(.icon) { color: var(--ink-3); }
  .tile { width: 40px; height: 40px; display: grid; place-items: center; flex: none; border-radius: var(--r-sm); font-size: var(--fs-lg);
    background: color-mix(in srgb, var(--c, var(--ink-3)) 22%, var(--surface)); color: var(--c, var(--ink-2)); }
  .info { display: grid; flex: 1; min-width: 0; }
  .info strong { font-size: var(--fs-base); font-weight: 600; }
  .info span { color: var(--ink-3); font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .dashed { display: flex; align-items: center; justify-content: center; gap: var(--s-2); min-height: 52px; margin-top: var(--s-2); border: 1.5px dashed var(--line); border-radius: var(--r-md); background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .field { font: inherit; color: var(--ink); background: var(--surface-2); border: 0; border-radius: var(--r-row); padding: var(--s-3) 14px; }
  .remove { display: flex; align-items: center; gap: var(--s-3); min-height: 48px; border: 0; background: none; color: var(--danger); font: inherit; cursor: pointer; padding: 0 var(--s-1); }
</style>
