package aj.unifiarr.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLDecoder
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// ---------- Model (qbit.mjs mapTorrent / applyDetails) ----------

private data class Torrent(
    val hash: String, val name: String, val category: String, val tags: List<String>, val state: String, val label: String, val tone: String,
    val progress: Double, val size: Long, val done: Long, val ratio: Double, val uploaded: Long, val savePath: String,
    val forced: Boolean, val sequential: Boolean, val firstLast: Boolean, val dlLimit: Int, val upLimit: Int,
    val dlspeed: Long, val upspeed: Long, val eta: Long?, val seeds: Pair<Int, Int>, val peers: Pair<Int, Int>, val addedOn: Long, val queue: Int,
) {
    val running get() = state != "paused" && state != "queued"
    fun live(down: Boolean): Long {
        if (!running) return 0
        val limit = (if (down) dlLimit else upLimit) * 1024L
        val speed = if (down) dlspeed else upspeed
        return if (limit > 0) minOf(speed, limit) else speed
    }
    val etaText get() = if (progress >= 100) "—" else if (running) duration(eta) else "∞"
    val ratioText get() = String.format(Locale.US, "%.2f", ratio)
    val percent get() = if (progress % 1.0 == 0.0) "${progress.toInt()}%" else "$progress%"
}
private data class TFile(val index: Int, val name: String, val size: Long, val progress: Double, val priority: Int)
private data class Tracker(val url: String, val status: String, val message: String, val seeds: Int, val peers: Int)
private data class Details(val files: List<TFile>, val trackers: List<Tracker>)
private data class Transfer(val dl: Long = 0, val up: Long = 0, val dlLimit: Long = 0, val upLimit: Long = 0, val free: Long? = null, val alt: Boolean = false)

private val stateLabels = mapOf(
    "error" to "Error", "missingFiles" to "Missing files", "metaDL" to "Fetching metadata", "forcedMetaDL" to "[F] Fetching metadata", "stalledDL" to "Stalled",
    "stalledUP" to "Seeding", "checkingDL" to "Checking", "checkingUP" to "Checking", "checkingResumeData" to "Checking", "allocating" to "Allocating", "moving" to "Moving",
    "queuedDL" to "Queued", "queuedUP" to "Queued", "uploading" to "Seeding", "forcedUP" to "[F] Seeding", "downloading" to "Downloading", "forcedDL" to "[F] Downloading",
    "pausedDL" to "Paused", "pausedUP" to "Paused", "stoppedDL" to "Stopped", "stoppedUP" to "Stopped",
)
private val trackerStatus = listOf("Disabled", "Not contacted", "Working", "Updating", "Not working")
private val filePriorities = listOf(0 to "Do not download", 1 to "Normal", 6 to "High", 7 to "Maximum")
private fun tenth(value: Double) = floor(value * 1000) / 10

private fun mapTorrent(hash: String, t: JSONObject): Torrent {
    val raw = t.str("state")
    val stopped = raw.startsWith("paused") || raw.startsWith("stopped") || raw == "error" || raw == "missingFiles"
    val progress = tenth(t.optDouble("progress", 0.0))
    val state = when { stopped -> "paused"; raw.startsWith("queued") -> "queued"; progress == 100.0 -> "seeding"; else -> "downloading" }
    return Torrent(
        hash, t.str("name"), t.str("category"), t.str("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }, state, stateLabels[raw] ?: raw,
        if (raw == "error" || raw == "missingFiles") "error" else state, progress, t.optLong("size"), t.optLong("completed"), t.optDouble("ratio", 0.0),
        t.optLong("uploaded"), t.str("save_path"), t.optBoolean("force_start"), t.optBoolean("seq_dl"), t.optBoolean("f_l_piece_prio"),
        t.optLong("dl_limit").let { if (it > 0) (it / 1024.0).roundToInt() else 0 }, t.optLong("up_limit").let { if (it > 0) (it / 1024.0).roundToInt() else 0 },
        t.optLong("dlspeed"), t.optLong("upspeed"), t.optLong("eta").takeIf { it < 8640000 }, t.optInt("num_seeds") to t.optInt("num_complete"),
        t.optInt("num_leechs") to t.optInt("num_incomplete"), t.optLong("added_on"), t.optInt("priority").coerceAtLeast(0),
    )
}

private fun mapDetails(files: org.json.JSONArray?, trackers: org.json.JSONArray?) = Details(
    files.objects().mapIndexed { i, f -> TFile(f.optInt("index", i), f.str("name"), f.optLong("size"), tenth(f.optDouble("progress", 0.0)), f.optInt("priority")) },
    trackers.objects().map { Tracker(it.str("url"), trackerStatus.getOrElse(it.optInt("status", -1)) { "Unknown" }, it.str("msg"), max(0, it.optInt("num_seeds")), max(0, it.optInt("num_leeches"))) },
)

suspend fun acceptTorrent(context: android.content.Context, uri: Uri) {
    when (uri.scheme?.lowercase()) {
        "magnet", "http", "https" -> Api.call("qbittorrent", "torrents/add", "POST", FormBody.Builder().add("urls", uri.toString()).build())
        "content" -> {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.takeIf(String::isNotBlank) ?: "torrent.torrent"
            val data = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(25 * 1024 * 1024 + 1) }
                ?: throw IOException("Couldn't read the selected torrent file.")
            if (data.size > 25 * 1024 * 1024) throw IOException("Torrent files must be 25 MB or smaller.")
            val type = context.contentResolver.getType(uri) ?: "application/x-bittorrent"
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("torrents", name, data.toRequestBody(type.toMediaType())).build()
            Api.call("qbittorrent", "torrents/add", "POST", body)
        }
        else -> throw IOException("Unifiarr can open magnet links, .torrent URLs and torrent files.")
    }
}

