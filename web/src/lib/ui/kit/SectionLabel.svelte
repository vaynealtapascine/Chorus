<script lang="ts">
  // A section's teal label (Android SectionLabel), with an optional link on the right ("All friends").
  let { label, link, href, onlink }: { label: string; link?: string; href?: string; onlink?: () => void } = $props();
</script>

<h2 class="section-label"><span>{label}</span>
  {#if link && href}<a {href}>{link}</a>{:else if link && onlink}<button onclick={onlink}>{link}</button>{/if}</h2>

<style>
  .section-label { display: flex; align-items: center; gap: 8px; margin: 0; padding: 16px 4px 6px; color: var(--accent); font-size: 13px; font-weight: 600; line-height: 18px; }
  span { flex: 1; }
  a, button { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; font-weight: 500; cursor: pointer; text-decoration: none; }
</style>
