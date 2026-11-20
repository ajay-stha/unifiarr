import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, request as httpRequest } from 'node:http';
import { once } from 'node:events';
import { mkdtemp, readFile, stat, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { mapTorrent, merge } from './qbit.mjs';

test('server.mjs proxies only allow-listed qBittorrent calls, logs in server-side and re-logs in when the session expires', async () => {
  const calls = [];
  let session = 'SID=first';
  const fake = createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    calls.push({ path: request.url, cookie: request.headers.cookie, type: request.headers['content-type'], body, key: request.headers['x-api-key'], auth: request.headers.authorization });
    if (request.url.startsWith('/api/v1/')) return response.end(request.url === '/api/v1/status' ? '{"version":"3.4.1"}' : '{}');
    if (request.url.startsWith('/api/v3/')) return response.end(request.url === '/api/v3/system/status' ? '{"version":"4.0.20"}' : '{}');
    if (/^\/(System|Items|Sessions)\//.test(request.url)) return response.end(request.url === '/System/Info' ? '{"Version":"10.10.7"}' : 'img');
    if (request.url === '/api/v2/auth/login') {
      if (body !== 'username=admin&password=s3cret') return response.end('Fails.');
      return response.writeHead(200, { 'set-cookie': `${session}; HttpOnly; path=/` }).end('Ok.');
    }
    if (request.headers.cookie !== session) return response.writeHead(403).end('Forbidden');
    const hash = 'b'.repeat(40), rid = request.url.match(/sync\/maindata\?rid=(\d+)/)?.[1];
    if (rid) return response.end(JSON.stringify(rid === '0' ? { rid: 1, full_update: true, torrents: { [hash]: { name: 'Sintel', progress: 0.5 } } } : rid === '1' ? { rid: 2, torrents: { [hash]: { progress: 0.6 } } } : { rid: 2 }));
    if (request.url === `/api/v2/torrents/files?hash=${hash}`) return response.end('[{"name":"a.mkv","size":1,"progress":1,"priority":1}]');
    if (request.url === `/api/v2/torrents/trackers?hash=${hash}`) return response.end('[]');
    response.end(request.url === '/api/v2/app/version' ? 'v5.1.4' : 'Ok.');
  }).listen(0, '127.0.0.1');
  await once(fake, 'listening');
  const dir = await mkdtemp(join(tmpdir(), 'unifiarr-')), CONFIG = join(dir, 'config.json');
  Object.assign(process.env, { CONFIG, QBIT_URL: `http://127.0.0.1:${fake.address().port}`, QBIT_USER: 'admin', QBIT_PASS: 's3cret', SONARR_URL: `http://127.0.0.1:${fake.address().port}`, SONARR_KEY: 'k1', JELLYFIN_URL: `http://127.0.0.1:${fake.address().port}`, JELLYFIN_KEY: 'k2' });
  const { server } = await import('./server.mjs');
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const api = (path, init = {}) => fetch(`${base}/api/qbittorrent${path}`, { ...init, headers: { 'x-unifiarr': '1', ...init.headers } });
  try {
    assert.equal((await fetch(`${base}/api/qbittorrent`)).status, 403, 'custom header required (CSRF)');
    const rebound = httpRequest(`${base}/api/qbittorrent`, { headers: { host: 'evil.example', 'x-unifiarr': '1' } }).end();
    assert.equal((await once(rebound, 'response'))[0].statusCode, 403, 'unknown Host rejected (DNS rebinding)');
    assert.equal((await api('/app/setPreferences', { method: 'POST', body: 'json={}' })).status, 404, 'preferences are not reachable');
    assert.deepEqual(await (await api('')).json(), { version: 'v5.1.4' });
    assert.equal(calls.filter(call => call.path === '/api/v2/auth/login').length, 1);

    session = 'SID=second'; // qBittorrent restarted: the old cookie now gets 403, the proxy logs in again once.
    const form = new FormData();
    form.append('urls', 'magnet:?xt=urn:btih:' + 'a'.repeat(40));
    form.append('torrents', new Blob([new Uint8Array([100, 101])]), 'x.torrent');
    assert.equal(await (await api('/torrents/add', { method: 'POST', body: form })).text(), 'Ok.');
    const add = calls.findLast(call => call.path === '/api/v2/torrents/add');
    assert.equal(add.cookie, 'SID=second');
    assert.match(add.type, /^multipart\/form-data; boundary=/);
    assert.match(add.body, /filename="x\.torrent"/);
    assert.equal(calls.filter(call => call.path === '/api/v2/auth/login').length, 2);

    // Live feed: one full snapshot, then only changes; nothing at all while nothing changes.
    const live = `ws://127.0.0.1:${server.address().port}/api/qbittorrent/live`;
    const refused = new WebSocket(live, { headers: { origin: 'http://evil.example' } });
    await new Promise(resolve => { refused.onerror = resolve; });
    const socket = new WebSocket(live, { headers: { origin: base } }), messages = [];
    socket.onmessage = event => messages.push(JSON.parse(event.data));
    const next = count => new Promise((resolve, reject) => { const started = Date.now(), check = setInterval(() => { if (messages.length >= count) { clearInterval(check); resolve(); } else if (Date.now() - started > 5000) { clearInterval(check); reject(new Error(`only ${messages.length} messages`)); } }, 50); });
    await next(2);
    const raw = messages.slice(0, 2).reduce(merge, {});
    assert.deepEqual(raw.torrents['b'.repeat(40)], { name: 'Sintel', progress: 0.6 });
    assert.deepEqual(messages[1], { rid: 2, torrents: { ['b'.repeat(40)]: { progress: 0.6 } } }, 'only the changed field is sent');
    socket.send(JSON.stringify({ watch: 'b'.repeat(40) }));
    await next(3);
    assert.equal(messages[2].details.files[0].name, 'a.mkv');
    await new Promise(resolve => setTimeout(resolve, 2200));
    assert.equal(messages.length, 3, 'unchanged torrents and details are not re-sent');
    socket.close();

    const service = (name, path, init = {}) => fetch(`${base}/api/${name}${path}`, { ...init, headers: { 'x-unifiarr': '1', ...init.headers } });
    assert.deepEqual(await (await service('sonarr', '')).json(), { version: '4.0.20' });
    assert.equal(calls.findLast(call => call.path === '/api/v3/system/status').key, 'k1', 'API key added server-side');
    const command = name => service('sonarr', '/command', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name }) });
    assert.equal((await command('EpisodeSearch')).status, 200);
    assert.equal((await command('Backup')).status, 403, 'only search/refresh commands');
    assert.equal((await service('sonarr', '/config/host')).status, 404, 'settings are not reachable');
    assert.equal((await service('radarr', '/movie')).status, 404, 'unset service');
    assert.equal((await service('sonarr', '/calendar?start=2024-01-01')).status, 200);
    assert.equal((await service('sonarr', '/health')).status, 200);
    assert.equal((await service('sonarr', '/diskspace')).status, 200);
    assert.equal((await service('constructor', '')).status, 404);
    assert.deepEqual(await (await service('jellyfin', '')).json(), { version: '10.10.7' });
    assert.equal(calls.findLast(call => call.path === '/System/Info').auth, 'MediaBrowser Token="k2"');
    assert.equal((await service('jellyfin', '/System/Configuration')).status, 404);
    assert.equal((await fetch(`${base}/api/jellyfin/Items/${'a'.repeat(32)}/Images/Primary?maxHeight=480`)).status, 200, 'posters load without the CSRF header');
    assert.equal((await fetch(`${base}/api/jellyfin/Sessions/${'a'.repeat(32)}/Playing/Stop`, { method: 'POST' })).status, 403);
    assert.equal((await fetch(`${base}/index.html`)).status, 200);
    assert.equal((await fetch(`${base}/%2e%2e/%2e%2e/etc/passwd`)).status, 404);
    assert.equal((await fetch(`${base}/.unifiarr.json`)).status, 404, 'saved config is never served');

    const upstreamUrl = `http://127.0.0.1:${fake.address().port}`, key = 'a'.repeat(32);
    const put = (name, settings) => service('config', `/${name}`, { method: 'PUT', body: JSON.stringify(settings) });
    const shown = await (await service('config', '')).json();
    assert.deepEqual(shown.qbittorrent, { url: upstreamUrl, user: 'admin', hasSecret: true });
    assert.doesNotMatch(JSON.stringify(shown), /s3cret|k1|k2/, 'secrets never reach the browser');
    assert.equal((await put('radarr', { url: 'file:///etc/passwd', key })).status, 400);
    assert.equal((await put('radarr', { url: upstreamUrl })).status, 400, 'API key required');
    assert.deepEqual(await (await put('radarr', { url: `${upstreamUrl}/`, key })).json(), { version: '4.0.20' });
    assert.equal(calls.findLast(call => call.path === '/api/v3/system/status').key, key, 'connection tested before saving');
    assert.equal((await service('radarr', '/movie')).status, 200, 'saved service is live without a restart');
    assert.equal((await put('sonarr', { url: `http://localhost:${fake.address().port}` })).status, 400, 'a new address needs the key again');
    assert.equal((await put('sonarr', { url: upstreamUrl })).status, 200, 'same address keeps the saved key');
    assert.equal(JSON.parse(await readFile(CONFIG, 'utf8')).sonarr.key, 'k1');
    assert.equal((await stat(CONFIG)).mode & 0o777, 0o600);
    assert.equal((await service('config', '/radarr', { method: 'DELETE' })).status, 204);
    assert.equal((await service('radarr', '')).status, 404);
    assert.equal((await put('constructor', { url: upstreamUrl, key })).status, 404);
    const seerrKey = 'MTc1NjQ5ODI0Njk5NWQ2YjQ+/2E=';
    assert.deepEqual(await (await put('seerr', { url: upstreamUrl, key: seerrKey })).json(), { version: '3.4.1' }, 'base64 Seerr keys are accepted');
    assert.equal(calls.findLast(call => call.path === '/api/v1/status').key, seerrKey);
    assert.equal((await service('seerr', '/discover/trending?page=1')).status, 200);
    assert.equal((await service('seerr', '/request/7/approve', { method: 'POST' })).status, 200);
    assert.equal((await service('seerr', '/request/7', { method: 'DELETE' })).status, 200);
    assert.equal((await service('seerr', '/settings/main')).status, 404, 'Seerr settings are not reachable');
    assert.equal((await put('seerr', { url: upstreamUrl, key: 'bad\r\nkey' })).status, 400);
  } finally {
    server.close();
    fake.close();
    await rm(dir, { recursive: true });
  }
});

