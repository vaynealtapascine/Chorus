<script lang="ts">
  import { untrack } from 'svelte';
  import { core } from '../core';
  import { openPostEdit, parsePostEdit, updatePostEdit } from '../post-edit';
  import type { PostRow } from '../posts';
  import { sync } from '../sync/client';
  let { post, onclose }: { post: PostRow; onclose: () => void } = $props();
  const baseline = untrack(() => post);
  const original = core.toMarkup({ text: baseline.text, entities: baseline.entities });
  let editor = openPostEdit({ text: baseline.text, entities: baseline.entities });
  let selection: [number, number] | undefined;
  let body = $state(original);
  let title = $state(baseline.title ?? '');
  let cw = $state(baseline.cw ?? '');
  let error = $state('');
  function save(e: SubmitEvent) {
    e.preventDefault();
    try {
      const rich = parsePostEdit(editor);
      if (!rich.text.trim()) { error = 'Write some text before saving.'; return; }
      sync.create('post.edit', sync.accountScope, post.id, { post_id: post.id, text: rich.text, entities: rich.entities,
        title: post.kind === 'entry' ? title.trim() || null : null, cw: cw.trim() || null });
      onclose();
    } catch (e) { error = e instanceof Error ? e.message : String(e); }
  }
</script>
<form onsubmit={save} aria-label="Edit post">
  {#if post.kind === 'entry'}<label>Title <input bind:value={title} /></label>{/if}
  <label>Post text <textarea value={body} rows="8" onbeforeinput={(e) => { selection = [e.currentTarget.selectionStart, e.currentTarget.selectionEnd]; }} oninput={(e) => {
    const next = e.currentTarget.value;
    editor = updatePostEdit(editor, next, selection?.[0], selection?.[1]); body = next; selection = undefined;
  }}></textarea></label>
  <label>Content warning <input bind:value={cw} /></label>
  {#if error}<p role="alert">{error}</p>{/if}
  <div><button disabled={!body.trim()}>Save changes</button><button type="button" onclick={onclose}>Cancel</button></div>
</form>
<style>
  form, label { display: grid; gap: 8px; } form { padding: 12px 0; } input, textarea { box-sizing: border-box; width: 100%; padding: 10px; font: inherit; color: var(--ink); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-sm); } div { display: flex; gap: 12px; } button { min-height: 44px; cursor: pointer; color: var(--accent); background: none; border: 0; } p { color: var(--danger); }
</style>