// ---------- Live state: server WebSocket feed, 1s HTTP polling only while it's down ----------

private object Q {
    var torrents by mutableStateOf<List<Torrent>>(emptyList())
    var categories by mutableStateOf<Map<String, String>>(emptyMap())
    var transfer by mutableStateOf(Transfer())
    var details by mutableStateOf<Map<String, Details>>(emptyMap())
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var version by mutableStateOf("")
    var defaultSavePath by mutableStateOf("/downloads")
    var watching: String? = null; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rawTorrents = HashMap<String, JSONObject>()
    private val rawCategories = HashMap<String, JSONObject>()
    private var server = JSONObject()
    private var socket: WebSocket? = null
    private var open = false
    private var users = 0
    private var job: Job? = null

    fun acquire() { if (users++ == 0) job = scope.launch { run() } }
    fun release() { if (--users == 0) { job?.cancel(); job = null; socket?.cancel(); socket = null; open = false } }

    fun watch(hash: String?) {
        watching = hash
        socket?.send(JSONObject().put("watch", hash ?: JSONObject.NULL).toString())
    }

    // qbit.mjs merge(): fold a sync/maindata reply (full or delta) into the raw state, then re-map.
    private fun merge(delta: JSONObject) {
        if (delta.optBoolean("full_update")) { rawTorrents.clear(); rawCategories.clear(); server = JSONObject() }
        delta.optJSONObject("server_state")?.let { s -> s.keys().forEach { server.put(it, s.get(it)) } }
        delta.optJSONObject("torrents")?.let { all -> all.keys().forEach { hash -> val fields = all.getJSONObject(hash); val t = rawTorrents.getOrPut(hash) { JSONObject() }; fields.keys().forEach { t.put(it, fields.get(it)) } } }
        delta.optJSONArray("torrents_removed").strings().forEach { rawTorrents.remove(it) }
        delta.optJSONObject("categories")?.let { all -> all.keys().forEach { name -> val fields = all.getJSONObject(name); val c = rawCategories.getOrPut(name) { JSONObject() }; fields.keys().forEach { c.put(it, fields.get(it)) } } }
        delta.optJSONArray("categories_removed").strings().forEach { rawCategories.remove(it) }
        torrents = rawTorrents.map { (hash, t) -> mapTorrent(hash, t) }
            .sortedWith(compareBy<Torrent> { if (it.queue > 0) it.queue else Int.MAX_VALUE }.thenByDescending { it.addedOn })
        categories = rawCategories.values.associate { it.str("name") to it.str("savePath") }.toSortedMap()
        transfer = Transfer(server.optLong("dl_info_speed"), server.optLong("up_info_speed"), server.optLong("dl_rate_limit"), server.optLong("up_rate_limit"),
            server.optLong("free_space_on_disk", -1).takeIf { it >= 0 }, server.optBoolean("use_alt_speed_limits"))
        Badges.downloading = torrents.count { it.progress < 100 }
        loaded = true; error = null
    }

    private fun handle(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val d = message.optJSONObject("details")
        if (d == null) return merge(message)
        val hash = d.str("hash")
        if (torrents.any { it.hash == hash }) details = details + (hash to mapDetails(d.optJSONArray("files"), d.optJSONArray("trackers")))
    }

    suspend fun sync() = merge(Api.obj("qbittorrent", "sync/maindata?rid=0"))

    suspend fun loadDetails(hash: String) = coroutineScope {
        val files = async { Api.call("qbittorrent", "torrents/files?hash=$hash") as? org.json.JSONArray }
        val trackers = async { Api.call("qbittorrent", "torrents/trackers?hash=$hash") as? org.json.JSONArray }
        details = details + (hash to mapDetails(files.await(), trackers.await()))
    }

    private suspend fun run() = coroutineScope {
        launch {
            runCatching {
                version = Api.obj("qbittorrent").str("version")
                defaultSavePath = Api.call("qbittorrent", "app/defaultSavePath").toString().trim()
            }
        }
        launch {
            while (true) {
                if (!open) try { sync(); watching?.let { loadDetails(it) } } catch (e: CancellationException) { throw e } catch (e: Exception) { if (!loaded) error = e.message ?: "Couldn't reach qBittorrent." }
                delay(1000)
            }
        }
        while (true) { stream(); delay(3000) }
    }

    // Suspends until the socket closes. The server only upgrades when Origin matches the Host header it receives.
    private suspend fun stream() = suspendCancellableCoroutine { cont ->
        val url = runCatching { Api.base.toHttpUrl() }.getOrNull() ?: return@suspendCancellableCoroutine cont.resume(Unit)
        val host = if (':' in url.host) "[${url.host}]" else url.host
        val origin = "${url.scheme}://$host" + if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        val request = Request.Builder().url(Api.base + "api/qbittorrent/live").header("Origin", origin).build()
        val done = { ws: WebSocket -> scope.launch { if (socket === ws) { socket = null; open = false }; if (cont.isActive) cont.resume(Unit) }; Unit }
        val ws = streamClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch { socket = webSocket; open = true; watch(watching) } }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { if (socket === webSocket) handle(text) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done(webSocket) }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }
    // The feed can stay quiet for long stretches; no read timeout, and no pings (the server doesn't answer them).
    private val streamClient by lazy { Api.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
}

