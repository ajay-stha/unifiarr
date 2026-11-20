// Live qBittorrent client. Talks to server.mjs (same origin), which holds the login and forwards to the WebUI API v2.
const labels = { error: 'Error', missingFiles: 'Missing files', metaDL: 'Fetching metadata', forcedMetaDL: '[F] Fetching metadata', stalledDL: 'Stalled', stalledUP: 'Seeding', checkingDL: 'Checking', checkingUP: 'Checking', checkingResumeData: 'Checking', allocating: 'Allocating', moving: 'Moving', queuedDL: 'Queued', queuedUP: 'Queued', uploading: 'Seeding', forcedUP: '[F] Seeding', downloading: 'Downloading', forcedDL: '[F] Downloading', pausedDL: 'Paused', pausedUP: 'Paused', stoppedDL: 'Stopped', stoppedUP: 'Stopped' };
const trackerStatus = ['Disabled', 'Not contacted', 'Working', 'Updating', 'Not working'];
const INFINITE_ETA = 8640000;

export function mapTorrent(hash, t, now = Date.now()) {
  const stopped = /^(paused|stopped)/.test(t.state) || t.state === 'error' || t.state === 'missingFiles';
  const progress = Math.floor(t.progress * 1000) / 10;
  const state = stopped ? 'paused' : t.state.startsWith('queued') ? 'queued' : progress === 100 ? 'seeding' : 'downloading';
  return {
    hash, live: true, name: t.name, category: t.category ?? '', tags: t.tags ? t.tags.split(',').map(tag => tag.trim()).filter(Boolean) : [],
    state, label: labels[t.state] ?? t.state, tone: ['error', 'missingFiles'].includes(t.state) ? 'error' : state,
    progress, size: t.size, done: t.completed, ratio: t.ratio, uploaded: t.uploaded, savePath: t.save_path,
    forced: !!t.force_start, sequential: !!t.seq_dl, firstLast: !!t.f_l_piece_prio,
    dlLimit: t.dl_limit > 0 ? Math.round(t.dl_limit / 1024) : 0, upLimit: t.up_limit > 0 ? Math.round(t.up_limit / 1024) : 0,
    dlspeed: t.dlspeed, upspeed: t.upspeed, eta: t.eta >= INFINITE_ETA ? null : t.eta,
    seeds: [t.num_seeds, t.num_complete], peers: [t.num_leechs, t.num_incomplete],
    added: Math.max(0, Math.floor((now / 1000 - t.added_on) / 60)), addedOn: t.added_on, queue: t.priority > 0 ? t.priority : 0,
    files: [], trackers: [], trackerCount: t.trackers_count,
  };
}

async function call(path, body) {
  const response = await fetch(`api/qbittorrent${path && `/${path}`}`, { method: body ? 'POST' : 'GET', headers: { 'x-unifiarr': '1' }, body });
  const text = await response.text();
  if (!response.ok) throw Object.assign(new Error(text.trim() || `qBittorrent returned ${response.status}.`), { status: response.status });
  return text;
}
const post = (path, params) => call(path, new URLSearchParams(params));
// qBittorrent 5 renamed pause/resume to stop/start; 4.x only knows the old names.
const either = (modern, legacy, params) => post(`torrents/${modern}`, params).catch(error => error.status === 404 ? post(`torrents/${legacy}`, params) : Promise.reject(error));

export async function connect() {
  try {
    const { version } = JSON.parse(await call(''));
    return { version, defaultSavePath: (await call('app/defaultSavePath')).trim() };
  } catch {
    return null;
  }
}

// Folds a sync/maindata reply into the raw state. After the first (full) reply qBittorrent sends only what changed.
export function merge(raw, delta) {
  if (delta.full_update) raw = {};
  raw.torrents ??= {}; raw.categories ??= {};
  raw.server_state = { ...raw.server_state, ...delta.server_state };
  for (const [hash, fields] of Object.entries(delta.torrents ?? {})) raw.torrents[hash] = { ...raw.torrents[hash], ...fields };
  for (const hash of delta.torrents_removed ?? []) delete raw.torrents[hash];
  for (const [name, fields] of Object.entries(delta.categories ?? {})) raw.categories[name] = { ...raw.categories[name], ...fields };
  for (const name of delta.categories_removed ?? []) delete raw.categories[name];
  return raw;
}

export function mapState(raw, previous = []) {
  const now = Date.now();
  const torrents = Object.entries(raw.torrents ?? {}).map(([hash, t]) => {
    const old = previous.find(item => item.hash === hash);
    return { ...mapTorrent(hash, t, now), files: old?.files ?? [], trackers: old?.trackers ?? [] };
  }).sort((a, b) => (a.queue || Infinity) - (b.queue || Infinity) || b.addedOn - a.addedOn);
  const server = raw.server_state ?? {};
  return {
    torrents,
    categories: Object.fromEntries(Object.values(raw.categories ?? {}).map(category => [category.name, category.savePath ?? ''])),
    altSpeed: !!server.use_alt_speed_limits,
    transfer: { dl: server.dl_info_speed ?? 0, up: server.up_info_speed ?? 0, dlLimit: server.dl_rate_limit ?? 0, upLimit: server.up_rate_limit ?? 0, free: server.free_space_on_disk >= 0 ? server.free_space_on_disk : null },
  };
}

