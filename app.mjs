import { services, filePriorities, addTorrents, parseMagnet, parseTorrent, torrentSettings, filterMedia, indexTitles, matchTitle } from './model.mjs';
import * as qbit from './qbit.mjs';
import { api, connect, arrClient, jellyfinClient, seerrClient } from './live.mjs';

// Icons: Lucide v1.74.0 (https://lucide.dev), ISC License, Copyright (c) Lucide Contributors.
const icons = {
  overview: '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
  discover: '<circle cx="12" cy="12" r="10"/><path d="m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z"/>',
  library: '<rect width="8" height="18" x="3" y="3" rx="1"/><path d="M7 3v18"/><path d="M20.4 18.9c.2.5-.1 1.1-.6 1.3l-1.9.7c-.5.2-1.1-.1-1.3-.6L11.1 5.1c-.2-.5.1-1.1.6-1.3l1.9-.7c.5-.2 1.1.1 1.3.6Z"/>',
  downloads: '<path d="M12 15V3"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5"/>',
  requests: '<path d="M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z"/><path d="M7 11h10"/><path d="M7 15h6"/><path d="M7 7h8"/>',
  calendar: '<path d="M8 2v3"/><path d="M16 2v3"/><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M8 13h.01"/><path d="M12 13h.01"/><path d="M16 13h.01"/><path d="M8 17h.01"/><path d="M12 17h.01"/><path d="M16 17h.01"/>',
  'arrow-down': '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
  'arrow-up': '<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
  'chevrons-up': '<path d="m17 11-5-5-5 5"/><path d="m17 18-5-5-5 5"/>',
  'chevrons-down': '<path d="m7 6 5 5 5-5"/><path d="m7 13 5 5 5-5"/>',
  'chevron-up': '<path d="m18 15-6-6-6 6"/>',
  'chevron-down': '<path d="m6 9 6 6 6-6"/>',
  upload: '<path d="M12 3v12"/><path d="m17 8-5-5-5 5"/><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>',
  trash: '<path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  folder: '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
  file: '<path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/>',
  magnet: '<path d="m12 15 4 4"/><path d="M2.352 10.648a1.205 1.205 0 0 0 0 1.704l2.296 2.296a1.205 1.205 0 0 0 1.704 0l6.029-6.029a1 1 0 1 1 3 3l-6.029 6.029a1.205 1.205 0 0 0 0 1.704l2.296 2.296a1.205 1.205 0 0 0 1.704 0l6.365-6.367A1 1 0 0 0 8.716 4.282z"/><path d="m5 8 4 4"/>',
  snail: '<path d="M2 13a6 6 0 1 0 12 0 4 4 0 1 0-8 0 2 2 0 0 0 4 0"/><circle cx="10" cy="13" r="8"/><path d="M2 21h12c4.4 0 8-3.6 8-8V7a2 2 0 1 0-4 0v6"/><path d="M18 3 19.1 5.2"/><path d="M22 3 20.9 5.2"/>',
  tower: '<path d="M4.9 16.1C1 12.2 1 5.8 4.9 1.9"/><path d="M7.8 4.7a6.14 6.14 0 0 0-.8 7.5"/><circle cx="12" cy="9" r="2"/><path d="M16.2 4.8c2 2 2.26 5.11.8 7.47"/><path d="M19.1 1.9a9.96 9.96 0 0 1 0 14.1"/><path d="M9.5 18h5"/><path d="m8 22 4-11 4 11"/>',
  recheck: '<path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/>',
  zap: '<path d="M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z"/>',
  tag: '<path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5" fill="currentColor"/>',
  settings: '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"/><circle cx="12" cy="12" r="3"/>',
  search: '<path d="m21 21-4.34-4.34"/><circle cx="11" cy="11" r="8"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/>',
  moon: '<path d="M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"/>',
  bell: '<path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/>',
  arrow: '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
  plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
  play: '<path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z"/>',
  info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
  star: '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"/>',
  pause: '<rect x="14" y="3" width="5" height="18" rx="1"/><rect x="5" y="3" width="5" height="18" rx="1"/>',
  stop: '<rect width="18" height="18" x="3" y="3" rx="2"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  close: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  external: '<path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
  storage: '<path d="M10 16h.01"/><path d="M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/><path d="M21.946 12.013H2.054"/><path d="M6 16h.01"/>',
  film: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M7 3v18"/><path d="M3 7.5h4"/><path d="M3 12h18"/><path d="M3 16.5h4"/><path d="M17 3v18"/><path d="M17 7.5h4"/><path d="M17 16.5h4"/>',
  menu: '<path d="M4 5h16"/><path d="M4 12h16"/><path d="M4 19h16"/>',
  panel: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M9 4v16"/>',
  sparkle: '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"/><path d="M20 2v4"/><path d="M22 4h-4"/><circle cx="4" cy="20" r="2"/>',
  manage: '<path d="m17 2-5 5-5-5"/><rect width="20" height="15" x="2" y="7" rx="2"/>',
  wanted: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
  activity: '<path d="M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"/>',
  server: '<rect width="20" height="8" x="2" y="2" rx="2" ry="2"/><rect width="20" height="8" x="2" y="14" rx="2" ry="2"/><path d="M6 6h.01"/><path d="M6 18h.01"/>',
  bookmark: '<path d="M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"/>',
  interactive: '<circle cx="10" cy="8" r="5"/><path d="M2 21a8 8 0 0 1 10.434-7.62"/><circle cx="18" cy="18" r="3"/><path d="m22 22-1.9-1.9"/>',
  pencil: '<path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/><path d="m15 5 4 4"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  'user-plus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M19 8v6"/><path d="M22 11h-6"/>',
  monitor: '<rect width="20" height="14" x="2" y="3" rx="2"/><path d="M8 21h8"/><path d="M12 17v4"/>',
  list: '<path d="M3 5h.01"/><path d="M3 12h.01"/><path d="M3 19h.01"/><path d="M8 5h13"/><path d="M8 12h13"/><path d="M8 19h13"/>',
  history: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
  ban: '<circle cx="12" cy="12" r="10"/><path d="M4.929 4.929 19.07 19.071"/>',
  refresh: '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.overview}</svg>`;
const esc = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const $ = selector => document.querySelector(selector);
const main = $('#main');
const modal = $('#modal');
const sidebar = $('#sidebar');
const mobileNavigation = matchMedia('(max-width: 760px)');
const state = { media: [], torrents: [], sessions: [], activity: [], categories: {}, altSpeed: false };
const libraries = []; // Jellyfin libraries
let configured = null; // service → { url, user?, hasSecret } from server.mjs; null when it can't be reached
let query = '';
let type = 'All';
let library = 'all';
let requestFilter = 'pending';
let requestSort = 'recent';
let torrentFilter = 'all';
let torrentCategory = 'all';
let activitySource = 'all';
const picked = new Set(); // torrents ticked on the Downloads page
let torrentTab = 'general';
let live = null; // { version, defaultSavePath } once server.mjs reaches a real qBittorrent
const connected = {}; // sonarr / radarr / jellyfin → { version } once server.mjs reaches them
let data = {}; // per-page data loaded from Sonarr, Radarr and Jellyfin
let wantedFilter = 'missing';
let titleTab = 'overview';
let current = null; // title open in the Sonarr/Radarr modal
let releases = null; // interactive search: { service, target, label, titleId, list, error }
let view = 'overview';
let toastTimer;
let returnFocus;

// Remote images show a shimmer until fully loaded, then fade in, instead of painting top-down. Loaded URLs are
// remembered so re-renders (and the DOM patcher, which copies classes) don't flash the placeholder again.
const loadedImages = new Set();
const photo = src => `class="${loadedImages.has(src) ? 'is-loaded' : 'is-loading'}" src="${esc(src)}"`;
for (const type of ['load', 'error']) document.addEventListener(type, ({ target }) => {
  if (!target.classList?.contains('is-loading')) return;
  if (type === 'load') loadedImages.add(target.getAttribute('src'));
  target.classList.replace('is-loading', type === 'load' ? 'is-loaded' : 'is-failed');
  if (type === 'load') target.classList.add('just-loaded');
}, true);
const posterUrl = item => item.image || `https://image.tmdb.org/t/p/w342${item.poster}`;
const picture = (item, className = '', lazy = true) => item.image || item.poster ? `<img ${photo(posterUrl(item)).replace('class="', `class="${className} `)} alt="${esc(item.title)} poster"${lazy ? ' loading="lazy"' : ''} width="200" height="300">` : `<span class="${className} poster-fallback" role="img" aria-label="${esc(item.title)}">${icon('film')}</span>`;
const serviceIcon = service => `<span class="service-symbol"><img src="${service.logo}" alt="" width="30" height="30"></span>`;
const button = (text, action, id, className = '', iconName = '') => `<button type="button" class="button ${className}" data-action="${action}"${id ? ` data-id="${id}"` : ''}>${iconName ? icon(iconName) : ''}${text}</button>`;
const link = (label, target) => `<button class="text-button" data-view="${target}">${label}${icon('arrow')}</button>`;

const toastBox = $('#toast');
function toast(message) {
  // An open modal dialog sits in the top layer, above everything else: show the toast inside it.
  if (toastBox.parentNode !== (modal.open ? modal : document.body)) (modal.open ? modal : document.body).append(toastBox);
  toastBox.textContent = message;
  toastBox.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastBox.classList.remove('visible'), 3600);
}
const loader = label => `<div class="loader" role="status"><span class="loader-deck" aria-hidden="true"><i></i><i></i><i></i></span><span class="loader-bar" aria-hidden="true"></span><span>${label}…</span></div>`;

const isLive = service => !!(service.id === 'qbittorrent' ? live : connected[service.id]);
const liveVersion = service => service.id === 'qbittorrent' ? live?.version : connected[service.id]?.version;
const has = id => !!configured?.[id];
const arrs = () => ['sonarr', 'radarr'].filter(has);
// A service that is set up but unreachable answers every call with nothing; its page shows why.
const idle = new Proxy({}, { get: () => async () => undefined });
const arr = service => connected[service] ? arrClient(service) : idle;
const jf = () => connected.jellyfin ? jellyfinClient : idle;
const sr = () => connected.seerr ? seerrClient : idle;
const each = async (method, ...args) => (await Promise.all(['sonarr', 'radarr'].map(service => settle(arr(service)[method](...args))))).flatMap(list => list ?? []);
const span = days => { const start = new Date(); start.setHours(0, 0, 0, 0); const end = new Date(start); end.setDate(end.getDate() + days); return [start.toISOString(), end.toISOString()]; };
const serviceName = id => services.find(service => service.id === id).name;
const since = date => date ? ago(Math.max(0, Math.floor((Date.now() - Date.parse(date)) / 6e4))) : 'Never';
const day = date => new Date(date).toLocaleDateString('en', { month: 'short', day: 'numeric', year: 'numeric' });

const patch = (element, html) => morph(element, Object.assign(element.cloneNode(false), { innerHTML: html }));

function renderNavigation() {
  patch($('#navigation'), pages().map(([id, name, count]) => `<button class="nav-item ${view === id ? 'active' : ''}" data-view="${id}" title="${name}"${view === id ? ' aria-current="page"' : ''}>${icon(id === 'connections' ? 'settings' : id)}${name}${count !== undefined ? ` <span class="tab-count">${count}</span>` : ''}</button>`).join(''));
  // Phones get the most used pages in a bottom bar; everything else stays behind More.
  const tabs = ['overview', 'discover', 'downloads', 'library', 'requests', 'manage'].map(id => pages().find(page => page[0] === id)).filter(Boolean).slice(0, 4);
  patch($('#tabbar'), tabs.map(([id, name, count]) => `<button class="tab-item${view === id ? ' active' : ''}" data-view="${id}"${view === id ? ' aria-current="page"' : ''}>${icon(id)}<span>${{ 'My library': 'Library', 'Series & movies': 'Media' }[name] ?? name}</span>${count ? `<b class="tab-badge">${count}</b>` : ''}</button>`).join('') + `<button class="tab-item tab-more" aria-label="More pages">${icon('menu')}<span>More</span></button>`);
  const mine = services.filter(service => has(service.id));
  $('.services-caption').hidden = !mine.length;
  $('.services-caption span').textContent = mine.length;
  patch($('#service-navigation'), mine.map(service => `<button class="service-health" data-action="service" data-id="${service.id}" aria-label="${service.name} connection status: ${isLive(service) ? 'live' : 'offline'}">${serviceIcon(service)}<span>${service.name}</span><span class="service-demo${isLive(service) ? ' is-live' : ''}">${isLive(service) ? 'Live' : 'Offline'}</span></button>`).join(''));
}

// Only pages for services that are set up; Services is always there.
const pages = () => [
  ['overview', 'Overview', undefined, Object.keys(configured ?? {}).length], ['discover', 'Discover', undefined, has('seerr')], ['requests', 'Requests', data.requests?.filter(item => item.requestStatus === 'pending').length || undefined, has('seerr')], ['library', 'My library', undefined, has('jellyfin')], ['manage', 'Series & movies', undefined, arrs().length],
  ['downloads', 'Downloads', state.torrents.filter(item => item.progress < 100).length || undefined, has('qbittorrent')], ['wanted', 'Wanted', undefined, arrs().length],
  ['activity', 'Activity', undefined, arrs().length || has('qbittorrent') || has('jellyfin')], ['calendar', 'Calendar', undefined, arrs().length], ['server', 'Server', undefined, has('jellyfin')], ['connections', 'Services', undefined, true],
].filter(page => page[3]);

function pageHeading(title, subtitle, actions = '', eyebrow = '') {
  return `<div class="page-heading"><div>${eyebrow ? `<div class="eyebrow">${eyebrow}</div>` : ''}<h1>${title}</h1><p>${subtitle}</p></div><div class="heading-actions">${actions}</div></div>`;
}

function mediaCard(item) {
  return `<button class="poster-card" data-action="${item.seerr ? 'request' : 'details'}" data-id="${item.id}" aria-label="View ${esc(item.title)}"><div class="poster-wrap">${picture(item)}${item.quality ? `<span class="quality-label">${item.quality}</span>` : ''}<span class="available-label"><span class="status-dot"></span>${item.label ?? ({ available: 'In your library', requested: 'Requested', downloading: 'Downloading', paused: 'Paused', partial: 'Partly available', none: 'Available to request' })[item.status]}</span></div><h3>${esc(item.title)}</h3><p><span>${item.year} <span>·</span> ${item.type === 'Series' ? 'TV Series' : 'Movie'}</span><span class="star">★ <span>${item.rating}</span></span></p></button>`;
}


const ago = minutes => minutes < 1 ? 'Just now' : minutes < 60 ? `${minutes} min ago` : minutes < 1440 ? `${Math.floor(minutes / 60)} h ago` : `${Math.floor(minutes / 1440)} d ago`;
const tagChips = item => item.tags?.length ? `<span class="tag-chips">${item.tags.map(tag => `<span>${icon('tag')}${esc(tag)}</span>`).join('')}</span>` : '';

function tabs() {
  return `<div class="section-tabs" aria-label="Media type">${[['All', 'All'], ['Movie', 'Movies'], ['Series', 'TV shows']].map(([value, name]) => `<button class="${type === value ? 'selected' : ''}" data-type="${value}" aria-pressed="${type === value}">${name}</button>`).join('')}</div>`;
}

function arrWarnings(results) {
  const limit = 10 * 1024 ** 3;
  return results.flatMap(({ service, health = [], disks = [] }) => [
    ...health.filter(item => ['warning', 'error'].includes(item.type?.toLowerCase())).map(item => ({ service, severity: item.type, message: item.message || 'Service warning' })),
    ...disks.filter(disk => Number.isFinite(disk.freeSpaceBytes) && (disk.freeSpaceBytes <= limit || (disk.totalSpaceBytes > 0 && disk.freeSpaceBytes / disk.totalSpaceBytes < .1)))
      .map(disk => ({ service, severity: 'warning', message: `Low disk space: ${bytes(disk.freeSpaceBytes)} free on ${disk.path || disk.label || 'volume'}` })),
  ]);
}

