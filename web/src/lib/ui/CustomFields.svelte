<script lang="ts">
  // Custom fields (F4.6): the fields every member's profile has, in the order they show. Drag to
  // reorder; open one to rename it, change its kind, who sees it by default, or retire it. Beside
  // the list on a wide screen, in a sheet on a narrow one. Values are edited on the member.
  import { buckets as bucketRows, fieldDefs, type FieldDef } from '../data';
  import { fieldKeys, nextFieldKey } from '../field-age';
  import { audienceLabel, audienceOf, isPrivate, type Audience } from '../profile-audience';
  import { sync, type Projection } from '../sync/client';
  import AudienceMenuSheet from './AudienceMenuSheet.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import ConfirmSheet from './kit/ConfirmSheet.svelte';
  import DragList from './kit/DragList.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Segmented from './kit/Segmented.svelte';
  import Switch from './kit/Switch.svelte';
  import ValueRow from './kit/ValueRow.svelte';

  let { projection, creating = $bindable(false) }: { projection: Projection; creating?: boolean } = $props();

  type Type = FieldDef['type'];
  const KINDS: { id: Type; label: string }[] = [
    { id: 'text', label: 'Text' }, { id: 'date', label: 'Date' }, { id: 'number', label: 'Number' }, { id: 'url', label: 'Link' }, { id: 'multi_select', label: 'List' },
  ];
  const OTHER: Partial<Record<Type, string>> = { long_text: 'Long text', select: 'One choice', boolean: 'Yes / no', color: 'Colour' };
  const ICONS: Record<Type, string> = { text: 'doc', long_text: 'doc', number: 'hash', date: 'clock', select: 'menu', multi_select: 'menu', boolean: 'check', url: 'globe', color: 'smile' };
  const kindLabel = (t: Type) => KINDS.find((k) => k.id === t)?.label ?? OTHER[t] ?? t;

  const defs = $derived(fieldDefs(projection));
  const buckets = $derived(bucketRows(projection));
  const names = $derived(new Map(buckets.map((b) => [b.id, b.name])));
  const live = $derived(new Set(buckets.map((b) => b.id)));
  const audience = (d: { default_visibility?: unknown }) => audienceOf(d.default_visibility);

  let selected = $state<string | null>(null);
  let draft = $state<{ name: string; type: Type; audience: Audience }>({ name: '', type: 'text', audience: { mode: 'private' } });
  function startNew() { selected = 'new'; draft = { name: '', type: 'text', audience: { mode: 'private' } }; error = ''; }
  $effect(() => { if (creating) { creating = false; startNew(); } });
  const current = $derived(selected && selected !== 'new' ? defs.find((d) => d.id === selected) : undefined);
  let error = $state('');
  let choosing = $state(false);
  let retiring = $state(false);
  let asking = $state(false);

  let wide = $state(typeof matchMedia === 'function' && matchMedia('(min-width: 980px)').matches);
  $effect(() => {
    const q = matchMedia('(min-width: 980px)');
    const on = () => (wide = q.matches);
    q.addEventListener('change', on);
    return () => q.removeEventListener('change', on);
  });

  const taken = (name: string, except?: string) => defs.some((d) => d.id !== except && d.name.trim().toLowerCase() === name.trim().toLowerCase());
  function rename(input: HTMLInputElement) {
    const v = input.value.trim();
    if (!current || v === current.name) return;
    if (!v) { input.value = current.name; return; }
    if (taken(v, current.id)) { error = 'Another field already has that name.'; input.value = current.name; return; }
    error = '';
    sync.create('field.set_def', sync.accountScope, current.id, { name: v });
  }
  const setDef = (fields: Record<string, unknown>) => current && sync.create('field.set_def', sync.accountScope, current.id, fields);
  const kindOptions = $derived.by(() => {
    const t = current?.type ?? draft.type;
    return KINDS.some((k) => k.id === t) ? KINDS : [...KINDS, { id: t, label: kindLabel(t) }];
  });
  function chooseKind(t: Type) {
    if (current) setDef({ type: t, ...(t === 'multi_select' && !current.options.choices ? { options: { ...current.options, choices: [] } } : {}) });
    else draft.type = t;
  }
  function add() {
    const name = draft.name.trim();
    if (!name) return;
    if (taken(name)) { error = 'There is already a field called that.'; asking = false; return; }
    const choice = draft.type === 'select' || draft.type === 'multi_select';
    const key = nextFieldKey(defs.map((d) => d.sort_key));
    sync.create('field.define', sync.accountScope, sync.newId(), {
      name, type: draft.type, default_visibility: draft.audience, ...(choice ? { options: { choices: [] } } : {}), ...(key ? { sort_key: key } : {}),
    });
    asking = false; selected = null; error = '';
  }
  function reorder(ids: string[]) {
    fieldKeys(ids.length).forEach((key, i) => {
      const d = defs.find((x) => x.id === ids[i]);
      if (d && d.sort_key !== key) sync.create('field.set_def', sync.accountScope, d.id, { sort_key: key });
    });
  }
  const close = () => { selected = null; error = ''; };
  const value = $derived(current ? audience(current) : draft.audience);
  const showsAge = $derived((current?.type ?? draft.type) === 'date');
</script>