/** Keeps the live feed running while the caller is on screen and the app is visible. */
@Composable
private fun LiveTorrents() {
    val visible = LocalAppVisible.current
    DisposableEffect(visible) { if (visible) Q.acquire(); onDispose { if (visible) Q.release() } }
}

/** Runs a qBittorrent call, then reloads the real state (web liveDo). Uses Q's scope so closing a sheet doesn't cancel it. */
private fun liveDo(work: suspend () -> String) = Q.scope.launch {
    try {
        val message = work()
        Q.sync()
        Q.watching?.let { runCatching { Q.loadDetails(it) } }
        Toasts.show(message)
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Toasts.fail(e) }
}

private fun form(vararg params: Pair<String, Any>) = FormBody.Builder().apply { params.forEach { add(it.first, it.second.toString()) } }.build()
private suspend fun post(path: String, vararg params: Pair<String, Any>) = Api.call("qbittorrent", path, "POST", form(*params))
// qBittorrent 5 renamed pause/resume to stop/start; 4.x only knows the old names.
private suspend fun either(modern: String, legacy: String, hashes: String) =
    try { post("torrents/$modern", "hashes" to hashes) } catch (e: Api.Failure) { if (e.status == 404) post("torrents/$legacy", "hashes" to hashes) else throw e }

private fun toggle(t: Torrent) = liveDo {
    if (t.state == "paused") { either("start", "resume", t.hash); "Torrent resumed" } else { either("stop", "pause", t.hash); "Torrent paused" }
}
private fun force(t: Torrent) = liveDo { post("torrents/setForceStart", "hashes" to t.hash, "value" to !t.forced); "Force start ${if (t.forced) "off" else "on"}" }
private fun recheck(t: Torrent) = liveDo { post("torrents/recheck", "hashes" to t.hash); "Recheck started" }
private fun reannounce(t: Torrent) = liveDo { post("torrents/reannounce", "hashes" to t.hash); "Reannounced to trackers" }

// ---------- Posters: release names matched to library titles (model.mjs indexTitles / matchTitle) ----------

private object Posters {
    var index by mutableStateOf<Map<String, String>>(emptyMap())
    private var stamp = -1

    private fun words(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(Regex("[\\u0300-\\u036f]"), "")
        .replace("&", " and ").replace("'", "").replace(Regex("[^a-z0-9]+"), " ").trim()
    private val stop = Regex("\\b(s\\d{1,2}(e\\d{1,3})?|season \\d+|complete|\\d{3,4}p|4k|uhd|bluray|bdrip|web ?dl|webrip|hdtv|x26[45]|h 26[45]|hevc|remux)\\b")
    private val year = Regex("\\b(19|20)\\d\\d\\b")

    fun match(name: String, index: Map<String, String>): String? {
        val text = words(name.replace(Regex("^\\s*[\\[(][^\\])]*[\\])]\\s*"), ""))
        val head = stop.find(text)?.let { text.substring(0, it.range.first).trim() } ?: text
        for (m in year.findAll(head)) {
            val title = head.substring(0, m.range.first).trim()
            val found = index["$title ${m.value}"] ?: index[title]
            if (title.isNotEmpty() && found != null) return found
        }
        return index[head]
    }

    // Jellyfin library first, then Sonarr/Radarr (same priority as the web's posterFor).
    suspend fun load(app: AppState) {
        if (stamp == app.refresh) return
        stamp = app.refresh
        val items = mutableListOf<Triple<String, String, String>>() // title, year, image
        coroutineScope {
            val jellyfin = async {
                if (!app.has("jellyfin")) emptyList() else runCatching {
                    Api.obj("jellyfin", "Items?Recursive=true&IncludeItemTypes=Movie,Series&SortBy=DateCreated&SortOrder=Descending&Limit=2000").list("Items").mapNotNull { i ->
                        val tag = i.obj("ImageTags")?.strOrNull("Primary") ?: return@mapNotNull null
                        Triple(i.str("Name"), i.str("ProductionYear"), "api/jellyfin/Items/${i.str("Id")}/Images/Primary?maxHeight=480&tag=$tag")
                    }
                }.getOrDefault(emptyList())
            }
            val arr = app.arrs.map { service ->
                async {
                    runCatching {
                        Api.arr(service, if (service == "sonarr") "series" else "movie").objects().mapNotNull { t ->
                            val poster = t.list("images").firstOrNull { it.str("coverType") == "poster" }?.strOrNull("remoteUrl") ?: return@mapNotNull null
                            Triple(t.str("title"), t.optInt("year").takeIf { it > 0 }?.toString().orEmpty(), poster)
                        }
                    }.getOrDefault(emptyList())
                }
            }
            items += jellyfin.await()
            items += arr.awaitAll().flatten().sortedBy { it.first }
        }
        val map = HashMap<String, String>()
        for ((title, y, image) in items) {
            val key = words(title)
            if (key.isEmpty()) continue
            map.putIfAbsent(key, image)
            if (y.isNotEmpty()) map["$key $y"] = image
        }
        index = map
    }
}

// ---------- Shared bits ----------

@Composable private fun toneColor(tone: String) = when (tone) { "downloading" -> C.accent; "seeding" -> C.ok; "error" -> C.danger; "queued" -> C.radarr; else -> C.mute }
@Composable private fun barColor(t: Torrent) = when { t.tone == "error" -> C.danger; t.state == "paused" -> C.mute; t.state == "seeding" -> C.ok; else -> C.accent }

@Composable
private fun Thumb(t: Torrent, modifier: Modifier) {
    val index = Posters.index
    val url = remember(t.name, index) { Posters.match(t.name, index) }
    val files = Q.details[t.hash]?.files?.size ?: 0
    Poster(url, modifier, RoundedCornerShape(8.dp), fallback = when { t.size == 0L && files == 0 -> Icons.Rounded.Link; files > 1 -> Icons.Rounded.Folder; else -> Icons.Rounded.InsertDriveFile })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(t: Torrent) {
    if (t.category.isEmpty() && t.tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (t.category.isNotEmpty()) IconLabel(Icons.Rounded.Folder, t.category, C.qbittorrent)
        t.tags.forEach { IconLabel(Icons.Rounded.Sell, it, C.mute) }
    }
}

@Composable
private fun IconLabel(icon: ImageVector, text: String, color: Color) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = if (C.dark) .18f else .1f)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(text, style = Type.tiny, color = color, maxLines = 1)
    }
}