const clock = seconds => `${Math.floor(seconds / 3600) ? `${Math.floor(seconds / 3600)}:` : ''}${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
// Where playback is now: Jellyfin's last report plus the time since, while playing.
const played = session => Math.min(session.duration, session.position + (session.status === 'playing' ? Math.floor((Date.now() - session.at) / 1000) : 0));
// Commands Jellyfin accepted but the player hasn't reported back yet; kept for up to 10s so the card doesn't flip back.
const pending = new Map();
function playing(sessions) {
  return sessions.filter(session => {
    const wanted = pending.get(session.id);
    if (!session.item) return false;
    if (!wanted || wanted.until < Date.now() || wanted.status === session.status) return pending.delete(session.id), true;
    if (wanted.status === 'stopped') return false;
    if (session.status === 'playing') session.position = played(session);
    return Object.assign(session, { status: wanted.status, at: Date.now() });
  }).sort((a, b) => a.user.localeCompare(b.user) || a.id.localeCompare(b.id)); // Jellyfin orders by last activity, which reshuffles cards
}

function nowPlaying() {
  return `<section class="now-playing sec" aria-labelledby="now-playing-title">
    <div class="section-title"><h2 id="now-playing-title"><span class="playing-indicator">${icon('play')}</span>Now playing <span class="count">${state.sessions.length}</span></h2><span class="playback-source">${serviceIcon(services[0])}Jellyfin ${connected.jellyfin ? '<span class="demo-pill is-live">Live</span>' : '<span class="demo-pill">Offline</span>'}</span></div>
    <div class="session-grid">${state.sessions.map(session => {
      const item = session.item ?? state.media.find(media => media.id === session.mediaId);
      const paused = session.status === 'paused';
      return `<article class="session-card${paused ? ' is-paused' : ''}" data-session="${session.id}">
      <img ${photo(posterUrl(item)).replace('class="', 'class="session-backdrop ')} alt="" loading="lazy">${picture(item, 'session-poster')}<div class="session-main">
      <div class="session-top"><div class="session-info"><span class="session-status ${paused ? 'is-paused' : ''}"><span class="status-dot"></span>${paused ? 'Paused' : 'Playing'}</span><h3>${esc(item.title)}</h3><p>${esc(item.detail ?? (item.type === 'Series' ? 'S01E01 · Anjin' : `${item.year} · ${item.genre}`))}</p></div>${session.quality ? `<span class="session-quality">${session.quality}</span>` : ''}</div>
      <div class="session-viewer"><span class="viewer-avatar">${esc(session.user[0])}</span><div><strong>${esc(session.user)}</strong><span>${esc(session.device)} · ${esc(session.client)}</span></div></div>
      <div class="session-progress" role="progressbar" aria-label="${esc(item.title)} playback position" aria-valuenow="${played(session)}" aria-valuemin="0" aria-valuemax="${session.duration}"><i style="width:${played(session) / session.duration * 100}%"></i></div>
      <div class="session-timing"><span><span class="session-clock">${clock(played(session))}</span> <span>/ ${clock(session.duration)}</span></span><span>${session.mode}</span></div></div>
      <div class="session-controls"${session.remote ? '' : ' title="This app doesn’t accept remote control"'}><button ${session.remote ? '' : 'disabled '}class="button playback-toggle ${paused ? '' : 'is-playing'}" data-action="playback-${paused ? 'play' : 'pause'}" data-id="${session.id}" aria-label="${paused ? 'Resume' : 'Pause'} ${esc(item.title)} for ${esc(session.user)}">${icon(paused ? 'play' : 'pause')}${paused ? 'Resume' : 'Pause'}</button><button ${session.remote ? '' : 'disabled '}class="button stop-control" data-action="playback-confirm-stop" data-id="${session.id}" aria-label="Stop ${esc(item.title)} for ${esc(session.user)}">${icon('stop')}Stop</button><button ${session.remote ? '' : 'disabled '}class="button message-control" data-action="playback-compose" data-id="${session.id}" aria-label="Message ${esc(session.user)}">${icon('requests')}Message</button></div>
      ${session.lastMessage ? `<p class="session-message">${icon('requests')}<span><strong>Last message</strong>${esc(session.lastMessage)}</span></p>` : ''}
      </article>`;
    }).join('') || `<div class="empty playback-empty">${icon('play')}<h3>A quiet moment on your server.</h3><p>No active sessions. Your next movie night starts in the library.</p>${link('Browse library', 'library')}</div>`}</div></section>`;
}

function renderOverview() {
  const available = filterMedia(state.media, { type });
  const greeting = new Date().getHours() < 12 ? 'Good morning' : new Date().getHours() < 18 ? 'Good afternoon' : 'Good evening';
  const active = state.torrents.filter(item => item.progress < 100), mine = services.filter(service => has(service.id)), on = mine.filter(isLive);
  const stats = [
    has('jellyfin') && `<div class="stat"><span class="stat-label">${icon('library')}In your library</span><strong>${state.media.length}<small>titles</small></strong><p>${state.media.filter(item => item.type === 'Movie').length} movies · ${state.media.filter(item => item.type === 'Series').length} TV shows</p></div>`,
    has('qbittorrent') && `<div class="stat"><span class="stat-label">${icon('downloads')}Download queue</span><strong>${active.length}<small>in queue</small></strong><p>${active.filter(running).length} downloading · ${active.filter(item => item.state === 'paused').length} paused</p></div>`,
    has('seerr') && `<div class="stat"><span class="stat-label">${icon('requests')}Requests</span><strong>${data.requests?.filter(item => item.requestStatus === 'pending').length ?? '—'}<small>pending</small></strong><p>${data.requests?.length ?? 0} recent in Seerr</p></div>`,
    arrs().length && `<div class="stat"><span class="stat-label">${icon('calendar')}Coming up</span><strong>${data.calendar?.length ?? '—'}<small>this week</small></strong><p>Episodes and movie releases</p></div>`,
    `<div class="stat"><span class="stat-label">${icon('settings')}Your services</span><strong>${mine.length}<small>connected</small></strong><p>${on.length === mine.length ? 'All services live' : `${mine.length - on.length} offline`}</p></div>`,
  ].filter(Boolean);
  const feed = data.feed?.length ? `<ul class="feed">${data.feed.map(event => `<li style="--c:${services.find(service => service.id === event.service).color}">${esc(event.title)}<small>${esc(event.detail)}</small><time>${since(event.date)}</time></li>`).join('')}</ul>` : `<div class="empty">${data.feed ? 'Nothing has happened yet.' : 'Loading activity…'}</div>`;
  const warnings = data.warnings?.length ? `<section class="health-warnings" aria-label="Service warnings">${data.warnings.map(item => `<div class="health-warning is-${item.severity === 'error' ? 'error' : 'warning'}"><strong>${serviceName(item.service)}</strong><span>${esc(item.message)}</span></div>`).join('')}</section>` : '';
  const recent = state.media.filter(item => item.added).sort((a, b) => b.added.localeCompare(a.added)).slice(0, 12);
  const upcoming = data.calendar?.slice(0, 4).map(item => `<button class="row service-row" data-action="arr-title" data-service="${item.service}" data-id="${item.titleId}">${picture(item, 'upcoming-poster')}<span><b>${esc(item.title)}</b><span class="meta">${esc(item.detail)} · ${new Date(item.date).toLocaleDateString('en', { weekday: 'short', month: 'short', day: 'numeric' })}</span></span></button>`).join('');
  return pageHeading(greeting, 'Your media universe, all in one place.', button('Manage services', 'connections', '', 'primary', 'settings'), 'YOUR PERSONAL COMMAND CENTER') +
    `<section class="stats" aria-label="Workspace statistics" style="--stats:${stats.length}">${stats.join('')}</section>${warnings}
    ${has('jellyfin') ? nowPlaying() : ''}<div class="home-grid"><div>
    ${has('qbittorrent') ? `<section class="sec"><div class="section-title"><h2>Downloading <span class="count">${active.length}</span></h2>${link('All downloads', 'downloads')}</div><div class="panel large-list">${active.slice(0, 3).map(torrentRow).join('') || `<div class="empty playback-empty queue-empty">${live ? `${icon('downloads')}<h3>Your queue is clear.</h3><p>Nothing downloading right now.</p>${button('Add torrent', 'torrent-add', '', '', 'plus')}` : `${icon('downloads')}<h3>qBittorrent is offline.</h3><p>Check the connection in Settings.</p>`}</div>`}</div></section>` : ''}
    ${has('jellyfin') && recent.length ? `<section class="sec"><div class="section-title"><h2>Recently added</h2>${link('Your library', 'library')}</div><div class="poster-strip">${recent.map(item => mediaCard({ ...item, label: `${item.episode ?? 'Added'} ${since(item.added).toLowerCase()}` })).join('')}</div></section>` : ''}
    ${has('jellyfin') ? `<section class="sec"><div class="section-title"><h2>Ready to watch</h2>${tabs()}${link('Your library', 'library')}</div><div class="poster-grid ready-grid">${available.slice(0, 6).map(mediaCard).join('')}</div>${!available.length ? '<div class="empty">Nothing in your library yet.</div>' : ''}</section>` : ''}</div>
    <aside>${arrs().length || has('jellyfin') || has('qbittorrent') ? `<section class="sec activity-panel"><div class="section-title"><h2>Activity</h2>${link('View all', 'activity')}</div>${feed}</section>` : ''}
    ${has('seerr') ? `<section class="sec"><div class="section-title"><h2>Requests</h2>${link('All requests', 'requests')}</div><div class="service-list">${data.requests?.slice(0, 4).map(item => `<button class="row service-row" data-action="request" data-id="${item.id}">${picture(item, 'upcoming-poster')}<span><b>${esc(item.title)}</b><span class="meta">${esc(item.by)} · ${since(item.date)}</span></span>${requestBadge(item)}</button>`).join('') || `<div class="empty">${data.requests ? 'No requests yet.' : 'Loading…'}</div>`}</div></section>` : ''}
    ${arrs().length ? `<section class="sec"><div class="section-title"><h2>Coming up</h2>${link('Calendar', 'calendar')}</div><div class="service-list">${upcoming || `<div class="empty">${data.calendar ? 'Nothing scheduled this week.' : 'Loading…'}</div>`}</div></section>` : ''}
    <section class="sec"><div class="section-title"><h2>Your services</h2>${link('Manage', 'connections')}</div><div class="service-list">${mine.map(service => `<button class="row service-row" data-action="service" data-id="${service.id}">${serviceIcon(service)}<span><b>${service.name}</b><span class="meta">${service.description}</span></span><span class="tag t-${service.id === 'qbittorrent' ? 'qbittorrent' : service.id === 'jellyfin' ? 'jelly' : service.id}">${isLive(service) ? 'Live' : 'Offline'}</span></button>`).join('')}</div></section></aside></div>`;
}

function renderCatalog() {
  const isLibrary = view === 'library';
  const status = isLibrary ? ['available'] : undefined;
  const current = libraries.find(item => item.id === library);
  const items = isLibrary ? filterMedia(state.media, { query, type, status, library }) : data.discover?.filter(item => type === 'All' || item.type === type);
  const kinds = { movies: 'Movies', tvshows: 'Shows', mixed: 'Movies & shows' };
  const picker = isLibrary ? `<div class="library-picker" aria-label="Jellyfin libraries">${[{ id: 'all', name: 'All libraries', collection: '' }, ...libraries].map(item => `<button class="library-chip ${library === item.id ? 'selected' : ''}" data-library="${item.id}" aria-pressed="${library === item.id}"><b>${esc(item.name)}</b><small>${item.collection ? `${kinds[item.collection]} · ` : `${libraries.length} libraries · `}${filterMedia(state.media, { status, library: item.id }).length}</small></button>`).join('')}</div>` : '';
  return pageHeading(isLibrary ? (current?.name ?? 'Library') : 'Find and request', isLibrary ? (current ? `Jellyfin ${kinds[current.collection].toLowerCase()} library.` : 'Every Jellyfin library, all in one place.') : query.trim() ? `Results from Seerr for “${esc(query.trim())}”.` : 'Trending now. Discover a new favorite and request it from Seerr.', isLibrary ? button(current ? 'Scan library' : 'Scan all libraries', 'library-scan', current?.id ?? '', '', 'refresh') : '') +
    `${banner([isLibrary ? 'jellyfin' : 'seerr'])}${picker}<div class="filter-bar">${!current || current.collection === 'mixed' ? tabs() : '<span></span>'}<p>${items ? `${items.length} titles${query && isLibrary ? ` matching “${esc(query)}”` : ''}` : ''}</p></div>${!items ? loading('titles') : `<div class="poster-grid full-grid">${items.map(mediaCard).join('')}</div>${!items.length ? '<div class="empty">No titles found. Try another search or media type.</div>' : ''}${!isLibrary && data.discoverMore ? `<div class="load-more" aria-busy="true">${loader('Loading more titles')}</div>` : ''}`}`;
}

const bytes = value => {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${value.toFixed(unit && value < 100 ? 1 : 0)} ${units[unit]}`;
};
const duration = seconds => seconds == null ? '∞' : seconds < 3600 ? `${Math.max(1, Math.round(seconds / 60))}m` : `${Math.floor(seconds / 3600)}h ${Math.round(seconds % 3600 / 60)}m`;
const ALT_SPEED = { dl: 2 * 1024 ** 2, up: 512 * 1024 };
const running = item => !['paused', 'queued'].includes(item.state);
const liveSpeed = (item, key) => running(item) ? Math.min(item[key === 'dl' ? 'dlspeed' : 'upspeed'], item[`${key}Limit`] * 1024 || Infinity) : 0;
const selectedSize = item => item.size ?? item.files.filter(file => file.priority).reduce((total, file) => total + file.size, 0);
const doneSize = item => item.done ?? item.files.filter(file => file.priority).reduce((total, file) => total + file.size * file.progress / 100, 0);
const torrentState = item => item.label ? [item.tone, item.label] : item.state === 'paused' ? ['paused', 'Paused'] : item.state === 'queued' ? ['queued', 'Queued'] : !item.files.length ? ['queued', 'Fetching metadata'] : item.progress === 100 ? ['seeding', `${item.forced ? '[F] ' : ''}Seeding`] : ['downloading', `${item.forced ? '[F] ' : ''}Downloading`];
const torrentFilters = { all: () => true, downloading: item => item.progress < 100 && item.state !== 'paused', seeding: item => item.progress === 100 && item.state !== 'paused', completed: item => item.progress === 100, paused: item => item.state === 'paused' };
// Posters come from whatever is connected: Jellyfin library first, then Sonarr/Radarr, then Seerr requests. Rebuilt only when a source changes.
let posterIndex, posterSources = [];
const posterFor = name => {
  const sources = [state.media, data.titles, data.requests];
  if (sources.some((source, index) => source !== posterSources[index])) [posterIndex, posterSources] = [indexTitles(sources.flatMap(source => source ?? [])), sources];
  return matchTitle(name, posterIndex);
};
const torrentThumb = (item, className = 'torrent-thumb') => {
  const media = posterFor(item.name);
  return media ? picture(media, className) : `<span class="${className} torrent-icon">${icon(!item.files.length && !item.size ? 'magnet' : item.files.length > 1 ? 'folder' : 'file')}</span>`;
};
const ratio = item => (item.ratio ?? item.uploaded / (doneSize(item) || 1)).toFixed(2);
const inQueue = item => item.queue === undefined ? item.progress < 100 : item.queue > 0;
const queuePosition = item => item.queue || state.torrents.filter(inQueue).indexOf(item) + 1;
const categoryChip = item => item.category ? `<span class="category-chip">${icon('folder')}${esc(item.category)}</span>` : '';

