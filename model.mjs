export const services = [
  { id: 'jellyfin', name: 'Jellyfin', description: 'Your personal cinema', color: '#9364ce', logo: 'assets/services/jellyfin.svg', port: 8096 },
  { id: 'sonarr', name: 'Sonarr', description: 'TV, on autopilot', color: '#38a8cc', logo: 'assets/services/sonarr.png', port: 8989 },
  { id: 'radarr', name: 'Radarr', description: 'Movie night, sorted', color: '#e6ae34', logo: 'assets/services/radarr.png', port: 7878 },
  { id: 'seerr', name: 'Seerr', description: 'Discover. Request. Enjoy.', color: '#8368df', logo: 'assets/services/seerr.png', port: 5055 },
  { id: 'qbittorrent', name: 'qBittorrent', description: 'Every byte, in its place', color: '#4c86d0', logo: 'assets/services/qbittorrent.svg', port: 8080 },
];

// Jellyfin collection types: movies, tvshows, mixed (movies and shows together).
// qBittorrent: file priorities, categories (with save paths) and torrent helpers.
export const filePriorities = { 0: 'Do not download', 1: 'Normal', 6: 'High', 7: 'Maximum' };
const trackers = (...urls) => urls.map(url => ({ url, status: 'Working', message: '' }));
const torrent = (data) => ({ category: '', tags: [], forced: false, sequential: false, firstLast: false, dlLimit: 0, upLimit: 0, dlspeed: 0, upspeed: 0, eta: null, seeds: [0, 0], peers: [0, 0], uploaded: 0, added: 0, trackers: trackers('udp://tracker.opentrackr.org:1337/announce'), ...data });

export function parseMagnet(link) {
  let url;
  try { url = new URL(link.trim()); } catch { throw new Error('Enter a valid magnet link.'); }
  const hash = url.protocol === 'magnet:' && url.searchParams.getAll('xt').map(xt => xt.match(/^urn:btih:([a-f0-9]{40}|[a-z2-7]{32})$/i)?.[1]).find(Boolean);
  if (!hash) throw new Error('Enter a valid magnet link.');
  return { hash: hash.toLowerCase(), name: url.searchParams.get('dn')?.trim() || hash.toLowerCase(), files: [], trackers: url.searchParams.getAll('tr') };
}

// Minimal bencode reader: enough for name, files, trackers and the info-hash (SHA-1 of the raw info dictionary).
export async function parseTorrent(bytes) {
  const text = new TextDecoder();
  let at = 0, depth = 0, info;
  const read = () => {
    const type = bytes[at];
    if (type === 0x69) { const end = bytes.indexOf(0x65, at); const value = Number(text.decode(bytes.subarray(at + 1, end))); if (end < 0 || !Number.isSafeInteger(value)) throw new Error(); at = end + 1; return value; }
    if (type === 0x6c || type === 0x64) {
      at++; depth++;
      const value = type === 0x6c ? [] : {};
      while (bytes[at] !== 0x65) {
        if (at >= bytes.length) throw new Error();
        if (Array.isArray(value)) { value.push(read()); continue; }
        const key = text.decode(read()), start = at;
        value[key] = read();
        if (depth === 1 && key === 'info') info = bytes.subarray(start, at);
      }
      at++; depth--;
      return value;
    }
    const colon = bytes.indexOf(0x3a, at), length = Number(text.decode(bytes.subarray(at, colon)));
    if (colon <= at || !Number.isSafeInteger(length) || colon + 1 + length > bytes.length) throw new Error();
    at = colon + 1 + length;
    return bytes.subarray(colon + 1, at);
  };
  try {
    const root = read(), meta = root.info, name = text.decode(meta.name);
    const list = meta.files ? meta.files.map(file => ({ name: `${name}/${file.path.map(part => text.decode(part)).join('/')}`, size: file.length })) : [{ name, size: meta.length }];
    if (!info || !name || list.some(file => !Number.isSafeInteger(file.size) || file.size < 0)) throw new Error();
    const hash = [...new Uint8Array(await crypto.subtle.digest('SHA-1', info))].map(byte => byte.toString(16).padStart(2, '0')).join('');
    const announce = [root.announce, ...(root['announce-list'] ?? []).flat()].filter(url => url instanceof Uint8Array).map(url => text.decode(url));
    return { hash, name, files: list.map(file => ({ ...file, progress: 0, priority: 1 })), trackers: [...new Set(announce)] };
  } catch {
    throw new Error('This is not a valid .torrent file.');
  }
}

