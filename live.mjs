// Live Sonarr, Radarr and Jellyfin clients. Calls go through server.mjs (same origin), which holds the API keys.
export async function api(service, path, { method = 'GET', body } = {}) {
  const response = await fetch(`api/${service}${path && `/${path}`}`, { method, headers: { 'x-unifiarr': '1', ...(body && { 'content-type': 'application/json' }) }, body: body && JSON.stringify(body) });
  const text = await response.text();
  if (!response.ok) {
    let message = text.trim();
    try { const json = JSON.parse(text); message = json.message ?? json[0]?.errorMessage ?? json.title ?? message; } catch { /* plain text */ }
    throw Object.assign(new Error(message || `${service} returned ${response.status}.`), { status: response.status });
  }
  return text ? JSON.parse(text) : null;
}

export const connect = service => api(service, '').catch(() => null);

const code = (season, episode) => `S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')}`;
const poster = images => images?.find(image => image.coverType === 'poster')?.remoteUrl ?? '';
const reasons = list => (list ?? []).map(entry => entry.reason ?? entry);

export function mapTitle(service, t) {
  const sonarr = service === 'sonarr', stats = t.statistics ?? {};
  return {
    id: t.id, service, title: t.title, year: t.year, image: poster(t.images), monitored: t.monitored, profileId: t.qualityProfileId,
    path: t.path, size: t.sizeOnDisk ?? stats.sizeOnDisk ?? 0, status: t.status, network: (sonarr ? t.network : t.studio) ?? '', overview: t.overview ?? '',
    files: sonarr ? stats.episodeFileCount ?? 0 : +!!t.hasFile, total: sonarr ? stats.episodeCount ?? 0 : 1, quality: t.movieFile?.quality?.quality?.name ?? '',
    seasons: (t.seasons ?? []).map(s => ({ number: s.seasonNumber, monitored: s.monitored, files: s.statistics?.episodeFileCount ?? 0, total: s.statistics?.totalEpisodeCount ?? 0 })).sort((a, b) => (a.number || 1e3) - (b.number || 1e3)),
    raw: t,
  };
}

export const mapEpisode = e => ({ id: e.id, season: e.seasonNumber, number: e.episodeNumber, code: code(e.seasonNumber, e.episodeNumber), title: e.title ?? 'TBA', airDate: e.airDateUtc ?? null, hasFile: e.hasFile, monitored: e.monitored });

export function mapRecord(service, r) {
  const owner = r.series ?? r.movie ?? r;
  return {
    id: r.id, service, titleId: r.seriesId ?? r.movieId ?? r.id, title: owner.title ?? r.title, image: poster(owner.images),
    detail: r.episode ? `${code(r.episode.seasonNumber, r.episode.episodeNumber)} · ${r.episode.title}` : r.seasonNumber !== undefined && r.episodeNumber !== undefined ? `${code(r.seasonNumber, r.episodeNumber)} · ${r.title}` : String(owner.year ?? ''),
    date: r.airDateUtc ?? r.date ?? r.digitalRelease ?? r.physicalRelease ?? r.inCinemas ?? null,
    // Queue and history extras
    release: r.sourceTitle ?? (r.size !== undefined ? r.title : ''), event: r.eventType, quality: r.quality?.quality?.name ?? '',
    size: r.size, left: r.sizeleft, progress: r.size ? Math.floor((1 - r.sizeleft / r.size) * 1000) / 10 : 0, timeleft: r.timeleft ?? '', status: r.trackedDownloadState ?? r.status ?? '',
    warning: r.trackedDownloadStatus === 'warning' || r.trackedDownloadStatus === 'error', messages: (r.statusMessages ?? []).flatMap(m => m.messages), client: r.downloadClient ?? '', protocol: r.protocol ?? '',
  };
}