function torrentRow(item, pickable = false) {
  const [stateClass, label] = torrentState(item);
  const position = queuePosition(item);
  return `<article class="torrent is-${stateClass}">${torrentThumb(item)}<div class="torrent-info">
    <button class="torrent-name" data-action="torrent-details" data-id="${item.hash}">${esc(item.name)}</button>
    <div class="torrent-meta"><span class="torrent-state is-${stateClass}">${label}</span>${categoryChip(item)}${tagChips(item)}</div>
    <div class="download-progress"><div class="progress-track"><i style="width:${item.progress}%"></i></div><strong>${item.progress}%</strong></div>
    <div class="torrent-stats"><span>${bytes(doneSize(item))} of ${item.files.length ? bytes(selectedSize(item)) : '—'}</span><span class="speed-down">${icon('arrow-down')}${bytes(liveSpeed(item, 'dl'))}/s</span><span class="speed-up">${icon('arrow-up')}${bytes(liveSpeed(item, 'up'))}/s</span><span>ETA ${item.progress === 100 ? '—' : running(item) ? duration(item.eta) : '∞'}</span><span>Seeds ${item.seeds[0]} (${item.seeds[1]})</span><span>Peers ${item.peers[0]} (${item.peers[1]})</span><span>Ratio ${ratio(item)}</span></div>
  </div><div class="torrent-controls">${pickable ? `<input type="checkbox" class="torrent-pick" data-id="${item.hash}" aria-label="Select ${esc(item.name)}"${picked.has(item.hash) ? ' checked' : ''}>` : ''}${position ? `<span class="queue-position" title="Queue position">#${position}</span>` : ''}<button data-action="torrent-toggle" data-id="${item.hash}" aria-label="${item.state === 'paused' ? 'Resume' : 'Pause'} ${esc(item.name)}" title="${item.state === 'paused' ? 'Resume' : 'Pause'}">${icon(item.state === 'paused' ? 'play' : 'pause')}</button><button data-action="torrent-details" data-id="${item.hash}" aria-label="Details for ${esc(item.name)}" title="Details">${icon('info')}</button></div></article>`;
}

function renderQueue() {
  const needle = query.trim().toLowerCase();
  for (const hash of picked) if (!state.torrents.some(item => item.hash === hash)) picked.delete(hash);
  const items = state.torrents.filter(item => torrentFilters[torrentFilter](item) && (torrentCategory === 'all' || item.category === torrentCategory) && (!needle || `${item.name} ${item.category} ${item.tags.join(' ')}`.toLowerCase().includes(needle)));
  const total = key => state.transfer ? state.transfer[key] : Math.min(state.torrents.reduce((sum, item) => sum + liveSpeed(item, key), 0), state.altSpeed ? ALT_SPEED[key] : Infinity);
  const limitText = key => { const limit = state.transfer ? state.transfer[`${key}Limit`] : state.altSpeed ? ALT_SPEED[key] : 0; return `${state.altSpeed ? 'Alt · ' : ''}${limit ? `Limit ${bytes(limit)}/s` : 'No limit'}`; };
  const labels = { all: 'All', downloading: 'Downloading', seeding: 'Seeding', completed: 'Completed', paused: 'Paused' };
  if (!live) return pageHeading('Downloads', 'Manage torrents in qBittorrent.') + banner(['qbittorrent']);
  return pageHeading('Downloads', 'Manage torrents in qBittorrent.', button('Add torrent', 'torrent-add', '', 'primary', 'plus') + `<button type="button" class="button${state.altSpeed ? ' is-on' : ''}" data-action="alt-speed" aria-pressed="${state.altSpeed}" title="Alternative speed limits">${icon('snail')}Alt speed</button>` + button('Pause all', 'torrent-pause-all', '', '', 'pause') + button('Resume all', 'resume-all', '', '', 'play')) +
    banner(['qbittorrent']) + `
    <div class="transfer-bar"><div>${icon('arrow-down')}<span>Download</span><strong>${bytes(total('dl'))}/s</strong><small>${limitText('dl')}</small></div><div>${icon('arrow-up')}<span>Upload</span><strong>${bytes(total('up'))}/s</strong><small>${limitText('up')}</small></div><div>${icon('downloads')}<span>Active</span><strong>${state.torrents.filter(running).length} of ${state.torrents.length}</strong><small>torrents</small></div><div>${icon('storage')}<span>Free space</span><strong>${state.transfer.free == null ? '—' : bytes(state.transfer.free)}</strong><small>${esc(live.defaultSavePath)}</small></div></div>
    <div class="filter-bar"><div class="section-tabs" aria-label="Torrent status">${Object.entries(labels).map(([key, name]) => `<button data-torrent-filter="${key}" class="${torrentFilter === key ? 'selected' : ''}" aria-pressed="${torrentFilter === key}">${name}<span class="count">${state.torrents.filter(torrentFilters[key]).length}</span></button>`).join('')}</div><label class="sort-field">Category<select id="torrent-category"><option value="all">All categories</option><option value=""${torrentCategory === '' ? ' selected' : ''}>Uncategorized</option>${Object.keys(state.categories).map(name => `<option${torrentCategory === name ? ' selected' : ''}>${name}</option>`).join('')}</select></label></div>
    ${picked.size ? `<div class="bulk-bar" role="toolbar" aria-label="Selected torrents"><strong>${picked.size} selected</strong>${button('Select all', 'pick-all', '', '', 'check')}${button('Pause', 'pick-pause', '', '', 'pause')}${button('Resume', 'pick-resume', '', '', 'play')}${button('Delete', 'pick-delete', '', 'danger', 'trash')}${button('Clear', 'pick-clear', '', '', 'close')}</div>` : ''}
    <section class="panel large-list">${items.map(item => torrentRow(item, true)).join('') || '<div class="empty">No torrents match these filters.</div>'}</section>`;
}

