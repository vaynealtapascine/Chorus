import { channels, spaces, members, messageById, type MessageRow } from './data';
import { bodyPresence } from './front-view';
import { activeViewers, memberVisible } from './hidden';
import { chatPrefs, privateVisible } from './chat-list';
import type { Poll, PollTally } from './polls';
import type { MemberRow } from './data';
import type { Projection } from './sync/client';

/** Local view guard, used again for every result, neighbor and jump. Server owns delivery. */
export function searchView(p: Projection, account: string, ancestors: ReadonlyMap<string, MessageRow> = new Map()): (m: MessageRow | undefined) => boolean {
  const cs = new Map(channels(p).map(c => [c.id, c]));
  const ss = new Map(spaces(p).map(s => [s.id, s]));
  const front = bodyPresence(p, account), active = activeViewers(front);
  const own = new Set(members(p).map(m => m.id));
  const prefs = chatPrefs(p.rows.pref ?? {}, account);
  // A known local tombstone/malformed row must never fall back to an older network card.
  function localOrAncestor(id: string): MessageRow | undefined {
    return p.rows.message?.[id] ? messageById(p,id) : ancestors.get(id);
  }
  function view(m: MessageRow | undefined, seen = new Set<string>()): boolean {
    if (!m || m.deleted || seen.size > 256 || seen.has(m.channel_id)) return false;
    const c = cs.get(m.channel_id), s = c && ss.get(c.space_id);
    if (!c || c.archived || c.invalid_membership || !s) return false;
    seen.add(c.id);
    // Permissions can arrive before scope repair evicts the old message rows.
    const sf = p.rows.space?.[s.id]?.fields ?? {};
    const sm = p.rows.space_member?.[`${s.id}|${account}`]?.fields;
    const role = sm?.is_present === false ? undefined : sm?.role;
    const owner = sf.owner_account_id ?? sf.created_by_account_id;
    if (c.kind !== 'thread' && owner !== account && role !== 'admin' && !(s.kind === 'dm' && role)) {
      let allowed: boolean | null = null;
      for (const [type, id] of [['account', account], ...(typeof role === 'string' ? [['role', role], ['role', 'everyone']] : [])]) {
        const f = p.rows.channel_permission?.[`${c.id}|${type}|${id}`]?.fields;
        if (Array.isArray(f?.deny) && f.deny.includes('view')) return false;
        if (Array.isArray(f?.allow) && f.allow.includes('view')) { allowed = true; break; }
      }
      if (allowed === null && role !== 'member' && role !== 'read_only' && !(Array.isArray(sf.roles) && sf.roles.some((r: unknown) => !!r && typeof r === 'object' && 'id' in r && r.id === role && 'perms' in r && Array.isArray(r.perms) && r.perms.includes('view')))) return false;
    }
    if (c.kind === 'thread') {
      if (!c.parent_message_id || !view(localOrAncestor(c.parent_message_id), seen)) return false;
    } else if (!privateVisible(c, s, front, null, true, id => own.has(id), front)) return false;
    const cutoff = prefs.hidden.get(c.id);
    if (cutoff != null && m.occurred_at <= cutoff) return false;
    const raw = p.rows.message?.[m.id]?.fields.visibility ?? m.visibility;
    if(raw != null && (typeof raw !== 'object' || !('mode' in raw) || typeof raw.mode !== 'string')) return false;
    const mode = m.visibility?.mode;
    if(mode === 'members' && !Array.isArray(m.visibility?.member_ids)) return false;
    if (mode === 'system_only' && m.account_id && m.account_id !== account) return false;
    if (mode === 'members') {
      if (m.account_id && m.account_id !== account) return false;
      if (!memberVisible(m, 'internal', active, null)) return false;
    } else if (mode && !['all', 'system_only'].includes(mode)) return false;
    return true;
  }
  return m => view(m ? (p.rows.message?.[m.id] ? messageById(p,m.id) : m) : undefined);
}

export function contextMessages(hit: MessageRow, candidates: MessageRow[], visible: (m: MessageRow) => boolean): MessageRow[] {
  if (!visible(hit)) return [];
  const rows = [...new Map([...candidates, hit].filter(m => m.channel_id === hit.channel_id && visible(m)).map(m => [m.id, m])).values()]
    .sort((a,b) => a.occurred_at-b.occurred_at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const i = rows.findIndex(m => m.id === hit.id);
  return rows.slice(Math.max(0,i-1),i+2);
}
/** Build each channel once, rather than rescanning its history for every match. */
export function searchContexts(p: Projection, hits: MessageRow[], remote: (MessageRow & {context_before?: MessageRow|null;context_after?: MessageRow|null})[], visible: (m:MessageRow)=>boolean): Map<string,MessageRow[]> {
  const wanted = new Set(hits.map(m=>m.channel_id));
  const all = [...remote.flatMap(m=>[m,m.context_before,m.context_after]), ...Object.entries(p.rows.message ?? {}).filter(([,r])=>wanted.has(String(r.fields.channel_id))).map(([id])=>messageById(p,id)), ...hits];
  const groups = new Map<string,Map<string,MessageRow>>();
  for(const m of all) if(m && wanted.has(m.channel_id) && visible(m)) {
    let g=groups.get(m.channel_id);if(!g)groups.set(m.channel_id,g=new Map());g.set(m.id,m);
  }
  const neighbors=new Map<string,MessageRow[]>();
  for(const g of groups.values()) {
    const rows=[...g.values()].sort((a,b)=>a.occurred_at-b.occurred_at || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    for(let i=0;i<rows.length;i++) neighbors.set(rows[i].id,rows.slice(Math.max(0,i-1),i+2));
  }
  return neighbors;
}
export type SearchMessage = MessageRow & {author_cards?: MemberRow[];poll?: Poll|null;poll_tally?:PollTally|null};
export function remoteMessage(raw: Partial<MessageRow> & { id: string; channel_id: string; occurred_at: number; text: string }): SearchMessage {
  return { authors: [], entities: [], segments: [], attachments: [], deleted: false, edited: false, pinned: false, sent_offline: false, ...raw,
    cw: raw.cw ?? undefined, visibility: raw.visibility ?? undefined, reply_to: raw.reply_to ?? undefined, forward_snapshot: raw.forward_snapshot ?? undefined, quote: raw.quote ?? undefined };
}

/** Fetch ephemeral thread dependencies through the same guarded jump endpoint. No sync/cache grant. */
export async function searchAncestors(p: Projection, fetch: (id:string)=>Promise<MessageRow|undefined>, wanted?: Iterable<string>): Promise<Map<string,MessageRow>> {
  const cs=new Map(channels(p).map(c=>[c.id,c]));
  const found=new Map<string,MessageRow>(), attempted=new Set<string>();
  async function visit(channel:string, seen=new Set<string>()):Promise<void> {
    const c=cs.get(channel);
    if(!c || c.archived || c.kind!=='thread' || !c.parent_message_id || seen.has(channel) || seen.size>256) return;
    seen.add(channel);
    const id=c.parent_message_id;
    let parent=p.rows.message?.[id] ? messageById(p,id) : found.get(id);
    if(!p.rows.message?.[id] && !parent && !attempted.has(id)) {
      attempted.add(id); parent=await fetch(id);
      if(parent?.id===id) found.set(id,parent); else parent=undefined;
    }
    if(parent && !parent.deleted) await visit(parent.channel_id,seen);
  }
  for(const id of wanted ?? cs.keys()) await visit(id);
  return found;
}
