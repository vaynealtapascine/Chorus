import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { createEditor } from './server.mjs';

async function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pluralspec-editor-'));
  const root = path.join(directory, 'spec'), state = path.join(directory, 'state');
  fs.mkdirSync(root);
  fs.writeFileSync(path.join(root, 'SPEC.md'), '# Test\r\n\r\nOriginal 🌱\r\n');
  fs.writeFileSync(path.join(directory, 'secret.txt'), 'outside');
  const { server, token } = createEditor({ root, state });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  t.after(async () => {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const request = (route, options = {}) => fetch(origin + route, {
    ...options, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...options.headers },
  });
  const read = async () => (await request('/api/file?path=SPEC.md')).json();
  const save = (content, revision) => request('/api/file?path=SPEC.md', {
    method: 'PUT', body: JSON.stringify({ content, revision }),
  });
  return { root, state, request, read, save, origin };
}

test('saves the exact draft and backs up original bytes, including CRLF and Unicode', async t => {
  const f = await fixture(t), before = await f.read();
  const draft = '# Updated\n\nA new draft 🌷\n';
  const response = await f.save(draft, before.revision);
  assert.equal(response.status, 200);
  assert.equal(fs.readFileSync(path.join(f.root, 'SPEC.md'), 'utf8'), draft);
  const backupDirectory = path.join(f.state, 'backups', 'SPEC.md');
  const backups = fs.readdirSync(backupDirectory);
  assert.equal(backups.length, 1);
  assert.equal(fs.readFileSync(path.join(backupDirectory, backups[0]), 'utf8'), before.content);
  assert.notEqual((await response.json()).revision, before.revision);
});

test('concurrent browser saves and external edits never silently overwrite a newer revision', async t => {
  const f = await fixture(t), before = await f.read();
  const responses = await Promise.all([f.save('First draft', before.revision), f.save('Second draft', before.revision)]);
  assert.deepEqual(responses.map(r => r.status).sort(), [200, 409]);
  const latest = await f.read();
  fs.writeFileSync(path.join(f.root, 'SPEC.md'), 'Changed by another editor');
  assert.equal((await f.save('Old browser draft', latest.revision)).status, 409);
  assert.equal(fs.readFileSync(path.join(f.root, 'SPEC.md'), 'utf8'), 'Changed by another editor');
});

test('unchanged saves do not rewrite the file or create a backup', async t => {
  const f = await fixture(t), before = await f.read();
  assert.equal((await f.save(before.content, before.revision)).status, 200);
  assert.equal(fs.existsSync(f.state), false);
});

test('requires the key, restricts origins/hosts, and never serves arbitrary disk paths', async t => {
  const f = await fixture(t);
  assert.equal((await fetch(f.origin + '/api/files')).status, 401);
  assert.equal((await f.request('/api/files', { headers: { Authorization: 'Bearer wrong' } })).status, 401);
  assert.equal((await f.request('/api/files', { headers: { Origin: 'http://evil.invalid' } })).status, 403);
  // fetch normalizes Host; raw HTTP exercises the DNS-rebinding boundary.
  const hostStatus = await new Promise((resolve, reject) => {
    http.get(f.origin + '/', { headers: { Host: 'evil.invalid' } }, response => {
      response.resume(); resolve(response.statusCode);
    }).on('error', reject);
  });
  assert.equal(hostStatus, 403);
  for (const name of ['../secret.txt', '..\\secret.txt', 'missing.md', 'C:\\Windows\\win.ini']) {
    assert.equal((await f.request('/api/file?path=' + encodeURIComponent(name))).status, 404);
  }
  assert.equal((await f.request('/server.mjs')).status, 404);
  assert.equal((await f.request('/')).status, 200);
  assert.equal((await f.request('/vendor/marked.js')).status, 200);
  const response = await f.request('/api/files');
  assert.deepEqual((await response.json()).files.map(file => file.path), ['SPEC.md']);
  assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
});

test('refuses malformed and oversized writes without changing the file', async t => {
  const f = await fixture(t), before = await f.read();
  assert.equal((await f.request('/api/file?path=SPEC.md', { method: 'PUT', body: '{bad' })).status, 400);
  assert.equal((await f.request('/api/file?path=SPEC.md', { method: 'PUT', body: '{}' })).status, 400);
  assert.equal((await f.save('x'.repeat(2 * 1024 * 1024 + 1), before.revision)).status, 413);
  assert.equal((await f.read()).content, before.content);
});

test('symlinks are excluded from the editable file list', async t => {
  const f = await fixture(t);
  const outside = path.join(path.dirname(f.root), 'secret.txt');
  try { fs.symlinkSync(outside, path.join(f.root, 'secret.md')); }
  catch (error) { if (error.code === 'EPERM') return t.skip('Windows symlink privilege unavailable'); throw error; }
  assert.equal((await f.request('/api/file?path=secret.md')).status, 404);
});
