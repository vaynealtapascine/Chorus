<script module lang="ts">
  export type StageTab = 'select' | 'filters' | 'style' | 'redact' | 'names';
</script>

<script lang="ts">
  // The frame every stage shares (F8.2, F8.8): a bar with Save and Capture, the preview, the five tools
  // as tabs under a panel (beside the preview on wide screens), and, while capturing, nothing but the
  // preview and two pills that fade after two seconds.
  import { onDestroy, type Snippet } from 'svelte';
  import Icon from '../Icon.svelte';

  const TABS: { id: StageTab; label: string; icon: string }[] = [
    { id: 'select', label: 'Select', icon: 'select' }, { id: 'filters', label: 'Filters', icon: 'sliders' }, { id: 'style', label: 'Style', icon: 'format-text' },
    { id: 'redact', label: 'Redact', icon: 'eye-off' }, { id: 'names', label: 'Names & times', icon: 'edit' },
  ];

  let { title = 'Stage', subtitle, closeHref, tab = $bindable('select'), capturing = $bindable(false), canCapture = true, onsave, preview, panel }: {
    title?: string; subtitle: string; closeHref: string; tab?: StageTab; capturing?: boolean; canCapture?: boolean; onsave: () => void;
    preview: Snippet; panel: Snippet;
  } = $props();

  let pills = $state(true);
  let timer: ReturnType<typeof setTimeout> | undefined;
  function showPills() {
    pills = true;
    clearTimeout(timer);
    timer = setTimeout(() => (pills = false), 2000);
  }
  function capture() { capturing = true; showPills(); }
  $effect(() => { document.body.classList.toggle('capture', capturing); });
  onDestroy(() => { clearTimeout(timer); document.body.classList.remove('capture'); });
</script>

<svelte:window onkeydown={(e) => { if (e.key === 'Escape' && capturing) capturing = false; }} />

<div class="stage" class:capturing>
  {#if !capturing}
    <header class="bar">
      <a class="icon-btn" href={closeHref} aria-label="Close stage"><Icon name="close" size={22} /></a>
      <div class="titles"><strong>{title}</strong><span>{subtitle}</span></div>
      <button class="icon-btn" aria-label="Save stage" onclick={onsave}><Icon name="bookmark" size={22} /></button>
      <button class="capture" disabled={!canCapture} onclick={capture}><Icon name="camera" size={16} />Capture</button>
    </header>
  {/if}
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
  <div class="preview" onclick={() => capturing && !pills && showPills()}>
    {@render preview()}
  </div>
  {#if !capturing}
    <div class="tools">
      <div class="panel">{@render panel()}</div>
      <div class="tabs" role="tablist" aria-label="Stage tools">
        {#each TABS as t (t.id)}
          <button role="tab" aria-selected={tab === t.id} onclick={() => (tab = t.id)}><span class="pill"><Icon name={t.icon} size={20} /></span>{t.label}</button>
        {/each}
      </div>
    </div>
  {:else}
    <div class="pills" class:gone={!pills} aria-hidden={!pills}>
      <span class="hint"><Icon name="camera" size={16} />Take a screenshot now · long screenshots work too</span>
      <button class="done" onclick={() => (capturing = false)}><Icon name="check" size={18} />Done</button>
    </div>
  {/if}
</div>

<style>
  .stage { position: fixed; inset: 0; z-index: 45; display: grid; grid-template-rows: auto minmax(0, 1fr) auto; background: var(--bg); color: var(--ink); }
  .stage.capturing { grid-template-rows: minmax(0, 1fr); }
  .bar { display: flex; align-items: center; gap: var(--s-1); padding: var(--s-1) var(--s-3) var(--s-1) var(--s-1); background: var(--surface); border-bottom: 1px solid var(--line); }
  .icon-btn { width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; background: none; color: var(--ink-2); cursor: pointer; text-decoration: none; }
  .titles { flex: 1; min-width: 0; display: grid; padding-left: var(--s-1); }
  .titles strong { font-size: 17px; line-height: 22px; }
  .titles span { color: var(--ink-3); font-size: var(--fs-xs); line-height: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .capture { display: inline-flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 14px; border: 0; border-radius: 18px; background: var(--accent); color: var(--on-accent);
    font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; }
  .capture:disabled { background: var(--surface-3); color: var(--ink-3); cursor: default; }
  .preview { overflow: auto; min-height: 0; overscroll-behavior: contain; }
  .tools { display: flex; flex-direction: column; min-height: 0; }
  .panel { display: grid; grid-template-columns: minmax(0, 1fr); align-content: start; gap: 10px; max-height: 330px; overflow: auto; padding: 14px var(--s-4); background: var(--surface); border-radius: 20px 20px 0 0; }
  .tabs { display: flex; padding: var(--s-1); background: var(--surface); }
  .tabs button { flex: 1; min-width: 0; display: grid; justify-items: center; gap: 3px; padding: 6px 0; border: 0; border-radius: var(--r-row); background: none; color: var(--ink-2);
    font: inherit; font-size: 11px; line-height: 14px; font-weight: 500; cursor: pointer; white-space: nowrap; }
  .pill { display: grid; place-items: center; width: 48px; height: 26px; border-radius: 13px; }
  .tabs button[aria-selected='true'] { color: var(--accent); font-weight: 600; }
  .tabs button[aria-selected='true'] .pill { background: var(--accent-soft); }
  @media (min-width: 900px) {
    .stage { grid-template-columns: minmax(0, 1fr) 380px; grid-template-rows: auto minmax(0, 1fr); }
    .stage.capturing { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr); }
    .bar { grid-column: 1 / -1; }
    .tools { flex-direction: column-reverse; justify-content: flex-end; border-left: 1px solid var(--line); background: var(--surface); }
    .panel { max-height: none; flex: 1; border-radius: 0; }
    .tabs { border-bottom: 1px solid var(--line); }
  }
  .pills { position: fixed; inset: 0; pointer-events: none; display: flex; flex-direction: column; justify-content: space-between; align-items: center; padding: var(--s-3) var(--s-4) 28px; transition: opacity 400ms; }
  .pills.gone { opacity: 0; }
  .hint, .done { pointer-events: auto; display: inline-flex; align-items: center; gap: var(--s-2); border-radius: var(--r-lg); background: var(--surface-2); color: var(--ink); font-size: var(--fs-sm); }
  .hint { padding: var(--s-2) 14px; color: var(--ink); } .hint :global(.icon) { color: var(--accent); }
  .done { min-height: 44px; padding: 0 var(--s-5); border: 0; font: inherit; font-size: var(--fs-md); font-weight: 600; cursor: pointer; }
  .done :global(.icon) { color: var(--accent); }
  .gone .hint, .gone .done { pointer-events: none; }
</style>
