package aj.unifiarr.app

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// ---------- Models (live.mjs / qbit.mjs mappers) ----------

private fun epCode(season: Int, episode: Int) = String.format(Locale.US, "S%02dE%02d", season, episode)
private fun arrPoster(images: JSONArray?) = images.objects().firstOrNull { it.str("coverType") == "poster" }?.str("remoteUrl").orEmpty()
private fun jfImage(id: String, tag: String?) = if (tag.isNullOrEmpty()) "" else "api/jellyfin/Items/$id/Images/Primary?maxHeight=480&tag=$tag"
private fun videoQuality(width: Int) = when { width >= 3200 -> "4K"; width >= 1800 -> "1080p"; width >= 1200 -> "720p"; width > 0 -> "SD"; else -> "" }
private fun tmdb(path: String) = if (path.isBlank()) "" else "https://image.tmdb.org/t/p/w342$path"
private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

private data class Media(val id: String, val title: String, val year: String, val type: String, val genre: String, val rating: String, val image: String, val quality: String, val added: String = "", val episode: String = "")

private fun mapItem(i: JSONObject) = Media(
    i.str("Id"), i.str("Name"), i.intOrNull("ProductionYear")?.toString().orEmpty(), if (i.str("Type") == "Series") "Series" else "Movie",
    i.optJSONArray("Genres").strings().firstOrNull().orEmpty(), i.optDouble("CommunityRating").takeIf { !it.isNaN() && it > 0 }?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
    jfImage(i.str("Id"), i.obj("ImageTags")?.strOrNull("Primary")), videoQuality(i.optInt("Width")), i.str("DateCreated"),
)

private data class Session(
    val id: String, val user: String, val device: String, val client: String, val remote: Boolean, val status: String, val position: Long, val duration: Long,
    val mode: String, val quality: String, val title: String, val image: String, val detail: String, val lastMessage: String, val at: Long,
)

private fun mapSession(s: JSONObject, previous: List<Session>): Session? {
    val n = s.obj("NowPlayingItem") ?: return null
    val play = s.obj("PlayState") ?: JSONObject()
    val episode = n.str("Type") == "Episode"
    return Session(
        id = s.str("Id"), user = s.strOrNull("UserName") ?: "Unknown", device = s.str("DeviceName"), client = s.str("Client"), remote = s.optBoolean("SupportsRemoteControl"),
        status = if (play.optBoolean("IsPaused")) "paused" else "playing", position = play.optLong("PositionTicks") / 10_000_000, duration = max(1, n.optLong("RunTimeTicks") / 10_000_000),
        mode = when (play.str("PlayMethod")) { "Transcode" -> "Transcoding"; "DirectStream" -> "Direct stream"; else -> "Direct play" }, quality = videoQuality(n.optInt("Width")),
        title = n.strOrNull("SeriesName") ?: n.str("Name"),
        image = if (episode) jfImage(n.str("SeriesId"), n.strOrNull("SeriesPrimaryImageTag")) else jfImage(n.str("Id"), n.obj("ImageTags")?.strOrNull("Primary")),
        detail = if (episode) "${epCode(n.optInt("ParentIndexNumber"), n.optInt("IndexNumber"))} · ${n.str("Name")}"
        else listOfNotNull(n.intOrNull("ProductionYear")?.toString(), n.optJSONArray("Genres").strings().firstOrNull()).joinToString(" · "),
        lastMessage = previous.firstOrNull { it.id == s.str("Id") }?.lastMessage.orEmpty(), at = System.currentTimeMillis(),
    )
}

/** Where playback is now: Jellyfin's last report plus the time since, while playing. */
private fun played(s: Session, now: Long = System.currentTimeMillis()) = minOf(s.duration, s.position + if (s.status == "playing") (now - s.at) / 1000 else 0)

/** A Sonarr/Radarr record: wanted, queue, history or calendar entry (live.mjs mapRecord). */
private data class Rec(
    val id: Int, val service: String, val titleId: Int, val title: String, val image: String, val detail: String, val date: String?,
    val release: String, val event: String, val quality: String, val size: Long?, val left: Long, val progress: Double, val timeleft: String,
    val status: String, val warning: Boolean, val messages: List<String>, val client: String, val hasFile: Boolean = false,
)

private fun mapRecord(service: String, r: JSONObject): Rec {
    val owner = r.obj("series") ?: r.obj("movie") ?: r
    val episode = r.obj("episode")
    val size = r.longOrNull("size")
    return Rec(
        id = r.optInt("id"), service = service, titleId = r.intOrNull("seriesId") ?: r.intOrNull("movieId") ?: r.optInt("id"),
        title = owner.strOrNull("title") ?: r.str("title"), image = arrPoster(owner.optJSONArray("images")),
        detail = when {
            episode != null -> "${epCode(episode.optInt("seasonNumber"), episode.optInt("episodeNumber"))} · ${episode.str("title")}"
            r.has("seasonNumber") && r.has("episodeNumber") -> "${epCode(r.optInt("seasonNumber"), r.optInt("episodeNumber"))} · ${r.str("title")}"
            else -> owner.intOrNull("year")?.toString().orEmpty()
        },
        date = r.strOrNull("airDateUtc") ?: r.strOrNull("date") ?: r.strOrNull("digitalRelease") ?: r.strOrNull("physicalRelease") ?: r.strOrNull("inCinemas"),
        release = r.strOrNull("sourceTitle") ?: (if (size != null) r.str("title") else ""), event = r.str("eventType"),
        quality = r.obj("quality")?.obj("quality")?.str("name").orEmpty(), size = size, left = r.optLong("sizeleft"),
        progress = if (size != null && size > 0) floor((1 - r.optLong("sizeleft").toDouble() / size) * 1000) / 10 else 0.0,
        timeleft = r.str("timeleft"), status = r.strOrNull("trackedDownloadState") ?: r.str("status"),
        warning = r.str("trackedDownloadStatus") in setOf("warning", "error"),
        messages = r.list("statusMessages").flatMap { it.optJSONArray("messages").strings() }, client = r.str("downloadClient"),
    )
}

private data class Req(val id: String, val title: String, val image: String, val requestStatus: String, val by: String, val date: String?)

private data class OverviewTorrent(
    val hash: String, val name: String, val category: String, val state: String, val label: String, val tone: String, val progress: Double,
    val size: Long, val done: Long, val dlspeed: Long, val upspeed: Long, val dlLimit: Long, val upLimit: Long, val eta: Long?, val queue: Int, val addedOn: Long,
)

private val qbitLabels = mapOf(
    "error" to "Error", "missingFiles" to "Missing files", "metaDL" to "Fetching metadata", "forcedMetaDL" to "[F] Fetching metadata", "stalledDL" to "Stalled",
    "stalledUP" to "Seeding", "checkingDL" to "Checking", "checkingUP" to "Checking", "checkingResumeData" to "Checking", "allocating" to "Allocating", "moving" to "Moving",
    "queuedDL" to "Queued", "queuedUP" to "Queued", "uploading" to "Seeding", "forcedUP" to "[F] Seeding", "downloading" to "Downloading", "forcedDL" to "[F] Downloading",
    "pausedDL" to "Paused", "pausedUP" to "Paused", "stoppedDL" to "Stopped", "stoppedUP" to "Stopped", "unknown" to "Unknown",
)

