import type { Projection } from './sync/client';
import type { Delta } from './sync/delta';

export interface SearchHit {
  id: string;
  channel_id: string;
  account_id?: string;
  occurred_at: number;
  text: string;
  cw?: string;
  visibility?: { mode: string; member_ids?: string[] };
  authors: string[];
  attachments: string[];
  has_image: boolean;
  has_file: boolean;
}

export interface SearchQuery {
  terms: string[];
  from?: string;
  in?: string;
  has?: string;
  before?: number;
  after?: number;
}

const fold = (value: string) => value.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase();
const tokens = (value: string) => fold(value).match(/[\p{L}\p{N}]+/gu) ?? [];
const str = (value: unknown): string | undefined => typeof value === 'string' ? value : undefined;
const strs = (value: unknown): string[] => Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];

export function parseSearch(value: string): SearchQuery {
  const out: SearchQuery = { terms: [] };
  for (const part of value.match(/(?:[^\s"]|"[^"]*")+/g) ?? []) {
    const match = part.match(/^(from|in|has|before|after):(.+)$/i);
    if (!match) { out.terms.push(...tokens(part)); continue; }
    const key = match[1].toLowerCase() as 'from' | 'in' | 'has' | 'before' | 'after';
    const raw = match[2].replace(/^"|"$/g, '');
    if (key === 'before' || key === 'after') {
      const at = /^\d+$/.test(raw) ? Number(raw) : Date.parse(raw);
      if (Number.isFinite(at)) out[key] = at;
    } else out[key] = raw;
  }
  return out;
}

/** Incremental token index over the confirmed + pending projection. */
export class SearchIndex {
  private docs = new Map<string, SearchHit>();
  private words = new Map<string, Set<string>>();
  private docWords = new Map<string, string[]>();
  private attachmentUsers = new Map<string, Set<string>>();
  private members = new Map<string, string>();
  private channels = new Map<string, string>();
  private sortedWords: string[] = [];
  private dirtyWords = true;

  constructor(projection: Projection) { this.rebuild(projection); }

  rebuild(p: Projection): void {
    this.docs.clear(); this.words.clear(); this.docWords.clear(); this.attachmentUsers.clear();
    this.members.clear(); this.channels.clear();
    for (const [id, row] of Object.entries(p.rows.member ?? {})) this.name(this.members, id, row);
    for (const [id, row] of Object.entries(p.rows.channel ?? {})) this.name(this.channels, id, row);
    for (const id of Object.keys(p.rows.message ?? {})) this.updateMessage(p, id);
    this.dirtyWords = true;
  }

  apply(p: Projection, d: Delta): void {
    if (d.full) { this.rebuild(p); return; }
    for (const id of Object.keys(d.rows.member ?? {})) this.name(this.members, id, p.rows.member?.[id]);
    for (const id of Object.keys(d.rows.channel ?? {})) this.name(this.channels, id, p.rows.channel?.[id]);
    const touched = new Set(Object.keys(d.rows.message ?? {}));
    for (const id of Object.keys(d.rows.attachment ?? {}))
      for (const messageId of this.attachmentUsers.get(id) ?? []) touched.add(messageId);
    for (const id of touched) this.updateMessage(p, id);
  }

  private name(map: Map<string, string>, id: string, row: Projection['rows'][string][string] | undefined): void {
    if (!row?.exists) { map.delete(id); return; }
    map.set(id, fold([row.fields.name, row.fields.display_name].filter((v): v is string => typeof v === 'string').join(' ')));
  }

  private remove(id: string): void {
    const old = this.docs.get(id);
    if (!old) return;
    for (const word of this.docWords.get(id) ?? []) {
      const users = this.words.get(word);
      users?.delete(id);
      if (users?.size === 0) { this.words.delete(word); this.dirtyWords = true; }
    }
    for (const attachment of old.attachments) {
      const users = this.attachmentUsers.get(attachment);
      users?.delete(id);
      if (users?.size === 0) this.attachmentUsers.delete(attachment);
    }
    this.docWords.delete(id);
    this.docs.delete(id);
  }

  private updateMessage(p: Projection, id: string): void {
    this.remove(id);
    const row = p.rows.message?.[id];
    if (!row?.exists || row.fields.deleted_at != null) return;
    const f = row.fields;
    const attachments = strs(f.attachments);
    const hit: SearchHit = {
      id, channel_id: str(f.channel_id) ?? '', account_id: str(f.account_id),
      occurred_at: typeof f.occurred_at === 'number' ? f.occurred_at : 0,
      text: str(f.text) ?? '', cw: str(f.cw),
      visibility: f.visibility && typeof f.visibility === 'object' ? f.visibility as SearchHit['visibility'] : undefined,
      authors: strs(f.authors), attachments,
      has_image: attachments.some((a) => str(p.rows.attachment?.[a]?.fields.mime)?.startsWith('image/')),
      has_file: attachments.some((a) => { const mime = str(p.rows.attachment?.[a]?.fields.mime); return !!mime && !mime.startsWith('image/'); }),
    };
    this.docs.set(id, hit);
    for (const attachment of attachments) {
      if (!this.attachmentUsers.has(attachment)) this.attachmentUsers.set(attachment, new Set());
      this.attachmentUsers.get(attachment)!.add(id);
    }
    const unique = [...new Set(tokens(`${hit.text} ${hit.cw ?? ''}`))];
    this.docWords.set(id, unique);
    for (const word of unique) {
      if (!this.words.has(word)) { this.words.set(word, new Set()); this.dirtyWords = true; }
      this.words.get(word)!.add(id);
    }
  }

  private prefix(term: string): Set<string> {
    if (this.dirtyWords) { this.sortedWords = [...this.words.keys()].sort(); this.dirtyWords = false; }
    let low = 0, high = this.sortedWords.length;
    while (low < high) { const mid = (low + high) >>> 1; if (this.sortedWords[mid] < term) low = mid + 1; else high = mid; }
    const ids = new Set<string>();
    for (let i = low; i < this.sortedWords.length && this.sortedWords[i].startsWith(term); i++)
      for (const id of this.words.get(this.sortedWords[i]) ?? []) ids.add(id);
    return ids;
  }

  search(query: SearchQuery, limit = 100): SearchHit[] {
    const terms = query.terms.map(fold).filter(Boolean);
    let ids: Set<string> | undefined;
    for (const term of terms) {
      const matches = this.prefix(term);
      if (ids === undefined) ids = matches;
      else {
        const next = new Set<string>();
        for (const id of ids) if (matches.has(id)) next.add(id);
        ids = next;
      }
      if (!ids.size) return [];
    }
    const from = query.from ? fold(query.from) : null;
    const channel = query.in ? fold(query.in) : null;
    const hits: SearchHit[] = [];
    for (const id of ids ?? this.docs.keys()) {
      const hit = this.docs.get(id)!;
      if (from && !hit.authors.some((a) => a === query.from || this.members.get(a)?.includes(from))) continue;
      if (channel && hit.channel_id !== query.in && !this.channels.get(hit.channel_id)?.includes(channel)) continue;
      if (query.before !== undefined && hit.occurred_at >= query.before) continue;
      if (query.after !== undefined && hit.occurred_at <= query.after) continue;
      if (query.has && !(
        query.has === 'image' ? hit.has_image :
        query.has === 'file' ? hit.has_file :
        query.has === 'attachment' ? hit.attachments.length : false
      )) continue;
      hits.push(hit);
    }
    return hits.sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id)).slice(0, limit);
  }
}
