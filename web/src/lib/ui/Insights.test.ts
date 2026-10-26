import { readFileSync } from 'node:fs';
import { render } from 'svelte/server';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { loadCore } from '../core';
import { WebReplica } from '../core/pkg/chorus_wasm.js';
import { sync, type Projection } from '../sync/client';
import { insightIds, insightOps } from '../testing/insights-fixture';
import Insights from './Insights.svelte';

beforeAll(async () => loadCore(readFileSync(new URL('../core/pkg/chorus_wasm_bg.wasm', import.meta.url))));
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); Object.assign(sync, { replica: null, cached: null, device: null }); });

it('renders selected-front history and exact-layer time without stale legacy data', () => {
  const now = Date.parse('2026-10-26T04:00:00Z');
  vi.useFakeTimers(); vi.setSystemTime(now);
  const replica = new WebReplica('fixture', 1);
  try {
    replica.absorb(JSON.stringify(insightOps(now)), '[]');
    const projection = JSON.parse(replica.projection()) as Projection;
    projection.fronts[insightIds.body] = { current: [], switches: [], intervals: [{
      subject_type: 'member', subject_id: insightIds.kai, level: 'front', is_primary: true,
      start_at: now - 36_000_000, end_at: null,
    }] }; // Stale legacy presence must never fill a named selection.
    Object.assign(sync, { replica, cached: projection, opening: false, device: { account_id: insightIds.body } });
    const view = (selectedFront: string, selectedLayer: string, savedView = false) => render(Insights, {
      props: { projection, selectedFront, selectedLayer, savedView },
    }).body;
    const source = view(insightIds.chorus, 'quiet');
    expect(source).toContain('Chorus · Close by time');
    expect(source).toContain('Day and week bars show member-hours; simultaneous members add together.');
    expect(source).toContain('Kai'); expect(source).toContain('Moon');
    expect(source).toContain('1.5 h'); // Kai one hour + Moon half an hour.
    expect(source).toContain('Changes by day');
    const body = view(insightIds.body, 'quiet');
    expect(body).toContain('Body · Close by time');
    expect(body).toContain('1.3 h'); // Only the inherited 45 + 30 minutes.
    const saved = view(insightIds.body, 'front', true);
    expect(saved).toContain('saved entries'); expect(saved).toContain('2.0 h');
    const empty = view(insightIds.chorus, 'front');
    expect(empty).toContain('No time in this range.');
    expect(empty).not.toContain('1.5 h');
    expect(empty).not.toContain('10.0 h');
    const error = vi.spyOn(sync, 'frontHistory').mockImplementation(() => { throw Error('read failed'); });
    expect(view(insightIds.chorus, 'quiet')).toContain('Could not load saved history.');
    error.mockReturnValue(null);
    expect(view(insightIds.chorus, 'quiet')).toContain('Preparing insights');
    error.mockClear();
    const legacy = render(Insights, { props: { projection: { ...projection, front_systems: undefined } } }).body;
    expect(legacy).toContain('Front time by day'); expect(legacy).toContain('10.0 h');
    expect(legacy).not.toContain('Insight time layer'); expect(error).not.toHaveBeenCalled();
  } finally { replica.free(); }
});
