<script lang="ts">
  // A post's stage (F8.5, D-145): the post as a card with its replies nested under it, the same five
  // tools as a chat's. The post is always shown; picking, "Only replies from" and "Reply levels" act on
  // the replies, and the ones a level cut count as "N deeper replies hidden". Core plans it
  // (`stagePlan` with `post_id`), so a saved stage looks the same on Android. Render-only.
  import { onDestroy } from 'svelte';
  import { stagePlan } from '../core/pkg/chorus_wasm.js';
  import { core, type Entity } from '../core';
  import { members, reactions, type MemberRow } from '../data';
  import { posts } from '../posts';
  import { memberHandles, shownHandle } from '../handles';
  import { apiFetch } from '../http';
  import { apiBase } from '../sync/device';
  import { count, fresh, loadDef, redacted, styleLabel, withBlackout, WIDTH_PX, type Def } from '../stage-def';
  import { paletteStyle } from '../stage-text';
  import { sync, type Projection } from '../sync/client';
  import type { PostDetail, ThreadPost } from '../post-threads';
  import AvatarImage from './AvatarImage.svelte';
  import Check from './stage/Check.svelte';
  import Label from './stage/Label.svelte';
  import MemberChips from './stage/MemberChips.svelte';
  import NamesTab from './stage/NamesTab.svelte';
  import RedactTab from './stage/RedactTab.svelte';
  import Row from './stage/Row.svelte';
  import SaveSheet, { type SavedRow } from './stage/SaveSheet.svelte';
  import StageShell, { type StageTab } from './stage/StageShell.svelte';
  import StageText from './stage/StageText.svelte';
  import Stepper from './stage/Stepper.svelte';
  import StyleTab from './stage/StyleTab.svelte';
  import Chip from './stage/Chip.svelte';
  import Segmented from './kit/Segmented.svelte';
  import FormCard from './kit/FormCard.svelte';
  import Switch from './kit/Switch.svelte';
  import { stageAuthor, type StageAuthor } from './stage/authors';

  let { projection, id, dark }: { projection: Projection; id: string; dark: boolean } = $props();

  /** A reply as the stage draws it: from the server's thread, or from this device's own posts. */
  interface StageReply {
    id: string; parent: string; at: number; authors: string[]; names: string[]; colors: string[]; blobs: (string | undefined)[];
    text: string; cw?: string | null; entities: Entity[];
  }
  type PlanRow =
    | { kind: 'item'; id: string; selected: boolean; at: number | null; reply_shown: boolean; blackout?: [number, number][] }
    | { kind: 'context'; ids: string[]; count: number };

  const HEART = '💜';
  const freshPost = (): Def => ({ ...fresh(), unselected: 'visible', reply_depth: 2, render: { ...fresh().render, style: 'card' }, post_id: id });

  let def = $state<Def>(freshPost());
  let tab = $state<StageTab>('filters');
  let capturing = $state(false);
  let saving = $state(false);
  let shownCw = $state(new Set<string>());
  let loaded = $state<StageReply[] | null>(null);

  const post = $derived(posts(projection).find((p) => p.id === id && !p.deleted));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m] as [string, MemberRow])));
  const handles = $derived(memberHandles(projection, sync.accountId));

  const fromRemote = (r: ThreadPost, parent: string): StageReply => {
    const cards = r.author_cards ?? [];
    const ids = r.authors?.length ? r.authors : cards.map((_, i) => `${r.id}#${i}`);
    return {
      id: r.id, parent, at: r.occurred_at, authors: ids, text: r.text, cw: r.cw, entities: r.entities ?? [],
      names: ids.map((a, i) => cards[i]?.display_name || cards[i]?.name || people.get(a)?.display_name || people.get(a)?.name || 'Someone'),
      colors: ids.map((a, i) => people.get(a)?.color ?? cards[i]?.color ?? '#A09184'),
      blobs: ids.map((a, i) => people.get(a)?.avatar_blob ?? cards[i]?.avatar_blob ?? undefined),
    };
  };
  const flatten = (list: ThreadPost[] | undefined, parent: string): StageReply[] =>
    (list ?? []).flatMap((r) => [fromRemote(r, parent), ...flatten((r as PostDetail).replies, r.id)]);
  // the server nests replies three levels deep; offline, our own direct replies still show
  const ownReplies = $derived(posts(projection).filter((p) => p.reply_to === id && !p.deleted).map((p): StageReply => ({
    id: p.id, parent: id, at: p.occurred_at, authors: p.authors, text: p.text, cw: p.cw ?? null, entities: p.entities,
    names: p.authors.map((a) => people.get(a)?.display_name ?? people.get(a)?.name ?? 'Someone'),
    colors: p.authors.map((a) => people.get(a)?.color ?? '#A09184'), blobs: p.authors.map((a) => people.get(a)?.avatar_blob),
  })));
  let failed = $state(false);
  $effect(() => {
    const at = id;
    loaded = null;
    failed = false;
    apiFetch(`${apiBase()}/posts/${encodeURIComponent(at)}?depth=3`, { headers: { authorization: `Bearer ${sync.device?.session ?? ''}` } })
      .then(async (response) => {
        if (!response.ok) throw new Error(String(response.status));
        const detail = (await response.json()) as PostDetail;
        if (at === id) loaded = flatten(detail.replies, at);
      })
      .catch(() => { if (at === id) failed = true; });
  });
  const replies = $derived(loaded ?? (failed ? ownReplies : []));
  const replyById = $derived(new Map(replies.map((r) => [r.id, r])));
  const depthOf = $derived.by(() => {
    const out = new Map<string, number>();
    for (const r of replies) {
      let depth = 0;
      for (let p = r.parent, guard = 0; p !== id && replyById.has(p) && guard < 30; p = replyById.get(p)!.parent, guard++) depth++;
      out.set(r.id, depth);
    }
    return out;
  });

  const items = $derived(post ? [
    { id: post.id, authors: post.authors, at: post.occurred_at, reply_to: null as string | null },
    ...replies.map((r) => ({ id: r.id, authors: r.authors, at: r.at, reply_to: r.parent })),
  ] : []);
  const picking = $derived(!capturing && tab === 'select');
  const editingText = $derived(!capturing && tab === 'redact');
  const plan = $derived.by(() => {
    const d = picking && def.selected.length > 0 ? { ...def, unselected: 'visible' as const } : def;
    return JSON.parse(stagePlan(JSON.stringify(items), JSON.stringify({ ...d, post_id: id }))) as {
      rows: PlanRow[]; names: Record<string, { label: string; color?: string | null }>; palette?: Record<string, string | boolean>; cropped?: number;
    };
  });
  const stageDark = $derived(def.render.theme === 'custom' ? plan.palette?.dark === true : def.render.theme === 'dark' || (def.render.theme === 'auto' && dark));
  $effect(() => {
    const root = document.documentElement;
    if (def.render.theme === 'auto' || def.render.theme === 'custom') delete root.dataset.theme;
    else root.dataset.theme = def.render.theme;
  });
  onDestroy(() => { delete document.documentElement.dataset.theme; });

  // who appears: the post's authors, then the repliers
  const authors = $derived.by(() => {
    const out = new Map<string, StageAuthor>();
    for (const a of post?.authors ?? []) out.set(a, stageAuthor(a, people.get(a)));
    for (const r of replies) r.authors.forEach((a, i) => {
      if (!out.has(a)) out.set(a, people.get(a) ? stageAuthor(a, people.get(a)) : { id: a, name: r.names[i], glyph: r.names[i]?.[0]?.toUpperCase() ?? '?', color: r.colors[i], blob: r.blobs[i] });
    });
    return [...out.values()];
  });
  const replyAuthors = $derived([...new Set(replies.flatMap((r) => r.authors))].map((a) => authors.find((x) => x.id === a)!).filter(Boolean));

  const nameOf = (a: string, fallback = 'Someone') => plan.names[a]?.label ?? authors.find((x) => x.id === a)?.name ?? fallback;
  const toneOf = (a: string, color: string) => core.adaptColor(plan.names[a] ? (plan.names[a].color ?? '#A09184') : color, stageDark);
  const glyphOf = (a: string, fallback: string) => (plan.names[a] ? plan.names[a].label.replace('Member ', '')[0] : fallback);
  const when = (t: number) => (def.render.hide_dates
    ? new Date(t).toLocaleTimeString([], { timeStyle: 'short' })
    : new Date(t).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }));

  const reacts = $derived(reactions(projection));
  const likes = $derived(reacts.get(id)?.get(HEART)?.length ?? 0);
  const reposts = $derived(posts(projection).filter((p) => p.repost_of === id && !p.deleted).length);

  const kind = $derived(post?.kind === 'entry' ? 'Journal entry' : 'Journal post');
  const levels = $derived(def.reply_depth == null ? 'all replies' : def.reply_depth === 0 ? 'no replies' : count(def.reply_depth, 'reply level'));
  const subtitle = $derived(`${kind} · ${styleLabel(def.render.style)} · ${levels}`);
  const rootRow = $derived(plan.rows.find((r): r is Extract<PlanRow, { kind: 'item' }> => r.kind === 'item' && r.id === id));
  const framed = $derived(def.render.style !== 'minimal' && def.render.style !== 'transcript');

  const saved = $derived(
    Object.entries(projection.rows.stage ?? {})
      .filter(([, r]) => r.exists && r.fields.deleted_at == null && (r.fields.definition as Def | undefined)?.post_id === id)
      .map(([sid, r]) => ({ id: sid, name: String(r.fields.name ?? 'Card'), def: loadDef(r.fields.definition) })),
  );
  const savedRows = $derived<SavedRow[]>(saved.map((s) => ({
    id: s.id, name: s.name, icon: 'journal',
    summary: [kind, styleLabel(s.def.render.style), s.def.reply_depth != null ? 'replies cropped' : '',
      Object.keys(s.def.fake_names).length ? 'names changed' : '', redacted(s.def) ? 'redacted' : ''].filter(Boolean).join(' · '),
  })));
  function save(name: string) {
    const sid = saved.find((s) => s.name.toLowerCase() === name.toLowerCase())?.id ?? sync.newId();
    sync.create('stage.save', sync.accountScope, sid, { name, definition: { ...$state.snapshot(def), post_id: id } });
    saving = false;
  }
  function toggle(rid: string) {
    if (!picking) return;
    def.selected = def.selected.includes(rid) ? def.selected.filter((x) => x !== rid) : [...def.selected, rid];
  }
  const replyDepth = $derived(def.reply_depth);
