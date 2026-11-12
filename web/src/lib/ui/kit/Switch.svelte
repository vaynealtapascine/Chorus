<script lang="ts">
  // The app's switch, 40 x 24 (Android ChorusSwitch). With a label it is a whole row (Android ToggleRow):
  // the label, a line under it when it carries data, and the switch; the row toggles.
  let { checked = $bindable(false), label, sub, disabled = false, onchange, aria }: {
    checked?: boolean; label?: string; sub?: string | null; disabled?: boolean; onchange?: (on: boolean) => void;
    /** the accessible name of a bare switch */
    aria?: string;
  } = $props();
  function flip() { if (disabled) return; checked = !checked; onchange?.(checked); }
</script>

{#if label}
  <button type="button" class="toggle-row" role="switch" aria-checked={checked} {disabled} onclick={flip}>
    <span class="text"><strong>{label}</strong>{#if sub}<span>{sub}</span>{/if}</span>
    <span class="track" class:on={checked} aria-hidden="true"></span>
  </button>
{:else}
  <button type="button" class="bare" role="switch" aria-checked={checked} aria-label={aria} {disabled} onclick={flip}><span class="track" class:on={checked}></span></button>
{/if}

<style>
  .toggle-row { display: flex; align-items: center; gap: var(--s-4); width: 100%; min-height: 52px; padding: var(--s-2) 14px; border: 0; background: none;
    color: var(--ink); font: inherit; text-align: left; cursor: pointer; }
  .text { flex: 1; min-width: 0; display: grid; }
  .text strong { font-size: var(--fs-md); font-weight: 400; line-height: 20px; }
  .text span { color: var(--ink-3); font-size: var(--fs-sm); line-height: 17px; }
  .bare { display: inline-grid; place-items: center; min-width: 44px; min-height: 44px; padding: 0; border: 0; background: none; cursor: pointer; }
  .track { position: relative; width: 40px; height: 24px; flex: none; border-radius: var(--r-row); background: var(--surface-3);
    transition: background var(--t-fast) var(--ease); }
  .track::after { content: ''; position: absolute; top: 3px; left: 3px; width: 18px; height: 18px; border-radius: 50%; background: var(--ink-3);
    transition: transform var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
  .track.on { background: var(--accent); }
  .track.on::after { transform: translateX(16px); background: var(--bg); }
  button:disabled { cursor: default; }
  button:disabled .track, .toggle-row:disabled .text { opacity: 0.5; }
</style>
