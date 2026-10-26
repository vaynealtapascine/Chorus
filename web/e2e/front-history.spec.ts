import { expect, test } from '@playwright/test';
import { enrol, go, invite } from './helpers';

test('named history separates inherited changes from offline editable saved entries', async ({ browser }, info) => {
  // Offline failures are expected here; keep this page out of the v1 suite's shared error list.
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors: string[] = [];
  let offline = false;
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && (!offline || !message.text().includes('ERR_INTERNET_DISCONNECTED'))) errors.push(message.text());
  });
  try {
    await enrol(page, invite('system'), 'History check');
    const seeded = await page.evaluate(async () => {
      const dev = await new Promise<{ device_id: string; short_id: string; account_id: string; session: string }>((resolve, reject) => {
        const open = indexedDB.open('chorus');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const get = open.result.transaction('kv').objectStore('kv').get('device');
          get.onsuccess = () => { resolve(get.result); open.result.close(); };
          get.onerror = () => reject(get.error);
        };
      });
      const [kai, moon, chorus] = [crypto.randomUUID(), crypto.randomUUID(), crypto.randomUUID()];
      const now = Date.now();
      const presence = (subject_id: string, layer_id: string) => ({ subject_type: 'member', subject_id, layer_id });
      const changes: [string, string, object][] = [
        ['member.create', kai, { name: 'Kai', color: '#7AA99D' }],
        ['member.create', moon, { name: 'Moon', color: '#ACA2D0' }],
        ['front.layer', dev.account_id, { layer_id: 'quiet', name: 'Close by', level: -2 }],
        ['front.define', chorus, { name: 'Chorus' }],
        ['front.change', chorus, { action: 'switch', effective_at: now - 3_600_000, entries: [presence(kai, 'quiet')] }],
        ['front.change', dev.account_id, { action: 'switch', effective_at: now - 7_200_000, entries: [presence(moon, 'front')], note: 'Before following' }],
        ['front.define', dev.account_id, { follow_source: chorus }],
        ['front.change', chorus, { action: 'switch', entries: [presence(kai, 'quiet'), presence(moon, 'quiet')] }],
      ];
      await new Promise<void>((resolve, reject) => {
        const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/api/v1/sync`);
        const timeout = setTimeout(() => { socket.close(); reject(Error('History fixture did not sync')); }, 10_000);
        socket.onopen = () => socket.send(JSON.stringify({ t: 'hello', token: dev.session, device_id: dev.device_id, cursors: {}, clock: { wall: now }, outbox: 0 }));
        socket.onerror = () => { clearTimeout(timeout); reject(Error('History fixture socket failed')); };
        socket.onmessage = (event) => {
          const f = JSON.parse(event.data);
          if (f.t === 'welcome') socket.send(JSON.stringify({ t: 'push', batch: 'history-fixture', ops: changes.map(([kind, entity_id, payload], i) => ({
            id: crypto.randomUUID(), kind, entity_id, payload, scope: `account:${dev.account_id}`, v: 1,
            hlc: `${(now + i).toString(16).padStart(12, '0')}-0000-${dev.short_id}`, device_at: now, time_source: 'auto', tz_offset_min: 480,
          })) }));
          if (f.t === 'ack') {
            clearTimeout(timeout); socket.close();
            if (f.results.some((r: { error?: unknown }) => r.error)) reject(Error(JSON.stringify(f.results)));
            else resolve();
          }
        };
      });
      return { body: dev.account_id, chorus };
    });
    // The fixture used this device's separate socket; its own writes are not fanned back to
    // another socket with the same device ID. Reconnect the app to read those accepted ops.
    await page.reload();
    await go(page, 'history');
    await expect(page.getByLabel('Front history selection')).toHaveValue(seeded.body);
    await expect(page.locator('.log li').first()).toContainText('Kai (Close by) & Moon (Close by)');
    await expect(page.locator('.log').getByRole('button', { name: 'Undo', exact: true })).toHaveCount(0);
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(page.locator('.front-summary-title')).toHaveText('Body');
    await expect(page.locator('.front-summary-names')).toHaveText('Kai (Close by) & Moon (Close by)');
    await page.screenshot({ path: info.outputPath('history-effective-desktop-dark.png'), fullPage: true });
    await page.getByRole('button', { name: 'Saved entries', exact: true }).click();
    const saved = page.locator('.log li', { hasText: 'Before following' });
    await expect(saved).toContainText('Moon (Fronting)');
    await saved.getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(saved).toHaveClass(/undone/);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: info.outputPath('history-saved-mobile-dark.png'), fullPage: true });
    await page.getByRole('button', { name: 'Front history', exact: true }).click();
    await expect(page.locator('.log li').first()).toContainText('Kai (Close by) & Moon (Close by)');
    await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
    offline = true;
    await context.setOffline(true);
    await page.reload();
    await expect(page.locator('.log li').first()).toContainText('Kai (Close by) & Moon (Close by)');
    await page.getByRole('button', { name: 'Saved entries', exact: true }).click();
    await saved.getByRole('button', { name: 'Redo', exact: true }).click();
    await expect(saved).not.toHaveClass(/undone/);
    await saved.getByRole('button', { name: 'Edit time', exact: true }).click();
    const edit = new Date(Date.now() - 10_800_000);
    const local = new Date(edit.getTime() - edit.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
    await page.getByLabel('New time', { exact: true }).fill(local);
    await saved.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByLabel('New time', { exact: true })).toHaveCount(0);
    const expectedTime = new Date(local).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' });
    await expect(saved.locator('time')).toHaveText(expectedTime);
    await page.getByRole('button', { name: 'Front history', exact: true }).click();
    await expect(page.locator('.log li').first()).toContainText('Kai (Close by) & Moon (Close by)');
    await page.reload();
    await page.getByRole('button', { name: 'Saved entries', exact: true }).click();
    await expect(saved).not.toHaveClass(/undone/);
    await expect(saved.locator('time')).toHaveText(expectedTime);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await context.setOffline(false);
    offline = false;
    await go(page, 'settings');
    await page.getByLabel('Theme', { exact: true }).selectOption('light');
    await go(page, 'history');
    await page.getByLabel('Front history selection').selectOption(seeded.chorus);
    await expect(page.locator('.log')).toContainText('Kai (Close by) & Moon (Close by)');
    await page.screenshot({ path: info.outputPath('history-effective-mobile-light.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.getByRole('button', { name: 'Saved entries', exact: true }).click();
    await expect(page.locator('.front-summary-title')).toHaveText('Body');
    const sourceSaved = page.locator('.log li').first();
    await sourceSaved.getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(sourceSaved).toHaveClass(/undone/);
    await expect(page.locator('.front-summary-names')).toHaveText('Kai (Close by)');
    await page.screenshot({ path: info.outputPath('history-saved-desktop-light.png'), fullPage: true });
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});
