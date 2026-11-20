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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

// ---------- Sonarr / Radarr client (live.mjs arrClient + mappers) ----------

private data class ArrSeason(val number: Int, val monitored: Boolean, val files: Int, val total: Int)
private data class ArrEpisode(val id: Int, val season: Int, val number: Int, val code: String, val title: String, val airDate: String?, val hasFile: Boolean, val monitored: Boolean)
private data class ArrTitle(
    val id: Int, val service: String, val title: String, val year: Int, val image: String, val monitored: Boolean, val profileId: Int,
    val path: String, val size: Long, val status: String, val network: String, val overview: String, val files: Int, val total: Int,
    val quality: String, val seasons: List<ArrSeason>, val raw: JSONObject, val episodes: List<ArrEpisode> = emptyList(),
) {
    val sonarr get() = service == "sonarr"
    val summary get() = if (sonarr) "$files of $total episodes" else if (files > 0) "Downloaded" else "Missing"
}
private data class ArrRelease(
    val guid: String, val indexerId: Int, val title: String, val indexer: String, val size: Long, val age: Double, val seeders: Int?, val leechers: Int?,
    val quality: String, val protocol: String, val rejections: List<String>, val approved: Boolean,
)

private fun code(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)
private fun JSONObject.intOrNull(key: String) = if (has(key) && !isNull(key)) optInt(key) else null

private fun mapTitle(service: String, t: JSONObject): ArrTitle {
    val sonarr = service == "sonarr"
    val stats = t.obj("statistics")
    return ArrTitle(
        id = t.optInt("id"), service = service, title = t.str("title"), year = t.optInt("year"),
        image = t.optJSONArray("images").objects().firstOrNull { it.str("coverType") == "poster" }?.str("remoteUrl").orEmpty(),
        monitored = t.optBoolean("monitored"), profileId = t.optInt("qualityProfileId"), path = t.str("path"),
        size = if (t.has("sizeOnDisk") && !t.isNull("sizeOnDisk")) t.optLong("sizeOnDisk") else stats?.optLong("sizeOnDisk") ?: 0,
        status = t.str("status"), network = t.str(if (sonarr) "network" else "studio"), overview = t.str("overview"),
        files = if (sonarr) stats?.optInt("episodeFileCount") ?: 0 else if (t.optBoolean("hasFile")) 1 else 0,
        total = if (sonarr) stats?.optInt("episodeCount") ?: 0 else 1,
        quality = t.obj("movieFile")?.obj("quality")?.obj("quality")?.str("name").orEmpty(),
        seasons = t.list("seasons").map { s ->
            ArrSeason(s.optInt("seasonNumber"), s.optBoolean("monitored"), s.obj("statistics")?.optInt("episodeFileCount") ?: 0, s.obj("statistics")?.optInt("totalEpisodeCount") ?: 0)
        }.sortedBy { if (it.number == 0) 1000 else it.number },
        raw = t,
    )
}

private fun mapEpisode(e: JSONObject) = ArrEpisode(
    e.optInt("id"), e.optInt("seasonNumber"), e.optInt("episodeNumber"), code(e.optInt("seasonNumber"), e.optInt("episodeNumber")),
    e.strOrNull("title") ?: "TBA", e.strOrNull("airDateUtc"), e.optBoolean("hasFile"), e.optBoolean("monitored"),
)

private fun mapRelease(r: JSONObject) = ArrRelease(
    r.str("guid"), r.optInt("indexerId"), r.str("title"), r.str("indexer"), r.optLong("size"),
    if (r.has("ageHours") && !r.isNull("ageHours")) r.optDouble("ageHours") else r.optDouble("age", 0.0) * 24,
    r.intOrNull("seeders"), r.intOrNull("leechers"), r.obj("quality")?.obj("quality")?.str("name").orEmpty(), r.str("protocol"),
    r.optJSONArray("rejections").let { list -> if (list == null) emptyList() else (0 until list.length()).map { i -> list.optJSONObject(i)?.str("reason") ?: list.optString(i) } },
    r.optBoolean("approved"),
)

