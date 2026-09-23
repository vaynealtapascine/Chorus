import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { beforeAll, describe, expect, it } from 'vitest';
import { core, loadCore } from './core';
import { cofrontPairs, csv, frontWeeks, localDay, offsetSchedule, switchesPerDay, windowStart, type DailyTime } from './insights';
import type { Interval } from './sync/client';
import type { SwitchRow } from './front.svelte';

beforeAll(async () => {
  await loadCore(readFileSync(fileURLToPath(new URL('./core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
});

describe('insight calculations', () => {
  it('uses core to split front time across local midnight and offset transitions', () => {
    const start = Date.parse('2026-09-22T15:30:00Z');
    const end = start + 2 * 3_600_000;
    const rows = core.frontDaily([{
      subject_type: 'member', subject_id: 'kai', level: 'front', is_primary: true, start_at: start, end_at: end,
    }], end, [[start - 1, 480]]) as DailyTime[];
    expect(rows.map((r) => [r.day, r.seconds])).toEqual([['2026-09-22', 1800], ['2026-09-23', 5400]]);
    const change = start + 30 * 60_000;
    const dst = core.frontDaily([{
      subject_type: 'member', subject_id: 'kai', level: 'front', is_primary: true, start_at: start, end_at: end,
    }], end, [[start - 1, 0], [change, 60]]) as DailyTime[];
    expect(dst.reduce((sum, r) => sum + r.seconds, 0)).toBe(7200);
  });

  it('uses the system time zone for local days and finds a daylight-saving offset change', () => {
    expect(localDay(Date.parse('2026-09-22T16:30:00Z'), 'Asia/Manila')).toBe('2026-09-23');
    expect(new Date(windowStart(Date.parse('2026-09-23T08:00:00Z'), 1, 'Asia/Manila')).toISOString())
      .toBe('2026-09-22T16:00:00.000Z');
    const offsets = offsetSchedule(Date.parse('2026-03-08T05:00:00Z'), Date.parse('2026-03-08T10:00:00Z'), 'America/New_York');
    expect(offsets.map((row) => row[1])).toEqual([-300, -240]);
    expect(offsets[1][0]).toBe(Date.parse('2026-03-08T07:00:00Z'));
  });

  it('sums weekly totals, pair overlaps and non-retracted switches', () => {
    const rows: DailyTime[] = [
      { day: '2026-09-21', subject_type: 'member', subject_id: 'kai', level: 'front', seconds: 3600, as_primary_seconds: 3600 },
      { day: '2026-09-22', subject_type: 'member', subject_id: 'kai', level: 'front', seconds: 1800, as_primary_seconds: 0 },
    ];
    expect(frontWeeks(rows)).toMatchObject([{ day: '2026-09-21', seconds: 5400 }]);
    const intervals: Interval[] = [
      { subject_type: 'member', subject_id: 'kai', level: 'front', is_primary: true, start_at: 0, end_at: 10_000 },
      { subject_type: 'member', subject_id: 'rin', level: 'front', is_primary: false, start_at: 5_000, end_at: 15_000 },
    ];
    expect(cofrontPairs(intervals, 0, 20_000)).toEqual([{ a: 'kai', b: 'rin', seconds: 5 }]);
    const switches = [
      { occurred_at: 1000, retracted: false }, { occurred_at: 2000, retracted: true },
    ] as SwitchRow[];
    expect(switchesPerDay(switches, 0, 3000)).toEqual([{ day: localDay(1000), count: 1 }]);
    expect(csv(['name', 'seconds'], [['Kai, Rin', 5]])).toBe('"name","seconds"\r\n"Kai, Rin","5"\r\n');
  });
});
