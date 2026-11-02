import { describe, it, expect } from 'vitest';
import { postPreview } from './post-preview';

describe('300 character journal previews', () => {
  it('keeps multiline short posts whole and stops long posts at Unicode boundaries', () => {
    expect(postPreview('a\nb\nc\nd', []).truncated).toBe(false);
    const preview = postPreview('🪴'.repeat(301), [{ type: 'spoiler', offset: 590, length: 12 }, { type: 'mention', offset: 598, length: 4 }]);
    expect(preview.text).toBe('🪴'.repeat(300));
    expect(preview.truncated).toBe(true);
    expect(preview.entities).toEqual([{ type: 'spoiler', offset: 590, length: 10 }]);
    expect(postPreview('a'.repeat(300), []).truncated).toBe(false);
  });
});
