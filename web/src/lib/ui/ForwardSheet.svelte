<script lang="ts">
  // Forward to… (Figma F5.20), also Reply in… and Quote in…: a search, a grid of chats' faces, the red card
  // when the copy leaves where it was written (in place of a confirm), "Show who originally wrote it", and a
  // comment with send. With `onpick`, one tap on a chat is the whole choice (a chat that leaves asks first).
  import { core } from '../core';
  import type { MemberRow } from '../data';
  import { destinations } from '../chat-forward';
  import AvatarImage from './AvatarImage.svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import EmptyState from './kit/EmptyState.svelte';
  import FormNote from './kit/FormNote.svelte';
  import Switch from './kit/Switch.svelte';

  export interface Target { id: string; title: string; names: (string | null | undefined)[]; at: number; disc: { glyph: string; color: string; blob?: string } }

  let { targets, dark, speaker = null, busy = false, label, placeholder = 'Forward to…', exclude, warning = () => null, onpick, onsend, onclose }: {
    targets: Target[]; dark: boolean; speaker?: MemberRow | null; busy?: boolean; label: string; placeholder?: string;
    /** the chat a reply is already in */
    exclude?: string;
    /** the red card for a chat, or null when nothing leaves */
    warning?: (id: string) => { title: string; text: string } | null;
    onpick?: (id: string) => void;
    onsend?: (id: string, comment: string, showAuthors: boolean) => void;
    onclose: () => void;
  } = $props();

  let query = $state('');
  let chosenId = $state<string | null>(null);
  let comment = $state('');
  let showAuthors = $state(true);
  const shown = $derived(destinations(targets, query, (t) => [t.title, ...t.names], (t) => t.at, (t) => t.id === exclude));
  const chosen = $derived(targets.find((t) => t.id === chosenId));
  const warn = $derived(chosen ? warning(chosen.id) : null);
  const color = (hex: string) => core.adaptColor(hex, dark);
  function choose(t: Target) {
    if (busy) return;
    if (onpick && !warning(t.id)) return onpick(t.id);
    chosenId = chosenId === t.id ? null : t.id;
  }
  const ready = $derived(!!chosen && !busy && (!!onpick || !!speaker));
  function go() {
    if (!chosen || !ready) return;
    if (onpick) onpick(chosen.id);
    else onsend?.(chosen.id, comment, showAuthors);
  }
</script>

<Sheet {label} onclose={() => { if (!busy) onclose(); }} footer={onpick && !warn ? undefined : bar}>
  <label class="find"><Icon name="search" size={20} />
    <!-- svelte-ignore a11y_autofocus -->
    <input bind:value={query} {placeholder} aria-label="Search chats" autofocus /></label>
  <div class="grid" role="radiogroup" aria-label="Chats">
    {#each shown as t (t.id)}
      {@const c = color(t.disc.color)}
      <button class="face" role="radio" aria-checked={t.id === chosenId} class:on={t.id === chosenId} disabled={busy} onclick={() => choose(t)}>
        <span class="ring"><span class="disc" style="background: {c.ring}; color: {c.avatar_ink}">{#if t.disc.blob}<AvatarImage hash={t.disc.blob} glyph={t.disc.glyph} name={t.title} />{:else}{t.disc.glyph}{/if}</span>
          {#if t.id === chosenId}<span class="tick"><Icon name="check" size={14} /></span>{/if}</span>
        <span class="name">{t.title}</span>
      </button>
    {/each}
  </div>
  {#if !shown.length}<EmptyState icon="search" label="No chats found" />{/if}
  {#if warn}<FormNote icon="globe" warning title={warn.title} text={warn.text} />{/if}
  {#if !onpick}<Switch label="Show who originally wrote it" bind:checked={showAuthors} />{/if}
</Sheet>

{#snippet bar()}
  {#if onpick}
    <button class="go wide" disabled={!ready} onclick={go}>Continue</button>
  {:else}
    {#if speaker}
      {@const c = color(speaker.color)}
      <span class="me" style="background: {c.ring}; color: {c.avatar_ink}"><AvatarImage hash={speaker.avatar_blob} glyph={speaker.sigils[0] ?? speaker.name[0]} name={speaker.name} /></span>
    {/if}
    <input class="comment" bind:value={comment} maxlength="4000" placeholder="Add a comment…" aria-label="Comment" onkeydown={(e) => { if (e.key === 'Enter') go(); }} />
    <button class="go" disabled={!ready} onclick={go} aria-label="Forward" title="Forward"><Icon name="send" size={20} /></button>
  {/if}
{/snippet}

<style>
  .find { display: flex; align-items: center; gap: 10px; min-height: 44px; color: var(--ink-3); }
  .find input { flex: 1; min-width: 0; border: 0; outline: 0; background: none; color: var(--ink); font: inherit; font-size: var(--fs-md); }
  .grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--s-1); }
  .face { display: grid; justify-items: center; align-content: start; gap: 6px; padding: 6px 2px; border: 0; border-radius: var(--r-row); background: none; color: var(--ink-2); font: inherit; cursor: pointer; }
  .face:hover:not(:disabled) { background: var(--surface-2); }
  .ring { position: relative; width: 60px; height: 60px; display: grid; place-items: center; border-radius: 50%; border: 2px solid transparent; }
  .face.on .ring { border-color: var(--accent); }
  .disc { width: 52px; height: 52px; display: grid; place-items: center; overflow: hidden; border-radius: 50%; font-weight: 700; font-size: 20px; }
  .disc :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .tick { position: absolute; right: -2px; bottom: -2px; width: 22px; height: 22px; display: grid; place-items: center; border: 2px solid var(--surface); border-radius: 50%; background: var(--accent); color: var(--on-accent); }
  .name { max-width: 100%; font-size: var(--fs-xs); line-height: 15px; text-align: center; overflow: hidden; display: -webkit-box; line-clamp: 2; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow-wrap: anywhere; }
  .face.on .name { color: var(--accent); font-weight: 600; }
  .me { width: 36px; height: 36px; flex: none; display: grid; place-items: center; overflow: hidden; border-radius: 50%; font-weight: 700; font-size: 14px; }
  .me :global(img) { width: 100%; height: 100%; object-fit: cover; }
  .comment { flex: 1; min-width: 0; min-height: 44px; padding: 0 var(--s-4); border: 0; border-radius: var(--r-lg); background: var(--surface-2); color: var(--ink); font: inherit; font-size: var(--fs-md); outline: 0; }
  .comment:focus { box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--accent) 60%, transparent); }
  .go { flex: none; width: 44px; height: 44px; display: grid; place-items: center; border: 0; border-radius: 50%; background: var(--accent); color: var(--on-accent); cursor: pointer; }
  .go.wide { width: 100%; border-radius: var(--r-control); font: inherit; font-size: var(--fs-md); font-weight: 600; }
  .go:disabled { background: var(--surface-3, var(--surface-2)); color: var(--ink-3); cursor: default; }
</style>
