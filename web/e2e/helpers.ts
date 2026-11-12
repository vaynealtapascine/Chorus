import { execFileSync } from 'node:child_process';
import type { Browser, BrowserContext, ConsoleMessage, Page } from '@playwright/test';
import { expect } from '@playwright/test';

/** `CHORUS_E2E_CLI`: the server's CLI with its config, e.g. `/path/chorus-server --config /path/chorus.toml`. */
function cli(args: string[]): string {
  const cmd = (process.env.CHORUS_E2E_CLI ?? '').trim();
  if (!cmd) throw new Error('set CHORUS_E2E_CLI (scripts/e2e-web.sh does)');
  const [bin, ...rest] = cmd.split(/\s+/);
  return execFileSync(bin, [...rest, ...args], { encoding: 'utf8' }).trim();
}

/** A fresh one-use invite code for a new account (`system` or `person`). */
export function invite(kind: 'system' | 'person'): string {
  const url = cli(['invite', '--kind', kind]).split('\n').pop() ?? '';
  const code = url.match(/\/i\/([A-Za-z0-9_-]+)/)?.[1];
  if (!code) throw new Error(`no invite in: ${url}`);
  return code;
}

/** Every console error and uncaught exception in the suite's pages (CSP violations show here). */
export const problems: string[] = [];

