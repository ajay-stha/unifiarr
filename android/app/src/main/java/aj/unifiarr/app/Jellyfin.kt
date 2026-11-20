package aj.unifiarr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

// ---------- Jellyfin client (live.mjs jellyfinClient + mapItem) ----------

private data class JfLibrary(val id: String, val name: String, val collection: String, val paths: List<String>)
private data class JfItem(val id: String, val title: String, val year: String, val series: Boolean, val genre: String, val rating: String, val image: String?, val quality: String, val library: String)
private data class JfCatalog(val libraries: List<JfLibrary>, val items: List<JfItem>)

private val kinds = mapOf("movies" to "Movies", "tvshows" to "Shows", "mixed" to "Movies & shows")
private fun jfImage(id: String, tag: String?) = if (tag.isNullOrBlank()) null else "api/jellyfin/Items/$id/Images/Primary?maxHeight=480&tag=$tag"
private fun quality(width: Int) = when { width >= 3200 -> "4K"; width >= 1800 -> "1080p"; width >= 1200 -> "720p"; width > 0 -> "SD"; else -> "" }
private fun rating(i: JSONObject) = i.optDouble("CommunityRating", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

private fun mapItem(i: JSONObject, library: String) = JfItem(
    i.str("Id"), i.str("Name"), i.str("ProductionYear"), i.str("Type") == "Series", i.optJSONArray("Genres").strings().firstOrNull().orEmpty(),
    rating(i), jfImage(i.str("Id"), i.obj("ImageTags")?.strOrNull("Primary")), quality(i.optInt("Width")), library,
)

private suspend fun jfLibraries() = Api.arr("jellyfin", "Library/VirtualFolders").objects()
    .map { JfLibrary(it.str("ItemId"), it.str("Name"), it.strOrNull("CollectionType") ?: "mixed", it.optJSONArray("Locations").strings()) }
    .filter { it.collection in kinds }

// ponytail: newest 300 titles per library (same as the web); add paging for bigger libraries.
private suspend fun jfCatalog(): JfCatalog = coroutineScope {
    val folders = jfLibraries()
    val items = folders.map { folder ->
        async { Api.obj("jellyfin", "Items?ParentId=${folder.id}&Recursive=true&IncludeItemTypes=Movie,Series&Fields=Genres,Width&SortBy=DateCreated&SortOrder=Descending&Limit=300").list("Items").map { mapItem(it, folder.id) } }
    }.awaitAll().flatten()
    JfCatalog(folders, items)
}

private suspend fun jfScan(library: String?) = Api.call("jellyfin", if (library != null) "Items/$library/Refresh?Recursive=true&MetadataRefreshMode=Default&ImageRefreshMode=Default" else "Library/Refresh", "POST")

/** Survives leaving the page, so coming back shows the library instantly while it reloads. */
private var catalogCache by mutableStateOf<JfCatalog?>(null)

// ---------- Shared bits ----------


/** The web's banner(['jellyfin']): offline warning always, "Connected to …" only when switched on in Services. */
@Composable
private fun JellyfinBanner(app: AppState) {
    if (!app.has("jellyfin")) return
    val context = LocalContext.current
    val live = app.isLive("jellyfin")
    if (!live) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundSmall).background(C.warn.copy(alpha = .10f)).border(1.dp, C.warn.copy(alpha = .4f), RoundSmall).padding(14.dp)) {
            Text("Can’t reach Jellyfin.", style = Type.small.copy(fontWeight = FontWeight.Bold))
            Text("Check that it’s running, or update its address in Services.", style = Type.small)
            Text("Open Services →", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.accent, modifier = Modifier.padding(top = 6.dp).clickable { app.go(Screen.Services) })
        }
    } else if (prefs(context).getBoolean("banners", false)) {
        val version = load(Unit) { Api.obj("jellyfin").str("version") }
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundSmall).background(C.ok.copy(alpha = .09f)).border(1.dp, C.ok.copy(alpha = .3f), RoundSmall).padding(14.dp)) {
            Text("Connected to Jellyfin ${version.value.orEmpty()}.".replace(" .", "."), style = Type.small.copy(fontWeight = FontWeight.Bold))
            Text("Changes here apply to your real server.", style = Type.small)
        }
    }
}

private fun initials(name: String) = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()

