package aj.unifiarr.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ---------- Seerr data (live.mjs seerrClient + mapSeerr) ----------

private data class SeerrItem(
    val id: String, val tmdb: Int, val kind: String, val title: String, val year: String, val genre: String,
    val rating: String, val poster: String, val backdrop: String, val status: String,
) { val series get() = kind == "tv" }

private data class SeerrRequest(
    val item: SeerrItem, val request: Int, val requestStatus: String, val seasons: List<Int>, val by: String, val date: String, val is4k: Boolean,
)

// Media status 2 pending, 3 processing, 4 partly available, 5 available; request status 1 pending … 5 completed.
private val seerrMediaStatus = mapOf(2 to "requested", 3 to "requested", 4 to "partial", 5 to "available")
private val seerrRequestStatus = mapOf(1 to "pending", 2 to "approved", 3 to "declined", 4 to "failed", 5 to "completed")

private fun mapSeerr(r: JSONObject, kind: String = r.str("mediaType"), media: JSONObject? = r.obj("mediaInfo"), fourK: Boolean = false): SeerrItem {
    val tmdb = r.optInt("id")
    val vote = r.optDouble("voteAverage", 0.0).takeUnless { it.isNaN() } ?: 0.0
    return SeerrItem(
        id = "$kind-$tmdb", tmdb = tmdb, kind = kind,
        title = r.strOrNull("title") ?: r.strOrNull("name") ?: "Unknown",
        year = (r.strOrNull("releaseDate") ?: r.strOrNull("firstAirDate") ?: "").take(4),
        genre = r.optJSONArray("genres")?.optJSONObject(0)?.str("name").orEmpty(),
        rating = if (vote > 0) String.format(Locale.US, "%.1f", vote) else "—",
        poster = r.str("posterPath"), backdrop = r.str("backdropPath"),
        status = seerrMediaStatus[media?.optInt(if (fourK) "status4k" else "status")] ?: "none",
    )
}

private fun tmdbImage(path: String, size: String = "w342") = path.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

private object SeerrStore {
    private val titles = ConcurrentHashMap<String, JSONObject>()
    /** Newest requests; shared so the bottom-bar badge and every visit start from the last known list. */
    var requests by mutableStateOf<List<SeerrRequest>?>(null)
    /** Bumped after a request/approve/decline/delete so Discover and Requests reload. */
    var version by mutableIntStateOf(0)

    suspend fun title(kind: String, tmdb: Int, fresh: Boolean = false): JSONObject {
        val key = "$kind-$tmdb"
        if (!fresh) titles[key]?.let { return it }
        return Api.obj("seerr", "$kind/$tmdb").also { titles[key] = it }
    }

    suspend fun discover(type: Int, query: String, page: Int): Pair<List<SeerrItem>, Boolean> {
        val kind = when (type) { 1 -> "movie"; 2 -> "tv"; else -> null }
        val path = if (query.isNotEmpty()) "search?query=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}&page=$page"
        else "discover/${when (kind) { "movie" -> "movies"; "tv" -> "tv"; else -> "trending" }}?page=$page"
        val json = Api.obj("seerr", path)
        val items = json.list("results").filter { val t = it.str("mediaType"); if (kind != null) t == kind else t == "movie" || t == "tv" }.map { mapSeerr(it) }
        return items to (page < json.optInt("totalPages"))
    }

    // ponytail: newest 50 requests like the web; titles come from one cached lookup per title (the request list has none).
    suspend fun loadRequests() {
        val results = Api.obj("seerr", "request?take=50&skip=0&filter=all&sort=added").list("results")
        val list = coroutineScope {
            results.map { r ->
                async {
                    val media = r.obj("media") ?: JSONObject()
                    val kind = r.strOrNull("type") ?: media.str("mediaType")
                    val tmdb = media.optInt("tmdbId")
                    val info = runCatching { title(kind, tmdb) }.getOrElse { JSONObject().put("id", tmdb) }
                    SeerrRequest(
                        mapSeerr(info, kind, media, r.optBoolean("is4k")), r.optInt("id"), seerrRequestStatus[r.optInt("status")] ?: "pending",
                        r.list("seasons").map { it.optInt("seasonNumber") }, r.obj("requestedBy")?.strOrNull("displayName") ?: "Unknown",
                        r.str("createdAt"), r.optBoolean("is4k"),
                    )
                }
            }.awaitAll()
        }
        requests = list
        Badges.pendingRequests = list.count { it.requestStatus == "pending" }
    }
}

