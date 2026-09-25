// The web client under failure, in a real browser (R29): the chaos test (crates/chorus-server/
// tests/chaos.rs) proves the sync engine; this proves the PWA around it. It runs its own server
// (the binary scripts/e2e-web.sh built, on its own port and data directory) so it can kill it:
// the server killed under open pages, messages and files queued offline across a reload and a
// service-worker update, two tabs of one browser profile, and IndexedDB refusing writes (a full
// disk). Each time, at the end the server has every message exactly once, and so does the page.
import { type ChildProcess, execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { type Page, expect, test } from '@playwright/test';
import { enrol, live } from './helpers';

// this suite's own list: a killed server makes failed requests on purpose, and v1.spec's
// "no console errors" shouldn't see them
const problems: string[] = [];
function watch(page: Page, who: string): Page {
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`${who}: console: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`${who}: pageerror: ${e.message}`));
  return page;
}

const [bin] = (process.env.CHORUS_E2E_CLI ?? '').trim().split(/\s+/);
const local = !!bin && (process.env.CHORUS_E2E_BASE ?? 'http://127.0.0.1').includes('127.0.0.1');
const port = Number(process.env.CHORUS_E2E_FAILURE_PORT ?? 5398);
const base = `http://127.0.0.1:${port}`;
const dist = resolve(fileURLToPath(new URL('..', import.meta.url)), 'dist');
const run = Date.now().toString(36).slice(-6);

test.describe.configure({ mode: 'serial' });
test.skip(!local, 'kills its server: runs with scripts/e2e-web.sh, not against a real server');
test.use({ baseURL: base, serviceWorkers: 'allow' });

let dir = '';
let config = '';
let server: ChildProcess | null = null;

async function start(): Promise<void> {
  const log = openSync(join(dir, 'server.log'), 'a');
  server = spawn(bin, ['--config', config, 'serve'], { stdio: ['ignore', log, log] });
  closeSync(log);
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(`${base}/api/v1/server`)).ok) return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error(`the server didn't start: ${readFileSync(join(dir, 'server.log'), 'utf8').slice(-2000)}`);
}

/** `kill -9`: no goodbye to the sockets. */
async function kill(): Promise<void> {
  const s = server;
  server = null;
  if (!s || s.exitCode !== null) return;
  const gone = new Promise((r) => s.once('exit', r));
  s.kill('SIGKILL');
  await gone;
}

function invite(kind: 'system' | 'person'): string {
  const out = execFileSync(bin, ['--config', config, 'invite', '--kind', kind], { encoding: 'utf8' }).trim();
  const code = out.split('\n').pop()?.match(/\/i\/([A-Za-z0-9_-]+)/)?.[1];
  if (!code) throw new Error(`no invite in: ${out}`);
  return code;
}

test.beforeAll(async () => {
  dir = mkdtempSync(join(tmpdir(), 'chorus-failure-'));
  config = join(dir, 'chorus.toml');
  const path = (p: string) => p.replace(/\\/g, '/');
  writeFileSync(
    config,
    `[server]\nlisten = "127.0.0.1:${port}"\npublic_url = "${base}"\ndata_dir = "${path(join(dir, 'data'))}"\nweb_dir = "${path(dist)}"\n`,
  );
  await start();
});

test.afterAll(async () => {
  await kill();
  if (process.env.CHORUS_E2E_KEEP !== '1') rmSync(dir, { recursive: true, force: true });
});

/** What the server has: every message of this account whose text has `word`, by text. */
async function onServer(page: Page, word: string): Promise<string[]> {
  return page.evaluate(async (word) => {
    const session: string = await new Promise((ok, fail) => {
      const r = indexedDB.open('chorus');
      r.onerror = () => fail(r.error);
      r.onsuccess = () => {
        const get = r.result.transaction('kv').objectStore('kv').get('device');
        get.onsuccess = () => {
          ok(get.result?.session ?? '');
          r.result.close();
        };
      };
    });
    const res = await fetch(`/api/v1/search/messages?q=${encodeURIComponent(word)}&limit=100`, {
      headers: { authorization: `Bearer ${session}` },
    });
    const body = await res.json();
    return (body.items ?? []).map((m: { text: string }) => m.text).sort();
  }, word);
}