private fun mapTorrent(hash: String, t: JSONObject): OverviewTorrent {
    val raw = t.str("state")
    val stopped = raw.startsWith("paused") || raw.startsWith("stopped") || raw == "error" || raw == "missingFiles"
    val progress = floor(t.optDouble("progress", 0.0) * 1000) / 10
    val state = when { stopped -> "paused"; raw.startsWith("queued") -> "queued"; progress == 100.0 -> "seeding"; else -> "downloading" }
    val eta = t.optLong("eta", 8_640_000)
    return OverviewTorrent(
        hash, t.str("name"), t.str("category"), state, qbitLabels[raw] ?: raw, if (raw == "error" || raw == "missingFiles") "error" else state, progress,
        t.optLong("size"), t.optLong("completed"), t.optLong("dlspeed"), t.optLong("upspeed"), max(0, t.optLong("dl_limit")), max(0, t.optLong("up_limit")),
        if (eta >= 8_640_000) null else eta, max(0, t.optInt("priority")), t.optLong("added_on"),
    )
}

private val OverviewTorrent.running get() = state != "paused" && state != "queued"
private fun OverviewTorrent.speed(down: Boolean): Long = if (!running) 0 else (if (down) dlspeed else upspeed).let { s -> val limit = if (down) dlLimit else upLimit; if (limit > 0) minOf(s, limit) else s }