// ---------- Shared bits ----------

@Composable
private fun SeerrOffline(app: AppState) {
    if (!app.live.containsKey("seerr") || app.isLive("seerr")) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(Round).background(C.warn.copy(alpha = .1f)).border(1.dp, C.warn.copy(alpha = .35f), Round)
            .clickable { app.go(Screen.Services) }.padding(14.dp),
    ) {
        Text("Can’t reach Seerr.", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = C.warn)
        Text("Check that it’s running, or update its address in Services.", style = Type.small, color = C.mute)
    }
}

@Composable
private fun RequestBadge(status: String) {
    val (label, color) = when (status) {
        "pending" -> "Pending" to C.radarr
        "approved" -> "Approved" to C.ok
        "declined" -> "Declined" to C.danger
        "failed" -> "Failed" to C.mute
        "completed" -> "Completed" to C.mute
        "Available" -> status to C.ok
        "" -> "Not requested" to C.mute
        else -> status to C.radarr
    }
    Pill(label, color)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonChips(seasons: List<Int>) {
    if (seasons.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        seasons.forEach { Text("S$it", style = Type.tiny, color = C.mute, modifier = Modifier.border(1.dp, C.line, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) }
    }
}

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = Type.tiny.copy(fontWeight = FontWeight.Bold), color = C.accent)
    }
}

// ---------- Discover (web renderCatalog for discover + moreDiscover) ----------

private val discoverTypes = listOf("All", "Movies", "TV shows")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var type by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf(app.query.trim()) }
    LaunchedEffect(app.query) { val q = app.query.trim(); if (q != query) { delay(350); query = q } }
    val key = "$type|$query"
    var items by remember { mutableStateOf<List<SeerrItem>?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }

    // Reloads refresh every page already scrolled through, so the list keeps its place (like the web).
    suspend fun reload() {
        val count = if (loadedKey == key) pages.coerceAtLeast(1) else 1
        try {
            val results = coroutineScope { (1..count).map { page -> async { SeerrStore.discover(type, query, page) } }.awaitAll() }
            items = results.flatMap { it.first }.distinctBy { it.id }
            pages = count; more = results.last().second; loadedKey = key; error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (items == null || loadedKey != key) error = e.message ?: "Couldn't reach Seerr." else Toasts.fail(e)
        }
    }
    LaunchedEffect(key, SeerrStore.version, app.refresh) { reload() }

    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { val info = grid.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 12 } }
    LaunchedEffect(nearEnd, more, loadedKey, pages, key) {
        if (!nearEnd || !more || loadingMore || loadedKey != key) return@LaunchedEffect
        val next = pages + 1
        loadingMore = true
        try {
            val (list, hasMore) = SeerrStore.discover(type, query, next)
            if (pages == next - 1) { items = (items.orEmpty() + list).distinctBy { it.id }; pages = next; more = hasMore }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Toasts.fail(e) } finally { loadingMore = false }
    }

    val shown = items?.filter { type == 0 || (type == 2) == it.series }
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; reload(); refreshing = false } }, Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("heading", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    PageHeading("Find and request", if (query.isNotEmpty()) "Results from Seerr for “$query”." else "Trending now. Discover a new favorite and request it from Seerr.")
                    SeerrOffline(app)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Segmented(discoverTypes, type, { type = it })
                        Spacer(Modifier.weight(1f))
                        shown?.let { Text("${it.size} titles", style = Type.small, color = C.mute) }
                    }
                    if (loadedKey != key && items != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp).clip(CircleShape), color = C.accent, trackColor = C.soft)
                }
            }
            when {
                shown == null && error != null -> item("error", span = { GridItemSpan(maxLineSpan) }) { ErrorBox(error!!, { scope.launch { reload() } }) }
                shown == null -> item("loading", span = { GridItemSpan(maxLineSpan) }) { Loader("Loading titles…") }
                shown.isEmpty() && !more -> item("empty", span = { GridItemSpan(maxLineSpan) }) { Empty("No titles found. Try another search or media type.", icon = Icons.Rounded.SearchOff) }
                else -> items(shown, key = { it.id }) { MediaCard(it) { open = it.id } }
            }
            if (shown != null && more) item("more", span = { GridItemSpan(maxLineSpan) }) { Loader("Loading more titles…") }
        }
    }
    open?.let { SeerrTitleSheet(it) { open = null } }
}