@Composable
private fun DeleteTorrent(t: Torrent, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) = DeleteTorrents(listOf(t.hash), "“${t.name}”", onDismiss, onDeleted)

@Composable
private fun DeleteTorrents(hashes: List<String>, label: String, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    var files by remember { mutableStateOf(false) }
    val many = hashes.size > 1
    Confirm(if (many) "Remove ${hashes.size} torrents?" else "Remove this torrent?", "$label will be removed from qBittorrent.", if (many) "Remove torrents" else "Remove torrent", onConfirm = {
        liveDo {
            post("torrents/delete", "hashes" to hashes.joinToString("|"), "deleteFiles" to files); onDeleted()
            if (many) "Removed ${hashes.size} torrents${if (files) " and their files" else ""}" else if (files) "Torrent and files deleted" else "Torrent removed"
        }
    }, onDismiss = onDismiss, extra = { Spacer(Modifier.height(10.dp)); Toggle("Also delete downloaded files from disk", files, { files = it }) })
}

// ---------- Downloads page (web renderQueue) ----------

private val filters = linkedMapOf<String, Pair<String, (Torrent) -> Boolean>>(
    "all" to ("All" to { _ -> true }),
    "downloading" to ("Downloading" to { t -> t.progress < 100 && t.state != "paused" }),
    "seeding" to ("Seeding" to { t -> t.progress >= 100 && t.state != "paused" }),
    "completed" to ("Completed" to { t -> t.progress >= 100 }),
    "paused" to ("Paused" to { t -> t.state == "paused" }),
)
private const val ALL = "\u0000all"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(app: AppState) {
    LiveTorrents()
    LaunchedEffect(app.refresh) { Posters.load(app) }
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("all") }
    var category by rememberSaveable { mutableStateOf(ALL) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Torrent?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var bulkDelete by remember { mutableStateOf(false) }
    fun pick(hash: String) { selecting = true; if (hash in picked) picked.remove(hash) else picked.add(hash) }
    fun stopSelecting() { selecting = false; picked.clear() }
    BackHandler(selecting) { stopSelecting() }

    val torrents = Q.torrents
    LaunchedEffect(torrents) { val live = torrents.map { it.hash }.toSet(); picked.retainAll { it in live } }
    val needle = app.query.trim().lowercase()
    val shown = remember(torrents, filter, category, needle) {
        torrents.filter { t ->
            filters.getValue(filter).second(t) && (category == ALL || t.category == category) &&
                (needle.isEmpty() || "${t.name} ${t.category} ${t.tags.joinToString(" ")}".lowercase().contains(needle))
        }
    }

    PullToRefreshBox(refreshing, {
        scope.launch { refreshing = true; runCatching { Q.sync() }.onFailure { Toasts.fail(it) }; app.refresh++; refreshing = false }
    }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = if (selecting) 96.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("head") {
                Column {
                    PageHeading("Downloads", "Manage torrents in qBittorrent.")
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val alt = Q.transfer.alt
                        UButton("Add torrent", { adding = true }, icon = Icons.Rounded.Add)
                        UButton("Alt speed", { liveDo { post("transfer/toggleSpeedLimitsMode"); "Alternative speed limits ${if (alt) "off" else "on"}" } },
                            icon = Icons.Rounded.Speed, kind = if (alt) ButtonKind.Soft else ButtonKind.Ghost)
                        UButton("Pause all", { liveDo { either("stop", "pause", "all"); "Paused all torrents" } }, icon = Icons.Rounded.Pause, kind = ButtonKind.Ghost)
                        UButton("Resume all", { liveDo { either("start", "resume", "all"); "Resumed all torrents" } }, icon = Icons.Rounded.PlayArrow, kind = ButtonKind.Ghost)
                        UButton("Select", { if (selecting) stopSelecting() else selecting = true }, icon = Icons.Rounded.Checklist, kind = if (selecting) ButtonKind.Soft else ButtonKind.Ghost)
                    }
                }
            }
            if (!Q.loaded) {
                item("state") { Q.error?.let { ErrorBox(it, { scope.launch { runCatching { Q.sync() }.onFailure { e -> Q.error = e.message } } }) } ?: Loader("Loading torrents…") }
            } else {
                item("transfer") { TransferBar(torrents) }
                item("filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        val keys = filters.keys.toList()
                        Segmented(keys.map { "${filters.getValue(it).first} ${torrents.count(filters.getValue(it).second)}" }, keys.indexOf(filter), { filter = keys[it] })
                        Select("Category", listOf(ALL to "All categories", "" to "Uncategorized") + Q.categories.keys.map { it to it }, category, { category = it })
                    }
                }
                items(shown, key = { it.hash }) { t ->
                    TorrentRow(t, { if (selecting) pick(t.hash) else open = t.hash }, { deleting = t }, Modifier.animateItem(), if (selecting) t.hash in picked else null) { pick(t.hash) }
                }
                if (shown.isEmpty()) item("empty") { Empty("No torrents match these filters.", icon = Icons.Rounded.Download) }
            }
        }
        if (selecting) {
            val hashes = picked.joinToString("|"); val count = picked.size; val some = count > 0
            fun bulk(pause: Boolean) = liveDo {
                if (pause) either("stop", "pause", hashes) else either("start", "resume", hashes)
                "${if (pause) "Paused" else "Resumed"} $count torrent${if (count == 1) "" else "s"}"
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().shadow(10.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)).background(C.panel).border(1.dp, C.accent, RoundedCornerShape(16.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(Icons.Rounded.Close, "Stop selecting", { stopSelecting() }, tint = C.mute)
                Text(if (some) "$count selected" else "Select torrents", style = Type.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                IconAction(Icons.Rounded.SelectAll, "Select all", { shown.forEach { if (it.hash !in picked) picked.add(it.hash) } })
                IconAction(Icons.Rounded.Pause, "Pause selected", { bulk(true) }, enabled = some)
                IconAction(Icons.Rounded.PlayArrow, "Resume selected", { bulk(false) }, tint = C.accent, enabled = some)
                IconAction(Icons.Rounded.Delete, "Delete selected", { bulkDelete = true }, tint = C.danger, enabled = some)
            }
        }
    }
    if (bulkDelete) DeleteTorrents(picked.toList(), "${picked.size} torrent${if (picked.size == 1) "" else "s"}", { bulkDelete = false }) { stopSelecting() }
    open?.let { TorrentSheet(it) { open = null } }
    deleting?.let { DeleteTorrent(it, { deleting = null }) }
    if (adding) AddTorrentSheet({ adding = false }) { filter = "all"; category = ALL }
}