// Release names → library titles (model.mjs indexTitles / matchTitle), for torrent thumbnails.
private fun words(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(Regex("[\\u0300-\\u036f]"), "")
    .replace("&", " and ").replace("'", "").replace(Regex("[^a-z0-9]+"), " ").trim()
private val releaseStop = Regex("\\b(s\\d{1,2}(e\\d{1,3})?|season \\d+|complete|\\d{3,4}p|4k|uhd|bluray|bdrip|web ?dl|webrip|hdtv|x26[45]|h 26[45]|hevc|remux)\\b")
private fun matchTitle(name: String, index: Map<String, String>): String? {
    val text = words(name.replace(Regex("^\\s*[\\[(][^\\])]*[\\])]\\s*"), ""))
    val stop = releaseStop.find(text)?.range?.first ?: -1
    val head = if (stop < 0) text else text.substring(0, stop).trim()
    for (match in Regex("\\b(19|20)\\d\\d\\b").findAll(head)) {
        val title = head.substring(0, match.range.first).trim()
        val found = index["$title ${match.value}"] ?: index[title]
        if (title.isNotEmpty() && found != null) return found
    }
    return index[head]
}

// ---------- Loading ----------

private fun span(days: Long): Pair<Instant, Instant> {
    val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
    return start to start.plusSeconds(days * 86400)
}

private suspend fun calendar(service: String, days: Long): List<Rec> {
    val (start, end) = span(days)
    val sonarr = service == "sonarr"
    val list = Api.arr(service, "calendar?start=${enc(start.toString())}&end=${enc(end.toString())}&unmonitored=false${if (sonarr) "&includeSeries=true" else ""}")
    return list.objects().map { r ->
        val record = mapRecord(service, r).copy(hasFile = r.optBoolean("hasFile"))
        if (sonarr) record else {
            // Radarr lists a movie once per window; show the release that falls inside it.
            val dates = listOf("inCinemas", "digitalRelease", "physicalRelease").map { r.strOrNull(it) }
            val index = max(0, dates.indexOfFirst { d -> instant(d)?.let { it >= start && it <= end } == true })
            record.copy(date = dates[index], detail = listOf("In cinemas", "Digital release", "Physical release")[index])
        }
    }
}

private suspend fun eachArr(app: AppState, block: suspend (String) -> List<Rec>): List<Rec> = coroutineScope {
    app.arrs.filter(app::isLive).map { s -> async { runCatching { block(s) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
}

private val logTones = mapOf("Warning" to "pending", "Error" to "declined", "Critical" to "declined", "Fatal" to "declined")
private fun logRec(service: String, id: Int, title: String, detail: String, kind: String, date: String?) =
    Rec(id, service, 0, title, "", detail, date, "", kind, "", null, 0, 0.0, "", "", false, emptyList(), "")
private val qbitKinds = mapOf(1 to "Normal", 2 to "Info", 4 to "Warning", 8 to "Critical")
private suspend fun qbitLog() = Api.arr("qbittorrent", "log/main?normal=true&info=true&warning=true&critical=true&last_known_id=-1").objects().filter { !it.str("message").startsWith("WebAPI login") }.takeLast(60)
    .map { logRec("qbittorrent", it.optInt("id"), it.str("message"), "", qbitKinds[it.optInt("type")] ?: "Info", Instant.ofEpochSecond(it.optLong("timestamp")).toString()) }
private suspend fun jellyfinLog() = Api.obj("jellyfin", "System/ActivityLog/Entries?limit=40").list("Items")
    .map { logRec("jellyfin", it.optInt("Id"), it.str("Name"), it.str("ShortOverview"), it.str("Severity"), it.strOrNull("Date")) }

private fun include(service: String) = if (service == "sonarr") "includeSeries=true&includeEpisode=true" else "includeMovie=true"
private suspend fun history(service: String) = Api.obj(service, "history?pageSize=30&sortKey=date&sortDirection=descending&${include(service)}").list("records").map { mapRecord(service, it) }
private suspend fun queue(service: String) = Api.obj(service, "queue?pageSize=50&${include(service)}").list("records").map { mapRecord(service, it) }
private fun byDate(rec: Rec) = instant(rec.date)?.toEpochMilli() ?: 0L

private val seerrTitles = ConcurrentHashMap<String, JSONObject>()
private val requestStatuses = mapOf(1 to "pending", 2 to "approved", 3 to "declined", 4 to "failed", 5 to "completed")

private suspend fun seerrRequests(): List<Req> = coroutineScope {
    Api.obj("seerr", "request?take=50&skip=0&filter=all&sort=added").list("results").map { r ->
        async {
            val media = r.obj("media") ?: JSONObject()
            val kind = r.strOrNull("type") ?: media.str("mediaType")
            val key = "$kind-${media.optInt("tmdbId")}"
            val info = seerrTitles[key] ?: runCatching { Api.obj("seerr", "$kind/${media.optInt("tmdbId")}").also { seerrTitles[key] = it } }.getOrDefault(JSONObject())
            Req(key, info.strOrNull("title") ?: info.strOrNull("name") ?: "Unknown", tmdb(info.str("posterPath")), requestStatuses[r.optInt("status")] ?: "pending",
                r.obj("requestedBy")?.strOrNull("displayName") ?: "Unknown", r.strOrNull("createdAt"))
        }
    }.awaitAll()
}

private suspend fun qbitTorrents(): List<OverviewTorrent> {
    val raw = Api.obj("qbittorrent", "sync/maindata?rid=0").obj("torrents") ?: JSONObject()
    return raw.keys().asSequence().map { mapTorrent(it, raw.getJSONObject(it)) }.toList()
        .sortedWith(compareBy<OverviewTorrent> { if (it.queue > 0) it.queue else Int.MAX_VALUE }.thenByDescending { it.addedOn })
}

/** qBittorrent 5 renamed pause/resume to stop/start; 4.x only knows the old names. */
private suspend fun qbitToggle(t: OverviewTorrent) {
    val (modern, legacy) = if (t.state == "paused") "start" to "resume" else "stop" to "pause"
    val body = { FormBody.Builder().add("hashes", t.hash).build() }
    try { Api.call("qbittorrent", "torrents/$modern", "POST", body()) } catch (e: Api.Failure) { if (e.status == 404) Api.call("qbittorrent", "torrents/$legacy", "POST", body()) else throw e }
}

private suspend fun jellyfinLibrary(): List<Media> = coroutineScope {
    Api.arr("jellyfin", "Library/VirtualFolders").objects().filter { (it.strOrNull("CollectionType") ?: "mixed") in setOf("movies", "tvshows", "mixed") }.map { folder ->
        async {
            Api.obj("jellyfin", "Items?ParentId=${folder.str("ItemId")}&Recursive=true&IncludeItemTypes=Movie,Series&Fields=Genres,Width,DateCreated&SortBy=DateCreated&SortOrder=Descending&Limit=300")
                .list("Items").map(::mapItem)
        }
    }.awaitAll().flatten().let { items ->
        // Series bump to their newest episode so new episodes show in "Recently added".
        val episodes = Api.obj("jellyfin", "Items?Recursive=true&IncludeItemTypes=Episode&Fields=DateCreated&SortBy=DateCreated&SortOrder=Descending&Limit=50").list("Items")
        items.map { m ->
            val e = episodes.firstOrNull { it.str("SeriesId") == m.id }
            if (e != null && e.str("DateCreated") > m.added) m.copy(added = e.str("DateCreated"), episode = epCode(e.optInt("ParentIndexNumber"), e.optInt("IndexNumber"))) else m
        }
    }
}

private val historyEvents = mapOf(
    "grabbed" to Triple("Grabbed", "pending", Icons.Rounded.Download), "downloadFolderImported" to Triple("Imported", "approved", Icons.Rounded.Check),
    "downloadFailed" to Triple("Failed", "declined", Icons.Rounded.Close), "episodeFileDeleted" to Triple("Deleted", "", Icons.Rounded.Delete),
    "movieFileDeleted" to Triple("Deleted", "", Icons.Rounded.Delete), "episodeFileRenamed" to Triple("Renamed", "", Icons.Rounded.Edit),
    "movieFileRenamed" to Triple("Renamed", "", Icons.Rounded.Edit), "downloadIgnored" to Triple("Ignored", "", Icons.Rounded.Block),
    "seriesFolderImported" to Triple("Imported", "approved", Icons.Rounded.Check), "movieFolderImported" to Triple("Imported", "approved", Icons.Rounded.Check),
)

private data class FeedEvent(val title: String, val detail: String, val service: String, val date: String?)
private data class Pending(val status: String, val until: Long)
private data class ArrWarning(val service: String, val severity: String, val message: String)

private suspend fun arrWarnings(app: AppState): List<ArrWarning> = coroutineScope {
    app.arrs.filter(app::isLive).map { service ->
        async {
            val health = Api.arr(service, "health").objects()
                .filter { it.str("type").lowercase() in setOf("warning", "error") }
                .map { ArrWarning(service, it.str("type").lowercase(), it.str("message")) }
            val disks = Api.arr(service, "diskspace").objects().mapNotNull { disk ->
                val free = disk.longOrNull("freeSpaceBytes") ?: return@mapNotNull null
                val total = disk.longOrNull("totalSpaceBytes") ?: 0L
                if (free > 10L * 1024 * 1024 * 1024 && (total <= 0 || free.toDouble() / total >= .1)) null
                else ArrWarning(service, "warning", "Low disk space: ${bytes(free)} free on ${disk.strOrNull("path") ?: disk.strOrNull("label") ?: "volume"}")
            }
            health + disks
        }
    }.awaitAll().flatten()
}

/** Overview data lives here so coming back to the page shows the last state instantly (then refreshes). */
private object Home {
    var media by mutableStateOf<List<Media>?>(null)
    var sessions by mutableStateOf<List<Session>>(emptyList())
    var torrents by mutableStateOf<List<OverviewTorrent>?>(null)
    var requests by mutableStateOf<List<Req>?>(null)
    var warnings by mutableStateOf<List<ArrWarning>?>(null)
    var calendar by mutableStateOf<List<Rec>?>(null)
    var feed by mutableStateOf<List<FeedEvent>?>(null)
    var server: String? = null
    /** Commands Jellyfin accepted but the player hasn't reported back yet; kept for up to 10s so the card doesn't flip back. */
    val pending = HashMap<String, Pending>()

    fun reset(server: String?) {
        if (this.server == server) return
        this.server = server; media = null; sessions = emptyList(); torrents = null; requests = null; warnings = null; calendar = null; feed = null; pending.clear()
    }

    fun playing(list: List<Session>): List<Session> {
        val now = System.currentTimeMillis()
        return list.mapNotNull { s ->
            val wanted = pending[s.id]
            when {
                wanted == null || wanted.until < now || wanted.status == s.status -> { pending.remove(s.id); s }
                wanted.status == "stopped" -> null
                else -> s.copy(position = played(s, now), status = wanted.status, at = now)
            }
        }.sortedWith(compareBy({ it.user.lowercase() }, { it.id })) // Jellyfin orders by last activity, which reshuffles cards
    }

    suspend fun loadSessions(app: AppState) {
        if (!app.isLive("jellyfin")) return
        val previous = sessions
        sessions = playing(Api.arr("jellyfin", "Sessions?ActiveWithinSeconds=960").objects().mapNotNull { mapSession(it, previous) })
    }

    suspend fun loadTorrents(app: AppState) {
        if (!app.has("qbittorrent")) return
        torrents = try { qbitTorrents() } catch (e: Exception) { if (torrents == null) emptyList() else throw e }
        Badges.downloading = torrents.orEmpty().count { it.progress < 100 }
    }

    suspend fun loadLibrary(app: AppState) { if (app.isLive("jellyfin")) media = jellyfinLibrary() else if (media == null) media = emptyList() }
    suspend fun loadWarnings(app: AppState) { warnings = arrWarnings(app) }

    /** The web's loadView('overview'): sessions, activity feed, this week's calendar and requests. */
    suspend fun load(app: AppState) = coroutineScope {
        val s = async { runCatching { loadSessions(app) } }
        val log = async { if (app.isLive("jellyfin")) runCatching { Api.obj("jellyfin", "System/ActivityLog/Entries?limit=40").list("Items") }.getOrNull() else null }
        val hist = async { eachArr(app, ::history) }
        val qlog = async { if (app.isLive("qbittorrent")) runCatching { qbitLog() }.getOrDefault(emptyList()) else emptyList() }
        val cal = async { eachArr(app) { calendar(it, 7) } }
        val req = async { if (app.isLive("seerr")) runCatching { seerrRequests() }.getOrNull() else null }
        s.await()
        req.await()?.let { requests = it; Badges.pendingRequests = it.count { r -> r.requestStatus == "pending" } } ?: run { if (requests == null && !app.isLive("seerr")) requests = emptyList() }
        feed = (hist.await().map { FeedEvent("${historyEvents[it.event]?.first ?: "Updated"} · ${it.title}", it.detail, it.service, it.date) } +
            log.await().orEmpty().map { FeedEvent(it.str("Name"), it.str("ShortOverview"), "jellyfin", it.strOrNull("Date")) } +
            qlog.await().map { FeedEvent(it.title, if (it.event in setOf("Warning", "Critical")) it.event else "", "qbittorrent", it.date) })
            .sortedByDescending { instant(it.date)?.toEpochMilli() ?: 0 }.take(6)
        calendar = cal.await().sortedBy(::byDate)
    }
}

// ---------- Shared bits ----------

private sealed interface Open {
    data class Jelly(val id: String) : Open
    data class OverviewTorrent(val hash: String) : Open
    data class Seerr(val id: String) : Open
    data class Arr(val service: String, val id: Int) : Open
}

@Composable
private fun OpenSheet(open: Open?, onDismiss: () -> Unit) {
    when (open) {
        is Open.Jelly -> JellyfinItemSheet(open.id, onDismiss)
        is Open.OverviewTorrent -> TorrentSheet(open.hash, onDismiss)
        is Open.Seerr -> SeerrTitleSheet(open.id, onDismiss)
        is Open.Arr -> ArrTitleSheet(open.service, open.id, onDismiss)
        null -> {}
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 44.dp).clip(RoundSmall).clickable(onClick = onClick).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = C.accent)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = C.accent, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun Count(n: Any) = Text(" $n", style = Type.title, color = C.mute)

private val typeOptions = listOf("All" to "All", "Movie" to "Movies", "Series" to "TV shows")

@Composable
private fun TypeTabs(type: String, onChange: (String) -> Unit) = Segmented(typeOptions.map { it.second }, typeOptions.indexOfFirst { it.first == type }, { onChange(typeOptions[it].first) })

private fun isType(type: String, service: String) = type == "All" || (type == "Series") == (service == "sonarr")

/** Shows offline services among [ids] like the web banner(). */
@Composable
private fun Banner(app: AppState, ids: List<String>) {
    val off = ids.filter { app.has(it) && !app.isLive(it) }
    if (off.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(Round).background(C.warn.copy(alpha = .1f)).border(1.dp, C.warn.copy(alpha = .35f), Round).padding(14.dp)) {
        Text("Can’t reach ${off.joinToString(" and ") { serviceNames[it] ?: it }}.", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = C.ink)
        Text("Check that it’s running, or update its address in Services.", style = Type.small, color = C.mute)
        TextLink("Services") { app.go(Screen.Services) }
    }
}

@Composable
private fun ServiceTag(id: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServiceLogo(id, 16.dp)
        Spacer(Modifier.width(5.dp))
        Text(serviceNames[id] ?: id, style = Type.tiny, color = C.mute)
    }
}

private fun mixedTone(tone: String, palette: Palette) = when (tone) { "approved", "completed" -> palette.ok; "pending" -> palette.warn; "declined", "failed" -> palette.danger; else -> palette.mute }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LazyPage(onRefresh: suspend () -> Unit, content: LazyListScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(refreshing, { scope.launch { refreshing = true; runCatching { onRefresh() }; refreshing = false } }, Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

// ---------- Overview ----------

private val serviceDescriptions = mapOf(
    "jellyfin" to "Your personal cinema", "sonarr" to "TV, on autopilot", "radarr" to "Movie night, sorted", "seerr" to "Discover. Request. Enjoy.", "qbittorrent" to "Every byte, in its place",
)

@Composable
fun OverviewScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Open?>(null) }
    var adding by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("All") }
    Home.reset(app.server)
    LaunchedEffect(app.config, app.live) {
        launch { runCatching { Home.loadTorrents(app) } }
        launch { runCatching { Home.loadLibrary(app) }.onFailure { if (Home.media == null) Home.media = emptyList(); Toasts.fail(it) } }
        Home.load(app)
    }
    // Same cadence as the web: page data every 5s, torrents as they change (qBittorrent pushes each second on the web).
    Every(5000, app.config, app.live) { if (app.has("jellyfin") || app.has("seerr") || app.arrs.isNotEmpty()) Home.load(app) }
    Every(2000, app.config) { Home.loadTorrents(app) }
    LaunchedEffect(app.config, app.live) { runCatching { Home.loadWarnings(app) }.onFailure(Toasts::fail) }
    Every(60_000, app.config, app.live) { runCatching { Home.loadWarnings(app) }.onFailure(Toasts::fail) }

    val hour = java.time.LocalTime.now().hour
    val greeting = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    val media = Home.media
    val active = Home.torrents.orEmpty().filter { it.progress < 100 }
    val mine = serviceNames.keys.filter(app::has)

    Page(onRefresh = {
        app.checkHealth()
        coroutineScope {
            launch { runCatching { Home.loadTorrents(app) } }
            launch { runCatching { Home.loadLibrary(app) }.onFailure(Toasts::fail) }
            launch { runCatching { Home.loadWarnings(app) }.onFailure(Toasts::fail) }
            Home.load(app)
        }
    }) {
        PageHeading(greeting, "Your media universe, all in one place.", "Your personal command center")
        if (!Home.warnings.isNullOrEmpty()) {
            SectionTitle("Service warnings")
            Panel(padding = 12.dp) {
                Home.warnings.orEmpty().forEachIndexed { index, warning ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        ServiceLogo(warning.service, 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${serviceNames[warning.service]} · ${warning.severity.replaceFirstChar { it.uppercase() }}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (warning.severity == "error") C.danger else C.warn)
                            Text(warning.message, style = Type.small, color = C.mute)
                        }
                    }
                }
            }
        }
        val stats = buildList<@Composable () -> Unit> {
            if (app.has("jellyfin")) add { Stat(Icons.Rounded.VideoLibrary, "In your library", media?.size?.toString() ?: "—", "titles", "${media.orEmpty().count { it.type == "Movie" }} movies · ${media.orEmpty().count { it.type == "Series" }} TV shows") }
            if (app.has("qbittorrent")) add { Stat(Icons.Rounded.Download, "Download queue", "${active.size}", "in queue", "${active.count { it.running }} downloading · ${active.count { it.state == "paused" }} paused") }
            if (app.has("seerr")) add { Stat(Icons.Rounded.Inbox, "Requests", Home.requests?.count { it.requestStatus == "pending" }?.toString() ?: "—", "pending", "${Home.requests?.size ?: 0} recent in Seerr") }
            if (app.arrs.isNotEmpty()) add { Stat(Icons.Rounded.CalendarMonth, "Coming up", Home.calendar?.size?.toString() ?: "—", "this week", "Episodes and movie releases") }
            add { val on = mine.count(app::isLive); Stat(Icons.Rounded.Settings, "Your services", "${mine.size}", "connected", if (on == mine.size) "All services live" else "${mine.size - on} offline") }
        }
        stats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { it() } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (app.has("jellyfin")) NowPlaying(app)

        if (app.has("qbittorrent")) {
            SectionTitle("Downloading", trailing = { Pill("${active.size}"); Spacer(Modifier.width(6.dp)); TextLink("All downloads") { app.go(Screen.Downloads) } })
            val index = remember(media, Home.requests) {
                buildMap {
                    (media.orEmpty().map { it.title to it.year to it.image } + Home.requests.orEmpty().map { it.title to "" to it.image }).forEach { (ty, image) ->
                        val key = words(ty.first)
                        if (image.isEmpty() || key.isEmpty()) return@forEach
                        if (key !in this) put(key, image)
                        if (ty.second.isNotEmpty()) put("$key ${ty.second}", image)
                    }
                }
            }
            Panel(padding = 0.dp) {
                active.take(3).forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                    TorrentRow(t, matchTitle(t.name, index), onOpen = { open = Open.OverviewTorrent(t.hash) }, onToggle = {
                        scope.act(if (t.state == "paused") "Torrent resumed" else "Torrent paused") { qbitToggle(t); Home.loadTorrents(app) }
                    })
                }
                if (active.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val live = app.isLive("qbittorrent")
                    Box(Modifier.size(52.dp).clip(CircleShape).background(C.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Icon(if (live) Icons.Rounded.DownloadDone else Icons.Rounded.CloudOff, null, tint = if (live) C.accent else C.mute, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (live) "Your queue is clear." else "qBittorrent is offline.", style = Type.subtitle)
                    Text(if (live) "Nothing downloading right now." else "Check the connection in Settings.", style = Type.small, color = C.mute)
                    if (live) { Spacer(Modifier.height(12.dp)); UButton("Add torrent", { adding = true }, icon = Icons.Rounded.Add, kind = ButtonKind.Ghost) }
                }
            }
        }

        val recent = media.orEmpty().filter { it.added.isNotEmpty() }.sortedByDescending { it.added }.take(12)
        if (app.has("jellyfin") && recent.isNotEmpty()) {
            SectionTitle("Recently added", trailing = { TextLink("Your library") { app.go(Screen.Library) } })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                recent.forEach { MediaCard(it, Modifier.width(112.dp), "${it.episode.ifEmpty { "Added" }} ${ago(it.added).lowercase()}") { open = Open.Jelly(it.id) } }
            }
        }

        if (app.has("jellyfin")) {
            SectionTitle("Ready to watch", trailing = { TextLink("Your library") { app.go(Screen.Library) } })
            TypeTabs(type) { type = it }
            Spacer(Modifier.height(12.dp))
            val available = media.orEmpty().filter { type == "All" || it.type == type }
            when {
                media == null -> Loader("Loading your library")
                available.isEmpty() -> Empty("Nothing in your library yet.")
                else -> available.take(6).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { MediaCard(it, Modifier.weight(1f)) { open = Open.Jelly(it.id) } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (app.arrs.isNotEmpty() || app.has("jellyfin") || app.has("qbittorrent")) {
            SectionTitle("Activity", trailing = { TextLink("View all") { app.go(Screen.Activity) } })
            val feed = Home.feed
            if (feed.isNullOrEmpty()) Empty(if (feed == null) "Loading activity…" else "Nothing has happened yet.")
            else Panel { feed.forEachIndexed { i, e -> FeedRow(e, last = i == feed.lastIndex) } }
        }

        if (app.has("seerr")) {
            SectionTitle("Requests", trailing = { TextLink("All requests") { app.go(Screen.Requests) } })
            val requests = Home.requests
            if (requests.isNullOrEmpty()) Empty(if (requests == null) "Loading…" else "No requests yet.")
            else Panel(padding = 6.dp) {
                requests.take(4).forEach { r ->
                    ListRow(r.image, r.title, "${r.by} · ${ago(r.date)}", { open = Open.Seerr(r.id) }) { RequestBadge(r.requestStatus) }
                }
            }
        }

        if (app.arrs.isNotEmpty()) {
            SectionTitle("Coming up", trailing = { TextLink("Calendar") { app.go(Screen.Calendar) } })
            val upcoming = Home.calendar
            if (upcoming.isNullOrEmpty()) Empty(if (upcoming == null) "Loading…" else "Nothing scheduled this week.")
            else Panel(padding = 6.dp) {
                upcoming.take(4).forEach { r ->
                    ListRow(r.image, r.title, "${r.detail} · ${shortDay(r.date)}", { open = Open.Arr(r.service, r.titleId) }) { ServiceLogo(r.service, 20.dp) }
                }
            }
        }

        SectionTitle("Your services", trailing = { TextLink("Manage") { app.go(Screen.Services) } })
        Panel(padding = 6.dp) {
            mine.forEach { id ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundSmall).clickable { app.go(Screen.Services) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServiceLogo(id, 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(serviceNames[id] ?: id, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
                        Text(serviceDescriptions[id].orEmpty(), style = Type.small, color = C.mute)
                    }
                    Pill(if (app.isLive(id)) "Live" else "Offline", if (app.isLive(id)) C.service(id) else C.warn, dot = true)
                }
            }
        }
    }
    if (adding) AddTorrentSheet({ adding = false }) { scope.launch { runCatching { Home.loadTorrents(app) } } }
    OpenSheet(open) { open = null }
}

private fun shortDay(iso: String?) = instant(iso)?.let { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(it) } ?: "—"

@Composable
private fun Stat(icon: ImageVector, label: String, value: String, unit: String, note: String) {
    Panel(Modifier.fillMaxSize(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.mute, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = Type.small, color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 6.dp, bottom = 3.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.stat.copy(fontSize = 26.sp, lineHeight = 30.sp))
            Spacer(Modifier.width(6.dp))
            Text(unit, style = Type.small.copy(fontSize = 11.sp), color = C.mute, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(note, style = Type.small.copy(fontSize = 11.sp), color = C.mute)
    }
}

@Composable
private fun MediaCard(item: Media, modifier: Modifier, label: String = "In your library", onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Poster(item.image, Modifier.matchParentSize(), fallback = Icons.Rounded.Movie)
            if (item.quality.isNotEmpty()) Text(item.quality, style = Type.tiny, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 5.dp, vertical = 2.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF73CFAD)))
                Spacer(Modifier.width(4.dp))
                Text(label, style = Type.tiny.copy(fontSize = 9.sp), color = Color.White, maxLines = 1)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row {
            Text("${item.year} · ${if (item.type == "Series") "TV Series" else "Movie"}", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 1, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
            Text("★ ${item.rating}", style = Type.tiny, color = C.warn)
        }
    }
}

@Composable
private fun FeedRow(e: FeedEvent, last: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 16.dp)) {
        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(C.service(e.service)))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
            if (e.detail.isNotEmpty()) Text(e.detail, style = Type.small.copy(fontSize = 11.sp), color = C.mute)
            Text(ago(e.date), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute)
        }
    }
}

