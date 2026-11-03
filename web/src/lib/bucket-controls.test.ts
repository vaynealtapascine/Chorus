import { describe, expect, it } from 'vitest';
import schema from '../../../fixtures/ui_bucket_controls.json';
import { bucketAudience, bucketAudienceIcon, bucketColor, bucketIcon, CEILING_FIELDS, COLLAPSE_AFTER, DEFAULT_BUCKET_ICON, listedBuckets, validBucketAudience, validCeilingValue, withField, withPreset } from './bucket-controls';

const live = [{ id: 'close', name: 'Close', icon: 'heart' }, { id: 'work', name: 'Work', icon: 'bogus' }];

describe('privacy bucket controls (D-115)', () => {
  it('accept exactly what the shared fixture says', () => {
    for (const c of schema.cases) expect(validCeilingValue(c.key, c.value), `${c.key} ${JSON.stringify(c.value)}`).toBe(c.ok);
  });

  it('accept every field’s own default', () => {
    for (const f of CEILING_FIELDS) expect(validCeilingValue(f.key, f.default), f.key).toBe(true);
  });

  it('show a built-in icon or the bucket’s own emoji, and a colour only when it is one', () => {
    for (const c of schema.icon_cases) expect(bucketIcon(c.value), JSON.stringify(c.value)).toBe(c.shown);
    for (const c of schema.color_cases) expect(bucketColor(c.value), JSON.stringify(c.value)).toBe(c.shown);
  });

  it('list every bucket up to eight, then fold the rest except the chosen ones', () => {
    const many = Array.from({ length: 11 }, (_, i) => ({ id: `b${i}`, name: `B${i}` }));
    expect(COLLAPSE_AFTER).toBe(8);
    expect(listedBuckets(many.slice(0, 8), [], false)).toEqual({ shown: many.slice(0, 8), folded: 0 });
    const folded = listedBuckets(many, ['b10'], false);
    expect(folded.shown.map((b) => b.id)).toEqual(['b0', 'b1', 'b2', 'b3', 'b4', 'b5', 'b6', 'b7', 'b10']);
    expect(folded.folded).toBe(2);
    expect(listedBuckets(many, [], true).folded).toBe(0);
  });

  it('show a bucket’s icon, or the default for an unknown one', () => {
    expect(bucketIcon('heart')).toBe('heart');
    expect(bucketIcon('bogus')).toBe(DEFAULT_BUCKET_ICON);
    expect(bucketIcon(undefined)).toBe(DEFAULT_BUCKET_ICON);
  });

  it('read a bucket audience by its live buckets, and never save a deleted one', () => {
    expect(bucketAudience({ mode: 'buckets', bucket_ids: ['close'] }, live)).toBe('Close');
    expect(bucketAudienceIcon({ mode: 'buckets', bucket_ids: ['close'] }, live)).toBe('heart');
    expect(bucketAudience({ mode: 'buckets', bucket_ids: ['gone'] }, live)).toBe('Only us');
    expect(bucketAudienceIcon({ mode: 'buckets', bucket_ids: ['gone'] }, live)).toBe('lock');
    expect(bucketAudienceIcon({ mode: 'buckets', bucket_ids: ['close', 'work'] }, live)).toBe('users');
    expect(validBucketAudience({ mode: 'buckets', bucket_ids: ['close'] }, live)).toBe(true);
    expect(validBucketAudience({ mode: 'buckets', bucket_ids: ['close', 'gone'] }, live)).toBe(false);
    expect(validBucketAudience({ mode: 'buckets', bucket_ids: [] }, live)).toBe(false);
    expect(validBucketAudience({ mode: 'followers' }, live)).toBe(true);
  });

  it('keep sharing, grants and limits when a preset is chosen, and inherit a field by removing it', () => {
    const before = { share_history: true, fronts: ['f1'], max_per_day: 3, delay: { min_s: 0, max_s: 0 } };
    const after = withPreset(before, { delay: { min_s: 300, max_s: 1200 }, time: { mode: 'round', round_min: 15 } });
    expect(after).toEqual({ share_history: true, fronts: ['f1'], max_per_day: 3, delay: { min_s: 300, max_s: 1200 }, time: { mode: 'round', round_min: 15 } });
    expect(withField(after, 'max_per_day', undefined)).not.toHaveProperty('max_per_day');
    expect(withField(after, 'stacking', 'sequence').stacking).toBe('sequence');
  });
});
