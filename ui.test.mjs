import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// Run with WEBDRIVER_URL pointing to geckodriver. Starts server.mjs with an empty config and a fake Sonarr.
test('first run shows setup only; a configured service brings back just its pages', {
  skip: !process.env.WEBDRIVER_URL,
  timeout: 90000,
}, async () => {
  const key = 'a'.repeat(32), soon = new Date(Date.now() + 864e5).toISOString();
  const series = { id: 1, title: 'Severance', year: 2022, monitored: true, qualityProfileId: 1, images: [], statistics: { episodeFileCount: 1, episodeCount: 2 }, seasons: [] };
  const sid = 'b'.repeat(32), commands = [];
  // Jellyfin keeps reporting "playing" like a real server does until the player catches up.
  const jellyfin = { 'System/Info': { Version: '10.10.7' }, 'Library/VirtualFolders': [{ ItemId: 'c'.repeat(32), Name: 'Movies', CollectionType: 'movies' }],
    Items: { Items: [{ Id: 'd'.repeat(32), Name: 'Big Test', Type: 'Movie', ProductionYear: 2020, ImageTags: { Primary: 'x' } }] }, 'System/ActivityLog/Entries': { Items: [] },
    Sessions: [{ Id: sid, UserName: 'Sam', DeviceName: 'TV', Client: 'Jellyfin Web', SupportsRemoteControl: true, PlayState: { PositionTicks: 6e8 }, NowPlayingItem: { Id: 'd'.repeat(32), Name: 'Big Test', Type: 'Movie', RunTimeTicks: 6e9, ImageTags: { Primary: 'x' } } }] };
  const server = { id: 0, name: 'Radarr', isDefault: true, is4k: false, activeProfileId: 4, activeDirectory: '/movies', activeTags: [] };
  const matrix = { id: 603, mediaType: 'movie', title: 'The Matrix', releaseDate: '1999-03-31', voteAverage: 8.2, overview: 'Wake up.' };
  const requests = [{ id: 9, status: 1, type: 'movie', media: { tmdbId: 603, mediaType: 'movie' }, requestedBy: { displayName: 'bob' }, createdAt: new Date().toISOString() }], requested = [];
  const seerr = { status: { version: '3.4.1' }, 'discover/trending?page=1': { totalPages: 2, results: [matrix, { id: 1, mediaType: 'person', name: 'Keanu' }] }, 'discover/trending?page=2': { totalPages: 2, results: [matrix, { id: 604, mediaType: 'movie', title: 'The Matrix Reloaded' }] }, 'movie/603': matrix, 'service/radarr': [server],
    'service/radarr/0': { server, profiles: [{ id: 4, name: 'HD-1080p' }], rootFolders: [{ id: 1, path: '/movies' }], tags: [{ id: 2, label: 'kids' }] }, request: { results: requests } };
  const discoverPage = Promise.withResolvers();
  const fake = createServer(async (request, response) => {
    if (request.url.startsWith('/api/v1/') && request.headers['x-api-key'] === key) {
      if (request.url === '/api/v1/discover/trending?page=2') await discoverPage.promise;
      const path = request.url.split('?')[0].slice(8);
      if (request.method === 'POST') {
        let body = '';
        for await (const chunk of request) body += chunk;
        if (path === 'request' && !seerr.failed) return (seerr.failed = true), response.writeHead(403, { 'content-type': 'application/json' }).end('{"message":"Quota exceeded"}');
        if (path === 'request') requested.push(JSON.parse(body));
        else requests[0].status = 2;
        return response.writeHead(201, { 'content-type': 'application/json' }).end('{"status":2}');
      }
      return response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(seerr[path] ?? seerr[request.url.slice(8)]));
    }
    const path = request.url.split('?')[0].replace('/api/v3/', '').slice(request.url.startsWith('/api/') ? 0 : 1);
    if (request.headers.authorization === `MediaBrowser Token="${key}"`) {
      if (request.method === 'POST') return commands.push(path), response.writeHead(204).end();
      if (path.startsWith('Items/')) return response.writeHead(200, { 'content-type': 'image/svg+xml' }).end('<svg xmlns="http://www.w3.org/2000/svg" width="2" height="3"/>');
      return response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(jellyfin[path]));
    }
    if (request.headers['x-api-key'] !== key) return response.writeHead(401).end();
    const reply = { 'system/status': { version: '4.0.20' }, health: [{ type: 'warning', message: 'Indexer needs attention' }], diskspace: [{ path: '/movies', freeSpaceBytes: 5 * 1024 ** 3, totalSpaceBytes: 100 * 1024 ** 3 }], series: [series], qualityprofile: [{ id: 1, name: 'HD-1080p' }], calendar: [{ id: 7, seriesId: 1, seasonNumber: 2, episodeNumber: 5, title: 'Trojan’s Horse', airDateUtc: soon, hasFile: false, series }], history: { records: [] }, queue: { records: [] } }[path] ?? { totalRecords: 0, records: [] };
    response.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(reply));
  }).listen(0, '127.0.0.1');
  await once(fake, 'listening');
  const dir = await mkdtemp(join(tmpdir(), 'unifiarr-ui-'));
  const app = spawn(process.execPath, ['server.mjs'], { cwd: import.meta.dirname, env: { ...process.env, PORT: '0', CONFIG: join(dir, 'config.json') } });
  const [line] = await once(app.stdout, 'data');
  const url = String(line).match(/http:\/\/\S+/)[0];

  const driver = process.env.WEBDRIVER_URL;
  async function request(path, body, method = body === undefined ? 'GET' : 'POST') {
    const response = await fetch(`${driver}${path}`, { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
    const { value } = await response.json();
    assert.ok(response.ok, JSON.stringify(value));
    return value;
  }
  const { sessionId } = await request('/session', { capabilities: { alwaysMatch: { browserName: 'firefox', 'moz:firefoxOptions': { args: ['-headless'] } } } });
  const session = `/session/${sessionId}`;
  const js = script => request(`${session}/execute/sync`, { script, args: [] });
  async function ready(script) {
    for (let attempt = 0; attempt < 50; attempt++) {
      if (await js(script)) return;
      await delay(100);
    }
    assert.fail(`Timed out: ${script}`);
  }
  const nav = 'return [...document.querySelectorAll("#navigation button")].map(b => b.dataset.view).join()';
  const fill = (values, id = 'sonarr') => js(`const f=document.querySelector('.service-form[data-service=${id}]'); ${Object.entries(values).map(([name, value]) => `f.elements.${name}.value=${JSON.stringify(value)};`).join('')} f.requestSubmit()`);
  const shoot = async name => process.env.UNIFIARR_SCREENSHOTS && writeFile(join(process.env.UNIFIARR_SCREENSHOTS, `${name}.png`), Buffer.from(await request(`${session}/screenshot`), 'base64'));
  try {
    await request(`${session}/window/rect`, { width: 1440, height: 1100 });
    await request(`${session}/url`, { url: `${url}/#overview` });
    await ready('return location.hash === "#connections" && document.querySelector("main h1")?.textContent === "Welcome to Unifiarr"');
    assert.equal(await js(nav), 'connections', 'nothing configured: only Services');
    assert.equal(await js('return [...document.querySelectorAll(".service-form")].map(f => f.dataset.service).join()'), 'jellyfin,sonarr,radarr,seerr,qbittorrent');
    assert.equal(await js('return document.querySelector(".services-caption").hidden && document.querySelector(".demo-pill").textContent'), 'Setup');
    await shoot('setup');
    await js('document.querySelector("#theme-toggle").click(); window.old = true; location.reload()');
    await ready('return !window.old && document.readyState === "complete" && document.documentElement.classList.contains("dark") && document.querySelector("#theme-toggle").ariaLabel === "Switch to light theme"');
    await js('document.querySelector("#theme-toggle").click()');

    await fill({ url: 'ftp://nope', key });
    assert.equal(await js('return document.querySelector(".service-form[data-service=sonarr] [name=url]").validity.valid'), false, 'browser rejects non-http addresses');
    await fill({ url: `http://127.0.0.1:${fake.address().port}`, key: 'wrong-key' });
    await ready('return document.querySelector("#toast").textContent.includes("check the API key")');
    assert.equal(await js(nav), 'connections');

    await fill({ url: `http://127.0.0.1:${fake.address().port}/`, key });
    await ready('return document.querySelector("#toast").textContent === "Connected to Sonarr 4.0.20"');
    assert.equal(await js(nav), 'overview,manage,wanted,activity,calendar,connections', 'Sonarr pages only');
    assert.equal(await js('return [...document.querySelectorAll("#service-navigation .service-health")].map(b => b.textContent).join()'), 'SonarrLive');
    assert.equal(await js('return document.querySelector(".service-form[data-service=sonarr] [name=key]").value'), '', 'the key is never sent back');

    await js('document.querySelector("#navigation [data-view=overview]").click()');
    await ready('return [...document.querySelectorAll(".service-row b")].some(b => b.textContent === "Severance")');
    assert.equal(await js('return [document.querySelectorAll(".stat").length, !!document.querySelector(".session-grid"), !!document.querySelector(".ready-grid")].join()'), '2,false,false', 'no Jellyfin or qBittorrent sections');
    await ready('return document.querySelectorAll(".health-warning").length === 2');
    assert.equal(await js('return [...document.querySelectorAll(".health-warning span")].map(e => e.textContent).join("|")'), 'Indexer needs attention|Low disk space: 5.0 GiB free on /movies');
    await shoot('overview');
    await js('document.querySelector("#navigation [data-view=calendar]").click()');
    await ready('return document.querySelector(".calendar-entry h3")?.textContent === "Severance" && document.querySelector(".calendar-entry p").textContent.startsWith("S02E05")');
    await js('location.hash = "#downloads"');
    await ready('return location.hash === "#overview"');

    await request(`${session}/window/rect`, { width: 390, height: 844 });
    await js('location.hash = "#connections"');
    await ready('return document.querySelector("main h1")?.textContent === "Your services" && document.documentElement.scrollWidth <= innerWidth');
    await shoot('services-phone');

    await js('document.querySelector("[data-action=service-remove][data-id=sonarr]").click(); document.querySelector("[data-action=service-remove-confirm]").click()');
    await ready('return document.querySelector("main h1")?.textContent === "Welcome to Unifiarr"');
    assert.equal(await js(nav), 'connections');

    await request(`${session}/window/rect`, { width: 1440, height: 1100 });
    await fill({ url: `http://127.0.0.1:${fake.address().port}`, key }, 'jellyfin');
    await ready('return document.querySelector("#toast").textContent === "Connected to Jellyfin 10.10.7"');
    await js('location.hash = "#overview"');
    await ready(`return !!document.querySelector('[data-session="${sid}"]') && !!document.querySelector(".ready-grid img")`);
    await js(`window.card = document.querySelector('[data-session="${sid}"]'); window.poster = document.querySelector(".ready-grid img"); window.clock = card.querySelector(".session-clock").textContent; card.querySelector(".playback-toggle").click()`);
    await ready(`return card.querySelector(".session-status").textContent === "Paused" && card.querySelector(".playback-toggle").dataset.action === "playback-play"`);
    assert.equal(commands.at(-1), `Sessions/${sid}/Playing/Pause`);
    await delay(6000);
    assert.equal(await js(`return [card.isConnected, poster.isConnected, card.querySelector(".session-status").textContent].join()`), 'true,true,Paused', 'polls patch in place and keep the paused state until the player reports it');
    await js('card.querySelector(".playback-toggle").click()');
    await ready('return card.querySelector(".session-status").textContent === "Playing"');
    const before = await js('return card.querySelector(".session-clock").textContent');
    await delay(2100);
    assert.notEqual(await js('return card.querySelector(".session-clock").textContent'), before, 'the clock ticks every second');

    await js('location.hash = "#connections"');
    await ready('return !!document.querySelector(".service-form[data-service=seerr]")');
    await fill({ url: `http://127.0.0.1:${fake.address().port}`, key: 'MTc1Njk+/2E=' }, 'seerr');
    await ready('return document.querySelector("#toast").textContent.includes("check the API key")');
    await fill({ url: `http://127.0.0.1:${fake.address().port}`, key }, 'seerr');
    await ready('return document.querySelector("#toast").textContent === "Connected to Seerr 3.4.1"');
    assert.match(await js(nav), /discover,requests/);
    await js('document.querySelector("#navigation [data-view=discover]").click()');
    await ready('return document.querySelectorAll(".load-more .loader-deck i").length === 3 && !!document.querySelector(".load-more .loader-bar")');
    assert.equal(await js('return getComputedStyle(document.querySelector(".load-more .loader-deck i")).animationName'), 'shuffle', 'pagination uses the animated loader');
    discoverPage.resolve();
    await ready('return document.querySelectorAll("[data-action=request]").length === 2 && !document.querySelector(".load-more")');
    assert.equal(await js('return document.querySelector(".filter-bar p").textContent'), '2 titles', 'the next page loads on scroll, without duplicates');
    assert.equal(await js('return !!document.querySelector(".live-banner")'), false, 'live banners are off by default');
    await js('document.querySelector("[data-action=request]").click()');
    await ready('return !!document.querySelector("#request-form [name=profile]")');
    await js('document.querySelector("#request-form [name=tag]").click(); document.querySelector("#request-form").requestSubmit()');
    await ready('return document.querySelector("#modal[open] #toast.visible")?.textContent === "Quota exceeded"');
    assert.equal(await js('return getComputedStyle(document.documentElement).overflow'), 'hidden', 'the page behind a popup does not scroll');
    await js('document.querySelector("#request-form").requestSubmit()');
    await ready('return document.querySelector("#toast").textContent === "Requested The Matrix"');
    assert.deepEqual(requested, [{ mediaType: 'movie', mediaId: 603, serverId: 0, profileId: 4, rootFolder: '/movies', tags: [2] }]);
    await js('location.hash = "#requests"');
    await ready('return !!document.querySelector("[data-action=seerr-approve]") && document.querySelector("#navigation [data-view=requests]").textContent.includes("1")');
    await js('document.querySelector("[data-action=seerr-approve]").click()');
    await ready('return document.querySelector("#toast").textContent === "Request approved" && !document.querySelector("[data-action=seerr-approve]")');
    const element = async css => Object.values(await request(`${session}/element`, { using: 'css selector', value: css }))[0];
    await request(`${session}/element/${await element('#request-sort')}/click`, {});
    await ready('return document.querySelector(".select-menu")?.matches(":popover-open")');
    await request(`${session}/element/${await element('.select-menu [data-index="1"]')}/click`, {});
    await ready('return document.querySelector("#request-sort")?.value === "title" && !document.querySelector(".select-menu").matches(":popover-open")');
  } finally {
    discoverPage.resolve();
    await request(session, undefined, 'DELETE');
    app.kill();
    fake.close();
    await rm(dir, { recursive: true });
  }
});
