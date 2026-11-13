// The Journal composer's model (UI24): one draft per context (`main`, `reply:<post>`), shared by every
// surface that shows it (the quick box, the full composer, a thread's reply box) so expanding keeps
// everything. No markup: the surfaces in ui/journal/ draw it.
import { tick } from 'svelte';
import { validBucketAudience } from './bucket-controls';
import { audienceOf, liveAudience, POST_AUDIENCE, type Audience } from './profile-audience';
import { core } from './core';
import { buckets, members, selfMember, type MemberRow } from './data';
import { canWriteAs } from './posts';
import { sync, type Projection } from './sync/client';
import { bodyAuthors } from './front-view';
import { pollPayload } from './polls';
import { flushUploads, imageThumbnail, stageBlob } from './sync/uploads';
import { decodeDraft, draftKey, emptyDraft, encodeDraft, loadDraft, occupied, saveDraft, type JournalDraft } from './journal-draft';
import { shelve as shelveDraft, unshelve as unshelveDraft } from './journal-drafts';
import { handleMentions, memberHandles, shownHandle } from './handles';
import { stepIndex, suggest, suggestionAt, applySuggestion, type Suggestion } from './compose-suggest';
import { inline, link, linkProblem, type Sel } from './format-text';

export const MAX_FILES = 10;
type ReplyVisibility = 'private' | 'followers' | 'server';
type Launch = { kind?: JournalDraft['kind']; quote?: string; author?: string; audience?: Audience };

export interface PendingFile { file: File; alt: string; spoiler: boolean; url: string }
export interface Attach {
  /** who the surface suggests writing as (the profile's member, the timeline's "react as") */
  authors?: string[];
  /** a reply's starting audience: the thread's (another account's post starts server-wide) */
  replyVisibility?: ReplyVisibility;
}