@Composable
private fun Avatar(text: String? = null, icon: ImageVector? = null, color: Color = C.jelly) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = if (C.dark) .22f else .13f)), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        else Text(text.orEmpty(), style = Type.subtitle, color = color)
    }
}

// ---------- Library ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val state = load(app.refresh) { jfCatalog().also { catalogCache = it } }
    val catalog = state.value ?: catalogCache
    var library by remember { mutableStateOf("all") }
    var type by remember { mutableIntStateOf(0) } // 0 All, 1 Movies, 2 TV shows
    var open by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val libraries = catalog?.libraries.orEmpty()
    val current = libraries.firstOrNull { it.id == library }
    val q = app.query.trim().lowercase()
    val items = catalog?.items?.filter { (library == "all" || it.library == library) && (type == 0 || it.series == (type == 2)) && it.title.lowercase().contains(q) }

    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; runCatching { jfCatalog().also { catalogCache = it; state.value = it } }.onFailure { Toasts.fail(it) }; refreshing = false } }, Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
                Column {
                    PageHeading(current?.name ?: "Library", current?.let { "Jellyfin ${kinds[it.collection]!!.lowercase()} library." } ?: "Every Jellyfin library, all in one place.") {
                        UButton(if (current != null) "Scan library" else "Scan all libraries", {
                            scope.act("${current?.name ?: "All libraries"} scan started") { jfScan(current?.id) }
                        }, icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost)
                    }
                    JellyfinBanner(app)
                    if (catalog != null) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                            items(listOf(JfLibrary("all", "All libraries", "", emptyList())) + libraries, key = { it.id }) { entry ->
                                val on = entry.id == library
                                val count = catalog.items.count { entry.id == "all" || it.library == entry.id }
                                Column(
                                    Modifier.widthIn(min = 128.dp).clip(RoundSmall).background(if (on) C.accentSoft else C.panel).border(1.dp, if (on) C.accent else C.line, RoundSmall)
                                        .clickable { library = entry.id; type = 0 }.padding(horizontal = 14.dp, vertical = 11.dp),
                                ) {
                                    Text(entry.name, style = Type.subtitle.copy(fontSize = 13.sp), color = if (on) C.accent else C.ink, maxLines = 1)
                                    Text("${if (entry.collection.isNotEmpty()) kinds[entry.collection] else "${libraries.size} libraries"} · $count", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (current == null || current.collection == "mixed") Segmented(listOf("All", "Movies", "TV shows"), type, { type = it })
                        Spacer(Modifier.weight(1f))
                        if (items != null) Text("${items.size} titles${if (q.isNotEmpty()) " matching “${app.query.trim()}”" else ""}", style = Type.small, color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            when {
                items == null -> item(span = { GridItemSpan(maxLineSpan) }, key = "state") { if (state.error != null) ErrorBox(state.error!!, { state.reload() }) else Loader("Loading titles…") }
                items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { Empty("No titles found. Try another search or media type.", icon = Icons.Rounded.VideoLibrary) }
                else -> items(items, key = { it.id }) { item -> PosterCard(item) { open = item.id } }
            }
        }
    }
    open?.let { JellyfinItemSheet(it) { open = null } }
}

@Composable
private fun PosterCard(item: JfItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Poster(item.image, Modifier.matchParentSize(), fallback = Icons.Rounded.Movie)
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xB0101623))))
            if (item.quality.isNotEmpty()) Text(
                item.quality, style = Type.tiny.copy(fontSize = 9.sp), color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCF101623)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Row(Modifier.align(Alignment.BottomStart).padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF77D8AF)))
                Spacer(Modifier.width(4.dp))
                Text("In your library", style = Type.tiny.copy(fontSize = 9.sp), color = Color.White)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, style = Type.subtitle.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${item.year} · ${if (item.series) "TV Series" else "Movie"}", style = Type.small.copy(fontSize = 11.sp), color = C.mute, maxLines = 1, modifier = Modifier.weight(1f))
            Text("★ ${item.rating}", style = Type.small.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = C.warn)
        }
    }
}

// ---------- Details (web showDetails) ----------

private data class JfDetails(val item: JfItem, val type: String, val genres: List<String>, val studios: List<String>, val overview: String, val tagline: String, val runtime: Long, val rated: String, val library: String?, val cast: List<Triple<String, String, String?>>, val version: String, val url: String?)

