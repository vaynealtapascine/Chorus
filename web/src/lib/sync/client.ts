// The web sync client: owns the core replica, talks to /api/v1/sync, persists write-behind.
// All protocol logic is in chorus-core (WebReplica); this file only moves bytes and timers.
import { WebReplica, newId } from '../core/pkg/chorus_wasm.js';
import type { SwitchRow } from '../front.svelte';
import { loadHead, loadOps, save, saveSnapshot, type Changes, type DeviceRecord, type Snapshot } from './persist';
import { renew } from './device';
import { applyDelta, type Delta } from './delta';
import { flushUploads, stageBlob } from './uploads';
import { keepStorage, loadBlob } from './blobs';
import { fillFiles, keepEverything } from './keep';
import type { Entity } from '../core';

/** An op the server refused (SYNC §7 "Sync issues"). */
export interface SyncIssue {
  id: string;
  kind: string;
  scope: string;
  entity_id: string | null;
  payload: Record<string, unknown>;
  at: number;
  code: string;
  message: string;
}

/** One version of a message's or post's text (SPEC §5.3 edit history). */
export interface Revision {
  rev: number;
  original: boolean;
  fields: { text?: string; entities?: Entity[]; cw?: string | null; title?: string | null };
  at: number;
}

export type Status = 'offline' | 'connecting' | 'live' | 'no-device';

export interface Projection {
  rows: Record<string, Record<string, { exists: boolean; fields: Record<string, unknown>; edits?: number }>>;
  sets: Record<string, Record<string, boolean>>;
  fronts: Record<string, { current: FrontEntry[]; switches: SwitchRow[]; intervals: Interval[] }>;
  reviews?: Record<string, { id: string; switch_a: string; switch_b: string }[]>;
  opaque: number;
}
export interface FrontEntry {
  subject_type: 'member' | 'group' | 'state';
  subject_id: string;
  level: 'front' | 'cocon' | 'present';
  is_primary: boolean;
}
export interface Interval {
  subject_type: string;
  subject_id: string;
  level: string;
  is_primary: boolean;
  start_at: number;
  end_at: number | null;
}

type Listener = () => void;
type ProjectionListener = (projection: Projection, delta: Delta) => void;

function nodeOf(shortId: string): number {
  return parseInt(shortId, 16) >>> 0;
}

function randomBytes(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(10));
}

