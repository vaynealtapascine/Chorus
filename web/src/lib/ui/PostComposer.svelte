<script lang="ts">
  import Icon from './Icon.svelte';
  import QuoteEmbed from './QuoteEmbed.svelte';
  import AudienceChip from './AudienceChip.svelte';
  import { validBucketAudience } from '../bucket-controls';
  import { audienceOf, liveAudience, POST_AUDIENCE, type Audience } from '../profile-audience';
  import { core } from '../core';
  import { buckets, members, selfMember } from '../data';
  import { canWriteAs, posts } from '../posts';
  import { sync, type Projection } from '../sync/client';
  import { bodyAuthors } from '../front-view';
  import { pollPayload } from '../polls';
  import { flushUploads, imageThumbnail, stageBlob } from '../sync/uploads';
  import { quoteView } from '../post-threads';
  import { decodeDraft, draftKey, emptyDraft, encodeDraft, occupied, type JournalDraft } from '../journal-draft';
  import { journalCompose } from '../journal-compose.svelte';
  import { handleMentions, memberHandles } from '../handles';
  import { remotePost } from '../remote-posts.svelte';
  import Sheet from './Sheet.svelte';
  import FormattingSheet from './FormattingSheet.svelte';
  import Segmented from './kit/Segmented.svelte';
  import Switch from './kit/Switch.svelte';
  import { unicodeEmoji } from '../chat-composer';
  import { active as isActive, inline, insert, link, linkProblem, lines, lineStyle, style, wordCount, type Sel } from '../format-text';
  import { tick } from 'svelte';

  let { projection, initialAuthors = [], replyTo, defaultVisibility = 'private', onsent }: { projection: Projection; initialAuthors?: string[]; replyTo?: string; defaultVisibility?: 'private' | 'followers' | 'server'; onsent?: () => void } = $props();
  const active = $derived(members(projection).filter((m) => canWriteAs(projection, m.id, sync.accountId)));
  const availableBuckets = $derived(buckets(projection));
  const chipBuckets = $derived(availableBuckets.map((b) => ({ id: b.id, name: b.name, icon: b.icon })));
  // a new post starts with the account's default audience (D-117); a reply with the thread's
  const savedDefault = $derived((projection.rows.pref?.[`||${POST_AUDIENCE}`] ?? projection.rows.pref?.[`${sync.accountId}||${POST_AUDIENCE}`])?.fields.value);
  const accountDefault = $derived(liveAudience(audienceOf(savedDefault), new Set(availableBuckets.map((b) => b.id))));
  const start = $derived<Audience>(replyTo ? { mode: defaultVisibility } : accountDefault);
  const startBuckets = $derived(start.mode === 'buckets' ? start.bucket_ids : []);
  function useByDefault(on: boolean) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: POST_AUDIENCE, value: on ? $state.snapshot(audience) : null });
  }
  const MAX_FILES = 10;

  // one saved draft per composer context, like Android's (the main composer, or a thread's reply box)
  const context = $derived(replyTo ? `reply:${replyTo}` : 'main');
  const key = $derived(draftKey(sync.accountId, context));
  let draft = $state<JournalDraft>(emptyDraft());
  let loadedKey = $state('');
  let restored = $state(false);
  // the same audience chip as profiles and Android's composer: Only us, Friends, buckets, More › everyone here
  const audience = $derived<Audience>(draft.visibility === 'buckets' ? { mode: 'buckets', bucket_ids: draft.bucketIds } : { mode: draft.visibility });
  function setAudience(a: Audience | null) {
    const next = a ?? { mode: 'private' as const };
    draft.visibility = next.mode;
    draft.bucketIds = next.mode === 'buckets' ? next.bucket_ids : [];
  }
  $effect(() => {
    if (loadedKey === key) return;
    let saved: JournalDraft | null = null;
    try { saved = decodeDraft(sessionStorage.getItem(key)); } catch { /* storage unavailable */ }
    draft = saved ?? emptyDraft(start.mode, startBuckets);
    // only words, files or a quote count as something to restore; a remembered author or audience is quiet
    restored = !!saved && !!(saved.body.trim() || saved.title.trim() || saved.cw.trim() || saved.quote || saved.pollOn || saved.lostFiles);
    pending = [];
    loadedKey = key;
  });
  $effect(() => {
    if (loadedKey !== key) return;
    const value = { ...draft, lostFiles: draft.lostFiles + pending.length };
    try {
      if (occupied(value, start.mode, startBuckets)) sessionStorage.setItem(key, encodeDraft(value));
      else sessionStorage.removeItem(key);
    } catch { /* a full browser store must not interrupt writing */ }
  });
  // a card's Quote hands its post here once (only the main composer quotes)
  $effect(() => {
    if (replyTo || !journalCompose.quote || loadedKey !== key) return;
    draft.quote = journalCompose.quote;
    journalCompose.quote = null;
    textarea?.focus();
  });

  // null = follow the current front (or the context's suggestion) until someone picks (Android's default intent)
  // a singlet always writes as themself (D-107)
  const self = $derived(selfMember(projection));
  const defaultAuthors = $derived(initialAuthors.length ? initialAuthors : self ? [self.id] : bodyAuthors(projection, sync.accountId));
  const authors = $derived(draft.authors ?? defaultAuthors.filter((id) => active.some((m) => m.id === id)));
  const authorLabel = $derived(active.filter((m) => authors.includes(m.id)).map((m) => m.display_name ?? m.name).join(' & ') || 'Choose author');
  const postById = $derived(new Map(posts(projection).map((p) => [p.id, p])));
  const memberName = (id: string) => active.find((m) => m.id === id)?.display_name ?? members(projection).find((m) => m.id === id)?.name ?? 'Someone';
  // another account's post is read from the server; quoting it borrows its audience (D-127)
  const quote = $derived(draft.quote ? quoteView(draft.quote, postById, memberName, postById.has(draft.quote) ? null : remotePost(draft.quote)) : null);
  const quotingOther = $derived(!!draft.quote && !postById.has(draft.quote));

  let pending = $state<{ file: File; alt: string; spoiler: boolean; url: string }[]>([]);
  let error = $state('');
  let busy = $state(false);
  let formatOpen = $state(false);
  let linkOpen = $state(false);
  let linkUrl = $state('');
  let linkError = $state('');
  let emojiOpen = $state(false);
  let alertsOpen = $state(false);
  let cwOpen = $state(false);
  let tagsOpen = $state(false);
  let camera = $state<HTMLInputElement | undefined>();
  const coarse = typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
  // the textarea's selection, mirrored so the buttons can show what is on and the pill when text is picked
  let caret = $state({ start: 0, end: 0 });
  let focused = $state(false);
  const marks: [string, string, string][] = [['format-bold', 'Bold', '**'], ['format-italic', 'Italic', '_'], ['format-strike', 'Strikethrough', '~~'], ['format-code', 'Code', '`']];
  const words = $derived(wordCount(draft.body));
  const picked = $derived(focused && caret.start !== caret.end);
  function track() {
    if (!textarea) return;
    caret = { start: textarea.selectionStart, end: textarea.selectionEnd };
    focused = document.activeElement === textarea;
  }
  const current = (): Sel => ({ text: draft.body, ...caret });
  const headingOn = $derived(lineStyle(current()).startsWith('heading'));
  const activeMark = (marker: string) => isActive(current(), marker);
  // a toolbar press must not take the selection away from the text
  const keepFocus = (e: Event) => e.preventDefault();
  async function apply(next: Sel) {
    draft.body = next.text;
    caret = { start: next.start, end: next.end };
    await tick();
    textarea?.focus();
    textarea?.setSelectionRange(next.start, next.end);
    track();
  }
  const act = (fn: (v: Sel) => Sel) => { track(); void apply(fn(current())); };
  function openLink() { track(); linkUrl = ''; linkError = ''; linkOpen = true; }
  function addLink() {
    const url = linkUrl.trim();
    const problem = linkProblem(url);
    if (problem) { linkError = problem; return; }
    linkOpen = false;
    void apply(link(current(), url));
  }
  function toggleCw() {
    if (cwOpen || draft.cw) { draft.cw = ''; cwOpen = false; } else cwOpen = true;
  }
  let textarea = $state<HTMLTextAreaElement | undefined>();
  let picker = $state<HTMLInputElement | undefined>();

  function toggle(id: string) {
    draft.authors = authors.includes(id) ? authors.filter((x) => x !== id) : [...authors, id];
  }
  function addFiles(files: FileList | File[]) {
    const room = MAX_FILES - pending.length;
    const list = [...files].slice(0, Math.max(0, room));
    if (files.length > list.length) error = `A post holds up to ${MAX_FILES} files.`;
    draft.lostFiles = 0; // attaching again replaces the restored draft's missing files
    pending = [...pending, ...list.map((file) => ({ file, alt: '', spoiler: false, url: file.type.startsWith('image/') ? URL.createObjectURL(file) : '' }))];
  }
  function removeFile(i: number) {
    if (pending[i]?.url) URL.revokeObjectURL(pending[i].url);
    pending = pending.filter((_, n) => n !== i);
  }
  function pasteFiles(e: ClipboardEvent) {
    const files = [...(e.clipboardData?.files ?? [])];
    if (!files.length) return;
    e.preventDefault();
    addFiles(files);
  }
  function dropFiles(e: DragEvent) {
    if (!e.dataTransfer?.files.length) return;
    e.preventDefault();
    addFiles(e.dataTransfer.files);
  }
  function discard() {
    for (const item of pending) if (item.url) URL.revokeObjectURL(item.url);
    pending = [];
    draft = emptyDraft(start.mode, startBuckets);
    restored = false;
    error = '';
  }

  const canPost = $derived(!busy && authors.length > 0 && (!!draft.body.trim() || pending.length > 0) &&
    validBucketAudience(audience, availableBuckets));

  async function send(e: SubmitEvent) {
    e.preventDefault();
    if (!canPost || authors.some((id) => !canWriteAs(projection, id, sync.accountId))) return;
    const closes = draft.pollCloses ? new Date(draft.pollCloses).getTime() : null;
    const pollDraft = draft.pollOn ? pollPayload(draft.pollOptions, draft.pollMultiple, Number.isFinite(closes) ? closes : null) : {};
    if (pollDraft.error) { error = pollDraft.error; return; }
    busy = true;
    error = '';
    const sending = draft;
    const files = pending;
    try {
      // files first: a post never names an attachment that isn't queued on this device
      const attachmentIds: string[] = [];
      for (const item of files) {
        const original = await stageBlob(item.file, item.file.type, sync.accountId);
        const thumb = await imageThumbnail(item.file);
        const thumbnail = thumb ? await stageBlob(thumb, thumb.type, sync.accountId) : null;
        const id = sync.newId();
        // a post's files are account-scoped rows (`post.attachment`); chat uses the space's `attachment.create`
        sync.create('post.attachment', sync.accountScope, id, {
          blob_hash: original.hash, filename: item.file.name, mime: original.mime, size: item.file.size,
          ...(thumbnail ? { thumb_blob_hash: thumbnail.hash } : {}),
          alt_text: item.alt.trim(), is_spoiler: item.spoiler,
        });
        attachmentIds.push(id);
      }
      // our members' @handles become mentions (D-126)
      const mentions = Object.fromEntries([...handleMentions(memberHandles(projection, sync.accountId), members(projection))]
        .map(([handle, member]) => [handle, { target_type: 'member', target_id: member }]));
      const rich = core.parseMarkup(sending.body, { mentions, headings: true });
      sync.create('post.create', sync.accountScope, sync.newId(), {
        kind: sending.kind, authors, title: sending.kind === 'entry' ? sending.title.trim() || null : null,
        text: rich.text, entities: rich.entities, mood: sending.mood.trim() || null,
        tags: sending.tags.split(',').map((t) => t.trim().replace(/^#/, '')).filter(Boolean),
        cw: sending.cw.trim() || null,
        visibility: sending.visibility === 'buckets' ? { mode: 'buckets', bucket_ids: sending.bucketIds } : { mode: sending.visibility },
        ...(replyTo ? { reply_to: replyTo } : {}),
        ...(sending.quote ? { quote: { post_id: sending.quote } } : {}),
        ...(attachmentIds.length ? { attachments: attachmentIds } : {}),
        ...(pollDraft.poll ? { poll: pollDraft.poll } : {}),
        ...(sending.alerts ? { alerts: sending.alerts } : {}),
      });
      if (sync.status === 'live') void flushUploads(sync.device);
      // keep who is writing and where it goes, unless new posts have a default; clear what was said
      draft = { ...(savedDefault && !replyTo ? emptyDraft(start.mode, startBuckets) : emptyDraft(sending.visibility, sending.bucketIds)), authors: sending.authors };
      for (const item of files) if (item.url) URL.revokeObjectURL(item.url);
      pending = [];
      restored = false;
      cwOpen = tagsOpen = false;
      onsent?.();
    } catch (cause) {
      error = `Could not post. Your draft is kept. ${cause instanceof Error ? cause.message : String(cause)}`;
    } finally { busy = false; }
  }
</script>

<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<form class="composer" onsubmit={send} ondragover={(e) => e.preventDefault()} ondrop={dropFiles}>
  <div class="head">
    <details class="author-picker"><summary>{authorLabel}</summary><fieldset><legend>Writing as</legend><div class="authors">{#each active as member (member.id)}<label class:on={authors.includes(member.id)}><input type="checkbox" checked={authors.includes(member.id)} onchange={() => toggle(member.id)} />{member.display_name ?? member.name}</label>{/each}</div></fieldset></details>
    <div class="seg"><button type="button" class:on={draft.kind === 'note'} onclick={() => (draft.kind = 'note')}>Note</button><button type="button" class:on={draft.kind === 'entry'} onclick={() => (draft.kind = 'entry')}>Entry</button></div>
  </div>
  {#if restored}<p class="restored">Draft restored{draft.lostFiles ? ` · ${draft.lostFiles} ${draft.lostFiles === 1 ? 'file needs' : 'files need'} attaching again` : ''}. <button type="button" onclick={discard}>Discard</button></p>{/if}
  {#if draft.kind === 'entry'}<input bind:value={draft.title} placeholder="Title (optional)" aria-label="Entry title" />{/if}
  <div class="body-wrap">
    {#if picked}<div class="pill" role="toolbar" aria-label="Format the selection">
      {#each marks as [icon, label, marker] (marker)}<button type="button" class:on={activeMark(marker)} aria-label={label} onmousedown={keepFocus} onclick={() => act((v) => inline(v, marker))}><Icon name={icon} size={18} /></button>{/each}
      <button type="button" aria-label="Link" onmousedown={keepFocus} onclick={openLink}><Icon name="link" size={18} /></button>
      <button type="button" class:on={activeMark('||')} aria-label="Spoiler" onmousedown={keepFocus} onclick={() => act((v) => inline(v, '||'))}><Icon name="eye-off" size={18} /></button>
      <button type="button" aria-label="Formatting" onmousedown={keepFocus} onclick={() => (formatOpen = true)}><Icon name="format-text" size={18} /></button>
    </div>{/if}
    <textarea bind:this={textarea} bind:value={draft.body} onselect={track} onkeyup={track} onclick={track} oninput={track} onfocus={track} onblur={track} onpaste={pasteFiles} rows={draft.kind === 'entry' ? 9 : 2} placeholder={replyTo ? 'Reply…' : draft.quote ? 'Add a comment…' : draft.kind === 'entry' ? 'Write your entry…' : "What's on your mind?"} aria-label="Post text"></textarea>
  </div>
  {#if quote}<div class="quoting"><QuoteEmbed {quote} gone={quote.post ? undefined : 'Loading the quoted post…'} /><button type="button" class="unquote" aria-label="Remove quote" onclick={() => (draft.quote = null)}><Icon name="close" size={16} /></button></div>
    {#if quotingOther}<p class="borrowed">Visible to readers of the quoted post.</p>{/if}{/if}
  {#if pending.length}
    <div class="tray" aria-label="Attachments ready to post">
      {#each pending as item, i (item.file.name + i)}
        <div class="tray-item">
          {#if item.url}<img src={item.url} alt="" class:spoiler={item.spoiler} />{:else}<span class="file-chip"><Icon name="doc" size={18} /></span>{/if}
          <div class="tray-fields">
            <strong>{item.file.name}</strong>
            <input placeholder="Describe it (alt text)" aria-label={`Alt text for ${item.file.name}`} bind:value={item.alt} maxlength="1500" />
            <label><input type="checkbox" bind:checked={item.spoiler} /> Spoiler</label>
          </div>
          <button type="button" class="remove" onclick={() => removeFile(i)} aria-label={`Remove ${item.file.name}`}><Icon name="close" size={16} /></button>
        </div>
      {/each}
    </div>
  {/if}
  {#if draft.pollOn}
    <div class="fields poll">
      <div class="field-head"><strong>Poll</strong><button type="button" class="remove" aria-label="Remove poll" onclick={() => (draft.pollOn = false)}><Icon name="close" size={16} /></button></div>
      {#each draft.pollOptions as _, i (i)}<input bind:value={draft.pollOptions[i]} placeholder="Option {i + 1}" aria-label="Poll option {i + 1}" maxlength="200" />{/each}
      {#if draft.pollOptions.length < 20}<button type="button" class="text-btn" onclick={() => (draft.pollOptions = [...draft.pollOptions, ''])}>Add option</button>{/if}
      <Switch label="Allow more than one choice" bind:checked={draft.pollMultiple} />
      <label class="closes">Closes <input type="datetime-local" bind:value={draft.pollCloses} aria-label="Poll closes" /></label>
    </div>
  {/if}
  {#if cwOpen || draft.cw}<div class="fields"><input bind:value={draft.cw} placeholder="Content warning" aria-label="Content warning" /></div>{/if}
  {#if tagsOpen || draft.tags || draft.mood}<div class="fields"><input bind:value={draft.tags} placeholder="Tags, separated by commas" aria-label="Tags" /><input bind:value={draft.mood} placeholder="Mood" aria-label="Mood" /></div>{/if}
  <div class="bar">
    <div class="row chips">
      <span class="audience"><AudienceChip label="This post" value={audience} own buckets={chipBuckets} onchange={setAudience}
        defaultAudience={replyTo ? undefined : accountDefault} onusedefault={useByDefault} /></span>
      <button type="button" class="chip" class:on={!!draft.alerts} aria-label="Notifications" onclick={() => (alertsOpen = true)}><Icon name={draft.alerts === 'none' ? 'bell-off' : 'bell'} size={16} />{draft.alerts === 'none' ? 'Silent' : draft.alerts === 'delayed' ? 'Delayed' : ''}</button>
      <span class="saved">{busy || error || !occupied(draft, start.mode, startBuckets) ? '' : 'Saved'}</span>
    </div>
    <div class="row icons" role="toolbar" aria-label="Formatting">
      <button type="button" aria-label="Formatting" title="Formatting" onmousedown={keepFocus} onclick={() => (formatOpen = true)}><Icon name="format-text" size={20} /></button>
      {#each marks as [icon, label, marker] (marker)}<button type="button" class:on={activeMark(marker)} aria-pressed={activeMark(marker)} aria-label={label} title={label} onmousedown={keepFocus} onclick={() => act((v) => inline(v, marker))}><Icon name={icon} size={20} /></button>{/each}
      <button type="button" class:on={headingOn} aria-pressed={headingOn} aria-label="Heading" title="Heading" onmousedown={keepFocus} onclick={() => act((v) => style(v, lineStyle(v) === 'heading1' ? '' : 'heading1'))}><Icon name="format-heading" size={20} /></button>
      <button type="button" aria-label="Bulleted list" title="Bulleted list" onmousedown={keepFocus} onclick={() => act((v) => lines(v, '• '))}><Icon name="format-list-ul" size={20} /></button>
      <button type="button" aria-label="Numbered list" title="Numbered list" onmousedown={keepFocus} onclick={() => act((v) => lines(v, '', true))}><Icon name="format-list-ol" size={20} /></button>
      <button type="button" aria-label="Quote" title="Quote" onmousedown={keepFocus} onclick={() => act((v) => style(v, lineStyle(v) === 'quote' ? '' : 'quote'))}><Icon name="format-quote" size={20} /></button>
      <button type="button" aria-label="Link" title="Link" onmousedown={keepFocus} onclick={openLink}><Icon name="link" size={20} /></button>
      <button type="button" class:on={activeMark('||')} aria-pressed={activeMark('||')} aria-label="Spoiler" title="Spoiler" onmousedown={keepFocus} onclick={() => act((v) => inline(v, '||'))}><Icon name="eye-off" size={20} /></button>
    </div>
    <div class="row icons">
      <button type="button" aria-label="Attach photos or files" title="Attach photos or files" disabled={pending.length >= MAX_FILES} onclick={() => picker?.click()}><Icon name="image" size={20} /></button>
      {#if coarse}<button type="button" aria-label="Take a photo" title="Take a photo" disabled={pending.length >= MAX_FILES} onclick={() => camera?.click()}><Icon name="camera" size={20} /></button>{/if}
      <button type="button" class:on={draft.pollOn} aria-pressed={draft.pollOn} aria-label="Poll" title="Poll" onclick={() => (draft.pollOn = !draft.pollOn)}><Icon name="poll" size={20} /></button>
      <button type="button" aria-label="Emoji" title="Emoji" onclick={() => (emojiOpen = true)}><Icon name="smile" size={20} /></button>
      <button type="button" class:on={cwOpen || !!draft.cw} aria-pressed={cwOpen || !!draft.cw} aria-label="Content warning" title="Content warning" onclick={toggleCw}><Icon name="alert" size={20} /></button>
      <button type="button" class:on={tagsOpen || !!draft.tags || !!draft.mood} aria-pressed={tagsOpen || !!draft.tags || !!draft.mood} aria-label="Tags and mood" title="Tags and mood" onclick={() => (tagsOpen = !tagsOpen)}><Icon name="hash" size={20} /></button>
      <span class="words">{words} {words === 1 ? 'word' : 'words'}</span>
      <button class="primary" disabled={!canPost}>{busy ? 'Posting…' : replyTo ? 'Reply' : 'Post'}</button>
    </div>
    <input bind:this={picker} class="sr-label" type="file" multiple tabindex="-1" aria-hidden="true" onchange={(e) => { if (e.currentTarget.files) addFiles(e.currentTarget.files); e.currentTarget.value = ''; }} />
    <input bind:this={camera} class="sr-label" type="file" accept="image/*" capture="environment" tabindex="-1" aria-hidden="true" onchange={(e) => { if (e.currentTarget.files) addFiles(e.currentTarget.files); e.currentTarget.value = ''; }} />
  </div>
  {#if error}<p class="error" role="alert">{error}</p>{/if}
</form>
{#if formatOpen}<FormattingSheet value={current()} onchange={apply} onlink={() => { formatOpen = false; openLink(); }} onclose={() => (formatOpen = false)} />{/if}
{#if linkOpen}
  <Sheet title="Link" onclose={() => (linkOpen = false)}>
    <input class="link-field" bind:value={linkUrl} placeholder="https://" aria-label="Link address" inputmode="url" onkeydown={(e) => e.key === 'Enter' && (e.preventDefault(), addLink())} />
    {#if linkError}<p class="error" role="alert">{linkError}</p>{/if}
    {#snippet footer()}<button type="button" class="primary" onclick={addLink}>Add link</button>{/snippet}
  </Sheet>
{/if}
{#if emojiOpen}
  <Sheet title="Emoji" onclose={() => (emojiOpen = false)}>
    <div class="emoji-grid">{#each unicodeEmoji as emoji (emoji)}<button type="button" title={emoji} aria-label={`Insert ${emoji}`} onclick={() => { act((v) => insert(v, emoji)); emojiOpen = false; }}>{emoji}</button>{/each}</div>
  </Sheet>
{/if}
{#if alertsOpen}
  <Sheet title="Notify" onclose={() => (alertsOpen = false)}>
    <Segmented label="Notifications" value={draft.alerts} options={[{ id: '', label: 'As usual' }, { id: 'delayed', label: 'Random delay' }, { id: 'none', label: 'Don’t notify' }]} onchange={(v) => { draft.alerts = v as typeof draft.alerts; alertsOpen = false; }} />
  </Sheet>
{/if}

<style>
  .composer { display: grid; gap: var(--s-3); background: var(--bg); border: 0; border-bottom: 1px solid var(--line); border-radius: 0; padding: var(--s-4); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-3); }
  .seg { display: flex; background: var(--surface-2); border-radius: var(--r-full); padding: 2px; }
  .seg button { border: 0; background: none; color: var(--ink-2); padding: var(--s-1) var(--s-3); border-radius: var(--r-full); cursor: pointer; }
  .seg button.on { background: var(--surface); color: var(--ink); }
  fieldset { border: 0; padding: 0; margin: 0; } legend { color: var(--ink-3); font-size: var(--fs-sm); }
  .authors { display: flex; flex-wrap: wrap; max-height: 6rem; overflow: auto; gap: var(--s-1); }
  .authors label { display: flex; align-items: center; gap: 4px; font-size: var(--fs-sm); padding: 2px var(--s-2); border: 1px solid var(--line); border-radius: var(--r-full); cursor: pointer; }
  .authors label.on { background: var(--accent-soft); }
  input:not([type='checkbox']), textarea { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); padding: var(--s-2); font: inherit; }
  textarea { width: 100%; resize: vertical; box-sizing: border-box; }
  .primary { justify-self: end; min-height: 40px; border: 0; border-radius: var(--r-sm); background: var(--accent); color: var(--on-accent); padding: var(--s-2) var(--s-4); cursor: pointer; }
  .primary:disabled { opacity: .5; cursor: default; }
  .error { color: var(--danger); margin: 0; }
  .restored { margin: 0; color: var(--ink-3); font-size: var(--fs-xs); }
  .borrowed { margin: -4px 0 0; color: var(--ink-3); font-size: var(--fs-xs); }
  .restored button { border: 0; background: none; padding: 0; color: var(--accent); font: inherit; cursor: pointer; }
  .author-picker { min-width: 0; position: relative; flex: 1; }
  .author-picker summary { cursor: pointer; color: var(--ink); font-size: var(--fs-sm); min-height: 40px; display: flex; align-items: center; font-weight: 600; }
  .author-picker fieldset { position: absolute; z-index: 3; left: 0; top: 100%; width: min(420px, 75vw); background: var(--surface); padding: 16px; border: 1px solid var(--line); border-radius: 12px; }
  .author-picker legend { background: var(--surface); }
  .authors label { min-height: 40px; }
  textarea { border: 0; padding: 8px 0; background: transparent; min-height: 56px; }
  .quoting { position: relative; }
  .unquote { position: absolute; top: 6px; right: 6px; display: grid; place-items: center; width: 28px; height: 28px; border: 0; border-radius: 50%; background: var(--surface-2); color: var(--ink-2); cursor: pointer; }
  .tray { display: grid; gap: 8px; }
  .tray-item { display: grid; grid-template-columns: 56px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 8px; border-radius: 12px; background: var(--surface); }
  .tray-item img, .file-chip { width: 56px; height: 56px; border-radius: 8px; object-fit: cover; }
  .tray-item img.spoiler { filter: blur(8px); }
  .file-chip { display: grid; place-items: center; background: var(--surface-2); color: var(--ink-2); }
  .tray-fields { display: grid; gap: 4px; min-width: 0; font-size: var(--fs-sm); }
  .tray-fields strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
  .tray-fields input:not([type='checkbox']) { padding: 6px 8px; }
  .tray-fields label { display: flex; align-items: center; gap: 6px; color: var(--ink-2); font-size: var(--fs-xs); }
  .remove { display: grid; place-items: center; width: 32px; height: 32px; border: 0; border-radius: 50%; background: none; color: var(--ink-3); cursor: pointer; }
  .body-wrap { position: relative; }
  .pill { position: absolute; z-index: 2; top: 0; left: 0; transform: translateY(-100%); display: flex; gap: 2px; padding: 4px; border-radius: 14px; background: var(--surface-2); box-shadow: var(--shadow-pop); }
  .pill button { display: grid; place-items: center; width: 36px; height: 36px; border: 0; border-radius: 10px; background: none; color: var(--ink-2); cursor: pointer; }
  .pill button.on, .icons button.on { background: var(--accent-soft); color: var(--accent); }
  .fields { display: grid; gap: 8px; }
  .fields.poll { padding: 12px; border-radius: 12px; background: var(--surface); }
  .field-head { display: flex; align-items: center; justify-content: space-between; }
  .text-btn { justify-self: start; border: 0; background: none; color: var(--accent); font: inherit; padding: 4px 0; cursor: pointer; }
  .closes { display: flex; align-items: center; gap: 8px; color: var(--ink-2); font-size: var(--fs-sm); }
  .bar { display: grid; gap: 4px; margin: 0 calc(var(--s-4) * -1) calc(var(--s-4) * -1); padding: 8px var(--s-3); background: var(--surface); border-top: 1px solid var(--line); }
  .row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
  .chips { min-height: 40px; }
  .chip { display: inline-flex; align-items: center; gap: 4px; min-height: 32px; padding: 0 10px; border: 0; border-radius: var(--r-full); background: var(--surface-2); color: var(--ink-3); font: inherit; font-size: var(--fs-sm); cursor: pointer; }
  .chip.on { background: var(--accent-soft); color: var(--accent); }
  .saved { margin-left: auto; color: var(--ink-3); font-size: var(--fs-sm); }
  .icons { gap: 0; justify-content: space-between; flex-wrap: nowrap; }
  .icons button:not(.primary) { display: grid; place-items: center; width: 40px; height: 40px; border: 0; border-radius: 10px; background: none; color: var(--accent); cursor: pointer; flex: none; }
  .icons button:disabled { opacity: .4; cursor: default; }
  .icons[role='toolbar'] button:not(.on) { color: var(--ink-2); }
  .words { margin-left: auto; margin-right: 8px; color: var(--ink-3); font-size: var(--fs-sm); white-space: nowrap; }
  .link-field { border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--surface-2); color: var(--ink); padding: var(--s-2); font: inherit; }
  .emoji-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(44px, 1fr)); gap: 4px; }
  .emoji-grid button { height: 44px; border: 0; border-radius: 10px; background: var(--surface-2); font-size: 24px; cursor: pointer; }
  .audience { display: flex; align-items: center; gap: 4px; color: var(--ink-3); min-width: 0; }
  .sr-label { position: absolute; width: 1px; height: 1px; clip-path: inset(50%); overflow: hidden; white-space: nowrap; }
</style>
