import { core } from './core';
import type { Interval } from './sync/client';
import type { SwitchRow } from './front.svelte';

export interface DailyTime { day: string; subject_type: string; subject_id: string; level: string; seconds: number; as_primary_seconds: number }
export interface PairTime { a: string; b: string; seconds: number }
export interface SwitchDay { day: string; count: number }

const HOUR = 3_600_000;

export function localDay(t: number, timeZone?: string): string {
  const d = new Date(t);
  if (timeZone) {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
      .formatToParts(d);
    const value = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
    return `${value('year')}-${value('month')}-${value('day')}`;
  }
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function windowStart(now: number, days: number, timeZone?: string): number {
  if (timeZone) {
    const target = new Date(`${localDay(now, timeZone)}T00:00:00Z`);
    target.setUTCDate(target.getUTCDate() - days + 1);
    const day = target.toISOString().slice(0, 10);
    let low = target.getTime() - 86_400_000, high = target.getTime() + 86_400_000;
    while (high - low > 60_000) {
      const mid = Math.floor((low + high) / 120_000) * 60_000;
      if (localDay(mid, timeZone) < day) low = mid;
      else high = mid;
    }
    return Math.ceil(high / 60_000) * 60_000;
  }
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - days + 1);
  return d.getTime();
}

/** Browser timezone transition table supplied to pure core daily aggregation. */
export function offsetSchedule(from: number, to: number, timeZone?: string): [number, number][] {
  const start = from - HOUR;
  const formatter = timeZone ? new Intl.DateTimeFormat('en-US', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }) : null;
  const offset = (t: number) => {
    if (!formatter) return -new Date(t).getTimezoneOffset();
    const parts = formatter.formatToParts(new Date(t));
    const number = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0);
    const wall = Date.UTC(number('year'), number('month') - 1, number('day'), number('hour'), number('minute'), number('second'));
    return Math.round((wall - t) / 60_000);
  };
  let prior = offset(start);
  const out: [number, number][] = [[start, prior]];
  for (let t = start + HOUR; t <= to + HOUR; t += HOUR) {
    const next = offset(t);
    if (next === prior) continue;
    let low = t - HOUR, high = t;
    while (high - low > 60_000) {
      const mid = Math.floor((low + high) / 120_000) * 60_000;
      if (offset(mid) === prior) low = mid;
      else high = mid;
    }
    out.push([Math.ceil(high / 60_000) * 60_000, next]);
    prior = next;
  }
  return out;
}

/** Core owns day splitting. Clamp inputs so a dashboard never reprocesses all history. */
export function frontDaily(intervals: Interval[], from: number, now: number, timeZone?: string): DailyTime[] {
  const window = intervals.filter((i) => (i.end_at ?? now) > from && i.start_at < now)
    .map((i) => ({ ...i, start_at: Math.max(i.start_at, from), end_at: i.end_at === null ? now : Math.min(i.end_at, now) }));
  return core.frontDaily(window, now, offsetSchedule(from, now, timeZone)) as DailyTime[];
}

export function weekOf(day: string): string {
  const d = new Date(`${day}T12:00:00`);
  const weekday = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - weekday);
  return localDay(d.getTime());
}

export function frontWeeks(rows: DailyTime[]): DailyTime[] {
  const total = new Map<string, DailyTime>();
  for (const row of rows.filter((r) => r.subject_type === 'member' && r.level === 'front')) {
    const day = weekOf(row.day);
    const key = `${day}|${row.subject_id}`;
    const existing = total.get(key);
    if (existing) { existing.seconds += row.seconds; existing.as_primary_seconds += row.as_primary_seconds; }
    else total.set(key, { ...row, day });
  }
  return [...total.values()].sort((a, b) => a.day.localeCompare(b.day) || a.subject_id.localeCompare(b.subject_id));
}

/** Intersect front intervals; each pair's simultaneous time is counted once. */
export function cofrontPairs(intervals: Interval[], from: number, now: number): PairTime[] {
  const active = intervals.filter((i) => i.subject_type === 'member' && i.level === 'front' && (i.end_at ?? now) > from && i.start_at < now);
  const totals = new Map<string, number>();
  for (let x = 0; x < active.length; x++) for (let y = x + 1; y < active.length; y++) {
    const a = active[x], b = active[y];
    if (a.subject_id === b.subject_id) continue;
    const overlap = Math.min(a.end_at ?? now, b.end_at ?? now, now) - Math.max(a.start_at, b.start_at, from);
    if (overlap <= 0) continue;
    const key = [a.subject_id, b.subject_id].sort().join('|');
    totals.set(key, (totals.get(key) ?? 0) + overlap);
  }
  return [...totals].map(([key, ms]) => ({ a: key.split('|')[0], b: key.split('|')[1], seconds: Math.floor(ms / 1000) }))
    .sort((a, b) => b.seconds - a.seconds || a.a.localeCompare(b.a));
}

export function switchesPerDay(switches: SwitchRow[], from: number, now: number, timeZone?: string): SwitchDay[] {
  const count = new Map<string, number>();
  for (const row of switches) {
    if (row.retracted || row.occurred_at < from || row.occurred_at > now) continue;
    const day = localDay(row.occurred_at, timeZone);
    count.set(day, (count.get(day) ?? 0) + 1);
  }
  return [...count].map(([day, value]) => ({ day, count: value })).sort((a, b) => a.day.localeCompare(b.day));
}

export function csv(headers: string[], rows: (string | number)[][]): string {
  const escape = (value: string | number) => `"${String(value).replaceAll('"', '""')}"`;
  return `${[headers, ...rows].map((row) => row.map(escape).join(',')).join('\r\n')}\r\n`;
}