export const mapRelease = r => ({ guid: r.guid, indexerId: r.indexerId, title: r.title, indexer: r.indexer, size: r.size, age: r.ageHours ?? r.age * 24, seeders: r.seeders, leechers: r.leechers, quality: r.quality?.quality?.name ?? '', protocol: r.protocol, rejections: reasons(r.rejections), approved: r.approved });

export function arrClient(service) {
  const sonarr = service === 'sonarr', kind = sonarr ? 'series' : 'movie', include = sonarr ? 'includeSeries=true&includeEpisode=true' : 'includeMovie=true';
  const command = (name, args = {}) => api(service, 'command', { method: 'POST', body: { name, ...args } });
  return {
    titles: async () => (await api(service, kind)).map(t => mapTitle(service, t)).sort((a, b) => a.title.localeCompare(b.title)),
    health: () => api(service, 'health'),
    diskspace: () => api(service, 'diskspace'),
    async title(id) {
      const [t, episodes] = await Promise.all([api(service, `${kind}/${id}`), sonarr ? api(service, `episode?seriesId=${id}`) : []]);
      return { ...mapTitle(service, t), episodes: episodes.map(mapEpisode).sort((a, b) => a.season - b.season || a.number - b.number) };
    },
    // ponytail: first page only (50 newest); add paging when a library has more wanted items than that.
    wanted: async which => { const page = await api(service, `wanted/${which}?pageSize=50&monitored=true&sortKey=${sonarr ? 'episodes.airDateUtc' : 'movieMetadata.sortTitle'}&sortDirection=descending&${sonarr ? 'includeSeries=true' : ''}`); return { total: page.totalRecords, records: page.records.map(r => mapRecord(service, r)) }; },
    queue: async () => (await api(service, `queue?pageSize=50&${include}`)).records.map(r => mapRecord(service, r)),
    history: async () => (await api(service, `history?pageSize=30&sortKey=date&sortDirection=descending&${include}`)).records.map(r => mapRecord(service, r)),
    calendar: async (start, end) => (await api(service, `calendar?${new URLSearchParams({ start, end, unmonitored: 'false', ...(sonarr && { includeSeries: 'true' }) })}`))
      // Radarr lists a movie once per window; show the release that falls inside it.
      .map(r => {
        const index = Math.max(0, [r.inCinemas, r.digitalRelease, r.physicalRelease].findIndex(date => date >= start && date <= end));
        return { ...mapRecord(service, r), hasFile: !!r.hasFile, ...(!sonarr && { date: [r.inCinemas, r.digitalRelease, r.physicalRelease][index], detail: ['In cinemas', 'Digital release', 'Physical release'][index] }) };
      }),
    profiles: async () => (await api(service, 'qualityprofile')).map(p => ({ id: p.id, name: p.name })),
    releases: async target => (await api(service, `release?${new URLSearchParams(target)}`)).map(mapRelease).sort((a, b) => b.approved - a.approved),
    grab: release => api(service, 'release', { method: 'POST', body: { guid: release.guid, indexerId: release.indexerId } }),
    search: target => target.episodeIds ? command('EpisodeSearch', target) : target.seasonNumber !== undefined ? command('SeasonSearch', target) : sonarr ? command('SeriesSearch', target) : command('MoviesSearch', target),
    searchAll: which => command(sonarr ? (which === 'missing' ? 'MissingEpisodeSearch' : 'CutoffUnmetEpisodeSearch') : (which === 'missing' ? 'MissingMoviesSearch' : 'CutOffUnmetMoviesSearch')),
    refresh: id => command(sonarr ? 'RefreshSeries' : 'RefreshMovie', sonarr ? { seriesId: id } : { movieIds: [id] }),
    monitorEpisodes: (ids, monitored) => api(service, 'episode/monitor', { method: 'PUT', body: { episodeIds: ids, monitored } }),
    monitorSeason: (title, number, monitored) => api(service, `series/${title.id}`, { method: 'PUT', body: { ...title.raw, seasons: title.raw.seasons.map(s => s.seasonNumber === number ? { ...s, monitored } : s) } }),
    save: (title, { monitored, profileId, path }) => api(service, `${kind}/${title.id}?moveFiles=${path !== title.path}`, { method: 'PUT', body: { ...title.raw, monitored, qualityProfileId: profileId, path } }),
    remove: (id, deleteFiles) => api(service, `${kind}/${id}?deleteFiles=${deleteFiles}`, { method: 'DELETE' }),
    removeQueue: (id, blocklist) => api(service, `queue/${id}?removeFromClient=true&blocklist=${blocklist}`, { method: 'DELETE' }),
  };
}

