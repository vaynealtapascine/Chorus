import { expect, test } from '@playwright/test';
import { addMember, device, enrol, go, invite, openChat, problems, switchIn } from './helpers';

// D-131: our own bubble carries a tick once the server has it, and Advanced holds the opt-in for
// friends' reads (off until chosen, and it stays chosen).
test('a sent message shows its tick, and "Show friends when we\'ve read" is off until chosen', async ({ browser }) => {
  const { context, page } = await device(browser, 'ticks');
  try {
    await enrol(page, invite('system'), 'Tick check');
    await addMember(page, 'Fox');
    await switchIn(page, 'Fox'); // on our side of the chat (D-103)
    await openChat(page, 'general');
    const composer = page.getByLabel('Message', { exact: true });
    await composer.fill('a message with a tick');
    await composer.press('Enter');
    const row = page.locator('.msg-row', { hasText: 'a message with a tick' });
    // alone in the chat nobody has read it: saved (a tick), neither waiting (a clock) nor read
    await expect(row.locator('.sent[title="Sent"]')).toBeVisible();
    await expect(row.locator('.sent[title="Waiting to sync"], .sent[title="Read"]')).toHaveCount(0);

    await go(page, 'settings/advanced');
    const friends = page.getByRole('switch', { name: "Show friends when we've read" });
    await expect(friends).toHaveAttribute('aria-checked', 'false');
    await friends.click();
    await expect(friends).toHaveAttribute('aria-checked', 'true');
    await page.reload();
    await expect(page.getByRole('switch', { name: "Show friends when we've read" })).toHaveAttribute('aria-checked', 'true');
    expect(problems.filter((p) => p.startsWith('ticks:'))).toEqual([]);
  } finally {
    await context.close();
  }
});