private class Arr(val service: String) {
    val sonarr = service == "sonarr"
    val kind = if (sonarr) "series" else "movie"
    private suspend fun command(name: String, args: Map<String, Any?> = emptyMap()) = Api.call(service, "command", "POST", mapOf("name" to name) + args)

    suspend fun titles() = Api.arr(service, kind).objects().map { mapTitle(service, it) }
    suspend fun title(id: Int) = coroutineScope {
        val t = async { Api.obj(service, "$kind/$id") }
        val episodes = async { if (sonarr) Api.arr(service, "episode?seriesId=$id") else JSONArray() }
        mapTitle(service, t.await()).copy(episodes = episodes.await().objects().map(::mapEpisode).sortedWith(compareBy({ it.season }, { it.number })))
    }
    suspend fun profiles() = Api.arr(service, "qualityprofile").objects().map { it.optInt("id") to it.str("name") }
    suspend fun releases(target: Map<String, Any>) =
        Api.arr(service, "release?" + target.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value.toString(), "UTF-8")}" }).objects().map(::mapRelease).sortedByDescending { it.approved }
    suspend fun grab(release: ArrRelease) = Api.call(service, "release", "POST", mapOf("guid" to release.guid, "indexerId" to release.indexerId))
    suspend fun search(target: Map<String, Any>) = when {
        "episodeIds" in target -> command("EpisodeSearch", target)
        "seasonNumber" in target -> command("SeasonSearch", target)
        sonarr -> command("SeriesSearch", target)
        else -> command("MoviesSearch", target)
    }
    suspend fun refresh(id: Int) = if (sonarr) command("RefreshSeries", mapOf("seriesId" to id)) else command("RefreshMovie", mapOf("movieIds" to listOf(id)))
    suspend fun monitorEpisodes(ids: List<Int>, monitored: Boolean) = Api.call(service, "episode/monitor", "PUT", mapOf("episodeIds" to ids, "monitored" to monitored))
    suspend fun monitorSeason(title: ArrTitle, number: Int, monitored: Boolean) = Api.call(service, "series/${title.id}", "PUT", title.raw.copy().apply {
        optJSONArray("seasons").objects().filter { it.optInt("seasonNumber") == number }.forEach { it.put("monitored", monitored) }
    })
    suspend fun save(title: ArrTitle, monitored: Boolean, profileId: Int, path: String) = Api.call(service, "$kind/${title.id}?moveFiles=${path != title.path}", "PUT",
        title.raw.copy().put("monitored", monitored).put("qualityProfileId", profileId).put("path", path))
    suspend fun remove(id: Int, deleteFiles: Boolean) = Api.call(service, "$kind/$id?deleteFiles=$deleteFiles", "DELETE")
}

/** Quality profiles per service (web data.profiles), shared by the grid labels and the edit form. */
private object Profiles {
    val byService = mutableStateMapOf<String, List<Pair<Int, String>>>()
    fun name(t: ArrTitle) = byService[t.service]?.firstOrNull { it.first == t.profileId }?.second.orEmpty()
    suspend fun ensure(service: String) { if (service !in byService) byService[service] = Arr(service).profiles() }
}

