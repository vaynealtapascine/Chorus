<script lang="ts">
  import { core, type Composed } from '../core';
  import { channels, lastRead, members, messageById, messages, reactions, segmentParsing, spaces, threadSummaries, unread, type MessageRow, type QuoteValue, type SnapshotItem, type TextRange } from '../data';
  import { router } from '../router.svelte';
  import { snapshot } from '../selection';
  import { editedSegments, segmentMarkup } from '../segments';
  import { sync, type Projection } from '../sync/client';
  import { flushUploads, imageThumbnail, stageBlob } from '../sync/uploads';
  import Message from './Message.svelte';

  let { projection, dark, channelId }: { projection: Projection; dark: boolean; channelId?: string } = $props();

  const ss = $derived(spaces(projection));
  const allChannels = $derived(channels(projection));
  const current = $derived(allChannels.find((c) => c.id === channelId) ?? allChannels.find((c) => !c.archived && c.kind !== 'thread'));
  const space = $derived(ss.find((s) => s.id === current?.space_id) ?? ss[0]);
  const spaceChannels = $derived(allChannels.filter((c) => c.space_id === space?.id && !c.archived && c.kind !== 'thread'));
  const msgs = $derived(current ? messages(projection, current.id) : []);
  const threads = $derived(threadSummaries(projection));
  const threadParent = $derived.by(() => {
    if (current?.kind !== 'thread' || !current.parent_message_id) return undefined;
    const parent = messageById(projection, current.parent_message_id);
    return allChannels.some((c) => c.id === parent?.channel_id && c.space_id === current.space_id) ? parent : undefined;
  });
  const parentChannel = $derived(allChannels.find((c) => c.id === threadParent?.channel_id));
  const people = $derived(new Map(members(projection).map((m) => [m.id, m])));
  const scopeOf = (spaceId: string) => `space:${spaceId}`;
  const scope = $derived(space ? scopeOf(space.id) : '');
  const parseSegments = $derived(segmentParsing(projection, sync.accountId));

  // who speaks by default: the primary fronter, else the first one fronting (SPEC §5.2)
  const fronting = $derived(projection.fronts[sync.accountId]?.current ?? []);
  const defaultSpeaker = $derived(
    (fronting.find((e) => e.is_primary && e.subject_type === 'member') ??
      fronting.find((e) => e.level === 'front' && e.subject_type === 'member'))?.subject_id,
  );
  let chosen = $state<string | null>(null);
  const speaker = $derived(chosen ?? defaultSpeaker ?? null);
  const speakers = $derived(
    [...people.values()].filter((m) => !m.deleted).map((m) => ({ member_id: m.id, sigils: m.sigils, proxy_tags: m.proxy_tags })),
  );
  const names = $derived({
    mentions: Object.fromEntries(
      [...people.values()].filter((m) => !m.deleted).map((m) => [m.name.toLowerCase(), { target_type: 'member', target_id: m.id }]),
    ),
  });

  let draft = $state('');
  let pending = $state<{ file: File; alt: string; spoiler: boolean }[]>([]);
  let uploadError = $state('');
  let fileInput: HTMLInputElement | undefined = $state();
  let replyTo = $state<MessageRow | null>(null);
  let quoting = $state<QuoteValue | null>(null);
  let editing = $state<MessageRow | null>(null);
  let editingParts = $state<string[] | null>(null);
  let forwarding = $state<SnapshotItem[] | null>(null);
  let forwardingSensitive = false;
  let quoteSourceSpaceId = '';
  let quoteSensitive = false;
  let keepQuoteOnNavigation = false;
  let selecting = $state(false);
  let selectedIds = $state(new Set<string>());
  let showPins = $state(false);
  let picking = $state(false);
  let box: HTMLTextAreaElement | undefined = $state();
  let activeChannelId: string | undefined;
  $effect(() => {
    if (current?.id === activeChannelId) return;
    activeChannelId = current?.id;
    replyTo = editing = forwarding = null;
    if (!keepQuoteOnNavigation) quoting = null;
    keepQuoteOnNavigation = false;
    editingParts = null;
    selecting = false;
    selectedIds = new Set();
    chosen = null;
  });

  const preview: Composed | null = $derived(draft.trim() && !editing ? core.compose(draft, speakers, { segments: parseSegments }, speaker ? [speaker] : [], names) : null);
  const previewNames = $derived(
    preview ? preview.segments.map((s) => s.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')).join(' → ') : '',
  );

  function addFiles(files: FileList | File[]) {
    pending = [...pending, ...[...files].map((file) => ({ file, alt: '', spoiler: false }))];
  }

  function dropFiles(e: DragEvent) {
    if (!e.dataTransfer?.files.length) return;
    e.preventDefault();
    addFiles(e.dataTransfer.files);
  }

  function pasteFiles(e: ClipboardEvent) {
    const files = [...(e.clipboardData?.files ?? [])];
    if (!files.length) return;
    e.preventDefault();
    addFiles(files);
  }

  async function send() {
    if (!current) return;
    if (editing) {
      const result = editingParts
        ? editedSegments(editingParts, editing.segments, names)
        : { rich: core.parseMarkup(draft, names), segments: [{ offset: 0, length: 0, authors: [...editing.authors] }] };
      if (!result.rich.text.trim()) return;
      if (!editingParts) result.segments[0].length = result.rich.text.length;
      sync.create('message.edit', scope, editing.id, {
        message_id: editing.id, text: result.rich.text, entities: result.rich.entities, segments: result.segments,
      });
      editing = null;
      editingParts = null;
      draft = '';
      return;
    }
    if (!preview && !pending.length) return;
    const authors = preview?.authors ?? (speaker ? [speaker] : []);
    if (!authors.length) return;
    const attachmentIds: string[] = [];
    try {
      for (const item of pending) {
        const original = await stageBlob(item.file, item.file.type, sync.accountId);
        const thumb = await imageThumbnail(item.file);
        const thumbnail = thumb ? await stageBlob(thumb, thumb.type, sync.accountId) : null;
        const id = sync.newId();
        sync.create('attachment.create', scope, id, {
          blob_hash: original.hash, filename: item.file.name, mime: original.mime, size: item.file.size,
          ...(thumbnail ? { thumb_blob_hash: thumbnail.hash } : {}),
          alt_text: item.alt, is_spoiler: item.spoiler,
        });
        attachmentIds.push(id);
      }
      uploadError = '';
    } catch (error) {
      uploadError = `Could not queue the attachment: ${String(error)}`;
      return;
    }
    sync.create('message.send', scope, sync.newId(), {
      channel_id: current.id,
      authors,
      text: preview?.rich.text ?? '',
      entities: preview?.rich.entities ?? [],
      ...(preview && preview.segments.length > 1 ? { segments: preview.segments } : {}),
      ...(attachmentIds.length ? { attachments: attachmentIds } : {}),
      ...(replyTo ? { reply_to: replyTo.id } : {}),
      ...(quoting ? { quote: quoting } : {}),
      sent_offline: sync.status !== 'live',
    });
    draft = '';
    pending = [];
    if (sync.status === 'live') void flushUploads(sync.device);
    chosen = null;
    replyTo = null;
    quoting = null;
    quoteSourceSpaceId = '';
    quoteSensitive = false;
  }

  function onkey(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
    if (e.key === 'Escape') cancel();
  }

  function cancel() {
    if (editing) draft = ''; // leaving an edit drops the edited text
    replyTo = quoting = editing = null;
    editingParts = null;
  }

  function startEdit(m: MessageRow) {
    editing = m;
    editingParts = m.segments.length > 1 ? segmentMarkup(m) : null;
    replyTo = quoting = null;
    // Segment editing keeps each part's authors; a single segment uses the plain composer.
    draft = editingParts ? '' : core.toMarkup({ text: m.text, entities: m.entities });
    if (!editingParts) box?.focus();
  }

  function toggleSelect(m: MessageRow) {
    selecting = true;
    const next = new Set(selectedIds);
    if (next.has(m.id)) next.delete(m.id);
    else next.add(m.id);
    selectedIds = next;
  }

  function selectedMessages(): MessageRow[] {
    return msgs.filter((m) => selectedIds.has(m.id) && !m.deleted);
  }

  function quoteSelected() {
    if (!current) return;
    const picked = selectedMessages();
    if (!picked.length) return;
    quoting = { items: picked.map((m) => snapshot(m, current.name)) };
    forwarding = null;
    quoteSourceSpaceId = current.space_id;
    quoteSensitive = picked.some((m) => !!m.visibility && m.visibility.mode !== 'all');
    replyTo = editing = null;
    editingParts = null;
    selecting = false;
    selectedIds = new Set();
    box?.focus();
  }

  function startForward(items: MessageRow[], range?: TextRange) {
    if (!current || !items.length) return;
    forwarding = items.map((m) => snapshot(m, current.name, m.id === range?.message_id ? range : undefined));
    quoting = null;
    replyTo = editing = null;
    editingParts = null;
    forwardingSensitive = items.some((m) => !!m.visibility && m.visibility.mode !== 'all');
    selecting = false;
    selectedIds = new Set();
  }

  function sharingOutward(targetId: string, targetSpaceId: string, sourceSpaceId: string, sensitive: boolean): boolean {
    if (targetId === current?.id) return false;
    const sourceSpace = ss.find((s) => s.id === sourceSpaceId);
    return (sourceSpace?.kind === 'internal' && targetSpaceId !== sourceSpaceId) || sensitive;
  }

  function moveQuote(targetId: string) {
    const target = allChannels.find((c) => c.id === targetId && !c.archived);
    if (!target || !quoting || target.id === current?.id) return;
    if (sharingOutward(target.id, target.space_id, quoteSourceSpaceId, quoteSensitive) &&
        !confirm('This quote shares a snapshot of the selected content with another channel. Continue?')) return;
    keepQuoteOnNavigation = true;
    router.go(`/chat/${target.id}`);
  }

  function forwardTo(targetId: string) {
    const target = allChannels.find((c) => c.id === targetId);
    const items = forwarding;
    if (!target || target.archived || !items || !speaker || !current) return;
    if (sharingOutward(target.id, target.space_id, current.space_id, forwardingSensitive) &&
        !confirm('This forward shares a snapshot of the selected content with another channel. Continue?')) return;
    const targetScope = scopeOf(target.space_id);
    const attachments = items.flatMap((item) => item.attachments ?? []).map((a) => {
      const id = sync.newId();
      sync.create('attachment.create', targetScope, id, {
        blob_hash: a.blob_hash, thumb_blob_hash: a.thumb_blob_hash, filename: a.filename,
        mime: a.mime, size: a.size, alt_text: a.alt_text, is_spoiler: a.is_spoiler,
      });
      return id;
    });
    sync.create('message.forward', targetScope, sync.newId(), {
      channel_id: target.id,
      authors: [speaker],
      text: '',
      entities: [],
      forward_of_id: items[0].message_id,
      forward_snapshot: items,
      ...(attachments.length ? { attachments } : {}),
    });
    forwarding = null;
    router.go(`/chat/${target.id}`);
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

  function openThread(m: MessageRow) {
    if (!current) return;
    const existing = allChannels.find((c) => c.kind === 'thread' && c.space_id === current.space_id && c.parent_message_id === m.id);
    const id = existing?.id ?? m.id; // one stable channel id per parent, even across devices
    if (existing?.archived) sync.create('channel.unarchive', scope, id, {});
    else if (!existing) sync.create('channel.create', scope, id, {
      space_id: current.space_id,
      kind: 'thread',
      name: 'Thread',
      parent_message_id: m.id,
    });
    router.go(`/chat/${id}`);
  }

  const grouped = $derived(
    msgs.map((m, i) => {
      const prev = msgs[i - 1];
      const cont =
        !!prev && !prev.deleted && prev.authors.join() === m.authors.join() && m.occurred_at - prev.occurred_at < 300_000 && m.segments.length === 1;
      return { m, cont };
    }),
  );
  const pinned = $derived(msgs.filter((m) => m.pinned && !m.deleted));
  const reacts = $derived(reactions(projection));

  function react(m: MessageRow, emoji: string, on: boolean) {
    if (!speaker) return;
    // add and remove must carry the identical element key (LWW element set)
    sync.create(on ? 'reaction.add' : 'reaction.remove', scope, m.id, {
      target_type: 'message',
      target_id: m.id,
      emoji,
      member_id: speaker,
    });
  }

  // reading the channel moves the read mark forward (never backward, SYNC.md §5.5); only while
  // the page is actually visible, and again when it becomes visible
  let visible = $state(!document.hidden);
  $effect(() => {
    const on = () => (visible = !document.hidden);
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  });
  $effect(() => {
    if (!current || !visible) return;
    const latest = msgs.findLast((m) => !m.deleted);
    if (!latest || latest.occurred_at <= lastRead(projection, current.id, sync.accountId)) return;
    sync.create('read.mark', scope, null, {
      channel_id: current.id,
      message_id: latest.id,
      message_at: latest.occurred_at,
      reader_member_id: '',
    });
  });
  const color = (id: string) => core.adaptColor(people.get(id)?.color ?? '#A09184', dark);

  let list: HTMLElement | undefined = $state();
  $effect(() => {
    void msgs.length;
    queueMicrotask(() => list?.scrollTo({ top: list.scrollHeight }));
  });
</script>

<div class="chat">
  <aside class="channels" aria-label="Channels">
    <p class="space">{space?.name ?? 'No spaces yet'}</p>
    {#each spaceChannels as c (c.id)}
      {@const n = c.id === current?.id ? 0 : unread(projection, c.id, sync.accountId)}
      <a href="#/chat/{c.id}" class:on={c.id === current?.id} class:unread={n > 0}
        ># {c.name}{#if n}<span class="badge">{n}</span>{/if}</a
      >
    {/each}
    {#if space}
      <form onsubmit={addChannel}>
        <input bind:value={newChannel} placeholder="new channel" aria-label="New channel name" />
      </form>
    {/if}
  </aside>

  <section class="room" aria-label={current ? `#${current.name}` : 'Chat'} ondragover={(e) => { if (e.dataTransfer?.types.includes('Files')) e.preventDefault(); }} ondrop={dropFiles}>
    <header>
      {#if current?.kind === 'thread'}
        <a class="thread-back" href="#/chat/{parentChannel?.id ?? ''}">‹ #{parentChannel?.name ?? 'channel'}</a>
        <h1>Thread</h1>
      {:else}
        <h1># {current?.name ?? '…'}</h1>
      {/if}
      {#if current?.topic}<span class="topic">{current.topic}</span>{/if}
      <button class="pins" class:on={showPins} onclick={() => (showPins = !showPins)}>📌 {pinned.length}</button>
      {#if current}
        <details class="room-menu">
          <summary aria-label="Channel menu">⋯</summary>
          <div class="menu-items">
            <a href="#/stage/{current.id}">Stage… (screenshot)</a>
            <a href="#/trash/{current.id}">Show deleted</a>
          </div>
        </details>
      {/if}
    </header>

    {#if showPins}
      <div class="pinned">
        {#each pinned as p (p.id)}
          <div class="pin"><strong>{p.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')}</strong> {p.text.slice(0, 140)}</div>
        {:else}
          <p class="muted">Nothing pinned yet.</p>
        {/each}
      </div>
    {/if}

    {#if current?.kind === 'thread'}
      <div class="thread-origin">
        <span>Original message</span>
        {#if threadParent}
          <strong>{threadParent.authors.map((a) => people.get(a)?.name ?? 'Someone').join(' & ')}</strong>
          <p>{threadParent.deleted ? 'Message deleted' : threadParent.text.slice(0, 240)}</p>
        {:else}
          <p>Original message unavailable</p>
        {/if}
      </div>
    {/if}

    <div class="list" bind:this={list}>
      {#each grouped as { m, cont } (m.id)}
        <Message
          {m}
          {cont}
          {people}
          {dark}
          lookup={(id) => messageById(projection, id)}
          mine={m.account_id === sync.accountId}
          onreply={() => { replyTo = m; quoting = null; editing = null; editingParts = null; box?.focus(); }}
          onquote={(q) => { quoting = q; forwarding = null; quoteSourceSpaceId = current?.space_id ?? ''; quoteSensitive = !!m.visibility && m.visibility.mode !== 'all'; replyTo = m; editing = null; editingParts = null; box?.focus(); }}
          onedit={() => startEdit(m)}
          ondelete={() => sync.create('message.delete', scope, m.id, {})}
          onrestore={() => sync.create('message.restore', scope, m.id, {})}
          onpin={() => sync.create(m.pinned ? 'message.unpin' : 'message.pin', scope, m.id, {}, { memberId: speaker ?? undefined })}
          onforward={(range) => startForward([m], range)}
          onselect={() => toggleSelect(m)}
          {selecting}
          selected={selectedIds.has(m.id)}
          thread={threads.get(m.id)}
          onthread={() => openThread(m)}
          reacts={reacts.get(m.id)}
          {speaker}
          onreact={(emoji, on) => react(m, emoji, on)}
        />
      {:else}
        <p class="empty">Say hello — messages here are only for your system.</p>
      {/each}
    </div>

    {#if selecting}
      <div class="bar">
        {selectedIds.size} selected
        <button onclick={quoteSelected} disabled={!selectedIds.size}>Quote bundle</button>
        <button onclick={() => startForward(selectedMessages())} disabled={!selectedIds.size}>Forward bundle</button>
        <button class="x" onclick={() => { selecting = false; selectedIds = new Set(); }} aria-label="Cancel selection">✕</button>
      </div>
    {/if}
    {#if forwarding}
      <div class="bar">
        Forward {forwarding.length} {forwarding.length === 1 ? 'item' : 'items'} to
        <select onchange={(e) => { forwardTo((e.currentTarget as HTMLSelectElement).value); e.currentTarget.value = ''; }} aria-label="Forward to channel">
          <option value="">choose…</option>
          {#each allChannels.filter((c) => !c.archived) as c (c.id)}<option value={c.id}># {c.name}</option>{/each}
        </select>
        {#if !speaker}<span>Pick a speaker first.</span>{/if}
        <button class="x" onclick={() => (forwarding = null)} aria-label="Cancel forward">✕</button>
      </div>
    {/if}
    {#if replyTo || quoting || editing}
      <div class="bar">
        {#if editing}Editing message{:else if quoting}Quoting {#if 'items' in quoting}{quoting.items.length} {quoting.items.length === 1 ? 'message' : 'messages'}{:else}“{quoting.text.slice(0, 60)}”{/if}{:else if replyTo}Replying to {replyTo.authors.map((a) => people.get(a)?.name ?? '?').join(' & ')}{/if}
        {#if quoting}
          <select onchange={(e) => { moveQuote((e.currentTarget as HTMLSelectElement).value); e.currentTarget.value = ''; }} aria-label="Quote in another channel">
            <option value="">Quote in…</option>
            {#each allChannels.filter((c) => !c.archived && c.id !== current?.id) as c (c.id)}<option value={c.id}># {c.name}</option>{/each}
          </select>
        {/if}
        <button class="x" onclick={cancel} aria-label="Cancel">✕</button>
      </div>
    {/if}

    {#if !editing}
      <details class="chat-advanced">
        <summary>Advanced chat settings</summary>
        <label><input type="checkbox" checked={parseSegments} onchange={(e) => sync.create('pref.set', sync.accountScope, null, {
          device: '', key: 'chat.segment_parsing', value: (e.currentTarget as HTMLInputElement).checked,
        })} /> Parse speaker annotations on new lines</label>
      </details>
    {/if}

    {#if pending.length}
      <div class="pending-files" aria-label="Attachments ready to send">
        {#each pending as item, i (item.file.name + i)}
          <div class="pending-file">
            <strong>{item.file.name}</strong>
            <input placeholder="Alt text" aria-label={`Alt text for ${item.file.name}`} bind:value={item.alt} />
            <label><input type="checkbox" bind:checked={item.spoiler} /> Spoiler</label>
            <button onclick={() => (pending = pending.filter((_, n) => n !== i))} aria-label={`Remove ${item.file.name}`}>✕</button>
          </div>
        {/each}
      </div>
    {/if}
    {#if uploadError}<p class="upload-error" role="alert">{uploadError}</p>{/if}
    <div class="composer">
      {#if !editing}
        <input class="file-input" type="file" multiple bind:this={fileInput} onchange={(e) => { addFiles(e.currentTarget.files ?? []); e.currentTarget.value = ''; }} aria-label="Choose attachments" />
        <button class="attach" onclick={() => fileInput?.click()} title="Attach files">＋</button>
      {/if}
      {#if !editing}<div class="chip-wrap">
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
      </div>{/if}
      <div class="input">
        {#if preview && !editing}<span class="as">as {previewNames || 'no one — pick a speaker'}</span>{/if}
        {#if editing && editingParts}
          {#each editingParts as part, i (i)}
            <label class="segment-edit">
              <span>{editing.segments[i].authors.map((a) => people.get(a)?.name ?? 'Someone').join(' & ')}</span>
              <textarea value={part} oninput={(e) => (editingParts![i] = e.currentTarget.value)} onkeydown={onkey} rows="2" aria-label={`Edit segment ${i + 1}`}></textarea>
            </label>
          {/each}
        {:else}
          <textarea
            bind:this={box}
            bind:value={draft}
            onkeydown={onkey}
            onpaste={pasteFiles}
            rows="1"
            placeholder={current ? `Message #${current.name}` : ''}
            aria-label="Message"
          ></textarea>
        {/if}
      </div>
      <button class="send" onclick={() => void send()} disabled={editing ? (editingParts ? !editingParts.some((part) => part.trim()) : !draft.trim()) : (!preview && !pending.length) || (!preview?.authors.length && !speaker)}>{editing ? 'Save' : 'Send'}</button>
    </div>
  </section>
</div>

<style>
  .file-input { display: none; }
  .pending-files { display: grid; gap: var(--s-1); padding: var(--s-2); background: var(--surface-2); border-radius: var(--r-md); }
  .pending-file { display: flex; align-items: center; flex-wrap: wrap; gap: var(--s-2); font-size: var(--fs-sm); }
  .pending-file strong { max-width: 20ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pending-file input:not([type='checkbox']) { min-width: 10ch; flex: 1; }
  .pending-file button, .attach { border: 0; background: none; color: var(--accent); cursor: pointer; }
  .upload-error { color: var(--danger, #b33); font-size: var(--fs-sm); }
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
  .channels a.unread {
    color: var(--ink);
    font-weight: 600;
  }
  .badge {
    margin-left: var(--s-2);
    font-size: var(--fs-xs);
    font-weight: 600;
    color: var(--accent);
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
    display: flex;
    flex-direction: column;
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
  .thread-back { color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .room-menu { position: relative; color: var(--ink-3); }
  .room-menu summary { cursor: pointer; list-style: none; }
  .menu-items { position: absolute; right: 0; top: 100%; z-index: 2; width: max-content; display: grid; background: var(--surface-2); border: 1px solid var(--line); border-radius: var(--r-sm); }
  .room-menu a { padding: var(--s-2) var(--s-3); color: var(--accent); text-decoration: none; font-size: var(--fs-sm); }
  .thread-origin {
    display: grid;
    gap: 2px;
    padding: var(--s-3) var(--s-4);
    border-bottom: 1px solid var(--line);
    color: var(--ink-2);
    font-size: var(--fs-sm);
  }
  .thread-origin span { color: var(--ink-3); font-size: var(--fs-xs); }
  .thread-origin p { margin: 0; white-space: pre-wrap; }
  .pins {
    margin-left: auto;
    background: none;
    border: 1px solid transparent;
    border-radius: var(--r-full);
    cursor: pointer;
    color: var(--ink-3);
  }
  .pins.on {
    border-color: var(--line);
    color: var(--ink);
  }
  .pinned {
    border-bottom: 1px solid var(--line);
    padding: var(--s-2) var(--s-4);
    max-height: 30vh;
    overflow: auto;
    display: grid;
    gap: var(--s-1);
    font-size: var(--fs-sm);
  }
  .muted {
    color: var(--ink-3);
    margin: 0;
  }
  .list {
    flex: 1;
    overflow-y: auto;
    padding: var(--s-2) var(--s-2);
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .empty {
    color: var(--ink-3);
    margin: auto;
  }
  .bar {
    display: flex;
    align-items: center;
    gap: var(--s-2);
    font-size: var(--fs-sm);
    color: var(--ink-2);
    padding: var(--s-2) var(--s-4);
    border-top: 1px solid var(--line);
    background: var(--surface-2);
  }
  .bar select {
    font: inherit;
    color: var(--ink);
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: var(--r-sm);
  }
  .x {
    margin-left: auto;
    background: none;
    border: 0;
    cursor: pointer;
    color: var(--ink-3);
  }
  .composer {
    display: flex;
    gap: var(--s-2);
    align-items: end;
    padding: var(--s-3);
    border-top: 1px solid var(--line);
  }
  .chat-advanced {
    padding: var(--s-1) var(--s-3);
    border-top: 1px solid var(--line);
    color: var(--ink-3);
    font-size: var(--fs-xs);
  }
  .chat-advanced summary { cursor: pointer; width: fit-content; }
  .chat-advanced label { display: flex; align-items: center; gap: var(--s-2); padding: var(--s-2) 0; color: var(--ink-2); }
  .segment-edit { display: grid; gap: 2px; font-size: var(--fs-xs); color: var(--ink-2); }
  .segment-edit + .segment-edit { margin-top: var(--s-2); }
  .chip-wrap {
    position: relative;
  }
  .chip {
    background: none;
    border: 0;
    padding: var(--s-1);
    cursor: pointer;
  }
  .avatar {
    width: 28px;
    height: 28px;
    display: grid;
    place-items: center;
    font-size: 14px;
    border-radius: 50%;
    background: var(--surface-2);
    box-shadow: 0 0 0 2px var(--ring, var(--line));
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