export function addTorrents(state, entries, options = {}) {
  if (!entries.length) throw new Error('Add at least one magnet link or .torrent file.');
  const hashes = entries.map(entry => entry.hash);
  if (new Set(hashes).size !== hashes.length || state.torrents.some(entry => hashes.includes(entry.hash))) throw new Error('This torrent is already in the list.');
  const settings = torrentSettings(state, { category: options.category ?? '', tags: options.tags ?? [], savePath: options.savePath || state.categories[options.category] || '/downloads' });
  const added = entries.map(entry => torrent({ ...entry, ...settings, trackers: trackers(...entry.trackers), state: options.paused ? 'paused' : entry.files.length ? 'downloading' : 'queued', progress: 0, sequential: !!options.sequential, firstLast: !!options.firstLast }));
  state.torrents = options.top ? [...added, ...state.torrents] : [...state.torrents, ...added];
  return log(state, `${added.length} torrent${added.length === 1 ? '' : 's'} added`, added.map(entry => entry.name).join(', '));
}

export function torrentSettings(state, { name, savePath, category, tags, dlLimit, upLimit }) {
  const settings = {};
  if (name !== undefined) { settings.name = String(name).trim(); if (!settings.name || settings.name.length > 255) throw new Error('Enter a torrent name up to 255 characters.'); }
  if (savePath !== undefined) { settings.savePath = String(savePath).trim(); if (!/^(\/|[a-z]:[\\/])[^\0]{0,254}$/i.test(settings.savePath) || settings.savePath.split(/[\\/]/).includes('..')) throw new Error('Enter an absolute save path.'); }
  if (category !== undefined) { if (category !== '' && !Object.hasOwn(state.categories, category)) throw new Error('Choose an existing category.'); settings.category = category; }
  if (tags !== undefined) { settings.tags = [...new Set(tags.map(tag => String(tag).trim()).filter(Boolean))]; if (settings.tags.some(tag => tag.length > 30 || tag.includes(','))) throw new Error('Tags must be up to 30 characters without commas.'); }
  for (const [key, value] of Object.entries({ dlLimit, upLimit })) if (value !== undefined) { if (!Number.isInteger(value) || value < 0 || value > 1e6) throw new Error('Speed limits must be whole KiB/s values (0 = unlimited).'); settings[key] = value; }
  return settings;
}

function log(state, title, detail) {
  state.activity.unshift({ title, detail, service: 'qbittorrent', time: 'Just now' });
  state.activity = state.activity.slice(0, 20);
  return title;
}

export function filterMedia(media, { query = '', type = 'All', status, library = 'all' } = {}) {
  return media.filter(item => item.title.toLowerCase().includes(query.trim().toLowerCase()) && (type === 'All' || item.type === type) && (!status || status.includes(item.status)) && (library === 'all' || item.library === library));
}

// Release names → library titles: "Dune.Part.Two.2024.2160p…" and "Severance S02 1080p" match "Dune: Part Two" (2024) and "Severance".
const words = text => String(text).toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/&/g, ' and ').replace(/'/g, '').replace(/[^a-z0-9]+/g, ' ').trim();
export function indexTitles(items) {
  const index = new Map();
  for (const item of items) if (item.image || item.poster) {
    const key = words(item.title);
    if (key && !index.has(key)) index.set(key, item);
    if (key && item.year) index.set(`${key} ${item.year}`, item);
  }
  return index;
}
export function matchTitle(name, index) {
  const text = words(String(name).replace(/^\s*[[(][^\])]*[\])]\s*/, ''));
  const stop = text.search(/\b(s\d{1,2}(e\d{1,3})?|season \d+|complete|\d{3,4}p|4k|uhd|bluray|bdrip|web ?dl|webrip|hdtv|x26[45]|h 26[45]|hevc|remux)\b/);
  const head = stop < 0 ? text : text.slice(0, stop).trim();
  // Try every "title year" split (titles can contain years, e.g. "Blade Runner 2049 2017"), then the title alone.
  for (const match of head.matchAll(/\b(19|20)\d\d\b/g)) {
    const title = head.slice(0, match.index).trim();
    const found = index.get(`${title} ${match[0]}`) ?? index.get(title);
    if (title && found) return found;
  }
  return index.get(head);
}