@Composable
fun JellyfinItemSheet(id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = load(id) {
        coroutineScope {
            val folders = async { runCatching { jfLibraries() }.getOrDefault(emptyList()) }
            val version = async { runCatching { Api.obj("jellyfin").str("version") }.getOrDefault("") }
            val url = async { runCatching { Api.obj("config").obj("jellyfin")?.str("url")?.trimEnd('/') }.getOrNull() }
            val i = Api.obj("jellyfin", "Items?Ids=$id&Fields=People,Overview,Studios,Taglines,Genres,Path,Width").list("Items").firstOrNull()
                ?: throw Exception("This title is no longer in Jellyfin.")
            val path = i.str("Path")
            val library = folders.await().firstOrNull { folder -> folder.paths.any { it.isNotEmpty() && path.startsWith(it) } }
            JfDetails(
                mapItem(i, library?.id.orEmpty()), if (i.str("Type") == "Series") "Series" else "Movie", i.optJSONArray("Genres").strings(), i.list("Studios").map { it.str("Name") },
                i.str("Overview"), i.optJSONArray("Taglines").strings().firstOrNull().orEmpty(), Math.round(i.optLong("RunTimeTicks") / 6e8), i.str("OfficialRating"), library?.name,
                i.list("People").filter { it.str("Type") == "Actor" }.take(12).map { Triple(it.str("Name"), it.str("Role"), jfImage(it.str("Id"), it.strOrNull("PrimaryImageTag"))) },
                version.await(), url.await(),
            )
        }
    }
    val info = state.value
    Sheet(onDismiss) {
        when {
            info == null && state.error != null -> ErrorBox(state.error!!, { state.reload() })
            info == null -> Loader("Loading details from Jellyfin…")
            else -> {
                val item = info.item
                Box(Modifier.fillMaxWidth().height(210.dp).clip(Round)) {
                    Poster(item.image, Modifier.matchParentSize().blur(18.dp).alpha(.55f), shape = Round)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to C.panel)))
                    Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.Bottom) {
                        Poster(item.image, Modifier.width(104.dp).aspectRatio(2f / 3f), fallback = Icons.Rounded.Movie)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = Type.title)
                            Spacer(Modifier.height(4.dp))
                            Text(listOf(item.year, info.type, item.quality).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.small, color = C.mute)
                            Text(listOf(item.genre, "★ ${item.rating}").filter { it.isNotEmpty() }.joinToString(" · "), style = Type.small, color = C.mute)
                            Text("Jellyfin library · ${info.library ?: "—"}", style = Type.small, color = C.mute)
                            Spacer(Modifier.height(8.dp))
                            Pill("Ready to watch", C.ok, dot = true)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (info.tagline.isNotEmpty()) { Text(info.tagline, style = Type.body.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)); Spacer(Modifier.height(8.dp)) }
                if (info.overview.isNotEmpty()) { Text(info.overview, style = Type.body, color = C.mute); Spacer(Modifier.height(10.dp)) }
                val facts = listOf(if (info.runtime > 0) "${info.runtime} min" else "", info.rated, info.genres.joinToString(", "), info.studios.take(3).joinToString(", ")).filter { it.isNotEmpty() }
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                if (info.cast.isNotEmpty()) {
                    SectionTitle("Cast")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(info.cast) { (name, role, image) ->
                            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (image != null) Poster(image, Modifier.size(56.dp), shape = CircleShape)
                                else Box(Modifier.size(56.dp).clip(CircleShape).background(C.soft), contentAlignment = Alignment.Center) { Text(initials(name), style = Type.subtitle, color = C.mute) }
                                Spacer(Modifier.height(6.dp))
                                Text(name, style = Type.tiny, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Text(role, style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UButton("Watch in Jellyfin", {
                        val base = info.url
                        if (base.isNullOrBlank()) return@UButton Toasts.show("Jellyfin isn’t set up.", true)
                        // Official Jellyfin apps have no item deep link, so open the app itself; the web item page is the fallback.
                        val jellyfinApp = listOf("org.jellyfin.mobile", "org.jellyfin.androidtv").firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
                        runCatching { context.startActivity(jellyfinApp ?: Intent(Intent.ACTION_VIEW, Uri.parse("$base/web/#/details?id=${Uri.encode(id)}"))) }.onFailure { Toasts.fail(it) }
                    }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                    UButton("Refresh metadata", {
                        scope.act("Refreshing metadata from Jellyfin") { Api.call("jellyfin", "Items/$id/Refresh?Recursive=true&MetadataRefreshMode=FullRefresh&ImageRefreshMode=FullRefresh&ReplaceAllMetadata=false&ReplaceAllImages=false", "POST"); state.reload() }
                    }, Modifier.weight(1f), icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost)
                }
                Spacer(Modifier.height(10.dp))
                Text("Live from Jellyfin ${info.version}.".replace(" .", "."), style = Type.small, color = C.mute)
            }
        }
    }
}

