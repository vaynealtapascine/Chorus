<script lang="ts">
  // Advanced notification controls (UI11c, D-115): every ceiling detail, each inherited until
  // customized. The same controls serve the account default, a bucket and a follow override.
  import { CEILING_FIELDS, withField } from '../bucket-controls';
  import CeilingField from './CeilingField.svelte';

  let { ceiling, inherited, omit = [], onchange }: {
    ceiling: Record<string, unknown>;
    /** What an uncustomized detail follows, e.g. "Account default". */
    inherited: string;
    /** Details this screen already has its own control for. */
    omit?: string[];
    onchange: (ceiling: Record<string, unknown>) => void;
  } = $props();
  const fields = $derived(CEILING_FIELDS.filter((f) => !omit.includes(f.key)));
  const customized = $derived(fields.filter((f) => Object.hasOwn(ceiling, f.key)).length);
</script>

<details class="ceiling-controls">
  <summary>Advanced notification controls{#if customized} · {customized} customized{/if}</summary>
  <p class="hint">Delay hides <em>when</em> you switched as it happens; the time shown hides the exact time afterwards. Use both for
    real privacy. A follower in several buckets gets the most open setting for each detail; a follower's own setting wins.</p>
  {#each fields as field (field.key)}
    <CeilingField {field} {ceiling} {inherited} onchange={(v) => onchange(withField(ceiling, field.key, v))} />
  {/each}
</details>

<style>
  details { width: 100%; min-width: 0; }
  summary { cursor: pointer; color: var(--accent); padding-block: var(--s-2); }
  .hint { font-size: var(--fs-sm); color: var(--ink-3); max-width: 65ch; margin: 0 0 var(--s-1); }
</style>
