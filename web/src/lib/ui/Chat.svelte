<script lang="ts">
  import { core, type Composed } from '../core';
  import { channels, members, messages, spaces, type MessageRow } from '../data';
  import { router } from '../router.svelte';
  import { sync, type Projection } from '../sync/client';
  import RichText from './RichText.svelte';

  let { projection, dark, channelId }: { projection: Projection; dark: boolean; channelId?: string } = $props();

  const ss = $derived(spaces(projection));
  const allChannels = $derived(channels(projection));
  const current = $derived(allChannels.find((c) => c.id === channelId) ?? allChannels.find((c) => !c.archived));
  const space = $derived(ss.find((s) => s.id === current?.space_id) ?? ss[0]);
  const spaceChannels = $derived(allChannels.filter((c) => c.space_id === space?.id && !c.archived));
  const msgs = $derived(current ? messages(projection, current.id) : []);
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const scope = $derived(space ? `space:${space.id}` : '');

  // who speaks by default: the primary fronter, else the first one fronting (SPEC §5.2)
  const fronting = $derived(projection.fronts[sync.accountId]?.current ?? []);
  const defaultSpeaker = $derived(
    (fronting.find((e) => e.is_primary && e.subject_type === 'member') ??
      fronting.find((e) => e.level === 'front' && e.subject_type === 'member'))?.subject_id,
  );
  let chosen = $state<string | null>(null); // speaker chip override for the next message
  const speaker = $derived(chosen ?? defaultSpeaker ?? null);

  const speakers = $derived(
    [...people.values()]
      .filter((m) => !m.deleted)
      .map((m) => ({ member_id: m.id, sigils: m.sigils, proxy_tags: m.proxy_tags })),
  );
  const names = $derived({
    mentions: Object.fromEntries(
      [...people.values()].filter((m) => !m.deleted).map((m) => [m.name.toLowerCase(), { target_type: 'member', target_id: m.id }]),
    ),
  });

  let draft = $state('');
  const preview: Composed | null = $derived(draft.trim() ? core.compose(draft, speakers, {}, speaker ? [speaker] : [], names) : null);
  const previewNames = $derived(
    preview ? preview.segments.map((s) => s.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')).join(' → ') : '',
  );
  let picking = $state(false);

  function send() {
    if (!current || !preview || !preview.rich.text.trim()) return;
    if (!preview.authors.length) return;
    sync.create('message.send', scope, sync.newId(), {
      channel_id: current.id,
      authors: preview.authors,
      text: preview.rich.text,
      entities: preview.rich.entities,
      ...(preview.segments.length > 1 ? { segments: preview.segments } : {}),
      sent_offline: sync.status !== 'live',
    });
    draft = '';
    chosen = null;
  }

  function onkey(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }

  let newChannel = $state('');
  function addChannel(e: SubmitEvent) {
    e.preventDefault();
    const name = newChannel.trim().toLowerCase().replace(/\s+/g, '-');
    if (!name || !space) return;
    const id = sync.newId();
    sync.create('channel.create', scope, id, { space_id: space.id, kind: 'text', name });
    newChannel = '';
    router.go(`/chat/${id}`);
  }

  // group consecutive messages by the same authors within 5 minutes
  const grouped = $derived(
    msgs.map((m, i) => {
      const prev = msgs[i - 1];
      const cont =
        prev && !prev.deleted && prev.authors.join() === m.authors.join() && m.occurred_at - prev.occurred_at < 300_000 && m.segments.length === 1;
      return { m, cont };
    }),
  );
  const time = (t: number) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const authorNames = (ids: string[]) => ids.map((a) => people.get(a)?.display_name ?? people.get(a)?.name ?? 'Someone');
  const color = (id: string) => core.adaptColor(people.get(id)?.color ?? '#A09184', dark);

  let list: HTMLElement | undefined = $state();
  $effect(() => {
    void msgs.length;
    queueMicrotask(() => list?.scrollTo({ top: list.scrollHeight }));
  });

  function segText(m: MessageRow, s: MessageRow['segments'][number]) {
    const ents = m.entities
      .filter((e) => e.offset >= s.offset && e.offset + e.length <= s.offset + s.length)
      .map((e) => ({ ...e, offset: e.offset - s.offset }));
    return { text: m.text.slice(s.offset, s.offset + s.length), entities: ents };
  }
</script>

<div class="chat">
  <aside class="channels" aria-label="Channels">
    <p class="space">{space?.name ?? 'No spaces yet'}</p>
    {#each spaceChannels as c (c.id)}
      <a href="#/chat/{c.id}" class:on={c.id === current?.id}># {c.name}</a>
    {/each}
    {#if space}
      <form onsubmit={addChannel}>
        <input bind:value={newChannel} placeholder="new channel" aria-label="New channel name" />
      </form>
    {/if}
  </aside>

  <section class="room" aria-label={current ? `#${current.name}` : 'Chat'}>
    <header><h1># {current?.name ?? '…'}</h1>{#if current?.topic}<span class="topic">{current.topic}</span>{/if}</header>
    <div class="list" bind:this={list}>
      {#each grouped as { m, cont } (m.id)}
        {#if m.deleted}
          <div class="msg deleted">Message deleted</div>
        {:else if m.segments.length > 1}
          <div class="msg">
            <div class="meta"><time>{time(m.occurred_at)}</time></div>
            {#each m.segments as s, i (i)}
              {@const seg = segText(m, s)}
              <div class="segment">
                <span class="who" style="color: {color(s.authors[0] ?? '').name}">{authorNames(s.authors).join(' & ')}</span>
                <RichText text={seg.text} entities={seg.entities} />
              </div>
            {/each}
          </div>
        {:else}
          <div class="msg" class:cont>
            {#if !cont}
              <div class="head">
                <span class="avatars">
                  {#each m.authors.slice(0, 3) as a (a)}
                    <span class="avatar" style="--ring: {color(a).ring}">{people.get(a)?.sigils[0] ?? people.get(a)?.name[0] ?? '?'}</span>
                  {/each}
                </span>
                <span class="who">
                  {#each m.authors as a, i (a)}{#if i}<span class="amp">{' & '}</span>{/if}<span style="color: {color(a).name}">{authorNames([a])[0]}</span>{/each}
                </span>
                <time>{time(m.occurred_at)}</time>
                {#if m.sent_offline}<span class="offline" title="Composed offline, synced later">sent offline</span>{/if}
              </div>
            {/if}
            <div class="body"><RichText text={m.text} entities={m.entities} />{#if m.edited}<span class="edited"> (edited)</span>{/if}</div>
          </div>
        {/if}
      {:else}
        <p class="empty">Say hello — messages here are only for your system.</p>
      {/each}
    </div>

    <div class="composer">
      <div class="chip-wrap">
        <button class="chip" onclick={() => (picking = !picking)} title="Speaking as">
          {#if speaker}
            <span class="avatar" style="--ring: {color(speaker).ring}">{people.get(speaker)?.sigils[0] ?? people.get(speaker)?.name[0]}</span>
          {:else}
            <span class="avatar">?</span>
          {/if}
        </button>
        {#if picking}
          <div class="picker" role="listbox">
            {#each [...people.values()].filter((p) => !p.deleted && !p.archived) as p (p.id)}
              <button onclick={() => { chosen = p.id; picking = false; }} style="color: {color(p.id).name}">{p.sigils[0] ?? ''} {p.name}</button>
            {/each}
          </div>
        {/if}
      </div>
      <div class="input">
        {#if preview}<span class="as">as {previewNames || 'no one — pick a speaker'}</span>{/if}
        <textarea
          bind:value={draft}
          onkeydown={onkey}
          rows="1"
          placeholder={current ? `Message #${current.name}` : ''}
          aria-label="Message"
        ></textarea>
      </div>
      <button class="send" onclick={send} disabled={!preview?.authors.length}>Send</button>
    </div>
  </section>
</div>

<style>
  .chat {
    display: grid;
    grid-template-columns: 180px 1fr;
    gap: var(--s-4);
    height: calc(100dvh - 140px);
  }
  @media (max-width: 640px) {
    .chat {
      grid-template-columns: 1fr;
      height: calc(100dvh - 190px);
    }
    .channels {
      display: flex;
      gap: var(--s-2);
      overflow-x: auto;
    }
    .channels form,
    .space {
      display: none;
    }
  }
  .channels {
    display: grid;
    align-content: start;
    gap: 2px;
  }
  .space {
    font-size: var(--fs-xs);
    color: var(--ink-3);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    margin: 0 0 var(--s-2);
  }
  .channels a {
    color: var(--ink-2);
    text-decoration: none;
    padding: var(--s-1) var(--s-3);
    border-radius: var(--r-sm);
    white-space: nowrap;
  }
  .channels a.on {
    background: var(--surface-3);
    color: var(--ink);
  }
  .channels input {
    margin-top: var(--s-2);
    width: 100%;
    font: inherit;
    font-size: var(--fs-sm);
    color: var(--ink);
    background: none;
    border: 1px dashed var(--line);
    border-radius: var(--r-sm);
    padding: var(--s-1) var(--s-3);
  }
  .room {
    display: grid;
    grid-template-rows: auto 1fr auto;
    min-height: 0;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-lg);
  }
  header {
    display: flex;
    gap: var(--s-3);
    align-items: baseline;
    padding: var(--s-3) var(--s-4);
    border-bottom: 1px solid var(--line);
  }
  h1 {
    font-size: var(--fs-lg);
  }
  .topic {
    color: var(--ink-3);
    font-size: var(--fs-sm);
  }
  .list {
    overflow-y: auto;
    padding: var(--s-3) var(--s-4);
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .msg {
    padding-top: var(--s-3);
  }
  .msg.cont {
    padding-top: 0;
  }
  .msg.deleted {
    color: var(--ink-3);
    font-style: italic;
    font-size: var(--fs-sm);
  }
  .head {
    display: flex;
    align-items: center;
    gap: var(--s-2);
  }
  .who {
    font-weight: 600;
  }
  .amp {
    color: var(--ink-3);
    font-weight: 400;
  }
  time,
  .offline,
  .edited {
    font-size: var(--fs-xs);
    color: var(--ink-3);
  }
  .offline {
    border: 1px solid var(--line);
    border-radius: var(--r-full);
    padding: 0 var(--s-2);
  }
  .body {
    padding-left: calc(28px + var(--s-2));
  }
  .segment {
    display: grid;
    gap: 2px;
    padding: var(--s-1) 0 var(--s-1) var(--s-3);
    border-left: 2px solid var(--line);
    margin-top: var(--s-1);
  }
  .avatars {
    display: flex;
  }
  .avatar {
    width: 28px;
    height: 28px;
    flex: none;
    display: grid;
    place-items: center;
    font-size: 14px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring, var(--line)), 0 0 0 3px var(--surface);
  }
  .avatars .avatar + .avatar {
    margin-left: -8px;
  }
  .empty {
    color: var(--ink-3);
    margin: auto;
  }
  .composer {
    display: flex;
    gap: var(--s-2);
    align-items: end;
    padding: var(--s-3);
    border-top: 1px solid var(--line);
  }
  .chip-wrap {
    position: relative;
  }
  .chip {
    background: none;
    border: 0;
    padding: var(--s-1);
    cursor: pointer;
  }
  .picker {
    position: absolute;
    bottom: 110%;
    left: 0;
    z-index: 5;
    display: grid;
    min-width: 160px;
    max-height: 260px;
    overflow: auto;
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    box-shadow: var(--shadow-pop);
    padding: var(--s-1);
  }
  .picker button {
    all: unset;
    cursor: pointer;
    padding: var(--s-2) var(--s-3);
    border-radius: var(--r-sm);
  }
  .picker button:hover {
    background: var(--surface-2);
  }
  .input {
    flex: 1;
    display: grid;
  }
  .as {
    font-size: var(--fs-xs);
    color: var(--ink-3);
    padding: 0 var(--s-2);
  }
  textarea {
    font: inherit;
    color: var(--ink);
    background: var(--surface-2);
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    padding: var(--s-2) var(--s-3);
    resize: none;
    field-sizing: content;
    max-height: 30vh;
  }
  .send {
    background: var(--accent);
    color: var(--surface);
    border: 0;
    border-radius: var(--r-full);
    padding: var(--s-2) var(--s-4);
    font-weight: 600;
    cursor: pointer;
  }
  .send:disabled {
    opacity: 0.5;
  }
</style>