/** Let the page paint and handle input between slices of opening work. */
function yieldToUi(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

// opening in slices small enough to keep the page responsive (≈ tens of ms each)
const OPS_PER_READ = 5000;
const OPS_PER_INDEX = 2000;
/** a snapshot is rewritten this long after the last change (and whenever the page is hidden) */
const SNAPSHOT_AFTER_MS = 10_000;

export class SyncClient {
  device: DeviceRecord | null = null;
  status: Status = 'offline';
  private replica: WebReplica | null = null;
  private ws: WebSocket | null = null;
  private backoff = 1000;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private listeners = new Set<Listener>();
  private projectionListeners = new Set<ProjectionListener>();
  private cached: Projection | null = null;
  private saving: Promise<void> = Promise.resolve();
  /** ops are still being read and indexed (opening from a snapshot); no sync until done */
  private opening = false;
  private snapshotTimer: ReturnType<typeof setTimeout> | null = null;
  private snapshotDirty = false;
  /** told about each `caught` frame while "Sync everything now" runs */
  private caughtWatch: ((scope: string) => void) | null = null;
  private keptFiles = false;

  async start(): Promise<void> {
    // open-time marks (CLIENTS.md §4.3: a 100k-op device opens in ≤ 2 s); web/perf measures them
    performance.mark('chorus:start');
    const p = await loadHead();
    performance.mark('chorus:loaded');
    this.device = p.device;
    if (!this.device) {
      this.status = 'no-device';
      this.emit();
      return;
    }
    // the replica and outbox are the only copy of offline work: ask not to be evicted
    void keepStorage();
    // Older saved devices predate the admin capability in the enrolment response.
    if (this.device.is_admin === undefined && navigator.onLine) {
      try { this.device = await renew(this.device); } catch { /* reconnect will retry auth later */ }
    }
    // Open from the last projection shown, at once; read and index the ops behind it in slices
    // (CLIENTS.md §4.3). Without a snapshot (first start) the app waits for the ops, as before.
    this.replica = WebReplica.begin(this.device.device_id, nodeOf(this.device.short_id), p.meta ? JSON.stringify(p.meta) : '', p.hlc);
    this.opening = true;
    if (p.snapshot) {
      this.cached = JSON.parse(p.snapshot.projection) as Projection;
      performance.mark('chorus:projected');
    }
    addEventListener('online', () => this.reconnectNow());
    // coming back to the tab: don't wait out a long backoff (e.g. after a server restart); leaving
    // it: keep the snapshot current, since the page may not come back
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) this.reconnectNow();
      else if (this.snapshotDirty) this.writeSnapshot();
    });
    const opened = this.openOps(p.snapshot).catch((e) => console.error('opening the replica failed', e));
    if (!p.snapshot) await opened;
    this.emit();
  }

  /** Read every persisted op into the core and index it, a slice at a time; then sync. */
  private async openOps(snapshot: Snapshot | null): Promise<void> {
    const replica = this.replica!;
    let after: string | undefined;
    for (;;) {
      const ops = await loadOps(after, OPS_PER_READ);
      if (!ops.length) break;
      replica.addOps(JSON.stringify(ops));
      after = ops[ops.length - 1].id;
      if (ops.length < OPS_PER_READ) break;
      await yieldToUi();
    }
    performance.mark('chorus:ops-read');
    while (!replica.indexStep(OPS_PER_INDEX)) await yieldToUi();
    const adopted = replica.adopt(snapshot && this.cached ? snapshot.digest : '');
    this.opening = false;
    if (adopted) this.refresh(); // ops created while opening
    else {
      this.cached = null; // the snapshot was stale: projection() computes it whole
      this.snapshotDirty = true;
      this.scheduleSnapshot();
    }
    performance.mark('chorus:restored');
    this.emit();
    this.connect();
  }

  /** With "keep everything" on, fetch files into the offline cache once a session (keep.ts). */
  /** The server was restored from a backup (SYNC.md §7.3): its files are as old as the backup.
   * Upload again the files named by ops it doesn't have yet that this browser has a copy of. */
  private async reuploadAfterRestore(): Promise<void> {
    const dev = this.device;
    if (!this.replica || !dev) return;
    for (const hash of JSON.parse(this.replica.restoringBlobs()) as string[]) {
      const blob = await loadBlob(hash, null); // this browser's copy only
      if (blob) await stageBlob(blob, blob.type, dev.account_id);
    }
    await flushUploads(dev);
  }

  private async keepFiles(): Promise<void> {
    if (this.keptFiles || !(await keepEverything())) return;
    this.keptFiles = true;
    // after the catch-up has had a moment, so the files of what it brought are included
    await new Promise((resolve) => setTimeout(resolve, 5_000));
    const p = this.projection();
    if (p) await fillFiles(p, this.device, () => {}).catch((e) => console.warn('keeping files failed', e));
  }

  private scheduleSnapshot(): void {
    if (this.snapshotTimer) clearTimeout(this.snapshotTimer);
    this.snapshotTimer = setTimeout(() => this.writeSnapshot(), SNAPSHOT_AFTER_MS);
  }

  /** Save what the UI shows, with the digest of the ops it came from (after those ops are saved). */
  private writeSnapshot(): void {
    if (!this.replica || this.opening || !this.projection()) return;
    this.refresh();
    const snapshot: Snapshot = { projection: JSON.stringify(this.cached), digest: this.replica.projectionDigest(), at: Date.now() };
    this.snapshotDirty = false;
    this.saving = this.saving.then(() => saveSnapshot(snapshot)).catch((e) => console.error('snapshot failed', e));
  }

  /** After enrolment. */
  async adopt(dev: DeviceRecord): Promise<void> {
    this.device = dev;
    await this.start();
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  /** Changed projection keys, delivered after the same delta updates the cached view. */
  subscribeProjection(fn: ProjectionListener): () => void {
    this.projectionListeners.add(fn);
    return () => this.projectionListeners.delete(fn);
  }

  /** Ops the server refused, with why, oldest first (SYNC §7 "Sync issues"). */
  syncIssues(): SyncIssue[] {
    return this.replica ? (JSON.parse(this.replica.syncIssues()) as SyncIssue[]) : [];
  }

  /** Let go of a refused op once seen (deleted from this device too). */
  dismissIssue(id: string): void {
    if (this.replica?.dismissIssue(id)) this.changed();
  }

  /** Every version of an edited message or post, oldest first, from this device's ops (core
   * `revisions`, the same rule as `GET /messages/{id}/revisions`). */
  revisions(id: string): Revision[] {
    return this.replica ? (JSON.parse(this.replica.revisions(id)) as Revision[]) : [];
  }

  projection(): Projection | null {
    if (!this.replica) return null;
    if (!this.cached) {
      if (this.opening) return null;
      this.cached = JSON.parse(this.replica.projection()) as Projection;
      this.replica.projectionDelta(); // the next delta starts from here
      performance.mark('chorus:projected');
    }
    return this.cached;
  }

  /** Bring the cached projection up to date with only what changed (SPEC §9). */
  private refresh(): void {
    if (!this.replica || !this.cached || this.opening) return;
    const d = JSON.parse(this.replica.projectionDelta()) as Delta;
    this.cached = d.full ? (JSON.parse(this.replica.projection()) as Projection) : applyDelta(this.cached, d);
    for (const listener of this.projectionListeners) listener(this.cached, d);
  }

  get accountId(): string {
    return this.device?.account_id ?? '';
  }

  get accountScope(): string {
    return `account:${this.accountId}`;
  }

  /** Create a local op; syncs when connected. Throws on validation errors. */
  create(
    kind: string,
    scope: string,
    entityId: string | null,
    payload: unknown,
    opts: { memberId?: string; userTime?: number } = {},
  ): string {
    if (!this.replica) throw new Error('not set up');
    const out = JSON.parse(
      this.replica.create(
        JSON.stringify({
          kind,
          scope,
          entity_id: entityId,
          payload,
          member_id: opts.memberId ?? null,
          user_time: opts.userTime ?? null,
        }),
        JSON.stringify({ now: Date.now(), tz_offset_min: -new Date().getTimezoneOffset() }),
        randomBytes(),
      ),
    ) as { op: { id: string }; frames: unknown[] };
    this.sendAll(out.frames);
    this.changed();
    return out.op.id;
  }

  /** Import a PluralKit export (safe to repeat: ids are derived from PluralKit's). */
  importPluralkit(exportJson: string): number {
    if (!this.replica) throw new Error('not set up');
    const out = JSON.parse(
      this.replica.importPluralkit(
        exportJson,
        this.accountScope,
        JSON.stringify({ now: Date.now(), tz_offset_min: -new Date().getTimezoneOffset() }),
      ),
    ) as { added: number; frames: unknown[] };
    this.sendAll(out.frames);
    this.changed();
    return out.added;
  }

  /**
   * "Sync everything now" (CLIENTS.md §4.3): ask the server for every scope again; each answer
   * ends with its digest, and a scope that doesn't match is repaired. Resolves once every scope
   * has answered and none is being repaired.
   */
  recheckAll(onProgress: (checked: number, total: number) => void): Promise<void> {
    if (!this.replica || this.status !== 'live') return Promise.reject(new Error("Not connected to the server right now."));
    const replica = this.replica;
    const frames = JSON.parse(replica.recheck()) as { scope: string }[];
    const waiting = new Set(frames.map((f) => f.scope));
    const total = waiting.size;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.caughtWatch = null;
        reject(new Error('The server stopped answering; try again.'));
      }, 120_000);
      const check = () => {
        const repairing = (JSON.parse(replica.repairing()) as string[]).length;
        onProgress(Math.max(0, total - waiting.size - repairing), total);
        if (!waiting.size && !repairing) {
          clearTimeout(timer);
          this.caughtWatch = null;
          resolve();
        }
      };
      this.caughtWatch = (scope) => { waiting.delete(scope); check(); };
      check();
      this.sendAll(frames);
    });
  }

  newId(): string {
    return newId(Date.now(), randomBytes());
  }

  private emit(): void {
    for (const l of this.listeners) l();
  }

  private changed(): void {
    this.refresh();
    const ch = JSON.parse(this.replica!.takeChanges()) as Changes;
    this.saving = this.saving.then(() => save(ch)).catch((e) => console.error('persist failed', e));
    if (!this.opening) {
      this.snapshotDirty = true;
      this.scheduleSnapshot();
    }
    this.emit();
  }

  private sendAll(frames: unknown[]): void {
    if (this.ws?.readyState !== WebSocket.OPEN) return;
    for (const f of frames) this.ws.send(JSON.stringify(f));
  }

  private url(): string {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${location.host}/api/v1/sync`;
  }

  private connect(): void {
    if (!this.replica || !this.device || this.opening) return;
    this.status = 'connecting';
    this.emit();
    const ws = new WebSocket(this.url());
    this.ws = ws;
    ws.onopen = () => {
      const clock = { wall: Date.now(), mono: null, boot_id: null };
      ws.send(this.replica!.connect(JSON.stringify(clock), this.device!.session));
    };
    ws.onmessage = async (ev) => {
      const frame = JSON.parse(ev.data as string);
      if (frame.t === 'error' && frame.code === 'unauthenticated') {
        ws.close();
        try {
          this.device = await renew(this.device!);
        } catch (e) {
          console.warn('session renewal failed', e);
        }
        return;
      }
      if (frame.t === 'welcome') {
        this.status = 'live';
        this.backoff = 1000;
        void flushUploads(this.device);
        void this.keepFiles();
      }
      const out = JSON.parse(this.replica!.onFrame(ev.data as string, Date.now())) as unknown[];
      this.sendAll(out);
      if (frame.t === 'welcome' && frame.reconcile) void this.reuploadAfterRestore();
      this.changed();
      if (frame.t === 'caught') this.caughtWatch?.(frame.scope);
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.replica?.disconnect();
      this.status = 'offline';
      this.emit();
      this.schedule();
    };
  }

  private schedule(): void {
    if (this.timer) clearTimeout(this.timer);
    const jitter = this.backoff * (0.7 + Math.random() * 0.6);
    this.timer = setTimeout(() => this.connect(), jitter);
    this.backoff = Math.min(this.backoff * 2, 5 * 60_000);
  }

  private reconnectNow(): void {
    if (this.status === 'live' || this.status === 'connecting') return;
    this.backoff = 1000;
    if (this.timer) clearTimeout(this.timer);
    this.connect();
  }
}

export const sync = new SyncClient();