const jfImage = (id, tag) => tag ? `api/jellyfin/Items/${id}/Images/Primary?maxHeight=480&tag=${tag}` : '';
const quality = width => width >= 3200 ? '4K' : width >= 1800 ? '1080p' : width >= 1200 ? '720p' : width ? 'SD' : '';
const KINDS = ['movies', 'tvshows', 'mixed'];

export const mapItem = (i, library) => ({ id: i.Id, jellyfin: true, library, status: 'available', title: i.Name, year: i.ProductionYear ?? '', type: i.Type === 'Series' ? 'Series' : 'Movie', genre: i.Genres?.[0] ?? '', rating: i.CommunityRating ? i.CommunityRating.toFixed(1) : '—', image: jfImage(i.Id, i.ImageTags?.Primary), quality: quality(i.Width), added: i.DateCreated ?? '' });

export function mapSession(s, previous = []) {
  const n = s.NowPlayingItem, play = s.PlayState ?? {};
  return {
    id: s.Id, user: s.UserName ?? 'Unknown', device: s.DeviceName ?? '', client: s.Client ?? '', lastActive: s.LastActivityDate, remote: !!s.SupportsRemoteControl,
    status: play.IsPaused ? 'paused' : 'playing', position: Math.floor((play.PositionTicks ?? 0) / 1e7), duration: Math.max(1, Math.floor((n?.RunTimeTicks ?? 0) / 1e7)),
    mode: { Transcode: 'Transcoding', DirectStream: 'Direct stream' }[play.PlayMethod] ?? 'Direct play', quality: quality(n?.Width),
    item: n && { title: n.SeriesName ?? n.Name, type: n.Type === 'Episode' ? 'Series' : 'Movie', image: n.Type === 'Episode' ? jfImage(n.SeriesId, n.SeriesPrimaryImageTag) : jfImage(n.Id, n.ImageTags?.Primary), detail: n.Type === 'Episode' ? `${code(n.ParentIndexNumber ?? 0, n.IndexNumber ?? 0)} · ${n.Name}` : [n.ProductionYear, n.Genres?.[0]].filter(Boolean).join(' · ') },
    lastMessage: previous.find(old => old.id === s.Id)?.lastMessage ?? '', at: Date.now(),
  };
}