// ---------- Series & movies page ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableIntStateOf(0) } // All / Movies / TV shows
    var open by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    val services = app.arrs
    val fetch: suspend () -> List<ArrTitle> = {
        coroutineScope {
            val results = services.map { service ->
                async {
                    runCatching { Profiles.byService[service] = Arr(service).profiles() }
                    runCatching { Arr(service).titles() }
                }
            }.map { it.await() }
            results.mapNotNull { it.exceptionOrNull() }.firstOrNull()?.let { if (results.all { r -> r.isFailure }) throw it else Toasts.fail(it) }
            results.flatMap { it.getOrDefault(emptyList()) }.sortedBy { it.title.lowercase() }
        }
    }
    val titles = load(services) { fetch() }
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; runCatching { titles.value = fetch(); titles.error = null }.onFailure(Toasts::fail); refreshing = false } }, Modifier.fillMaxSize()) {
        val items = titles.value?.filter { (type == 0 || (type == 2) == it.sonarr) && it.title.contains(app.query.trim(), ignoreCase = true) }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(it.maxLineSpan) }
            item(span = full) {
                Column {
                    PageHeading("Series & movies", "Everything Sonarr and Radarr are watching for.") {
                        if (Screen.Wanted.needs(app)) UButton("Wanted", { app.go(Screen.Wanted) }, icon = Icons.Rounded.Search, kind = ButtonKind.Ghost)
                    }
                    OfflineBanner(app, services)
                    Segmented(listOf("All", "Movies", "TV shows"), type, { type = it })
                    items?.let { Text("${it.size} titles · ${it.count { t -> t.monitored }} monitored", style = Type.small, color = C.mute, modifier = Modifier.padding(top = 10.dp)) }
                }
            }
            when {
                items == null -> item(span = full) { Show(titles, "Loading titles…") {} }
                items.isEmpty() -> item(span = full) { Empty(if (app.query.isBlank()) "Nothing here yet. Titles you add in Sonarr or Radarr show up here." else "No titles match “${app.query.trim()}”.", icon = Icons.Rounded.Movie) }
                else -> items(items, key = { "${it.service}-${it.id}" }) { TitleCard(it) { open = it.service to it.id } }
            }
        }
    }
    open?.let { (service, id) -> TitleSheet(service, id, onDismiss = { open = null }, onChanged = { scope.launch { runCatching { titles.value = fetch() } } }) }
}

@Composable
private fun OfflineBanner(app: AppState, services: List<String>) {
    val off = services.filter { !app.isLive(it) }
    if (off.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundSmall).background(C.warn.copy(alpha = .1f)).border(1.dp, C.warn.copy(alpha = .35f), RoundSmall)
            .clickable { app.go(Screen.Services) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = C.warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Can’t reach ${off.joinToString(" and ") { serviceNames[it] ?: it }}. Check that it’s running, or update its address in Services.", style = Type.small, color = C.ink)
    }
}

@Composable
private fun TitleCard(t: ArrTitle, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).alpha(if (t.monitored) 1f else .55f)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Poster(t.image, Modifier.matchParentSize(), fallback = if (t.sonarr) Icons.Rounded.Tv else Icons.Rounded.Movie)
            Profiles.name(t).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = Type.tiny, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
            val missing = t.files < t.total
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (missing) C.warn else C.ok))
                Spacer(Modifier.width(5.dp))
                Text(t.summary, style = Type.tiny, color = Color.White, maxLines = 1)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(t.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${t.year} · ${if (t.sonarr) "TV Series" else "Movie"}", style = Type.tiny, color = C.mute, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(if (t.monitored) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (t.monitored) "Monitored" else "Unmonitored", tint = if (t.monitored) C.accent else C.mute, modifier = Modifier.size(16.dp))
        }
    }
}

// ---------- Title sheet (web showTitle / titleModal) ----------

@Composable
fun ArrTitleSheet(service: String, id: Int, onDismiss: () -> Unit) = TitleSheet(service, id, onDismiss) {}

