import { expect, test } from '@playwright/test';
import { device, enrol, go, invite, problems, watch } from './helpers';

test('typing reaches the other device, expires, stops on send and is absent on the composing device', async ({ browser }, info) => {
  const phone = await device(browser, 'typing-phone');
  const desk = await device(browser, 'typing-desk');
  const wire: unknown[] = [];
  for (const [name, page] of [['phone', phone.page], ['desk', desk.page]] as const) {
    page.on('websocket', (socket) => {
      for (const event of ['framesent', 'framereceived'] as const) socket.on(event, (frame) => {
        try {
          const f = JSON.parse(frame.payload.toString());
          if (f.t === 'typing' || f.t === 'error') wire.push({ name, event, ...f });
        } catch { /* non-JSON control frame */ }
      });
    });
  }
  try {
    await enrol(phone.page, invite('system'), 'Typing check');
    await phone.page.getByRole('button', { name: 'Add member', exact: true }).click();
    await phone.page.getByLabel('Name', { exact: true }).fill('Amarynthe');
    await phone.page.locator('form.add').getByRole('button', { name: 'Add', exact: true }).click();
    await phone.page.locator('button.member', { hasText: 'Amarynthe' }).click();
    await phone.page.getByText('Devices and data', { exact: true }).click();
    await phone.page.getByRole('button', { name: 'Link another device…' }).click();
    const link = await phone.page.locator('section[aria-label="Link another device"] code').innerText();
    await enrol(desk.page, link.match(/\/i\/([A-Za-z0-9_-]+)/)![1]);
    await go(phone.page, 'chat');
    await go(desk.page, 'chat');
    const mirror = watch(await desk.context.newPage(), 'typing-desk-tab');
    await go(mirror, 'chat');
    const composer = phone.page.getByLabel('Message', { exact: true });
    const subtitle = desk.page.locator('header .typing-subtitle');
    await composer.fill('A draft that is never stored on the server');
    await expect(subtitle, JSON.stringify(wire)).toHaveText('Amarynthe is typing…');
    await expect(mirror.locator('header .typing-subtitle')).toHaveText('Amarynthe is typing…');
    await expect(phone.page.locator('.typing-subtitle')).toHaveCount(0);
    await desk.page.screenshot({ path: info.outputPath('typing-mobile.png'), fullPage: true });
    await desk.page.setViewportSize({ width: 1440, height: 900 });
    await desk.page.screenshot({ path: info.outputPath('typing-desktop.png'), fullPage: true });
    await expect(subtitle).toHaveCount(0, { timeout: 8000 });
    await expect(mirror.locator('header .typing-subtitle')).toHaveCount(0);
    await composer.fill('Now send it');
    await expect(subtitle).toHaveText('Amarynthe is typing…');
    await phone.page.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(subtitle).toHaveCount(0);
    await expect(desk.page.locator('.msg', { hasText: 'Now send it' })).toBeVisible();
    // A clear stops immediately; changing channels must not announce an idle carried draft.
    await phone.page.waitForTimeout(2100); // the previous start's server throttle window
    await composer.fill('Clear this draft');
    await expect(subtitle).toHaveText('Amarynthe is typing…');
    await composer.fill('');
    await expect(subtitle).toHaveCount(0);
    await phone.page.waitForTimeout(2100);
    await composer.fill('Carry this draft');
    await expect(subtitle).toHaveText('Amarynthe is typing…');
    await phone.page.setViewportSize({ width: 1440, height: 900 });
    await phone.page.getByLabel('New channel name').fill('another-channel');
    await phone.page.getByLabel('New channel name').press('Enter');
    await expect(subtitle).toHaveCount(0);
    const secondChannel = phone.page.url().split('/').pop();
    expect(wire.filter((f) => {
      const frame = f as { event?: string; channel?: string; state?: string };
      return frame.event === 'framesent' && frame.channel === secondChannel && frame.state === 'start';
    })).toEqual([]);
    expect(problems).toEqual([]);
  } finally {
    await info.attach('typing-wire', { body: JSON.stringify(wire), contentType: 'application/json' });
    await phone.context.close();
    await desk.context.close();
  }
});