export const load = async previous => mapState(merge({}, JSON.parse(await call('sync/maindata?rid=0'))), previous);

export function applyDetails(item, files, trackers) {
  item.files = files.map((file, index) => ({ index: file.index ?? index, name: file.name, size: file.size, progress: Math.floor(file.progress * 1000) / 10, priority: file.priority }));
  item.trackers = trackers.map(tracker => ({ url: tracker.url, status: trackerStatus[tracker.status] ?? 'Unknown', message: tracker.msg, seeds: Math.max(0, tracker.num_seeds), peers: Math.max(0, tracker.num_leeches) }));
  return item;
}

export async function details(item) {
  const [files, trackers] = await Promise.all([call(`torrents/files?hash=${item.hash}`), call(`torrents/trackers?hash=${item.hash}`)]);
  return applyDetails(item, JSON.parse(files), JSON.parse(trackers));
}

// Applies one Downloads-page operation to the real torrent. `settings` for options are pre-validated by the model.
export async function run(item, operation, value) {
  const hashes = item.hash;
  if (operation === 'pause') return await either('stop', 'pause', { hashes }), 'Torrent paused';
  if (operation === 'resume') return await either('start', 'resume', { hashes }), 'Torrent resumed';
  if (operation === 'force') return await post('torrents/setForceStart', { hashes, value: !item.forced }), `Force start ${item.forced ? 'off' : 'on'}`;
  if (operation === 'recheck') return await post('torrents/recheck', { hashes }), 'Recheck started';
  if (operation === 'reannounce') return await post('torrents/reannounce', { hashes }), 'Reannounced to trackers';
  if (operation === 'queue') return await post(`torrents/${{ top: 'topPrio', up: 'increasePrio', down: 'decreasePrio', bottom: 'bottomPrio' }[value]}`, { hashes }), 'Queue position changed';
  if (operation === 'priority') return await post('torrents/filePrio', { hash: hashes, id: item.files[value.index].index, priority: value.priority }), 'File priority changed';
  if (operation === 'delete') return await post('torrents/delete', { hashes, deleteFiles: !!value }), value ? 'Torrent and files deleted' : 'Torrent removed';
  if (operation === 'options') {
    if (value.name !== item.name) await post('torrents/rename', { hash: hashes, name: value.name });
    if (value.savePath !== item.savePath) await post('torrents/setLocation', { hashes, location: value.savePath });
    if (value.category !== item.category) await post('torrents/setCategory', { hashes, category: value.category });
    const removed = item.tags.filter(tag => !value.tags.includes(tag)), added = value.tags.filter(tag => !item.tags.includes(tag));
    if (removed.length) await post('torrents/removeTags', { hashes, tags: removed.join(',') });
    if (added.length) await post('torrents/addTags', { hashes, tags: added.join(',') });
    if (value.dlLimit !== item.dlLimit) await post('torrents/setDownloadLimit', { hashes, limit: value.dlLimit * 1024 });
    if (value.upLimit !== item.upLimit) await post('torrents/setUploadLimit', { hashes, limit: value.upLimit * 1024 });
    if (value.sequential !== item.sequential) await post('torrents/toggleSequentialDownload', { hashes });
    if (value.firstLast !== item.firstLast) await post('torrents/toggleFirstLastPiecePrio', { hashes });
    return 'Torrent options saved';
  }
  throw new Error('This torrent action is not available.');
}

export const setAll = pause => either(pause ? 'stop' : 'start', pause ? 'pause' : 'resume', { hashes: 'all' });
const logKinds = { 1: 'Normal', 2: 'Info', 4: 'Warning', 8: 'Critical' };
export const log = async () => JSON.parse(await call('log/main?normal=true&info=true&warning=true&critical=true&last_known_id=-1')).filter(e => !e.message.startsWith('WebAPI login')).slice(-60).reverse()
  .map(e => ({ service: 'qbittorrent', id: e.id, title: e.message, kind: logKinds[e.type] ?? 'Info', date: new Date(e.timestamp * 1000).toISOString() }));
export const toggleAltSpeed = () => post('transfer/toggleSpeedLimitsMode', {});

export async function add({ magnets, files, category, savePath, tags, paused, top, sequential, firstLast }) {
  const form = new FormData();
  if (magnets.length) form.append('urls', magnets.join('\n'));
  for (const file of files) form.append('torrents', file, file.name);
  // `paused` is the 4.x name, `stopped` the 5.x one; autoTMM off so the chosen save path is honoured.
  for (const [key, val] of Object.entries({ category, savepath: savePath, tags: tags.join(','), paused, stopped: paused, addToTopOfQueue: top, sequentialDownload: sequential, firstLastPiecePrio: firstLast, autoTMM: false })) form.append(key, String(val));
  const result = (await call('torrents/add', form)).trim();
  if (result === 'Fails.') throw new Error('qBittorrent could not add these torrents.');
  const count = magnets.length + files.length;
  return `${count} torrent${count === 1 ? '' : 's'} added`;
}