<div class="fields" class:wide>
  <div class="list">
    {#if !defs.length}<EmptyState icon="menu" label="No fields yet" />{/if}
    <DragList items={defs} onreorder={reorder} label="Reorder fields">
      {#snippet row(d)}
        {@const a = audience(d)}
        {@const quiet = isPrivate(a, live)}
        <button class="item" class:on={selected === d.id} onclick={() => { selected = d.id; error = ''; }}>
          <span class="tile"><Icon name={ICONS[d.type]} size={20} /></span>
          <span class="info"><strong>{d.name}</strong><span>{kindLabel(d.type)}{d.type === 'date' && d.options.show_age ? ' · shows age only' : ''}</span></span>
          <span class="pill" class:quiet><Icon name={quiet ? 'lock' : a.mode === 'server' ? 'globe' : 'users'} size={13} />{audienceLabel(a, names)}</span>
          <Icon name="chevron-right" size={18} />
        </button>
      {/snippet}
    </DragList>
    <button class="dashed" onclick={startNew}><Icon name="plus" size={18} />Add field</button>
  </div>

  {#if selected && wide}<aside class="panel">{@render detail()}</aside>{/if}
</div>

{#if selected && !wide}
  <Sheet title={current?.name ?? 'New field'} onclose={close}>{@render detail()}</Sheet>
{/if}

{#snippet detail()}
  <div class="detail">
    {#if wide}<h2>{current?.name ?? 'New field'}</h2>{/if}
    {#if current}
      <label class="name"><span>Name</span><input value={current.name} maxlength="60" onchange={(e) => rename(e.currentTarget)} onkeydown={(e) => e.key === 'Enter' && e.currentTarget.blur()} /></label>
    {:else}
      <label class="name"><span>Name</span><input bind:value={draft.name} maxlength="60" /></label>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <Segmented label="Type" options={kindOptions} value={current?.type ?? draft.type} onchange={chooseKind} />
    <FormCard>
      <ValueRow label="Who can see it by default" value={audienceLabel(value, names)} onclick={() => (choosing = true)} />
      {#if showsAge && current}
        <Switch label="Show age, not the date" checked={!!current.options.show_age} onchange={(on) => setDef({ options: { ...current.options, show_age: on } })} />
      {/if}
    </FormCard>
    {#if current}
      <button class="retire" onclick={() => (retiring = true)}>Retire this field</button>
    {:else}
      <button class="go" disabled={!draft.name.trim()} onclick={() => (asking = true)}>Add field</button>
    {/if}
  </div>
{/snippet}

{#if choosing}
  <AudienceMenuSheet title="Who can see it by default" {value} own {buckets} layer={wide ? 0 : 1}
    onpick={(a) => { const next = a ?? { mode: 'private' as const }; if (current) setDef({ default_visibility: next }); else draft.audience = next; }} onclose={() => (choosing = false)} />
{/if}
{#if retiring && current}
  <ConfirmSheet title="Retire {current.name}?" message="Nothing is erased." confirm="Retire" danger layer={wide ? 0 : 1} onclose={() => (retiring = false)}
    onconfirm={() => { sync.create('field.delete_def', sync.accountScope, current.id, {}); retiring = false; close(); }} />
{/if}
{#if asking}
  <ConfirmSheet title="Add “{draft.name.trim()}” to every member?" message="Every member gets an empty one." confirm="Add field" layer={wide ? 0 : 1} onclose={() => (asking = false)} onconfirm={add} />
{/if}

<style>
  .fields { display: grid; gap: var(--s-4); align-items: start; }
  .fields.wide { grid-template-columns: minmax(0, 1fr) 320px; }
  .list { display: grid; gap: var(--s-2); }
  .item { display: flex; align-items: center; gap: var(--s-3); flex: 1; min-width: 0; min-height: 60px; padding: var(--s-2) var(--s-2) var(--s-2) 0; border: 0; border-radius: var(--r-row); background: none; color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .item.on { background: var(--surface-2); }
  .item > :global(.icon) { color: var(--ink-3); }
  .tile { width: 40px; height: 40px; display: grid; place-items: center; flex: none; border-radius: var(--r-sm); background: var(--accent-soft); color: var(--accent); }
  .info { display: grid; flex: 1; min-width: 0; }
  .info strong { font-size: var(--fs-base); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .info span { color: var(--ink-3); font-size: var(--fs-sm); }
  .pill { display: inline-flex; align-items: center; gap: var(--s-1); border-radius: var(--r-full); background: var(--accent-soft); color: var(--accent); font-size: var(--fs-xs); font-weight: 500; padding: var(--s-1) 10px; white-space: nowrap; }
  .pill.quiet { background: var(--surface-2); color: var(--ink-2); }
  .dashed { display: flex; align-items: center; justify-content: center; gap: var(--s-2); min-height: 52px; border: 1.5px dashed var(--line); border-radius: var(--r-md); background: none; color: var(--accent); font: inherit; font-weight: 600; cursor: pointer; }
  .panel { padding: var(--s-4); border-radius: var(--r-md); background: var(--surface-2); position: sticky; top: 16px; }
  .detail { display: grid; gap: var(--s-3); }
  h2 { margin: 0; font-size: var(--fs-base); }
  .name { display: grid; gap: 2px; padding: var(--s-2) 14px; border-radius: var(--r-control); background: var(--surface); color: var(--accent); font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; }
  .name:focus-within { box-shadow: inset 0 0 0 1.5px var(--accent); }
  .name input { font: inherit; font-size: var(--fs-base); letter-spacing: 0; text-transform: none; font-weight: 400; color: var(--ink); background: none; border: 0; padding: 2px 0; min-width: 0; }
  .name input:focus { outline: none; }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
  .retire { justify-self: start; border: 0; background: none; color: var(--danger); font: inherit; font-weight: 600; cursor: pointer; padding: var(--s-2) 0; }
  .go { min-height: 44px; border: 0; border-radius: var(--r-control); background: var(--accent); color: var(--on-accent); font: inherit; font-weight: 600; cursor: pointer; }
  .go:disabled { background: var(--surface); color: var(--ink-3); cursor: default; }
</style>
