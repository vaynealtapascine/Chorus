/** A Journal composer draft kept in this tab (Android keeps one in its encrypted store; the web, like
 * its chat drafts, keeps one per composer context in sessionStorage). Files are not kept, so a
 * restored draft says how many were left behind. `authors: null` means "follow the current front". */
export interface JournalDraft {
  kind: 'note' | 'entry';
  title: string;
  body: string;
  cw: string;
  mood: string;
  tags: string;
  visibility: 'private' | 'buckets' | 'friends' | 'followers' | 'following' | 'server';
  bucketIds: string[];
  authors: string[] | null;
  quote: string | null;
  pollOn: boolean;
  pollOptions: string[];
  pollMultiple: boolean;
  /** Attachments that were in the composer when it was saved; they must be attached again. */
  lostFiles: number;
  /** Who hears about it (D-127): '' as usual, `delayed` (a random 5–60 minutes later) or `none`. */
  alerts: '' | 'delayed' | 'none';
}

export const emptyDraft = (visibility: JournalDraft['visibility'] = 'private', bucketIds: string[] = []): JournalDraft => ({
  kind: 'note', title: '', body: '', cw: '', mood: '', tags: '', visibility, bucketIds, authors: null,
  quote: null, pollOn: false, pollOptions: ['', ''], pollMultiple: false, lostFiles: 0, alerts: '',
});

/** Worth keeping: anything the writer typed or chose beyond the context's defaults. */
export function occupied(d: JournalDraft, defaultVisibility: JournalDraft['visibility'] = 'private', defaultBuckets: string[] = []): boolean {
  return !!(d.body.trim() || d.title.trim() || d.cw.trim() || d.mood.trim() || d.tags.trim() || d.quote || d.pollOn ||
    d.authors !== null || d.visibility !== defaultVisibility || d.bucketIds.join() !== defaultBuckets.join() || d.lostFiles || d.alerts);
}

/** Length-prefixed so a context id can never alias another account's key. */
export function draftKey(accountId: string, context: string): string {
  return `chorus.journal-draft.v1:${accountId.length}:${accountId}:${context}`;
}

const strings = (v: unknown): string[] => Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
const text = (v: unknown): string => typeof v === 'string' ? v : '';

/** Read a stored draft field by field; anything malformed falls back to the safe default (private,
 * no explicit authors), never to a wider audience. */
export function decodeDraft(raw: string | null): JournalDraft | null {
  if (!raw) return null;
  let j: Record<string, unknown>;
  try { j = JSON.parse(raw); } catch { return null; }
  if (!j || typeof j !== 'object' || j.v !== 1) return null;
  const visibility = (['private', 'buckets', 'friends', 'followers', 'following', 'server'] as const).find((v) => v === j.visibility) ?? 'private';
  const options = strings(j.pollOptions);
  return {
    kind: j.kind === 'entry' ? 'entry' : 'note',
    title: text(j.title), body: text(j.body), cw: text(j.cw), mood: text(j.mood), tags: text(j.tags),
    visibility, bucketIds: strings(j.bucketIds),
    authors: Array.isArray(j.authors) ? strings(j.authors) : null,
    quote: typeof j.quote === 'string' && j.quote ? j.quote : null,
    pollOn: j.pollOn === true, pollOptions: options.length >= 2 ? options : ['', ''], pollMultiple: j.pollMultiple === true,
    lostFiles: typeof j.lostFiles === 'number' && j.lostFiles > 0 ? Math.floor(j.lostFiles) : 0,
    alerts: j.alerts === 'delayed' || j.alerts === 'none' ? j.alerts : '',
  };
}

export function encodeDraft(d: JournalDraft): string {
  return JSON.stringify({ v: 1, ...d });
}
