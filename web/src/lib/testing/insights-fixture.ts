/** Account-local, deterministic fixture for component tests and static layout inspection. */
export const insightIds = {
  body: '0192f8c2-0000-7000-8000-0000000000a1',
  chorus: '0192f8c2-0000-7000-8000-0000000000a2',
  kai: '0192f8c2-0000-7000-8000-0000000000b1',
  moon: '0192f8c2-0000-7000-8000-0000000000b2',
};

export function insightOps(now: number) {
  const { body, chorus, kai, moon } = insightIds;
  const presence = (subject_id: string, layer_id: string) => ({ subject_type: 'member', subject_id, layer_id });
  const changes: [number, string, string, object][] = [
    [-10_000_000, 'member.create', kai, { name: 'Kai', color: '#7AA99D' }],
    [-9_000_000, 'member.create', moon, { name: 'Moon', color: '#ACA2D0' }],
    [-8_500_000, 'front.layer', body, { layer_id: 'quiet', name: 'Close by', level: -2 }],
    [-8_000_000, 'front.define', chorus, { name: 'Chorus' }],
    [-7_200_000, 'front.change', body, { action: 'switch', entries: [presence(moon, 'front')] }],
    [-3_600_000, 'front.change', chorus, { action: 'switch', entries: [presence(kai, 'quiet')] }],
    [-2_700_000, 'front.define', body, { follow_source: chorus }],
    [-1_800_000, 'front.change', chorus, { action: 'switch', entries: [presence(kai, 'quiet'), presence(moon, 'quiet')] }],
  ];
  return changes.map(([offset, kind, entity_id, payload], i) => ({
    id: `0192f8c2-0000-7000-8000-${(i + 1).toString(16).padStart(12, '0')}`,
    scope: `account:${body}`, kind, entity_id, payload, v: 1,
    device_at: now + offset, tz_offset_min: 480,
    hlc: `${(now + offset).toString(16).padStart(12, '0')}-0000-00000001`,
  }));
}
