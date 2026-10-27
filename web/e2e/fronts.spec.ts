import { expect, test } from '@playwright/test';
import { enrol, go, invite } from './helpers';

// D-080 management UI: custom layers, a following front, a manual override and Resume following,
// all through the app's own screens (no seeded ops).
test('fronts can be created, follow Body, be overridden and resume following', async ({ page }, info) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await enrol(page, invite('system'), 'Fronts check');

  // two members from Home's quick switch
  for (const name of ['Kai', 'Moon']) {
    await page.getByRole('button', { name: 'Add member' }).click();
    await page.getByLabel('Name', { exact: true }).fill(name);
    await page.getByRole('button', { name: 'Add', exact: true }).click();
  }

  await go(page, 'fronts');
  await expect(page.getByRole('heading', { name: 'Fronts' })).toBeVisible();
  await page.getByLabel('New layer name').fill('Quiet');
  await page.getByRole('button', { name: 'Add', exact: true }).last().click();
  await expect(page.getByLabel('Name of layer Quiet')).toBeVisible();

  await page.getByLabel('New front name').fill('Headspace');
  await page.getByRole('button', { name: 'Add', exact: true }).first().click();
  await page.getByLabel('What Headspace follows').selectOption({ label: 'Body' });
  await expect(page.locator('.card', { hasText: 'Headspace' }).locator('.badge')).toHaveText('Follows Body');
  await page.screenshot({ path: info.outputPath('fronts-mobile-dark.png'), fullPage: true });

  // Body switches; Headspace mirrors it
  await go(page, '');
  await page.getByRole('tab', { name: /Body/ }).click();
  await page.locator('.grid').getByRole('button', { name: 'Kai' }).click();
  await page.getByRole('tab', { name: /Headspace/ }).click();
  await expect(page.locator('.follow-state')).toContainText('Follows Body');
  await expect(page.locator('.card-button')).toContainText('Kai');

  // changing Headspace by hand overrides until Resume following
  await page.locator('.grid').getByRole('button', { name: 'Moon' }).click();
  await expect(page.locator('.follow-state')).toContainText('Changed by hand');
  await expect(page.locator('.card-button')).toContainText('Moon');
  await page.getByRole('tab', { name: /Body/ }).click();
  await expect(page.locator('.card-button')).toContainText('Kai');
  await page.getByRole('tab', { name: /Headspace/ }).click();
  await page.getByRole('button', { name: 'Resume following' }).click();
  await expect(page.locator('.follow-state')).toContainText('Follows Body');
  await expect(page.locator('.card-button')).toContainText('Kai');
  await expect(page.locator('.card-button')).not.toContainText('Moon');

  // the switcher offers the custom layer; it survives a reload
  await page.getByRole('button', { name: 'Switch', exact: true }).click();
  await expect(page.getByRole('heading', { name: "Who's in Headspace?" })).toBeVisible();
  const level = page.locator('.tray .lvl').first();
  await level.click(); // Fronting → Co-conscious
  await level.click(); // → Present
  await level.click(); // → Quiet
  await expect(level).toHaveText('Quiet');
  await page.getByRole('button', { name: 'Switch', exact: true }).last().click();
  await page.reload();
  await page.getByRole('tab', { name: /Headspace/ }).click();
  await expect(page.locator('.card-button .layer-footer', { hasText: 'Quiet' })).toContainText('Kai');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: info.outputPath('home-fronts-desktop-dark.png'), fullPage: true });
  expect(errors).toEqual([]);
});
