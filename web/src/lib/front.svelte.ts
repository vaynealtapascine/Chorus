// Front helpers shared by Home, Switcher and History (SPEC §4.3).
import { sync } from './sync/client';
import type { FrontPresence } from './core/fronts';
import { withPrimary } from './fronting-display';

export type Level = 'front' | 'cocon' | 'present';
export type SubjectType = 'member' | 'group' | 'state';

export interface Entry {
  subject_type: SubjectType;
  subject_id: string;
  level: Level;
  is_primary: boolean;
}

export interface SwitchRow {
  id: string;
  kind: 'switch' | 'add' | 'remove' | 'update';
  occurred_at: number;
  device_id: string;
  entries: Entry[];
  resulting_front: Entry[];
  note: string | null;
  retracted: boolean;
  amended: boolean;
}

export const LEVELS: Level[] = ['front', 'cocon', 'present'];
export const LEVEL_LABEL: Record<Level, string> = { front: 'fronting', cocon: 'co-con', present: 'present' };

export type Notify = 'default' | 'silent' | 'now' | 'extra_delay';

/** The one pending undo (SPEC §4.3: 10 s). `front` set = a named-front change (D-080). */
class UndoState {
  pending: { opId: string; label: string; until: number; front?: string; more?: string[] } | null = $state(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  /** `more`: further ops of the same action (an Add-mode switch places several people). */
  offer(opId: string, label: string, front?: string, more: string[] = []) {
    this.pending = { opId, label, until: Date.now() + 10_000, front, more };
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => (this.pending = null), 10_000);
  }

  undo() {
    const p = this.pending;
    if (!p || Date.now() > p.until + 20_000) return;
    for (const id of [...(p.more ?? []), p.opId]) {
      if (p.front) sync.create('front.change', sync.accountScope, p.front, { action: 'retract', target_op_id: id });
      else sync.create('front.retract', sync.accountScope, sync.newId(), { target_op_id: id });
    }
    this.pending = null;
  }

  dismiss() {
    this.pending = null;
  }
}

export const undo = new UndoState();

/** Record a full switch and offer undo. `at` = a time the user typed, else now. */
export function doSwitch(entries: Entry[], label: string, opts: { note?: string; at?: number; notify?: Notify } = {}) {
  const payload: Record<string, unknown> = { entries };
  if (opts.note) payload.note = opts.note;
  if (opts.notify && opts.notify !== 'default') payload.notify = opts.notify;
  // a typed time travels as the op's user time (time_source = user, not clock-corrected)
  const id = sync.create('front.switch', sync.accountScope, sync.newId(), payload, { userTime: opts.at });
  undo.offer(id, label);
  return id;
}

/** A named-front switch (D-080): independent layer IDs; a typed time goes in `effective_at` while
 * the envelope keeps clock time, so the record time stays correctable (FRONTS.md). */
export function doFrontSwitch(
  front: string, entries: FrontPresence[], label: string,
  opts: { note?: string; at?: number; notify?: Notify } = {},
) {
  const payload: Record<string, unknown> = { action: 'switch', entries };
  if (opts.note) payload.note = opts.note;
  if (opts.notify && opts.notify !== 'default') payload.notify = opts.notify;
  if (opts.at !== undefined) payload.effective_at = opts.at;
  const id = sync.create('front.change', sync.accountScope, front, payload);
  undo.offer(id, label, front);
  return id;
}

/** Sparse front definition edit; missing fields stay as they are. */
export function defineFront(front: string, patch: Record<string, unknown>) {
  return sync.create('front.define', sync.accountScope, front, patch);
}

/** Layer edit; layers are system-wide, keyed by `layer_id` (entity is the account). */
export function editLayer(layer: string, patch: { name?: string; level?: number; deleted?: boolean }) {
  return sync.create('front.layer', sync.accountScope, sync.accountId, { layer_id: layer, ...patch });
}

export function resumeFollowing(front: string) {
  return sync.create('front.change', sync.accountScope, front, { action: 'resume' });
}

/** One subject's new layer in a front (null: out of it), and whether they are in it now. */
export interface Placement { type: SubjectType; id: string; layer: string | null; present: boolean; primary?: boolean }

/** Write placements (F3.2 Add mode, F3.3) as one undoable action: add, update (a move) or remove.
 * `front` is a configured account's named front; unconfigured accounts use the legacy Body ops. */
export function place(moves: Placement[], label: string, front: string | null, current: FrontPresence[], inner: string | undefined, at?: number) {
  const ids: string[] = [];
  try {
    for (const m of withPrimary(moves, current, inner)) ids.push(write(m, front, at));
  } finally {
    // whatever was written stays undoable, even if a later write failed
    if (ids.length) undo.offer(ids[ids.length - 1], label, front ?? undefined, ids.slice(0, -1));
  }
}

function write(m: Placement, front: string | null, at?: number): string {
  if (front) {
    const change: Record<string, unknown> = m.layer === null ? { action: 'remove', subject_type: m.type, subject_id: m.id }
      : m.present ? { action: 'update', subject_type: m.type, subject_id: m.id, layer_id: m.layer, ...(m.primary !== undefined ? { is_primary: m.primary } : {}) }
      : { action: 'add', entry: { subject_type: m.type, subject_id: m.id, layer_id: m.layer, is_primary: m.primary ?? false } };
    if (at !== undefined) change.effective_at = at;
    return sync.create('front.change', sync.accountScope, front, change);
  }
  const [kind, payload] = m.layer === null ? ['front.remove', { subject_type: m.type, subject_id: m.id }]
    : m.present ? ['front.update', { subject_type: m.type, subject_id: m.id, level: m.layer, ...(m.primary !== undefined ? { is_primary: m.primary } : {}) }]
    : ['front.add', { entry: { subject_type: m.type, subject_id: m.id, level: m.layer, is_primary: m.primary ?? false } }];
  return sync.create(kind, sync.accountScope, sync.newId(), payload, { userTime: at });
}
