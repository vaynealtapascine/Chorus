<script lang="ts">
  // A question before something that can't be taken back, or that reaches other people (Android
  // ConfirmDrawer): an icon disc, the question, maybe a line, then the action and Cancel.
  import type { Snippet } from 'svelte';
  import Icon from '../Icon.svelte';
  import Sheet from '../Sheet.svelte';

  let { title, message, confirm, onconfirm, onclose, danger = false, cancel = 'Cancel', busy = false, disabled = false, icon, children, layer = 0 }: {
    title: string; message?: string | null; confirm: string; onconfirm: () => void; onclose: () => void; danger?: boolean;
    cancel?: string; busy?: boolean; disabled?: boolean; icon?: string; children?: Snippet; layer?: number;
  } = $props();
</script>

<Sheet label={title} onclose={() => { if (!busy) onclose(); }} {layer}>
  <div class="question" class:danger>
    <span class="disc" aria-hidden="true"><Icon name={icon ?? (danger ? 'delete' : 'check')} size={22} /></span>
    <h2>{title}</h2>
  </div>
  {#if message}<p class="message">{message}</p>{/if}
  {#if children}{@render children()}{/if}
  {#snippet footer()}
    <div class="buttons">
      <button class="go" class:danger disabled={disabled || busy} onclick={onconfirm}>{confirm}</button>
      <button class="quiet" disabled={busy} onclick={onclose}>{cancel}</button>
    </div>
  {/snippet}
</Sheet>

<style>
  .question { display: flex; align-items: center; gap: 14px; padding: var(--s-3) var(--s-1) 0; }
  .disc { width: 44px; height: 44px; flex: none; display: grid; place-items: center; border-radius: 50%;
    background: color-mix(in oklab, var(--accent) 16%, transparent); color: var(--accent); }
  .danger .disc { background: color-mix(in oklab, var(--danger) 16%, transparent); color: var(--danger); }
  h2 { flex: 1; margin: 0; font-size: 19px; line-height: 24px; font-weight: 600; }
  .message { margin: 0; padding: 0 var(--s-1); color: var(--ink-2); font-size: var(--fs-md); line-height: 21px; }
  .buttons { display: grid; gap: 6px; width: 100%; }
  .go, .quiet { min-height: 44px; padding: 10px var(--s-4); border: 0; border-radius: var(--r-control); font: inherit; font-size: var(--fs-md); font-weight: 600; cursor: pointer; }
  .go { background: var(--accent); color: var(--on-accent); }
  .go.danger { background: var(--danger); }
  .go:disabled { background: var(--surface-3, var(--surface-2)); color: var(--ink-3); cursor: default; }
  .quiet { background: none; color: var(--ink-2); }
</style>
