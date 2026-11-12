import { expect, test } from '@playwright/test';
import { addMember, enrol, go, invite, newFront, switchSheet } from './helpers';

// D-080 management UI: custom layers, a following front, a manual override and Resume following,
// all through the app's own screens (no seeded ops). F3 screens (D-122).
test('fronts can be created, follow Body, be overridden and resume following', async ({ page }, info) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await enrol(page, invite('system'), 'Fronts check');
  for (const name of ['Kai', 'Moon']) await addMember(page, name);

  // a custom layer, from Layers & display
  await go(page, 'fronting');
  await page.getByRole('link', { name: 'Layers and display' }).click();
  await page.getByRole('button', { name: /^Add a layer/ }).click();
  await page.getByLabel('Name', { exact: true }).fill('Quiet');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Edit Quiet' })).toBeVisible();

  // a new front that follows Body
  await newFront(page, 'Headspace');
  await page.getByLabel('Follows').selectOption({ label: 'Body' });
  await expect(page.locator('.card', { hasText: 'Headspace' }).locator('.badge')).toContainText('Body');
  await page.screenshot({ path: info.outputPath('fronts-mobile-dark.png'), fullPage: true });

  // Body switches; Headspace mirrors it
  const tab = (name: RegExp) => page.getByRole('tablist', { name: 'Fronts' }).getByRole('tab', { name });
  const shown = (name: string) => page.getByRole('region', { name, exact: true });
  await go(page, 'fronting');
  await tab(/^Body/).click();
  await switchSheet(page, ['Kai']);
  await expect(shown('Body')).toContainText('Kai');
  await tab(/^Headspace/).click();
  await expect(page.locator('.follow-state')).toContainText('Follows Body');
  await expect(shown('Headspace')).toContainText('Kai');

  // changing Headspace by hand overrides until Resume following
  await switchSheet(page, ['Moon']);
  await expect(page.locator('.follow-state')).toContainText('Changed by hand');
  await expect(shown('Headspace')).toContainText('Moon');
  await tab(/^Body/).click();
  await expect(shown('Body')).toContainText('Kai');
  await tab(/^Headspace/).click();
  await page.getByRole('button', { name: 'Resume following' }).click();
  await expect(page.locator('.follow-state')).toContainText('Follows Body');
  await expect(shown('Headspace')).toContainText('Kai');
  await expect(shown('Headspace')).not.toContainText('Moon');

  // the switch sheet offers the custom layer (each tap moves someone outward); it survives a reload
  await page.getByRole('button', { name: 'Switch', exact: true }).click();
  const sheet = page.getByRole('dialog', { name: /^Switch / });
  const kai = sheet.locator('button.pick', { hasText: 'Kai' });
  for (let i = 0; i < 6 && !(await kai.getAttribute('aria-label'))?.includes('Quiet'); i++) await kai.click();
  await expect(kai).toHaveAttribute('aria-label', 'Kai, Quiet');
  await sheet.locator('button.confirm').click();
  await page.reload();
  await tab(/^Headspace/).click();
  await expect(shown('Headspace')).toContainText('Quiet');
  await expect(shown('Headspace')).toContainText('Kai');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: info.outputPath('home-fronts-desktop-dark.png'), fullPage: true });
  expect(errors).toEqual([]);
});
