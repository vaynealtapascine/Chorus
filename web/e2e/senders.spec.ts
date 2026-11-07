import { expect, test } from '@playwright/test';
import { addMember, device, enrol, invite, openChat } from './helpers';

test('representative characters and joined prefixes choose senders without a default front', async ({ browser }) => {
  const { context, page } = await device(browser, 'representative-senders');
  try {
    await enrol(page, invite('system'), 'Sender check');
    for (const [name, sigil] of [['Fox', '::fox::'], ['Moon', '🌙'], ['Space', '👩🏽‍🚀']]) await addMember(page, name, sigil);
    await openChat(page, 'general');
    const composer = page.getByLabel('Message', { exact: true });
    const send = page.getByRole('button', { name: 'Send', exact: true });
    await composer.fill('::fox::without a separator');
    await expect(send).toBeDisabled();
    await composer.fill('::fox:: ');
    await expect(send).toBeDisabled();
    await composer.fill('::fox:: hello from one');
    await expect(send).toBeEnabled();
    await send.click();
    const single = page.locator('.msg', { hasText: 'hello from one' });
    await expect(single.locator('.who')).toHaveText('Fox');
    await composer.fill('::fox::🌙👩🏽‍🚀 all together');
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