export const jellyfinClient = {
  async library() {
    const folders = (await api('jellyfin', 'Library/VirtualFolders')).map(v => ({ id: v.ItemId, name: v.Name, collection: v.CollectionType ?? 'mixed', path: v.Locations?.[0] ?? '', scanning: v.RefreshStatus === 'Active' })).filter(v => KINDS.includes(v.collection));
    // ponytail: newest 300 titles per library; add paging/infinite scroll for bigger libraries.
    const items = await Promise.all(folders.map(async folder => (await api('jellyfin', `Items?ParentId=${folder.id}&Recursive=true&IncludeItemTypes=Movie,Series&Fields=Genres,Width,DateCreated&SortBy=DateCreated&SortOrder=Descending&Limit=300`)).Items.map(i => mapItem(i, folder.id))));
    // Series bump to their newest episode so new episodes show in "Recently added".
    const episodes = (await api('jellyfin', 'Items?Recursive=true&IncludeItemTypes=Episode&Fields=DateCreated&SortBy=DateCreated&SortOrder=Descending&Limit=50')).Items;
    const all = items.flat();
    for (const item of all) {
      const e = episodes.find(e => e.SeriesId === item.id);
      if (e?.DateCreated > item.added) Object.assign(item, { added: e.DateCreated, episode: code(e.ParentIndexNumber ?? 0, e.IndexNumber ?? 0) });
    }
    return { libraries: folders, items: all };
  },
  async item(id) {
    const [i] = (await api('jellyfin', `Items?Ids=${id}&Fields=People,Overview,Studios,Taglines,Genres,Path`)).Items;
    return { overview: i.Overview ?? '', tagline: i.Taglines?.[0] ?? '', genres: i.Genres ?? [], studios: (i.Studios ?? []).map(s => s.Name), runtime: i.RunTimeTicks ? Math.round(i.RunTimeTicks / 6e8) : 0, rated: i.OfficialRating ?? '', path: i.Path ?? '', cast: (i.People ?? []).filter(p => p.Type === 'Actor').slice(0, 12).map(p => ({ name: p.Name, role: p.Role ?? '', image: jfImage(p.Id, p.PrimaryImageTag) })) };
  },
  scan: library => api('jellyfin', library ? `Items/${library}/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default` : 'Library/Refresh', { method: 'POST' }),
  refresh: id => api('jellyfin', `Items/${id}/Refresh?Recursive=true&MetadataRefreshMode=FullRefresh&ImageRefreshMode=FullRefresh&ReplaceAllMetadata=false&ReplaceAllImages=false`, { method: 'POST' }),
  sessions: async previous => (await api('jellyfin', 'Sessions?ActiveWithinSeconds=960')).map(s => mapSession(s, previous)),
  playback: (id, action, text) => action === 'message'
    ? api('jellyfin', `Sessions/${id}/Message`, { method: 'POST', body: { Header: 'Unifiarr', Text: text, TimeoutMs: 15000 } })
    : api('jellyfin', `Sessions/${id}/Playing/${{ play: 'Unpause', pause: 'Pause', stop: 'Stop' }[action]}`, { method: 'POST' }),
  devices(previous) { return this.sessions(previous); },
  users: async () => (await api('jellyfin', 'Users')).map(u => ({ id: u.Id, name: u.Name, admin: u.Policy.IsAdministrator, disabled: u.Policy.IsDisabled, lastActive: u.LastActivityDate ?? null, policy: u.Policy })),
  setDisabled: (user, disabled) => api('jellyfin', `Users/${user.id}/Policy`, { method: 'POST', body: { ...user.policy, IsDisabled: disabled } }),
  createUser: (name, password) => api('jellyfin', 'Users/New', { method: 'POST', body: { Name: name, Password: password } }),
  deleteUser: id => api('jellyfin', `Users/${id}`, { method: 'DELETE' }),
  tasks: async () => (await api('jellyfin', 'ScheduledTasks?isHidden=false')).map(t => ({ id: t.Id, name: t.Name, category: t.Category, description: t.Description, state: t.State, progress: t.CurrentProgressPercentage ?? 0, result: t.LastExecutionResult && { status: t.LastExecutionResult.Status, end: t.LastExecutionResult.EndTimeUtc } })).sort((a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name)),
  runTask: id => api('jellyfin', `ScheduledTasks/Running/${id}`, { method: 'POST' }),
  stopTask: id => api('jellyfin', `ScheduledTasks/Running/${id}`, { method: 'DELETE' }),
  log: async () => (await api('jellyfin', 'System/ActivityLog/Entries?limit=40')).Items.map(e => ({ id: e.Id, name: e.Name, detail: e.ShortOverview ?? '', severity: e.Severity, date: e.Date })),
};