function torrentModal(hash) {
  const item = state.torrents.find(entry => entry.hash === hash);
  if (!item) return '<h2 id="modal-title">Torrent removed</h2><p>This torrent is no longer in qBittorrent.</p>';
  const [stateClass, label] = torrentState(item);
  const queueLength = state.torrents.filter(inQueue).length;
  const tabs = [['general', 'General'], ['files', `Files · ${item.files.length}`], ['trackers', `Trackers · ${item.trackers.length}`]];
  const head = `<div class="request-head">${torrentThumb(item, 'request-modal-poster')}<div><span class="eyebrow">QBITTORRENT · <span class="torrent-state is-${stateClass}">${label}</span></span><h2 id="modal-title" class="torrent-title">${esc(item.name)}</h2><p>${categoryChip(item)}${tagChips(item)}</p></div></div>`;
  if (torrentTab === 'delete') return `${head}<h3 class="modal-subtitle">Remove this torrent?</h3><p class="form-note">It will be removed from qBittorrent${item.mediaId ? ' and the linked library title will stop downloading' : ''}.</p><label class="switch-row"><input type="checkbox" class="switch" id="delete-files"> Also delete downloaded files from disk</label><div class="modal-actions">${button('Remove torrent', 'torrent-delete-confirm', hash, 'danger', 'trash')}<button type="button" class="button" data-torrent-tab="general" data-id="${hash}">Cancel</button></div>`;
  const tabBar = `<div class="section-tabs modal-tabs" role="tablist">${tabs.map(([key, name]) => `<button role="tab" data-torrent-tab="${key}" data-id="${hash}" class="${torrentTab === key ? 'selected' : ''}" aria-selected="${torrentTab === key}">${name}</button>`).join('')}</div>`;
  let body;
  if (torrentTab === 'files') {
    body = item.files.length ? `<p class="form-note">Selected ${bytes(selectedSize(item))} of ${bytes(item.files.reduce((sum, file) => sum + file.size, 0))}</p><div class="table-scroll"><table class="data-table"><thead><tr><th>Name</th><th>Size</th><th>Progress</th><th>Priority</th></tr></thead><tbody>${item.files.map((file, index) => `<tr class="${file.priority ? '' : 'is-skipped'}"><td class="file-name">${icon('file')}<span>${esc(file.name.split('/').pop())}</span></td><td>${bytes(file.size)}</td><td><div class="mini-progress"><i style="width:${file.progress}%"></i></div>${file.progress}%</td><td><select class="torrent-priority" data-id="${hash}" data-index="${index}" aria-label="Priority for ${esc(file.name)}">${Object.entries(filePriorities).map(([value, name]) => `<option value="${value}"${Number(value) === file.priority ? ' selected' : ''}>${name}</option>`).join('')}</select></td></tr>`).join('')}</tbody></table></div>` : '<div class="empty">Waiting for metadata from peers. Files appear once the magnet link resolves.</div>';
  } else if (torrentTab === 'trackers') {
    body = `<div class="table-scroll"><table class="data-table"><thead><tr><th>URL</th><th>Status</th><th>Seeds</th><th>Peers</th></tr></thead><tbody>${item.live ? '' : ['** [DHT] **', '** [PeX] **', '** [LSD] **'].map(url => `<tr><td>${url}</td><td>Working</td><td>0</td><td>0</td></tr>`).join('')}${item.trackers.map(tracker => `<tr><td class="tracker-url">${esc(tracker.url)}${tracker.message ? `<small>${esc(tracker.message)}</small>` : ''}</td><td><span class="request-badge${tracker.status === 'Working' ? ' is-approved' : tracker.status === 'Not working' ? ' is-declined' : ''}">${tracker.status}</span></td><td>${tracker.seeds ?? item.seeds[1]}</td><td>${tracker.peers ?? item.peers[1]}</td></tr>`).join('')}</tbody></table></div>`;
  } else {
    const position = queuePosition(item);
    const facts = [['Size', item.files.length ? `${bytes(selectedSize(item))} selected` : 'Unknown'], ['Downloaded', bytes(doneSize(item))], ['Uploaded', bytes(item.uploaded)], ['Ratio', ratio(item)], ['Down speed', `${bytes(liveSpeed(item, 'dl'))}/s${item.dlLimit ? ` · limit ${item.dlLimit} KiB/s` : ''}`], ['Up speed', `${bytes(liveSpeed(item, 'up'))}/s${item.upLimit ? ` · limit ${item.upLimit} KiB/s` : ''}`], ['ETA', item.progress === 100 ? '—' : running(item) ? duration(item.eta) : '∞'], ['Seeds', `${item.seeds[0]} of ${item.seeds[1]}`], ['Peers', `${item.peers[0]} of ${item.peers[1]}`], ['Added', ago(item.added)], ['Save path', esc(item.savePath)], ['Info hash', `<code>${item.hash}</code>`]];
    body = `<div class="download-progress"><div class="progress-track"><i style="width:${item.progress}%"></i></div><strong>${item.progress}%</strong></div>
      <dl class="torrent-facts">${facts.map(([term, value]) => `<div><dt>${term}</dt><dd>${value}</dd></div>`).join('')}</dl>
      <div class="torrent-actions">${button(item.state === 'paused' ? 'Resume' : 'Pause', 'torrent-toggle', hash, 'primary', item.state === 'paused' ? 'play' : 'pause')}<button type="button" class="button${item.forced ? ' is-on' : ''}" data-action="torrent-force" data-id="${hash}" aria-pressed="${item.forced}">${icon('zap')}Force start</button>${button('Recheck', 'torrent-recheck', hash, '', 'recheck')}${button('Reannounce', 'torrent-reannounce', hash, '', 'tower')}${button('Delete', 'torrent-delete', hash, 'danger', 'trash')}</div>
      ${position ? `<div class="queue-controls"><span>Queue position <strong>#${position}</strong> of ${queueLength}</span>${[['top', 'chevrons-up', 'Move to top'], ['up', 'chevron-up', 'Move up'], ['down', 'chevron-down', 'Move down'], ['bottom', 'chevrons-down', 'Move to bottom']].map(([where, name, text]) => `<button type="button" data-action="torrent-queue" data-queue="${where}" data-id="${hash}" aria-label="${text}" title="${text}">${icon(name)}</button>`).join('')}</div>` : ''}
      <form id="torrent-form" data-id="${hash}"><details class="advanced"><summary>${icon('settings')}Torrent options</summary><div class="advanced-grid">
        <label class="form-field">Name<input name="name" value="${esc(item.name)}" required maxlength="255"></label>
        <label class="form-field">Save path<input name="savePath" value="${esc(item.savePath)}" required></label>
        <label class="form-field">Category<select name="category"><option value="">Uncategorized</option>${Object.keys(state.categories).map(name => `<option${item.category === name ? ' selected' : ''}>${name}</option>`).join('')}</select></label>
        <label class="form-field">Tags<input name="tags" value="${esc(item.tags.join(', '))}" placeholder="Comma separated"></label>
        <label class="form-field">Download limit (KiB/s)<input name="dlLimit" type="number" min="0" step="1" value="${item.dlLimit}"></label>
        <label class="form-field">Upload limit (KiB/s)<input name="upLimit" type="number" min="0" step="1" value="${item.upLimit}"></label>
      </div><div class="switch-list"><label class="switch-row"><input type="checkbox" class="switch" name="sequential"${item.sequential ? ' checked' : ''}> Download in sequential order</label><label class="switch-row"><input type="checkbox" class="switch" name="firstLast"${item.firstLast ? ' checked' : ''}> Download first and last pieces first</label></div>
      <div class="modal-actions options-actions"><button type="submit" class="button primary">${icon('check')}Save options</button></div></details></form>`;
  }
  return `${head}${tabBar}<div class="tab-panel" role="tabpanel">${body}</div>`;
}

async function showTorrent(hash, tab = 'general') {
  torrentTab = tab;
  const item = state.torrents.find(entry => entry.hash === hash);
  openModal(torrentModal(hash));
  if (!live || !item) return;
  watch();
  await qbit.details(item).catch(error => toast(error.message));
  if (openTorrent() === item) patch($('#modal .modal-body'), torrentModal(hash));
}
const openTorrent = () => modal.open && $('#modal .torrent-title') && state.torrents.find(entry => entry.hash === $('#modal [data-torrent-tab], #modal [data-action^=torrent][data-id]')?.dataset.id);

async function syncLive() {
  Object.assign(state, await qbit.load(state.torrents));
}

// Runs a qBittorrent call, then reloads the real state so the page shows what qBittorrent actually did.
async function liveDo(work, hash, focus) {
  try {
    const message = await work();
    await syncLive();
    const item = hash && modal.open && $('#modal .torrent-title') && state.torrents.find(entry => entry.hash === hash);
    if (item) await qbit.details(item);
    if (hash) refreshTorrent(hash, focus); else render();
    toast(message);
  } catch (error) {
    toast(error.message);
  }
}

// Patch, never replace: replacing re-creates every poster behind the popup and inside it, which flickers.
function refreshTorrent(hash, focus) {
  render(true);
  if (!modal.open || !$('#modal .torrent-title')) return;
  patch($('#modal .modal-body'), torrentModal(hash));
  if (focus) ($(`#modal ${focus}`) ?? $('#modal [data-torrent-tab].selected'))?.focus();
}

function showAddTorrent() {
  openModal(`<form id="torrent-add-form"><div class="request-head"><span class="torrent-icon request-modal-poster">${icon('magnet')}</span><div><span class="eyebrow">QBITTORRENT</span><h2 id="modal-title">Add torrents</h2><p>Paste magnet links or upload .torrent files.</p></div></div>
    <label class="form-field">Magnet links · one per line<textarea name="magnets" class="magnet-input" placeholder="magnet:?xt=urn:btih:…" spellcheck="false"></textarea></label>
    <label class="form-field file-drop">${icon('upload')}<span><strong>Choose .torrent files</strong><small>or drop them here</small></span><input type="file" name="files" accept=".torrent,application/x-bittorrent" multiple></label>
    <div class="form-grid"><label class="form-field">Category<select name="category"><option value="">Uncategorized</option>${Object.keys(state.categories).map(name => `<option>${name}</option>`).join('')}</select></label><label class="form-field">Save path<input name="savePath" value="${esc(live?.defaultSavePath ?? '/downloads')}" required></label></div>
    <label class="form-field">Tags<input name="tags" placeholder="Comma separated"></label>
    <div class="switch-list"><label class="switch-row"><input type="checkbox" class="switch" name="start" checked> Start torrent</label><label class="switch-row"><input type="checkbox" class="switch" name="top"> Add to top of queue</label><label class="switch-row"><input type="checkbox" class="switch" name="sequential"> Download in sequential order</label><label class="switch-row"><input type="checkbox" class="switch" name="firstLast"> Download first and last pieces first</label></div>
    <p class="form-note">Sent to qBittorrent ${esc(live.version)}.</p>
    <div class="modal-actions"><button type="submit" class="button primary">${icon('plus')}Add torrents</button>${button('Cancel', 'close')}</div></form>`);
}

const requestBadge = item => `<span class="request-badge is-${item.requestStatus}">${{ pending: 'Pending', approved: 'Approved', declined: 'Declined', failed: 'Failed', completed: 'Completed' }[item.requestStatus]}</span>`;
const seasonChips = item => item.seasons?.length ? `<span class="season-chips">${item.seasons.map(season => `<span>S${season}</span>`).join('')}</span>` : '';

function requestRow(item) {
  return `<article class="request">${picture(item, 'request-poster')}<div class="request-info"><div class="request-heading"><button class="request-title" data-action="request" data-id="${item.id}">${esc(item.title)}</button>${requestBadge(item)}</div>
    <p class="request-meta">${item.year ? `${item.year} · ` : ''}${item.type === 'Series' ? 'TV series' : 'Movie'}${item.is4k ? ' · 4K' : ''}${seasonChips(item)}${item.status === 'available' ? ' · In your library' : item.status === 'partial' ? ' · Partly available' : ''}</p>
    <p class="request-user"><span class="viewer-avatar">${esc(item.by[0])}</span>${esc(item.by)} · ${since(item.date)}</p></div>
    <div class="request-actions">${item.requestStatus === 'pending' ? button('Approve', 'seerr-approve', item.request, 'primary', 'check') + button('Decline', 'seerr-decline', item.request, '', 'close') : ''}${button('Delete', 'seerr-delete', item.request, '', 'trash')}</div></article>`;
}

function renderRequests() {
  const all = data.requests?.filter(item => item.title.toLowerCase().includes(query.trim().toLowerCase()));
  const group = item => item.requestStatus === 'completed' ? 'approved' : item.requestStatus;
  const items = all?.filter(item => requestFilter === 'all' || group(item) === requestFilter)
    .sort((a, b) => requestSort === 'title' ? a.title.localeCompare(b.title) : Date.parse(b.date) - Date.parse(a.date));
  const filters = [['all', 'All'], ['pending', 'Pending'], ['approved', 'Approved'], ['declined', 'Declined'], ['failed', 'Failed']];
  return pageHeading('Requests', 'Review, approve and track requests from Seerr.', button('Request something', 'go-discover', '', 'primary', 'plus')) + banner(['seerr']) +
    (!all ? loading('requests') : `<div class="filter-bar"><div class="section-tabs" aria-label="Request status">${filters.map(([value, name]) => `<button class="${requestFilter === value ? 'selected' : ''}" data-request-filter="${value}" aria-pressed="${requestFilter === value}">${name} <span class="count">${value === 'all' ? all.length : all.filter(item => group(item) === value).length}</span></button>`).join('')}</div>
    <label class="sort-field">Sort<select id="request-sort"><option value="recent"${requestSort === 'recent' ? ' selected' : ''}>Most recent</option><option value="title"${requestSort === 'title' ? ' selected' : ''}>Title</option></select></label></div>
    <section class="panel large-list">${items.map(requestRow).join('') || `<div class="empty">${query ? 'No matching requests.' : requestFilter === 'pending' ? 'All caught up. New requests show up here.' : 'No requests here yet.'}</div>`}</section>`);
}

// Seerr-style request: seasons with their status, then the server's quality profile, root folder and tags.
async function showRequest(id) {
  openModal(`<h2 id="modal-title" class="sr-only">Loading</h2>${loader('Loading from Seerr')}`);
  let item;
  try { item = await seerrClient.title(id); } catch (error) { modal.close(); return toast(error.message); }
  if (!modal.open) return;
  const series = item.type === 'Series', open = series ? item.seasons.filter(season => !season.status) : [], { options } = item;
  const done = series ? !open.length : item.status !== 'none' || item.requested;
  const server = options?.server;
  $('#modal .modal-body').innerHTML = `<form id="request-form" data-id="${id}"${server ? ` data-server="${server.id}"` : ''}>
    <div class="request-head">${picture(item, 'request-modal-poster', false)}<div><span class="eyebrow">${series ? 'REQUEST SERIES' : 'REQUEST MOVIE'}</span><h2 id="modal-title">${esc(item.title)}</h2><p>${[item.year, item.genre, item.rating !== '—' && `★ ${item.rating}`].filter(Boolean).map(esc).join(' · ')}</p></div></div>
    ${item.overview ? `<p class="request-overview">${esc(item.overview)}</p>` : ''}
    ${series ? `<table class="season-table"><thead><tr><th>${open.length ? '<input type="checkbox" class="switch" data-all-seasons aria-label="Select all seasons">' : ''}</th><th>Season</th><th># of episodes</th><th>Status</th></tr></thead><tbody>${item.seasons.map(season => `<tr><td>${season.status ? '' : `<input type="checkbox" class="switch" name="season" value="${season.number}" aria-label="Season ${season.number}">`}</td><td>Season ${season.number}</td><td>${season.episodes}</td><td><span class="request-badge${season.status === 'Available' ? ' is-approved' : season.status ? ' is-pending' : ''}">${season.status || 'Not requested'}</span></td></tr>`).join('')}</tbody></table>` : ''}
    ${done ? `<p class="form-note">${item.status === 'available' ? 'Already in your library.' : 'Already requested.'}</p>` : options ? `<details class="advanced"><summary>${icon('settings')}Advanced</summary><div class="advanced-grid"><label class="form-field">Quality profile<select name="profile">${options.profiles.map(profile => `<option value="${profile.id}"${profile.id === server.activeProfileId ? ' selected' : ''}>${esc(profile.name)}</option>`).join('')}</select></label><label class="form-field">Root folder<select name="folder">${options.rootFolders.map(folder => `<option${folder.path === server.activeDirectory ? ' selected' : ''}>${esc(folder.path)}</option>`).join('')}</select></label></div>${options.tags.length ? `<fieldset class="tag-picker"><legend>Tags</legend>${options.tags.map(tag => `<label><input type="checkbox" name="tag" value="${tag.id}"${server.activeTags?.includes(tag.id) ? ' checked' : ''}>${icon('tag')}${esc(tag.label)}</label>`).join('')}</fieldset>` : ''}</details>
    <p class="form-note">Sent through Seerr to ${esc(server.name)}.</p>` : `<p class="form-note">Seerr has no ${series ? 'Sonarr' : 'Radarr'} server yet, so it will keep the request until one is added.</p>`}
    <div class="modal-actions">${done ? '' : `<button type="submit" class="button primary"${series ? ' disabled' : ''}>${icon('downloads')}<span>${series ? 'Select season(s)' : 'Request'}</span></button>`}${button(done ? 'Close' : 'Cancel', 'close')}</div></form>`;
  $('#modal [name=season], #modal [type=submit], #modal .modal-actions button')?.focus();
}

const setupHelp = { seerr: 'Find the API key in Seerr › Settings › General.', qbittorrent: 'The WebUI address. Leave the login empty if qBittorrent skips authentication for this host.', sonarr: 'Find the API key in Sonarr › Settings › General.', radarr: 'Find the API key in Radarr › Settings › General.', jellyfin: 'Create an API key in Jellyfin › Dashboard › API Keys.' };

function serviceCard(service) {
  const saved = configured[service.id], on = isLive(service), keep = saved?.hasSecret ? ' placeholder="Saved · leave blank to keep"' : '';
  return `<section class="panel connection-card" id="service-${service.id}"><div class="connection-head">${serviceIcon(service)}<div><h2>${service.name}</h2><p>${service.description}</p></div>${saved ? `<span class="connected${on ? ' is-live' : ''}"><span class="status-dot"></span>${on ? `Live · ${esc(liveVersion(service))}` : 'Offline'}</span>` : ''}</div>
    <form class="service-form" data-service="${service.id}"><label class="form-field">Address<input name="url" type="url" pattern="https?://.+" title="An http:// or https:// address" required placeholder="http://192.168.1.10:${service.port}" value="${esc(saved?.url ?? '')}" autocomplete="off" spellcheck="false"></label>
    ${service.id === 'qbittorrent' ? `<div class="form-grid"><label class="form-field">Username<input name="user" value="${esc(saved?.user ?? '')}" autocomplete="off"></label><label class="form-field">Password<input name="pass" type="password" autocomplete="new-password"${keep}></label></div>` : `<label class="form-field">API key<input name="key" type="password" autocomplete="off" spellcheck="false"${keep || ' required'}></label>`}
    <p class="connection-note">${setupHelp[service.id]}</p><div class="modal-actions"><button class="button primary" type="submit">${icon(saved ? 'check' : 'plus')}${saved ? 'Save & test' : 'Connect'}</button>${saved ? button('Remove', 'service-remove', service.id, '', 'trash') : ''}</div></form></section>`;
}

function renderConnections() {
  if (!configured) return pageHeading('Welcome to Unifiarr', 'Bring Jellyfin, Sonarr, Radarr, Seerr and qBittorrent together.', '', 'SETUP') + '<div class="demo-banner">Unifiarr’s server isn’t answering. Start it with <code>node server.mjs</code> and open the address it prints; your services are set up from there.</div>';
  const fresh = !Object.keys(configured).length;
  return (fresh ? '<img class="welcome-mark" src="assets/unifiarr.svg" alt="" width="56" height="56">' : '') + pageHeading(fresh ? 'Welcome to Unifiarr' : 'Your services', fresh ? 'Connect your first service to get started. Only the services you set up appear in Unifiarr.' : 'Connect, update or remove the services Unifiarr manages.', '', fresh ? 'SETUP' : '') +
    `<div class="connection-list">${services.map(serviceCard).join('')}</div><p class="form-note">Addresses and keys are saved by Unifiarr’s server and never sent back to your browser.</p>${fresh ? '' : `<section class="panel connection-card"><h2>Display</h2><label class="switch-row"><input type="checkbox" class="switch" id="live-banners"${localStorage.banners === 'show' ? ' checked' : ''}> Show “Connected to …” banners on pages</label><p class="connection-note">Warnings about services that can’t be reached always show.</p></section>`}`;
}

function renderCalendar() {
  const items = data.calendar?.filter(item => item.title.toLowerCase().includes(query.toLowerCase()));
  return pageHeading('Coming to your screen.', 'Upcoming episodes and movie releases for the next two weeks.') + banner(['sonarr', 'radarr']) +
    (!items ? loading('the calendar') : Object.values(Object.groupBy(items, item => new Date(item.date).toDateString())).map(list => {
      const date = new Date(list[0].date);
      return `<section class="calendar-day"><div class="calendar-date">${date.toLocaleDateString('en', { weekday: 'short' })}<b>${date.getDate()}</b>${date.toLocaleDateString('en', { month: 'short' })}</div><div class="calendar-items">${list.map(item => `<button class="panel calendar-entry${item.hasFile ? ' is-downloaded' : ''}" data-action="arr-title" data-service="${item.service}" data-id="${item.titleId}">${picture(item)}<div><h3>${esc(item.title)}</h3><p>${esc(item.detail)}${item.service === 'sonarr' ? ` · ${date.toLocaleTimeString('en', { hour: 'numeric', minute: '2-digit' })}` : ''}${item.hasFile ? ' · Downloaded' : ''}</p></div>${serviceIcon(services.find(service => service.id === item.service))}</button>`).join('')}</div></section>`;
    }).join('') || '<div class="empty">Nothing scheduled in the next two weeks.</div>');
}

function banner(ids) {
  const mine = ids.filter(has), on = mine.filter(id => isLive({ id })), off = mine.filter(id => !isLive({ id })), names = list => list.map(serviceName).join(' and ');
  return (on.length && localStorage.banners === 'show' ? `<div class="demo-banner live-banner"><strong>Connected to ${on.map(id => `${serviceName(id)} ${esc(liveVersion({ id }))}`).join(' and ')}.</strong> Changes here apply to your real server.</div>` : '') +
    (off.length ? `<div class="demo-banner offline-banner"><strong>Can’t reach ${names(off)}.</strong> Check that it’s running, or update its address in ${link('Services', 'connections')}</div>` : '');
}
const loading = what => `<div class="empty" aria-busy="true">Loading ${what}…</div>`;
const isType = item => type === 'All' || (type === 'Series') === (item.service === 'sonarr');
const profileName = item => data.profiles?.[item.service]?.find(profile => profile.id === item.profileId)?.name ?? '';
const fileSummary = item => item.service === 'sonarr' ? `${item.files} of ${item.total} episodes` : item.files ? 'Downloaded' : 'Missing';
const monitorButton = (on, action, attributes, label) => `<button type="button" class="monitor-toggle${on ? ' is-on' : ''}" data-action="${action}" ${attributes} aria-pressed="${on}" aria-label="${on ? 'Unmonitor' : 'Monitor'} ${esc(label)}" title="${on ? 'Monitored' : 'Unmonitored'}">${icon('bookmark')}</button>`;
const serviceTag = id => `<span class="service-tag">${serviceIcon(services.find(service => service.id === id))}${serviceName(id)}</span>`;

function renderManage() {
  const items = data.titles?.filter(item => isType(item) && item.title.toLowerCase().includes(query.toLowerCase()));
  return pageHeading('Series & movies', 'Everything Sonarr and Radarr are watching for.', button('Wanted', 'go-wanted', '', '', 'wanted')) + banner(['sonarr', 'radarr']) +
    `<div class="filter-bar">${tabs()}<p>${items ? `${items.length} titles · ${items.filter(item => item.monitored).length} monitored` : ''}</p></div>` +
    (!items ? loading('titles') : items.length ? `<div class="poster-grid full-grid">${items.map(item => `<button class="poster-card arr-card${item.monitored ? '' : ' is-unmonitored'}" data-action="arr-title" data-service="${item.service}" data-id="${item.id}" aria-label="Manage ${esc(item.title)}"><div class="poster-wrap">${picture(item)}${profileName(item) ? `<span class="quality-label">${esc(profileName(item))}</span>` : ''}<span class="available-label${item.files < item.total ? ' is-missing' : ''}"><span class="status-dot"></span>${fileSummary(item)}</span></div><h3>${esc(item.title)}</h3><p><span>${item.year} <span>·</span> ${item.service === 'sonarr' ? 'TV Series' : 'Movie'}</span><span class="monitor-state${item.monitored ? ' is-on' : ''}" title="${item.monitored ? 'Monitored' : 'Unmonitored'}">${icon('bookmark')}</span></p></button>`).join('')}</div>` : '<div class="empty">Nothing here yet. Titles you add in Sonarr or Radarr show up here.</div>');
}

function titleModal() {
  const t = current, sonarr = t.service === 'sonarr', attrs = `data-service="${t.service}" data-id="${t.id}"`;
  const head = `<div class="request-head">${picture(t, 'request-modal-poster', false)}<div><span class="eyebrow">${serviceName(t.service).toUpperCase()} · ${esc(t.status.toUpperCase())}</span><h2 id="modal-title" class="arr-title">${esc(t.title)}</h2><p>${[t.year, t.network, profileName(t), bytes(t.size)].filter(Boolean).map(esc).join(' · ')}</p><p class="arr-badges"><span class="request-badge${t.monitored ? ' is-approved' : ''}">${t.monitored ? 'Monitored' : 'Unmonitored'}</span><span class="request-badge${t.files < t.total ? ' is-declined' : ' is-approved'}">${fileSummary(t)}</span>${t.quality && t.files ? `<span class="request-badge">${esc(t.quality)}</span>` : ''}</p></div></div>`;
  if (titleTab === 'delete') return `${head}<h3 class="modal-subtitle">Delete ${esc(t.title)}?</h3><p class="form-note">It will be removed from ${serviceName(t.service)} and no longer monitored.</p><label class="switch-row"><input type="checkbox" class="switch" id="arr-delete-files"> Also delete ${sonarr ? 'the series folder' : 'the movie folder'} and its files</label><div class="modal-actions">${button('Delete', 'arr-delete-confirm', t.id, 'danger', 'trash').replace('data-action', `data-service="${t.service}" data-action`)}<button type="button" class="button" data-title-tab="overview">Cancel</button></div>`;
  if (titleTab === 'edit') return `${head}<form id="arr-edit"><div class="switch-list"><label class="switch-row"><input type="checkbox" class="switch" name="monitored"${t.monitored ? ' checked' : ''}> Monitored</label></div><div class="form-grid"><label class="form-field">Quality profile<select name="profile">${(data.profiles?.[t.service] ?? []).map(profile => `<option value="${profile.id}"${profile.id === t.profileId ? ' selected' : ''}>${esc(profile.name)}</option>`).join('')}</select></label><label class="form-field">Path<input name="path" value="${esc(t.path)}" required></label></div><p class="form-note">Changing the path moves existing files to the new folder.</p><div class="modal-actions"><button type="submit" class="button primary">${icon('check')}Save changes</button><button type="button" class="button" data-title-tab="overview">Cancel</button></div></form>`;
  const now = Date.now();
  const seasons = sonarr ? `<h3 class="modal-subtitle">Seasons</h3><div class="season-list">${t.seasons.map(season => {
    const episodes = t.episodes.filter(episode => episode.season === season.number), name = season.number ? `Season ${season.number}` : 'Specials';
    return `<section class="season"><div class="season-head">${monitorButton(season.monitored, 'arr-monitor-season', `data-season="${season.number}"`, name)}<b>${name}</b><span class="season-count">${season.files} / ${season.total}</span><div class="mini-progress"><i style="width:${season.total ? season.files / season.total * 100 : 0}%"></i></div><button type="button" data-action="arr-search" data-season="${season.number}" aria-label="Search ${name}" title="Automatic search">${icon('search')}</button><button type="button" data-action="arr-releases" data-season="${season.number}" aria-label="Interactive search for ${name}" title="Interactive search">${icon('interactive')}</button></div>
      <details data-season="${season.number}"><summary>${episodes.length} episodes</summary><div class="table-scroll"><table class="data-table episode-table"><tbody>${episodes.map(episode => {
        const aired = episode.airDate && Date.parse(episode.airDate) < now;
        return `<tr><td>${monitorButton(episode.monitored, 'arr-monitor-episode', `data-episode="${episode.id}"`, episode.code)}</td><td>${episode.number}</td><td class="episode-title">${esc(episode.title)}</td><td>${episode.airDate ? day(episode.airDate) : 'TBA'}</td><td><span class="request-badge${episode.hasFile ? ' is-approved' : aired ? ' is-declined' : ''}">${episode.hasFile ? 'Downloaded' : aired ? 'Missing' : 'Unaired'}</span></td><td class="row-actions"><button type="button" data-action="arr-search" data-episode="${episode.id}" aria-label="Search ${episode.code}" title="Automatic search">${icon('search')}</button><button type="button" data-action="arr-releases" data-episode="${episode.id}" aria-label="Interactive search for ${episode.code}" title="Interactive search">${icon('interactive')}</button></td></tr>`;
      }).join('')}</tbody></table></div></details></section>`;
  }).join('')}</div>` : '';
  return `${head}${t.overview ? `<p class="arr-overview">${esc(t.overview)}</p>` : ''}<dl class="torrent-facts"><div><dt>Path</dt><dd>${esc(t.path)}</dd></div><div><dt>Size on disk</dt><dd>${bytes(t.size)}</dd></div></dl>
    <div class="torrent-actions">${button(sonarr ? 'Search monitored' : 'Search movie', 'arr-search', '', 'primary', 'search')}${sonarr ? '' : button('Interactive search', 'arr-releases', '', '', 'interactive')}${button('Refresh & scan', 'arr-refresh', '', '', 'refresh')}<button type="button" class="button" data-title-tab="edit">${icon('pencil')}Edit</button><button type="button" class="button danger" data-title-tab="delete">${icon('trash')}Delete</button></div>${seasons}`.replace(/data-action="arr-/g, `${attrs} data-action="arr-`);
}

async function showTitle(service, id, tab = 'overview') {
  titleTab = tab;
  openModal(`<h2 id="modal-title" class="sr-only">Loading</h2>${loader(`Loading from ${serviceName(service)}`)}`);
  const waiting = modal.querySelector('.loader');
  try {
    data.profiles ??= {};
    [current, data.profiles[service]] = await Promise.all([arr(service).title(id), data.profiles[service] ?? arr(service).profiles()]);
  } catch (error) {
    if (waiting.isConnected) modal.close();
    return toast(error.message);
  }
  if (waiting.isConnected) $('#modal .modal-body').innerHTML = titleModal();
}

async function refreshTitle(focus) {
  const open = [...modal.querySelectorAll('details[data-season][open]')].map(details => details.dataset.season);
  current = await arr(current.service).title(current.id);
  if (!modal.open || !$('#modal .arr-title')) return;
  $('#modal .modal-body').innerHTML = titleModal();
  open.forEach(season => $(`#modal details[data-season="${season}"]`)?.setAttribute('open', ''));
  ($(`#modal ${focus}`) ?? $('#modal .arr-title'))?.focus?.();
}

function releasesModal() {
  const { service, label, list, error, titleId } = releases;
  const rows = list?.map((release, index) => `<tr class="${release.approved ? '' : 'is-rejected'}"><td class="release-title"><span>${esc(release.title)}</span>${release.rejections.length ? `<small>${release.rejections.map(esc).join(' · ')}</small>` : ''}</td><td>${esc(release.indexer)}</td><td>${bytes(release.size)}</td><td>${release.age < 48 ? `${Math.round(release.age)} h` : `${Math.round(release.age / 24)} d`}</td><td>${release.protocol === 'torrent' ? `${release.seeders ?? 0} / ${release.leechers ?? 0}` : 'Usenet'}</td><td><span class="request-badge">${esc(release.quality)}</span></td><td><button type="button" class="button${release.approved ? ' primary' : ''}" data-action="release-grab" data-index="${index}"${release.grabbed ? ' disabled' : ''} aria-label="Download ${esc(release.title)}">${icon(release.grabbed ? 'check' : 'downloads')}${release.grabbed ? 'Sent' : 'Grab'}</button></td></tr>`).join('');
  return `<div class="request-head"><span class="torrent-icon request-modal-poster">${icon('interactive')}</span><div><span class="eyebrow">${serviceName(service).toUpperCase()} · INTERACTIVE SEARCH</span><h2 id="modal-title" class="releases-title">${esc(label)}</h2><p>${list ? `${list.length} releases · ${list.filter(release => release.approved).length} match your profile` : error ? '' : 'Searching your indexers… this can take a minute.'}</p></div></div>
    ${error ? `<div class="empty">${esc(error)}</div>` : !list ? loader('Searching indexers') : list.length ? `<div class="table-scroll"><table class="data-table release-table"><thead><tr><th>Release</th><th>Indexer</th><th>Size</th><th>Age</th><th>Peers</th><th>Quality</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>` : '<div class="empty">No releases found. Check that your indexers are set up in ' + serviceName(service) + '.</div>'}
    <div class="modal-actions">${titleId ? `<button type="button" class="button" data-action="arr-title" data-service="${service}" data-id="${titleId}">${icon('arrow')}Back to title</button>` : ''}${button('Close', 'close')}</div>`;
}

async function showReleases(service, target, label, titleId) {
  const search = releases = { service, target, label, titleId, list: null };
  openModal(releasesModal());
  try { search.list = await arr(service).releases(target); } catch (error) { search.error = error.message; }
  if (releases === search && modal.open && $('#modal .releases-title')) $('#modal .modal-body').innerHTML = releasesModal();
}

function renderWanted() {
  const label = { missing: 'Missing', cutoff: 'Cutoff unmet' };
  const records = data.wanted && ['sonarr', 'radarr'].flatMap(service => data.wanted[service]?.[wantedFilter]?.records ?? []).filter(record => isType(record) && record.title.toLowerCase().includes(query.toLowerCase()));
  const total = kind => data.wanted ? ['sonarr', 'radarr'].reduce((sum, service) => sum + (data.wanted[service]?.[kind]?.total ?? 0), 0) : '…';
  return pageHeading('Wanted', 'Aired episodes and movies you don’t have yet, and files below your quality cutoff.', button(`Search all ${label[wantedFilter].toLowerCase()}`, 'wanted-search-all', '', 'primary', 'search')) + banner(['sonarr', 'radarr']) +
    `<div class="filter-bar"><div class="section-tabs" aria-label="Wanted list">${Object.entries(label).map(([key, name]) => `<button data-wanted-filter="${key}" class="${wantedFilter === key ? 'selected' : ''}" aria-pressed="${wantedFilter === key}">${name}<span class="count">${total(key)}</span></button>`).join('')}</div>${tabs()}</div>` +
    (!records ? loading('wanted items') : `<section class="panel large-list">${records.map(record => `<article class="request wanted-row">${picture(record, 'request-poster')}<div class="request-info"><div class="request-heading"><button class="request-title" data-action="arr-title" data-service="${record.service}" data-id="${record.titleId}">${esc(record.title)}</button><span class="request-badge ${wantedFilter === 'missing' ? 'is-declined' : 'is-pending'}">${label[wantedFilter]}</span></div><div class="request-meta">${esc(record.detail)}${record.date ? ` · ${record.service === 'sonarr' ? 'Aired' : 'Released'} ${day(record.date)}` : ''}</div><div class="request-user">${serviceTag(record.service)}</div></div><div class="request-actions"><button data-action="wanted-search" data-service="${record.service}" data-id="${record.id}" aria-label="Search for ${esc(record.title)} ${esc(record.detail)}" title="Automatic search">${icon('search')}</button><button data-action="wanted-releases" data-service="${record.service}" data-id="${record.id}" aria-label="Interactive search for ${esc(record.title)} ${esc(record.detail)}" title="Interactive search">${icon('interactive')}</button></div></article>`).join('') || `<div class="empty">${wantedFilter === 'missing' ? 'Nothing missing. Every monitored episode and movie is on disk.' : 'Everything meets its quality cutoff.'}</div>`}</section>${records.length < total(wantedFilter) ? `<p class="form-note">Showing the newest ${records.length} of ${total(wantedFilter)}.</p>` : ''}`);
}

const events = { grabbed: ['Grabbed', 'is-pending', 'downloads'], downloadFolderImported: ['Imported', 'is-approved', 'check'], downloadFailed: ['Failed', 'is-declined', 'close'], episodeFileDeleted: ['Deleted', '', 'trash'], movieFileDeleted: ['Deleted', '', 'trash'], episodeFileRenamed: ['Renamed', '', 'pencil'], movieFileRenamed: ['Renamed', '', 'pencil'], downloadIgnored: ['Ignored', '', 'ban'], seriesFolderImported: ['Imported', 'is-approved', 'check'], movieFolderImported: ['Imported', 'is-approved', 'check'] };

const logTones = { Warning: 'is-pending', Error: 'is-declined', Critical: 'is-declined', Fatal: 'is-declined' };
function renderActivity() {
  const sources = ['sonarr', 'radarr', 'qbittorrent', 'jellyfin'].filter(has), shows = id => activitySource === 'all' || activitySource === id;
  if (!sources.includes(activitySource)) activitySource = 'all';
  const queue = data.queue?.filter(item => shows(item.service)), arrQueue = sources.some(id => arrs().includes(id) && shows(id));
  const parts = [['sonarr', data.history?.filter(item => item.service === 'sonarr')], ['radarr', data.history?.filter(item => item.service === 'radarr')], ['qbittorrent', data.qlog], ['jellyfin', data.log?.map(entry => ({ service: 'jellyfin', title: entry.name, detail: entry.detail, kind: entry.severity, date: entry.date }))]].filter(([id]) => sources.includes(id) && shows(id));
  const history = parts.some(([, list]) => !list) ? null : parts.flatMap(([, list]) => list).sort((a, b) => Date.parse(b.date) - Date.parse(a.date)).slice(0, 120);
  const row = entry => {
    if (entry.event) { const [name, tone, symbol] = events[entry.event] ?? [entry.event, '', 'info']; return `<article class="history-row"><span class="history-icon ${tone}">${icon(symbol)}</span><div><b>${esc(entry.title)}${entry.detail ? ` · ${esc(entry.detail)}` : ''}</b><small>${serviceName(entry.service)} · ${esc(entry.release)}</small></div><span class="request-badge ${tone}">${name}</span>${entry.quality ? `<span class="category-chip">${esc(entry.quality)}</span>` : '<span></span>'}<span class="activity-time">${since(entry.date)}</span></article>`; }
    const tone = logTones[entry.kind] ?? '';
    return `<article class="history-row"><span class="history-icon">${serviceIcon(services.find(service => service.id === entry.service))}</span><div><b title="${esc(entry.title)}">${esc(entry.title)}</b><small>${serviceName(entry.service)}${entry.detail ? ` · ${esc(entry.detail)}` : ''}</small></div><span class="request-badge ${tone}">${esc(entry.kind)}</span><span></span><span class="activity-time">${since(entry.date)}</span></article>`;
  };
  const filter = `<div class="section-tabs" aria-label="Source">${[['all', 'All'], ...sources.map(id => [id, serviceName(id)])].map(([id, name]) => `<button class="${activitySource === id ? 'selected' : ''}" data-source="${id}" aria-pressed="${activitySource === id}">${name}</button>`).join('')}</div>`;
  return pageHeading('Activity', 'Downloads, imports and logs from all your services.', has('qbittorrent') ? button('Downloads', 'go-downloads', '', '', 'downloads') : '') + banner(sources) + `<div class="filter-bar">${filter}<span></span></div>
    ${arrQueue ? `<section class="sec"><div class="section-title"><h2>Queue <span class="count">${queue?.length ?? '…'}</span></h2></div><div class="panel large-list">${!queue ? loading('queue') : queue.map(item => `<article class="torrent queue-item is-${item.warning ? 'error' : item.status === 'paused' ? 'paused' : 'downloading'}">${picture(item, 'torrent-thumb')}<div class="torrent-info"><button class="torrent-name" data-action="arr-title" data-service="${item.service}" data-id="${item.titleId}">${esc(item.title)}${item.detail ? ` · ${esc(item.detail)}` : ''}</button><div class="torrent-meta"><span class="torrent-state is-${item.warning ? 'error' : item.status === 'paused' ? 'paused' : 'downloading'}">${esc(item.status || 'queued')}</span>${item.quality ? `<span class="category-chip">${esc(item.quality)}</span>` : ''}${serviceTag(item.service)}</div><div class="download-progress"><div class="progress-track"><i style="width:${item.progress}%"></i></div><strong>${item.progress}%</strong></div><div class="torrent-stats"><span class="release-name">${esc(item.release)}</span>${item.size ? `<span>${bytes(item.left)} left of ${bytes(item.size)}</span>` : ''}${item.timeleft ? `<span>ETA ${esc(item.timeleft)}</span>` : ''}${item.client ? `<span>${esc(item.client)}</span>` : ''}</div>${item.messages.length ? `<p class="queue-message">${item.messages.map(esc).join(' · ')}</p>` : ''}</div><div class="torrent-controls"><button data-action="queue-remove" data-service="${item.service}" data-id="${item.id}" aria-label="Remove ${esc(item.title)} from queue" title="Remove from queue">${icon('trash')}</button></div></article>`).join('') || '<div class="empty">Nothing in the queue. Grabbed releases show up here while they download.</div>'}</div></section>` : ''}
    <section class="sec"><div class="section-title"><h2>History</h2></div><div class="panel large-list">${!history ? loading('history') : history.map(row).join('') || '<div class="empty">No history yet.</div>'}</div></section>`;
}

function renderServer() {
  const { users, devices, tasks, log } = data;
  const panel = (title, iconName, body, count) => `<section class="panel server-panel"><div class="section-title"><h2>${icon(iconName)}${title}${count !== undefined ? ` <span class="count">${count}</span>` : ''}</h2></div>${body}</section>`;
  return pageHeading('Jellyfin server', 'Users, devices, scheduled tasks and what happened on your server.', button('Add user', 'user-add', '', 'primary', 'user-plus') + button('Scan all libraries', 'library-scan', '', '', 'refresh')) + banner(['jellyfin']) + `<div class="server-grid">
    ${panel('Users', 'users', !users ? loading('users') : users.map(user => `<div class="server-row${user.disabled ? ' is-disabled' : ''}"><span class="viewer-avatar">${esc(user.name[0].toUpperCase())}</span><div><b>${esc(user.name)}</b><small>${user.admin ? 'Administrator · ' : ''}${user.disabled ? 'Disabled' : `Active ${since(user.lastActive).toLowerCase()}`}</small></div>${user.admin ? '' : `<div class="row-actions"><button type="button" class="button" data-action="user-toggle" data-id="${user.id}" aria-label="${user.disabled ? 'Enable' : 'Disable'} ${esc(user.name)}">${icon(user.disabled ? 'check' : 'ban')}${user.disabled ? 'Enable' : 'Disable'}</button><button type="button" data-action="user-delete" data-id="${user.id}" aria-label="Delete ${esc(user.name)}" title="Delete user">${icon('trash')}</button></div>`}</div>`).join(''), users?.length)}
    ${panel('Devices', 'monitor', !devices ? loading('devices') : devices.map(device => `<div class="server-row"><span class="viewer-avatar">${icon('monitor')}</span><div><b>${esc(device.device)}</b><small>${esc(device.client)} · ${esc(device.user)} · ${since(device.lastActive).toLowerCase()}${device.item ? ` · ▶ ${esc(device.item.title)}` : ''}</small></div><div class="row-actions"><button type="button" class="button" data-action="playback-compose" data-id="${device.id}" aria-label="Message ${esc(device.user)} on ${esc(device.device)}">${icon('requests')}Message</button></div></div>`).join('') || '<div class="empty">No devices active in the last 15 minutes.</div>', devices?.length)}
    ${panel('Scheduled tasks', 'history', !tasks ? loading('tasks') : tasks.map(task => `<div class="server-row task-row"><div><b>${esc(task.name)}</b><small>${esc(task.category)} · ${task.state === 'Running' ? `Running · ${Math.round(task.progress)}%` : task.result ? `${task.result.status === 'Completed' ? 'Last ran' : task.result.status} ${since(task.result.end).toLowerCase()}` : 'Never run'}</small>${task.state === 'Running' ? `<div class="mini-progress"><i style="width:${task.progress}%"></i></div>` : ''}</div>${task.result && task.result.status !== 'Completed' && task.state !== 'Running' ? `<span class="request-badge is-declined">${esc(task.result.status)}</span>` : '<span></span>'}<button type="button" class="button" data-action="task-${task.state === 'Idle' ? 'run' : 'stop'}" data-id="${task.id}" aria-label="${task.state === 'Idle' ? 'Run' : 'Stop'} ${esc(task.name)}">${icon(task.state === 'Idle' ? 'play' : 'stop')}${task.state === 'Idle' ? 'Run' : 'Stop'}</button></div>`).join(''))}
    ${panel('Server activity', 'list', !log ? loading('activity') : log.map(entry => `<div class="server-row log-row"><span class="severity is-${esc(entry.severity.toLowerCase())}" title="${esc(entry.severity)}"></span><div><b>${esc(entry.name)}</b>${entry.detail ? `<small>${esc(entry.detail)}</small>` : ''}</div><span class="activity-time">${since(entry.date)}</span></div>`).join('') || '<div class="empty">No activity yet.</div>')}
  </div>`;
}

const discoverKey = () => `${type}|${query.trim()}`;
const unique = items => [...new Map(items.map(item => [item.id, item])).values()];
let loadingMore = false;
async function moreDiscover() {
  if (loadingMore || view !== 'discover' || !data.discoverMore || data.discoverKey !== discoverKey()) return;
  loadingMore = true;
  const key = discoverKey(), page = data.discoverPages + 1, result = await settle(sr().discover(type, query.trim(), page));
  loadingMore = false;
  if (!result || key !== discoverKey() || data.discoverPages !== page - 1) return;
  Object.assign(data, { discover: unique([...data.discover, ...result.items]), discoverPages: page, discoverMore: result.more });
  render(true);
}
const moreObserver = new IntersectionObserver(entries => entries.some(entry => entry.isIntersecting) && moreDiscover(), { rootMargin: '800px' });
const settle = promise => promise.catch(error => { toast(error.message); return undefined; });
// Loads what the current page needs from Sonarr/Radarr/Jellyfin, then re-renders if the page is still open.
async function loadView() {
  const page = view;
  if (page === 'manage') {
    data.profiles ??= {};
    const [series, movies, sonarrProfiles, radarrProfiles] = await Promise.all([settle(arr('sonarr').titles()), settle(arr('radarr').titles()), settle(arr('sonarr').profiles()), settle(arr('radarr').profiles())]);
    Object.assign(data.profiles, { sonarr: sonarrProfiles ?? [], radarr: radarrProfiles ?? [] });
    data.titles = [...series ?? [], ...movies ?? []].sort((a, b) => a.title.localeCompare(b.title));
  } else if (page === 'wanted') {
    const pages = await Promise.all(['sonarr', 'radarr'].flatMap(service => ['missing', 'cutoff'].map(kind => settle(arr(service).wanted(kind)))));
    data.wanted = { sonarr: { missing: pages[0], cutoff: pages[1] }, radarr: { missing: pages[2], cutoff: pages[3] } };
  } else if (page === 'activity') {
    const on = id => has(id) ? undefined : [], arrCall = (id, kind) => on(id) ?? settle(arr(id)[kind]());
    const [sq, rq, sh, rh, qlog, log] = await Promise.all([arrCall('sonarr', 'queue'), arrCall('radarr', 'queue'), arrCall('sonarr', 'history'), arrCall('radarr', 'history'), on('qbittorrent') ?? settle(qbit.log()), on('jellyfin') ?? settle(jf().log())]);
    data.queue = [...sq ?? [], ...rq ?? []];
    data.history = [...sh ?? [], ...rh ?? []];
    data.qlog = qlog ?? [];
    data.log = log ?? [];
  } else if (page === 'server') {
    const client = jf();
    [data.users, data.devices, data.tasks, data.log] = await Promise.all([settle(client.users()), settle(client.devices(state.sessions)), settle(client.tasks()), settle(client.log())]);
  } else if (page === 'discover') {
    // Reloads (e.g. after a request) refresh every page already scrolled through, so the list keeps its place.
    const key = discoverKey(), pages = data.discoverKey === key ? data.discoverPages : 1;
    const results = await settle(Promise.all(Array.from({ length: pages }, (_, index) => sr().discover(type, query.trim(), index + 1))));
    if (key !== discoverKey()) return;
    Object.assign(data, { discoverKey: key, discoverPages: pages, discover: results && unique(results.flatMap(result => result.items)), discoverMore: !!results?.at(-1).more });
  } else if (page === 'downloads') {
    // Sonarr/Radarr titles and Seerr requests only supply torrent posters here, so load them once.
    const [series, movies, requests] = await Promise.all([!data.titles && settle(arr('sonarr').titles()), !data.titles && settle(arr('radarr').titles()), !data.requests && settle(sr().requests())]);
    if (series || movies) data.titles = [...series || [], ...movies || []];
    if (requests) data.requests = requests;
  } else if (page === 'requests') {
    data.requests = await settle(sr().requests()) ?? data.requests;
  } else if (page === 'calendar') {
    data.calendar = (await each('calendar', ...span(14))).sort((a, b) => Date.parse(a.date) - Date.parse(b.date));
  } else if (page === 'overview') {
    const [sessions, log, history, calendar, requests, qlog, checks] = await Promise.all([settle(jf().sessions(state.sessions)), settle(jf().log()), each('history'), each('calendar', ...span(7)), settle(sr().requests()), has('qbittorrent') ? settle(qbit.log()) : [], Promise.all(arrs().filter(service => connected[service]).map(async service => ({
      service, health: await settle(api(service, 'health')) ?? [], disks: await settle(api(service, 'diskspace')) ?? [],
    })))]);
    data.requests = requests ?? data.requests;
    data.warnings = arrWarnings(checks);
    if (sessions) state.sessions = playing(sessions);
    data.feed = [...history.map(entry => ({ title: `${events[entry.event]?.[0] ?? 'Updated'} · ${entry.title}`, detail: entry.detail, service: entry.service, date: entry.date })), ...(log ?? []).map(entry => ({ title: entry.name, detail: entry.detail, service: 'jellyfin', date: entry.date })), ...(qlog ?? []).map(entry => ({ title: entry.title, detail: ['Warning', 'Critical'].includes(entry.kind) ? entry.kind : '', service: 'qbittorrent', date: entry.date }))]
      .sort((a, b) => Date.parse(b.date) - Date.parse(a.date)).slice(0, 6);
    data.calendar = calendar.sort((a, b) => Date.parse(a.date) - Date.parse(b.date));
  } else return;
  if (view === page) quietRender();
}

// Re-render without stealing focus or wiping what someone is typing.
function quietRender() {
  if (main.contains(document.activeElement) && document.activeElement.matches('input, select, textarea')) return renderNavigation();
  render(true);
}

// Updates only the nodes that changed, so images, focus and scroll stay put.
function morph(from, to) {
  if (from.isEqualNode(to)) return;
  if (from.nodeName !== to.nodeName || from.nodeType !== Node.ELEMENT_NODE) return from.replaceWith(to);
  for (const { name } of [...from.attributes]) if (!to.hasAttribute(name)) from.removeAttribute(name);
  for (const { name, value } of to.attributes) if (from.getAttribute(name) !== value) from.setAttribute(name, value);
  const old = [...from.childNodes], next = [...to.childNodes];
  next.forEach((node, index) => old[index] ? morph(old[index], node) : from.append(node));
  old.slice(next.length).forEach(node => node.remove());
}

// Runs a Sonarr/Radarr/Jellyfin action, then reloads the page data so it shows what the service actually did.
async function serviceDo(service, work, success) {
  try {
    const result = await work();
    toast(typeof result === 'string' ? result : success);
    await loadView();
    return true;
  } catch (error) {
    toast(error.message);
    return false;
  }
}

async function syncLibrary() {
  const result = await jellyfinClient.library();
  libraries.splice(0, libraries.length, ...result.libraries);
  state.media = state.media.filter(item => item.status !== 'available' && !item.jellyfin).concat(result.items);
}

function render(quiet) {
  renderNavigation();
  const html = view === 'overview' ? renderOverview() : view === 'library' || view === 'discover' ? renderCatalog() : view === 'downloads' ? renderQueue() : view === 'requests' ? renderRequests() : view === 'calendar' ? renderCalendar() : view === 'manage' ? renderManage() : view === 'wanted' ? renderWanted() : view === 'activity' ? renderActivity() : view === 'server' ? renderServer() : renderConnections();
  if (!quiet) main.innerHTML = html;
  else patch(main, html);
  // Re-observe so a sentinel still on screen after a page loads asks for the next one.
  const more = $('.load-more');
  moreObserver.disconnect();
  if (more) moreObserver.observe(more);
  const title = ({ overview: 'Overview', discover: 'Discover', library: 'My library', manage: 'Series & movies', downloads: 'Downloads', requests: 'Requests', wanted: 'Wanted', activity: 'Activity', calendar: 'Calendar', server: 'Server', connections: 'Services' })[view];
  $('#breadcrumb').textContent = title;
  document.title = `Unifiarr — ${title}`;
}

function navigate(target) {
  const open = pages().map(page => page[0]);
  if (!open.includes(target)) {
    target = open.includes('overview') ? 'overview' : 'connections';
    history.replaceState(null, '', `#${target}`);
  }
  view = target;
  type = 'All';
  library = 'all';
  if (mobileNavigation.matches && sidebar.open) sidebar.close();
  render();
  window.scrollTo({ top: 0, behavior: 'instant' });
  loadView();
}

function openModal(content) {
  if (mobileNavigation.matches && sidebar.open) sidebar.close();
  if (modal.open) modal.close();
  returnFocus = document.activeElement;
  modal.innerHTML = `<button class="modal-close" data-action="close" aria-label="Close dialog">${icon('close')}</button><div class="modal-body">${content}</div>`;
  if (toastBox.parentNode === modal || !toastBox.isConnected) document.body.append(toastBox);
  modal.showModal();
}

function showDetails(id) {
  const item = state.media.find(media => media.id === id);
  if (!item) return;
  const watch = `${configured.jellyfin.url}/web/#/details?id=${encodeURIComponent(id)}`;
  openModal(`<div class="detail-heading">${picture(item, 'detail-poster', false)}<div><h2 id="modal-title">${esc(item.title)}</h2><p>${esc(item.year)} · ${item.type}${item.quality ? ` · ${item.quality}` : ''}<br>${esc(item.genre)} · ★ ${item.rating}<br>Jellyfin library · ${esc(libraries.find(entry => entry.id === item.library)?.name ?? '—')}</p><span class="detail-badge">Ready to watch</span></div></div><div class="detail-extra" aria-busy="true">${loader('Loading details from Jellyfin')}</div><div class="modal-actions"><a class="button primary" href="${esc(watch)}" target="_blank" rel="noopener">${icon('play')}Watch in Jellyfin</a>${button('Refresh metadata', 'jf-refresh', id, '', 'refresh')}</div><div class="modal-footnote">Live from Jellyfin ${esc(connected.jellyfin?.version ?? '')}.</div>`);
  jf().item(id).then(info => {
    const box = $('#modal .detail-extra');
    if (!box || !info) return;
    box.removeAttribute('aria-busy');
    box.innerHTML = `${info.tagline ? `<p class="tagline">${esc(info.tagline)}</p>` : ''}${info.overview ? `<p>${esc(info.overview)}</p>` : ''}<p class="detail-facts">${[info.runtime && `${info.runtime} min`, info.rated, info.genres.join(', '), info.studios.slice(0, 3).join(', ')].filter(Boolean).map(esc).join(' · ')}</p>${info.cast.length ? `<h3 class="modal-subtitle">Cast</h3><div class="cast-list">${info.cast.map(person => `<div class="cast-member">${person.image ? `<img ${photo(person.image)} alt="" loading="lazy" width="56" height="56">` : `<span class="cast-initials">${esc(person.name.split(' ').map(word => word[0]).slice(0, 2).join(''))}</span>`}<b>${esc(person.name)}</b><small>${esc(person.role)}</small></div>`).join('')}</div>` : ''}`;
  }).catch(error => { const box = $('#modal .detail-extra'); if (box) box.innerHTML = `<p class="form-note">${esc(error.message)}</p>`; });
}

document.addEventListener('click', event => {
  const target = event.target.closest('button');
  if (target?.dataset.view) {
    query = '';
    $('#search').value = '';
    history.pushState(null, '', `#${target.dataset.view}`);
    navigate(target.dataset.view);
  }
  if (target?.dataset.library) {
    library = target.dataset.library;
    type = 'All';
    render();
    $('.library-chip.selected').scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
  if (target?.dataset.requestFilter) {
    requestFilter = target.dataset.requestFilter;
    render();
  }
  if (target?.dataset.source) {
    activitySource = target.dataset.source;
    render();
  }
  if (target?.dataset.torrentFilter) {
    torrentFilter = target.dataset.torrentFilter;
    render();
  }
  if (target?.dataset.torrentTab) {
    torrentTab = target.dataset.torrentTab;
    $('#modal .modal-body').innerHTML = torrentModal(target.dataset.id);
    return $(`#modal [data-torrent-tab="${torrentTab}"]`)?.focus();
  }
  if (target?.dataset.type) {
    type = target.dataset.type;
    if (view === 'discover') { data.discover = undefined; loadView(); }
    render();
  }
  if (target?.dataset.wantedFilter) {
    wantedFilter = target.dataset.wantedFilter;
    render();
  }
  if (target?.dataset.titleTab) {
    titleTab = target.dataset.titleTab;
    $('#modal .modal-body').innerHTML = titleModal();
    return ($('#modal #arr-delete-files') ?? $('#modal #arr-edit select') ?? $('#modal .arr-title')).focus();
  }
  if (!target?.dataset.action) return;
  const { action, id } = target.dataset;
  if (action === 'close') return modal.close();
  if (action.startsWith('playback-')) {
    const session = [...state.sessions, ...data.devices ?? []].find(item => item.id === id);
    if (!session) return toast('This playback session has ended.');
    const title = (session.item ?? state.media.find(media => media.id === session.mediaId))?.title ?? 'playback';
    if (action === 'playback-compose') {
      openModal(`<span class="composer-icon">${icon('requests')}</span><h2 id="modal-title">A message for ${esc(session.user)}</h2><p>To ${esc(session.device)} · ${esc(session.client)}</p><form id="playback-message" data-id="${session.id}"><label class="form-field">Your message<textarea name="message" rows="4" maxlength="500" required placeholder="Let them know about a server restart, or just say hello…"></textarea></label><p class="modal-footnote">Shows on their screen for 15 seconds. Some apps don’t display messages. Maximum 500 characters.</p><div class="modal-actions"><button class="button primary" type="submit">${icon('requests')}Send message</button>${button('Cancel', 'close', '')}</div></form>`);
      return;
    }
    if (action === 'playback-confirm-stop') {
      return openModal(`<h2 id="modal-title">Stop this session?</h2><p>This will end <strong>${esc(title)}</strong> for ${esc(session.user)} on ${esc(session.device)}.</p><div class="modal-actions">${button('Stop playback', 'playback-stop', id, 'danger', 'stop')}${button('Keep watching', 'close', '')}</div>`);
    }
    const operation = action.slice('playback-'.length);
    (async () => {
      await jf().playback(id, operation);
      modal.close();
      pending.set(id, { status: { play: 'playing', pause: 'paused', stop: 'stopped' }[operation], until: Date.now() + 10000 });
      state.sessions = playing(state.sessions);
      render(true);
      toast({ play: 'Playback resumed', pause: 'Playback paused', stop: 'Playback stopped' }[operation]);
      $(`[data-session="${id}"] .playback-toggle`)?.focus({ preventScroll: true });
    })().catch(error => toast(error.message));
    return;
  }
  if (action.startsWith('go-')) {
    history.pushState(null, '', `#${action.slice(3)}`);
    return navigate(action.slice(3));
  }
  const { service } = target.dataset;
  const t = current;
  if (action === 'arr-title') return showTitle(service, Number(id));
  if (action === 'arr-search' || action === 'arr-releases') {
    const { season, episode } = target.dataset, sonarr = service === 'sonarr';
    const name = episode ? t.episodes.find(entry => entry.id === Number(episode)).code : season !== undefined ? `Season ${season}` : '';
    if (action === 'arr-releases') return showReleases(service, episode ? { episodeId: episode } : season !== undefined ? { seriesId: t.id, seasonNumber: season } : { movieId: t.id }, `${t.title}${name && ` · ${name}`}`, t.id);
    const search = episode ? { episodeIds: [Number(episode)] } : season !== undefined ? { seriesId: t.id, seasonNumber: Number(season) } : sonarr ? { seriesId: t.id } : { movieIds: [t.id] };
    return serviceDo(service, () => arr(service).search(search), `Searching for ${t.title}${name && ` ${name}`}`);
  }
  if (action === 'arr-refresh') return serviceDo(service, () => arr(service).refresh(t.id), `Refreshing ${t.title}`).then(ok => ok && refreshTitle('[data-action="arr-refresh"]'));
  if (action === 'arr-monitor-season' || action === 'arr-monitor-episode') {
    const on = target.getAttribute('aria-pressed') !== 'true', { season, episode } = target.dataset;
    const focus = season !== undefined ? `[data-action="arr-monitor-season"][data-season="${season}"]` : `[data-action="arr-monitor-episode"][data-episode="${episode}"]`;
    return serviceDo(service, () => season !== undefined ? arr(service).monitorSeason(t, Number(season), on) : arr(service).monitorEpisodes([Number(episode)], on), `${on ? 'Monitoring' : 'Stopped monitoring'} ${season !== undefined ? (Number(season) ? `season ${season}` : 'specials') : t.episodes.find(entry => entry.id === Number(episode)).code}`).then(ok => ok && refreshTitle(focus));
  }
  if (action === 'arr-delete-confirm') {
    const deleteFiles = $('#arr-delete-files').checked;
    return serviceDo(service, async () => { await arr(service).remove(t.id, deleteFiles); modal.close(); }, `Deleted ${t.title}${deleteFiles ? ' and its files' : ''}`);
  }
  if (action === 'release-grab') {
    const search = releases, release = search.list[Number(target.dataset.index)];
    target.disabled = true;
    return arr(search.service).grab(release).then(() => {
      release.grabbed = true;
      if (releases === search && $('#modal .releases-title')) {
        $('#modal .modal-body').innerHTML = releasesModal();
        $(`#modal [data-index="${target.dataset.index}"]`)?.focus();
      }
      toast('Sent to your download client');
    }).catch(error => { target.disabled = false; toast(error.message); });
  }
  if (action === 'wanted-search' || action === 'wanted-releases') {
    const record = data.wanted[service][wantedFilter].records.find(entry => entry.id === Number(id)), sonarr = service === 'sonarr';
    if (action === 'wanted-releases') return showReleases(service, sonarr ? { episodeId: id } : { movieId: id }, `${record.title} · ${record.detail}`);
    return serviceDo(service, () => arr(service).search(sonarr ? { episodeIds: [Number(id)] } : { movieIds: [Number(id)] }), `Searching for ${record.title} ${record.detail}`);
  }
  if (action === 'wanted-search-all') {
    const which = wantedFilter, services = ['sonarr', 'radarr'].filter(name => data.wanted?.[name]?.[which]?.total);
    if (!services.length) return toast('Nothing to search for.');
    return services.forEach(name => serviceDo(name, () => arr(name).searchAll(which), `${serviceName(name)} is searching for all ${which === 'missing' ? 'missing' : 'cutoff unmet'} items`));
  }
  if (action === 'queue-remove') {
    const item = data.queue.find(entry => entry.service === service && entry.id === Number(id));
    return openModal(`<h2 id="modal-title">Remove from queue?</h2><p><strong>${esc(item.title)}${item.detail ? ` · ${esc(item.detail)}` : ''}</strong> will be removed from ${serviceName(service)} and your download client.</p><p class="form-note">${esc(item.release)}</p><label class="switch-row"><input type="checkbox" class="switch" id="queue-blocklist"> Blocklist this release so it isn’t grabbed again</label><div class="modal-actions">${button('Remove', 'queue-remove-confirm', id, 'danger', 'trash').replace('data-action', `data-service="${service}" data-action`)}${button('Cancel', 'close', '')}</div>`);
  }
  if (action === 'queue-remove-confirm') {
    const blocklist = $('#queue-blocklist').checked;
    return serviceDo(service, async () => { await arr(service).removeQueue(Number(id), blocklist); modal.close(); }, `Removed from queue${blocklist ? ' and blocklisted' : ''}`);
  }
  const user = data.users?.find(entry => entry.id === id);
  if (action === 'user-toggle') return serviceDo('jellyfin', () => jf().setDisabled(user, !user.disabled), `${user.name} ${user.disabled ? 'enabled' : 'disabled'}`).then(() => main.querySelector(`[data-action="user-toggle"][data-id="${CSS.escape(id)}"]`)?.focus());
  if (action === 'user-delete') return openModal(`<h2 id="modal-title">Delete ${esc(user.name)}?</h2><p>They lose access to your Jellyfin server, and their watch history and settings are removed. This can’t be undone.</p><div class="modal-actions">${button('Delete user', 'user-delete-confirm', id, 'danger', 'trash')}${button('Cancel', 'close', '')}</div>`);
  if (action === 'user-delete-confirm') return serviceDo('jellyfin', async () => { await jf().deleteUser(id); modal.close(); }, `Deleted ${user.name}`);
  if (action === 'user-add') return openModal(`<span class="composer-icon">${icon('user-plus')}</span><h2 id="modal-title">Add a Jellyfin user</h2><form id="user-form"><div class="form-grid"><label class="form-field">Name<input name="name" required maxlength="64" autocomplete="off"></label><label class="form-field">Password<input name="password" type="password" autocomplete="new-password"></label></div><p class="form-note">New users can see all libraries. Fine-tune their access in Jellyfin’s dashboard.</p><div class="modal-actions"><button class="button primary" type="submit">${icon('user-plus')}Add user</button>${button('Cancel', 'close', '')}</div></form>`);
  if (action === 'task-run' || action === 'task-stop') {
    const task = data.tasks.find(entry => entry.id === id);
    return serviceDo('jellyfin', () => action === 'task-run' ? jf().runTask(id) : jf().stopTask(id), `${action === 'task-run' ? 'Started' : 'Stopping'} ${task.name}`);
  }
  if (action === 'library-scan') return serviceDo('jellyfin', () => jf().scan(id), `${id ? libraries.find(entry => entry.id === id)?.name ?? 'Library' : 'All libraries'} scan started`);
  if (action === 'jf-refresh') return serviceDo('jellyfin', () => jf().refresh(id), 'Refreshing metadata from Jellyfin');
  if (action === 'details') return showDetails(id);
  if (action === 'request') return showRequest(id);
  if (action === 'seerr-delete') return openModal(`<h2 id="modal-title">Delete this request?</h2><p>Seerr forgets the request for <strong>${esc(data.requests.find(item => item.request === Number(id))?.title ?? 'this title')}</strong>. Anything already downloaded stays.</p><div class="modal-actions">${button('Delete request', 'seerr-delete-confirm', id, 'danger', 'trash')}${button('Cancel', 'close', '')}</div>`);
  if (['seerr-approve', 'seerr-decline', 'seerr-delete-confirm'].includes(action)) {
    const step = action.split('-')[1];
    return serviceDo('seerr', async () => { await sr().act(id, step); modal.close(); }, { approve: 'Request approved', decline: 'Request declined', delete: 'Request deleted' }[step]);
  }
  if (action === 'service' || action === 'connections' || action === 'activity') {
    modal.close();
    const destination = action === 'activity' ? pages().find(page => ['activity', 'server'].includes(page[0]))?.[0] ?? 'connections' : 'connections';
    query = '';
    $('#search').value = '';
    history.pushState(null, '', `#${destination}`);
    navigate(destination);
    if (action === 'service') { $(`#service-${id}`)?.scrollIntoView({ block: 'start' }); $(`#service-${id} input`)?.focus({ preventScroll: true }); }
    return;
  }
  if (action === 'service-remove') return openModal(`<h2 id="modal-title">Remove ${serviceName(id)}?</h2><p>Unifiarr forgets its address and ${id === 'qbittorrent' ? 'login' : 'API key'}, and hides its pages. Nothing changes on ${serviceName(id)} itself.</p><div class="modal-actions">${button('Remove', 'service-remove-confirm', id, 'danger', 'trash')}${button('Cancel', 'close', '')}</div>`);
  if (action === 'service-remove-confirm') {
    return api('config', id, { method: 'DELETE' }).then(async () => { modal.close(); await setup(); toast(`${serviceName(id)} removed`); }).catch(error => toast(error.message));
  }
  if (action === 'resume-all' || action === 'torrent-pause-all') {
    const pause = action === 'torrent-pause-all';
    return liveDo(async () => (await qbit.setAll(pause), `${pause ? 'Paused' : 'Resumed'} all torrents`));
  }
  if (action === 'alt-speed') {
    return liveDo(async () => (await qbit.toggleAltSpeed(), `Alternative speed limits ${state.altSpeed ? 'off' : 'on'}`));
  }
  if (action === 'pick-all') { main.querySelectorAll('.torrent-pick').forEach(box => picked.add(box.dataset.id)); return render(); }
  if (action === 'pick-clear') { picked.clear(); return render(); }
  if (action === 'pick-pause' || action === 'pick-resume') {
    const pause = action === 'pick-pause', count = picked.size;
    return liveDo(async () => (await qbit.run({ hash: [...picked].join('|') }, pause ? 'pause' : 'resume'), `${pause ? 'Paused' : 'Resumed'} ${count} torrent${count === 1 ? '' : 's'}`));
  }
  if (action === 'pick-delete') return openModal(`<h2 id="modal-title">Remove ${picked.size} torrent${picked.size === 1 ? '' : 's'}?</h2><p>They will be removed from qBittorrent.</p><label class="switch-row"><input type="checkbox" class="switch" id="delete-files"> Also delete downloaded files from disk</label><div class="modal-actions">${button('Remove', 'pick-delete-confirm', '', 'danger', 'trash')}${button('Cancel', 'close', '')}</div>`);
  if (action === 'pick-delete-confirm') {
    const files = $('#delete-files').checked, count = picked.size;
    return liveDo(async () => { await qbit.run({ hash: [...picked].join('|') }, 'delete', files); picked.clear(); modal.close(); return `Removed ${count} torrent${count === 1 ? '' : 's'}${files ? ' and their files' : ''}`; });
  }
  if (action === 'torrent-add') return showAddTorrent();
  if (action === 'torrent-details') return showTorrent(id);
  if (action === 'torrent-delete') {
    torrentTab = 'delete';
    return refreshTorrent(id, '#delete-files');
  }
  if (action.startsWith('torrent-')) {
    const item = state.torrents.find(entry => entry.hash === id);
    const operation = { 'torrent-toggle': item?.state === 'paused' ? 'resume' : 'pause', 'torrent-force': 'force', 'torrent-recheck': 'recheck', 'torrent-reannounce': 'reannounce', 'torrent-queue': 'queue', 'torrent-delete-confirm': 'delete' }[action];
    const value = operation === 'queue' ? target.dataset.queue : operation === 'delete' ? $('#delete-files').checked : undefined;
    const focus = target.dataset.queue ? `[data-queue="${target.dataset.queue}"]` : `[data-action="${action}"]`;
    if (!item) return toast('This torrent is no longer in qBittorrent.');
    return liveDo(async () => { const message = await qbit.run(item, operation, value); if (operation === 'delete') modal.close(); return message; }, id, focus);
  }
});

document.addEventListener('submit', event => {
  if (event.target.matches('.service-form')) {
    event.preventDefault();
    const form = event.target, id = form.dataset.service, submit = form.querySelector('[type=submit]');
    submit.disabled = true;
    submit.setAttribute('aria-busy', 'true');
    api('config', id, { method: 'PUT', body: Object.fromEntries(new FormData(form)) })
      .then(async ({ version }) => { await setup(); toast(`Connected to ${serviceName(id)} ${version}`); $(`#service-${id} [type=submit]`)?.focus(); })
      .catch(error => { submit.disabled = false; submit.removeAttribute('aria-busy'); toast(error.message); });
    return;
  }
  if (event.target.id === 'request-form') {
    event.preventDefault();
    const form = event.target;
    const [kind, tmdb] = form.dataset.id.split('-'), { profile, folder } = form.elements, title = $('#modal-title').textContent;
    const body = { mediaType: kind, mediaId: Number(tmdb), ...(kind === 'tv' && { seasons: [...form.querySelectorAll('[name=season]:checked')].map(input => Number(input.value)) }), ...(profile && { serverId: Number(form.dataset.server), profileId: Number(profile.value), rootFolder: folder.value, tags: [...form.querySelectorAll('[name=tag]:checked')].map(input => Number(input.value)) }) };
    form.querySelector('[type=submit]').disabled = true;
    serviceDo('seerr', async () => { const request = await sr().request(body); modal.close(); return `Requested ${title}${request?.status === 1 ? ' · Pending approval' : ''}`; })
      .then(ok => ok || (form.querySelector('[type=submit]').disabled = false));
    return;
  }
  if (event.target.id === 'torrent-form') {
    event.preventDefault();
    const { elements, dataset } = event.target;
    const reopen = () => { $('#modal details.advanced')?.setAttribute('open', ''); $('#modal #torrent-form [type=submit]')?.focus(); };
    try {
      const options = { name: elements.name.value, savePath: elements.savePath.value, category: elements.category.value, tags: elements.tags.value.split(','), dlLimit: Number(elements.dlLimit.value), upLimit: Number(elements.upLimit.value) };
      const item = state.torrents.find(entry => entry.hash === dataset.id);
      const settings = { ...torrentSettings(state, options), sequential: elements.sequential.checked, firstLast: elements.firstLast.checked };
      liveDo(() => qbit.run(item, 'options', settings), dataset.id).then(reopen);
    } catch (error) {
      toast(error.message);
    }
    return;
  }
  if (event.target.id === 'torrent-add-form') {
    event.preventDefault();
    const { elements } = event.target;
    (async () => {
      const files = [...elements.files.files];
      if (files.some(file => file.size > 10 * 1024 ** 2)) throw new Error('Torrent files must be under 10 MiB.');
      const entries = [...elements.magnets.value.split('\n').filter(line => line.trim()).map(parseMagnet), ...await Promise.all(files.map(async file => parseTorrent(new Uint8Array(await file.arrayBuffer()))))];
      const options = { category: elements.category.value, savePath: elements.savePath.value, tags: elements.tags.value.split(','), paused: !elements.start.checked, top: elements.top.checked, sequential: elements.sequential.checked, firstLast: elements.firstLast.checked };
      // Validate (empty list, duplicates, save path, tags) on a throwaway copy, then send the originals to qBittorrent.
      const check = { ...state, torrents: [...state.torrents], activity: [] };
      addTorrents(check, entries, options);
      const settings = check.torrents.at(options.top ? 0 : -1);
      const message = await qbit.add({ ...options, magnets: elements.magnets.value.split('\n').map(line => line.trim()).filter(Boolean), files, savePath: settings.savePath, tags: settings.tags });
      await syncLive();
      modal.close();
      torrentFilter = 'all';
      torrentCategory = 'all';
      if (view === 'downloads') render();
      else { history.pushState(null, '', '#downloads'); navigate('downloads'); }
      toast(message);
    })().catch(error => toast(error.message));
    return;
  }
  if (event.target.id === 'arr-edit') {
    event.preventDefault();
    const { elements } = event.target, t = current;
    serviceDo(t.service, () => arr(t.service).save(t, { monitored: elements.monitored.checked, profileId: Number(elements.profile.value), path: elements.path.value.trim() }), `Saved ${t.title}`).then(ok => { if (ok) { titleTab = 'overview'; refreshTitle('[data-title-tab="edit"]'); } });
    return;
  }
  if (event.target.id === 'user-form') {
    event.preventDefault();
    const { elements } = event.target;
    serviceDo('jellyfin', async () => { await jf().createUser(elements.name.value.trim(), elements.password.value); modal.close(); }, `Added ${elements.name.value.trim()}`);
    return;
  }
  if (event.target.id !== 'playback-message') return;
  event.preventDefault();
  const form = event.target;
  const field = form.elements.namedItem('message');
  field.setCustomValidity('');
  const text = field.value.trim();
  if (!text || text.length > 500) {
    field.setCustomValidity('Enter a message between 1 and 500 characters.');
    return field.reportValidity();
  }
  jf().playback(form.dataset.id, 'message', text).then(() => {
    const session = state.sessions.find(item => item.id === form.dataset.id);
    if (session) session.lastMessage = text;
    modal.close();
    render(true);
    toast('Message sent');
  }).catch(error => toast(error.message));
});
document.addEventListener('change', event => {
  if (event.target.id === 'live-banners') {
    localStorage.banners = event.target.checked ? 'show' : 'hide';
    return toast(event.target.checked ? 'Connection banners shown' : 'Connection banners hidden');
  }
  if (event.target.id === 'request-sort') {
    requestSort = event.target.value;
    return render();
  }
  if (event.target.matches('.torrent-pick')) {
    picked[event.target.checked ? 'add' : 'delete'](event.target.dataset.id);
    return render();
  }
  if (event.target.id === 'torrent-category') {
    torrentCategory = event.target.value;
    return render();
  }
  if (event.target.matches('.torrent-priority')) {
    const { id, index } = event.target.dataset;
    const focus = `.torrent-priority[data-index="${index}"]`;
    return liveDo(() => qbit.run(state.torrents.find(entry => entry.hash === id), 'priority', { index: Number(index), priority: event.target.value }), id, focus);
  }
  if (event.target.name === 'files' && event.target.closest('#torrent-add-form')) {
    event.target.parentElement.querySelector('strong').textContent = [...event.target.files].map(file => file.name).join(', ') || 'Choose .torrent files';
    return;
  }
  if (event.target.name === 'category' && event.target.closest('#torrent-add-form')) {
    event.target.form.elements.savePath.value = state.categories[event.target.value] || (live?.defaultSavePath ?? '/downloads');
    return;
  }
  const form = event.target.closest('#request-form');
  if (!form || event.target.type !== 'checkbox') return;
  const seasons = [...form.querySelectorAll('[name=season]')];
  if (event.target.matches('[data-all-seasons]')) seasons.forEach(input => { input.checked = event.target.checked; });
  const count = seasons.filter(input => input.checked).length;
  const all = form.querySelector('[data-all-seasons]');
  all.checked = count === seasons.length;
  all.indeterminate = count > 0 && count < seasons.length;
  const submit = form.querySelector('[type=submit]');
  submit.disabled = !count;
  submit.querySelector('span').textContent = count ? `Request ${count} season${count === 1 ? '' : 's'}` : 'Select season(s)';
});
document.addEventListener('input', event => {
  if (event.target.name === 'message' && event.target.closest('#playback-message')) event.target.setCustomValidity('');
});

let searchTimer;
$('#search').addEventListener('input', event => {
  query = event.target.value;
  if (view === 'discover') {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(loadView, 350);
  } else if (!['library', 'manage', 'calendar', 'requests', 'downloads'].includes(view)) {
    const target = has('jellyfin') ? 'library' : has('seerr') ? 'discover' : arrs().length ? 'manage' : has('qbittorrent') ? 'downloads' : '';
    if (!target) return;
    view = target;
    history.replaceState(null, '', `#${target}`);
    loadView();
  }
  render();
});
$('#search-icon').innerHTML = icon('search');
$('#activity-toggle').innerHTML = icon('bell');
$('#profile-settings').innerHTML = icon('settings');
$('#menu-toggle').innerHTML = icon('menu') + icon('panel');
// Phones open the sidebar as a drawer; wider screens collapse it to an icon rail, remembered like the theme.
$('#menu-toggle').addEventListener('click', () => {
  if (mobileNavigation.matches) {
    sidebar.showModal();
    return $('#menu-toggle').setAttribute('aria-expanded', 'true');
  }
  localStorage.sidebar = document.documentElement.classList.toggle('collapsed') ? 'collapsed' : 'expanded';
  syncToggle();
});
function syncToggle() {
  const collapsed = !mobileNavigation.matches && document.documentElement.classList.contains('collapsed');
  $('#menu-toggle').ariaLabel = mobileNavigation.matches ? 'Open navigation' : collapsed ? 'Expand sidebar' : 'Collapse sidebar';
  $('#menu-toggle').setAttribute('aria-expanded', String(!mobileNavigation.matches && !collapsed));
}
$('#menu-close').addEventListener('click', () => sidebar.close());
$('#tabbar').addEventListener('click', event => event.target.closest('.tab-more') && $('#menu-toggle').click());
sidebar.addEventListener('close', () => $('#menu-toggle').setAttribute('aria-expanded', 'false'));
sidebar.addEventListener('click', event => {
  if (event.target !== sidebar || !mobileNavigation.matches) return;
  const rect = sidebar.getBoundingClientRect();
  if (event.clientX > rect.right || event.clientY > rect.bottom) sidebar.close();
});
function syncNavigation() {
  syncToggle();
  sidebar.close();
  if (!mobileNavigation.matches) sidebar.show();
}
mobileNavigation.addEventListener('change', syncNavigation);
syncNavigation();
function theme(dark) {
  document.documentElement.classList.toggle('dark', dark);
  localStorage.theme = dark ? 'dark' : 'light';
  $('#theme-toggle').setAttribute('aria-label', `Switch to ${dark ? 'light' : 'dark'} theme`);
  $('#theme-toggle').innerHTML = icon(dark ? 'sun' : 'moon');
}
theme(localStorage.theme === 'dark');
$('#theme-toggle').addEventListener('click', () => theme(!document.documentElement.classList.contains('dark')));
// Styled dropdowns: every <select> keeps its value and change events, but opens this listbox instead of the browser's popup.
const picker = document.createElement('div');
Object.assign(picker, { className: 'select-menu', role: 'listbox', popover: 'auto', tabIndex: -1 });
let pickerFor = null;
function openPicker(select) {
  pickerFor = select;
  picker.ariaLabel = select.ariaLabel ?? select.closest('label')?.firstChild?.textContent?.trim() ?? 'Options';
  picker.innerHTML = [...select.options].map((option, index) => `<button type="button" role="option" data-index="${index}" aria-selected="${option.selected}"${option.disabled ? ' disabled' : ''}><span>${esc(option.text)}</span>${option.selected ? icon('check') : ''}</button>`).join('');
  // Inside the modal the menu must live in the dialog, or the modal makes it inert.
  (select.closest('dialog') ?? document.body).append(picker);
  const box = select.getBoundingClientRect(), width = Math.max(box.width, 180);
  picker.style.minWidth = `${width}px`;
  picker.style.left = `${Math.max(8, Math.min(box.left, innerWidth - width - 8))}px`;
  picker.showPopover();
  const below = innerHeight - box.bottom - 8, above = box.top - 8, height = picker.scrollHeight;
  const up = height > below && above > below;
  picker.style.maxHeight = `${Math.min(320, up ? above - 6 : below - 6)}px`;
  picker.style.top = `${up ? box.top - 6 - Math.min(height, 320, above - 6) : box.bottom + 6}px`;
  picker.classList.toggle('is-up', up);
  (picker.querySelector('[aria-selected=true]') ?? picker.querySelector('button:not(:disabled)'))?.focus({ preventScroll: false });
}
function pick(index) {
  const select = pickerFor;
  picker.hidePopover();
  if (select.selectedIndex !== index) {
    select.selectedIndex = index;
    select.dispatchEvent(new Event('input', { bubbles: true }));
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }
}
for (const type of ['scroll', 'resize']) addEventListener(type, event => event.target !== picker && picker.matches(':popover-open') && picker.hidePopover(), { capture: true, passive: true });
picker.addEventListener('toggle', event => { if (event.newState === 'closed' && pickerFor?.isConnected && picker.contains(document.activeElement)) pickerFor.focus(); });
picker.addEventListener('click', event => { const option = event.target.closest('[data-index]'); if (option) pick(Number(option.dataset.index)); });
picker.addEventListener('keydown', event => {
  const items = [...picker.querySelectorAll('button:not(:disabled)')], at = items.indexOf(document.activeElement);
  const next = { ArrowDown: at + 1, ArrowUp: at - 1, Home: 0, End: items.length - 1 }[event.key];
  if (next !== undefined) { event.preventDefault(); items[Math.max(0, Math.min(items.length - 1, next))]?.focus(); }
  else if (event.key === 'Tab') picker.hidePopover();
});
document.addEventListener('mousedown', event => {
  const select = event.target.closest?.('select');
  if (!select || select.multiple || select.size > 1 || event.button) return;
  event.preventDefault();
  select.focus();
  select.dataset.wasOpen = picker.matches(':popover-open') && pickerFor === select;
});
// Open on click, not mousedown: the popover's light dismiss would close it again on pointerup.
document.addEventListener('click', event => {
  const select = event.target.closest?.('select');
  if (!select || select.multiple || select.size > 1) return;
  if (select.dataset.wasOpen === 'true') picker.hidePopover();
  else openPicker(select);
  delete select.dataset.wasOpen;
});
document.addEventListener('keydown', event => {
  if (event.target.matches?.('select') && ([' ', 'Enter', 'F4'].includes(event.key) || (event.altKey && event.key === 'ArrowDown'))) {
    event.preventDefault();
    return openPicker(event.target);
  }
  if (event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName) && !modal.open) {
    event.preventDefault();
    $('#search').focus();
  }
});
modal.addEventListener('click', event => {
  const rect = modal.getBoundingClientRect();
  if (event.target === modal && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) modal.close();
});
modal.addEventListener('close', () => {
  watch();
  if (returnFocus?.isConnected) returnFocus.focus();
  else main.focus({ preventScroll: true });
});
window.addEventListener('hashchange', () => {
  query = '';
  $('#search').value = '';
  navigate(location.hash.slice(1));
});
main.innerHTML = loading('your services');

function health() {
  const mine = Object.keys(configured ?? {}), off = mine.filter(id => !isLive({ id }));
  document.querySelector('.workspace .demo-pill').outerHTML = !mine.length ? '<span class="demo-pill">Setup</span>' : off.length ? `<span class="demo-pill">${mine.length - off.length}/${mine.length} live</span>` : '<span class="demo-pill is-live">Live</span>';
  document.querySelector('.server-health').classList.toggle('is-offline', !!off.length || !mine.length);
  document.querySelector('.server-health div').innerHTML = !mine.length ? 'No services yet<small>Set one up in Services</small>' : off.length ? `${off.map(serviceName).join(', ')} offline<small>${mine.length - off.length} of ${mine.length} services live</small>` : `All services live<small>${mine.map(serviceName).join(', ')}</small>`;
}

// Reads which services are set up on server.mjs, connects to each, then shows only their pages.
async function setup() {
  configured = await api('config', '').catch(() => null);
  await Promise.all([
    (async () => { live = has('qbittorrent') ? await qbit.connect() : null; })(),
    ...['sonarr', 'radarr', 'jellyfin', 'seerr'].map(async id => { const found = has(id) && await connect(id); if (found) connected[id] = found; else delete connected[id]; }),
  ]);
  if (live) await syncLive().catch(error => toast(error.message)), stream();
  else Object.assign(state, { torrents: [], categories: {}, transfer: undefined, altSpeed: false });
  if (connected.jellyfin) {
    await syncLibrary().catch(error => toast(error.message));
    state.sessions = playing(await jellyfinClient.sessions([]).catch(() => []));
  } else Object.assign(state, { media: [], sessions: [] }), libraries.length = 0;
  health();
  data = {};
  navigate(location.hash.slice(1));
}
setup();

// Poll pages that show changing data; skip while a dialog is open so nothing jumps under the cursor.
setInterval(() => {
  if (document.hidden || modal.open || !(view === 'overview' ? connected.jellyfin || connected.seerr : ['activity', 'server', 'requests'].includes(view))) return;
  loadView();
}, 5000);

// Now playing clocks tick locally between Jellyfin polls; only the time and progress bar change.
setInterval(() => {
  if (view !== 'overview' || document.hidden) return;
  for (const session of state.sessions) {
    const card = main.querySelector(`[data-session="${session.id}"]`), now = played(session);
    if (!card || session.status !== 'playing') continue;
    card.querySelector('.session-clock').textContent = clock(now);
    card.querySelector('.session-progress').ariaValueNow = now;
    card.querySelector('.session-progress i').style.width = `${now / session.duration * 100}%`;
  }
}, 1000);

// qBittorrent changes by the second. The server pushes only what changed over a WebSocket; polling is the fallback.
function showTorrents() {
  if (document.hidden) return;
  // Re-render without stealing focus or closing an open dropdown or form.
  if (document.activeElement?.matches('input:not(#search), select, textarea') || picker.matches(':popover-open') || $('#modal details.advanced[open]') || $('#modal #torrent-add-form')) return renderNavigation();
  const active = document.activeElement, selector = active?.dataset.action ? `[data-action="${active.dataset.action}"]${active.dataset.id ? `[data-id="${active.dataset.id}"]` : ''}${active.dataset.queue ? `[data-queue="${active.dataset.queue}"]` : ''}` : active?.dataset.torrentTab ? `[data-torrent-tab="${active.dataset.torrentTab}"]` : '';
  if (view === 'overview' && !modal.open) return render(true);
  if (view !== 'downloads') return renderNavigation();
  const open = openTorrent();
  if (open && torrentTab !== 'delete') refreshTorrent(open.hash, selector);
  else render(true);
}
let socket = null, raw = {};
function stream() {
  if (socket || !live) return;
  socket = new WebSocket(new URL('api/qbittorrent/live', location.href.replace(/^http/, 'ws')));
  socket.addEventListener('open', watch);
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data), item = message.details && state.torrents.find(entry => entry.hash === message.details.hash);
    if (message.details && !item) return;
    if (item) qbit.applyDetails(item, message.details.files, message.details.trackers);
    else Object.assign(state, qbit.mapState(raw = qbit.merge(raw, message), state.torrents));
    showTorrents();
  });
  socket.addEventListener('close', () => { socket = null; raw = {}; setTimeout(stream, 3000); });
}
function watch() {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ watch: openTorrent()?.hash ?? null }));
}
let polling = false;
setInterval(async () => {
  if (!live || document.hidden || polling || socket?.readyState === WebSocket.OPEN) return;
  polling = true;
  try {
    await syncLive();
    const open = openTorrent();
    if (open && torrentTab !== 'delete') await qbit.details(open).catch(() => {});
    showTorrents();
  } catch { /* qBittorrent unreachable: keep the last view */ } finally { polling = false; }
}, 1000);
