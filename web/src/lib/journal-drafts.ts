// The shelf: Journal drafts kept for later (Android's Drafts drawer, JournalDraftSession.shelve).
import { decodeDraft, encodeDraft, type JournalDraft } from './journal-draft';
import { wordCount } from './format-text';

export interface ShelvedDraft { id: string; savedAt: number; draft: JournalDraft }

const MAX_SHELF = 50;
const shelfKey = (accountId: string) => `chorus.journal-shelf.v1:${accountId.length}:${accountId}`;

function read(accountId: string): ShelvedDraft[] {
  try {
    const raw = globalThis.localStorage?.getItem(shelfKey(accountId));
    if (!raw) return [];
    const j = JSON.parse(raw);
    if (!j || j.v !== 1 || !Array.isArray(j.drafts)) return [];
    const out: ShelvedDraft[] = [];
    for (const r of j.drafts) {
      if (!r || typeof r.id !== 'string' || typeof r.savedAt !== 'number' || !r.draft || typeof r.draft !== 'object') continue;
      const draft = decodeDraft(JSON.stringify({ ...r.draft, v: 1 }));
      if (draft) out.push({ id: r.id, savedAt: r.savedAt, draft });
    }
    return out.sort((a, b) => b.savedAt - a.savedAt);
  } catch { return []; }
}

function write(accountId: string, rows: ShelvedDraft[]): void {
  try {
    const drafts = rows.map((r) => ({ id: r.id, savedAt: r.savedAt, draft: JSON.parse(encodeDraft(r.draft)) }));
    globalThis.localStorage?.setItem(shelfKey(accountId), JSON.stringify({ v: 1, drafts }));
  } catch { /* storage full or blocked: the shelf stays as it was */ }
}

/** The kept drafts, newest first. */
export function shelf(accountId: string): ShelvedDraft[] {
  return read(accountId);
}

/** Does the draft hold anything of the writer's own? (`occupied` without audience, authors and alerts) */
const worthKeeping = (d: JournalDraft) =>
  !!(d.body.trim() || d.title.trim() || d.cw.trim() || d.mood.trim() || d.tags.trim() || d.quote || d.pollOn || d.lostFiles);

/** Keep a draft for later under `id` (replacing that row); the oldest beyond 50 are dropped. */
export function shelve(accountId: string, draft: JournalDraft, id: string, now: number): void {
  if (!worthKeeping(draft)) return;
  const rows = [{ id, savedAt: now, draft }, ...read(accountId).filter((r) => r.id !== id)];
  write(accountId, rows.sort((a, b) => b.savedAt - a.savedAt).slice(0, MAX_SHELF));
}

/** Take a draft off the shelf and hand it back. */
export function unshelve(accountId: string, id: string): JournalDraft | null {
  const rows = read(accountId);
  const hit = rows.find((r) => r.id === id);
  if (!hit) return null;
  write(accountId, rows.filter((r) => r.id !== id));
  return hit.draft;
}

export function discardShelved(accountId: string, id: string): void {
  const rows = read(accountId);
  if (rows.some((r) => r.id === id)) write(accountId, rows.filter((r) => r.id !== id));
}

/** A row's two lines in the Drafts list (the same text as Android's drawer). */
export function draftHeadline(d: JournalDraft): { head: string; meta: string } {
  const line = d.body.split('\n').find((l) => l.trim()) ?? '';
  const head = (d.title.trim() || line.replace(/^[#>•\s]+/, '').trim()) || (d.lostFiles ? `${d.lostFiles} files` : 'Empty');
  const words = wordCount(d.body);
  const meta = [d.kind === 'entry' ? 'Journal entry' : 'Post', words ? `${words} ${words === 1 ? 'word' : 'words'}` : null,
    d.pollOn ? 'Poll' : null, d.quote ? 'Quote' : null].filter(Boolean).join(' · ');
  return { head, meta };
}