/** The server ends up with exactly these texts (each once), and so does the page. */
async function converged(page: Page, word: string, texts: string[]): Promise<void> {
  const want = [...texts].sort();
  await expect.poll(() => onServer(page, word), { timeout: 30_000, intervals: [500] }).toEqual(want);
  for (const t of texts) await expect(page.locator('.msg', { hasText: t })).toHaveCount(1);
}

async function send(page: Page, text: string): Promise<void> {
  const box = page.getByLabel('Message', { exact: true });
  await box.fill(text);
  await box.press('Enter');
  await expect(page.locator('.msg', { hasText: text })).toHaveCount(1);
}

async function openChat(page: Page): Promise<void> {
  // the app turns `#/chat` into `#/chat/<channel>` at once, which a goto can take as aborted
  if (!page.url().startsWith(base)) await page.goto('/');
  await page.getByRole('link', { name: 'Chat', exact: true }).click();
  const general = page.getByRole('link', { name: /general/ }).first();
  if (await general.isVisible().catch(() => false)) await general.click();
  await expect(page.getByLabel('Message', { exact: true })).toBeVisible();
}

async function offline(page: Page): Promise<void> {
  await expect(page.locator('.status[data-status=live]')).toHaveCount(0, { timeout: 15_000 });
}

let page: Page;

test('the server killed under an open page: sends queue, survive a reload, arrive once', async ({ browser }) => {
  const context = await browser.newContext();
  page = watch(await context.newPage(), 'failure');
  await enrol(page, invite('system'), 'Failure Test', `fail_${run}`);
  // someone to speak as
  await page.getByRole('button', { name: 'Add member' }).click();
  await page.getByLabel('Name', { exact: true }).fill('Kai');
  await page.locator('form.add').getByRole('button', { name: 'Add' }).click();
  await page.locator('button.member', { hasText: 'Kai' }).click();
  await expect(page.getByLabel("Change who's here")).toContainText('Kai');
  await openChat(page);
  const word = `kz${run}`;
  await send(page, `${word} one, live`);
  await kill();
  await offline(page);
  await send(page, `${word} two, offline`);
  await send(page, `${word} three, offline`);
  // a reload with the server down: the app opens from its cache and IndexedDB
  await page.reload();
  await openChat(page);
  for (const t of ['two, offline', 'three, offline']) await expect(page.locator('.msg', { hasText: t })).toHaveCount(1);
  await start();
  await live(page);
  await converged(page, word, [`${word} one, live`, `${word} two, offline`, `${word} three, offline`]);
});

test('a file queued offline goes up after a reload and a new version of the app', async () => {
  const word = `fz${run}`;
  await kill();
  await offline(page);
  await page.locator('input.file-input').setInputFiles({ name: `tomatoes-${run}.txt`, mimeType: 'text/plain', buffer: Buffer.from(`tomatoes ${run}`) });
  await send(page, `${word} with a file`);
  await send(page, `${word} after it`);
  await page.reload();
  await openChat(page);
  // a new version of the app is out while this page waits: its service worker takes over
  const sw = join(dist, 'sw.js');
  const original = readFileSync(sw, 'utf8');
  writeFileSync(sw, original.replace(/const CACHE = 'chorus-([^']*)'/, `const CACHE = 'chorus-$1-${run}'`));
  try {
    await start();
    await live(page);
    await page.evaluate(async () => {
      const reg = await navigator.serviceWorker.getRegistration();
      await reg?.update();
    });
    await expect
      .poll(() => page.evaluate(async () => (await caches.keys()).join(' ')), { timeout: 20_000 })
      .toContain(run);
    await page.reload();
    await live(page);
    await converged(page, word, [`${word} with a file`, `${word} after it`]);
    // and the file is on the server, whole
    const hash = createHash('sha256').update(`tomatoes ${run}`).digest('hex');
    const bytes = await page.evaluate(async (hash) => {
      const session: string = await new Promise((ok) => {
        const r = indexedDB.open('chorus');
        r.onsuccess = () => {
          const get = r.result.transaction('kv').objectStore('kv').get('device');
          get.onsuccess = () => {
            ok(get.result?.session ?? '');
            r.result.close();
          };
        };
      });
      const res = await fetch(`/api/v1/blobs/${hash}`, { headers: { authorization: `Bearer ${session}` } });
      return res.ok ? await res.text() : `blob ${res.status}`;
    }, hash);
    expect(bytes).toBe(`tomatoes ${run}`);
  } finally {
    writeFileSync(sw, original);
  }
});

