// The app-wide opener of the full Journal composer (UI24): the pencil, New post, Quote, Home's
// "Write something" and a box's expand button all call `openComposer`; App.svelte hosts the dialog once.
import type { Audience } from './profile-audience';
import type { JournalDraft } from './journal-draft';

export interface ComposeOptions {
  kind?: JournalDraft['kind'];
  /** a post to quote (Quote on a card) */
  quote?: string;
  /** answer this post: the dialog opens that thread's reply draft */
  replyTo?: string;
  /** "Replying to …" as the thread page names it */
  replyNames?: string;
  /** a reply's starting audience (another account's post starts server-wide) */
  replyVisibility?: 'private' | 'followers' | 'server';
  author?: string;
  audience?: Audience;
  /** opened from an inline box (its expand button): closing hands the draft back to it without asking */
  inline?: boolean;
}

export const journalCompose = $state<{ open: boolean; seq: number; options: ComposeOptions }>({ open: false, seq: 0, options: {} });

export function openComposer(options: ComposeOptions = {}) {
  journalCompose.options = options;
  journalCompose.seq++;
  journalCompose.open = true;
}

export function closeComposer() {
  journalCompose.open = false;
}
