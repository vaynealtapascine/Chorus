// The web sync client: owns the core replica, talks to /api/v1/sync, persists write-behind.
// All protocol logic is in chorus-core (WebReplica); this file only moves bytes and timers.
import { WebReplica, newId } from '../core/pkg/chorus_wasm.js';
import type { SwitchRow } from '../front.svelte';
import { load, save, type Changes, type DeviceRecord } from './persist';
import { renew } from './device';

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

function nodeOf(shortId: string): number {
  return parseInt(shortId, 16) >>> 0;
}

function randomBytes(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(10));
}

export class SyncClient {
  device: DeviceRecord | null = null;
  status: Status = 'offline';
  private replica: WebReplica | null = null;
  private ws: WebSocket | null = null;
  private backoff = 1000;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private listeners = new Set<Listener>();
  private cached: Projection | null = null;
  private saving: Promise<void> = Promise.resolve();

  async start(): Promise<void> {
    const p = await load();
    this.device = p.device;
    if (!this.device) {
      this.status = 'no-device';
      this.emit();
      return;
    }
    this.replica = WebReplica.restore(
      this.device.device_id,
      nodeOf(this.device.short_id),
      p.meta ? JSON.stringify(p.meta) : '',
      JSON.stringify(p.ops),
      p.hlc,
    );
    this.emit();
    addEventListener('online', () => this.reconnectNow());
    this.connect();
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

  projection(): Projection | null {
    if (!this.replica) return null;
    this.cached ??= JSON.parse(this.replica.projection()) as Projection;
    return this.cached;
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

  newId(): string {
    return newId(Date.now(), randomBytes());
  }

  private emit(): void {
    for (const l of this.listeners) l();
  }

  private changed(): void {
    this.cached = null;
    const ch = JSON.parse(this.replica!.takeChanges()) as Changes;
    this.saving = this.saving.then(() => save(ch)).catch((e) => console.error('persist failed', e));
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
    if (!this.replica || !this.device) return;
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
      }
      const out = JSON.parse(this.replica!.onFrame(ev.data as string, Date.now())) as unknown[];
      this.sendAll(out);
      this.changed();
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
