import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '../..');
const MAX_BYTES = 2 * 1024 * 1024;
const EXTENSIONS = new Set(['.md', '.proto', '.json', '.yaml']);
const titles = { 'SPEC.md': 'Specification', 'DESIGN_NOTES.md': 'Decisions & open questions',
  'PRIOR_ART.md': 'Prior art', 'README.md': 'Overview', 'LICENSE': 'License' };
const revision = bytes => createHash('sha256').update(bytes).digest('hex');

function isLocal(address) {
  address = address?.replace(/^::ffff:/, '');
  return address === '::1' || /^127\./.test(address) || /^10\./.test(address)
    || /^192\.168\./.test(address) || /^172\.(1[6-9]|2\d|3[01])\./.test(address);
}

export function createEditor({ root = path.join(REPO, 'spec'),
  state = path.join(REPO, '.pluralspec-editor'), token = randomBytes(32).toString('hex') } = {}) {
  root = fs.realpathSync(root);
  const hosts = new Set(['localhost', '127.0.0.1', '[::1]', ...Object.values(os.networkInterfaces())
    .flat().filter(Boolean).map(item => item.address)]);

  function files() {
    const result = [];
    function visit(directory, prefix = '') {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const relative = prefix + entry.name;
        // Never follow symlinks/junctions outside the specification tree.
        if (entry.isSymbolicLink()) continue;
        if (entry.isDirectory()) visit(path.join(directory, entry.name), relative + '/');
        else if (entry.isFile() && (EXTENSIONS.has(path.extname(entry.name)) || relative === 'LICENSE')) {
          result.push({ path: relative, title: titles[relative] || entry.name,
            kind: path.extname(entry.name).slice(1) || 'text' });
        }
      }
    }
    visit(root);
    const order = ['SPEC.md', 'DESIGN_NOTES.md', 'PRIOR_ART.md', 'README.md', 'LICENSE'];
    return result.sort((a, b) => {
      const rank = item => order.includes(item.path) ? order.indexOf(item.path) : 10;
      return rank(a) - rank(b) || a.path.localeCompare(b.path);
    });
  }

  function resolveFile(name) {
    if (typeof name !== 'string' || !files().some(file => file.path === name)) {
      throw Object.assign(new Error('This file is not in the specification.'), { status: 404 });
    }
    const filename = fs.realpathSync(path.join(root, name));
    if (!filename.startsWith(root + path.sep)) throw Object.assign(new Error('Invalid path.'), { status: 404 });
    return filename;
  }

  const assets = new Map([
    ['/', ['public/index.html', 'text/html; charset=utf-8']],
    ['/app.js', ['public/app.js', 'text/javascript; charset=utf-8']],
    ['/style.css', ['public/style.css', 'text/css; charset=utf-8']],
    ['/vendor/marked.js', ['node_modules/marked/lib/marked.umd.js', 'text/javascript; charset=utf-8']],
    ['/vendor/purify.js', ['node_modules/dompurify/dist/purify.min.js', 'text/javascript; charset=utf-8']],
  ]);

  const server = http.createServer(async (req, res) => {
    function send(status, body, type = 'application/json; charset=utf-8') {
      res.writeHead(status, { 'Content-Type': type });
      res.end(type.startsWith('application/json') ? JSON.stringify(body) : body);
    }
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'");
    try {
      const url = new URL(req.url, 'http://localhost');
      const host = new URL(`http://${req.headers.host}`).hostname;
      if (!isLocal(req.socket.remoteAddress) || !hosts.has(host)) return send(403, { error: 'Local network access only.' });
      if (req.headers.origin && req.headers.origin !== `http://${req.headers.host}`) return send(403, { error: 'Use the editor directly to make changes.' });
      if (url.pathname.startsWith('/api/')) {
        const given = Buffer.from((req.headers.authorization || '').replace(/^Bearer /, ''));
        const expected = Buffer.from(token);
        if (given.length !== expected.length || !timingSafeEqual(given, expected)) return send(401, { error: 'Open the full editor link, including its access key.' });
        if (req.method === 'GET' && url.pathname === '/api/files') return send(200, { files: files() });
        if (url.pathname !== '/api/file') return send(404, { error: 'Not found.' });
        const filename = resolveFile(url.searchParams.get('path'));
        if (req.method === 'GET') {
          const bytes = fs.readFileSync(filename);
          if (bytes.length > MAX_BYTES) return send(413, { error: 'This file is too large for the editor.' });
          return send(200, { content: bytes.toString('utf8'), revision: revision(bytes) });
        }
        if (req.method !== 'PUT') return send(405, { error: 'Method not allowed.' });
        if (!req.headers['content-type']?.startsWith('application/json')) return send(415, { error: 'Expected JSON.' });
        const chunks = [];
        let length = 0;
        for await (const chunk of req) {
          length += chunk.length;
          // JSON escaping can increase the size of a 2 MB text file sixfold.
          if (length > MAX_BYTES * 6 + 1024) return send(413, { error: 'This file is too large for the editor.' });
          chunks.push(chunk);
        }
        let input;
        try { input = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
        catch { return send(400, { error: 'Invalid JSON request.' }); }
        if (typeof input.content !== 'string' || typeof input.revision !== 'string') return send(400, { error: 'Content and revision are required.' });
        const bytes = Buffer.from(input.content);
        if (bytes.length > MAX_BYTES) return send(413, { error: 'This file is too large for the editor.' });
        // No awaits between checking the revision and replacing the file: two browser saves serialize.
        const before = fs.readFileSync(filename);
        if (revision(before) !== input.revision) return send(409, { error: 'This file changed on disk. Download your draft, then reload the file to compare.' });
        if (before.equals(bytes)) return send(200, { revision: revision(bytes) });
        const backups = path.join(state, 'backups', url.searchParams.get('path'));
        fs.mkdirSync(backups, { recursive: true });
        const stamp = new Date().toISOString().replace(/[:.]/g, '-') + '-' + randomBytes(4).toString('hex');
        fs.writeFileSync(path.join(backups, stamp + '.bak'), before, { flag: 'wx' });
        const temporary = filename + '.' + randomBytes(8).toString('hex') + '.tmp';
        try {
          const fd = fs.openSync(temporary, 'wx', fs.statSync(filename).mode);
          try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
          // Detect an external edit during backup/temp-file creation as well.
          if (revision(fs.readFileSync(filename)) !== input.revision) return send(409, { error: 'This file changed on disk. Your draft is still in the editor.' });
          fs.renameSync(temporary, filename);
        } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
        return send(200, { revision: revision(bytes) });
      }
      if (req.method !== 'GET') return send(405, { error: 'Method not allowed.' });
      const asset = assets.get(url.pathname);
      if (!asset) return send(404, { error: 'Not found.' });
      return send(200, fs.readFileSync(path.join(HERE, asset[0])), asset[1]);
    } catch (error) {
      console.error(error.message);
      return send(error.status || 500, { error: error.status ? error.message : 'Could not read or save the file. Your draft is still here; check the server log and disk space.' });
    }
  });
  return { server, token };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const argument = (name, fallback) => {
    const i = process.argv.indexOf(name);
    return i < 0 ? fallback : process.argv[i + 1];
  };
  const host = argument('--host', '0.0.0.0');
  const port = Number(argument('--port', '4783'));
  const state = path.join(REPO, '.pluralspec-editor');
  fs.mkdirSync(state, { recursive: true });
  const { server, token } = createEditor();
  server.on('error', error => { console.error(error.message); process.exitCode = 1; });
  server.listen(port, host, () => {
    const addresses = ['127.0.0.1', ...Object.values(os.networkInterfaces()).flat()
      .filter(item => item?.family === 'IPv4' && !item.internal && isLocal(item.address)).map(item => item.address)];
    const links = addresses.map(address => `http://${address}:${port}/#key=${token}`);
    fs.writeFileSync(path.join(state, 'runtime.json'), JSON.stringify({ pid: process.pid, port, links }, null, 2));
    console.log('PluralSpec editor running. Full links grant read/write access to spec/ only.');
    console.log(links.join('\n'));
  });
}