// ---------- Server (web renderServer) ----------

private data class JfUser(val id: String, val name: String, val admin: Boolean, val disabled: Boolean, val lastActive: String?, val policy: JSONObject)
private data class JfDevice(val id: String, val user: String, val device: String, val client: String, val lastActive: String?, val playing: String?)
private data class JfTask(val id: String, val name: String, val category: String, val state: String, val progress: Double, val status: String?, val end: String?)
private data class JfLog(val id: String, val name: String, val detail: String, val severity: String, val date: String?)

private suspend fun jfUsers() = Api.arr("jellyfin", "Users").objects().map {
    val policy = it.obj("Policy") ?: JSONObject()
    JfUser(it.str("Id"), it.str("Name"), policy.optBoolean("IsAdministrator"), policy.optBoolean("IsDisabled"), it.strOrNull("LastActivityDate"), policy)
}
private suspend fun jfDevices() = Api.arr("jellyfin", "Sessions?ActiveWithinSeconds=960").objects().map { s ->
    JfDevice(s.str("Id"), s.strOrNull("UserName") ?: "Unknown", s.str("DeviceName"), s.str("Client"), s.strOrNull("LastActivityDate"), s.obj("NowPlayingItem")?.let { it.strOrNull("SeriesName") ?: it.str("Name") })
}
private suspend fun jfTasks() = Api.arr("jellyfin", "ScheduledTasks?isHidden=false").objects().map { t ->
    val result = t.obj("LastExecutionResult")
    JfTask(t.str("Id"), t.str("Name"), t.str("Category"), t.str("State"), t.optDouble("CurrentProgressPercentage", 0.0).takeUnless { it.isNaN() } ?: 0.0, result?.str("Status"), result?.strOrNull("EndTimeUtc"))
}.sortedWith(compareBy({ it.category }, { it.name }))
private suspend fun jfLog() = Api.obj("jellyfin", "System/ActivityLog/Entries?limit=40").list("Items").map { JfLog(it.str("Id"), it.str("Name"), it.str("ShortOverview"), it.str("Severity"), it.strOrNull("Date")) }

private fun since(iso: String?) = ago(iso).lowercase()

