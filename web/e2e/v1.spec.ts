// The v1 run, repeatable (R12): two accounts in separate browsers, a second device of the first,
// switches, a follower's delayed view, a shared space and a DM, a journal post with a reply and a
// reaction across accounts, search, and no console errors (the CSP included) anywhere.
import { expect, test } from '@playwright/test';
import { type Device, device, enrol, go, invite, live, problems } from './helpers';

test.describe.configure({ mode: 'serial' });

// handles are unique per run, so the suite can run again against the same server
const run = Date.now().toString(36).slice(-6);
const starsHandle = `stars_${run}`;
const robinHandle = `robin_${run}`;

let stars: Device; // a system, on its first device
let desk: Device; // the same system on a second device
let friend: Device; // a person account that follows it

test.beforeAll(async ({ browser }) => {
  stars = await device(browser, 'stars');
  desk = await device(browser, 'desk');
  friend = await device(browser, 'friend');
});

test.afterAll(async () => {
  for (const d of [stars, desk, friend]) await d?.context.close();
});

test('onboarding by invite, members and a switch', async () => {
  const { page } = stars;
  await enrol(page, invite('system'), 'The Stars', starsHandle);
  for (const name of ['Kai', 'Rin']) {
    await page.getByRole('button', { name: 'Add member' }).click();
    await page.getByLabel('Name', { exact: true }).fill(name);
    await page.locator('form.add').getByRole('button', { name: 'Add' }).click();
    await expect(page.locator('button.member', { hasText: name })).toBeVisible();
  }
  await page.locator('button.member', { hasText: 'Kai' }).click();
  await expect(page.getByLabel("Change who's here")).toContainText('Kai');
});

test('a switch reaches a second device', async () => {
  await stars.page.getByRole('button', { name: 'Link another device…' }).click();
  const link = await stars.page.locator('section[aria-label="Link another device"] code').innerText();
  const code = link.match(/\/i\/([A-Za-z0-9_-]+)/)?.[1];
  expect(code).toBeTruthy();
  await enrol(desk.page, code!);
  await expect(desk.page.getByLabel("Change who's here")).toContainText('Kai');
  await stars.page.locator('button.member', { hasText: 'Rin' }).click();
  await expect(desk.page.getByLabel("Change who's here")).toContainText('Rin', { timeout: 5_000 });
});

test('a follower sees a switch only after its delay', async () => {
  await enrol(friend.page, invite('person'), 'Robin', robinHandle);
  await go(friend.page, 'people');
  await friend.page.getByLabel('Handle to follow').fill(`@${starsHandle}`);
  await friend.page.getByRole('button', { name: 'Follow', exact: true }).click();
  await expect(friend.page.getByText('waiting for them to accept')).toBeVisible();

  await go(stars.page, 'people');
  const request = stars.page.locator('li.card', { hasText: 'wants to follow you' });
  await request.locator('select').selectOption('close');
  await request.getByRole('button', { name: 'Accept' }).click();
  await expect(stars.page.locator('li.card', { hasText: 'Robin' }).first()).toBeVisible();

  // "Close": no delay beyond the 15 s settle (NOTIFICATIONS §2)
  await go(stars.page, '');
  await stars.page.locator('button.member', { hasText: 'Kai' }).click();
  const switched = Date.now();
  const following = friend.page.locator('li.card', { hasText: 'The Stars' });
  await go(friend.page, 'people');
  await expect(following).toBeVisible();
  await expect(following).not.toContainText('Kai is fronting');
  await expect(async () => {
    await friend.page.reload();
    await live(friend.page);
    await expect(following).toContainText('Kai is fronting', { timeout: 1_000 });
  }).toPass({ timeout: 60_000, intervals: [2_000] });
  expect(Date.now() - switched).toBeGreaterThanOrEqual(14_000);
});

test('a DM between two accounts', async () => {
  const hello = `hello from Robin ${run}`;
  await go(friend.page, 'people');
  await friend.page.locator('li.card', { hasText: 'The Stars' }).getByRole('button', { name: 'Message' }).click();
  const box = friend.page.getByLabel('Message', { exact: true });
  await expect(box).toBeVisible();
  await box.fill(hello);
  await box.press('Enter');
  await expect(friend.page.getByText(hello)).toBeVisible();

  await go(stars.page, 'chat');
  await stars.page.locator('nav[aria-label="Spaces"] a.dm', { hasText: 'Robin' }).click();
  await expect(stars.page.getByText(hello)).toBeVisible();
  const back = `hi Robin, Kai here ${run}`;
  await stars.page.getByLabel('Message', { exact: true }).fill(back);
  await stars.page.getByLabel('Message', { exact: true }).press('Enter');
  await expect(friend.page.getByText(back)).toBeVisible();
});