@Composable
private fun MediaCard(item: SeerrItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
            Poster(tmdbImage(item.poster), Modifier.matchParentSize(), fallback = Icons.Rounded.Movie)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = .72f))))
            StatusLabel(item.status, Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = Type.subtitle.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(item.year, if (item.series) "TV Series" else "Movie").filter { it.isNotEmpty() }.joinToString(" · "), style = Type.small, color = C.mute, modifier = Modifier.weight(1f), maxLines = 1)
            Text("★ ${item.rating}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.radarr)
        }
    }
}

private val statusLabels = mapOf("available" to "In your library", "requested" to "Requested", "partial" to "Partly available", "none" to "Available to request")

@Composable
private fun StatusLabel(status: String, modifier: Modifier = Modifier) {
    val dot = when (status) { "available" -> Color(0xFF77D8AF); "partial" -> Color(0xFF98BCF3); "requested" -> Color(0xFFF0A36F); else -> Color.White.copy(alpha = .8f) }
    Row(modifier.clip(CircleShape).background(Color.Black.copy(alpha = .35f)).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(4.dp))
        Text(statusLabels[status] ?: status, style = Type.tiny.copy(fontSize = 10.sp), color = Color.White, maxLines = 1)
    }
}

// ---------- Request sheet (web showRequest + request-form handlers) ----------

private data class SeasonRow(val number: Int, val episodes: Int, val status: String)

private class RequestChoice {
    var profile by mutableIntStateOf(-1)
    var folder by mutableStateOf("")
    var language by mutableIntStateOf(-1)
    val tags = mutableStateListOf<Int>()
}