@Composable
private fun TransferBar(torrents: List<Torrent>) {
    val tr = Q.transfer
    fun limit(value: Long) = "${if (tr.alt) "Alt · " else ""}${if (value > 0) "Limit ${speed(value)}" else "No limit"}"
    val cards = listOf(
        Triple(Icons.Rounded.ArrowDownward, "Download", Triple(speed(tr.dl), limit(tr.dlLimit), C.accent)),
        Triple(Icons.Rounded.ArrowUpward, "Upload", Triple(speed(tr.up), limit(tr.upLimit), C.ok)),
        Triple(Icons.Rounded.Download, "Active", Triple("${torrents.count { it.running }} of ${torrents.size}", "torrents", C.qbittorrent)),
        Triple(Icons.Rounded.Storage, "Free space", Triple(tr.free?.let { bytes(it) } ?: "—", Q.defaultSavePath, C.radarr)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label, value) ->
                    Panel(Modifier.weight(1f), padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(16.dp), tint = value.third)
                            Spacer(Modifier.width(6.dp))
                            Text(label, style = Type.small, color = C.mute)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(value.first, style = Type.subtitle.copy(fontSize = Type.title.fontSize), maxLines = 1)
                        Text(value.second, style = Type.small, color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TorrentRow(t: Torrent, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier, selected: Boolean? = null, onLongClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Panel(modifier.fillMaxWidth().then(if (selected == true) Modifier.border(2.dp, C.accent, RoundedCornerShape(16.dp)) else Modifier), onClick = onOpen, padding = 12.dp, onLongClick = onLongClick) {
        Row {
            Thumb(t, Modifier.size(48.dp, 72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(t.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(t.label, toneColor(t.tone))
                    if (t.queue > 0) Text("#${t.queue}", style = Type.tiny, color = C.mute)
                }
                Chips(t)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine((t.progress / 100).toFloat(), Modifier.weight(1f), barColor(t))
                    Spacer(Modifier.width(8.dp))
                    Text(t.percent, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                }
                Text(
                    "${bytes(t.done)} of ${if (t.size > 0) bytes(t.size) else "—"} · ↓ ${speed(t.live(true))} · ↑ ${speed(t.live(false))} · ETA ${t.etaText}" +
                        " · Seeds ${t.seeds.first} (${t.seeds.second}) · Peers ${t.peers.first} (${t.peers.second}) · Ratio ${t.ratioText}",
                    style = Type.small, color = C.mute,
                )
            }
            if (selected != null) Checkbox(selected, { onOpen() }, colors = CheckboxDefaults.colors(checkedColor = C.accent, uncheckedColor = C.mute))
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val paused = t.state == "paused"
                IconAction(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause", { toggle(t) }, tint = C.accent)
                Box {
                    IconAction(Icons.Rounded.MoreVert, "More actions", { menu = true }, tint = C.mute)
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(12.dp), containerColor = C.panel) {
                        listOf(
                            Triple(Icons.Rounded.Info, "Details", onOpen),
                            Triple(Icons.Rounded.Bolt, if (t.forced) "Force start off" else "Force start", { force(t); Unit }),
                            Triple(Icons.Rounded.Refresh, "Recheck", { recheck(t); Unit }),
                            Triple(Icons.Rounded.CellTower, "Reannounce", { reannounce(t); Unit }),
                            Triple(Icons.Rounded.Delete, "Delete", onDelete),
                        ).forEach { (icon, label, action) ->
                            val danger = label == "Delete"
                            DropdownMenuItem({ Text(label, style = Type.body, color = if (danger) C.danger else C.ink) }, { menu = false; action() },
                                leadingIcon = { Icon(icon, null, tint = if (danger) C.danger else C.mute) })
                        }
                    }
                }
            }
        }
    }
}

// ---------- Torrent details (web torrentModal) ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentSheet(hash: String, onDismiss: () -> Unit) {
    LiveTorrents()
    DisposableEffect(hash) {
        Q.watch(hash)
        val load = Q.scope.launch { runCatching { Q.loadDetails(hash) }.onFailure { Toasts.fail(it) } }
        onDispose { load.cancel(); if (Q.watching == hash) Q.watch(null) }
    }
    val t = Q.torrents.firstOrNull { it.hash == hash }
    var tab by rememberSaveable(hash) { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    Sheet(onDismiss) {
        when {
            t == null && !Q.loaded -> Loader("Loading torrent…")
            t == null -> {
                Text("Torrent removed", style = Type.title)
                Spacer(Modifier.height(6.dp))
                Text("This torrent is no longer in qBittorrent.", style = Type.body, color = C.mute)
            }
            else -> {
                val details = Q.details[hash]
                Row(Modifier.padding(top = 6.dp, bottom = 14.dp)) {
                    Thumb(t, Modifier.size(64.dp, 96.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("QBITTORRENT", style = Type.eyebrow, color = C.mute)
                            Spacer(Modifier.width(8.dp))
                            Pill(t.label, toneColor(t.tone))
                        }
                        Text(t.name, style = Type.title)
                        Chips(t)
                    }
                }
                Segmented(listOf("General", "Files · ${details?.files?.size ?: 0}", "Trackers · ${details?.trackers?.size ?: 0}"), tab, { tab = it })
                Spacer(Modifier.height(16.dp))
                when (tab) {
                    0 -> General(t) { deleting = true }
                    1 -> Files(t, details)
                    else -> Trackers(details)
                }
                if (deleting) DeleteTorrent(t, { deleting = false }, onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun General(t: Torrent, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressLine((t.progress / 100).toFloat(), Modifier.weight(1f), barColor(t), height = 7.dp)
        Spacer(Modifier.width(10.dp))
        Text(t.percent, style = Type.subtitle)
    }
    Spacer(Modifier.height(14.dp))
    val facts = listOf(
        "Size" to (if (t.size > 0) "${bytes(t.size)} selected" else "Unknown"), "Downloaded" to bytes(t.done), "Uploaded" to bytes(t.uploaded), "Ratio" to t.ratioText,
        "Down speed" to speed(t.live(true)) + (if (t.dlLimit > 0) " · limit ${t.dlLimit} KiB/s" else ""),
        "Up speed" to speed(t.live(false)) + (if (t.upLimit > 0) " · limit ${t.upLimit} KiB/s" else ""),
        "ETA" to t.etaText, "Seeds" to "${t.seeds.first} of ${t.seeds.second}", "Peers" to "${t.peers.first} of ${t.peers.second}",
        "Added" to ago(Instant.ofEpochSecond(t.addedOn).toString()), "Save path" to t.savePath, "Info hash" to t.hash,
    )
    Column(Modifier.fillMaxWidth().clip(RoundSmall).background(C.soft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (term, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(term, style = Type.small, color = C.mute)
                        Text(value, style = if (term == "Info hash") Type.small.copy(fontFamily = FontFamily.Monospace) else Type.body.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    val paused = t.state == "paused"
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        UButton(if (paused) "Resume" else "Pause", { toggle(t) }, icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause)
        UButton("Force start", { force(t) }, icon = Icons.Rounded.Bolt, kind = if (t.forced) ButtonKind.Soft else ButtonKind.Ghost)
        UButton("Recheck", { recheck(t) }, icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost)
        UButton("Reannounce", { reannounce(t) }, icon = Icons.Rounded.CellTower, kind = ButtonKind.Ghost)
        UButton("Delete", onDelete, icon = Icons.Rounded.Delete, kind = ButtonKind.Danger)
    }
    if (t.queue > 0) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundSmall).background(C.soft).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue position #${t.queue} of ${Q.torrents.count { it.queue > 0 }}", style = Type.small, modifier = Modifier.weight(1f))
            listOf(
                Triple("topPrio", Icons.Rounded.KeyboardDoubleArrowUp, "Move to top"), Triple("increasePrio", Icons.Rounded.KeyboardArrowUp, "Move up"),
                Triple("decreasePrio", Icons.Rounded.KeyboardArrowDown, "Move down"), Triple("bottomPrio", Icons.Rounded.KeyboardDoubleArrowDown, "Move to bottom"),
            ).forEach { (op, icon, label) -> IconAction(icon, label, { liveDo { post("torrents/$op", "hashes" to t.hash); "Queue position changed" } }) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Options(t)
}

/** Web "Torrent options": rename, location, category, tags, limits, sequential / first-last piece. */
@Composable
private fun Options(t: Torrent) {
    var expanded by rememberSaveable(t.hash) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundSmall).clickable { expanded = !expanded }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Settings, null, tint = C.mute, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Torrent options", style = Type.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = C.mute)
    }
    if (!expanded) return
    // Seeded once from the torrent so live updates never overwrite what's being typed.
    var name by remember(t.hash) { mutableStateOf(t.name) }
    var savePath by remember(t.hash) { mutableStateOf(t.savePath) }
    var category by remember(t.hash) { mutableStateOf(t.category) }
    var tags by remember(t.hash) { mutableStateOf(t.tags.joinToString(", ")) }
    var dl by remember(t.hash) { mutableStateOf(t.dlLimit.toString()) }
    var up by remember(t.hash) { mutableStateOf(t.upLimit.toString()) }
    var sequential by remember(t.hash) { mutableStateOf(t.sequential) }
    var firstLast by remember(t.hash) { mutableStateOf(t.firstLast) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
        Field("Name", name, { name = it })
        Field("Save path", savePath, { savePath = it })
        Select("Category", listOf("" to "Uncategorized") + Q.categories.keys.map { it to it }, category, { category = it })
        Field("Tags", tags, { tags = it }, placeholder = "Comma separated")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Download limit (KiB/s)", dl, { dl = it.filter(Char::isDigit) }, Modifier.weight(1f), number = true)
            Field("Upload limit (KiB/s)", up, { up = it.filter(Char::isDigit) }, Modifier.weight(1f), number = true)
        }
        Toggle("Download in sequential order", sequential, { sequential = it })
        Toggle("Download first and last pieces first", firstLast, { firstLast = it })
        UButton("Save options", {
            try {
                val s = settings(name, savePath, category, tags, dl, up)
                liveDo {
                    val h = t.hash
                    if (s.name != t.name) post("torrents/rename", "hash" to h, "name" to s.name)
                    if (s.savePath != t.savePath) post("torrents/setLocation", "hashes" to h, "location" to s.savePath)
                    if (s.category != t.category) post("torrents/setCategory", "hashes" to h, "category" to s.category)
                    val removed = t.tags - s.tags.toSet(); val added = s.tags - t.tags.toSet()
                    if (removed.isNotEmpty()) post("torrents/removeTags", "hashes" to h, "tags" to removed.joinToString(","))
                    if (added.isNotEmpty()) post("torrents/addTags", "hashes" to h, "tags" to added.joinToString(","))
                    if (s.dlLimit != t.dlLimit) post("torrents/setDownloadLimit", "hashes" to h, "limit" to s.dlLimit * 1024)
                    if (s.upLimit != t.upLimit) post("torrents/setUploadLimit", "hashes" to h, "limit" to s.upLimit * 1024)
                    if (sequential != t.sequential) post("torrents/toggleSequentialDownload", "hashes" to h)
                    if (firstLast != t.firstLast) post("torrents/toggleFirstLastPiecePrio", "hashes" to h)
                    "Torrent options saved"
                }
            } catch (e: IllegalArgumentException) { Toasts.fail(e) }
        }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Check)
    }
}

private data class Settings(val name: String, val savePath: String, val category: String, val tags: List<String>, val dlLimit: Int, val upLimit: Int)

// model.mjs torrentSettings validation.
private fun validPath(path: String) = Regex("^(/|[a-zA-Z]:[\\\\/])[^\\u0000]{0,254}$").matches(path) && ".." !in path.split('/', '\\')
private fun parseTags(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().also { tags ->
    require(tags.none { it.length > 30 }) { "Tags must be up to 30 characters without commas." }
}
private fun settings(name: String, savePath: String, category: String, tags: String, dl: String, up: String): Settings {
    val n = name.trim(); val p = savePath.trim()
    require(n.isNotEmpty() && n.length <= 255) { "Enter a torrent name up to 255 characters." }
    require(validPath(p)) { "Enter an absolute save path." }
    require(category.isEmpty() || category in Q.categories) { "Choose an existing category." }
    val limits = listOf(dl, up).map { it.ifBlank { "0" }.toIntOrNull()?.takeIf { v -> v in 0..1_000_000 } ?: throw IllegalArgumentException("Speed limits must be whole KiB/s values (0 = unlimited).") }
    return Settings(n, p, category, parseTags(tags), limits[0], limits[1])
}

@Composable
private fun Files(t: Torrent, details: Details?) {
    when {
        details == null -> Loader("Loading files…")
        details.files.isEmpty() -> Empty("Waiting for metadata from peers. Files appear once the magnet link resolves.", icon = Icons.Rounded.Link)
        else -> {
            Text("Selected ${bytes(details.files.filter { it.priority > 0 }.sumOf { it.size })} of ${bytes(details.files.sumOf { it.size })}", style = Type.small, color = C.mute)
            Spacer(Modifier.height(10.dp))
            details.files.forEachIndexed { i, file ->
                if (i > 0) HorizontalDivider(color = C.line)
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(18.dp).padding(top = 2.dp), tint = C.mute)
                        Spacer(Modifier.width(8.dp))
                        Text(file.name.substringAfterLast('/'), style = Type.body, color = if (file.priority > 0) C.ink else C.mute, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${bytes(file.size)} · ${if (file.progress % 1.0 == 0.0) file.progress.toInt() else file.progress}%", style = Type.small, color = C.mute)
                            Spacer(Modifier.height(4.dp))
                            ProgressLine((file.progress / 100).toFloat(), color = if (file.priority > 0) barColor(t) else C.mute, height = 4.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Select("", filePriorities, file.priority, { priority ->
                            liveDo { post("torrents/filePrio", "hash" to t.hash, "id" to file.index, "priority" to priority); "File priority changed" }
                        }, Modifier.width(170.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Trackers(details: Details?) {
    if (details == null) return Loader("Loading trackers…")
    if (details.trackers.isEmpty()) return Empty("No trackers.")
    details.trackers.forEachIndexed { i, tr ->
        if (i > 0) HorizontalDivider(color = C.line)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr.url, style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (tr.message.isNotEmpty()) Text(tr.message, style = Type.small, color = C.mute)
                Text("Seeds ${tr.seeds} · Peers ${tr.peers}", style = Type.small, color = C.mute)
            }
            Spacer(Modifier.width(10.dp))
            Pill(tr.status, when (tr.status) { "Working" -> C.ok; "Not working" -> C.danger; else -> C.mute })
        }
    }
}

// ---------- Add torrents (web showAddTorrent / qbit.add) ----------

private val btih = Regex("^urn:btih:([a-f0-9]{40}|[a-z2-7]{32})$", RegexOption.IGNORE_CASE)
private fun magnetHash(link: String): String {
    val text = link.trim()
    val query = if (text.startsWith("magnet:?", ignoreCase = true)) text.substringAfter('?') else throw IllegalArgumentException("Enter a valid magnet link.")
    return query.split('&').filter { it.startsWith("xt=") }.firstNotNullOfOrNull { btih.find(URLDecoder.decode(it.substring(3), "UTF-8"))?.groupValues?.get(1) }
        ?.lowercase() ?: throw IllegalArgumentException("Enter a valid magnet link.")
}

private class Picked(val name: String, val bytes: ByteArray)

@Composable
fun AddTorrentSheet(onDismiss: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var magnets by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var category by remember { mutableStateOf("") }
    var savePath by remember { mutableStateOf(Q.defaultSavePath) }
    var tags by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(true) }
    var top by remember { mutableStateOf(false) }
    var sequential by remember { mutableStateOf(false) }
    var firstLast by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        scope.launch {
            try {
                files = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file.torrent"
                        val data = context.contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = input.readNBytesCompat(10 * 1024 * 1024 + 1)
                            require(buffer.size <= 10 * 1024 * 1024) { "Torrent files must be under 10 MiB." }
                            buffer
                        } ?: throw IllegalArgumentException("Couldn't read $name.")
                        Picked(name, data)
                    }
                }
            } catch (e: Exception) { Toasts.fail(e) }
        }
    }
    Sheet(onDismiss, "Add torrents", "Paste magnet links or upload .torrent files.") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Magnet links · one per line", magnets, { magnets = it }, placeholder = "magnet:?xt=urn:btih:…", singleLine = false, minLines = 3)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundSmall).background(C.soft).clickable { picker.launch("*/*") }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.UploadFile, null, tint = C.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(files.joinToString(", ") { it.name }.ifEmpty { "Choose .torrent files" }, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (files.isEmpty()) "Pick one or more from your device" else "${files.size} file${if (files.size == 1) "" else "s"} · tap to change", style = Type.small, color = C.mute)
                }
                if (files.isNotEmpty()) IconAction(Icons.Rounded.Close, "Clear files", { files = emptyList() }, tint = C.mute)
            }
            Select("Category", listOf("" to "Uncategorized") + Q.categories.keys.map { it to it }, category, {
                category = it; savePath = Q.categories[it]?.ifEmpty { null } ?: Q.defaultSavePath
            })
            Field("Save path", savePath, { savePath = it })
            Field("Tags", tags, { tags = it }, placeholder = "Comma separated")
            Column {
                Toggle("Start torrent", start, { start = it })
                Toggle("Add to top of queue", top, { top = it })
                Toggle("Download in sequential order", sequential, { sequential = it })
                Toggle("Download first and last pieces first", firstLast, { firstLast = it })
            }
            if (Q.version.isNotEmpty()) Text("Sent to qBittorrent ${Q.version}.", style = Type.small, color = C.mute)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UButton("Add torrents", {
                    try {
                        val links = magnets.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val hashes = links.map(::magnetHash)
                        require(links.isNotEmpty() || files.isNotEmpty()) { "Add at least one magnet link or .torrent file." }
                        require(hashes.toSet().size == hashes.size && Q.torrents.none { it.hash in hashes }) { "This torrent is already in the list." }
                        require(category.isEmpty() || category in Q.categories) { "Choose an existing category." }
                        val path = savePath.trim().ifEmpty { Q.categories[category]?.ifEmpty { null } ?: "/downloads" }
                        require(validPath(path)) { "Enter an absolute save path." }
                        val tagList = parseTags(tags)
                        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                            if (links.isNotEmpty()) addFormDataPart("urls", links.joinToString("\n"))
                            files.forEach { addFormDataPart("torrents", it.name, it.bytes.toRequestBody("application/x-bittorrent".toMediaType())) }
                            // `paused` is the 4.x name, `stopped` the 5.x one; autoTMM off so the chosen save path is honoured.
                            mapOf("category" to category, "savepath" to path, "tags" to tagList.joinToString(","), "paused" to !start, "stopped" to !start,
                                "addToTopOfQueue" to top, "sequentialDownload" to sequential, "firstLastPiecePrio" to firstLast, "autoTMM" to false,
                            ).forEach { (k, v) -> addFormDataPart(k, v.toString()) }
                        }.build()
                        val count = links.size + files.size
                        busy = true
                        scope.launch {
                            try {
                                val result = Api.call("qbittorrent", "torrents/add", "POST", body)?.toString()?.trim()
                                if (result == "Fails.") throw IllegalStateException("qBittorrent could not add these torrents.")
                                runCatching { Q.sync() }
                                onAdded(); onDismiss()
                                Toasts.show("$count torrent${if (count == 1) "" else "s"} added")
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { Toasts.fail(e) }
                            busy = false
                        }
                    } catch (e: IllegalArgumentException) { Toasts.fail(e) }
                }, Modifier.weight(1f), icon = Icons.Rounded.Add, busy = busy)
                UButton("Cancel", onDismiss, kind = ButtonKind.Ghost)
            }
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
    return out.toByteArray()
}
