import test from 'node:test';
import assert from 'node:assert/strict';
import { filterMedia, addTorrents, torrentSettings, parseMagnet, parseTorrent } from './model.mjs';
import { createHash } from 'node:crypto';

const media = [
  { id: 'interstellar', title: 'Interstellar', type: 'Movie', status: 'available', library: 'movies' },
  { id: 'frieren', title: 'Frieren', type: 'Series', status: 'available', library: 'anime' },
  { id: 'spiritedaway', title: 'Spirited Away', type: 'Movie', status: 'available', library: 'anime' },
  { id: 'dune', title: 'Dune', type: 'Movie', status: 'downloading', library: 'movies' },
];

test('search combines trimmed case-insensitive query, media type and status', () => {
  assert.deepEqual(filterMedia(media, { query: '  INTERSTELLAR ', type: 'Movie', status: ['available'] }).map(item => item.id), ['interstellar']);
  assert.equal(filterMedia(media, { query: 'Interstellar', type: 'Series' }).length, 0);
  assert.equal(filterMedia(media, { query: '<script>' }).length, 0);
  assert.equal(filterMedia(media, { status: ['downloading', 'paused'] }).length, 1);
});

test('mixed libraries filter by type', () => {
  assert.deepEqual(filterMedia(media, { library: 'anime' }).map(item => item.type).sort(), ['Movie', 'Series']);
  assert.deepEqual(filterMedia(media, { library: 'anime', type: 'Movie' }).map(item => item.id), ['spiritedaway']);
});

test('adding torrents from magnet links and .torrent files', async () => {
  const state = { torrents: [], activity: [], categories: { sonarr: '/downloads/tv' } };
  const magnet = parseMagnet('magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=Demo%20Show&tr=udp%3A%2F%2Ft.example%3A80');
  assert.deepEqual([magnet.hash, magnet.name, magnet.trackers], ['0123456789abcdef0123456789abcdef01234567', 'Demo Show', ['udp://t.example:80']]);
  assert.throws(() => parseMagnet('https://example.com'), /valid magnet/);
  const info = 'd6:lengthi1024e4:name8:demo.mkv12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaae';
  const file = await parseTorrent(new TextEncoder().encode(`d8:announce14:http://t.local4:info${info}e`));
  assert.equal(file.hash, createHash('sha1').update(info).digest('hex'));
  assert.deepEqual([file.name, file.files[0].size, file.trackers], ['demo.mkv', 1024, ['http://t.local']]);
  await assert.rejects(parseTorrent(new TextEncoder().encode('d4:infod')), /not a valid/);
  addTorrents(state, [magnet, file], { category: 'sonarr', tags: ['new'], top: true, paused: true });
  assert.deepEqual(state.torrents.slice(0, 2).map(item => [item.savePath, item.state]), [['/downloads/tv', 'paused'], ['/downloads/tv', 'paused']]);
  assert.throws(() => addTorrents(state, [magnet]), /already in the list/);
  assert.throws(() => addTorrents(state, [], {}), /at least one/);
});

test('torrent options are validated before they reach qBittorrent', () => {
  const state = { categories: { manual: '/downloads/manual' } };
  assert.throws(() => torrentSettings(state, { category: 'nope' }), /existing category/);
  assert.throws(() => torrentSettings(state, { savePath: '/a/../etc' }), /absolute/);
  assert.throws(() => torrentSettings(state, { dlLimit: -1 }), /Speed limits/);
  assert.deepEqual(torrentSettings(state, { category: 'manual', tags: ['a', ' a ', 'b'], dlLimit: 2048 }), { category: 'manual', tags: ['a', 'b'], dlLimit: 2048 });
});

test('torrent names find their library title for a poster', async () => {
  const { indexTitles, matchTitle } = await import('./model.mjs');
  const index = indexTitles([
    { title: 'Dune: Part Two', year: 2024, image: 'dune' }, { title: 'Dune', year: 1984, image: 'old-dune' }, { title: 'Severance', year: 2022, poster: '/sev' },
    { title: 'Blade Runner 2049', year: 2017, image: 'br' }, { title: 'Shōgun', year: 2024, image: 'sho' }, { title: 'No Poster', year: 2020 },
  ]);
  const poster = name => matchTitle(name, index)?.image ?? matchTitle(name, index)?.poster;
  assert.equal(poster('Dune.Part.Two.2024.2160p.BluRay.HEVC'), 'dune');
  assert.equal(poster('Dune (1984) 1080p'), 'old-dune');
  assert.equal(poster('[Group] Severance S02E04 1080p WEB-DL'), '/sev');
  assert.equal(poster('Blade.Runner.2049.2017.REMUX'), 'br');
  assert.equal(poster('Shogun.2024.S01.1080p'), 'sho');
  assert.equal(poster('No.Poster.2020.720p'), undefined);
  assert.equal(poster('ubuntu-24.04.1-desktop-amd64.iso'), undefined);
});
