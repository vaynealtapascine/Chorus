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

test('an edited message keeps its history (SPEC §5.3)', async () => {
  const hello = `helo agian ${run}`;
  const fixed = `hello again, edited ${run}`;
  const box = friend.page.getByLabel('Message', { exact: true });
  await box.fill(hello);
  await box.press('Enter');
  const msg = friend.page.locator('.msg', { hasText: hello });
  await msg.hover();
  await msg.getByTitle('Edit').click();
  await box.fill(fixed);
  await box.press('Enter');
  // the other account sees the new text, and "(edited)" opens what it said before
  const theirs = stars.page.locator('.msg', { hasText: fixed });
  await expect(theirs).toBeVisible();
  await theirs.getByRole('button', { name: '(edited)' }).click();
  const history = theirs.getByRole('list', { name: 'Edit history' });
  await expect(history.getByRole('listitem')).toHaveCount(2);
  await expect(history.getByRole('listitem').first()).toContainText(hello);
  await expect(history.getByRole('listitem').first()).toContainText('original');
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

test('reply privately: from the shared space into the DM, linking back (SPEC §5.3)', async () => {
  const welcome = `welcome to the club ${run}`;
  const msg = friend.page.locator('.msg', { hasText: welcome });
  await msg.hover();
  await msg.getByTitle('Reply privately').click();
  // the DM opens with the reply ready
  await expect(friend.page.locator('nav[aria-label="Spaces"] a.dm.on, nav[aria-label="Spaces"] a.on', { hasText: 'Stars' })).toBeVisible();
  await expect(friend.page.getByText(/Replying to/)).toBeVisible();
  const answer = `glad to be here ${run}`;
  const box = friend.page.getByLabel('Message', { exact: true });
  await box.fill(answer);
  await box.press('Enter');
  // the system sees it in the DM, with a card pointing back to the club's channel
  await go(stars.page, 'chat');
  await stars.page.locator('nav[aria-label="Spaces"] a.dm', { hasText: 'Robin' }).click();
  const reply = stars.page.locator('.msg', { hasText: answer });
  await expect(reply).toBeVisible();
  await expect(reply.locator('.replybar')).toContainText(welcome.slice(0, 20));
  await expect(reply.locator('.replybar .elsewhere')).toContainText('#general');
});

test('one internal channel shared with a follower (channel permissions)', async () => {
  const note = `news for Robin ${run}`;
  const inside = `inside only ${run}`;
  await stars.page.setViewportSize({ width: 1000, height: 900 }); // the new-channel field is desktop-only
  await go(stars.page, 'chat');
  await stars.page.locator('nav[aria-label="Spaces"] a', { hasText: 'Home' }).click();
  await stars.page.getByLabel('Message', { exact: true }).fill(inside);
  await stars.page.getByLabel('Message', { exact: true }).press('Enter');
  await stars.page.getByLabel('New channel name').fill(`news-${run}`);
  await stars.page.getByLabel('New channel name').press('Enter');
  await expect(stars.page.locator('section.room h1')).toContainText(`news-${run}`);
  await stars.page.getByLabel('Message', { exact: true }).fill(note);
  await stars.page.getByLabel('Message', { exact: true }).press('Enter');
  await stars.page.getByLabel('Channel menu').click();
  await stars.page.locator('details.perms summary').click();
  await stars.page.getByLabel('Permission target').selectOption({ label: 'Robin' });
  await stars.page.getByLabel('See it permission').selectOption('allow');
  await expect(stars.page.getByText('this shares just this channel with them')).toBeVisible();
  await stars.page.locator('details.perms').getByRole('button', { name: 'Save' }).click();
  await expect(stars.page.locator('details.perms li', { hasText: 'Robin' })).toContainText('can view');

  await expect(async () => {
    await go(friend.page, 'chat');
    await friend.page.locator('nav[aria-label="Spaces"] a', { hasText: 'shared with you' }).click({ timeout: 2_000 });
    await expect(friend.page.getByText(note)).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  await expect(friend.page.getByText(inside)).toHaveCount(0);
  await stars.page.setViewportSize({ width: 420, height: 900 });
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

  // switches, searched on the device (works offline)
  await stars.page.getByRole('tab', { name: 'Switches' }).click();
  await stars.page.getByLabel('Search switches').fill('kai');
  await expect(stars.page.locator('.results article', { hasText: 'Kai' }).first()).toBeVisible();
});

test('sync everything now', async () => {
  await go(stars.page, 'data');
  await stars.page.getByRole('button', { name: 'Sync everything now' }).click();
  await expect(stars.page.getByText('Everything is on this device.')).toBeVisible();
  await expect(stars.page.getByText(/This app uses .* on this device/)).toBeVisible();
});

test('a full export with files', async () => {
  await go(stars.page, 'data');
  await stars.page.getByRole('button', { name: /Prepare a (full export|new one)/ }).click();
  const link = stars.page.getByRole('link', { name: /Download chorus-.*\.zip/ });
  await expect(link).toBeVisible({ timeout: 30_000 });
  const zip = await stars.page.request.get((await link.getAttribute('href'))!);
  expect(zip.status()).toBe(200);
  expect(zip.headers()['content-type']).toBe('application/zip');
  expect((await zip.body()).subarray(0, 2).toString()).toBe('PK');
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

  // a feed that filters by fronting can be shared too, with a note on both sides (D-069)
  const fronting = `Fronting ${run}`;
  await stars.page.getByRole('button', { name: 'New feed' }).click();
  await stars.page.getByLabel('Name', { exact: true }).fill(fronting);
  await stars.page.getByLabel('Filter').fill('tag:garden fronting:true');
  await stars.page.getByLabel('Who can open it').selectOption('followers');
  await expect(stars.page.getByRole('note')).toContainText('who was fronting when these posts were written');
  await stars.page.getByRole('button', { name: 'Save feed' }).click();
  await expect(stars.page.locator('nav[aria-label="Saved feeds"]')).toContainText(fronting);
  await friend.page.reload();
  await live(friend.page);
  await friend.page.getByRole('button', { name: 'Feeds' }).click();
  await shared.getByRole('button', { name: new RegExp(fronting) }).click();
  await expect(shared.getByRole('note')).toContainText('who was fronting when these posts were written');
});

test('no console errors, CSP included', async () => {
  const page = await stars.page.request.get('/');
  expect(page.headers()['content-security-policy']).toContain("default-src 'self'");
  expect(problems).toEqual([]);
});
