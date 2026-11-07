import { expect, test } from '@playwright/test';
import { addMember, device, enrol, go, invite, openChat, switchIn } from './helpers';

// D-081 / D-086 through the app's own screens: a single-choice chat poll whose member vote can
// be changed and removed, and a multiple-choice journal poll; totals come from the server tally.
test('chat and journal polls take one editable vote per member', async ({ browser }, info) => {
  const { context, page } = await device(browser, 'polls');
  try {
    await enrol(page, invite('system'), 'Poll check');
    for (const name of ['Kai', 'Moon']) await addMember(page, name);
    await switchIn(page, 'Kai');

    await openChat(page, 'general');
    await page.getByRole('button', { name: 'Message options', exact: true }).click(); // polls are behind the composer's options
    await page.getByText('Add a poll', { exact: true }).click();
    await page.getByLabel('Poll option 1').fill('Pasta');
    await page.getByLabel('Poll option 2').fill('Soup');
    await page.getByLabel('Message', { exact: true }).fill('Dinner?');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    const poll = page.locator('.msg', { hasText: 'Dinner?' }).getByRole('group', { name: 'Poll' });
    await expect(poll).toContainText('Choose one');
    await poll.getByRole('button', { name: /Pasta/ }).click();
    await expect(poll.getByRole('button', { name: /Pasta/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(poll).toContainText('1 vote', { timeout: 15_000 }); // the server's tally
    await poll.getByRole('button', { name: /Soup/ }).click(); // single choice: moves the vote
    await expect(poll.getByRole('button', { name: /Pasta/ })).toHaveAttribute('aria-pressed', 'false');
    await expect(poll.getByRole('button', { name: /Soup/ }).locator('.count')).toHaveText('1', { timeout: 15_000 });
    await expect(poll).toContainText('1 vote');
    await page.reload();
    const again = page.locator('.msg', { hasText: 'Dinner?' }).getByRole('group', { name: 'Poll' });
    await expect(again.getByRole('button', { name: /Soup/ })).toHaveAttribute('aria-pressed', 'true');
    await again.getByRole('button', { name: 'Remove my vote' }).click();
    await expect(again).toContainText('0 votes', { timeout: 15_000 });
    await page.screenshot({ path: info.outputPath('chat-poll.png'), fullPage: true });

    await go(page, 'journal');
    const composer = page.locator('form.composer').first();
    await composer.getByText('Post options').click();
    await composer.getByLabel('Add a poll').check();
    await composer.getByLabel('Poll option 1').fill('Walk');
    await composer.getByLabel('Poll option 2').fill('Read');
    await composer.getByLabel('Allow more than one choice').check();
    await composer.getByLabel('Post text').fill('Plans for later');
    await composer.getByRole('button', { name: 'Post', exact: true }).click();
    const board = page.locator('article', { hasText: 'Plans for later' }).getByRole('group', { name: 'Poll' });
    await expect(board).toContainText('Choose any');
    await board.getByRole('button', { name: /Walk/ }).click();
    await board.getByRole('button', { name: /Read/ }).click();
    await expect(board.getByRole('button', { name: /Walk/ }).locator('.count')).toHaveText('1', { timeout: 15_000 });
    await expect(board.getByRole('button', { name: /Read/ }).locator('.count')).toHaveText('1');
    await expect(board).toContainText('1 vote'); // one member, two choices
    await page.screenshot({ path: info.outputPath('journal-poll.png'), fullPage: true });
  } finally {
    await context.close();
  }
});
