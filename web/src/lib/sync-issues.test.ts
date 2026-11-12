import { expect, it } from 'vitest';
import { issueText, issueTitle } from './sync-issues';
import type { SyncIssue } from './sync/client';

const issue = (kind: string, payload: Record<string, unknown> = {}): SyncIssue => ({ id: 'i', kind, scope: 's', entity_id: null, payload, at: 0, code: 'x', message: 'Too long' });

it('titles a refused op in plain words and finds the text to copy', () => {
  expect(issueTitle(issue('message.create'))).toBe('Message not sent');
  expect(issueTitle(issue('channel.rename'))).toBe('channel rename not saved');
  expect(issueText(issue('message.create', { text: 'hello' }))).toBe('hello');
  expect(issueText(issue('message.create', { text: 4 }))).toBe('');
});
