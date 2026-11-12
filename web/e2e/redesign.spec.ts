import { test, expect } from '@playwright/test';
import { addMember, device, enrol, go, invite, openChat, switchIn } from './helpers';

test('appearance defaults to dark and explicit choices persist across responsive navigation', async ({ browser }) => {
  const { context, page } = await device(browser, 'appearance');
  try {
    await page.emulateMedia({ colorScheme: 'light' });
    await enrol(page, invite('system'), 'Appearance check');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await go(page, 'settings/appearance');
    await page.getByRole('radio', { name: 'Light' }).click();
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await page.getByRole('radio', { name: 'Follow device' }).click();
    await page.emulateMedia({ colorScheme: 'dark' });
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(page.getByRole('navigation', { name: 'Your tools' })).toBeVisible();
    await page.getByRole('link', { name: 'Skip to content' }).focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/#\/settings\/appearance$/);
    await expect(page.locator('main')).toBeFocused();
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole('navigation', { name: 'Your tools' })).toBeHidden();
    await expect(page.getByRole('navigation', { name: 'Sections', exact: true }).getByRole('link')).toHaveCount(5);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('link', { name: 'More', exact: true }).click();
    // Journal moved onto the bar beside Notifications (UI19); Friends is one of More's rows
    await expect(page.getByRole('main').getByRole('link', { name: /^Friends/ })).toBeVisible();
  } finally {
    await context.close();
  }
});

test('message actions work from the keyboard and compact pins replace chat info', async ({ browser }, testInfo) => {
  const { context, page } = await device(browser, 'message-actions');
  try {
    await page.setViewportSize({ width: 390, height: 844 });
    await enrol(page, invite('system'), 'Action check');
    await addMember(page, 'Kai');
    await switchIn(page, 'Kai');
    await openChat(page, 'general');
    const composer = page.getByLabel('Message', { exact: true });
    await composer.fill('A message with reachable actions');
    await composer.press('Enter');
    await composer.fill('A consecutive message that leaves room for its actions');
    await composer.press('Enter');
    const message = page.locator('.msg', { hasText: 'A message with reachable actions' });
    const trigger = message.getByRole('button', { name: 'Message actions', exact: true });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const menu = message.getByRole('group', { name: 'Message actions', exact: true });
    await expect(menu).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('chat-actions-mobile.png'), fullPage: true });
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
    await expect(trigger).toBeFocused();
    await trigger.click();
    await menu.getByRole('button', { name: 'Pin', exact: true }).click();
    await expect(message).toHaveClass(/pinned/);
    await page.getByRole('button', { name: 'Chat info', exact: true }).click();
    const info = page.getByRole('complementary', { name: 'Chat info', exact: true });
    await info.getByRole('button', { name: /Pinned/ }).click();
    await expect(info).toBeHidden();
    await page.screenshot({ path: testInfo.outputPath('chat-pins-mobile.png'), fullPage: true });
    await expect(page.locator('.pinned .pin')).toContainText('A message with reachable actions');
    await expect(message).toBeVisible();
  } finally {
    await context.close();
  }
});
