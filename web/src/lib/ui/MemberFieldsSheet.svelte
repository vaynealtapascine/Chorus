<script lang="ts">
  // A member's custom field values and birthday (F4.2 "Custom fields"). Only values live here: the
  // fields themselves (names, kinds, who sees them by default) are the Custom fields tab. Each value
  // saves when it is let go.
  import Button from './kit/Button.svelte';
  import type { FieldDef, MemberRow } from '../data';
  import { roleColor, rolesOf } from '../member-list';
  import { sync } from '../sync/client';
  import Sheet from './Sheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Switch from './kit/Switch.svelte';

  let { m, defs, values, set, onclose, layer = 0 }: {
    m: MemberRow; defs: FieldDef[]; values: Map<string, unknown>; set: (fields: Record<string, unknown>) => void; onclose: () => void; layer?: number;
  } = $props();

  let error = $state('');
  const setValue = (d: FieldDef, value: unknown) => sync.create('field.set_value', sync.accountScope, null, { member_id: m.id, field_id: d.id, value });
  const shown = (v: unknown) => (v == null || v === '' ? '' : Array.isArray(v) ? v.join(', ') : String(v));

  function save(d: FieldDef, input: HTMLInputElement | HTMLTextAreaElement) {
    const v = input.value.trim();
    error = '';
    if (!v) return setValue(d, null);
    if (d.type === 'number') {
      const n = Number(v);
      if (!Number.isFinite(n)) { error = 'Use a number.'; return; }
      return setValue(d, n);
    }
    if (d.type === 'url') {
      let ok = false;
      try { const u = new URL(v); ok = (u.protocol === 'https:' || u.protocol === 'http:') && !!u.hostname && !u.username; } catch { ok = false; }
      if (!ok || /\s/.test(v)) { error = 'Use a full link starting with https://'; return; }
    }
    if (d.type === 'text' && v.length > 500) { error = 'Keep it under 500 characters.'; return; }
    setValue(d, v);
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
  function saveBirthday(input: HTMLInputElement) {
    const v = input.value.trim();
    if (v && !/^(\d{4}|-)-\d{2}-\d{2}$/.test(v)) { error = 'Use a date.'; return; }
    error = '';
    set({ birthday: v || null });
  }
</script>

<Sheet title="Custom fields" subtitle={m.display_name ?? m.name} {onclose} {layer}>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
  <FormCard>
    <label class="field"><span>Birthday</span>
      <input type="date" value={m.birthday && !m.birthday.startsWith('--') ? m.birthday : ''} aria-label="Birthday" onchange={(e) => saveBirthday(e.currentTarget)} /></label>
    {#each defs as d (d.id)}
      {@const v = values.get(d.id)}
      {#if d.type === 'select' || d.type === 'multi_select'}
        {@const chosen = rolesOf(v)}
        <div class="field"><span>{d.name}</span>
          <div class="choices" role="group" aria-label={d.name}>
            {#each d.options.choices ?? [] as c (c)}
              <button type="button" class="choice" class:on={chosen.includes(c)} aria-pressed={chosen.includes(c)} style="--pill: {roleColor(c)}" onclick={() => toggleChoice(d, chosen, c)}>{c}</button>
            {/each}
            {#each chosen.filter((c) => !(d.options.choices ?? []).includes(c)) as c (c)}
              <button type="button" class="choice on" aria-pressed="true" style="--pill: {roleColor(c)}" onclick={() => toggleChoice(d, chosen, c)}>{c}</button>
            {/each}
            <input class="add" placeholder="Add" aria-label="Add a choice to {d.name}" onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addChoice(d, chosen, e.currentTarget); } }} onblur={(e) => addChoice(d, chosen, e.currentTarget)} />
          </div>
        </div>
      {:else if d.type === 'boolean'}
        <Switch label={d.name} checked={v === true} onchange={(on) => setValue(d, on)} />
      {:else if d.type === 'color'}
        <label class="field"><span>{d.name}</span>
          <span class="colour"><input type="color" value={typeof v === 'string' ? v : '#888888'} aria-label={d.name} onchange={(e) => setValue(d, e.currentTarget.value)} />
            {#if typeof v === 'string'}<button class="link" onclick={() => setValue(d, null)}>Clear</button>{/if}</span></label>
      {:else if d.type === 'long_text'}
        <label class="field"><span>{d.name}</span><textarea rows="3" value={shown(v)} onblur={(e) => save(d, e.currentTarget)}></textarea></label>
      {:else}
        <label class="field"><span>{d.name}</span>
          <input type={d.type === 'number' ? 'number' : d.type === 'date' ? 'date' : d.type === 'url' ? 'url' : 'text'} value={shown(v)} onchange={(e) => save(d, e.currentTarget)} /></label>
      {/if}
    {/each}
  </FormCard>
  {#if !defs.length}<EmptyState icon="members" label="No fields yet" action="Custom fields" href="#/members/fields" />{/if}
  {#snippet footer()}<Button variant="primary" grow class="go" onclick={onclose}>Done</Button>{/snippet}
</Sheet>

<style>
  .field { display: grid; gap: var(--s-1); padding: 10px 14px; color: var(--ink-3); font-size: var(--fs-xs); }
  .field input, .field textarea { font: inherit; font-size: var(--fs-md); color: var(--ink); background: none; border: 0; padding: var(--s-1) 0; min-width: 0; resize: vertical; }
  .field input:focus, .field textarea:focus { outline: none; box-shadow: 0 1px 0 var(--accent); }
  .choices { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
  .choice { font: inherit; font-size: var(--fs-sm); padding: 3px 10px; border-radius: var(--r-full); cursor: pointer; color: var(--ink-2); background: none; border: 1px dashed var(--line); }
  .choice.on { color: var(--ink); font-weight: 600; background: color-mix(in srgb, var(--pill) 28%, var(--surface)); border: 1px solid color-mix(in srgb, var(--pill) 55%, transparent); }
  .field .add { width: 7em; font-size: var(--fs-sm); padding: 3px 10px; border-radius: var(--r-full); background: var(--surface); }
  .colour { display: inline-flex; align-items: center; gap: var(--s-3); }
  .colour input { width: 48px; height: 32px; padding: 2px; }
  .link { border: 0; background: none; color: var(--accent); font: inherit; cursor: pointer; }
  .error { margin: 0; color: var(--danger); font-size: var(--fs-sm); }
</style>