export function watch(page: Page, who: string): Page {
  page.on('console', (m: ConsoleMessage) => {
    if (m.type() === 'error') problems.push(`${who}: console: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`${who}: pageerror: ${e.message}`));
  // the console says only "Failed to load resource": say which
  page.on('response', (r) => { if (r.status() >= 400) problems.push(`${who}: ${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`); });
  return page;
}

export interface Device { context: BrowserContext; page: Page }

export async function device(browser: Browser, who: string): Promise<Device> {
  const context = await browser.newContext();
  const page = watch(await context.newPage(), who);
  return { context, page };
}

/** Enrol through the invite page (a new account, or another device of one). */
export async function enrol(page: Page, code: string, name?: string, handle?: string): Promise<void> {
  await page.goto(`/i/${code}`);
  if (name) await page.getByLabel(/^(System name|Your name|Name)/).fill(name);
  if (handle) await page.getByLabel(/^Handle/).fill(handle);
  // the invite decides the form (D-093): Continue for a new account, Link for another device
  await page.getByRole('button', { name: /^(Continue|Link)$/ }).click();
  // a new system goes through setup (F1.2-F1.4): leave it as it is
  const setup = page.getByRole('main', { name: 'Setup' });
  for (let i = 0; i < 3; i++) {
    const seen = await Promise.race([
      setup.waitFor({ timeout: 15_000 }).then(() => true, () => false),
      page.locator('.shell').waitFor({ state: 'attached', timeout: 15_000 }).then(() => false, () => false),
    ]);
    if (!seen) break;
    await setup.getByRole('button', { name: 'Continue' }).click();
  }
  await live(page);
}

export async function live(page: Page): Promise<void> {
  // on the shell, not a visible label: the connection banner only speaks when something is wrong (F2.5)
  await expect(page.locator('.shell[data-status=live]')).toHaveCount(1, { timeout: 30_000 });
}

/** Go to a hash route and wait for the socket to be live. */
export async function go(page: Page, hash: string): Promise<void> {
  // only the commit: Chat rewrites `#/chat` to `#/chat/<channel>` while loading, which a goto
  // waiting for load can report as ERR_ABORTED (NOTES §Web)
  await page.goto(`/#/${hash}`, { waitUntil: 'commit' });
  await live(page);
}

/** Add a member from Members (the name in the search box becomes theirs); lands on their editor. */
export async function addMember(page: Page, name: string, sigil?: string): Promise<void> {
  await go(page, 'members');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByLabel('Search members').fill(name);
  await page.getByRole('button', { name: 'Add member', exact: true }).click();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue(name);
  if (sigil) {
    await page.getByRole('button', { name: /^Sigils and proxy tags/ }).click();
    const box = page.getByLabel('Sigils', { exact: true });
    await box.fill(sigil);
    await box.press('Tab'); // saved on change
    await expect(box).toHaveValue(sigil);
    await page.getByRole('dialog').getByRole('button', { name: 'Done' }).click();
  }
}

/** A new channel in the system's own space, from the chat list's New chat; lands in it. */
export async function newChannel(page: Page, name: string): Promise<void> {
  await go(page, 'chat');
  await page.getByRole('button', { name: 'New chat' }).click();
  await page.getByRole('dialog', { name: 'New message' }).getByRole('button', { name: /^New internal group/ }).click();
  const editor = page.getByRole('dialog', { name: 'New channel' });
  await editor.getByLabel('Name', { exact: true }).fill(name);
  await editor.getByRole('button', { name: 'Create' }).click();
  await expect(page.locator('.room-head h1')).toContainText(name);
}

/** Body's front becomes this one member, from their editor's Switch in; lands on Fronting. */
export async function switchIn(page: Page, name: string): Promise<void> {
  await go(page, 'members');
  await page.getByRole('link', { name, exact: true }).click();
  await page.getByRole('button', { name: 'Switch in' }).click();
  await expect(fronting(page)).toContainText(name);
}

/** Who Fronting shows in Body (the page must be on Fronting). */
export function fronting(page: Page) {
  return page.getByRole('region', { name: 'Body' });
}

/** Open a chat from the chat list by its title; a shared space with several channels opens its
 * first channel (chat list, D-120). */
export async function openChat(page: Page, title: string): Promise<void> {
  await go(page, 'chat');
  const row = page.locator('.rows a.row, .rows button.space-entry').filter({ hasText: title }).first();
  await row.click();
  const inside = page.locator('.space-head');
  if (await inside.isVisible().catch(() => false)) await page.locator('.rows a.row').first().click();
  await expect(page.getByLabel('Message', { exact: true })).toBeVisible();
}

/** A message bubble by its text (the chat list's preview of it is not a bubble). */
export function message(page: Page, text: string) {
  return page.locator('.msg', { hasText: text });
}

/** Set an audience chip ("This post", "This feed") to Followers: everyone who follows us (it read "Friends" before D-129). */
export async function shareWithFriends(page: Page, chip: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(`^${chip}: .*Change who can see it$`) }).click();
  await page.getByRole('menuitemradio', { name: /^Followers/ }).click();
}

/** A new front from Fronts › New front › Custom; leaves its card open for editing (F3.4). */
export async function newFront(page: Page, name: string): Promise<void> {
  await go(page, 'fronts');
  await page.locator('button.new-head').click();
  await page.getByRole('button', { name: 'Custom', exact: true }).click();
  await page.getByLabel('New front name').fill(name);
  // an account's first front turns on separate fronts, after asking
  page.once('dialog', (d) => void d.accept());
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await page.getByRole('button', { name: `Edit ${name}` }).click();
}

/** On Fronting: open the switch sheet for the shown front, place `names` (each tap moves someone
 * one layer outward, `taps` times) and switch (F3.2). */
export async function switchSheet(page: Page, names: string[], taps = 1): Promise<void> {
  await page.getByRole('button', { name: 'Switch', exact: true }).click();
  const sheet = page.getByRole('dialog', { name: /^Switch / });
  for (const name of names) for (let i = 0; i < taps; i++) await sheet.locator('button.pick', { hasText: name }).click();
  await sheet.getByRole('button', { name: 'Switch', exact: true }).click();
  await expect(sheet).toHaveCount(0);
}

/** Ask to follow someone by handle from Friends (UI20, F7.1). */
export async function follow(page: Page, handle: string): Promise<void> {
  await go(page, 'people');
  await page.getByRole('button', { name: 'Follow someone' }).click();
  await page.getByLabel('Their handle').fill(handle);
  await page.getByRole('button', { name: 'Send request' }).click();
}

/** A system accepts the first follow request from Friends › Requests with a preset (F7.7). */
export async function acceptRequest(page: Page, preset: 'Close — instant' | 'Gentle' | 'Private' | 'Digest' | 'Off'): Promise<void> {
  await go(page, 'people');
  await page.getByRole('tab', { name: /^Requests/ }).click();
  await page.getByRole('button', { name: 'Accept…' }).first().click();
  const sheet = page.getByRole('dialog', { name: /follow you\?$/ });
  await sheet.getByRole('radio', { name: new RegExp(`^${preset}`) }).click();
  await sheet.getByRole('button', { name: /^Accept/ }).click();
  await expect(sheet).toBeHidden();
  // accepting offers to follow back (D-129); these flows stay one-way
  const back = page.getByRole('dialog', { name: /^Follow .+ back\?$/ });
  if (await back.waitFor({ state: 'visible', timeout: 5_000 }).then(() => true, () => false)) {
    await back.getByRole('button', { name: 'Not now' }).click();
    await expect(back).toBeHidden();
  }
}

/** A friend's row on Friends, by name. */
export function friendRow(page: Page, name: string) {
  return page.locator('.fr-row', { hasText: name });
}
