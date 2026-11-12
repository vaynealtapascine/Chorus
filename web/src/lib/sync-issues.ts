// What the Sync issues page says about an op the server refused (Figma F10.4; Android SyncIssues).
import type { SyncIssue } from './sync/client';

const TITLES: Record<string, string> = {
  'message.create': 'Message not sent', 'message.edit': 'Edit not saved', 'post.create': 'Post not saved',
  'post.edit': 'Edit not saved', 'reaction.add': 'Reaction not sent',
};

export const issueTitle = (issue: SyncIssue): string => TITLES[issue.kind] ?? `${issue.kind.replaceAll('.', ' ')} not saved`;
export const issueText = (issue: SyncIssue): string => (typeof issue.payload.text === 'string' ? issue.payload.text : '');
