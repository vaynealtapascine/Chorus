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
  if (name) await page.getByLabel(/^(Your system's name|Your name|Name)/).fill(name);
  if (handle) await page.getByLabel(/^Handle/).fill(handle);
  // the invite decides the form (D-093): Join for a new account, Link this browser for another device
  await page.getByRole('button', { name: /^(Join|Link this browser)$/ }).click();
  await live(page);
}

export async function live(page: Page): Promise<void> {
  // attached, not visible: the desktop chat hides the app bar that shows it (F5.21)
  await expect(page.locator('.status[data-status=live]')).toHaveCount(1, { timeout: 30_000 });
}

/** Go to a hash route and wait for the socket to be live. */
export async function go(page: Page, hash: string): Promise<void> {
  await page.goto(`/#/${hash}`);
  await live(page);
}

/** Add a member from Members (the name in the search box becomes theirs); lands on their editor. */
export async function addMember(page: Page, name: string): Promise<void> {
  await go(page, 'members');
  await page.getByLabel('Search members').fill(name);
  await page.getByRole('button', { name: 'Add member', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
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

/** Set an audience chip ("This post", "This feed") to Friends: everyone who follows us. */
export async function shareWithFriends(page: Page, chip: string): Promise<void> {
  await page.getByRole('button', { name: new RegExp(`^${chip}: .*Change who can see it$`) }).click();
  await page.getByRole('menuitemradio', { name: /^Friends/ }).click();
}
