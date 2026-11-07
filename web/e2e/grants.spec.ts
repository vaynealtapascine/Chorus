import { expect, test } from '@playwright/test';
import { addMember, device, enrol, go, invite, live, newFront, switchSheet } from './helpers';

const run = Date.now().toString(36);

// D-084 through both accounts' screens: an additional front is invisible to a follower until the
// owner grants it, then follows the usual delayed reveal; revoking hides it again.
test('a follower sees an additional front only once granted', async ({ browser }, info) => {
  test.setTimeout(240_000);
  const sys = await device(browser, 'grants-system');
  const friend = await device(browser, 'grants-friend');
  try {
    await enrol(sys.page, invite('system'), 'Grant Stars', `grants${run}`);
    for (const name of ['Kai', 'Moon']) await addMember(sys.page, name);

    // a second front whose Fronting layer drives switch notifications
    await newFront(sys.page, 'Headspace');
    const notify = sys.page.locator('fieldset', { hasText: 'Switch notifications' });
    await sys.page.getByText('Who counts for what').click();
    await notify.getByLabel('Use the default').uncheck();
    await notify.getByLabel('Fronting').check();

    await enrol(friend.page, invite('person'), 'Robin', `robin${run}`);
    await go(friend.page, 'people');
    await friend.page.getByLabel('Handle to follow').fill(`@grants${run}`);
    await friend.page.getByRole('button', { name: 'Follow', exact: true }).click();
    await go(sys.page, 'people');
    const request = sys.page.locator('li.card', { hasText: 'wants to follow you' });
    await request.locator('select').selectOption('close');
    await request.getByRole('button', { name: 'Accept' }).click();

    const following = friend.page.locator('li.card', { hasText: 'Grant Stars' });
    const switchHead = async (who: string) => {
      await go(sys.page, '');
      await sys.page.getByRole('tablist', { name: 'Fronts' }).getByRole('tab', { name: /^Headspace/ }).click();
      await switchSheet(sys.page, [who]);
    };
    const followerSees = async (text: string, present: boolean) => expect(async () => {
      await friend.page.reload();
      await live(friend.page);
      if (present) await expect(following).toContainText(text, { timeout: 1_000 });
      else await expect(following).not.toContainText(text, { timeout: 1_000 });
    }).toPass({ timeout: 60_000, intervals: [2_000] });

    await switchHead('Kai');
    await friend.page.waitForTimeout(20_000); // past the settle: still nothing without a grant
    await followerSees('Headspace', false);

    await go(sys.page, 'people');
    const card = sys.page.locator('li.card', { hasText: 'Robin' }).first();
    await card.getByText('Additional fronts and layers').click();
    await card.getByLabel('Headspace').check();
    await switchHead('Moon'); // a change after the grant reveals on the usual schedule
    await followerSees('Headspace: Moon', true);
    await friend.page.screenshot({ path: info.outputPath('follower-granted-front.png'), fullPage: true });

    await go(sys.page, 'people');
    await card.getByText('Additional fronts and layers').click();
    await card.getByLabel('Headspace').uncheck();
    await followerSees('Headspace', false);
  } finally {
    await sys.context.close();
    await friend.context.close();
  }
});
