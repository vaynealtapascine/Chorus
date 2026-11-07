<script lang="ts">
  // Ceiling details as rows (F7.10): each shows its value and opens a sheet to change it or go back
  // to what it inherits, and an inherited one says what that is ("23:00–08:00 · from Everyone else").
  // Switch-outs is a switch. Extra fronts and layers (D-084) and the rarer details come last.
  import { CEILING_FIELDS, withField } from '../bucket-controls';
  import { fieldValue } from '../friends-screens';
  import { sync, type Projection } from '../sync/client';
  import CeilingField from './CeilingField.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';

  let { projection, ceiling, inherited, beneath = null, onchange }: {
    projection: Projection; ceiling: Record<string, unknown>;
    /** what an uncustomized detail follows: "Everyone else", "their buckets" */
    inherited: string;
    /** the values it inherits, when known */
    beneath?: Record<string, unknown> | null;
    onchange: (ceiling: Record<string, unknown>) => void;
  } = $props();
  const MAIN = ['levels', 'delay', 'time', 'stacking', 'announce_leaving', 'quiet_hours'];
  const SHARING = ['share_current_front', 'share_history', 'share_stats'];
  const TITLES: Record<string, string> = { levels: 'Layers', delay: 'Delay before telling', stacking: 'Several switches close together', announce_leaving: 'Switch-outs' };
  const NOTES: Record<string, string> = { delay: 'Hides when you switched', time: 'Hides the exact time afterwards', levels: 'Who counts as here', stacking: 'Several switches in a row', quiet_hours: 'Your system’s time' };
  const fields = Object.fromEntries(CEILING_FIELDS.map((f) => [f.key, f]));
  let open = $state<string | null>(null);
  let more = $state(false);
  let grants = $state(false);
  const custom = (key: string) => Object.hasOwn(ceiling, key);
  const below = (key: string) => (beneath && Object.hasOwn(beneath, key) ? beneath[key] : fields[key]?.default);
  const boolOn = (key: string) => (custom(key) ? ceiling[key] === true : below(key) === true);
  const named = $derived(projection.front_systems?.[sync.accountId]?.system);
  const grantable = $derived(named ? [
    ...Object.values(named.fronts).filter((f) => !f.deleted && f.id !== sync.accountId).map((f) => ({ key: 'fronts' as const, id: f.id, name: f.name, kind: 'Front' })),
    ...Object.values(named.layers).filter((l) => !l.deleted && !['front', 'cocon', 'present'].includes(l.id)).map((l) => ({ key: 'layers' as const, id: l.id, name: l.name, kind: 'Layer' })),
  ] : []);
  const granted = (key: 'fronts' | 'layers') => (Array.isArray(ceiling[key]) ? (ceiling[key] as string[]) : []);
  const grantSummary = $derived.by(() => {
    const names = grantable.filter((g) => granted(g.key).includes(g.id)).map((g) => g.name);
    return names.length ? ['Body', ...names].join(', ') : 'Body only';
  });
  function setGrant(key: 'fronts' | 'layers', id: string, on: boolean) {
    const next = granted(key).filter((x) => x !== id);
    if (on) next.push(id);
    onchange({ ...ceiling, [key]: next.sort() });
  }
</script>

{#each MAIN as key (key)}
  {@const f = fields[key]}
  {#if f?.kind === 'bool'}
    <button class="fr-toggle" role="switch" aria-checked={boolOn(key)} onclick={() => onchange(withField(ceiling, key, !boolOn(key)))}>
      <span class="fr-text"><strong>{TITLES[key] ?? f.label}</strong><span>{key === 'announce_leaving' ? 'Tell them when someone leaves' : f.hint ?? ''}</span></span>
      <span class="fr-switch"></span>
    </button>
  {:else if f}
    <button class="fr-link" onclick={() => (open = key)}>
      <span class="fr-text"><strong>{TITLES[key] ?? f.label}</strong><span>{custom(key) ? NOTES[key] ?? '' : `${fieldValue(key, below(key))} · from ${inherited}`}</span></span>
      <span class="fr-value">{custom(key) ? fieldValue(key, ceiling[key]) : 'Inherit'}</span>
    </button>
  {/if}
{/each}
<button class="fr-link" onclick={() => (grants = true)}><span class="fr-text"><strong>Extra fronts</strong><span>{grantSummary}</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></button>
<button class="fr-link" onclick={() => (more = true)}><span class="fr-text"><strong>More details</strong><span>Daily limit, digest, who’s hidden, “notify now”</span></span><span class="chev"><Icon name="chevron-right" size={18} /></span></button>

{#if open}
  {@const f = fields[open]}
  <Sheet title={TITLES[open] ?? f.label} subtitle={f.hint} layer={1} onclose={() => (open = null)}>
    <CeilingField field={f} {ceiling} inherited={inherited[0].toUpperCase() + inherited.slice(1)} onchange={(v) => onchange(withField(ceiling, f.key, v))} />
  </Sheet>
{/if}
{#if more}
  <Sheet title="More details" subtitle="Each is inherited from {inherited} until you change it" layer={1} onclose={() => (more = false)}>
    {#each CEILING_FIELDS.filter((x) => !MAIN.includes(x.key) && !SHARING.includes(x.key)) as f (f.key)}
      <CeilingField field={f} {ceiling} inherited={inherited[0].toUpperCase() + inherited.slice(1)} onchange={(v) => onchange(withField(ceiling, f.key, v))} />
    {/each}
  </Sheet>
{/if}
{#if grants}
  <Sheet title="Extra fronts" subtitle="They see Body only until you allow more. Delays and privacy rules still apply." layer={1} onclose={() => (grants = false)}>
    {#if !grantable.length}<p class="fr-line quiet">You have no other fronts or custom layers.</p>{/if}
    {#each grantable as g (g.key + g.id)}
      {@const on = granted(g.key).includes(g.id)}
      <button class="fr-toggle" role="switch" aria-checked={on} onclick={() => setGrant(g.key, g.id, !on)}>
        <span class="fr-text"><strong>{g.name}</strong><span>{g.kind}</span></span><span class="fr-switch"></span>
      </button>
    {/each}
  </Sheet>
{/if}

<style>
  .chev { color: var(--ink-3); display: grid; }
</style>