/** What a send would create, without the files (they are staged first): the `post.create` payload. */
export function postPayload(d: JournalDraft, authors: string[], replyTo: string | null, rich: { text: string; entities: unknown[] },
  attachmentIds: string[], poll: unknown): Record<string, unknown> {
  return {
    kind: d.kind, authors, title: d.kind === 'entry' ? d.title.trim() || null : null,
    text: rich.text, entities: rich.entities, mood: d.mood.trim() || null,
    tags: d.tags.split(',').map((t) => t.trim().replace(/^#/, '')).filter(Boolean),
    cw: d.cw.trim() || null,
    visibility: d.visibility === 'buckets' ? { mode: 'buckets', bucket_ids: d.bucketIds } : { mode: d.visibility },
    ...(replyTo ? { reply_to: replyTo } : {}),
    ...(d.quote ? { quote: { post_id: d.quote } } : {}),
    ...(attachmentIds.length ? { attachments: attachmentIds } : {}),
    ...(poll ? { poll } : {}),
    ...(d.alerts ? { alerts: d.alerts } : {}),
  };
}

/** Something worth asking about before it goes: words, files, a quote or a poll (not a remembered audience). */
export const hasContent = (d: JournalDraft, files: number) =>
  !!(d.body.trim() || d.title.trim() || d.cw.trim() || d.quote || d.pollOn || files || d.lostFiles);

export class Composer {
  readonly accountId: string;
  readonly context: string;
  readonly replyTo: string | null;
  projection = $state.raw<Projection | null>(null);
  suggested = $state<string[]>([]);
  replyVisibility = $state<ReplyVisibility>('private');
  draft = $state<JournalDraft>(emptyDraft());
  pending = $state<PendingFile[]>([]);
  restored = $state(false);
  error = $state('');
  busy = $state(false);
  loaded = false;
  // what this tab last read or wrote for the draft: only a change made here is written, so a second
  // tab doesn't put its stale copy back over the first's on every sync
  private kept = '';

  constructor(accountId: string, context: string) {
    this.accountId = accountId;
    this.context = context;
    this.replyTo = context.startsWith('reply:') ? context.slice(6) : null;
  }

  readonly availableBuckets = $derived(this.projection ? buckets(this.projection) : []);
  readonly chipBuckets = $derived(this.availableBuckets.map((b) => ({ id: b.id, name: b.name, icon: b.icon })));
  // a new post starts with the account's default audience (D-117); a reply with the thread's
  readonly savedDefault = $derived.by(() => (this.projection?.rows.pref?.[`||${POST_AUDIENCE}`] ?? this.projection?.rows.pref?.[`${this.accountId}||${POST_AUDIENCE}`])?.fields.value);
  readonly accountDefault = $derived(liveAudience(audienceOf(this.savedDefault), new Set(this.availableBuckets.map((b) => b.id))));
  readonly start = $derived.by<Audience>(() => this.replyTo ? { mode: this.replyVisibility } : this.accountDefault);
  readonly startBuckets = $derived(this.start.mode === 'buckets' ? this.start.bucket_ids : []);
  readonly audience = $derived<Audience>(this.draft.visibility === 'buckets' ? { mode: 'buckets', bucket_ids: this.draft.bucketIds } : { mode: this.draft.visibility });

  /** our members this account may write as */
  readonly writers = $derived<MemberRow[]>(this.projection ? members(this.projection).filter((m) => canWriteAs(this.projection!, m.id, this.accountId)) : []);
  // a singlet always writes as themself (D-107); else follow the front (or the surface's suggestion) until someone picks
  readonly self = $derived(this.projection ? selfMember(this.projection) : undefined);
  readonly defaultAuthors = $derived.by(() => this.suggested.length ? this.suggested : this.self ? [this.self.id] : this.projection ? bodyAuthors(this.projection, this.accountId) : []);
  readonly authors = $derived(this.draft.authors ?? this.defaultAuthors.filter((id) => this.writers.some((m) => m.id === id)));
  readonly authorMembers = $derived(this.authors.map((id) => this.writers.find((m) => m.id === id)).filter((m): m is MemberRow => !!m));
  readonly firstName = $derived(this.authorMembers[0] ? this.authorMembers[0].display_name ?? this.authorMembers[0].name : '');

  /** our members an `@` can name, with the handle shown for each */
  readonly mentionChoices = $derived.by<Suggestion[]>(() => {
    const p = this.projection;
    if (!p) return [];
    const own = memberHandles(p, this.accountId);
    return members(p).filter((m) => !m.deleted && !m.archived && own.has(m.id))
      .map((m) => ({ id: m.id, name: m.display_name ?? m.name, handle: shownHandle(m, own) ?? '' })).filter((s) => s.handle);
  });

  readonly hasContent = $derived(hasContent(this.draft, this.pending.length));
  readonly isOccupied = $derived(occupied(this.draft, this.start.mode, this.startBuckets) || this.pending.length > 0);
  // every author must still be ours to write as (a picked member since archived disables Post, not a silent no-op)
  readonly postable = $derived(this.authors.length > 0 && this.authorMembers.length === this.authors.length &&
    (!!this.draft.body.trim() || this.pending.length > 0) && validBucketAudience(this.audience, this.availableBuckets));
  readonly canPost = $derived(!this.busy && this.postable);

  /** A surface showing this draft: hands over the live projection, and loads the draft the first time. */
  attach(projection: Projection, opts: Attach = {}) {
    this.projection = projection;
    if (opts.authors) this.suggested = opts.authors;
    if (opts.replyVisibility) this.replyVisibility = opts.replyVisibility;
    if (this.loaded) return;
    const saved = loadDraft(this.accountId, this.context);
    this.draft = saved ?? emptyDraft(this.start.mode, this.startBuckets);
    this.kept = saved ? encodeDraft(saved) : '';
    // only words, files or a quote count as something to restore; a remembered author or audience is quiet
    this.restored = !!saved && hasContent(saved, 0);
    this.loaded = true;
  }

  /** Keep the draft on this device (files are counted, not kept). */
  persist() {
    if (!this.loaded) return;
    const value = { ...$state.snapshot(this.draft), lostFiles: this.draft.lostFiles + this.pending.length };
    const keep = occupied(value, this.start.mode, this.startBuckets) ? value : null;
    const enc = keep ? encodeDraft(keep) : '';
    if (enc === this.kept) return;
    this.kept = enc;
    saveDraft(this.accountId, this.context, keep);
  }

  /** Another tab wrote the main draft: take it, unless this tab holds files of its own or is posting. */
  adopt(raw: string | null) {
    if (!this.loaded || this.busy || this.pending.length) return;
    const next = decodeDraft(raw);
    const enc = next ? encodeDraft(next) : '';
    if (enc === this.kept) return;
    this.kept = enc;
    this.draft = next ?? emptyDraft(this.start.mode, this.startBuckets);
    this.restored = !!next?.lostFiles;
  }

  setAudience(a: Audience | null) {
    const next = a ?? { mode: 'private' as const };
    this.draft.visibility = next.mode;
    this.draft.bucketIds = next.mode === 'buckets' ? next.bucket_ids : [];
  }
  useByDefault(on: boolean) {
    sync.create('pref.set', sync.accountScope, null, { device: '', key: POST_AUDIENCE, value: on ? $state.snapshot(this.audience) : null });
  }
  setAuthors(ids: string[]) { this.draft.authors = ids; }

  addFiles(files: FileList | File[]) {
    const room = MAX_FILES - this.pending.length;
    const list = [...files].slice(0, Math.max(0, room));
    if (files.length > list.length) this.error = `A post holds up to ${MAX_FILES} files.`;
    if (!list.length) return;
    this.draft.lostFiles = 0; // attaching again replaces the restored draft's missing files
    this.pending = [...this.pending, ...list.map((file) => ({ file, alt: '', spoiler: false, url: file.type.startsWith('image/') ? URL.createObjectURL(file) : '' }))];
  }
  removeFile(i: number) {
    if (this.pending[i]?.url) URL.revokeObjectURL(this.pending[i].url);
    this.pending = this.pending.filter((_, n) => n !== i);
  }
  private dropFiles() {
    for (const item of this.pending) if (item.url) URL.revokeObjectURL(item.url);
    this.pending = [];
  }

  /** Empty the composer (the draft is gone). */
  discard() {
    this.dropFiles();
    this.draft = emptyDraft(this.start.mode, this.startBuckets);
    this.restored = false;
    this.error = '';
    this.persist();
  }

  /** Put the draft on the Drafts shelf and start an empty one; false when there was nothing to keep. */
  shelve(): boolean {
    if (!this.hasContent) return false;
    const kept = { ...$state.snapshot(this.draft), lostFiles: this.draft.lostFiles + this.pending.length };
    shelveDraft(this.accountId, kept, sync.newId(), Date.now());
    this.discard();
    return true;
  }

  /** Open a shelved draft here, shelving what the composer holds first (Android's Drafts). */
  unshelve(id: string) {
    const next = unshelveDraft(this.accountId, id);
    if (!next) return;
    this.shelve();
    this.dropFiles();
    this.draft = next;
    this.restored = next.lostFiles > 0;
    this.error = '';
    this.persist();
  }

  /** Would applying an opener's ask change a draft that holds something? Then the writer is asked first (Android's "You have a Journal draft"). */
  asksFirst(o: Launch): boolean {
    if (!this.hasContent) return false;
    const d = this.draft;
    return (!!o.quote && o.quote !== d.quote) || (!!o.kind && o.kind !== d.kind) ||
      (!!o.author && (this.authors.length !== 1 || this.authors[0] !== o.author)) ||
      (!!o.audience && JSON.stringify(o.audience) !== JSON.stringify($state.snapshot(this.audience)));
  }

  /** Apply what an opener asked for (a quote, a kind, an author, an audience). */
  apply(o: Launch) {
    if (o.kind) this.draft.kind = o.kind;
    if (o.quote) this.draft.quote = o.quote;
    if (o.author) this.draft.authors = [o.author];
    if (o.audience) this.setAudience(o.audience);
  }

  /** Post it: files first, then the post. True once the post op is written. */
  async send(): Promise<boolean> {
    const projection = this.projection;
    if (!projection || !this.canPost || this.authors.some((id) => !canWriteAs(projection, id, this.accountId))) return false;
    const poll = (d: JournalDraft) => {
      const closes = d.pollCloses ? new Date(d.pollCloses).getTime() : null;
      return d.pollOn ? pollPayload(d.pollOptions, d.pollMultiple, Number.isFinite(closes) ? closes : null) : {};
    };
    const checked = poll(this.draft);
    if (checked.error) { this.error = checked.error; return false; }
    this.busy = true;
    this.error = '';
    const files = this.pending;
    try {
      // files first: a post never names an attachment that isn't queued on this device
      const staged = new Map<File, string>();
      for (const item of files) {
        const original = await stageBlob(item.file, item.file.type, this.accountId);
        const thumb = await imageThumbnail(item.file);
        const thumbnail = thumb ? await stageBlob(thumb, thumb.type, this.accountId) : null;
        const id = sync.newId();
        // a post's files are account-scoped rows (`post.attachment`); chat uses the space's `attachment.create`
        sync.create('post.attachment', sync.accountScope, id, {
          blob_hash: original.hash, filename: item.file.name, mime: original.mime, size: item.file.size,
          ...(thumbnail ? { thumb_blob_hash: thumbnail.hash } : {}),
          alt_text: item.alt.trim(), is_spoiler: item.spoiler,
        });
        staged.set(item.file, id);
      }
      // what the composer shows once the files are staged is what goes (words typed meanwhile are not lost,
      // a file removed meanwhile stays out, one added meanwhile waits in the tray)
      const sent = this.pending.filter((p) => staged.has(p.file));
      const attachmentIds = sent.map((p) => staged.get(p.file)!);
      const sending = $state.snapshot(this.draft);
      const authors = $state.snapshot(this.authors);
      const pollDraft = poll(sending);
      if (!this.postable || (!sending.body.trim() && !attachmentIds.length) || pollDraft.error || authors.some((id) => !canWriteAs(projection, id, this.accountId))) {
        this.error = pollDraft.error ?? 'Could not post. Your draft is kept.';
        return false;
      }
      // our members' @handles become mentions (D-126)
      const mentions = Object.fromEntries([...handleMentions(memberHandles(projection, this.accountId), members(projection))]
        .map(([handle, member]) => [handle, { target_type: 'member', target_id: member }]));
      const rich = core.parseMarkup(sending.body, { mentions, headings: true });
      sync.create('post.create', sync.accountScope, sync.newId(), postPayload(sending, authors, this.replyTo, rich, attachmentIds, pollDraft.poll));
      if (sync.status === 'live') void flushUploads(sync.device);
      // keep who is writing and where it goes, unless new posts have a default; clear what was said
      this.draft = { ...(this.savedDefault && !this.replyTo ? emptyDraft(this.start.mode, this.startBuckets) : emptyDraft(sending.visibility, sending.bucketIds)), authors: sending.authors };
      for (const item of files) if (item.url) URL.revokeObjectURL(item.url);
      this.pending = this.pending.filter((p) => !staged.has(p.file));
      this.restored = false;
      this.persist();
      return true;
    } catch (cause) {
      this.error = `Could not post. Your draft is kept. ${cause instanceof Error ? cause.message : String(cause)}`;
      return false;
    } finally { this.busy = false; }
  }
}

// one composer per account and context, for as long as the tab lives
const registry = new Map<string, Composer>();
export function composerFor(context: string, accountId = sync.accountId): Composer {
  const key = `${accountId.length}:${accountId}:${context}`;
  let c = registry.get(key);
  if (!c) registry.set(key, (c = new Composer(accountId, context)));
  if (!listening && typeof window !== 'undefined') {
    listening = true;
    // the main draft lives in localStorage: another tab's edit shows here too, so neither puts back a stale copy
    window.addEventListener('storage', (e) => {
      for (const one of registry.values()) if (one.context === 'main' && e.key === draftKey(one.accountId, 'main')) one.adopt(e.newValue);
    });
  }
  return c;
}
let listening = false;
export const contextOf = (replyTo?: string | null) => (replyTo ? `reply:${replyTo}` : 'main');

/** One surface's text field: its selection, and the formatting that acts on it. Each surface has its own
 * (the quick box and the dialog can both be on screen), all writing into the same draft. */
let uid = 0;
export class TextEditing {
  readonly c: Composer;
  el = $state<HTMLTextAreaElement | undefined>();
  caret = $state({ start: 0, end: 0 });
  focused = $state(false);
  /** the sheet this field has open */
  sheet = $state<'' | 'link' | 'format' | 'emoji' | 'alerts'>('');
  linkUrl = $state('');
  linkError = $state('');
  /** the formatting row under the text (the quick and reply boxes keep it behind T) */
  formatRow = $state(false);
  cwOpen = $state(false);
  tagsOpen = $state(false);

  /** the `@` suggestions: the highlighted row, and the @word an Escape put away */
  readonly listId = `mention-${++uid}`;
  index = $state(0);
  dismissed = $state(-1);
  private lastKey = '';

  constructor(c: Composer) { this.c = c; }

  readonly at = $derived.by(() => (this.focused ? suggestionAt({ text: this.c.draft.body, ...this.caret }) : null));
  readonly matches = $derived.by(() => (this.at && this.dismissed !== this.at.from ? suggest(this.at.query, this.c.mentionChoices) : []));
  moveMention = (delta: number) => { this.index = stepIndex(this.index, delta, this.matches.length); };
  pickMention = (s: Suggestion | undefined = this.matches[this.index]) => {
    const at = this.at;
    if (!at || !s) return;
    void this.apply(applySuggestion(this.current(), at, s.handle));
  };
  closeMention = () => { if (this.at) this.dismissed = this.at.from; };

  readonly showCw = $derived.by(() => this.cwOpen || !!this.c.draft.cw);
  readonly showTags = $derived.by(() => this.tagsOpen || !!this.c.draft.tags || !!this.c.draft.mood);
  toggleCw = () => {
    if (this.showCw) { this.c.draft.cw = ''; this.cwOpen = false; } else this.cwOpen = true;
  };

  readonly picked = $derived(this.focused && this.caret.start !== this.caret.end);
  current = (): Sel => ({ text: this.c.draft.body, ...this.caret });
  track = () => {
    if (!this.el) return;
    this.caret = { start: this.el.selectionStart, end: this.el.selectionEnd };
    this.focused = document.activeElement === this.el;
    // a new word starts at the top of the list; leaving the @word forgets an Escape
    const at = this.focused ? suggestionAt(this.current()) : null;
    const key = at ? `${at.from}:${at.query}` : '';
    if (key !== this.lastKey) { this.index = 0; this.lastKey = key; }
    if (!at) this.dismissed = -1;
  };
  apply = async (next: Sel) => {
    this.c.draft.body = next.text;
    this.caret = { start: next.start, end: next.end };
    await tick();
    this.el?.focus();
    this.el?.setSelectionRange(next.start, next.end);
    this.track();
  };
  act = (fn: (v: Sel) => Sel) => { this.track(); void this.apply(fn(this.current())); };
  mark = (marker: string) => this.act((v) => inline(v, marker));
  openLink = () => { this.track(); this.linkUrl = ''; this.linkError = ''; this.sheet = 'link'; };
  addLink = () => {
    const url = this.linkUrl.trim();
    const problem = linkProblem(url);
    if (problem) { this.linkError = problem; return; }
    this.sheet = '';
    void this.apply(link(this.current(), url));
  };
}
