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
  if (name) await page.getByPlaceholder('The Stars').fill(name);
  if (handle) await page.getByPlaceholder('stars', { exact: true }).fill(handle);
  await page.getByRole('button', { name: 'Continue' }).click();
  await live(page);
}

export async function live(page: Page): Promise<void> {
  await expect(page.locator('.status[data-status=live]')).toBeVisible({ timeout: 30_000 });
}

/** Go to a hash route and wait for the socket to be live. */
export async function go(page: Page, hash: string): Promise<void> {
  await page.goto(`/#/${hash}`);
  await live(page);
}