test('two tabs of one browser profile: both send, one closes, nothing is lost or doubled', async () => {
  const word = `tz${run}`;
  const second = watch(await page.context().newPage(), 'failure tab 2');
  await openChat(second);
  await live(second);
  await send(page, `${word} from the first tab`);
  await send(second, `${word} from the second tab`);
  // with the server down, both queue
  await kill();
  await offline(page);
  await offline(second);
  await send(page, `${word} first tab, offline`);
  await send(second, `${word} second tab, offline`);
  await page.close();
  await start();
  await live(second);
  await send(second, `${word} second tab, back`);
  const all = [
    `${word} from the first tab`,
    `${word} from the second tab`,
    `${word} first tab, offline`,
    `${word} second tab, offline`,
    `${word} second tab, back`,
  ];
  await second.reload();
  await openChat(second);
  await converged(second, word, all);
  page = second;
});

test('IndexedDB refusing writes (a full disk): sends still arrive, and the page says so', async () => {
  const word = `qz${run}`;
  // every write fails from now on, as it does when the browser's quota is used up
  await page.evaluate(() => {
    const put = IDBObjectStore.prototype.put;
    (window as unknown as { __quota: boolean }).__quota = true;
    IDBObjectStore.prototype.put = function (...args: Parameters<typeof put>) {
      if ((window as unknown as { __quota: boolean }).__quota) {
        throw new DOMException('The quota has been exceeded.', 'QuotaExceededError');
      }
      return put.apply(this, args);
    };
  });
  await send(page, `${word} during a full disk`);
  await converged(page, word, [`${word} during a full disk`]);
  await expect(page.getByRole('alert')).toContainText("This browser's storage is full");
  // offline, still full: this one exists only in the page for now
  await kill();
  await offline(page);
  await send(page, `${word} offline, disk full`);
  await page.evaluate(() => {
    (window as unknown as { __quota: boolean }).__quota = false;
  });
  await send(page, `${word} with room again`);
  // the failed saves went through with this one: a reload with the server still down has them all
  await expect(page.getByRole('alert')).toHaveCount(0, { timeout: 20_000 });
  await page.reload();
  await openChat(page);
  for (const t of ['offline, disk full', 'with room again']) await expect(page.locator('.msg', { hasText: t })).toHaveCount(1);
  await start();
  await live(page);
  await converged(page, word, [`${word} during a full disk`, `${word} offline, disk full`, `${word} with room again`]);
});

test('a windowed tab asking the server for older messages while it goes away', async () => {
  // a browser tab (not installed) keeps a window and pages older history in over REST
  const olderButton = page.getByRole('button', { name: 'Older messages (from the server)' });
  await expect(olderButton).toBeVisible();
  // the server goes away mid-request: the page says so and can try again
  await page.route('**/channels/*/messages?before=*', (r) => r.abort('connectionrefused'));
  await olderButton.click();
  await expect(page.getByText("Couldn't reach the server for older messages")).toBeVisible();
  await expect(olderButton).toBeEnabled();
  await page.unroute('**/channels/*/messages?before=*');
  // and while it's down, there's nothing to ask
  await kill();
  await offline(page);
  await expect(olderButton).toBeDisabled();
  await start();
  await live(page);
  await olderButton.click();
  // nothing older than what this tab holds: the button goes
  await expect(olderButton).toHaveCount(0);
  await expect(page.getByText("Couldn't reach the server for older messages")).toHaveCount(0);
});

test('no page errors', async () => {
  // a killed server shows as failed requests (expected); anything else is a bug
  const real = problems.filter((p) => !/Failed to load resource|ERR_CONNECTION_REFUSED|WebSocket/.test(p));
  expect(real).toEqual([]);
});