private data class ReleaseTarget(val target: Map<String, Any>, val label: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleSheet(service: String, id: Int, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val arr = remember(service) { Arr(service) }
    val state = load(service, id) { coroutineScope { val p = async { Profiles.ensure(service) }; arr.title(id).also { runCatching { p.await() } } } }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var releases by remember { mutableStateOf<ReleaseTarget?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateListOf<Int>() }
    // Monitor toggles flip immediately and stay flipped until the re-fetch confirms them.
    val pending = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun refetch() { state.value = arr.title(id); pending.clear(); onChanged() }
    fun run(key: String, success: String, refetch: Boolean = true, work: suspend () -> Unit) {
        busy = key
        scope.launch {
            try { work(); Toasts.show(success); if (refetch) runCatching { refetch() } } catch (e: Exception) { pending.remove(key); Toasts.fail(e) }
            if (busy == key) busy = null
        }
    }

    Sheet(onDismiss) {
        val t = state.value
        if (t == null) { Show(state, "Loading from ${serviceNames[service]}") {}; return@Sheet }
        TitleHead(t)
        if (editing) { EditForm(t, busy == "save", onCancel = { editing = false }) { monitored, profile, path ->
            run("save", "Saved ${t.title}") { arr.save(t, monitored, profile, path); editing = false }
        }; return@Sheet }

        if (t.overview.isNotBlank()) { Text(t.overview, style = Type.body, color = C.mute); Spacer(Modifier.height(14.dp)) }
        Fact("Path", t.path.ifBlank { "—" })
        Fact("Size on disk", bytes(t.size))
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UButton(if (t.sonarr) "Search monitored" else "Search movie", {
                run("search", "Searching for ${t.title}", refetch = false) { arr.search(if (t.sonarr) mapOf("seriesId" to t.id) else mapOf("movieIds" to listOf(t.id))) }
            }, icon = Icons.Rounded.Search, busy = busy == "search")
            if (!t.sonarr) UButton("Interactive search", { releases = ReleaseTarget(mapOf("movieId" to t.id), t.title) }, icon = Icons.AutoMirrored.Rounded.ManageSearch, kind = ButtonKind.Ghost)
            UButton("Refresh & scan", { run("refresh", "Refreshing ${t.title}") { arr.refresh(t.id) } }, icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost, busy = busy == "refresh")
            UButton("Edit", { editing = true }, icon = Icons.Rounded.Edit, kind = ButtonKind.Ghost)
            UButton("Delete", { deleting = true }, icon = Icons.Rounded.Delete, kind = ButtonKind.Danger, busy = busy == "delete")
        }

        if (t.sonarr) {
            SectionTitle("Seasons")
            val now = Instant.now()
            t.seasons.forEach { season ->
                val name = if (season.number == 0) "Specials" else "Season ${season.number}"
                val key = "season-${season.number}"
                val episodes = t.episodes.filter { it.season == season.number }
                val open = season.number in expanded
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundSmall).border(1.dp, C.line, RoundSmall)) {
                    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonitorToggle(pending[key] ?: season.monitored, name) { on ->
                            pending[key] = on
                            run(key, "${if (on) "Monitoring" else "Stopped monitoring"} ${if (season.number == 0) "specials" else "season ${season.number}"}") { arr.monitorSeason(t, season.number, on) }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = Type.subtitle, modifier = Modifier.weight(1f))
                                Text("${season.files} / ${season.total}", style = Type.small, color = C.mute)
                            }
                            Spacer(Modifier.height(5.dp))
                            ProgressLine(if (season.total > 0) season.files.toFloat() / season.total else 0f, color = C.sonarr, height = 4.dp)
                        }
                        IconAction(Icons.Rounded.Search, "Search $name", {
                            run("search-$key", "Searching for ${t.title} $name", refetch = false) { arr.search(mapOf("seriesId" to t.id, "seasonNumber" to season.number)) }
                        }, enabled = busy != "search-$key")
                        IconAction(Icons.AutoMirrored.Rounded.ManageSearch, "Interactive search for $name", { releases = ReleaseTarget(mapOf("seriesId" to t.id, "seasonNumber" to season.number), "${t.title} · $name") })
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { if (open) expanded.remove(season.number) else expanded.add(season.number) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${episodes.size} episodes", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.mute, modifier = Modifier.weight(1f))
                        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = C.mute)
                    }
                    if (open) episodes.forEach { e ->
                        HorizontalDivider(color = C.line)
                        val ekey = "episode-${e.id}"
                        val aired = instant(e.airDate)?.isBefore(now) == true
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            MonitorToggle(pending[ekey] ?: e.monitored, e.code) { on ->
                                pending[ekey] = on
                                run(ekey, "${if (on) "Monitoring" else "Stopped monitoring"} ${e.code}") { arr.monitorEpisodes(listOf(e.id), on) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text("${e.number} · ${e.title}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (e.airDate != null) day(e.airDate) else "TBA", style = Type.tiny, color = C.mute)
                                    Spacer(Modifier.width(8.dp))
                                    when {
                                        e.hasFile -> Pill("Downloaded", C.ok)
                                        aired -> Pill("Missing", C.danger)
                                        else -> Pill("Unaired")
                                    }
                                }
                            }
                            IconAction(Icons.Rounded.Search, "Search ${e.code}", {
                                run("search-$ekey", "Searching for ${t.title} ${e.code}", refetch = false) { arr.search(mapOf("episodeIds" to listOf(e.id))) }
                            }, enabled = busy != "search-$ekey")
                            IconAction(Icons.AutoMirrored.Rounded.ManageSearch, "Interactive search for ${e.code}", { releases = ReleaseTarget(mapOf("episodeId" to e.id), "${t.title} · ${e.code}") })
                        }
                    }
                }
            }
        }

        if (deleting) {
            var files by remember { mutableStateOf(false) }
            Confirm("Delete ${t.title}?", "It will be removed from ${serviceNames[service]} and no longer monitored.", "Delete", onDismiss = { deleting = false }, onConfirm = {
                val withFiles = files
                busy = "delete"
                scope.launch {
                    try { arr.remove(t.id, withFiles); onChanged(); onDismiss(); Toasts.show("Deleted ${t.title}${if (withFiles) " and its files" else ""}") }
                    catch (e: Exception) { Toasts.fail(e) }
                    busy = null
                }
            }) {
                Spacer(Modifier.height(12.dp))
                Toggle("Also delete ${if (t.sonarr) "the series folder" else "the movie folder"} and its files", files, { files = it })
            }
        }
    }
    releases?.let { ReleasesSheet(service, it, onDismiss = { releases = null }) }
}

