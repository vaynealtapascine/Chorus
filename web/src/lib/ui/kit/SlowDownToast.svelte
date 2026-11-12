<script lang="ts">
  // "Catching its breath. Try again in 12 seconds.": a pill above the bottom bar, counting down (F10.7).
  import { onSlowDown, slowDownText } from '../../notices';

  let until: number | null = $state(null);
  let unknown = $state(false);
  let left: number | null = $state(null);
  $effect(() => onSlowDown((s) => { unknown = s == null; until = Date.now() + (s ?? 4) * 1000; }));
  $effect(() => {
    if (until == null) return;
    const end = until;
    const tick = () => {
      const r = Math.ceil((end - Date.now()) / 1000);
      if (r <= 0) { until = null; return; }
      left = r;
    };
    tick();
    const t = setInterval(tick, 250);
    return () => clearInterval(t);
  });
</script>

{#if until != null}
  <div class="toast" role="status">{slowDownText(unknown ? null : left)}</div>
{/if}

<style>
  .toast { position: fixed; left: 50%; bottom: calc(76px + env(safe-area-inset-bottom)); transform: translateX(-50%); width: max-content;
    max-width: calc(100vw - 32px); z-index: 20; background: var(--surface-2); color: var(--ink); border-radius: var(--r-full);
    padding: var(--s-2) var(--s-5); font-size: var(--fs-sm); box-shadow: var(--shadow-pop); }
</style>