test('qBittorrent torrent data maps onto the Downloads page model', () => {
  const now = 1_700_000_600_000;
  const base = { name: 'Sintel', category: 'movies', tags: 'kids, 4k', progress: 0.4567, size: 1000, completed: 456, ratio: 0.25, uploaded: 114, save_path: '/data', force_start: false, seq_dl: true, f_l_piece_prio: false, dl_limit: 2048, up_limit: -1, dlspeed: 5, upspeed: 1, eta: 8640000, num_seeds: 2, num_complete: 9, num_leechs: 1, num_incomplete: 4, added_on: 1_700_000_000, priority: 3, trackers_count: 2 };
  const item = mapTorrent('abc', { ...base, state: 'stoppedDL' }, now);
  assert.equal(item.state, 'paused');
  assert.equal(item.label, 'Stopped');
  assert.equal(item.progress, 45.6);
  assert.deepEqual(item.tags, ['kids', '4k']);
  assert.equal(item.eta, null);
  assert.deepEqual([item.dlLimit, item.upLimit, item.queue, item.added], [2, 0, 3, 10]);
  assert.equal(mapTorrent('abc', { ...base, state: 'pausedDL' }, now).state, 'paused', 'qBittorrent 4.x name');
  assert.equal(mapTorrent('abc', { ...base, state: 'forcedUP', progress: 1, priority: 0 }, now).label, '[F] Seeding');
  assert.equal(mapTorrent('abc', { ...base, state: 'forcedUP', progress: 1, priority: 0 }, now).state, 'seeding');
  assert.equal(mapTorrent('abc', { ...base, state: 'queuedDL' }, now).state, 'queued');
  assert.equal(mapTorrent('abc', { ...base, state: 'missingFiles' }, now).tone, 'error');
  assert.equal(mapTorrent('abc', { ...base, state: 'downloading', progress: 0.99999 }, now).progress, 99.9, 'never shows 100% early');
});
