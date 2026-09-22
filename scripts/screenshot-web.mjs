// Enrol a fresh browser against the dev server, add members, switch, screenshot light + dark.
// Usage (from DisclosureStudio/, which has Playwright): node ../Chorus/scripts/screenshot-web.mjs <invite code> <out dir>
import { createRequire } from 'node:module';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const require = createRequire('C:/Users/pcuser/source/repos/DisclosureStudio/package.json');
const { chromium } = require('playwright');

const [invite, outDir] = process.argv.slice(2);
const profile = mkdtempSync(join(tmpdir(), 'chorus-shot-'));
const ctx = await chromium.launchPersistentContext(profile, {
  viewport: { width: 420, height: 720 },
  deviceScaleFactor: 2,
  colorScheme: 'light',
});
const page = await ctx.newPage();
page.on('pageerror', (e) => console.error('pageerror', e.message));
await page.goto(`http://localhost:5252/i/${invite}`);
await page.screenshot({ path: join(outDir, 'chorus-onboarding.png') });
await page.fill('input[placeholder="The Stars"]', 'The Stars');
await page.click('button:has-text("Continue")');
await page.waitForSelector('.status[data-status=live]', { timeout: 10000 });
const members = [
  ['🌌', 'Kai', '#c0694e'],
  ['🔖', 'June', '#5e8c61'],
  ['❤️‍🔥', 'Rin', '#fff27a'],
  ['🌿', 'Moss', '#6c7bd6'],
  ['🌙', 'Wren', '#9b6fb3'],
];
for (const [sigil, name, color] of members) {
  await page.click('button:has-text("Add member")');
  await page.fill('[aria-label=Sigil]', sigil);
  await page.fill('[aria-label=Name]', name);
  await page.$eval('[aria-label=Colour]', (el, c) => {
    el.value = c;
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, color);
  await page.click('form.add button');
}
await page.click('button.member:has-text("Rin")');
await page.waitForTimeout(400);
await page.screenshot({ path: join(outDir, 'chorus-home-light.png') });
await page.emulateMedia({ colorScheme: 'dark' });
await page.reload();
await page.waitForSelector('.status[data-status=live]', { timeout: 10000 });
await page.waitForTimeout(300);
await page.screenshot({ path: join(outDir, 'chorus-home-dark.png') });
await ctx.close();
console.log('ok');
