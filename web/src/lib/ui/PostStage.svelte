<script lang="ts">
  import { onDestroy } from 'svelte';
  import { stagePlan } from '../core/pkg/chorus_wasm.js';
  import { members, type MemberRow } from '../data';
  import { posts } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import AvatarImage from './AvatarImage.svelte';
  import RichText from './RichText.svelte';

  let { projection, id }: { projection: Projection; id: string } = $props();
  const post = $derived(posts(projection).find((p) => p.id === id && !p.deleted));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  type Def = { selected: string[]; unselected: 'visible'; only_members: null; redact_names: boolean; fake_names: Record<string, { label: string }>; time: { mode: 'real' | 'hide' }; render: { style: 'card'; theme: 'auto' | 'light' | 'dark'; width: 'phone' | 'square' | 'wide'; blur_avatars: boolean }; post_id: string };
  const fresh = (): Def => ({ selected: [], unselected: 'visible', only_members: null, redact_names: false, fake_names: {}, time: { mode: 'real' }, render: { style: 'card', theme: 'auto', width: 'phone', blur_avatars: false }, post_id: id });
  let def = $state<Def>(fresh());
  let saveName = $state('');
  let capturing = $state(false);
  let shownCw = $state(false);
  const saved = $derived(Object.entries(projection.rows.stage ?? {}).filter(([, r]) => r.exists && r.fields.deleted_at == null && (r.fields.definition as Def | undefined)?.post_id === id)
    .map(([stageId, r]) => ({ id: stageId, name: String(r.fields.name ?? 'Card'), definition: r.fields.definition as Def })));
  const plan = $derived(post ? JSON.parse(stagePlan(JSON.stringify([{ id: post.id, authors: post.authors, at: post.occurred_at, reply_to: post.reply_to ?? null }]), JSON.stringify(def))) as { rows: { kind: string; at: number | null }[]; names: Record<string, { label: string }> } : null);
  const name = (memberId: string) => plan?.names[memberId]?.label ?? people.get(memberId)?.display_name ?? people.get(memberId)?.name ?? 'Someone';
  const lead = $derived(post?.authors[0] ? people.get(post.authors[0]) : undefined);
  const WIDTH = { phone: 390, square: 600, wide: 820 };
  $effect(() => { document.body.classList.toggle('capture', capturing); });
  onDestroy(() => document.body.classList.remove('capture'));
  function save() {
    if (!saveName.trim()) return;
    sync.create('stage.save', sync.accountScope, sync.newId(), { name: saveName.trim(), definition: $state.snapshot(def) });
    saveName = '';
  }
  function load(savedDef: Def) { def = { ...fresh(), ...JSON.parse(JSON.stringify(savedDef)), post_id: id }; }
</script>

<svelte:window onkeydown={(event) => { if (event.key === 'Escape') capturing = false; }} />
<section class="post-stage" class:capturing>
  {#if !capturing}
    <header><a href="#/journal">‹ Journal</a><h1 class="display">Stage post</h1><button onclick={() => (capturing = true)}>Capture</button></header>
    <p class="hint">Card style · take a screenshot when the controls disappear. Nothing here changes the post.</p>
    <div class="controls">
      <label><input type="checkbox" bind:checked={def.redact_names} /> Hide real names</label>
      <label><input type="checkbox" bind:checked={def.render.blur_avatars} /> Blur avatar</label>
      <label><input type="checkbox" checked={def.time.mode === 'hide'} onchange={(e) => (def.time = { mode: e.currentTarget.checked ? 'hide' : 'real' })} /> Hide time</label>
      <select bind:value={def.render.width} aria-label="Card width"><option value="phone">Phone</option><option value="square">Square</option><option value="wide">Wide</option></select>
      <select bind:value={def.render.theme} aria-label="Card theme"><option value="auto">Auto</option><option value="light">Light</option><option value="dark">Dark</option></select>
      <input bind:value={saveName} placeholder="Name this card" aria-label="Stage name" /><button onclick={save} disabled={!saveName.trim()}>Save</button>
      {#each saved as entry (entry.id)}<button onclick={() => load(entry.definition)}>{entry.name}</button>{/each}
    </div>
  {/if}
  {#if post && plan?.rows.length}
    <article class="card" class:blur={def.render.blur_avatars} class:dark={def.render.theme === 'dark'} class:light={def.render.theme === 'light'} style="--w: {WIDTH[def.render.width]}px">
      <div class="author"><span class="avatar"><AvatarImage hash={plan.names[post.authors[0]] ? undefined : lead?.avatar_blob} glyph={lead?.sigils[0] ?? '·'} name={name(post.authors[0] ?? '')} /></span><div><strong>{post.authors.map(name).join(' & ')}</strong>{#if def.time.mode !== 'hide'}<time>{new Date(plan.rows[0].at ?? post.occurred_at).toLocaleString()}</time>{/if}</div></div>
      {#if post.cw}<button class="cw" onclick={() => (shownCw = !shownCw)}>Content warning: {post.cw} · {shownCw ? 'Hide' : 'Show'}</button>{/if}
      {#if !post.cw || shownCw}{#if post.title}<h2 class="display">{post.title}</h2>{/if}<p><RichText text={post.text} entities={post.entities} /></p>{/if}
    </article>
  {:else}<p>Post unavailable.</p>{/if}
  {#if capturing}<button class="done" onclick={() => (capturing = false)}>Done · Esc</button>{/if}
</section>

<style>
  .post-stage { display: grid; gap: var(--s-3); }
  header { display: flex; align-items: center; gap: var(--s-3); } h1 { margin: 0; margin-right: auto; }
  header a { color: var(--accent); text-decoration: none; }
  header button, .controls button { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface); color: var(--ink); padding: var(--s-2); cursor: pointer; }
  .hint { color: var(--ink-3); margin: 0; font-size: var(--fs-sm); }
  .controls { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-3); padding: var(--s-3); background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); }
  .controls label { white-space: nowrap; } .controls select, .controls input:not([type='checkbox']) { background: var(--surface-2); color: var(--ink); border: 1px solid var(--line); padding: var(--s-2); border-radius: var(--r-sm); }
  .card { box-sizing: border-box; width: min(100%, var(--w)); margin: auto; display: grid; gap: var(--s-4); background: var(--surface); color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-lg); padding: var(--s-5); }
  .card.dark { background: #231e1a; color: #f1e9e0; } .card.light { background: #fffaf5; color: #2b211c; }
  .author { display: flex; gap: var(--s-3); align-items: center; } .author div { display: grid; } .avatar { display: inline-grid; width: 44px; height: 44px; border-radius: 50%; overflow: hidden; } .blur .avatar { filter: blur(8px); }
  time { color: var(--ink-3); font-size: var(--fs-xs); } h2, p { margin: 0; } .card p { white-space: pre-wrap; overflow-wrap: anywhere; }
  .cw { background: var(--surface-2); color: var(--ink); border: 1px solid var(--line); border-radius: var(--r-sm); padding: var(--s-2); text-align: left; cursor: pointer; }
  .done { position: fixed; bottom: var(--s-5); right: var(--s-5); border: 0; border-radius: var(--r-full); background: var(--accent); color: var(--bg); padding: var(--s-3); cursor: pointer; }
</style>
