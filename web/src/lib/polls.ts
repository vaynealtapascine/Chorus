// Polls (D-081, D-086, docs/POLLS.md). The definition rides on its parent message/post; a vote is
// an account-scoped `poll.vote` (only this account's devices see it); totals arrive as a
// counts-only tally the server publishes. Nothing here can name another account's voters.
import { sync, type Projection } from './sync/client';

export interface PollOption { id: string; text: string }
export interface Poll { question?: string; options: PollOption[]; multiple: boolean }
export interface PollTally { counts: Record<string, number>; voters: number }

export const MAX_OPTIONS = 20;

function parse(v: unknown): Poll | null {
  if (!v || typeof v !== 'object') return null;
  const o = v as Record<string, unknown>;
  if (!Array.isArray(o.options)) return null;
  const options = o.options.filter((x): x is PollOption =>
    !!x && typeof (x as PollOption).id === 'string' && typeof (x as PollOption).text === 'string');
  if (options.length < 2) return null;
  return { question: typeof o.question === 'string' ? o.question : undefined, options, multiple: o.multiple === true };
}

/** The poll on a message or post, if it has one. */
export function pollOf(p: Projection, parentId: string): Poll | null {
  return parse((p.rows.message?.[parentId] ?? p.rows.post?.[parentId])?.fields.poll);
}

/** The latest published totals, or null before the server has published any. */
export function tallyOf(p: Projection, parentId: string): PollTally | null {
  const f = p.rows.poll_tally?.[parentId]?.fields;
  if (!f || typeof f.counts !== 'object' || f.counts === null) return null;
  return { counts: f.counts as Record<string, number>, voters: typeof f.voters === 'number' ? f.voters : 0 };
}

/** This account's member's current choices (own votes only ever reach own devices). */
export function ownChoices(p: Projection, parentId: string, member: string | null | undefined): string[] {
  if (!member) return [];
  const c = p.rows.poll_vote?.[`${parentId}|${member}`]?.fields.choices;
  return Array.isArray(c) ? c.filter((x): x is string => typeof x === 'string') : [];
}

/** Next choices after tapping `option`: single choice replaces (or clears), multiple toggles. */
export function toggled(poll: Poll, current: string[], option: string): string[] {
  if (current.includes(option)) return current.filter((c) => c !== option);
  return poll.multiple ? [...current, option] : [option];
}

export function vote(parentId: string, member: string, choices: string[]) {
  sync.create('poll.vote', sync.accountScope, parentId, { member_id: member, choices });
}

/** The `poll` payload for a composer draft, or an error to show. Option IDs are a, b, c… */
export function pollPayload(texts: string[], multiple: boolean): { poll?: Poll; error?: string } {
  const options = texts.map((t) => t.trim()).filter(Boolean);
  if (options.length < 2) return { error: 'A poll needs at least two options.' };
  if (options.length > MAX_OPTIONS) return { error: `A poll can have at most ${MAX_OPTIONS} options.` };
  if (options.some((t) => [...t].length > 200)) return { error: 'Each option can be at most 200 characters.' };
  return { poll: { options: options.map((text, i) => ({ id: String.fromCharCode(97 + i), text })), multiple } };
}