@Composable
fun SeerrTitleSheet(id: String, onDismiss: () -> Unit) {
    val kind = id.substringBefore('-')
    val tmdb = id.substringAfter('-').toIntOrNull() ?: 0
    val service = if (kind == "tv") "sonarr" else "radarr"
    val details = load(id) {
        coroutineScope {
            val info = async { SeerrStore.title(kind, tmdb, fresh = true) }
            val servers = async { Api.arr("seerr", "service/$service").objects() }
            info.await() to servers.await()
        }
    }
    Sheet(onDismiss) {
        Show(details, "Loading from Seerr") { (info, servers) -> RequestForm(kind, tmdb, info, servers, onDismiss) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequestForm(kind: String, tmdb: Int, info: JSONObject, servers: List<JSONObject>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val series = kind == "tv"
    val service = if (series) "sonarr" else "radarr"
    val has4k = servers.any { it.optBoolean("is4k") }
    var fourK by remember { mutableStateOf(false) }
    val server = servers.firstOrNull { it.optBoolean("isDefault") && it.optBoolean("is4k") == fourK } ?: servers.firstOrNull { it.optBoolean("is4k") == fourK }
    val serverId = server?.optInt("id")
    val options = load(service, serverId, fourK) { serverId?.let { Api.obj("seerr", "service/$service/$it") } ?: JSONObject() }

    val media = info.obj("mediaInfo")
    val item = mapSeerr(info, kind, media, fourK)
    val requests = media?.list("requests").orEmpty().filter { it.optBoolean("is4k") == fourK }
    val active = requests.filter { it.optInt("status") != 3 }
    val seasons = info.list("seasons").filter { it.optInt("seasonNumber") > 0 }.map { s ->
        val n = s.optInt("seasonNumber")
        val available = media?.list("seasons")?.firstOrNull { it.optInt("seasonNumber") == n }?.optInt(if (fourK) "status4k" else "status")
        SeasonRow(n, s.optInt("episodeCount"), when {
            available == 5 -> "Available"
            available == 4 -> "Partly available"
            active.any { r -> r.list("seasons").any { it.optInt("seasonNumber") == n } } -> "Requested"
            else -> ""
        })
    }
    val open = seasons.filter { it.status.isEmpty() }
    val done = if (series) open.isEmpty() else item.status != "none" || active.isNotEmpty()
    val picked = remember(fourK) { mutableStateListOf<Int>() }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val opts = options.value?.takeIf { it.has("server") }
    val choice = remember(opts) {
        RequestChoice().apply {
            if (opts == null) return@apply
            val s = opts.obj("server")!!
            val profiles = opts.list("profiles")
            profile = profiles.firstOrNull { it.optInt("id") == s.optInt("activeProfileId", -1) }?.optInt("id") ?: profiles.firstOrNull()?.optInt("id") ?: -1
            val folders = opts.list("rootFolders").map { it.str("path") }
            folder = folders.firstOrNull { it == s.str("activeDirectory") } ?: folders.firstOrNull().orEmpty()
            val languages = opts.list("languageProfiles")
            language = languages.firstOrNull { it.optInt("id") == s.optInt("activeLanguageProfileId", -1) }?.optInt("id") ?: languages.firstOrNull()?.optInt("id") ?: -1
            tags.addAll(s.optJSONArray("activeTags").strings().mapNotNull { it.toIntOrNull() })
        }
    }

    // Header: backdrop, poster, eyebrow, title, facts.
    // Backdrop fades into the sheet; the poster row overlaps its bottom 35%.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val backdrop = item.backdrop.isNotBlank()
    if (backdrop) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        Poster(tmdbImage(item.backdrop, "w780"), Modifier.matchParentSize(), shape = RoundedCornerShape(14.dp))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to C.panel)))
    }
    Row(Modifier.padding(top = if (backdrop) maxWidth * 9f / 16f * .65f else 0.dp, start = 12.dp), verticalAlignment = Alignment.Bottom) {
        Poster(tmdbImage(item.poster), Modifier.size(84.dp, 126.dp), fallback = Icons.Rounded.Movie)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (series) "REQUEST SERIES" else "REQUEST MOVIE", style = Type.eyebrow, color = C.seerr)
            Spacer(Modifier.height(4.dp))
            Text(item.title, style = Type.title)
            Text(listOfNotNull(item.year.ifEmpty { null }, item.genre.ifEmpty { null }, item.rating.takeIf { it != "—" }?.let { "★ $it" }).joinToString(" · "), style = Type.small, color = C.mute)
            Spacer(Modifier.height(6.dp))
            Pill(statusLabels[item.status] ?: item.status, when (item.status) { "available" -> C.ok; "partial" -> C.qbittorrent; "requested" -> C.radarr; else -> C.seerr }, dot = true)
        }
    }
    }
    info.strOrNull("overview")?.let { Spacer(Modifier.height(14.dp)); Text(it, style = Type.body, color = C.mute) }

    if (has4k) {
        Spacer(Modifier.height(10.dp))
        Toggle("Request in 4K", fourK, { fourK = it }, note = "Uses Seerr’s 4K ${if (series) "Sonarr" else "Radarr"} server.")
    }

    if (series && seasons.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundSmall).border(1.dp, C.line, RoundSmall)) {
            Row(Modifier.fillMaxWidth().background(C.soft).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (open.isNotEmpty()) TriStateCheckbox(
                        when (picked.size) { 0 -> ToggleableState.Off; open.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                        { val all = picked.size != open.size; picked.clear(); if (all) picked.addAll(open.map { it.number }) },
                        colors = CheckboxDefaults.colors(checkedColor = C.accent, uncheckedColor = C.mute),
                    )
                }
                Text("Season", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.mute, modifier = Modifier.weight(1f))
                Text("Episodes", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.mute, modifier = Modifier.width(70.dp))
                Text("Status", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.mute, modifier = Modifier.width(110.dp).padding(end = 8.dp))
            }
            seasons.forEach { season ->
                HorizontalDivider(color = C.line)
                val selectable = season.status.isEmpty()
                val on = season.number in picked
                val toggle = { if (on) picked.remove(season.number) else picked.add(season.number); Unit }
                Row(
                    Modifier.fillMaxWidth().then(if (selectable) Modifier.clickable(onClick = toggle) else Modifier).padding(horizontal = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (selectable) Checkbox(on, { toggle() }, colors = CheckboxDefaults.colors(checkedColor = C.accent, uncheckedColor = C.mute))
                    }
                    Text("Season ${season.number}", style = Type.body, modifier = Modifier.weight(1f))
                    Text("${season.episodes}", style = Type.body, color = C.mute, modifier = Modifier.width(70.dp))
                    Box(Modifier.width(110.dp).padding(end = 8.dp)) { RequestBadge(season.status) }
                }
            }
        }
    }

    // Existing requests for this title (status, who, when, which seasons).
    if (requests.isNotEmpty()) {
        SectionTitle("Requests")
        requests.forEach { r ->
            val by = r.obj("requestedBy")?.strOrNull("displayName") ?: "Unknown"
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(by)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("$by · ${ago(r.str("createdAt"))}", style = Type.small)
                    SeasonChips(r.list("seasons").map { it.optInt("seasonNumber") })
                }
                RequestBadge(seerrRequestStatus[r.optInt("status")] ?: "pending")
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    when {
        done -> Text(if (item.status == "available") "Already in your library." else "Already requested.", style = Type.small, color = C.mute)
        server == null -> Text("Seerr has no ${if (series) "Sonarr" else "Radarr"}${if (fourK) " 4K" else ""} server yet, so it will keep the request until one is added.", style = Type.small, color = C.mute)
        options.value == null && options.error != null -> ErrorBox(options.error!!, { options.reload() })
        opts == null -> Loader("Loading server options…")
        else -> {
            Row(
                Modifier.fillMaxWidth().clip(RoundSmall).border(1.dp, C.line, RoundSmall).clickable { advanced = !advanced }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Settings, null, tint = C.mute, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Advanced", style = Type.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = C.mute)
            }
            if (advanced) {
                Spacer(Modifier.height(12.dp))
                Select("Quality profile", opts.list("profiles").map { it.optInt("id") to it.str("name") }, choice.profile, { choice.profile = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Select("Root folder", opts.list("rootFolders").map { it.str("path") to it.str("path") + (if (it.has("freeSpace") && !it.isNull("freeSpace")) " · ${bytes(it.optLong("freeSpace"))} free" else "") }, choice.folder, { choice.folder = it }, Modifier.fillMaxWidth())
                val languages = opts.list("languageProfiles")
                if (languages.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Select("Language profile", languages.map { it.optInt("id") to it.str("name") }, choice.language, { choice.language = it }, Modifier.fillMaxWidth())
                }
                val tags = opts.list("tags")
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Tags", style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            val tagId = tag.optInt("id")
                            Chip(tag.str("label"), tagId in choice.tags, { if (tagId in choice.tags) choice.tags.remove(tagId) else choice.tags.add(tagId) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Sent through Seerr to ${server.str("name")}.", style = Type.small, color = C.mute)
        }
    }

    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!done) UButton(
            when { !series -> "Request"; picked.isEmpty() -> "Select season(s)"; else -> "Request ${picked.size} season${if (picked.size == 1) "" else "s"}" },
            {
                val body = mutableMapOf<String, Any>("mediaType" to kind, "mediaId" to tmdb)
                if (series) body["seasons"] = picked.sorted()
                if (fourK) body["is4k"] = true
                if (opts != null && server != null) {
                    body["serverId"] = server.optInt("id"); body["profileId"] = choice.profile; body["rootFolder"] = choice.folder; body["tags"] = choice.tags.toList()
                    if (choice.language >= 0) body["languageProfileId"] = choice.language
                }
                busy = true
                scope.launch {
                    try {
                        val result = Api.call("seerr", "request", "POST", body) as? JSONObject
                        Toasts.show("Requested ${item.title}${if (result?.optInt("status") == 1) " · Pending approval" else ""}")
                        SeerrStore.version++
                        onDismiss()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { Toasts.fail(e) } finally { busy = false }
                }
            },
            Modifier.weight(1f), icon = Icons.Rounded.Download, enabled = !series || picked.isNotEmpty(), busy = busy,
        )
        UButton(if (done) "Close" else "Cancel", onDismiss, if (done) Modifier.fillMaxWidth() else Modifier, kind = ButtonKind.Ghost)
    }
}

// ---------- Requests (web renderRequests + requestRow + seerr-approve/decline/delete) ----------

private val requestFilters = listOf("all" to "All", "pending" to "Pending", "approved" to "Approved", "declined" to "Declined", "failed" to "Failed")
private fun requestGroup(r: SeerrRequest) = if (r.requestStatus == "completed") "approved" else r.requestStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("pending") }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var open by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SeerrRequest?>(null) }
    var busy by remember { mutableStateOf(setOf<Int>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try { SeerrStore.loadRequests(); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (SeerrStore.requests == null) error = e.message ?: "Couldn't reach Seerr." else Toasts.fail(e)
        }
    }
    LaunchedEffect(SeerrStore.version, app.refresh) { refresh() }
    // Like the web: poll while no dialog is open so nothing jumps under the finger.
    Every(5000, open, deleting) { if (open == null && deleting == null) SeerrStore.loadRequests() }

    fun act(r: SeerrRequest, step: String) {
        busy = busy + r.request
        scope.act(mapOf("approve" to "Request approved", "decline" to "Request declined", "delete" to "Request deleted")[step]) {
            try {
                if (step == "delete") Api.call("seerr", "request/${r.request}", "DELETE") else Api.call("seerr", "request/${r.request}/$step", "POST")
                SeerrStore.version++
            } finally { busy = busy - r.request }
        }
    }

    val needle = app.query.trim().lowercase()
    val all = SeerrStore.requests?.filter { it.item.title.lowercase().contains(needle) }
    val shown = all?.filter { filter == "all" || requestGroup(it) == filter }
        ?.let { list -> if (sort == "title") list.sortedBy { it.item.title.lowercase() } else list.sortedByDescending { instant(it.date) } }

    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; refresh(); refreshing = false } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("heading") {
                Column {
                    PageHeading("Requests", "Review, approve and track requests from Seerr.") {
                        UButton("Request something", { app.go(Screen.Discover) }, icon = Icons.Rounded.Add)
                    }
                    SeerrOffline(app)
                    if (all != null) {
                        Segmented(requestFilters.map { (value, name) -> "$name ${if (value == "all") all.size else all.count { requestGroup(it) == value }}" },
                            requestFilters.indexOfFirst { it.first == filter }, { filter = requestFilters[it].first }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Select("", listOf("recent" to "Sort: Most recent", "title" to "Sort: Title"), sort, { sort = it }, Modifier.width(200.dp))
                    }
                }
            }
            when {
                all == null && error != null -> item("error") { ErrorBox(error!!, { scope.launch { refresh() } }) }
                all == null -> item("loading") { Loader("Loading requests…") }
                shown.isNullOrEmpty() -> item("empty") {
                    Empty(if (needle.isNotEmpty()) "No matching requests." else if (filter == "pending") "All caught up. New requests show up here." else "No requests here yet.", icon = Icons.Rounded.Inbox)
                }
                else -> items(shown, key = { it.request }) { r ->
                    RequestRow(r, r.request in busy, { open = r.item.id }, { act(r, "approve") }, { act(r, "decline") }, { deleting = r })
                }
            }
        }
    }
    open?.let { SeerrTitleSheet(it) { open = null } }
    deleting?.let { r ->
        Confirm("Delete this request?", "Seerr forgets the request for ${r.item.title}. Anything already downloaded stays.", "Delete request", { act(r, "delete") }, { deleting = null })
    }
}

