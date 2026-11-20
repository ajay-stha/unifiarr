package aj.unifiarr.app

import android.Manifest
import android.content.BroadcastReceiver
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.app.Notification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("unifiarr", Context.MODE_PRIVATE)

/** Saved Unifiarr servers (e.g. home LAN + remote address); the first one that answers is used. */
object Servers {
    private val quick = Api.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    fun list(p: SharedPreferences): List<String> =
        p.getString("servers", null)?.split('\n')?.filter { it.isNotBlank() } ?: listOfNotNull(p.getString("server", null))

    fun save(p: SharedPreferences, servers: List<String>) = p.edit().putString("servers", servers.distinct().joinToString("\n")).apply()

    suspend fun reachable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { quick.newCall(Request.Builder().url(url + "api/config").header("x-unifiarr", "1").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** First reachable saved server, skipping [except]. */
    suspend fun pick(p: SharedPreferences, except: String? = null): String? = list(p).filter { it != except }.firstOrNull { reachable(it) }
}

/** Polls the server and posts native notifications for torrents, Seerr requests and episodes airing soon. Remembers what it already told you. */
object Watch {
    private const val CHANNEL = "activity"
    private val lock = Mutex()

    fun setup(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Downloads, requests & episodes", NotificationManager.IMPORTANCE_DEFAULT))
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (jobs.getPendingJob(1) == null) jobs.schedule(
            // ponytail: Android's minimum periodic interval is 15 min; while the app is open Shell also runs check() every minute.
            JobInfo.Builder(1, ComponentName(context, NotifyJob::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()
        )
    }

    fun result(context: Context, id: String, title: String, text: String = "") = post(context, "r$id", title, text)

    suspend fun check(context: Context) = lock.withLock {
        // Without permission nothing would show, so don't mark anything as told yet.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withLock
        val p = prefs(context)
        val seen = runCatching { JSONObject(p.getString("seen", "{}")!!) }.getOrDefault(JSONObject())
        runCatching { torrents(context, seen) }
        runCatching { requests(context, seen) }
        runCatching { episodes(context, seen) }
        p.edit().putString("seen", seen.toString()).apply()
    }

    /** New torrent → "started"; progress reaching 100% → "completed". First run only records the current state. */
    private suspend fun torrents(context: Context, seen: JSONObject) {
        val all = Api.obj("qbittorrent", "sync/maindata?rid=0").obj("torrents") ?: return
        val old = seen.optJSONObject("torrents")
        val now = JSONObject()
        for (hash in all.keys()) {
            val t = all.getJSONObject(hash)
            val done = t.optDouble("progress") >= 1
            now.put(hash, done)
            if (old == null) continue
            val name = t.str("name")
            if (!old.has(hash) && !done) post(context, "t$hash", "Download started", name)
            else if (old.has(hash) && !old.optBoolean(hash) && done) post(context, "t$hash", "Download completed", name)
        }
        seen.put("torrents", now)
    }

    /** New request → "requested"; status turning approved (2) → "approved". */
    private suspend fun requests(context: Context, seen: JSONObject) {
        val list = Api.obj("seerr", "request?take=50&skip=0&filter=all&sort=added").list("results")
        val old = seen.optJSONObject("requests")
        val now = JSONObject(old?.toString() ?: "{}")
        for (r in list) {
            val id = r.optInt("id").toString()
            val status = r.optInt("status")
            now.put(id, status)
            if (old == null) continue
            val title = when {
                !old.has(id) -> "New request"
                status == 2 && old.optInt(id) != 2 -> "Request approved"
                else -> continue
            }
            val media = r.obj("media") ?: JSONObject()
            val kind = r.strOrNull("type") ?: media.str("mediaType")
            val info = runCatching { Api.obj("seerr", "$kind/${media.optInt("tmdbId")}") }.getOrDefault(JSONObject())
            val name = info.strOrNull("title") ?: info.strOrNull("name") ?: "A title"
            val by = r.obj("requestedBy")?.strOrNull("displayName")
            post(context, "r$id", title, if (title == "New request" && by != null) "$name · requested by $by" else name, id.takeIf { status == 1 })
        }
        seen.put("requests", now)
    }

    /** Monitored Sonarr episodes airing in the next 24 hours, once each. */
    private suspend fun episodes(context: Context, seen: JSONObject) {
        val start = Instant.now()
        val list = Api.arr("sonarr", "calendar?start=$start&end=${start.plusSeconds(86_400)}&unmonitored=false&includeSeries=true").objects()
        val told = seen.optJSONObject("episodes") ?: JSONObject()
        val time = DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault())
        for (e in list) {
            val id = e.optInt("id").toString()
            if (told.has(id)) continue
            told.put(id, e.str("airDateUtc"))
            val series = e.obj("series")?.str("title") ?: "Episode"
            val code = "S%02dE%02d".format(e.optInt("seasonNumber"), e.optInt("episodeNumber"))
            val at = runCatching { time.format(Instant.parse(e.str("airDateUtc"))) }.getOrDefault("soon")
            post(context, "e$id", "Upcoming: $series", "$code · ${e.str("title")} · $at")
        }
        // Forget episodes that aired more than two days ago so the list stays small.
        val cutoff = start.minusSeconds(172_800)
        told.keys().asSequence().toList().forEach { k -> if (runCatching { Instant.parse(told.getString(k)) < cutoff }.getOrDefault(true)) told.remove(k) }
        seen.put("episodes", told)
    }

    private fun post(context: Context, key: String, title: String, text: String, pendingRequest: String? = null) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_foreground).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).apply {
                if (pendingRequest != null) {
                    listOf("approve" to "Approve", "decline" to "Decline").forEachIndexed { index, (action, label) ->
                        val intent = Intent(context, RequestActionReceiver::class.java).putExtra("request", pendingRequest).putExtra("action", action)
                        val pending = PendingIntent.getBroadcast(context, pendingRequest.hashCode() * 2 + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        addAction(if (action == "approve") android.R.drawable.checkbox_on_background else android.R.drawable.ic_menu_close_clear_cancel, label, pending)
                    }
                }
            }.build()
        context.getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
    }
}

class RequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = intent.getStringExtra("request") ?: return
        val action = intent.getStringExtra("action")?.takeIf { it == "approve" || it == "decline" } ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = prefs(context)
                val server = p.getString("server", null)?.trimEnd('/')?.plus("/")
                Api.base = server?.takeIf { Servers.reachable(it) } ?: Servers.pick(p)
                    ?: throw IllegalStateException("No saved Unifiarr server is reachable.")
                Api.call("seerr", "request/$request/$action", "POST")
                val seen = JSONObject(p.getString("seen", "{}") ?: "{}")
                val requests = seen.optJSONObject("requests") ?: JSONObject()
                requests.put(request, if (action == "approve") 2 else 3)
                seen.put("requests", requests)
                p.edit().putString("seen", seen.toString()).apply()
                Watch.result(context, request, "Request ${if (action == "approve") "approved" else "declined"}")
            } catch (e: Exception) {
                Watch.result(context, request, "Request update failed", e.message ?: "Could not update the request.")
            } finally { result.finish() }
        }
    }
}

/** Background check (JobScheduler, no extra dependency). Uses whichever saved server answers. */
class NotifyJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (Api.base.isEmpty() || !Servers.reachable(Api.base)) Api.base = Servers.pick(prefs(applicationContext)) ?: return@runCatching
                Watch.check(applicationContext)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
}
