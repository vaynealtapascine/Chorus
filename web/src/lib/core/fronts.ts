/** Own-account shared-core data (D-080). These shapes are never a follower view. */
export interface FrontPresence {
  subject_type: 'member' | 'group' | 'state';
  subject_id: string;
  layer_id: string;
  is_primary: boolean;
}

export interface FrontLayer {
  id: string;
  name: string;
  /** Display level/order only; never an authorship or notification role. */
  level: number;
  deleted: boolean;
}

export interface NamedFront {
  id: string;
  name: string;
  own: FrontPresence[];
  follow: { source: string; clock: string } | null;
  overridden: boolean;
  /** null preserves legacy candidates/fallback; [] selects nobody. */
  authorship_layers: string[] | null;
  /** null preserves legacy input to ceilings; new fronts start with []. */
  notification_layers: string[] | null;
  deleted: boolean;
}

export interface FrontSystem {
  layers: Record<string, FrontLayer>;
  fronts: Record<string, NamedFront>;
}

export interface ResolvedFront {
  id: string;
  name: string;
  deleted: boolean;
  own: FrontPresence[];
  effective: FrontPresence[];
  source: string | null;
  state: 'independent' | 'following' | 'overridden' | 'unavailable';
  issue: 'cycle' | 'source_unavailable' | null;
  authorship: FrontPresence[];
  notifications: FrontPresence[];
}

type Notify = 'default' | 'silent' | 'now' | 'extra_delay';
type Subject = Pick<FrontPresence, 'subject_type' | 'subject_id'>;
export type FrontPresenceChange =
  | { action: 'switch'; entries: FrontPresence[]; based_on?: string | null; note?: string | null; notify?: Notify }
  | { action: 'add'; entry: FrontPresence; position?: number | null; based_on?: string | null; notify?: Notify }
  | ({ action: 'remove'; based_on?: string | null; notify?: Notify } & Subject)
  | ({ action: 'update'; layer_id?: string | null; is_primary?: boolean | null; position?: number | null; based_on?: string | null } & Subject);
export type FrontChange = FrontPresenceChange
  | { action: 'resume' }
  | { action: 'retract' | 'unretract'; target_op_id: string }
  | { action: 'amend'; target_op_id: string; occurred_at?: number | null; entries?: FrontPresence[] | null; note?: string | null };

export interface FrontHistoryRow {
  id: string;
  kind: FrontPresenceChange['action'];
  occurred_at: number;
  hlc: string;
  tz_offset_min: number;
  device_id: string;
  based_on: string | null;
  change: FrontPresenceChange;
  entries: FrontPresence[];
  resulting_front: FrontPresence[];
  note: string | null;
  notify: Notify;
  retracted: boolean;
  amended: boolean;
}

/** Own presence intervals; do not infer inherited history from current follow settings. */
export interface FrontHistoryInterval extends FrontPresence {
  id: string;
  front_id: string;
  position: number;
  start_at: number;
  end_at: number | null;
  start_switch_id: string;
  end_switch_id: string | null;
  start_tz_offset_min: number;
}

export interface FoldedFrontSystem {
  system: FrontSystem;
  resolved: Record<string, ResolvedFront>;
  histories: Record<string, {
    switches: FrontHistoryRow[];
    intervals: FrontHistoryInterval[];
    current: FrontPresence[];
  }>;
  reviews: Record<string, { id: string; switch_a: string; switch_b: string }[]>;
}
