// Front helpers shared by Home, Switcher and History (SPEC §4.3).
import { sync } from './sync/client';

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

/** The one pending undo (SPEC §4.3: 10 s). */
class UndoState {
  pending: { opId: string; label: string; until: number } | null = $state(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  offer(opId: string, label: string) {
    this.pending = { opId, label, until: Date.now() + 10_000 };
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => (this.pending = null), 10_000);
  }

  undo() {
    const p = this.pending;
    if (!p || Date.now() > p.until + 20_000) return;
    sync.create('front.retract', sync.accountScope, sync.newId(), { target_op_id: p.opId });
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
