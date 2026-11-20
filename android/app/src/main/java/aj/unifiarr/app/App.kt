package aj.unifiarr.app

import android.content.Context
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Pages, in sidebar order; [needs] says which configured services make a page appear (same rules as the web app). */
enum class Screen(val title: String, val short: String, val icon: ImageVector, val needs: (AppState) -> Boolean) {
    Overview("Overview", "Overview", Icons.Rounded.GridView, { it.config.isNotEmpty() }),
    Discover("Discover", "Discover", Icons.Rounded.Explore, { it.has("seerr") }),
    Requests("Requests", "Requests", Icons.Rounded.Inbox, { it.has("seerr") }),
    Library("My library", "Library", Icons.Rounded.VideoLibrary, { it.has("jellyfin") }),
    Manage("Series & movies", "Media", Icons.Rounded.Movie, { it.arrs.isNotEmpty() }),
    Downloads("Downloads", "Downloads", Icons.Rounded.Download, { it.has("qbittorrent") }),
    Wanted("Wanted", "Wanted", Icons.Rounded.Search, { it.arrs.isNotEmpty() }),
    Activity("Activity", "Activity", Icons.Rounded.History, { it.arrs.isNotEmpty() || it.has("qbittorrent") || it.has("jellyfin") }),
    Calendar("Calendar", "Calendar", Icons.Rounded.CalendarMonth, { it.arrs.isNotEmpty() }),
    Server("Server", "Server", Icons.Rounded.Dns, { it.has("jellyfin") }),
    Services("Services", "Services", Icons.Rounded.Settings, { true }),
}

val serviceNames = linkedMapOf("jellyfin" to "Jellyfin", "sonarr" to "Sonarr", "radarr" to "Radarr", "seerr" to "Seerr", "qbittorrent" to "qBittorrent")

class AppState(private val prefs: android.content.SharedPreferences) {
    var server by mutableStateOf(prefs.getString("server", null)?.trimEnd('/')?.plus("/"))
    /** service id → { url, user?, hasSecret } from the server; empty while unknown. */
    var config by mutableStateOf<Map<String, JSONObject>>(emptyMap())
    var live by mutableStateOf<Map<String, Boolean>>(emptyMap())
    var ready by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
    var screen by mutableStateOf(Screen.Overview)
    /** The header search; screens filter or search with it (Discover searches Seerr, Downloads filters torrents …). */
    var query by mutableStateOf("")
    /** Bumped by pull-to-refresh style "reload everything" actions. */
    var refresh by mutableIntStateOf(0)
    var incomingTorrent by mutableStateOf<Uri?>(null)
    val history = mutableStateListOf<Screen>()

    fun has(id: String) = config.containsKey(id)
    fun isLive(id: String) = live[id] == true
    val arrs get() = listOf("sonarr", "radarr").filter(::has)
    val pages get() = Screen.entries.filter { it.needs(this) }


    fun go(target: Screen, remember: Boolean = true) {
        if (target == screen) return
        if (remember) history.add(screen)
        screen = target
    }

    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        screen = previous
        return true
    }

    var servers by mutableStateOf(Servers.list(prefs))

    fun saveServer(url: String) {
        server = url; prefs.edit().putString("server", url).apply()
        Api.base = url
        servers = (servers + url).distinct(); Servers.save(prefs, servers)
    }

    /** Set when the user picked a server by hand, so the next connect doesn't fail over away from it. */
    var explicit = false

    fun forget(url: String) { servers = servers - url; Servers.save(prefs, servers) }

    /** Switches to another saved server when the current one stops answering (e.g. left home Wi-Fi). */
    suspend fun failover(): Boolean {
        val current = server ?: return false
        if (Servers.reachable(current)) return false
        val other = Servers.pick(prefs, except = current) ?: return false
        saveServer(other)
        Toasts.show("Switched to $other")
        return true
    }

    /** Reads which services the server has, then checks each one (the web's setup()). */
    suspend fun connect() {
        Api.base = server ?: return
        if (!explicit && failover()) return
        explicit = false
        try {
            val json = Api.obj("config")
            config = json.keys().asSequence().associateWith { json.getJSONObject(it) }
            problem = null
            checkHealth()
            if (!screen.needs(this)) screen = if (has("jellyfin") || config.isNotEmpty()) Screen.Overview else Screen.Services
            if (config.isEmpty()) screen = Screen.Services
        } catch (e: Api.Failure) {
            problem = if (e.status == 403) "The server refused this app. Add “${android.net.Uri.parse(server).host}” to ALLOWED_HOSTS (in compose.yaml) and restart Unifiarr." else e.message
        } catch (e: Exception) {
            problem = "Couldn't reach Unifiarr at $server. ${e.message ?: ""}".trim()
        }
        ready = true
    }

    suspend fun checkHealth() = coroutineScope {
        live = config.keys.map { id -> async { id to (runCatching { Api.call(id) }.isSuccess) } }.awaitAll().toMap()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState

    // Launcher shortcut "Change server".
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "aj.unifiarr.app.SERVER") app.server = null
        acceptLink(intent)
    }

    private fun acceptLink(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        val supported = when (uri.scheme?.lowercase()) {
            "magnet" -> true
            "http", "https" -> uri.lastPathSegment?.endsWith(".torrent", ignoreCase = true) == true
            "content" -> intent.type in setOf("application/x-bittorrent", "application/x-torrent")
            else -> false
        }
        if (supported) app.incomingTorrent = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { Api.http })); add(SvgDecoder.Factory()) }.build()
        }
        app = AppState(getSharedPreferences("unifiarr", Context.MODE_PRIVATE))
        acceptLink(intent)
        Watch.setup(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        if (intent?.action == "aj.unifiarr.app.SERVER") app.server = null
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            UnifiarrTheme(dark) { Root(app) }
        }
    }
}