test('a shared space between two accounts', async () => {
  const name = `Book club ${run}`;
  await go(stars.page, 'people');
  await stars.page.getByLabel('Shared space name').fill(name);
  await stars.page.locator('form.new-space label', { hasText: 'Robin' }).locator('input').check();
  await stars.page.getByRole('button', { name: 'Start a shared space' }).click();
  const welcome = `welcome to the club ${run}`;
  const box = stars.page.getByLabel('Message', { exact: true });
  await expect(box).toBeVisible();
  await expect(stars.page.locator('p.space')).toContainText(name);
  await box.fill(welcome);
  await box.press('Enter');
  await expect(stars.page.getByText(welcome)).toBeVisible();

  await go(friend.page, 'chat');
  await friend.page.locator('nav[aria-label="Spaces"] a', { hasText: name }).click();
  await expect(friend.page.getByText(welcome)).toBeVisible();
});

test('a post with a reply and a reaction across accounts', async () => {
  const text = `tomatoes are in ${run}`;
  await go(stars.page, 'journal');
  await stars.page.getByLabel('Post text').fill(text);
  await stars.page.getByLabel('Tags').fill('garden');
  await stars.page.getByLabel('Visible to').selectOption('followers');
  await stars.page.getByRole('button', { name: 'Post', exact: true }).click();
  await expect(stars.page.locator('article.post', { hasText: text })).toBeVisible();

  // the follower finds it with the account it follows, reacts and replies
  const reply = `congrats from Robin ${run}`;
  await go(friend.page, 'people');
  const shared = friend.page.locator('article.shared-post', { hasText: text });
  await expect(async () => {
    await friend.page.reload();
    await live(friend.page);
    await expect(shared).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  await shared.getByRole('button', { name: 'React 💜' }).click();
  await expect(shared.getByRole('button', { name: 'Remove 💜 reaction' })).toBeVisible();
  await shared.getByRole('button', { name: 'Reply' }).click();
  await shared.getByLabel('Post text').fill(reply);
  await shared.getByRole('button', { name: 'Post', exact: true }).click();
  await expect(shared.getByLabel('Post text')).toBeHidden();

  // the author sees both through the post's thread
  await go(stars.page, 'journal');
  const thread = stars.page.locator('article.post', { hasText: text }).locator('xpath=following-sibling::div[contains(@class, "thread")][1]');
  await expect(async () => {
    await thread.getByRole('button', { name: /View replies|Refresh replies/ }).first().click();
    await expect(thread.getByText(reply)).toBeVisible({ timeout: 2_000 });
    await expect(thread.getByText('💜 Robin')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

test('search finds messages and posts', async () => {
  await go(stars.page, 'search');
  await stars.page.getByLabel('Search messages').fill(`Robin ${run}`);
  await expect(stars.page.locator('.results article', { hasText: `hello from Robin ${run}` })).toBeVisible();

  // posts: the follower finds the followers-only post; the server checks who may read it
  await go(friend.page, 'search');
  await friend.page.getByRole('tab', { name: 'Posts' }).click();
  await friend.page.getByLabel('Search posts').fill(`tomatoes ${run}`);
  await expect(friend.page.locator('.results article', { hasText: `tomatoes are in ${run}` })).toBeVisible();
  await friend.page.getByLabel('Search posts').fill(`nothing-like-this-${run}`);
  await expect(friend.page.getByText('No matching posts.')).toBeVisible();
});

test('a feed shared with followers', async () => {
  const name = `Garden ${run}`;
  await go(stars.page, 'journal');
  await stars.page.getByRole('button', { name: 'Feeds' }).click();
  await stars.page.getByLabel('Name', { exact: true }).fill(name);
  await stars.page.getByLabel('Filter').fill('tag:garden');
  await stars.page.getByLabel('Who can open it').selectOption('followers');
  await stars.page.getByRole('button', { name: 'Save feed' }).click();
  await expect(stars.page.locator('nav[aria-label="Saved feeds"]')).toContainText(name);

  await go(friend.page, 'journal');
  await friend.page.getByRole('button', { name: 'Feeds' }).click();
  const shared = friend.page.locator('[aria-label="Feeds shared with you"]');
  await shared.getByRole('button', { name: new RegExp(name) }).click();
  await expect(shared.getByText(`tomatoes are in ${run}`)).toBeVisible();
});

test('no console errors, CSP included', async () => {
  const page = await stars.page.request.get('/');
  expect(page.headers()['content-security-policy']).toContain("default-src 'self'");
  expect(problems).toEqual([]);
});
