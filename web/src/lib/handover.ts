// Handover (D-132, D-162; docs/handoff/d132-clients.md): notes from one front to whoever fronts next.
// Delivery, who took it and the checklist ticks are core's `handoverView`; this builds its input from the
// projection and turns its answer into what the chat and Home draw. Pure: the front's history comes in.
import { core } from './core';
import { messages, type MessageRow } from './data';
import { sync, type Projection } from './sync/client';

type Rows = Record<string, { exists?: boolean; fields: Record<string, unknown> } | undefined>;

export interface ViewItem { id: string; text: string; done: boolean; by?: string | null }
export interface HandoverOut {
  id: string; delivered_at: number | null; acked: boolean;
  acks: { member_id: string; acked_at: number }[]; checklist: ViewItem[];
}
export interface HandoverView { handovers: HandoverOut[]; unread: string[]; last_fronted: number | null }

/** A Body-front interval as either shape carries it: named fronts (`layer_id`) or the legacy fold (`level`). */
export interface FrontInterval { subject_id: string; layer_id?: string; level?: string; start_at: number; end_at: number | null }

/** The Body front's whole history, as the device folds it (named fronts, D-080, else the legacy fold). */
export function bodyIntervals(p: Projection, account: string): FrontInterval[] {
  return p.front_systems?.[account] ? sync.frontHistory(account)?.effective.intervals ?? [] : p.fronts[account]?.intervals ?? [];
}

/** The live handovers in a channel, oldest first. */
export function handoverMessages(p: Projection, channelId: string): MessageRow[] {
  return messages(p, channelId).filter((m) => !m.deleted && m.handover);
}

/** `deliver: next_switch` and not yet delivered. */
export const waiting = (view: HandoverView | null, id: string): boolean =>
  !!view && view.handovers.some((h) => h.id === id && h.delivered_at === null);

/** Core's view of the Handover channel for `member` (the speaker, for "since X last fronted"). */
export function handoverView(p: Projection, body: string, channelId: string, intervals: FrontInterval[], member: string | null): HandoverView {
  const list = handoverMessages(p, channelId);
  const ids = new Set(list.map((m) => m.id));
  const acks = Object.values((p.rows.handover_ack ?? {}) as Rows).filter((r) => r?.exists && ids.has(String(r.fields.message_id)))
    .map((r) => ({ message_id: r!.fields.message_id, member_id: r!.fields.member_id, acked_at: r!.fields.acked_at }));
  const checks = Object.values((p.rows.checklist_item_state ?? {}) as Rows).filter((r) => r?.exists && ids.has(String(r.fields.message_id)))
    .map((r) => ({ message_id: r!.fields.message_id, item_id: r!.fields.item_id, done: r!.fields.done === true, member_id: r!.fields.member_id }));
  return core.handoverView({
    body,
    intervals: intervals.map((i) => ({ subject_id: i.subject_id, layer: i.layer_id ?? i.level ?? '', start_at: i.start_at, end_at: i.end_at })),
    handovers: list.map((m) => ({ id: m.id, sent_at: m.occurred_at, deliver: m.handover!.deliver, authors: m.authors, checklist: m.handover!.checklist ?? [] })),
    acks, checks, member,
  }) as HandoverView;
}

/** "Foxglove acknowledged the handover" lines, at the time of each ack by someone who didn't write it. */
export function ackLines(view: HandoverView, list: MessageRow[], nameOf: (id: string) => string): { at: number; text: string }[] {
  const authors = new Map(list.map((m) => [m.id, m.authors]));
  return view.handovers.flatMap((h) => h.acks.filter((a) => !authors.get(h.id)?.includes(a.member_id))
    .map((a) => ({ at: a.acked_at, text: `${nameOf(a.member_id)} acknowledged the handover` })));
}

/** The first line of a handover, or how long its checklist is. */
export function firstLine(m: MessageRow): string {
  const line = m.text.split('\n').map((l) => l.trim()).find(Boolean);
  const n = m.handover?.checklist?.length ?? 0;
  return line ?? (n ? `Checklist · ${n} ${n === 1 ? 'item' : 'items'}` : '');
}

export interface HomeCard { id: string; channelId: string; title: string; line: string; more: number }

/** The Home card (F0.1): the newest unread handover and how many others wait. */
export function homeCard(view: HandoverView | null, list: MessageRow[], nameOf: (id: string) => string): HomeCard | null {
  const id = view?.unread.at(-1);
  const m = id ? list.find((x) => x.id === id) : undefined;
  if (!view || !m) return null;
  const who = m.authors.map(nameOf).filter(Boolean).join(' & ');
  return { id: m.id, channelId: m.channel_id, title: who ? `Handover from ${who}` : 'Handover', line: firstLine(m), more: view.unread.length - 1 };
}

/** The ops a send, an ack and a tick write. */
export const ackPayload = (id: string, member: string) => ({ message_id: id, member_id: member });
export const tickPayload = (id: string, item: string, done: boolean, member: string) => ({ message_id: id, item_id: item, done, member_id: member });

/** `handover` for a `message.send` from the Handover composer: every send is one, so Home lists it. */
export function sendDef(nextSwitch: boolean, items: string[]): { deliver: 'now' | 'next_switch'; checklist?: { id: string; text: string }[] } {
  const list = items.map((t) => t.trim()).filter(Boolean).slice(0, 30).map((text, i) => ({ id: `c${i + 1}`, text: text.slice(0, 200) }));
  return { deliver: nextSwitch ? 'next_switch' : 'now', ...(list.length ? { checklist: list } : {}) };
}
