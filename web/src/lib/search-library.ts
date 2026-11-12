import type { AttachmentRow, MessageRow } from './data';

/** Put `key:value` into a query (replacing the one already there), or take it out with null. */
export function setOperator(query: string, key: 'from' | 'in' | 'after', value: string | null): string {
  const rest = (query.match(/(?:[^\s"]|"[^"]*")+/g) ?? []).filter((part) => !part.toLowerCase().startsWith(`${key}:`));
  if (value) rest.push(`${key}:${/\s/.test(value) ? `"${value}"` : value}`);
  return rest.join(' ');
}

export type LibraryKind = 'media' | 'files' | 'links';
export interface LibraryItem { id: string; message: MessageRow; attachment?: AttachmentRow; url?: string; label: string }

const fold = (s: string) => s.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase();

/** Every picture, file or link in the given messages, newest first, matching all of `terms` (words of the file name, link or text). */
export function libraryOf(messages: MessageRow[], kind: LibraryKind, terms: string[], limit = 300): LibraryItem[] {
  const out: LibraryItem[] = [];
  const has = (s: string) => { const f = fold(s); return terms.every((t) => f.includes(t)); };
  for (const m of [...messages].sort((a, b) => b.occurred_at - a.occurred_at || b.id.localeCompare(a.id))) {
    if (m.deleted || m.cw) continue;
    if (kind === 'links') {
      for (const e of m.entities) {
        if (!['url', 'text_link'].includes(e.type)) continue;
        if (m.entities.some((s) => s.type === 'spoiler' && s.offset < e.offset + e.length && s.offset + s.length > e.offset)) continue;
        const shown = m.text.slice(e.offset, e.offset + e.length);
        const url = e.type === 'url' ? shown : String(e.url ?? '');
        if (/^https?:\/\//i.test(url) && has(`${shown} ${url}`)) out.push({ id: `${m.id}:${e.offset}`, message: m, url, label: shown || url });
      }
    } else {
      for (const a of m.attachments) {
        if (a.is_spoiler || a.mime.startsWith('image/') !== (kind === 'media')) continue;
        if (has(`${a.filename} ${a.alt_text ?? ''} ${m.text}`)) out.push({ id: a.id, message: m, attachment: a, label: a.filename });
      }
    }
    if (out.length >= limit) break;
  }
  return out;
}
