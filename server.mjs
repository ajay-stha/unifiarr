// Unifiarr server: serves the app and proxies allow-listed slices of the qBittorrent, Sonarr, Radarr, Jellyfin and Seerr APIs.
// Services are set up in the app (Services page) and saved to CONFIG (default .unifiarr.json, mode 0600, never served).
// Logins and API keys stay on this server; the browser never sees them.
//
// Env vars seed the config on first run: QBIT_URL/QBIT_USER/QBIT_PASS, SONARR_URL/SONARR_KEY, RADARR_URL/RADARR_KEY,
// JELLYFIN_URL/JELLYFIN_KEY, SEERR_URL/SEERR_KEY. PORT (default 4180), HOST (default 127.0.0.1), ALLOWED_HOSTS (comma-separated hostnames).
// There is no Unifiarr login: keep HOST on loopback, or put it behind your reverse proxy's authentication.
import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { extname, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { merge } from './qbit.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const { PORT = '4180', HOST = '127.0.0.1', CONFIG = join(root, '.unifiarr.json'), ...env } = process.env;
const allowedHosts = new Set(['localhost', '127.0.0.1', '[::1]', ...(process.env.ALLOWED_HOSTS ?? '').split(',').map(host => host.trim().toLowerCase()).filter(Boolean)]);
const types = { '.html': 'text/html; charset=utf-8', '.mjs': 'text/javascript', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.txt': 'text/plain; charset=utf-8' };
const MAX_BODY = 25 * 1024 ** 2;

const exact = names => [new RegExp(`^(?:${names.join('|')})$`)];
const id = '[a-f0-9]{32}';
// Only what the app uses. qBittorrent preferences/shutdown/auth, arr settings/backups and Jellyfin config/plugins are not reachable.
const arr = {
  GET: [/^system\/status$/, /^(health|diskspace)$/, /^(series|movie)(\/\d+)?$/, /^(episode|queue|history|release|qualityprofile|calendar)$/, /^wanted\/(missing|cutoff)$/],
  POST: [/^release$/, /^command$/],
  PUT: [/^(series|movie)\/\d+$/, /^episode\/monitor$/],
  DELETE: [/^(series|movie|queue)\/\d+$/],
};
// Commands are checked by name too: /command can otherwise trigger backups, updates or restarts.
const commands = new Set(['EpisodeSearch', 'SeasonSearch', 'SeriesSearch', 'MissingEpisodeSearch', 'CutoffUnmetEpisodeSearch', 'RefreshSeries', 'MoviesSearch', 'MissingMoviesSearch', 'CutOffUnmetMoviesSearch', 'RefreshMovie']);
const names = { qbittorrent: 'qBittorrent', sonarr: 'Sonarr', radarr: 'Radarr', jellyfin: 'Jellyfin', seerr: 'Seerr' };
const qbit = {
  GET: exact(['app/version', 'app/webapiVersion', 'app/defaultSavePath', 'sync/maindata', 'torrents/files', 'torrents/trackers', 'sync/torrentPeers', 'log/main']),
  POST: exact(['start', 'stop', 'resume', 'pause', 'setForceStart', 'recheck', 'reannounce', 'topPrio', 'increasePrio', 'decreasePrio', 'bottomPrio', 'filePrio', 'rename', 'setLocation', 'setCategory', 'addTags', 'removeTags', 'setDownloadLimit', 'setUploadLimit', 'toggleSequentialDownload', 'toggleFirstLastPiecePrio', 'delete', 'add', 'createCategory'].map(name => `torrents/${name}`).concat('transfer/toggleSpeedLimitsMode')),
};
const jellyfin = {
  GET: [/^(System\/Info|Library\/VirtualFolders|Items|Users|Sessions|ScheduledTasks|System\/ActivityLog\/Entries)$/, new RegExp(`^Items/${id}/Images/Primary$`)],
  POST: [/^(Library\/Refresh|Users\/New)$/, ...[`Items/${id}/Refresh`, `ScheduledTasks/Running/${id}`, `Sessions/${id}/Playing/(Pause|Unpause|Stop)`, `Sessions/${id}/Message`, `Users/${id}/Policy`].map(path => new RegExp(`^${path}$`))],
  DELETE: [new RegExp(`^ScheduledTasks/Running/${id}$`), new RegExp(`^Users/${id}$`)],
};
// Seerr: browse, request, approve/decline/delete requests. No settings, users or jobs.
const seerr = {
  GET: [/^(status|request|search|discover\/(trending|movies|tv)|(movie|tv)\/\d+|service\/(radarr|sonarr)(\/\d+)?)$/],
  POST: [/^request(\/\d+\/(approve|decline))?$/],
  DELETE: [/^request\/\d+$/],
};
const build = (service, { url, user = '', pass = '', key = '' }) => ({
  qbittorrent: { name: names.qbittorrent, url, user, pass, base: `${url}/api/v2/`, status: ['app/version', text => text], allow: qbit },
  sonarr: { name: names.sonarr, base: `${url}/api/v3/`, headers: { 'x-api-key': key }, status: ['system/status', text => JSON.parse(text).version], allow: arr },
  radarr: { name: names.radarr, base: `${url}/api/v3/`, headers: { 'x-api-key': key }, status: ['system/status', text => JSON.parse(text).version], allow: arr },
  jellyfin: { name: names.jellyfin, base: `${url}/`, headers: { authorization: `MediaBrowser Token="${key}"` }, status: ['System/Info', text => JSON.parse(text).Version], allow: jellyfin },
  seerr: { name: names.seerr, base: `${url}/api/v1/`, headers: { 'x-api-key': key }, status: ['status', text => JSON.parse(text).version], allow: seerr },
})[service];

const seed = Object.fromEntries(Object.entries({
  qbittorrent: { url: env.QBIT_URL, user: env.QBIT_USER, pass: env.QBIT_PASS },
  sonarr: { url: env.SONARR_URL, key: env.SONARR_KEY }, radarr: { url: env.RADARR_URL, key: env.RADARR_KEY }, jellyfin: { url: env.JELLYFIN_URL, key: env.JELLYFIN_KEY }, seerr: { url: env.SEERR_URL, key: env.SEERR_KEY },
}).filter(([, value]) => value.url));
let config;
try { config = JSON.parse(readFileSync(CONFIG, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; config = seed; }
const upstreams = {};
const rebuild = () => { for (const service in names) upstreams[service] = config[service] && build(service, config[service]); };
rebuild();

// Settings from the browser. A changed URL needs the secret re-entered, so a stored key can't be redirected to another host.
export function settings(service, input, saved = config[service]) {
  if (!Object.hasOwn(names, service)) throw Object.assign(new Error('Unknown service.'), { status: 404 });
  let url;
  try { url = new URL(String(input?.url ?? '').trim()); } catch { url = null; }
  if (!url || !['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw Object.assign(new Error('Enter an http(s) address like http://192.168.1.10:8989.'), { status: 400 });
  const clean = url.href.replace(/\/+$/, ''), same = clean === saved?.url;
  const secret = field => typeof input[field] === 'string' && input[field] !== '' ? input[field] : same ? saved[field] ?? '' : '';
  if (service === 'qbittorrent') return { url: clean, user: String(input.user ?? '').trim(), pass: secret('pass') };
  const key = secret('key').trim();
  // Seerr keys are base64, so + / = are allowed; nothing that could break out of a header.
  if (!/^[\w+/=-]{1,256}$/.test(key)) throw Object.assign(new Error(same || !saved ? 'Enter the API key.' : 'The address changed, so enter the API key again.'), { status: 400 });
  return { url: clean, key };
}
const publicConfig = () => Object.fromEntries(Object.entries(config).map(([service, { url, user, pass, key }]) => [service, { url, ...(service === 'qbittorrent' && { user }), hasSecret: !!(pass || key) }]));
const save = () => writeFile(CONFIG, JSON.stringify(config, null, 2), { mode: 0o600 });
const json = data => JSON.stringify(data);

async function version(target) {
  const status = await upstream(target, target.status[0], { method: 'GET', headers: {} });
  if (!status.ok) throw Object.assign(new Error(`${target.name} returned ${status.status}${status.status === 401 ? ': check the API key' : ''}.`), { status: 502 });
  return target.status[1](await status.text());
}

// Posters load through <img>, which can't send the CSRF header; they are read-only and still Host-checked.
const image = new RegExp(`^/api/jellyfin/Items/${id}/Images/Primary$`);

async function login(target) {
  const response = await fetch(`${target.url}/api/v2/auth/login`, { method: 'POST', body: new URLSearchParams({ username: target.user, password: target.pass }) });
  const cookie = response.headers.getSetCookie().find(value => value.includes('='));
  if (!response.ok || !cookie) throw Object.assign(new Error(response.status === 403 ? 'qBittorrent banned this IP after failed logins.' : 'qBittorrent rejected the username or password.'), { status: 502 });
  target.sid = cookie.split(';')[0];
}

async function upstream(target, path, init, retry = true) {
  const { base, headers } = target;
  if (target.name !== names.qbittorrent) return fetch(base + path, { ...init, headers: { ...init.headers, ...headers } });
  if (target.user && !target.sid) await login(target);
  const response = await fetch(base + path, { ...init, headers: { ...init.headers, ...(target.sid && { cookie: target.sid }) } });
  if (response.status === 403 && target.user && retry) { target.sid = ''; return upstream(target, path, init, false); }
  return response;
}

async function body(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY) throw Object.assign(new Error('Upload is too large.'), { status: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

const send = (response, status, text, type = 'text/plain; charset=utf-8') => response.writeHead(status, { 'content-type': type, 'x-content-type-options': 'nosniff' }).end(text);

export const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://unifiarr');
  let label = 'the service';
  try {
    if (url.pathname.startsWith('/api/')) {
      // DNS-rebinding and CSRF guards: known Host only, and a custom header other sites can't send without CORS.
      const host = (request.headers.host ?? '').toLowerCase().replace(/:\d+$/, '');
      const header = request.headers['x-unifiarr'] === '1' || (request.method === 'GET' && image.test(url.pathname));
      if (!allowedHosts.has(host) || !header) return send(response, 403, 'Forbidden');
      const [, service, path = ''] = url.pathname.match(/^\/api\/([a-z]+)(?:\/(.*))?$/) ?? [];
      if (service === 'config') {
        if (!path && request.method === 'GET') return send(response, 200, json(publicConfig()), 'application/json');
        if (request.method === 'PUT') {
          let input;
          try { input = JSON.parse(await body(request)); } catch { return send(response, 400, 'Send the settings as JSON.'); }
          const next = settings(path, input), candidate = build(path, next);
          label = candidate.name;
          const found = await version(candidate);
          config = { ...config, [path]: next };
          await save();
          upstreams[path] = candidate;
          return send(response, 200, json({ version: found }), 'application/json');
        }
        if (request.method === 'DELETE' && Object.hasOwn(names, path)) {
          const { [path]: removed, ...rest } = config;
          config = rest;
          await save();
          rebuild();
          return send(response, 204, '');
        }
        return send(response, 404, 'Not available.');
      }
      const target = Object.hasOwn(upstreams, service) && upstreams[service];
      if (!target) return send(response, 404, `${names[service] ?? 'This service'} is not configured.`);
      label = target.name;
      if (!path) return send(response, 200, json({ version: await version(target) }), 'application/json');
      if (!target.allow[request.method]?.some(pattern => pattern.test(path))) return send(response, 404, 'Not available.');
      const init = { method: request.method, headers: {} };
      if (request.method !== 'GET') {
        init.body = await body(request);
        if (request.headers['content-type']) init.headers['content-type'] = request.headers['content-type'];
        if (path === 'command' && !commands.has((() => { try { return JSON.parse(init.body).name; } catch { return ''; } })())) return send(response, 403, 'This command is not allowed.');
      }
      const reply = await upstream(target, path + url.search, init);
      return send(response, reply.status, Buffer.from(await reply.arrayBuffer()), reply.headers.get('content-type') ?? 'text/plain; charset=utf-8');
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') return send(response, 405, 'Method not allowed');
    const file = join(root, decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(root) || file.split(sep).some(part => part.startsWith('.'))) return send(response, 404, 'Not found');
    return send(response, 200, await readFile(file), types[extname(file)] ?? 'application/octet-stream');
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'EISDIR') return send(response, 404, 'Not found');
    if (!response.headersSent) send(response, error.status ?? 502, error.status ? error.message : `Could not reach ${label}: ${error.message}`);
  }
});

// Live torrents over a WebSocket (/api/qbittorrent/live). qBittorrent has no push API, so the server runs one
// incremental sync/maindata?rid=N loop for every open tab and forwards only the changes it reports. A tab can also
// {"watch": hash} a torrent to get its files and trackers, sent only when they change.
const sockets = new Set();
let feed = { raw: null, rid: 0, target: null }, feeding = false;

function push(socket, text) {
  const body = Buffer.from(text), n = body.length;
  const length = n < 126 ? [n] : n < 65536 ? [126, n >> 8, n & 255] : [127, 0, 0, 0, 0, n >>> 24, (n >> 16) & 255, (n >> 8) & 255, n & 255];
  if (socket.writable) socket.write(Buffer.concat([Buffer.from([0x81, ...length]), body]));
}

// Browsers send small, masked, unfragmented frames; anything else closes the socket.
function frames(socket, onText) {
  let buffer = Buffer.alloc(0);
  socket.on('data', chunk => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 2) {
      const opcode = buffer[0] & 15, short = buffer[1] & 127, start = short === 126 ? 4 : 2;
      if (!(buffer[1] & 128) || short === 127 || !(buffer[0] & 128)) return socket.destroy();
      if (buffer.length < start + 4) return;
      const length = short === 126 ? buffer.readUInt16BE(2) : short;
      if (length > 4096) return socket.destroy();
      if (buffer.length < start + 4 + length) return;
      const mask = buffer.subarray(start, start + 4), payload = buffer.subarray(start + 4, start + 4 + length).map((byte, index) => byte ^ mask[index % 4]);
      buffer = buffer.subarray(start + 4 + length);
      if (opcode === 8) return socket.end(Buffer.from([0x88, 0]));
      if (opcode === 1) onText(payload.toString());
    }
  });
}

async function pump() {
  feeding = true;
  while (sockets.size) {
    const target = upstreams.qbittorrent;
    if (target !== feed.target) feed = { raw: null, rid: 0, target };
    if (target) try {
      const reply = await upstream(target, `sync/maindata?rid=${feed.rid}`, { method: 'GET', headers: {} });
      if (!reply.ok) throw new Error(`qBittorrent returned ${reply.status}`);
      const delta = await reply.json();
      if (target === feed.target) {
        feed.rid = delta.rid;
        feed.raw = merge(feed.raw ?? {}, delta);
        if (Object.keys(delta).some(key => key !== 'rid')) for (const socket of sockets) push(socket, json(delta));
      }
      for (const hash of new Set([...sockets].map(socket => socket.watch).filter(Boolean))) {
        const [files, trackers] = await Promise.all(['files', 'trackers'].map(async kind => (await upstream(target, `torrents/${kind}?hash=${hash}`, { method: 'GET', headers: {} })).json()));
        const message = json({ details: { hash, files, trackers } });
        for (const socket of sockets) if (socket.watch === hash && socket.sent !== message) push(socket, socket.sent = message);
      }
    } catch { /* qBittorrent unreachable or the torrent just left: try again next tick */ }
    await delay(1000);
  }
  feeding = false;
}

server.on('upgrade', (request, socket) => {
  // Same guards as the HTTP API; browsers can't add headers to a WebSocket, so the Origin must be this server.
  const host = (request.headers.host ?? '').toLowerCase(), key = request.headers['sec-websocket-key'];
  let origin = '';
  try { origin = new URL(request.headers.origin).host; } catch { /* missing or bad Origin */ }
  if (new URL(request.url, 'http://unifiarr').pathname !== '/api/qbittorrent/live' || !allowedHosts.has(host.replace(/:\d+$/, '')) || origin !== host || !key) return socket.end('HTTP/1.1 403 Forbidden\r\n\r\n');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${createHash('sha1').update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64')}\r\n\r\n`);
  sockets.add(socket);
  socket.on('close', () => sockets.delete(socket));
  socket.on('error', () => socket.destroy());
  frames(socket, text => {
    try { const { watch } = JSON.parse(text); socket.watch = /^[a-f0-9]{40}(?:[a-f0-9]{24})?$/.test(watch) ? watch : null; socket.sent = ''; } catch { /* ignore */ }
  });
  if (feed.raw && feed.target === upstreams.qbittorrent) push(socket, json({ ...feed.raw, full_update: true }));
  if (!feeding) pump();
});

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  server.listen(Number(PORT), HOST, () => console.log(`Unifiarr on http://${HOST}:${server.address().port} · live: ${Object.values(upstreams).filter(Boolean).map(target => target.name).join(', ') || 'none yet, open the app to set up services'}`));
}