</script>

<StageShell bind:tab bind:capturing {subtitle} closeHref="#/journal" onsave={() => (saving = true)}>
  {#snippet panel()}
    {#if tab === 'select'}
      {#if !replies.length}
        <p class="quiet">{loaded === null && !failed ? 'Loading…' : 'No replies'}</p>
      {:else}
        <Label>Replies you didn’t pick</Label>
        <Segmented label="Replies you didn’t pick" value={def.unselected} options={[{ id: 'hidden', label: 'Hidden' }, { id: 'context', label: 'Context only' }, { id: 'visible', label: 'Shown' }]}
          onchange={(v) => (def.unselected = v)} />
        <div class="chips">
          <Chip label="Pick all" onclick={() => (def.selected = replies.map((r) => r.id))} />
          <Chip label="Pick none" onclick={() => (def.selected = [])} />
        </div>
      {/if}
    {:else if tab === 'filters'}
      <Label>Replies</Label>
      <Row label="Reply levels">
        <Stepper value={replyDepth == null ? 'All' : String(replyDepth)} label="Reply levels"
          minus={replyDepth === 0 ? null : () => (def.reply_depth = replyDepth == null ? 2 : Math.max(0, replyDepth - 1))}
          plus={replyDepth == null ? null : () => (def.reply_depth = replyDepth >= 2 ? null : replyDepth + 1)} />
      </Row>
      {#if replyAuthors.length > 1}
        <span class="sub">Only replies from</span>
        <MemberChips authors={replyAuthors} only={def.only_members} onchange={(next) => (def.only_members = next)} />
      {/if}
      <FormCard><Switch label="Hide like and repost counts" bind:checked={def.render.hide_counts} /></FormCard>
    {:else if tab === 'style'}
      <StyleTab bind:def {dark} />
    {:else if tab === 'redact'}
      <RedactTab bind:def chatName={false} />
    {:else}
      <NamesTab bind:def {authors} firstAt={post?.occurred_at ?? null} />
    {/if}
  {/snippet}

  {#snippet preview()}
    <div class="canvas style-{def.render.style}" class:capturing class:blur={def.render.blur_avatars} style="--w: {WIDTH_PX[def.render.width]}px; {paletteStyle(plan.palette)}">
      {#if post && rootRow}
        {@const lead = post.authors[0] ?? ''}
        {@const tone = toneOf(lead, people.get(lead)?.color ?? '#A09184')}
        {@const handle = post.authors.length === 1 && !plan.names[lead] ? shownHandle(people.get(lead), handles) : undefined}
        <article class="card" class:framed>
          <div class="main">
          <div class="head">
            <span class="avatar big" style="--ring: {tone.ring}; --name: {tone.name}" aria-hidden="true"><AvatarImage hash={plan.names[lead] ? undefined : people.get(lead)?.avatar_blob} glyph={glyphOf(lead, people.get(lead)?.sigils[0] ?? nameOf(lead)[0])} name={nameOf(lead)} /></span>
            <strong>{post.authors.map((a) => nameOf(a)).join(' & ') || 'Someone'}</strong>
            <span class="meta">{[handle ? `@${handle}` : '', rootRow.at != null ? when(rootRow.at) : ''].filter(Boolean).join(' · ')}</span>
          </div>
          {#if post.cw}
            <button class="cw" aria-expanded={shownCw.has(post.id)} onclick={() => { const next = new Set(shownCw); if (next.has(post.id)) next.delete(post.id); else next.add(post.id); shownCw = next; }}>Content warning: {post.cw} · {shownCw.has(post.id) ? 'Hide' : 'Show'}</button>
          {/if}
          {#if !post.cw || shownCw.has(post.id)}
            {#if post.title}<h2>{post.title}</h2>{/if}
            {#if post.text}
              <div class="post-text"><StageText text={post.text} entities={post.entities} shown={rootRow.blackout} own={def.blackout?.[post.id] ?? []} editing={editingText}
                onchange={(ranges) => (def.blackout = withBlackout(def, post.id, ranges))} /></div>
            {/if}
          {/if}
          {#if !def.render.hide_counts && (likes || reposts)}
            <p class="counts">{[likes ? `♥ ${likes}` : '', reposts ? count(reposts, 'repost') : ''].filter(Boolean).join(' · ')}</p>
          {/if}
          </div>

          {#if plan.rows.length > 1 || (plan.cropped ?? 0) > 0}
            <div class="replies">
              {#each plan.rows as row, i (row.kind === 'item' ? row.id : `c${i}`)}
                {#if row.kind === 'context'}
                  {@const who = [...new Set(row.ids.flatMap((rid) => replyById.get(rid)?.authors ?? []).map((a) => nameOf(a)))]}
                  <div class="context">{[who.length > 2 ? `${who.slice(0, 2).join(', ')}…` : who.join(', '), count(row.count, 'reply', 'replies')].filter(Boolean).join(' · ')}</div>
                {:else if row.id !== id && replyById.has(row.id)}
                  {@const r = replyById.get(row.id)!}
                  {@const rl = r.authors[0] ?? ''}
                  {@const rt = toneOf(rl, r.colors[0] ?? '#A09184')}
                  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
                  <div class="reply" class:dim={!capturing && !row.selected} class:picking style="margin-left: {16 * (depthOf.get(r.id) ?? 0)}px" onclick={() => toggle(r.id)}>
                    {#if picking}<Check on={def.selected.includes(r.id)} />{/if}
                    <span class="avatar" style="--ring: {rt.ring}; --name: {rt.name}" aria-hidden="true"><AvatarImage hash={plan.names[rl] ? undefined : r.blobs[0]} glyph={glyphOf(rl, r.names[0]?.[0]?.toUpperCase() ?? '?')} name={nameOf(rl, r.names[0])} /></span>
                    <div class="body">
                      <div class="who"><strong style="color: {rt.name}">{r.authors.map((a, k) => nameOf(a, r.names[k])).join(' & ')}</strong>{#if row.at != null}<time>{when(row.at)}</time>{/if}</div>
                      {#if r.cw}<span class="cwline">Content warning: {r.cw}</span>
                      {:else}<StageText text={r.text} entities={r.entities} shown={row.blackout} own={def.blackout?.[r.id] ?? []} editing={editingText}
                        onchange={(ranges) => (def.blackout = withBlackout(def, r.id, ranges))} />{/if}
                    </div>
                  </div>
                {/if}
              {/each}
              {#if (plan.cropped ?? 0) > 0}<p class="cropped">{count(plan.cropped ?? 0, 'deeper reply', 'deeper replies')} hidden</p>{/if}
            </div>
          {/if}
        </article>
      {:else}
        <p class="empty">Post unavailable.</p>
      {/if}
    </div>
  {/snippet}
</StageShell>

{#if saving}
  <SaveSheet saved={savedRows} onsave={save} onclose={() => (saving = false)}
    onopen={(sid) => { const s = saved.find((x) => x.id === sid); if (s) { def = { ...loadDef(s.def), post_id: id }; saving = false; } }}
    ondelete={(sid) => sync.create('stage.delete', sync.accountScope, sid, {})} />
{/if}

<style>
  .quiet { margin: 0; color: var(--ink-3); font-size: 14px; }
  .sub { color: var(--ink-3); font-size: var(--fs-sm); }
  .chips { display: flex; gap: var(--s-2); }

  .canvas { width: min(100%, var(--w)); margin: 0 auto; box-sizing: border-box; min-height: 100%; padding: var(--s-6) var(--s-4); background: var(--bg); color: var(--ink); }
  .card { display: grid; gap: var(--s-2); }
  .card.framed { background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; }
  .main { display: grid; gap: var(--s-2); padding: var(--s-4); }
  .card:not(.framed) .main { padding: 0 0 var(--s-2); }
  .head { display: flex; align-items: center; gap: 10px; min-width: 0; }
  .head strong { font-size: var(--fs-base); white-space: nowrap; }
  .meta { flex: 1; min-width: 0; color: var(--ink-3); font-size: var(--fs-sm); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .avatar { flex: none; width: 24px; height: 24px; display: grid; place-items: center; border-radius: 50%; overflow: hidden; background: var(--surface-2); box-shadow: 0 0 0 2px var(--ring); color: var(--name); font-size: var(--fs-xs); }
  .avatar.big { width: 44px; height: 44px; font-size: var(--fs-lg); }
  .blur .avatar { filter: blur(5px); }
  h2 { margin: 0; font-size: var(--fs-lg); line-height: 24px; }
  .post-text { font-size: var(--fs-base); line-height: 23px; }
  .counts { margin: 0; color: var(--ink-2); font-size: var(--fs-sm); }
  .cw, .cwline { color: var(--accent); font: inherit; font-size: 14px; text-align: left; }
  .cw { padding: 0; border: 0; background: none; cursor: pointer; }
  .cwline { color: var(--ink-3); font-size: var(--fs-sm); }
  .replies { display: grid; gap: 10px; padding: 10px var(--s-4); margin-top: var(--s-2); background: var(--surface-2); }
  .card:not(.framed) .replies { background: var(--bg); padding-inline: 0; }
  .reply { display: flex; gap: var(--s-2); align-items: flex-start; }
  .reply.picking { cursor: pointer; }
  .reply.dim { opacity: 0.45; }
  .body { min-width: 0; display: grid; gap: 2px; font-size: 14px; line-height: 20px; }
  .who { display: flex; gap: var(--s-2); align-items: baseline; }
  .who strong { font-size: var(--fs-sm); }
  time { color: var(--ink-3); font-size: 11px; }
  .context { color: var(--ink-3); font-size: var(--fs-xs); font-style: italic; border-left: 3px solid var(--line); padding: 2px var(--s-2); }
  .cropped { margin: 0 0 0 var(--s-4); color: var(--ink-3); font-size: var(--fs-xs); font-style: italic; }
  .empty { color: var(--ink-3); text-align: center; }
</style>
