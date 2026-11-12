// The v1 run, repeatable (R12): two accounts in separate browsers, a second device of the first,
// switches, a follower's delayed view, a shared space and a DM, a journal post with a reply and a
// reaction across accounts, search, and no console errors (the CSP included) anywhere.
import { expect, test } from '@playwright/test';
import { acceptRequest, addMember, type Device, device, enrol, follow, friendRow, fronting, go, invite, live, message, newChannel, openChat, problems, shareWithFriends, switchIn } from './helpers';

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
  for (const name of ['Kai', 'Rin']) await addMember(page, name);
  await switchIn(page, 'Kai');
});

test('a switch reaches a second device', async () => {
  await stars.page.getByText('Devices and data', { exact: true }).click();
  await stars.page.getByRole('button', { name: 'Link another device…' }).click();
  const link = await stars.page.locator('section[aria-label="Link another device"] code').innerText();
  const code = link.match(/\/i\/([A-Za-z0-9_-]+)/)?.[1];
  expect(code).toBeTruthy();
  await enrol(desk.page, code!);
  await expect(fronting(desk.page)).toContainText('Kai');
  await switchIn(stars.page, 'Rin');
  await expect(fronting(desk.page)).toContainText('Rin', { timeout: 5_000 });
});

test('a follower sees a switch only after its delay', async () => {
  await enrol(friend.page, invite('person'), 'Robin', robinHandle);
  await follow(friend.page, `@${starsHandle}`);
  await expect(friend.page.getByText('Waiting for them to accept')).toBeVisible();

  await acceptRequest(stars.page, 'Close — instant');
  await stars.page.getByRole('tab', { name: 'Friends' }).click();
  await expect(friendRow(stars.page, 'Robin')).toBeVisible();

  // "Close": no delay beyond the 15 s settle (NOTIFICATIONS §2)
  await switchIn(stars.page, 'Kai');
  const switched = Date.now();
  const following = friendRow(friend.page, 'The Stars');
  await go(friend.page, 'people');
  await expect(following).toBeVisible();
  await expect(following).not.toContainText('Kai fronting');
  await expect(async () => {
    await friend.page.reload();
    await live(friend.page);
    await expect(following).toContainText('Kai fronting', { timeout: 1_000 });
  }).toPass({ timeout: 60_000, intervals: [2_000] });
  expect(Date.now() - switched).toBeGreaterThanOrEqual(14_000);
});

test('a DM between two accounts', async () => {
  const hello = `hello from Robin ${run}`;
  await go(friend.page, 'people');
  await friend.page.getByRole('button', { name: 'Message The Stars' }).click();
  const box = friend.page.getByLabel('Message', { exact: true });
  await expect(box).toBeVisible();
  await box.fill(hello);
  await box.press('Enter');
  await expect(message(friend.page, hello)).toBeVisible();

  await openChat(stars.page, 'Robin');
  await expect(message(stars.page, hello)).toBeVisible();
  const back = `hi Robin, Kai here ${run}`;
  await stars.page.getByLabel('Message', { exact: true }).fill(back);
  await stars.page.getByLabel('Message', { exact: true }).press('Enter');
  await expect(message(friend.page, back)).toBeVisible();
});

test('an edited message keeps its history (SPEC §5.3)', async () => {
  const hello = `helo agian ${run}`;
  const fixed = `hello again, edited ${run}`;
  const box = friend.page.getByLabel('Message', { exact: true });
  await box.fill(hello);
  await box.press('Enter');
  const msg = friend.page.locator('.msg', { hasText: hello });
  await msg.getByRole('button', { name: 'Message actions', exact: true }).click();
  await msg.getByTitle('Edit').click();
  await box.fill(fixed);
  await box.press('Enter');
  // the other account sees the new text, and "(edited)" opens what it said before
  // "edited" sits beside the bubble (owner, 2026-11-02)
  const theirs = stars.page.locator('.msg-row', { hasText: fixed });
  await expect(theirs).toBeVisible();
  await theirs.getByRole('button', { name: 'edited', exact: true }).click();
  const history = theirs.getByRole('list', { name: 'Edit history' });
  await expect(history.getByRole('listitem')).toHaveCount(2);
  await expect(history.getByRole('listitem').first()).toContainText(hello);
  await expect(history.getByRole('listitem').first()).toContainText('original');
});

