/* global marked, DOMPurify */
const $ = id => document.getElementById(id);
const incoming = new URLSearchParams(location.hash.slice(1));
let key = incoming.get('key');
try {
  if (key) sessionStorage.setItem('pluralspec-key', key);
  else key = sessionStorage.getItem('pluralspec-key');
} catch { /* The link still works when browser storage is disabled. */ }
if (incoming.has('key')) history.replaceState(null, '', location.pathname);

let files = [], current = null, original = '', revision = '', lineEnding = '\n', editing = false;
let busy = false, saving = false, headings = [], activeHeading = null, renderTimer;
const draftKey = name => 'pluralspec-draft:' + name;
const dirty = () => current && $('source').value !== original;
const message = (text, error = false) => {
  $('message').textContent = text;
  $('message').classList.toggle('error', error);
};

async function api(url, options = {}) {
  const response = await fetch(url, { ...options,
    headers: { Authorization: `Bearer ${key || ''}`, 'Content-Type': 'application/json', ...options.headers } });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error || 'Could not reach the editor. Please try again.');
  return body;
}

function persistDraft() {
  if (!current) return;
  try {
    if (dirty()) localStorage.setItem(draftKey(current.path), JSON.stringify({ content: $('source').value, base: revision }));
    else localStorage.removeItem(draftKey(current.path));
    return true;
  } catch { return false; }
}

function updateState() {
  $('save').disabled = !dirty() || busy || saving;
  $('read').disabled = $('edit').disabled = busy || !current;
  $('reload').disabled = busy || saving;
  $('download').hidden = !dirty();
  document.title = `${dirty() ? '• ' : ''}${current?.title || 'PluralSpec'} — PluralSpec`;
}

function setMode(value) {
  if (!current || busy) return;
  editing = value;
  document.body.classList.toggle('editing', value);
  $('source').closest('section').hidden = !value;
  $('save').hidden = !value;
  $('mobile-views').hidden = !value;
  $('read').setAttribute('aria-pressed', String(!value));
  $('edit').setAttribute('aria-pressed', String(value));
  if (value) {
    if (activeHeading) focusSource(activeHeading.offset);
    else $('source').focus({ preventScroll: true });
  }
}

function focusSource(offset) {
  const source = $('source');
  source.focus({ preventScroll: true });
  source.setSelectionRange(offset, offset);
  const row = source.value.slice(0, offset).split('\n').length - 1;
  source.scrollTop = row * parseFloat(getComputedStyle(source).lineHeight);
}

function slug(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, '').replace(/\s+/g, '-');
}

