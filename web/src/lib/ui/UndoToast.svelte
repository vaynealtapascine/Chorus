<script lang="ts">
  import { undo } from '../front.svelte';
</script>

{#if undo.pending}
  <div class="toast" role="status">
    <span>{undo.pending.label}</span>
    <button onclick={() => undo.undo()}>Undo</button>
  </div>
{/if}

<style>
  .toast {
    position: fixed;
    left: 50%;
    bottom: calc(76px + env(safe-area-inset-bottom));
    transform: translateX(-50%);
    /* left: 50% would cap an auto width at half the screen and wrap short labels */
    width: max-content;
    max-width: calc(100vw - 32px);
    z-index: 20;
    display: flex;
    gap: var(--s-4);
    align-items: center;
    background: var(--ink);
    color: var(--bg);
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-2) var(--s-2) var(--s-5);
    box-shadow: var(--shadow-pop);
    animation: rise var(--t-base) var(--ease);
  }
  button {
    background: none;
    border: 0;
    color: var(--accent-soft);
    font-weight: 600;
    cursor: pointer;
    padding: var(--s-2) var(--s-3);
  }
  @keyframes rise {
    from {
      transform: translate(-50%, 8px);
      opacity: 0;
    }
  }
</style>