@Composable
private fun ListRow(image: String, title: String, meta: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundSmall).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(image, Modifier.size(38.dp, 56.dp), RoundedCornerShape(6.dp), fallback = Icons.Rounded.Movie)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = Type.small, color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun RequestBadge(status: String) = Pill(
    mapOf("pending" to "Pending", "approved" to "Approved", "declined" to "Declined", "failed" to "Failed", "completed" to "Completed")[status] ?: status, mixedTone(status, C),
)

@Composable
private fun TorrentRow(t: OverviewTorrent, poster: String?, onOpen: () -> Unit, onToggle: () -> Unit) {
    val color = when (t.tone) { "error" -> C.danger; "paused" -> C.mute; "queued" -> C.warn; "seeding" -> C.ok; else -> C.qbittorrent }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (poster != null) Poster(poster, Modifier.size(40.dp, 60.dp), RoundedCornerShape(6.dp))
        else Box(Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(6.dp)).background(C.soft), contentAlignment = Alignment.Center) {
            Icon(if (t.size == 0L) Icons.Rounded.Link else Icons.Rounded.Folder, null, tint = C.mute, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(t.label, color)
                if (t.category.isNotEmpty()) Pill(t.category)
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressLine((t.progress / 100).toFloat(), Modifier.weight(1f), color)
                Spacer(Modifier.width(8.dp))
                Text("${t.progress}%", style = Type.tiny)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "${bytes(t.done)} of ${bytes(t.size)} · ↓ ${speed(t.speed(true))} · ↑ ${speed(t.speed(false))} · ETA ${if (t.progress >= 100) "—" else if (t.running) duration(t.eta) else "∞"}",
                style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (t.queue > 0) Text("#${t.queue}", style = Type.tiny, color = C.mute)
            IconAction(if (t.state == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (t.state == "paused") "Resume ${t.name}" else "Pause ${t.name}", onToggle)
            IconAction(Icons.Rounded.Info, "Details for ${t.name}", onOpen, tint = C.mute)
        }
    }
}

