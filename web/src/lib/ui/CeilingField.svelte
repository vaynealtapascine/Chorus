<script lang="ts">
  // One Advanced notification detail (UI11c, D-115): inherited until customized, then a typed
  // control. Delays are entered in minutes and stored in seconds; nothing invalid is saved.
  import { choiceLabel, validCeilingValue, type CeilingField } from '../bucket-controls';

  let { field, ceiling, inherited, onchange }: {
    field: CeilingField; ceiling: Record<string, unknown>;
    /** What "Default" means here: "Account default" or "From their buckets". */
    inherited: string;
    onchange: (value: unknown) => void;
  } = $props();

  const custom = $derived(Object.hasOwn(ceiling, field.key));
  const value = $derived(custom ? ceiling[field.key] : field.default);
  const uid = Math.random().toString(36).slice(2, 8);
  const id = $derived(`ceiling-${field.key}-${uid}`);
  let error = $state('');
  function save(v: unknown) {
    if (!validCeilingValue(field.key, v)) { error = 'That value can’t be used.'; return; }
    error = '';
    onchange(v);
  }
  const obj = (v: unknown) => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
  const minutes = (s: unknown) => (typeof s === 'number' ? Math.round(s / 6) / 10 : '');
  const seconds = (m: string) => (m.trim() === '' ? NaN : Math.round(Number(m) * 60));
</script>

<div class="field" class:custom>
  <div class="head">
    <span class="label" id={id}>{field.label}</span>
    {#if custom}<button class="link" onclick={() => onchange(undefined)}>Use {inherited.toLowerCase()}</button>
    {:else}<button class="link" onclick={() => save(field.default)}>Customize</button>{/if}
  </div>
  {#if field.hint}<small>{field.hint}</small>{/if}
  {#if !custom}
    <span class="inherited">{inherited}</span>
  {:else if field.kind === 'bool'}
    <label class="toggle"><input type="checkbox" checked={value === true} aria-labelledby={id} onchange={(e) => save(e.currentTarget.checked)} />{value ? 'On' : 'Off'}</label>
  {:else if field.kind === 'enum'}
    <select aria-labelledby={id} value={value as string} onchange={(e) => save(e.currentTarget.value)}>
      {#each field.choices ?? [] as c (c)}<option value={c}>{choiceLabel(c)}</option>{/each}
    </select>
  {:else if field.kind === 'levels'}
    <div class="checks" role="group" aria-labelledby={id}>
      {#each field.choices ?? [] as c (c)}
        {@const on = Array.isArray(value) && value.includes(c)}
        <label><input type="checkbox" checked={on}
          onchange={(e) => save((field.choices ?? []).filter((x) => (x === c ? e.currentTarget.checked : Array.isArray(value) && value.includes(x))))} />{choiceLabel(c)}</label>
      {/each}
    </div>
  {:else if field.kind === 'range'}
    {@const r = obj(value)}
    <div class="row" role="group" aria-labelledby={id}>
      <label>From <input type="number" min="0" step="1" inputmode="decimal" value={minutes(r.min_s)}
        onchange={(e) => save({ min_s: seconds(e.currentTarget.value), max_s: r.max_s })} /> min</label>
      <label>to <input type="number" min="0" step="1" inputmode="decimal" value={minutes(r.max_s)}
        onchange={(e) => save({ min_s: r.min_s, max_s: seconds(e.currentTarget.value) })} /> min</label>
    </div>
  {:else if field.kind === 'time'}
    {@const t = obj(value)}
    <div class="row">
      <select aria-labelledby={id} value={t.mode as string} onchange={(e) => {
        const mode = e.currentTarget.value;
        save(mode === 'round' ? { mode, round_min: 15 } : mode === 'jitter' ? { mode, jitter_min: 15 } : { mode });
      }}>
        {#each field.choices ?? [] as c (c)}<option value={c}>{choiceLabel(c)}</option>{/each}
      </select>
      {#if t.mode === 'round' || t.mode === 'jitter'}
        {@const k = t.mode === 'round' ? 'round_min' : 'jitter_min'}
        <label>{t.mode === 'round' ? 'to' : 'by up to'} <input type="number" min="1" max="1440" step="1" value={t[k] as number}
          onchange={(e) => save({ mode: t.mode, [k]: Number(e.currentTarget.value) })} /> min</label>
      {/if}
    </div>
  {:else if field.kind === 'limit'}
    <div class="row">
      <label><input type="checkbox" checked={value !== null} onchange={(e) => save(e.currentTarget.checked ? 10 : null)} />Limit</label>
      {#if value !== null}<input type="number" min="0" max="1000" step="1" aria-labelledby={id} value={value as number}
        onchange={(e) => save(Number(e.currentTarget.value))} /> a day{/if}
    </div>
  {:else if field.kind === 'quiet'}
    {@const q = obj(value)}
    <div class="row">
      <label><input type="checkbox" checked={value !== null} onchange={(e) => save(e.currentTarget.checked ? { from: '23:00', to: '08:00' } : null)} />Quiet hours</label>
      {#if value !== null}
        <label>from <input type="time" value={q.from as string} onchange={(e) => save({ ...q, from: e.currentTarget.value })} /></label>
        <label>to <input type="time" value={q.to as string} onchange={(e) => save({ ...q, to: e.currentTarget.value })} /></label>
      {/if}
    </div>
  {/if}
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</div>

<style>
  .field { display: grid; gap: 4px; padding: 10px 0; border-top: 1px solid var(--line); }
  .head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
  .label { color: var(--ink); font-weight: 500; }
  small, .inherited { color: var(--ink-3); font-size: var(--fs-sm); }
  .link { border: 0; background: none; color: var(--accent); font: inherit; font-size: var(--fs-sm); cursor: pointer; padding: 2px 0; white-space: nowrap; }
  .row, .checks { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; color: var(--ink-2); }
  label { display: inline-flex; align-items: center; gap: 6px; }
  .toggle { color: var(--ink-2); }
  input[type='number'] { width: 5.5em; }
  input[type='number'], input[type='time'], select { font: inherit; color: var(--ink); background: var(--surface-2); border: 1px solid var(--line);
    border-radius: var(--r-sm); padding: 5px 8px; }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
</style>
