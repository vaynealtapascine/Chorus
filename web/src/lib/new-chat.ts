// The New message page (Figma F5.18) and the New shared space page (F5.10): who is listed, in what
// order, and what the chat list's round button starts from each folder (F5.22). Port of Android's
// data/NewChat.kt.
import { fuzzy, type MemberRow } from './data';
import { accountName, type SpaceAccount } from './spaces';
import { frontingLine } from './friends';
import type { Settings as FolderSettings } from './chat-folders';

/** "space" when the open folder holds only shared spaces (the Shared tab, or a folder of our own that
 * is every shared space and nothing else), else "message". */
export function startFrom(folder: string, settings: Pick<FolderSettings, 'custom'>): 'space' | 'message' {
  const custom = settings.custom.find((c) => c.id === folder);
  const only = !!custom && custom.kinds.length === 1 && custom.kinds[0] === 'shared' && !custom.chats.length;
  return folder === 'shared' || only ? 'space' : 'message';
}

const matches = (query: string, ...names: (string | null | undefined)[]) =>
  !query.trim() || names.some((n) => !!n && fuzzy(query, n) !== null);

/** Our members one may write to, without `speaker`: those here now first (in the order given), then the rest. */
export function memberList(own: MemberRow[], speaker: string | null, here: string[], query: string, handle: (m: MemberRow) => string | undefined): MemberRow[] {
  const rank = new Map(here.map((id, i) => [id, i]));
  return own
    .filter((m) => m.id !== speaker && matches(query, m.display_name, m.name, ...m.sigils, handle(m)))
    .map((m, i) => ({ m, i }))
    .sort((a, b) => (rank.get(a.m.id) ?? 1e9) - (rank.get(b.m.id) ?? 1e9) || a.i - b.i)
    .map((x) => x.m);
}

/** What a member's row says under the name: where they are now, else their pronouns. */
export const memberLine = (layer: string | undefined, pronouns: string | null | undefined): string | null =>
  layer || pronouns?.trim() || null;

export const friendList = (accounts: SpaceAccount[], query: string): SpaceAccount[] =>
  accounts.filter((a) => matches(query, accountName(a), a.handle));

/** Under a friend's name: who is fronting there, as far as they share it, else what they are. */
export const friendLine = (account: Pick<SpaceAccount, 'kind'>, fronting: string[] | undefined): string =>
  (fronting && frontingLine(fronting)) || (account.kind === 'system' ? 'a system' : 'one person');

export const toggle = <T>(list: T[], item: T): T[] => (list.includes(item) ? list.filter((x) => x !== item) : [...list, item]);

/** "2 systems selected", "1 person selected", "3 selected" (a mix). */
export function selectedLabel(chosen: Pick<SpaceAccount, 'kind'>[]): string | null {
  if (!chosen.length) return null;
  const systems = chosen.filter((a) => a.kind === 'system').length;
  const noun = systems === chosen.length ? (systems === 1 ? 'system' : 'systems') : systems === 0 ? (chosen.length === 1 ? 'person' : 'people') : null;
  return [String(chosen.length), noun, 'selected'].filter(Boolean).join(' ');
}

/** A first name for the new space: us and who we picked ("Lanterns, Junipers & Driftwood"). */
export function spaceName(own: string | null | undefined, chosen: SpaceAccount[]): string {
  const names = [...(own?.trim() ? [own.trim()] : []), ...chosen.map(accountName)];
  const name = names.length < 2 ? names[0] ?? '' : `${names.slice(0, -1).join(', ')} & ${names[names.length - 1]}`;
  return name.slice(0, 80);
}