// ---------- Now playing ----------

@Composable
private fun NowPlaying(app: AppState) {
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf<Session?>(null) }
    var messaging by remember { mutableStateOf<Session?>(null) }
    val sessions = Home.sessions

    fun command(s: Session, operation: String) = scope.act {
        Api.call("jellyfin", "Sessions/${s.id}/Playing/${mapOf("play" to "Unpause", "pause" to "Pause", "stop" to "Stop")[operation]}", "POST")
        Home.pending[s.id] = Pending(mapOf("play" to "playing", "pause" to "paused", "stop" to "stopped")[operation]!!, System.currentTimeMillis() + 10_000)
        Home.sessions = Home.playing(Home.sessions)
        Toasts.show(mapOf("play" to "Playback resumed", "pause" to "Playback paused", "stop" to "Playback stopped")[operation]!!)
    }

    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(C.jelly.copy(alpha = .15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = C.jelly, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("Now playing", style = Type.title)
        Count(sessions.size)
        Spacer(Modifier.weight(1f))
        ServiceLogo("jellyfin", 18.dp)
        Spacer(Modifier.width(6.dp))
        Pill(if (app.isLive("jellyfin")) "Live" else "Offline", if (app.isLive("jellyfin")) C.ok else C.mute, dot = true)
    }
    if (sessions.isEmpty()) {
        Column(Modifier.fillMaxWidth().clip(Round).border(1.dp, C.line, Round).padding(vertical = 26.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PlayCircleOutline, null, tint = C.mute, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(8.dp))
            Text("A quiet moment on your server.", style = Type.subtitle)
            Text("No active sessions. Your next movie night starts in the library.", style = Type.small, color = C.mute)
            TextLink("Browse library") { app.go(Screen.Library) }
        }
    } else sessions.forEach { s ->
        key(s.id) { SessionCard(s, onToggle = { command(s, if (s.status == "paused") "play" else "pause") }, onStop = { stopping = s }, onMessage = { messaging = s }) }
        Spacer(Modifier.height(12.dp))
    }

    stopping?.let { s ->
        Confirm("Stop this session?", "This will end ${s.title} for ${s.user} on ${s.device}.", "Stop playback", { command(s, "stop") }, { stopping = null })
    }
    messaging?.let { s ->
        var text by remember(s.id) { mutableStateOf("") }
        var busy by remember(s.id) { mutableStateOf(false) }
        Sheet({ messaging = null }, "A message for ${s.user}", "To ${s.device} · ${s.client}") {
            Field("Your message", text, { if (it.length <= 500) text = it }, placeholder = "Let them know about a server restart, or just say hello…", singleLine = false, minLines = 4,
                note = "Shows on their screen for 15 seconds. Some apps don’t display messages. Maximum 500 characters.")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UButton("Send message", {
                    val message = text.trim()
                    if (message.isEmpty()) return@UButton Toasts.show("Enter a message between 1 and 500 characters.", true)
                    busy = true
                    scope.launch {
                        try {
                            Api.call("jellyfin", "Sessions/${s.id}/Message", "POST", mapOf("Header" to "Unifiarr", "Text" to message, "TimeoutMs" to 15000))
                            Home.sessions = Home.sessions.map { if (it.id == s.id) it.copy(lastMessage = message) else it }
                            messaging = null
                            Toasts.show("Message sent")
                        } catch (e: Exception) { Toasts.fail(e) }
                        busy = false
                    }
                }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Send, busy = busy)
                UButton("Cancel", { messaging = null }, kind = ButtonKind.Ghost)
            }
        }
    }
}

