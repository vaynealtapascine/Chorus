import { expect, test } from '@playwright/test';
import { device, enrol, go, invite } from './helpers';

test('representative characters and joined prefixes choose senders without a default front', async ({ browser }) => {
  const { context, page } = await device(browser, 'representative-senders');
  try {
    await enrol(page, invite('system'), 'Sender check');
    for (const [name, sigil] of [['Fox', '::fox::'], ['Moon', '🌙'], ['Space', '👩🏽‍🚀']]) {
      await page.getByRole('button', { name: 'Add member', exact: true }).click();
      await page.getByLabel('Name', { exact: true }).fill(name);
      await page.getByLabel('Sigil', { exact: true }).fill(sigil);
      await page.locator('form.add').getByRole('button', { name: 'Add', exact: true }).click();
      await expect(page.locator('button.member', { hasText: name })).toBeVisible();
    }
    await go(page, 'chat');
    const composer = page.getByLabel('Message', { exact: true });
    const send = page.getByRole('button', { name: 'Send', exact: true });
    await composer.fill('::fox::without a separator');
    await expect(send).toBeDisabled();
    await composer.fill('::fox:: ');
    await expect(send).toBeDisabled();
    await composer.fill('::fox:: hello from one');
    await expect(page.locator('.as')).toHaveText('as Fox');
    await send.click();
    const single = page.locator('.msg', { hasText: 'hello from one' });
    await expect(single.locator('.who')).toHaveText('Fox');
    await composer.fill('::fox::🌙👩🏽‍🚀 all together');
    await expect(page.locator('.as')).toHaveText('as Fox & Moon & Space');
    await composer.press('Enter');
    const joint = page.locator('.msg', { hasText: 'all together' });
    await expect(joint.locator('.who')).toHaveText('Fox & Moon & Space');
    await expect(joint.locator('.body')).toHaveText('all together');
    await page.reload();
    await expect(page.locator('.msg', { hasText: 'all together' }).locator('.who')).toHaveText('Fox & Moon & Space');
  } finally {
    await context.close();
  }
});
