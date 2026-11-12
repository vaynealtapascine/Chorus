<script lang="ts">
  // A member's state (Figma F3.10; Android MemberStatePage): any of the common states, the system's own and
  // their own words, a note, and whether it clears when they stop fronting (`member_status:<id>`).
  import { untrack } from 'svelte';
  import * as fd from '../fronting-display';
  import type { Who } from '../fronting-subjects';
  import { sync, type Projection } from '../sync/client';
  import Face from './Face.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Switch from './kit/Switch.svelte';

  let { projection, dark, who, onclose, layer = 0 }: { projection: Projection; dark: boolean; who: Who; onclose: () => void; layer?: number } = $props();
  const states = $derived(fd.customStates(projection.rows.custom_state));
  // opened once on this member: what they have now seeds the form
  const current = untrack(() => fd.memberStatuses(projection.rows.pref, sync.accountId).get(who.id) ?? null);
  const own = untrack(() => fd.customStates(projection.rows.custom_state));
  let picked = $state<string[]>([...(current?.tags ?? [])]);
  let offered = $state<string[]>(fd.statusChoices(own, current));
  let note = $state(current?.note ?? '');
  let clear = $state(current?.clearOnLeave ?? true);
  let typing = $state(false);
  let words = $state('');
  const ready = $derived(picked.length > 0 || !!note.trim());
  const toggle = (t: string) => (picked = picked.includes(t) ? picked.filter((x) => x !== t) : [...picked, t]);
  function addWords() {
    const t = words.trim().slice(0, fd.MAX_STATUS_TAG);
    if (t) { if (!offered.includes(t)) offered = [...offered, t]; if (!picked.includes(t)) picked = [...picked, t]; }
    words = ''; typing = false;
  }
  function set(clearIt = false) {
    if (!clearIt && !ready) return;
    fd.setStatus(who.id, clearIt ? null : { ...current, tags: picked, note: note.trim() || null, at: Date.now(), clearOnLeave: clear }, states);
    onclose();
  }
</script>

<Sheet title="{who.name}'s state" {onclose} {layer}>
  <div class="who"><Face {who} size={56} {dark} />
    <div class="titles"><strong>{who.name}</strong>
      {#if picked.length}<span class="picked">{#each picked as t (t)}<span>{fd.statusLabel(t, states)}</span>{/each}</span>{/if}</div></div>
  <div class="fr-chips pad" role="group" aria-label="State">
    {#each offered as t (t)}
      {@const on = picked.includes(t)}
      <button class="fr-chip" role="checkbox" aria-checked={on} class:on onclick={() => toggle(t)}>{#if on}<Icon name="check" size={14} />{/if}{fd.statusLabel(t, states)}</button>
    {/each}
    <button class="fr-chip" onclick={() => (typing = true)}><Icon name="plus" size={14} />Custom</button>
  </div>
  <!-- svelte-ignore a11y_autofocus -->
  {#if typing}<input class="own" bind:value={words} maxlength={fd.MAX_STATUS_TAG} placeholder="Custom" aria-label="Custom" autofocus
    onkeydown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addWords(); } }} onblur={addWords} />{/if}
  <textarea class="own" bind:value={note} maxlength={fd.MAX_STATUS_NOTE} rows="2" placeholder="Note (optional)" aria-label="Note (optional)"></textarea>
  <FormCard><Switch label="Clear when {who.name} stops fronting" bind:checked={clear} /></FormCard>
  {#snippet footer()}
    <button class="fr-wide neutral" disabled={!current} onclick={() => set(true)}>Clear</button>
    <button class="fr-wide" disabled={!ready} onclick={() => set()}>Set state</button>
  {/snippet}
</Sheet>

<style>
  .who { display: flex; align-items: center; gap: 14px; }
  .who strong { font-size: var(--fs-lg); font-weight: 600; }
  .titles { display: grid; min-width: 0; }
  .picked { display: flex; flex-wrap: wrap; gap: var(--s-1); margin-top: var(--s-1); }
  .picked span { padding: 3px 10px; border-radius: var(--r-full); background: var(--accent-soft); color: var(--accent); font-size: var(--fs-xs); font-weight: 500; }
  .pad { padding: 0; }
  .own { font: inherit; color: var(--ink); background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-3) 14px; resize: vertical; }
</style>
