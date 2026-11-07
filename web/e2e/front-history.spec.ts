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
    // Front history as F3.7: a front picker, sentences per change, and Saved entries below
    const pick = (name: string) => page.locator('.fronts').getByRole('button', { name, exact: true });
    const list = page.locator('ol.list');
    const openSaved = () => page.locator('button.saved-head').click();
    const saved = page.locator('ol.saved li', { hasText: 'Before following' });
    await page.getByRole('tab', { name: 'Week' }).click(); // the seeded hours stay in range near midnight
    await expect(pick('Body')).toHaveAttribute('aria-pressed', 'true');
    await expect(list).toContainText('Kai');
    const line = (text: string) => list.locator('li', { hasText: text }).locator(':scope > button');
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(page.locator('.front-summary-title')).toHaveText('Body');
    await expect(page.locator('.front-summary-names')).toHaveText('Kai (Close by) & Moon (Close by)');
    await page.screenshot({ path: info.outputPath('history-effective-desktop-dark.png'), fullPage: true });
    // Body's own saved entry, from before it followed Chorus, can be undone
    await openSaved();
    await expect(saved).toContainText('Moon (Fronting)');
    await saved.getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(saved).toHaveClass(/undone/);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: info.outputPath('history-saved-mobile-dark.png'), fullPage: true });
    // offline: the page opens from the cache, redo and a new time are kept across a reload
    await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
    offline = true;
    await context.setOffline(true);
    await page.reload();
    await page.getByRole('tab', { name: 'Week' }).click();
    await expect(list).toContainText('Kai');
    await openSaved();
    await saved.getByRole('button', { name: 'Redo', exact: true }).click();
    await expect(saved).not.toHaveClass(/undone/);
    await line('Moon is fronting').click();
    const actions = page.locator('ol.list .actions');
    await expect(actions.locator('.note')).toContainText('Before following');
    const edit = new Date(Date.now() - 10_800_000);
    const local = new Date(edit.getTime() - edit.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
    await actions.getByLabel('Time').fill(local);
    await actions.getByRole('button', { name: 'Save time', exact: true }).click();
    await expect(actions).toHaveCount(0);
    const expectedTime = new Date(local).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
    await expect(saved.locator('time')).toContainText(expectedTime);
    await page.reload();
    await openSaved();
    await expect(saved).not.toHaveClass(/undone/);
    await expect(saved.locator('time')).toContainText(expectedTime);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await context.setOffline(false);
    offline = false;
    // Chorus, the front Body follows: its own saved entries, undone there, change Body too
    await go(page, 'settings');
    await page.getByLabel('Theme', { exact: true }).selectOption('light');
    await go(page, 'history');
    await page.getByRole('tab', { name: 'Week' }).click();
    await pick('Chorus').click();
    await expect(list).toContainText('Kai');
    await page.screenshot({ path: info.outputPath('history-effective-mobile-light.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });
    await openSaved();
    await expect(page.locator('.front-summary-title')).toHaveText('Body');
    const sourceSaved = page.locator('ol.saved li').first();
    await sourceSaved.getByRole('button', { name: 'Undo', exact: true }).click();
    await expect(sourceSaved).toHaveClass(/undone/);
    await expect(page.locator('.front-summary-names')).toHaveText('Kai (Close by)');
    await page.screenshot({ path: info.outputPath('history-saved-desktop-light.png'), fullPage: true });
    expect(errors).toEqual([]);
  } finally { await context.close(); }
});