@Composable
private fun SessionCard(s: Session, onToggle: () -> Unit, onStop: () -> Unit, onMessage: () -> Unit) {
    val paused = s.status == "paused"
    val panel = C.panel
    Box(Modifier.fillMaxWidth().clip(Round).background(C.panel).border(1.dp, C.line, Round)) {
        Poster(s.image, Modifier.matchParentSize().graphicsLayer { alpha = .2f; scaleX = 1.3f; scaleY = 1.3f }.then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(34.dp) else Modifier), RoundedCornerShape(0.dp))
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.2f to Color.Transparent, 0.85f to panel)))
        Column(Modifier.padding(14.dp)) {
            Row {
                Box(Modifier.width(96.dp).aspectRatio(2f / 3f).graphicsLayer { alpha = if (paused) .85f else 1f }) {
                    Poster(s.image, Modifier.matchParentSize(), RoundedCornerShape(9.dp), fallback = Icons.Rounded.Movie)
                    if (paused) Box(Modifier.matchParentSize().clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = .15f)))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (paused) "Paused" else "Playing", if (paused) C.mute else C.ok, dot = true)
                        Spacer(Modifier.weight(1f))
                        if (s.quality.isNotEmpty()) Pill(s.quality, C.ink)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(s.title, style = Type.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.detail, style = Type.small, color = C.mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
                            Text(s.user.take(1).uppercase(), style = Type.small.copy(fontWeight = FontWeight.Bold), color = C.accent)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(s.user, style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                            Text("${s.device} · ${s.client}", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SessionTiming(s)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UButton(if (paused) "Resume" else "Pause", onToggle, Modifier.weight(1f), if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) ButtonKind.Primary else ButtonKind.Soft, enabled = s.remote)
                UButton("", onStop, Modifier.semantics { contentDescription = "Stop" }, Icons.Rounded.Stop, ButtonKind.Ghost, enabled = s.remote)
                UButton("", onMessage, Modifier.semantics { contentDescription = "Send message" }, Icons.Rounded.ChatBubbleOutline, ButtonKind.Ghost, enabled = s.remote)
            }
            if (!s.remote) Text("This app doesn’t accept remote control.", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, modifier = Modifier.padding(top = 6.dp))
            if (s.lastMessage.isNotEmpty()) Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundSmall).background(C.soft).padding(10.dp)) {
                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = C.mute, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Last message", style = Type.tiny)
                    Text(s.lastMessage, style = Type.small, color = C.mute)
                }
            }
        }
    }
}

/** Ticks every second between Jellyfin polls; only this clock and bar recompose, not the whole card. */
@Composable
private fun SessionTiming(s: Session) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val visible = LocalAppVisible.current
    if (s.status == "playing" && visible) LaunchedEffect(s) { while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) } }
    val position = played(s, maxOf(now, s.at))
    ProgressLine(position.toFloat() / s.duration, color = if (s.status == "paused") C.mute else C.jelly)
    Spacer(Modifier.height(6.dp))
    Row {
        Text(clock(position), style = Type.small.copy(fontWeight = FontWeight.SemiBold))
        Text(" / ${clock(s.duration)}", style = Type.small, color = C.mute, modifier = Modifier.weight(1f))
        Text(s.mode, style = Type.small, color = C.mute)
    }
}

// ---------- Wanted ----------

private data class WantedPage(val total: Int, val records: List<Rec>)

private suspend fun wanted(service: String, kind: String): WantedPage {
    val sonarr = service == "sonarr"
    val page = Api.obj(service, "wanted/$kind?pageSize=50&monitored=true&sortKey=${if (sonarr) "episodes.airDateUtc" else "movieMetadata.sortTitle"}&sortDirection=descending&${if (sonarr) "includeSeries=true" else ""}")
    return WantedPage(page.optInt("totalRecords"), page.list("records").map { mapRecord(service, it) })
}