@Composable
fun ServerScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val users = load(app.refresh) { jfUsers() }
    val devices = load(app.refresh) { jfDevices() }
    val tasks = load(app.refresh) { jfTasks() }
    val log = load(app.refresh) { jfLog() }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JfUser?>(null) }
    var messaging by remember { mutableStateOf<JfDevice?>(null) }
    val running = tasks.value?.any { it.state != "Idle" } == true
    val dialogOpen = adding || deleting != null || messaging != null
    // Like the web: poll while nothing is open; faster while a task runs so its progress moves.
    Every(if (running) 2000 else 5000, running, dialogOpen) {
        if (!dialogOpen) { users.reload(); devices.reload(); tasks.reload(); log.reload() }
    }
    fun reloadAll() { users.reload(); devices.reload(); tasks.reload(); log.reload() }
    fun act(success: String, block: suspend () -> Unit) = scope.act(success) { block(); reloadAll() }

    Page(onRefresh = { coroutineScope { listOf(async { users.value = jfUsers() }, async { devices.value = jfDevices() }, async { tasks.value = jfTasks() }, async { log.value = jfLog() }).awaitAll() } }) {
        PageHeading("Jellyfin server", "Users, devices, scheduled tasks and what happened on your server.") {
            UButton("Add user", { adding = true }, icon = Icons.Rounded.PersonAdd)
            UButton("Scan all libraries", { act("All libraries scan started") { jfScan(null) } }, icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost)
        }
        JellyfinBanner(app)

        ServerPanel("Users", Icons.Rounded.Group, users.value?.size) {
            Show(users, "Loading users…") { list ->
                list.forEach { user ->
                    Row(Modifier.fillMaxWidth().alpha(if (user.disabled) .55f else 1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(user.name.take(1).uppercase())
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.name, style = Type.body.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (user.admin) "Administrator · " else ""}${if (user.disabled) "Disabled" else "Active ${since(user.lastActive)}"}", style = Type.small, color = C.mute)
                        }
                        if (!user.admin) {
                            UButton(if (user.disabled) "Enable" else "Disable", {
                                act("${user.name} ${if (user.disabled) "enabled" else "disabled"}") {
                                    Api.call("jellyfin", "Users/${user.id}/Policy", "POST", user.policy.copy().put("IsDisabled", !user.disabled))
                                }
                            }, icon = if (user.disabled) Icons.Rounded.Check else Icons.Rounded.Block, kind = ButtonKind.Ghost)
                            IconAction(Icons.Rounded.DeleteOutline, "Delete ${user.name}", { deleting = user }, tint = C.danger)
                        }
                    }
                }
            }
        }

        ServerPanel("Devices", Icons.Rounded.Devices, devices.value?.size) {
            Show(devices, "Loading devices…") { list ->
                if (list.isEmpty()) Empty("No devices active in the last 15 minutes.")
                list.forEach { device ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(icon = Icons.Rounded.Monitor)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.device, style = Type.body.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${device.client} · ${device.user} · ${since(device.lastActive)}${device.playing?.let { " · ▶ $it" } ?: ""}", style = Type.small, color = C.mute)
                        }
                        UButton("Message", { messaging = device }, icon = Icons.Rounded.ChatBubbleOutline, kind = ButtonKind.Ghost)
                    }
                }
            }
        }

        ServerPanel("Scheduled tasks", Icons.Rounded.History, null) {
            Show(tasks, "Loading tasks…") { list ->
                list.forEach { task ->
                    val active = task.state == "Running"
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(task.name, style = Type.body.copy(fontWeight = FontWeight.Bold))
                            val detail = when {
                                active -> "Running · ${Math.round(task.progress)}%"
                                task.status != null -> "${if (task.status == "Completed") "Last ran" else task.status} ${since(task.end)}"
                                else -> "Never run"
                            }
                            Text("${task.category} · $detail", style = Type.small, color = C.mute)
                            if (active) { Spacer(Modifier.height(6.dp)); ProgressLine((task.progress / 100).toFloat(), color = C.jelly, height = 4.dp) }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (task.status != null && task.status != "Completed" && !active) { Pill(task.status, C.danger); Spacer(Modifier.width(8.dp)) }
                        val idle = task.state == "Idle"
                        UButton(if (idle) "Run" else "Stop", {
                            act("${if (idle) "Started" else "Stopping"} ${task.name}") { Api.call("jellyfin", "ScheduledTasks/Running/${task.id}", if (idle) "POST" else "DELETE") }
                        }, icon = if (idle) Icons.Rounded.PlayArrow else Icons.Rounded.Stop, kind = ButtonKind.Ghost)
                    }
                }
            }
        }

        ServerPanel("Server activity", Icons.AutoMirrored.Rounded.List, null) {
            Show(log, "Loading activity…") { list ->
                if (list.isEmpty()) Empty("No activity yet.")
                list.forEach { entry ->
                    val color = when (entry.severity.lowercase()) { "warning" -> C.radarr; "error", "fatal" -> Color(0xFFC24A4A); else -> C.ok }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                            if (entry.detail.isNotEmpty()) Text(entry.detail, style = Type.small, color = C.mute)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(ago(entry.date), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute)
                    }
                }
            }
        }
    }

    if (adding) AddUserSheet({ adding = false }) { name, password ->
        scope.launch {
            try { Api.call("jellyfin", "Users/New", "POST", mapOf("Name" to name, "Password" to password)); adding = false; Toasts.show("Added $name"); reloadAll() }
            catch (e: Exception) { Toasts.fail(e) }
        }
    }
    deleting?.let { user ->
        Confirm("Delete ${user.name}?", "They lose access to your Jellyfin server, and their watch history and settings are removed. This can’t be undone.", "Delete user",
            { act("Deleted ${user.name}") { Api.call("jellyfin", "Users/${user.id}", "DELETE") } }, { deleting = null })
    }
    messaging?.let { device -> MessageSheet(device) { messaging = null } }
}

