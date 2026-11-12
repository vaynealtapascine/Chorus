// One action as several of our members at once (D-142; Android's ManyMembers): hold a heart, a
// reaction or a poll option and choose who does it. Each member's part is still its own op, as if
// each had tapped it, so nothing new is stored or revealed.
import { bodyPresence } from './front-view';
import { members, type MemberRow } from './data';
import { canWriteAs } from './posts';
import type { Projection } from './sync/client';
import type { Poll } from './polls';

export interface MemberChange { add: string[]; remove: string[] }

/** Who to add and who to take back, in `order`, when `had` have it and `chosen` should. */
export function memberChange(order: string[], had: Set<string>, chosen: Set<string>): MemberChange {
  return { add: order.filter((id) => chosen.has(id) && !had.has(id)), remove: order.filter((id) => had.has(id) && !chosen.has(id)) };
}

/** The votes to write when `chosen` (of `order`) should pick `option`: each member whose choice
 * changes, with their whole next choice. Single choice moves a vote there; multiple choice adds it
 * beside the others; leaving someone out takes only `option` back. */
export function votesFor(poll: Poll, choicesOf: (member: string) => string[], order: string[], option: string,
  chosen: Set<string>): [string, string[]][] {
  const out: [string, string[]][] = [];
  for (const id of order) {
    const now = choicesOf(id);
    if (chosen.has(id) && !now.includes(option)) out.push([id, poll.multiple ? [...now, option] : [option]]);
    else if (!chosen.has(id) && now.includes(option)) out.push([id, now.filter((c) => c !== option)]);
  }
  return out;
}

/** Our members who may act (not archived, deleted or another account's). */
export function actors(p: Projection, account: string): MemberRow[] {
  return members(p).filter((m) => canWriteAs(p, m.id, account));
}

/** Who fronts now, for the sheet's Fronting pick. */
export function frontingIds(p: Projection, account: string): Set<string> {
  return new Set(bodyPresence(p, account).filter((e) => e.subject_type === 'member').map((e) => e.subject_id));
}

/** A member as the round face draws them. */
export const faceOf = (m: MemberRow) => ({ type: 'member' as const, id: m.id, name: m.display_name ?? m.name, color: m.color,
  glyph: m.sigils[0] ?? m.name[0] ?? '?', blob: m.avatar_blob ?? undefined });
export const isDark = () => typeof document === 'undefined' || document.documentElement.dataset.theme !== 'light';
