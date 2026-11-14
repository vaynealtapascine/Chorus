import { readFileSync } from 'node:fs';
import { beforeAll, describe, expect, it } from 'vitest';
import { loadCore } from './core';
import { ackLines, ackPayload, firstLine, handoverMessages, handoverView, homeCard, sendDef, tickPayload, waiting, type FrontInterval } from './handover';
import { messages } from './data';
import { build, ID, ME, send, systemSpecs, T0, u } from './testing/d132-ops';

beforeAll(async () => loadCore(readFileSync(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));

const home = `space:${ID.home}`;
const H1 = u(0x501), H2 = u(0x502), PLAIN = u(0x503);
const name = (id: string) => ({ [ID.fox]: 'Foxglove', [ID.amy]: 'Amarynthe' } as Record<string, string>)[id] ?? '';
// Amarynthe fronts from the start; Foxglove takes over at T0 + 60 s
const iv = (subject: string, start: number, end: number | null): FrontInterval => ({ subject_id: subject, layer_id: 'front', start_at: start, end_at: end });
const before = [iv(ID.amy, T0 - 1000, null)];
const after = [iv(ID.amy, T0 - 1000, T0 + 60_000), iv(ID.fox, T0 + 60_000, null)];

const base = () => [
  ...systemSpecs(),
  send(ID.handover, PLAIN, 'Evening: dishes done', [ID.amy], {}, T0 - 5000),
  send(ID.handover, H1, 'Handover for whoever is next', [ID.amy], { handover: { deliver: 'next_switch', checklist: [{ id: 'a', text: 'Keys' }, { id: 'b', text: 'Lunch' }] } }, T0 + 1000),
  send(ID.handover, H2, '', [ID.amy], { handover: { deliver: 'now', checklist: [{ id: 'a', text: 'Pills' }] } }, T0 + 2000),
];

describe('handover from ops through the real projection and core', () => {
  it('reads handovers off the projected messages and leaves plain ones alone', () => {
    const p = build(base());
    expect(p.opaque).toBe(0);
    expect(handoverMessages(p, ID.handover).map((m) => m.id)).toEqual([H1, H2]);
    expect(messages(p, ID.handover).find((m) => m.id === PLAIN)?.handover).toBeUndefined();
    expect(handoverMessages(p, ID.handover)[0].handover).toEqual({ deliver: 'next_switch', checklist: [{ id: 'a', text: 'Keys' }, { id: 'b', text: 'Lunch' }] });
  });

  it('waits for the next switch, then delivers it, in order', () => {
    const p = build(base());
    const wait = handoverView(p, ME, ID.handover, before, ID.fox);
    expect(waiting(wait, H1)).toBe(true);
    expect(waiting(wait, H2)).toBe(false);
    expect(wait.unread).toEqual([H2]);
    const done = handoverView(p, ME, ID.handover, after, ID.fox);
    expect(waiting(done, H1)).toBe(false);
    expect(done.unread).toHaveLength(2);
  });

  it('closes a handover with one ack by someone who did not write it, and says so once', () => {
    const p = build([...base(),
      { kind: 'handover.ack', scope: home, entity: H1, at: T0 + 90_000, payload: ackPayload(H1, ID.amy) },
      { kind: 'handover.ack', scope: home, entity: H2, at: T0 + 95_000, payload: ackPayload(H2, ID.fox) }]);
    expect(p.opaque).toBe(0);
    const v = handoverView(p, ME, ID.handover, after, ID.fox);
    // the author's own ack doesn't close H1; Foxglove's closes H2
    expect(v.unread).toEqual([H1]);
    const lines = ackLines(v, handoverMessages(p, ID.handover), name);
    expect(lines).toEqual([{ at: T0 + 95_000, text: 'Foxglove acknowledged the handover' }]);
  });

  it('ticks checklist items as the speaker, last tick wins', () => {
    const p = build([...base(),
      { kind: 'checklist.set', scope: home, entity: H1, at: T0 + 70_000, payload: tickPayload(H1, 'a', true, ID.fox) },
      { kind: 'checklist.set', scope: home, entity: H1, at: T0 + 80_000, payload: tickPayload(H1, 'b', true, ID.fox) },
      { kind: 'checklist.set', scope: home, entity: H1, at: T0 + 90_000, payload: tickPayload(H1, 'b', false, ID.fox) }]);
    expect(p.opaque).toBe(0);
    const h = handoverView(p, ME, ID.handover, after, ID.fox).handovers.find((x) => x.id === H1)!;
    expect(h.checklist.map((i) => [i.id, i.done])).toEqual([['a', true], ['b', false]]);
    expect(h.checklist[0].by).toBe(ID.fox);
  });

  it('knows when a member last stopped fronting, for the divider', () => {
    const p = build(base());
    expect(handoverView(p, ME, ID.handover, after, ID.amy).last_fronted).toBe(T0 + 60_000);
    expect(handoverView(p, ME, ID.handover, after, ID.fox).last_fronted).toBeNull();
  });

  it('makes the Home card from the newest unread and counts the rest', () => {
    const p = build(base());
    const list = handoverMessages(p, ID.handover);
    const v = handoverView(p, ME, ID.handover, after, ID.fox);
    const card = homeCard(v, list, name)!;
    expect(card.id).toBe(v.unread.at(-1));
    expect(card.title).toBe('Handover from Amarynthe');
    expect(card.more).toBe(1);
    expect(homeCard({ handovers: [], unread: [], last_fronted: null }, list, name)).toBeNull();
    expect(firstLine(list[1])).toBe('Checklist · 1 item');
    expect(firstLine(list[0])).toBe('Handover for whoever is next');
  });

  it('writes what the composer says: every send a handover, items numbered', () => {
    expect(sendDef(false, [])).toEqual({ deliver: 'now' });
    expect(sendDef(true, [' Keys ', '', 'Lunch'])).toEqual({ deliver: 'next_switch', checklist: [{ id: 'c1', text: 'Keys' }, { id: 'c2', text: 'Lunch' }] });
    expect(sendDef(false, Array.from({ length: 40 }, (_, i) => `i${i}`)).checklist).toHaveLength(30);
  });
});