@Composable
private fun ServerPanel(title: String, icon: ImageVector, count: Int?, content: @Composable ColumnScope.() -> Unit) {
    Panel(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.jelly, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = Type.title)
            count?.let { Spacer(Modifier.width(8.dp)); Pill("$it") }
        }
        content()
    }
}

@Composable
private fun AddUserSheet(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Sheet(onDismiss, "Add a Jellyfin user") {
        Field("Name", name, { name = it.take(64) })
        Spacer(Modifier.height(12.dp))
        Field("Password", password, { password = it }, password = true)
        Spacer(Modifier.height(8.dp))
        Text("New users can see all libraries. Fine-tune their access in Jellyfin’s dashboard.", style = Type.small, color = C.mute)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UButton("Add user", { if (name.isBlank()) Toasts.show("Enter a name.", true) else onAdd(name.trim(), password) }, Modifier.weight(1f), icon = Icons.Rounded.PersonAdd)
            UButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun MessageSheet(device: JfDevice, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Sheet(onDismiss, "A message for ${device.user}", "To ${device.device} · ${device.client}") {
        Field("Your message", text, { text = it.take(500) }, placeholder = "Let them know about a server restart, or just say hello…", singleLine = false, minLines = 4)
        Spacer(Modifier.height(8.dp))
        Text("Shows on their screen for 15 seconds. Some apps don’t display messages. Maximum 500 characters.", style = Type.small, color = C.mute)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UButton("Send message", {
                val message = text.trim()
                if (message.isEmpty()) return@UButton Toasts.show("Enter a message between 1 and 500 characters.", true)
                busy = true
                scope.launch {
                    try { Api.call("jellyfin", "Sessions/${device.id}/Message", "POST", mapOf("Header" to "Unifiarr", "Text" to message, "TimeoutMs" to 15000)); Toasts.show("Message sent"); onDismiss() }
                    catch (e: Exception) { Toasts.fail(e) }
                    busy = false
                }
            }, Modifier.weight(1f), icon = Icons.Rounded.ChatBubbleOutline, busy = busy)
            UButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Ghost)
        }
    }
}

// ---------- Services (web renderConnections / serviceCard) ----------

private data class ServiceInfo(val id: String, val description: String, val port: Int, val help: String)

private val serviceInfo = listOf(
    ServiceInfo("jellyfin", "Your personal cinema", 8096, "Create an API key in Jellyfin › Dashboard › API Keys."),
    ServiceInfo("sonarr", "TV, on autopilot", 8989, "Find the API key in Sonarr › Settings › General."),
    ServiceInfo("radarr", "Movie night, sorted", 7878, "Find the API key in Radarr › Settings › General."),
    ServiceInfo("seerr", "Discover. Request. Enjoy.", 5055, "Find the API key in Seerr › Settings › General."),
    ServiceInfo("qbittorrent", "Every byte, in its place", 8080, "The WebUI address. Leave the login empty if qBittorrent skips authentication for this host."),
)