test('a shared space between two accounts', async () => {
  const name = `Book club ${run}`;
  // shared spaces start from New chat › New shared space: friends first, then a name (F5.10)
  await go(stars.page, 'chat');
  await stars.page.getByRole('button', { name: 'New chat' }).click();
  await stars.page.getByRole('dialog', { name: 'New message' }).getByRole('button', { name: /^New shared space/ }).click();
  const dialog = stars.page.getByRole('dialog', { name: 'New shared space' });
  await dialog.getByRole('checkbox', { name: /Robin/ }).click();
  await dialog.getByRole('button', { name: 'Name the space' }).click();
  const naming = stars.page.getByRole('dialog', { name: 'Name' });
  await naming.getByLabel('Name', { exact: true }).fill(name);
  await naming.getByRole('button', { name: 'Create' }).click();
  const welcome = `welcome to the club ${run}`;
  const box = stars.page.getByLabel('Message', { exact: true });
  await expect(box).toBeVisible();
  await expect(stars.page.locator('.room-head h1')).toContainText(name);
  await box.fill(welcome);
  await box.press('Enter');
  await expect(message(stars.page, welcome)).toBeVisible();

  await openChat(friend.page, name);
  await expect(message(friend.page, welcome)).toBeVisible();
});

test('reply privately: from the shared space into the DM, linking back (SPEC §5.3)', async () => {
  const welcome = `welcome to the club ${run}`;
  const msg = friend.page.locator('.msg', { hasText: welcome });
  await msg.getByRole('button', { name: 'Message actions', exact: true }).click();
  await msg.getByTitle('Reply privately').click();
  // the DM opens with the reply ready
  await expect(friend.page.locator('.room-head h1')).toContainText('Stars');
  await expect(friend.page.getByText(/Replying to/)).toBeVisible();
  const answer = `glad to be here ${run}`;
  const box = friend.page.getByLabel('Message', { exact: true });
  await box.fill(answer);
  await box.press('Enter');
  // the system sees it in the DM, with a card pointing back to the club's channel
  await openChat(stars.page, 'Robin');
  const reply = stars.page.locator('.msg', { hasText: answer });
  await expect(reply).toBeVisible();
  await expect(reply.locator('.reply-quote')).toContainText(welcome.slice(0, 20));
  await expect(reply.locator('.reply-quote .elsewhere')).toContainText('#general');
});

test('space roles: the owner makes someone read-only, and their send is refused with why', async () => {
  const club = `Book club ${run}`;
  await openChat(stars.page, club);
  await stars.page.getByLabel('Channel menu').click();
  await stars.page.getByText('Space roles…').click();
  const role = stars.page.getByLabel(/^Role of /).first();
  await role.selectOption('read_only');
  await expect(role).toHaveValue('read_only');

  await openChat(friend.page, club);
  const quiet = `can I still talk ${run}`;
  const box = friend.page.getByLabel('Message', { exact: true });
  await box.fill(quiet);
  await box.press('Enter');
  const refused = friend.page.getByRole('list', { name: 'Messages not sent' });
  await expect(refused).toContainText("you don't have the send permission");
  await expect(refused).toContainText(quiet);
  await refused.getByRole('button', { name: 'Dismiss' }).click();
  await expect(refused).toHaveCount(0);
  await expect(message(stars.page, quiet)).toHaveCount(0);
  // and back to a member, so the rest of the run can talk; the menu closed again
  await role.selectOption('member');
  await stars.page.getByLabel('Channel menu').click();
});

test('slow mode holds a second message and it can be cancelled (D-076)', async () => {
  const club = `Book club ${run}`;
  await openChat(stars.page, club);
  await stars.page.getByLabel('Channel menu').click();
  const slow = stars.page.getByLabel('Slow mode for this channel');
  await slow.selectOption('300');
  await expect(slow).toHaveValue('300');

  await openChat(friend.page, club);
  const box = friend.page.getByLabel('Message', { exact: true });
  const first = `first under slow mode ${run}`;
  const second = `second too soon ${run}`;
  await box.fill(first);
  await box.press('Enter');
  await expect(message(stars.page, first)).toBeVisible();
  await box.fill(second);
  await box.press('Enter');
  // held, not refused: a countdown and a Cancel, and no Sync issue
  const held = friend.page.locator('.msg', { hasText: second }).locator('.held');
  await expect(held).toContainText(/Slow mode: sending in \d+ s/);
  await expect(friend.page.getByRole('list', { name: 'Messages not sent' })).toHaveCount(0);
  await held.getByRole('button', { name: 'Cancel' }).click();
  await expect(message(friend.page, second)).toHaveCount(0);
  await expect(message(stars.page, second)).toHaveCount(0);
  // off again for the rest of the run
  await slow.selectOption('0');
  await stars.page.getByLabel('Channel menu').click();
});