@Composable
private fun RequestRow(r: SeerrRequest, busy: Boolean, onOpen: () -> Unit, onApprove: () -> Unit, onDecline: () -> Unit, onDelete: () -> Unit) {
    val item = r.item
    Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row {
            Poster(tmdbImage(item.poster), Modifier.size(56.dp, 84.dp).clickable(onClick = onOpen), shape = RoundedCornerShape(10.dp), fallback = Icons.Rounded.Movie)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(item.title, style = Type.subtitle, modifier = Modifier.weight(1f).clickable(onClick = onOpen), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    RequestBadge(r.requestStatus)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(item.year.ifEmpty { null }, if (item.series) "TV series" else "Movie", "4K".takeIf { r.is4k },
                        when (item.status) { "available" -> "In your library"; "partial" -> "Partly available"; else -> null }).joinToString(" · "),
                    style = Type.small, color = C.mute,
                )
                if (r.seasons.isNotEmpty()) { Spacer(Modifier.height(4.dp)); SeasonChips(r.seasons) }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(r.by)
                    Spacer(Modifier.width(8.dp))
                    Text("${r.by} · ${ago(r.date)}", style = Type.small, color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.requestStatus == "pending") {
                UButton("Approve", onApprove, Modifier.weight(1f), icon = Icons.Rounded.Check, busy = busy)
                UButton("Decline", onDecline, Modifier.weight(1f), icon = Icons.Rounded.Close, kind = ButtonKind.Ghost, enabled = !busy)
            }
            UButton("Delete", onDelete, if (r.requestStatus == "pending") Modifier else Modifier.weight(1f), icon = Icons.Rounded.Delete, kind = ButtonKind.Ghost, enabled = !busy)
        }
    }
}