@Composable
fun Root(app: AppState) {
    var visible by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { visible = true; try { kotlinx.coroutines.awaitCancellation() } finally { visible = false } }
    }
    CompositionLocalProvider(LocalAppVisible provides visible) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            val server = app.server
            if (server == null) ServerSetup(app)
            else {
                LaunchedEffect(server) { app.ready = false; app.connect() }
                when {
                    !app.ready -> Splash()
                    app.problem != null -> ServerSetup(app, app.problem)
                    else -> Shell(app)
                }
            }
            ToastHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (app.server != null && app.ready && app.problem == null) 72.dp else 0.dp))
        }
    }
}

@Composable
private fun Splash() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Brand(big = true)
        Loader("Connecting to your services…")
    }
}

@Composable
fun Brand(big: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ServiceLogo("unifiarr", if (big) 44.dp else 30.dp)
        Spacer(Modifier.width(if (big) 12.dp else 9.dp))
        Text("unifiarr", style = Type.title.copy(fontSize = if (big) 28.sp else 20.sp, fontWeight = FontWeight.ExtraBold))
        Text(".", style = Type.title.copy(fontSize = if (big) 28.sp else 20.sp, fontWeight = FontWeight.ExtraBold), color = C.accent)
    }
}

@Composable
private fun ServerSetup(app: AppState, problem: String? = null) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.server ?: "http://") }
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Brand(big = true)
        Spacer(Modifier.height(28.dp))
        Text("Connect to your server", style = Type.display)
        Spacer(Modifier.height(6.dp))
        Text("Enter the address of the Unifiarr server on your network. It keeps your service keys; this app just talks to it.", style = Type.body, color = C.mute)
        Spacer(Modifier.height(22.dp))
        Panel {
            Field("Server address", url, { url = it.trim() }, placeholder = "http://192.168.1.10:4180", note = "Same address you open Unifiarr with in a browser.")
            problem?.let { Spacer(Modifier.height(12.dp)); Text(it, style = Type.small, color = C.danger) }
            Spacer(Modifier.height(16.dp))
            UButton("Connect", {
                var value = url.trim().trimEnd('/')
                if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://$value"
                if (android.net.Uri.parse(value).host.isNullOrBlank()) return@UButton Toasts.show("Enter an address like http://192.168.1.10:4180", true)
                busy = true
                scope.launch { app.explicit = true; app.saveServer("$value/"); app.ready = false; app.connect(); busy = false }
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Link, busy = busy)
        }
        if (app.servers.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("Saved servers", style = Type.title)
            Spacer(Modifier.height(4.dp))
            Text("If the one in use stops answering, Unifiarr switches to another saved server automatically.", style = Type.small, color = C.mute)
            Spacer(Modifier.height(10.dp))
            app.servers.forEach { saved ->
                Row(Modifier.fillMaxWidth().clip(RoundSmall).clickable { app.explicit = true; app.saveServer(saved); app.ready = false; scope.launch { app.connect() } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, null, tint = if (saved == app.server) C.accent else C.mute)
                    Spacer(Modifier.width(12.dp))
                    Text(saved.trimEnd('/'), style = Type.body, modifier = Modifier.weight(1f), maxLines = 1)
                    if (saved == app.server) Pill("In use", C.ok, dot = true)
                    IconButton({ app.forget(saved) }) { Icon(Icons.Rounded.Close, "Forget $saved", tint = C.mute) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(app: AppState) {
    var more by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(app.history.isNotEmpty() || app.query.isNotEmpty()) { if (app.query.isNotEmpty()) app.query = "" else app.back() }
    LaunchedEffect(app.screen) { app.query = app.query.takeIf { app.screen in searchable } ?: "" }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { Watch.check(context) }
    LaunchedEffect(app.incomingTorrent, app.ready, app.config) {
        val uri = app.incomingTorrent ?: return@LaunchedEffect
        if (!app.ready) return@LaunchedEffect
        if (!app.has("qbittorrent")) {
            app.incomingTorrent = null
            Toasts.show("Set up qBittorrent in Services before adding torrents.", true)
            app.go(Screen.Services)
            return@LaunchedEffect
        }
        try {
            acceptTorrent(context, uri)
            app.go(Screen.Downloads)
            Toasts.show("Torrent added to qBittorrent")
        } catch (e: Exception) { Toasts.fail(e) }
        finally { app.incomingTorrent = null }
    }
    Every(60_000) { if (!app.failover()) { app.checkHealth(); Watch.check(context) } }

    Scaffold(
        containerColor = C.bg,
        topBar = { Header(app) },
        bottomBar = { BottomBar(app) { more = true } },
    ) { padding ->
        AnimatedContent(app.screen, transitionSpec = { fadeIn(androidx.compose.animation.core.tween(180)) togetherWith fadeOut(androidx.compose.animation.core.tween(120)) }, label = "screen",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding)) { screen ->
            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Overview -> OverviewScreen(app)
                    Screen.Discover -> DiscoverScreen(app)
                    Screen.Requests -> RequestsScreen(app)
                    Screen.Library -> LibraryScreen(app)
                    Screen.Manage -> ManageScreen(app)
                    Screen.Downloads -> DownloadsScreen(app)
                    Screen.Wanted -> WantedScreen(app)
                    Screen.Activity -> ActivityScreen(app)
                    Screen.Calendar -> CalendarScreen(app)
                    Screen.Server -> ServerScreen(app)
                    Screen.Services -> ServicesScreen(app)
                }
            }
        }
    }
    if (more) Sheet(onDismiss = { more = false }, title = "All pages") {
        val pages = app.pages
        pages.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { page ->
                    val on = page == app.screen
                    Column(
                        Modifier.weight(1f).clip(Round).background(if (on) C.accentSoft else C.soft).clickable { more = false; app.go(page) }.padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(page.icon, null, tint = if (on) C.accent else C.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(page.title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (on) C.accent else C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        SectionTitle("Your services") { Text("${app.config.count { app.isLive(it.key) }}/${app.config.size} live", style = Type.small, color = C.mute) }
        app.config.keys.forEach { id ->
            Row(Modifier.fillMaxWidth().clip(RoundSmall).clickable { more = false; app.go(Screen.Services) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(id, 26.dp)
                Spacer(Modifier.width(12.dp))
                Text(serviceNames[id] ?: id, style = Type.body, modifier = Modifier.weight(1f))
                Pill(if (app.isLive(id)) "Live" else "Offline", if (app.isLive(id)) C.ok else C.warn, dot = true)
            }
        }
        Spacer(Modifier.height(6.dp))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UButton("Recheck", { scope.act("Checked your services.") { app.checkHealth() } }, Modifier.weight(1f), icon = Icons.Rounded.Refresh, kind = ButtonKind.Ghost)
            UButton("Change server", { more = false; app.server = null }, Modifier.weight(1f), icon = Icons.Rounded.Link, kind = ButtonKind.Ghost)
        }
        Spacer(Modifier.height(8.dp))
        Text("Connected to ${app.server}", style = Type.small, color = C.mute)
    }
}

private val searchable = setOf(Screen.Discover, Screen.Library, Screen.Manage, Screen.Requests, Screen.Downloads, Screen.Calendar)

@Composable
private fun Header(app: AppState) {
    val focusManager = LocalFocusManager.current
    Column(Modifier.background(C.panel).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceLogo("unifiarr", 30.dp)
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundSmall).background(C.soft).border(1.dp, C.line, RoundSmall).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = C.mute, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (app.query.isEmpty()) Text(if (app.screen == Screen.Downloads) "Search downloads…" else "Search movies and shows…", style = Type.body, color = C.mute, maxLines = 1)
                    BasicTextField(
                        app.query, { value ->
                            app.query = value
                            // Like the web: searching from a page that can't search jumps to the best searchable page.
                            if (app.screen !in searchable) listOf(Screen.Library, Screen.Discover, Screen.Manage, Screen.Downloads).firstOrNull { it.needs(app) }?.let { app.go(it) }
                        },
                        Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body.copy(color = C.ink), cursorBrush = SolidColor(C.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text), keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }
                if (app.query.isNotEmpty()) IconAction(Icons.Rounded.Close, "Clear search", { app.query = ""; focusManager.clearFocus() })
                else Spacer(Modifier.width(12.dp))
            }
        }
        HorizontalDivider(color = C.line)
    }
}

/** Downloads badge count is shared through this (DownloadsScreen/overview update it from qBittorrent data). */
object Badges { var downloading by mutableIntStateOf(0); var pendingRequests by mutableIntStateOf(0) }

@Composable
private fun BottomBar(app: AppState, onMore: () -> Unit) {
    val tabs = listOf(Screen.Overview, Screen.Discover, Screen.Downloads, Screen.Library, Screen.Requests, Screen.Manage).filter { it.needs(app) }.take(4)
    Column(Modifier.background(C.panel)) {
        HorizontalDivider(color = C.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp)) {
            tabs.forEach { tab ->
                val badge = when (tab) { Screen.Downloads -> Badges.downloading; Screen.Requests -> Badges.pendingRequests; else -> 0 }
                TabItem(tab.short, tab.icon, tab == app.screen, badge) { app.go(tab) }
            }
            TabItem("More", Icons.Rounded.Menu, app.screen !in tabs, 0, onMore)
        }
    }
}

@Composable
private fun RowScope.TabItem(label: String, icon: ImageVector, active: Boolean, badge: Int, onClick: () -> Unit) {
    val color = if (active) C.accent else C.mute
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (active) C.accent.copy(alpha = .11f) else Color.Transparent).clickable(onClick = onClick).padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(badge = { if (badge > 0) Badge(containerColor = C.accent, contentColor = Color.White) { Text("$badge", fontSize = 9.sp) } }) {
            Icon(icon, label, tint = color, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, style = Type.tiny.copy(fontSize = 11.sp), color = color, maxLines = 1)
    }
}

/** The web modal: a bottom sheet with a title, scrolling content and its own toast host (so toasts show above it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, subtitle: String? = null, scroll: Boolean = true, actions: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    DisposableEffect(Unit) { Toasts.sheets++; onDispose { Toasts.sheets-- } }
    // Sheet stays below the status bar; letting it slide under made its inset padding change height mid-drag, so it bounced.
    ModalBottomSheet(onDismiss, Modifier.statusBarsPadding(), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.panel, contentColor = C.ink,
        contentWindowInsets = { WindowInsets.navigationBars },
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(C.line)) }) {
        Box {
            Column(Modifier.fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                if (title != null) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 14.dp)) {
                            Text(title, style = Type.title)
                            subtitle?.let { Text(it, style = Type.small, color = C.mute) }
                        }
                        actions?.invoke(this)
                    }
                }
                content()
            }
            ToastHost(Modifier.align(Alignment.BottomCenter), inSheet = true)
        }
    }
}

/** Confirmation dialog for destructive actions. */
@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    AlertDialog(
        onDismiss, containerColor = C.panel, shape = Round,
        title = { Text(title, style = Type.title) },
        text = { Column { Text(text, style = Type.body, color = C.mute); extra?.invoke(this) } },
        confirmButton = { UButton(confirm, { onDismiss(); onConfirm() }, kind = ButtonKind.Danger) },
        dismissButton = { UButton("Cancel", onDismiss, kind = ButtonKind.Ghost) },
    )
}

/** Standard scrolling page body with pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(onRefresh: (suspend () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val body = @Composable { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 22.dp), content = content) }
    if (onRefresh == null) body()
    else PullToRefreshBox(refreshing, { scope.launch { refreshing = true; runCatching { onRefresh() }; refreshing = false } }, Modifier.fillMaxSize()) { body() }
}