// Seerr: media status 2 pending, 3 processing, 4 partly available, 5 available; request status 1 pending … 5 completed.
const mediaStatus = { 2: 'requested', 3: 'requested', 4: 'partial', 5: 'available' };
const requestStatus = { 1: 'pending', 2: 'approved', 3: 'declined', 4: 'failed', 5: 'completed' };
export const mapSeerr = r => ({ id: `${r.mediaType}-${r.id}`, tmdb: r.id, seerr: true, type: r.mediaType === 'tv' ? 'Series' : 'Movie', title: r.title ?? r.name ?? 'Unknown', year: (r.releaseDate ?? r.firstAirDate ?? '').slice(0, 4), genre: r.genres?.[0]?.name ?? '', rating: r.voteAverage ? r.voteAverage.toFixed(1) : '—', poster: r.posterPath ?? '', status: mediaStatus[r.mediaInfo?.status] ?? 'none', quality: '' });
const titles = new Map();
const seerrTitle = (kind, tmdb) => {
  if (!titles.has(`${kind}-${tmdb}`)) titles.set(`${kind}-${tmdb}`, api('seerr', `${kind}/${tmdb}`).catch(error => { titles.delete(`${kind}-${tmdb}`); throw error; }));
  return titles.get(`${kind}-${tmdb}`);
};

export const seerrClient = {
  async discover(type, query, page = 1) {
    const kind = { Movie: 'movie', Series: 'tv' }[type];
    const { results, totalPages } = await api('seerr', query ? `search?query=${encodeURIComponent(query)}&page=${page}` : `discover/${kind ? { movie: 'movies', tv: 'tv' }[kind] : 'trending'}?page=${page}`);
    return { items: results.filter(r => kind ? r.mediaType === kind : ['movie', 'tv'].includes(r.mediaType)).map(mapSeerr), more: page < totalPages };
  },
  // ponytail: newest 50 requests; titles come from one cached lookup per title (the request list has none).
  async requests() {
    const { results } = await api('seerr', 'request?take=50&skip=0&filter=all&sort=added');
    return Promise.all(results.map(async r => {
      const kind = r.type ?? r.media.mediaType, info = await seerrTitle(kind, r.media.tmdbId).catch(() => ({ id: r.media.tmdbId, mediaType: kind }));
      return { ...mapSeerr({ ...info, mediaType: kind, mediaInfo: r.media }), request: r.id, requestStatus: requestStatus[r.status] ?? 'pending', seasons: (r.seasons ?? []).map(s => s.seasonNumber), by: r.requestedBy?.displayName ?? 'Unknown', date: r.createdAt, is4k: r.is4k };
    }));
  },
  // Details plus what each season can still be requested for, and the default Sonarr/Radarr server's options.
  async title(id) {
    const [kind, tmdb] = id.split('-');
    titles.delete(id);
    const [info, servers] = await Promise.all([seerrTitle(kind, tmdb), api('seerr', `service/${kind === 'tv' ? 'sonarr' : 'radarr'}`)]);
    const server = servers.find(entry => entry.isDefault && !entry.is4k) ?? servers.find(entry => !entry.is4k);
    const options = server && await api('seerr', `service/${kind === 'tv' ? 'sonarr' : 'radarr'}/${server.id}`);
    const active = (info.mediaInfo?.requests ?? []).filter(r => r.status !== 3);
    const seasons = (info.seasons ?? []).filter(s => s.seasonNumber > 0).map(s => {
      const available = info.mediaInfo?.seasons?.find(entry => entry.seasonNumber === s.seasonNumber)?.status;
      return { number: s.seasonNumber, episodes: s.episodeCount, status: available === 5 ? 'Available' : available === 4 ? 'Partly available' : active.some(r => r.seasons?.some(entry => entry.seasonNumber === s.seasonNumber)) ? 'Requested' : '' };
    });
    return { ...mapSeerr({ ...info, mediaType: kind }), overview: info.overview ?? '', seasons, requested: active.length > 0, options };
  },
  request: body => api('seerr', 'request', { method: 'POST', body }),
  act: (id, action) => api('seerr', action === 'delete' ? `request/${id}` : `request/${id}/${action}`, { method: action === 'delete' ? 'DELETE' : 'POST' }),
};