@Composable
fun WantedScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("missing") }
    var type by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<Open?>(null) }
    var releases by remember { mutableStateOf<Pair<Rec, String>?>(null) }
    val label = mapOf("missing" to "Missing", "cutoff" to "Cutoff unmet")
    suspend fun fetch(): Map<String, Map<String, WantedPage?>> = coroutineScope {
        app.arrs.filter(app::isLive).associateWith { s -> listOf("missing", "cutoff").associateWith { k -> async { runCatching { wanted(s, k) }.onFailure(Toasts::fail).getOrNull() } } }
            .mapValues { (_, m) -> m.mapValues { it.value.await() } }
    }
    val data = load(app.config, app.live) { fetch() }
    val all = data.value
    fun total(k: String) = all?.values?.sumOf { it[k]?.total ?: 0 }
    val records = all?.values?.flatMap { it[kind]?.records.orEmpty() }?.filter { isType(type, it.service) && it.title.contains(app.query.trim(), true) }

    LazyPage({ data.value = fetch() }) {
        item {
            PageHeading("Wanted", "Aired episodes and movies you don’t have yet, and files below your quality cutoff.") {
                UButton("Search all ${label[kind]!!.lowercase()}", {
                    val which = kind
                    val targets = app.arrs.filter { (all?.get(it)?.get(which)?.total ?: 0) > 0 }
                    if (targets.isEmpty()) return@UButton Toasts.show("Nothing to search for.")
                    targets.forEach { s ->
                        val name = if (s == "sonarr") (if (which == "missing") "MissingEpisodeSearch" else "CutoffUnmetEpisodeSearch") else (if (which == "missing") "MissingMoviesSearch" else "CutOffUnmetMoviesSearch")
                        scope.act("${serviceNames[s]} is searching for all ${if (which == "missing") "missing" else "cutoff unmet"} items") { Api.call(s, "command", "POST", mapOf("name" to name)); data.value = fetch() }
                    }
                }, icon = Icons.Rounded.Search)
            }
            Banner(app, listOf("sonarr", "radarr"))
            Segmented(label.map { (k, v) -> "$v ${total(k) ?: "…"}" }, label.keys.indexOf(kind), { kind = label.keys.elementAt(it) })
            Spacer(Modifier.height(10.dp))
            TypeTabs(type) { type = it }
            Spacer(Modifier.height(6.dp))
        }
        when {
            records == null -> item { if (data.error != null) ErrorBox(data.error!!, data::reload) else Loader("Loading wanted items") }
            records.isEmpty() -> item { Empty(if (kind == "missing") "Nothing missing. Every monitored episode and movie is on disk." else "Everything meets its quality cutoff.") }
            else -> {
                items(records, key = { "${it.service}-${it.id}" }) { r ->
                    Panel(padding = 12.dp, onClick = { open = Open.Arr(r.service, r.titleId) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Poster(r.image, Modifier.size(44.dp, 66.dp), RoundedCornerShape(7.dp), fallback = Icons.Rounded.Movie)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.detail + (r.date?.let { " · ${if (r.service == "sonarr") "Aired" else "Released"} ${day(it)}" } ?: ""), style = Type.small, color = C.mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(5.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill(label[kind]!!, if (kind == "missing") C.danger else C.warn)
                                    ServiceTag(r.service)
                                }
                            }
                            IconAction(Icons.Rounded.Search, "Search for ${r.title} ${r.detail}", {
                                val sonarr = r.service == "sonarr"
                                scope.act("Searching for ${r.title} ${r.detail}") {
                                    Api.call(r.service, "command", "POST", if (sonarr) mapOf("name" to "EpisodeSearch", "episodeIds" to listOf(r.id)) else mapOf("name" to "MoviesSearch", "movieIds" to listOf(r.id)))
                                    data.value = fetch()
                                }
                            })
                            IconAction(Icons.AutoMirrored.Rounded.ManageSearch, "Interactive search for ${r.title} ${r.detail}", { releases = r to "${r.title} · ${r.detail}" })
                        }
                    }
                }
                val t = total(kind) ?: 0
                if (records.size < t) item { Text("Showing the newest ${records.size} of $t.", style = Type.small, color = C.mute) }
            }
        }
    }
    OpenSheet(open) { open = null }
    releases?.let { (r, title) -> ReleasesSheet(r.service, if (r.service == "sonarr") "episodeId=${r.id}" else "movieId=${r.id}", title) { releases = null } }
}

private data class Release(val guid: String, val indexerId: Int, val title: String, val indexer: String, val size: Long, val age: Double, val seeders: Int?, val leechers: Int?, val quality: String, val protocol: String, val rejections: List<String>, val approved: Boolean)

/** Interactive search (web showReleases): every release the indexers return, grab one. */
@Composable
private fun ReleasesSheet(service: String, target: String, title: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val grabbed = remember { mutableStateListOf<String>() }
    val list = load(target) {
        Api.arr(service, "release?$target").objects().map { r ->
            Release(r.str("guid"), r.optInt("indexerId"), r.str("title"), r.str("indexer"), r.optLong("size"), r.optDouble("ageHours").takeIf { !it.isNaN() } ?: (r.optDouble("age", 0.0) * 24),
                r.intOrNull("seeders"), r.intOrNull("leechers"), r.obj("quality")?.obj("quality")?.str("name").orEmpty(), r.str("protocol"),
                r.optJSONArray("rejections")?.let { a -> (0 until a.length()).map { i -> a.optJSONObject(i)?.str("reason") ?: a.optString(i) } }.orEmpty(), r.optBoolean("approved"))
        }.sortedByDescending { it.approved }
    }
    val releases = list.value
    Sheet(onDismiss, title, scroll = false, subtitle = "${serviceNames[service]?.uppercase()} · INTERACTIVE SEARCH · " + when {
        releases != null -> "${releases.size} releases · ${releases.count { it.approved }} match your profile"
        list.error != null -> "Search failed"
        else -> "Searching your indexers… this can take a minute."
    }) {
        when {
            list.error != null && releases == null -> Empty(list.error!!)
            releases == null -> Loader("Searching indexers")
            releases.isEmpty() -> Empty("No releases found. Check that your indexers are set up in ${serviceNames[service]}.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(releases, key = { it.guid + it.indexerId }) { r ->
                    Column(Modifier.fillMaxWidth().clip(RoundSmall).background(C.soft).graphicsLayer { alpha = if (r.approved) 1f else .7f }.padding(12.dp)) {
                        Text(r.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold))
                        if (r.rejections.isNotEmpty()) Text(r.rejections.joinToString(" · "), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.danger)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${r.indexer} · ${bytes(r.size)} · ${if (r.age < 48) "${r.age.roundToInt()} h" else "${(r.age / 24).roundToInt()} d"}", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute)
                                Text(if (r.protocol == "torrent") "${r.seeders ?: 0} / ${r.leechers ?: 0} peers" else "Usenet", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute)
                                if (r.quality.isNotEmpty()) Pill(r.quality, C.service(service), Modifier.padding(top = 4.dp))
                            }
                            val sent = r.guid in grabbed
                            UButton(if (sent) "Sent" else "Grab", {
                                grabbed.add(r.guid)
                                scope.launch {
                                    try { Api.call(service, "release", "POST", mapOf("guid" to r.guid, "indexerId" to r.indexerId)); Toasts.show("Sent to your download client") }
                                    catch (e: Exception) { grabbed.remove(r.guid); Toasts.fail(e) }
                                }
                            }, icon = if (sent) Icons.Rounded.Check else Icons.Rounded.Download, kind = if (r.approved) ButtonKind.Primary else ButtonKind.Ghost, enabled = !sent)
                        }
                    }
                }
                item { UButton("Close", onDismiss, Modifier.fillMaxWidth().padding(top = 6.dp), kind = ButtonKind.Ghost) }
            }
        }
    }
}

// ---------- Activity ----------