function render() {
  if (!current) return;
  const preview = $('preview');
  const text = $('source').value;
  if (current.kind === 'md') {
    preview.innerHTML = DOMPurify.sanitize(marked.parse(text), {
      USE_PROFILES: { html: true }, FORBID_TAGS: ['form', 'input', 'button', 'select', 'textarea', 'style'],
      FORBID_ATTR: ['style'],
    });
  } else {
    const pre = document.createElement('pre'), code = document.createElement('code');
    code.textContent = text; pre.append(code); preview.replaceChildren(pre);
  }
  // Match the outline to actual rendered headings, including repeated names.
  const sourceHeadings = [];
  let offset = 0, fence = null;
  for (const line of text.split('\n')) {
    const marker = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (marker) {
      if (!fence) fence = marker[1];
      else if (marker[1][0] === fence[0] && marker[1].length >= fence.length) fence = null;
    } else if (!fence && /^#{1,6}\s/.test(line)) sourceHeadings.push(offset);
    offset += line.length + 1;
  }
  const used = new Map();
  headings = [...preview.querySelectorAll('h1,h2,h3,h4,h5,h6')].map((heading, index) => {
    const name = slug(heading.textContent);
    const duplicate = used.get(name) || 0;
    used.set(name, duplicate + 1);
    heading.id = name + (duplicate ? '-' + duplicate : '');
    return { element: heading, id: heading.id, text: heading.textContent,
      level: Number(heading.tagName[1]), offset: sourceHeadings[index] || 0 };
  });
  const outline = $('outline');
  outline.replaceChildren();
  for (const heading of headings.filter(item => item.level === 2 || item.level === 3)) {
    const a = document.createElement('a');
    a.href = '#' + heading.id; a.textContent = heading.text;
    if (heading.level > 2) a.className = 'subheading';
    a.addEventListener('click', event => {
      event.preventDefault(); activeHeading = heading;
      if (editing && !document.body.classList.contains('preview-mobile')) focusSource(heading.offset);
      else heading.element.scrollIntoView({ block: 'start' });
      setOutlineCurrent(heading.id);
      if (innerWidth <= 800) toggleOutline(false);
    });
    outline.append(a);
  }
  if (!outline.childElementCount) {
    const note = document.createElement('p'); note.textContent = 'No sections in this file.'; note.className = 'sidebar-note'; outline.append(note);
  }
  preview.querySelectorAll('table').forEach(table => {
    const wrapper = document.createElement('div'); wrapper.className = 'table-scroll';
    wrapper.tabIndex = 0; wrapper.setAttribute('role', 'region'); wrapper.setAttribute('aria-label', 'Scrollable table');
    table.replaceWith(wrapper); wrapper.append(table);
  });
  preview.querySelectorAll('a').forEach(a => a.addEventListener('click', event => {
    const href = a.getAttribute('href');
    if (!href) return;
    if (/^https?:\/\//i.test(href)) { a.target = '_blank'; a.rel = 'noopener noreferrer'; return; }
    if (/^[a-z]+:/i.test(href)) { event.preventDefault(); return; }
    event.preventDefault();
    const target = new URL(href, 'http://spec/' + current.path);
    const name = decodeURIComponent(target.pathname.slice(1));
    const id = decodeURIComponent(target.hash.slice(1));
    if (name === current.path) jumpTo(id);
    else if (files.some(file => file.path === name)) openFile(name, id);
    else message('That reference is outside the available specification files.');
  }));
}

function jumpTo(id) {
  const heading = headings.find(item => item.id === id);
  if (heading) { activeHeading = heading; heading.element.scrollIntoView({ block: 'start' }); setOutlineCurrent(id); }
}
function setOutlineCurrent(id) {
  $('outline').querySelectorAll('a').forEach(a => {
    if (a.getAttribute('href') === '#' + id) a.setAttribute('aria-current', 'location');
    else a.removeAttribute('aria-current');
  });
}
function toggleOutline(value) {
  $('outline').classList.toggle('expanded', value);
  $('outline-toggle').setAttribute('aria-expanded', String(value));
  $('outline-toggle').textContent = value ? 'Hide outline' : 'Show outline';
}

async function openFile(name, anchor = '', reloading = false) {
  if (busy || saving) return;
  if (dirty() && !confirm(reloading
    ? 'Reload the saved file and discard this unsaved draft? Download it first if you want to keep a copy.'
    : 'Leave this unsaved draft? It will be kept in this browser for when you return.')) {
    $('files').value = current.path; return;
  }
  const retained = persistDraft();
  if (dirty() && !reloading && !retained) {
    $('files').value = current.path;
    message('This browser could not keep your draft. Save it, or download a copy and reload this file before switching.', true);
    return;
  }
  busy = true; $('files').disabled = true; $('source').disabled = true; updateState();
  message('Loading file…');
  try {
    const data = await api('/api/file?path=' + encodeURIComponent(name));
    current = files.find(file => file.path === name);
    lineEnding = data.content.includes('\r\n') ? '\r\n' : '\n';
    original = data.content.replace(/\r\n?/g, '\n'); revision = data.revision;
    $('source').value = data.content;
    $('source').setSelectionRange(0, 0);
    let restored = false, conflicted = false;
    try {
      if (reloading) localStorage.removeItem(draftKey(name));
      else {
        const draft = JSON.parse(localStorage.getItem(draftKey(name)) || 'null');
        if (draft && typeof draft.content === 'string' && draft.content !== original) {
          $('source').value = draft.content; restored = true;
          if (draft.base !== revision) { conflicted = true; revision = draft.base; }
        }
      }
    } catch { /* A broken draft never prevents reading the saved file. */ }
    $('files').value = name; $('filepath').textContent = 'spec/' + name;
    $('source-label').textContent = current.kind === 'md' ? 'Markdown source' : 'Source';
    activeHeading = null; render();
    busy = false;
    setMode(restored || editing);
    $('source').scrollTop = 0;
    window.scrollTo(0, 0);
    if (anchor) jumpTo(anchor);
    message(conflicted ? 'Draft restored, but the saved file has changed. Download your draft before reloading to compare.'
      : restored ? 'Unsaved draft restored from this browser.' : 'Saved file · backups kept on this PC', conflicted);
  } catch (error) {
    if (current) $('files').value = current.path;
    message(error.message, true);
  } finally {
    busy = false; $('files').disabled = !files.length; $('source').disabled = !current; updateState();
  }
}

async function save() {
  if (!dirty() || saving || busy) return;
  saving = true; $('files').disabled = true; updateState(); message('Saving changes…');
  const content = $('source').value;
  try {
    const result = await api('/api/file?path=' + encodeURIComponent(current.path), {
      method: 'PUT', body: JSON.stringify({ content: content.replace(/\n/g, lineEnding), revision }),
    });
    original = content; revision = result.revision; persistDraft();
    message(dirty() ? 'Saved. Your newer changes are still a draft.' : 'Saved to spec/' + current.path + ' · previous version backed up');
  } catch (error) { persistDraft(); message(error.message, true); }
  finally { saving = false; $('files').disabled = false; updateState(); }
}

$('read').addEventListener('click', () => setMode(false));
$('edit').addEventListener('click', () => setMode(true));
$('save').addEventListener('click', save);
$('reload').addEventListener('click', () => current && openFile(current.path, '', true));
$('home').addEventListener('click', event => { event.preventDefault(); openFile('SPEC.md'); });
$('files').addEventListener('change', () => openFile($('files').value));
$('outline-toggle').addEventListener('click', () => toggleOutline(!$('outline').classList.contains('expanded')));
$('source-tab').addEventListener('click', () => mobileView(false));
$('preview-tab').addEventListener('click', () => mobileView(true));
function mobileView(preview) {
  document.body.classList.toggle('preview-mobile', preview);
  $('source-tab').setAttribute('aria-pressed', String(!preview));
  $('preview-tab').setAttribute('aria-pressed', String(preview));
}
$('download').addEventListener('click', () => {
  const url = URL.createObjectURL(new Blob([$('source').value], { type: 'text/plain;charset=utf-8' }));
  const a = document.createElement('a'); a.href = url; a.download = current.path.split('/').pop(); a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
$('source').addEventListener('input', () => {
  const stored = persistDraft(); updateState();
  message(stored ? 'Unsaved changes · draft kept in this browser' : 'Unsaved changes · browser storage is unavailable; save or download before leaving', !stored);
  clearTimeout(renderTimer); renderTimer = setTimeout(render, 180);
});
document.addEventListener('keydown', event => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') { event.preventDefault(); save(); }
});
window.addEventListener('beforeunload', event => { if (dirty()) { persistDraft(); event.preventDefault(); event.returnValue = ''; } });
let scrolling = false;
window.addEventListener('scroll', () => {
  if (scrolling || editing) return;
  scrolling = true;
  requestAnimationFrame(() => {
    scrolling = false;
    const section = headings.filter(item => item.level <= 3).findLast(item => item.element.getBoundingClientRect().top <= 110);
    if (section) { activeHeading = section; setOutlineCurrent(section.id); }
  });
}, { passive: true });

(async () => {
  updateState();
  try {
    files = (await api('/api/files')).files;
    $('files').replaceChildren();
    for (const file of files) {
      const option = document.createElement('option'); option.value = file.path;
      option.textContent = file.path.includes('/') ? file.path : file.title;
      $('files').append(option);
    }
    if (!files.length) { message('No specification files found in spec/.', true); return; }
    await openFile(files.some(file => file.path === 'SPEC.md') ? 'SPEC.md' : files[0].path);
  } catch (error) { message(error.message, true); }
})();