test('one internal channel shared with a follower (channel permissions)', async () => {
  const note = `news for Robin ${run}`;
  const inside = `inside only ${run}`;
  // the system's own space opens on its #general (chat list, D-120)
  await openChat(stars.page, 'general');
  await stars.page.getByLabel('Message', { exact: true }).fill(inside);
  await stars.page.getByLabel('Message', { exact: true }).press('Enter');
  await newChannel(stars.page, `news-${run}`);
  await stars.page.getByLabel('Message', { exact: true }).fill(note);
  await stars.page.getByLabel('Message', { exact: true }).press('Enter');
  await stars.page.getByLabel('Channel menu').click();
  await stars.page.locator('details.perms summary').click();
  await stars.page.getByLabel('Permission target').selectOption({ label: 'Robin' });
  await stars.page.getByLabel('See it permission').selectOption('allow');
  // A live projection update must not erase the unsaved permissions draft.
  await openChat(desk.page, 'general');
  await desk.page.getByLabel('Message', { exact: true }).fill(`while editing permissions ${run}`);
  await desk.page.getByLabel('Message', { exact: true }).press('Enter');
  await expect(stars.page.getByLabel('See it permission')).toHaveValue('allow');
  await expect(stars.page.getByText('this shares just this channel with them')).toBeVisible();
  await stars.page.locator('details.perms').getByRole('button', { name: 'Save' }).click();
  await expect(stars.page.locator('details.perms li', { hasText: 'Robin' })).toContainText('can view');

  await expect(async () => {
    await go(friend.page, 'chat');
    await friend.page.locator('.rows a.row', { hasText: `news-${run}` }).click({ timeout: 2_000 });
    await expect(message(friend.page, note)).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  await expect(message(friend.page, inside)).toHaveCount(0);
});

test('a post with a reply and a reaction across accounts', async () => {
  const text = `tomatoes are in ${run}`;
  await go(stars.page, 'journal');
  await stars.page.getByLabel('Post text').fill(text);
  await stars.page.getByText('Post options', { exact: true }).click();
  await stars.page.getByLabel('Tags').fill('garden');
  await stars.page.getByText('Post options', { exact: true }).click();
  await shareWithFriends(stars.page, 'This post');
  await stars.page.getByRole('button', { name: 'Post', exact: true }).click();
  await expect(stars.page.locator('article.post', { hasText: text })).toBeVisible();

  // the follower finds it in Journal › Friends, hearts it and replies on its thread page
  const reply = `congrats from Robin ${run}`;
  const shared = friend.page.locator('article.post', { hasText: text });
  await expect(async () => {
    await go(friend.page, 'journal');
    await friend.page.getByRole('navigation', { name: 'Journal sections' }).getByRole('button', { name: 'Friends' }).click();
    await expect(shared).toBeVisible({ timeout: 3_000 });
  }).toPass({ timeout: 30_000 });
  const removeHeart = shared.getByRole('button', { name: /^Remove heart as / });
  await shared.getByRole('button', { name: /^Heart as / }).click();
  // the first heart on another account's post says once that they see who hearted
  const disclose = friend.page.locator('dialog.disclose[open]');
  await expect(disclose.or(removeHeart)).toBeVisible();
  if (await disclose.isVisible()) await disclose.getByRole('button', { name: 'Heart', exact: true }).click();
  await expect(removeHeart).toBeVisible();
  await shared.getByRole('button', { name: 'Reply' }).click();
  const theirThread = friend.page.getByRole('region', { name: 'Post thread' });
  await expect(theirThread.getByText(/Replying to/)).toBeVisible();
  await theirThread.getByLabel('Post text').fill(reply);
  await theirThread.locator('form.composer').getByRole('button', { name: 'Reply', exact: true }).click();
  await expect(theirThread.locator('article', { hasText: reply })).toBeVisible();

  // the author sees both on the post's thread page
  await go(stars.page, 'journal');
  await stars.page.locator('article.post', { hasText: text }).getByRole('link', { name: 'now' }).click();
  const thread = stars.page.getByRole('region', { name: 'Post thread' });
  await expect(thread.getByText(/Replying to/)).toBeVisible();
  await expect(async () => {
    await thread.getByRole('button', { name: 'Refresh' }).click();
    await expect(thread.locator('article.row', { hasText: reply })).toBeVisible({ timeout: 2_000 });
    await expect(thread.getByText('1 like')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
  // the friend's reply opens as a thread of its own, with the post it answers above it
  await thread.locator('article.row', { hasText: reply }).getByTitle('Open this post').click();
  await expect(thread.locator('.ancestors')).toContainText(text);
  await stars.page.goBack();
  await expect(thread.locator('article.row', { hasText: reply })).toBeVisible();
});

test('search finds messages and posts', async () => {
  const scope = (page: typeof stars.page, name: string) =>
    page.getByRole('navigation', { name: 'Search scope' }).getByRole('button', { name }).click();
  await go(stars.page, 'search');
  await stars.page.getByLabel('Search all chats').fill(`Robin ${run}`);
  await expect(stars.page.locator('article', { hasText: `hello from Robin ${run}` }).first()).toBeVisible();

  // posts: the follower finds the followers-only post; the server checks who may read it
  await go(friend.page, 'search');
  await scope(friend.page, 'Journal');
  await friend.page.getByLabel('Search Journal').fill(`tomatoes ${run}`);
  await expect(friend.page.locator('article', { hasText: `tomatoes are in ${run}` }).first()).toBeVisible();
  await friend.page.getByLabel('Search Journal').fill(`nothing-like-this-${run}`);
  await expect(friend.page.getByText('No matching Journal posts or entries.')).toBeVisible();

  // switches, searched on the device (works offline)
  await scope(stars.page, 'Switches');
  await stars.page.getByLabel('Search switches').fill('kai');
  await expect(stars.page.locator('article', { hasText: 'Kai' }).first()).toBeVisible();
});

test('sync everything now', async () => {
  await go(stars.page, 'settings/device');
  await stars.page.getByRole('button', { name: 'Sync everything now' }).click();
  await expect(stars.page.getByText('Everything is on this device.')).toBeVisible();
  await expect(stars.page.getByText(/This app uses .* on this device/)).toBeVisible();
});

test('a full export with files', async () => {
  await go(stars.page, 'settings/data');
  const bundle = stars.page.getByLabel('Full export');
  await bundle.getByRole('button', { name: 'Export' }).click();
  const link = bundle.getByRole('link', { name: 'Download' });
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
  await shareWithFriends(stars.page, 'This feed');
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
  await shareWithFriends(stars.page, 'This feed');
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

// last: it signs `desk` out, then links it again (D-148, D-153)
test('a device signed out from another one goes back to onboarding, and can be linked again', async () => {
  await go(stars.page, '');
  await stars.page.getByText('Devices and data', { exact: true }).click();
  const devices = stars.page.locator('section[aria-label="Your devices"]');
  await devices.getByRole('button', { name: 'Sign out', exact: true }).click();
  const asking = devices.getByRole('group', { name: /^Sign out / });
  await expect(asking).toContainText('erased from it two days from now');
  await expect(asking.getByLabel('Erase its copy now instead')).not.toBeChecked(); // off unless chosen
  const revoke = stars.page.waitForRequest((r) => r.url().endsWith('/revoke'));
  await asking.getByRole('button', { name: /^Sign out .+/ }).click();
  expect((await revoke).postData()).toBeNull(); // no `wipe`: erased in two days
  await expect(devices).toHaveCount(0); // one device left
  // the open socket learns it on its next frame: the heartbeat's ping, within ~25 s of quiet
  const line = desk.page.getByText('This browser was signed out from another device.');
  await expect(line).toBeVisible({ timeout: 45_000 });
  await expect(desk.page.getByRole('heading', { name: 'Welcome to Chorus' })).toBeVisible();
  await expect(desk.page.locator('.shell')).toHaveCount(0); // the kept copy isn't shown
  // a reload remembers it, and opens no socket
  const sockets: string[] = [];
  desk.page.on('websocket', (ws) => sockets.push(ws.url()));
  await desk.page.reload();
  await expect(line).toBeVisible({ timeout: 30_000 });
  await desk.page.waitForTimeout(3_000);
  expect(sockets).toHaveLength(0);

  // linked again with a device link of the same account: a server that says the account picks
  // the copy up; one that doesn't (older) asks to erase it first
  await stars.page.getByRole('button', { name: 'Link another device…' }).click();
  const link = await stars.page.locator('section[aria-label="Link another device"] code').innerText();
  await desk.page.getByLabel('Invite link or code').fill(link);
  await desk.page.getByRole('button', { name: 'Continue' }).click();
  await desk.page.getByRole('button', { name: 'Link this browser', exact: true }).click();
  const erase = desk.page.getByRole('button', { name: 'Erase and link this browser' });
  if (await erase.waitFor({ timeout: 3_000 }).then(() => true, () => false)) {
    await expect(desk.page.getByRole('alert')).toContainText('including anything written here that never synced');
    await erase.click();
  }
  await live(desk.page);
  await expect(fronting(desk.page)).toContainText('Kai'); // the suite's last switch, above

  // signed out again, asking for its copy to go at once (D-152)
  await stars.page.reload();
  await stars.page.getByText('Devices and data', { exact: true }).click();
  await devices.getByRole('button', { name: 'Sign out', exact: true }).click();
  await asking.getByLabel('Erase its copy now instead').check();
  const wiped = stars.page.waitForRequest((r) => r.url().endsWith('/revoke'));
  await asking.getByRole('button', { name: /^Sign out .+/ }).click();
  expect(JSON.parse((await wiped).postData() ?? 'null')).toEqual({ wipe: true });
  await expect(devices).toHaveCount(0);
});