@Composable
private fun TitleHead(t: ArrTitle) {
    Row(Modifier.padding(top = 4.dp, bottom = 16.dp)) {
        Poster(t.image, Modifier.size(96.dp, 144.dp), fallback = if (t.sonarr) Icons.Rounded.Tv else Icons.Rounded.Movie)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${serviceNames[t.service]?.uppercase()} · ${t.status.uppercase()}", style = Type.eyebrow, color = C.service(t.service))
            Spacer(Modifier.height(4.dp))
            Text(t.title, style = Type.title)
            Spacer(Modifier.height(4.dp))
            Text(listOf(if (t.year > 0) "${t.year}" else "", t.network, Profiles.name(t), bytes(t.size)).filter { it.isNotBlank() }.joinToString(" · "), style = Type.small, color = C.mute)
            Spacer(Modifier.height(8.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (t.monitored) "Monitored" else "Unmonitored", if (t.monitored) C.ok else C.mute)
                Pill(t.summary, if (t.files < t.total) C.danger else C.ok)
                if (t.quality.isNotEmpty() && t.files > 0) Pill(t.quality)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = Type.small, color = C.mute, modifier = Modifier.width(100.dp))
        Text(value, style = Type.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MonitorToggle(on: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    IconAction(if (on) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "${if (on) "Unmonitor" else "Monitor"} $label", { onToggle(!on) }, tint = if (on) C.accent else C.mute)
}

@Composable
private fun EditForm(t: ArrTitle, busy: Boolean, onCancel: () -> Unit, onSave: (Boolean, Int, String) -> Unit) {
    var monitored by remember { mutableStateOf(t.monitored) }
    var profile by remember { mutableIntStateOf(t.profileId) }
    var path by remember { mutableStateOf(t.path) }
    Toggle("Monitored", monitored, { monitored = it })
    Spacer(Modifier.height(10.dp))
    val profiles = Profiles.byService[t.service].orEmpty().ifEmpty { listOf(t.profileId to "Profile ${t.profileId}") }
    Select("Quality profile", profiles, profile, { profile = it })
    Spacer(Modifier.height(14.dp))
    Field("Path", path, { path = it }, note = "Changing the path moves existing files to the new folder.")
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UButton("Save changes", {
            if (path.isBlank()) Toasts.show("Enter a path.", true) else onSave(monitored, profile, path.trim())
        }, Modifier.weight(1f), icon = Icons.Rounded.Check, busy = busy)
        UButton("Cancel", onCancel, Modifier.weight(1f), kind = ButtonKind.Ghost)
    }
}

// ---------- Interactive search (web showReleases / releasesModal) ----------

@Composable
private fun ReleasesSheet(service: String, search: ReleaseTarget, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val arr = remember(service) { Arr(service) }
    val state = load(service, search) { arr.releases(search.target) }
    val grabbed = remember { mutableStateListOf<String>() }
    val grabbing = remember { mutableStateListOf<String>() }
    val name = serviceNames[service] ?: service
    Sheet(onDismiss, scroll = false) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Row(Modifier.padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundSmall).background(C.service(service).copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ManageSearch, null, tint = C.service(service))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${name.uppercase()} · INTERACTIVE SEARCH", style = Type.eyebrow, color = C.mute)
                        Text(search.label, style = Type.title)
                        val list = state.value
                        Text(when {
                            list != null -> "${list.size} releases · ${list.count { it.approved }} match your profile"
                            state.error != null -> ""
                            else -> "Searching your indexers… this can take a minute."
                        }, style = Type.small, color = C.mute)
                    }
                }
            }
            val list = state.value
            when {
                list == null && state.error != null -> item { Empty(state.error!!) }
                list == null -> item { Loader("Searching indexers") }
                list.isEmpty() -> item { Empty("No releases found. Check that your indexers are set up in $name.") }
                else -> itemsIndexed(list, key = { i, r -> "$i-${r.guid}" }) { _, r ->
                    val sent = r.guid in grabbed
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundSmall).border(1.dp, C.line, RoundSmall).padding(12.dp)) {
                        Text(r.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (r.approved) C.ink else C.mute, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (r.rejections.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(r.rejections.joinToString(" · "), style = Type.tiny, color = C.danger) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(listOf(r.indexer, bytes(r.size), if (r.age < 48) "${Math.round(r.age)} h" else "${Math.round(r.age / 24)} d",
                                    if (r.protocol == "torrent") "${r.seeders ?: 0} / ${r.leechers ?: 0} peers" else "Usenet").filter { it.isNotBlank() }.joinToString(" · "),
                                    style = Type.tiny, color = C.mute)
                                if (r.quality.isNotEmpty()) { Spacer(Modifier.height(5.dp)); Pill(r.quality, C.service(service)) }
                            }
                            Spacer(Modifier.width(10.dp))
                            UButton(if (sent) "Sent" else "Grab", {
                                grabbing.add(r.guid)
                                scope.launch {
                                    try { arr.grab(r); grabbed.add(r.guid); Toasts.show("Sent to your download client") } catch (e: Exception) { Toasts.fail(e) }
                                    grabbing.remove(r.guid)
                                }
                            }, icon = if (sent) Icons.Rounded.Check else Icons.Rounded.Download, kind = if (r.approved) ButtonKind.Primary else ButtonKind.Ghost,
                                enabled = !sent, busy = r.guid in grabbing)
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UButton("Back to title", onDismiss, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.ArrowBack, kind = ButtonKind.Ghost)
                }
            }
        }
    }
}