@Composable
fun ActivityScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    val sources = listOf("sonarr", "radarr", "qbittorrent", "jellyfin").filter(app::has)
    var source by rememberSaveable { mutableStateOf("all") }
    if (source != "all" && source !in sources) source = "all"
    var open by remember { mutableStateOf<Open?>(null) }
    var removing by remember { mutableStateOf<Rec?>(null) }
    var blocklist by remember { mutableStateOf(false) }
    suspend fun fetch(): Pair<List<Rec>, List<Rec>> = coroutineScope {
        val q = async { eachArr(app, ::queue) }
        val h = async { eachArr(app, ::history) }
        val logs = listOf("qbittorrent" to ::qbitLog, "jellyfin" to ::jellyfinLog).filter { app.isLive(it.first) }.map { async { runCatching { it.second() }.getOrDefault(emptyList()) } }
        q.await() to (h.await() + logs.awaitAll().flatten()).sortedByDescending(::byDate).take(120)
    }
    val data = load(app.config, app.live) { fetch() }
    Every(5000, app.config, app.live) { data.value = fetch() }
    val queue = data.value?.first?.filter { source == "all" || source == it.service }
    val history = data.value?.second?.filter { source == "all" || source == it.service }
    val arrQueue = app.arrs.any { source == "all" || source == it }

    LazyPage({ data.value = fetch() }) {
        item {
            PageHeading("Activity", "Downloads, imports and logs from all your services.",
                actions = if (Screen.Downloads.needs(app)) ({ UButton("Downloads", { app.go(Screen.Downloads) }, icon = Icons.Rounded.Download, kind = ButtonKind.Ghost) }) else null)
            Banner(app, sources)
            val keys = listOf("all") + sources
            Segmented(keys.map { if (it == "all") "All" else serviceNames[it] ?: it }, keys.indexOf(source), { source = keys[it] })
            if (arrQueue) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 22.dp, bottom = 2.dp)) { Text("Queue", style = Type.title); Count(queue?.size ?: "…") }
        }
        if (arrQueue) when {
            queue == null -> item { if (data.error != null) ErrorBox(data.error!!, data::reload) else Loader("Loading queue") }
            queue.isEmpty() -> item { Empty("Nothing in the queue. Grabbed releases show up here while they download.") }
            else -> items(queue, key = { "q-${it.service}-${it.id}" }) { QueueRow(it, { open = Open.Arr(it.service, it.titleId) }) { blocklist = false; removing = it } }
        }
        item { Text("History", style = Type.title, modifier = Modifier.padding(top = 22.dp, bottom = 2.dp)) }
        when {
            history == null -> item { Loader("Loading history") }
            history.isEmpty() -> item { Empty("No history yet.") }
            else -> items(history, key = { "h-${it.service}-${it.id}" }) { HistoryRow(it) }
        }
    }
    OpenSheet(open) { open = null }
    removing?.let { item ->
        Confirm("Remove from queue?", "${item.title}${if (item.detail.isNotEmpty()) " · ${item.detail}" else ""} will be removed from ${serviceNames[item.service]} and your download client.", "Remove", {
            val block = blocklist
            scope.act("Removed from queue${if (block) " and blocklisted" else ""}") {
                Api.call(item.service, "queue/${item.id}?removeFromClient=true&blocklist=$block", "DELETE")
                data.value = fetch()
            }
        }, { removing = null }) {
            if (item.release.isNotEmpty()) Text(item.release, style = Type.small, color = C.mute, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(8.dp))
            Toggle("Blocklist this release so it isn’t grabbed again", blocklist, { blocklist = it })
        }
    }
}

@Composable
private fun QueueRow(item: Rec, onOpen: () -> Unit, onRemove: () -> Unit) {
    val color = if (item.warning) C.danger else if (item.status == "paused") C.mute else C.service(item.service)
    Panel(padding = 12.dp, onClick = onOpen) {
        Row {
            Poster(item.image, Modifier.size(44.dp, 66.dp), RoundedCornerShape(7.dp), fallback = Icons.Rounded.Movie)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title + if (item.detail.isNotEmpty()) " · ${item.detail}" else "", style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(item.status.ifEmpty { "queued" }, color)
                    if (item.quality.isNotEmpty()) Pill(item.quality)
                    ServiceTag(item.service)
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine((item.progress / 100).toFloat(), Modifier.weight(1f), color)
                    Spacer(Modifier.width(8.dp))
                    Text("${item.progress}%", style = Type.tiny)
                }
                Spacer(Modifier.height(5.dp))
                if (item.release.isNotEmpty()) Text(item.release, style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val stats = listOfNotNull(item.size?.takeIf { it > 0 }?.let { "${bytes(item.left)} left of ${bytes(it)}" }, item.timeleft.takeIf { it.isNotEmpty() }?.let { "ETA $it" }, item.client.takeIf { it.isNotEmpty() })
                if (stats.isNotEmpty()) Text(stats.joinToString(" · "), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute)
                if (item.messages.isNotEmpty()) Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundSmall).background(C.warn.copy(alpha = .1f)).padding(8.dp)) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = C.warn, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(item.messages.joinToString(" · "), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.ink)
                }
            }
            IconAction(Icons.Rounded.Delete, "Remove ${item.title} from queue", onRemove, tint = C.danger)
        }
    }
}

@Composable
private fun HistoryRow(entry: Rec) {
    val log = entry.service !in setOf("sonarr", "radarr")
    val (name, tone, icon) = historyEvents[entry.event] ?: Triple(entry.event, logTones[entry.event].orEmpty(), Icons.Rounded.Info)
    val color = mixedTone(tone, C)
    Panel(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                if (log) ServiceLogo(entry.service, 20.dp) else Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title + if (entry.detail.isNotEmpty()) " · ${entry.detail}" else "", style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(serviceNames[entry.service].orEmpty(), entry.release).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(name, color)
                    if (entry.quality.isNotEmpty()) Pill(entry.quality)
                    Spacer(Modifier.weight(1f))
                    Text(ago(entry.date), style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute)
                }
            }
        }
    }
}

// ---------- Calendar ----------

@Composable
fun CalendarScreen(app: AppState) {
    var open by remember { mutableStateOf<Open?>(null) }
    suspend fun fetch() = eachArr(app) { calendar(it, 14) }.sortedBy(::byDate)
    val data = load(app.config, app.live) { fetch() }
    val zone = ZoneId.systemDefault()
    val days = data.value?.filter { it.title.contains(app.query.trim(), true) }?.groupBy { instant(it.date)?.atZone(zone)?.toLocalDate() }?.entries?.toList()

    LazyPage({ data.value = fetch() }) {
        item {
            PageHeading("Coming to your screen.", "Upcoming episodes and movie releases for the next two weeks.")
            Banner(app, listOf("sonarr", "radarr"))
        }
        when {
            days == null -> item { if (data.error != null) ErrorBox(data.error!!, data::reload) else Loader("Loading the calendar") }
            days.isEmpty() -> item { Empty("Nothing scheduled in the next two weeks.") }
            else -> items(days, key = { it.key?.toString() ?: "none" }) { (date, list) ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.width(54.dp).clip(RoundSmall).background(C.panel).border(1.dp, C.line, RoundSmall).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(date?.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)) ?: "—", style = Type.tiny, color = C.mute)
                        Text(date?.dayOfMonth?.toString() ?: "", style = Type.title.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp))
                        Text(date?.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)) ?: "", style = Type.tiny, color = C.mute)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { item ->
                            Panel(Modifier.graphicsLayer { alpha = if (item.hasFile) .72f else 1f }, padding = 10.dp, onClick = { open = Open.Arr(item.service, item.titleId) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Poster(item.image, Modifier.size(40.dp, 60.dp), RoundedCornerShape(6.dp), fallback = Icons.Rounded.Movie)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val time = if (item.service == "sonarr") instant(item.date)?.let { " · " + DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(zone).format(it) }.orEmpty() else ""
                                        Text(item.detail + time + if (item.hasFile) " · Downloaded" else "", style = Type.tiny.copy(fontWeight = FontWeight.Normal), color = C.mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    ServiceLogo(item.service, 22.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    OpenSheet(open) { open = null }
}
