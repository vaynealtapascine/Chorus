import { expect, test } from '@playwright/test';
import { addMember, device, enrol, go, invite, openChat, switchIn } from './helpers';

// D-132 through the app's own screens: a handover with a checklist written as one member, waiting for
// the next switch, delivered by it, ticked, and taken from Home by the member who is next.
test('a handover waits for the next switch, is read from Home and acknowledged', async ({ browser }, info) => {
  const { context, page } = await device(browser, 'handover');
  try {
    await enrol(page, invite('system'), 'Handover check');
    for (const name of ['Kai', 'Moon']) await addMember(page, name);
    await switchIn(page, 'Kai');

    await openChat(page, 'Handover');
    await expect(page.getByLabel('Message', { exact: true })).toHaveAttribute('placeholder', 'Write a handover…');
    await page.getByRole('checkbox', { name: /^Checklist/ }).click();
    await page.getByRole('textbox', { name: 'Item 1' }).fill('Keys by the door');
    await page.keyboard.press('Enter');
    await page.getByRole('textbox', { name: 'Item 2' }).fill('Lunch is packed');
    await page.getByRole('checkbox', { name: 'At next switch' }).click();
    await page.getByLabel('Message', { exact: true }).fill('For whoever is next');
    await page.getByRole('button', { name: 'Send', exact: true }).click();

    const bubble = page.locator('.msg', { hasText: 'For whoever is next' });
    await expect(bubble.getByText('Waiting for next switch')).toBeVisible();
    // ticking an item writes checklist.set as the speaker; it stays after a reload
    await bubble.getByRole('checkbox', { name: 'Keys by the door' }).click();
    await expect(bubble.getByRole('checkbox', { name: 'Keys by the door' })).toHaveAttribute('aria-checked', 'true');
    await page.screenshot({ path: info.outputPath('handover-waiting.png'), fullPage: true });

    // the next switch delivers it: Home offers it to Moon, who is now here
    await switchIn(page, 'Moon');
    await go(page, '');
    const card = page.getByRole('group', { name: 'Handover' });
    await expect(card).toContainText('Handover from Kai');
    await expect(card).toContainText('For whoever is next');
    await page.screenshot({ path: info.outputPath('handover-home.png'), fullPage: true });
    await card.getByRole('button', { name: 'Read' }).click();
    await expect(card).toHaveCount(0);

    await openChat(page, 'Handover');
    await expect(page.locator('.service', { hasText: 'Moon acknowledged the handover' })).toBeVisible();
    await expect(page.locator('.msg', { hasText: 'For whoever is next' }).getByText('Waiting for next switch')).toHaveCount(0);
    await expect(page.locator('.msg', { hasText: 'For whoever is next' }).getByRole('checkbox', { name: 'Keys by the door' })).toHaveAttribute('aria-checked', 'true');
    await page.screenshot({ path: info.outputPath('handover-read.png'), fullPage: true });
  } finally {
    await context.close();
  }
});

// D-132 / D-162: a person has no space, only its Notes chat; Save to notes leads the forward sheet.
test('a person sees only Notes, never an Internal space', async ({ browser }, info) => {
  const { context, page } = await device(browser, 'person');
  try {
    await enrol(page, invite('person'), 'Violet', 'violet');
    await go(page, 'chat');
    const rows = page.locator('.rows a.row');
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('Notes');
    await expect(page.getByText('Internal', { exact: false })).toHaveCount(0);
    await rows.first().click();
    await expect(page.getByLabel('Message', { exact: true })).toHaveAttribute('placeholder', 'Save a note…');
    await page.getByLabel('Message', { exact: true }).fill('Library card 2209 #codes');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(page.locator('.msg', { hasText: 'Library card' })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: '#codes' })).toBeVisible();
    await page.getByRole('checkbox', { name: '#codes' }).click();
    await expect(page.locator('.msg', { hasText: 'Library card' })).toBeVisible();
    await page.screenshot({ path: info.outputPath('person-notes.png'), fullPage: true });
  } finally {
    await context.close();
  }
});