@Composable
fun ServicesScreen(app: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versions = load(app.config, app.live) {
        coroutineScope { app.config.keys.filter(app::isLive).map { id -> async { id to runCatching { Api.obj(id).str("version") }.getOrDefault("") } }.awaitAll().toMap() }
    }
    var removing by remember { mutableStateOf<String?>(null) }
    var banners by remember { mutableStateOf(prefs(context).getBoolean("banners", false)) }
    val fresh = app.config.isEmpty()

    Page(onRefresh = { app.connect() }) {
        if (fresh) { ServiceLogo("unifiarr", 56.dp); Spacer(Modifier.height(14.dp)) }
        PageHeading(
            if (fresh) "Welcome to Unifiarr" else "Your services",
            if (fresh) "Connect your first service to get started. Only the services you set up appear in Unifiarr." else "Connect, update or remove the services Unifiarr manages.",
            eyebrow = if (fresh) "Setup" else null,
        )
        serviceInfo.forEach { service ->
            ServiceCard(app, service, versions.value?.get(service.id), { removing = service.id }) { body ->
                val name = serviceNames[service.id]!!
                try {
                    val version = Api.obj("config", service.id, "PUT", body).str("version")
                    app.connect()
                    Toasts.show("Connected to $name $version".trim())
                    true
                } catch (e: Exception) { Toasts.fail(e); false }
            }
            Spacer(Modifier.height(14.dp))
        }
        Text("Addresses and keys are saved by Unifiarr’s server and never sent back to this app.", style = Type.small, color = C.mute)
        if (!fresh) {
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth()) {
                Text("Display", style = Type.title)
                Spacer(Modifier.height(8.dp))
                Toggle("Show “Connected to …” banners on pages", banners, {
                    banners = it
                    prefs(context).edit().putBoolean("banners", it).apply()
                    Toasts.show(if (it) "Connection banners shown" else "Connection banners hidden")
                }, note = "Warnings about services that can’t be reached always show.")
            }
        }
        Spacer(Modifier.height(14.dp))
        Panel(Modifier.fillMaxWidth()) {
            Text("Unifiarr server", style = Type.title)
            Spacer(Modifier.height(4.dp))
            Text("This app is connected to ${app.server}", style = Type.small, color = C.mute)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UButton("Recheck", { scope.act("Checked your services.") { app.checkHealth() } }, Modifier.weight(1f), icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost)
                UButton("Change server", { app.server = null }, Modifier.weight(1f), icon = Icons.Rounded.Link, kind = ButtonKind.Ghost)
            }
        }
    }

    removing?.let { id ->
        val name = serviceNames[id]!!
        Confirm("Remove $name?", "Unifiarr forgets its address and ${if (id == "qbittorrent") "login" else "API key"}, and hides its pages. Nothing changes on $name itself.", "Remove", {
            scope.act("$name removed") { Api.call("config", id, "DELETE"); app.connect() }
        }, { removing = null })
    }
}

@Composable
private fun ServiceCard(app: AppState, service: ServiceInfo, version: String?, onRemove: () -> Unit, onSave: suspend (Map<String, String>) -> Boolean) {
    val scope = rememberCoroutineScope()
    val saved = app.config[service.id]
    val qbit = service.id == "qbittorrent"
    var url by remember(saved) { mutableStateOf(saved?.str("url").orEmpty()) }
    var user by remember(saved) { mutableStateOf(saved?.str("user").orEmpty()) }
    var secret by remember(saved) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val keep = saved?.optBoolean("hasSecret") == true
    val live = app.isLive(service.id)

    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServiceLogo(service.id, 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(serviceNames[service.id]!!, style = Type.title)
                Text(service.description, style = Type.small, color = C.mute)
            }
            if (saved != null) Pill(if (live) listOf("Live", version.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ") else "Offline", if (live) C.ok else C.warn, dot = true)
        }
        Spacer(Modifier.height(14.dp))
        Field("Address", url, { url = it.trim() }, placeholder = "http://192.168.1.10:${service.port}")
        Spacer(Modifier.height(12.dp))
        if (qbit) {
            Field("Username", user, { user = it })
            Spacer(Modifier.height(12.dp))
            Field("Password", secret, { secret = it }, password = true, placeholder = if (keep) "Saved · leave blank to keep" else "")
        } else Field("API key", secret, { secret = it.trim() }, password = true, placeholder = if (keep) "Saved · leave blank to keep" else "")
        Spacer(Modifier.height(8.dp))
        Text(service.help, style = Type.small, color = C.mute)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UButton(if (saved != null) "Save & test" else "Connect", {
                when {
                    !Regex("^https?://.+").matches(url) -> Toasts.show("Enter an http:// or https:// address.", true)
                    !qbit && !keep && secret.isEmpty() -> Toasts.show("Enter the API key.", true)
                    else -> {
                        busy = true
                        scope.launch {
                            val ok = onSave(if (qbit) mapOf("url" to url, "user" to user, "pass" to secret) else mapOf("url" to url, "key" to secret))
                            busy = false
                            if (ok) secret = ""
                        }
                    }
                }
            }, Modifier.weight(1f), icon = if (saved != null) Icons.Rounded.Check else Icons.Rounded.Add, busy = busy)
            if (saved != null) UButton("Remove", onRemove, Modifier.weight(1f), icon = Icons.Rounded.DeleteOutline, kind = ButtonKind.Ghost)
        }
    }
}
