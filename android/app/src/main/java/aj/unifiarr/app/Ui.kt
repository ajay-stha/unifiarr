package aj.unifiarr.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Design tokens (same as styles.css :root / :root.dark) ----------

data class Palette(
    val bg: Color, val panel: Color, val soft: Color, val line: Color, val ink: Color, val mute: Color,
    val accent: Color, val accentSoft: Color, val seerr: Color, val sonarr: Color, val radarr: Color,
    val jelly: Color, val qbittorrent: Color, val ok: Color, val danger: Color, val warn: Color, val dark: Boolean,
) {
    fun service(id: String) = when (id) { "seerr" -> seerr; "sonarr" -> sonarr; "radarr" -> radarr; "jellyfin" -> jelly; "qbittorrent" -> qbittorrent; else -> accent }
}

val LightPalette = Palette(
    bg = Color(0xFFF7F8FA), panel = Color.White, soft = Color(0xFFF1F3F6), line = Color(0xFFE7EAF0), ink = Color(0xFF272C36), mute = Color(0xFF737E8E),
    accent = Color(0xFFE97750), accentSoft = Color(0xFFFFF0E9), seerr = Color(0xFF7960C9), sonarr = Color(0xFF2694AC), radarr = Color(0xFFB37B24),
    jelly = Color(0xFF8C62B5), qbittorrent = Color(0xFF477FCA), ok = Color(0xFF258564), danger = Color(0xFFC24A4A), warn = Color(0xFFC4892C), dark = false,
)
val DarkPalette = Palette(
    bg = Color(0xFF14191F), panel = Color(0xFF1C232C), soft = Color(0xFF242D38), line = Color(0xFF303A47), ink = Color(0xFFE9EDF3), mute = Color(0xFFA4AFBE),
    accent = Color(0xFFF39473), accentSoft = Color(0xFF3A2B28), seerr = Color(0xFFB29AE9), sonarr = Color(0xFF6BCBD6), radarr = Color(0xFFECC07A),
    jelly = Color(0xFFC3A2E8), qbittorrent = Color(0xFF98BCF3), ok = Color(0xFF73CFAD), danger = Color(0xFFE07070), warn = Color(0xFFE0A84E), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
/** Current palette: `C.accent`, `C.ink` … */
val C: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

private fun variable(res: Int, weight: Int) = Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
val Bricolage = FontFamily((500..800 step 100).map { variable(R.font.bricolage, it) })
val Instrument = FontFamily((400..700 step 100).map { variable(R.font.instrument, it) })

/** Headings use Bricolage Grotesque, everything else Instrument Sans, like the web app. */
object Type {
    val display = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp)
    val subtitle = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = Instrument, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = Instrument, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 13.sp)
    val eyebrow = TextStyle(fontFamily = Instrument, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp)
    val stat = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 34.sp)
}

@Composable
fun UnifiarrTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = Color.White, primaryContainer = p.accentSoft, onPrimaryContainer = p.accent,
        secondary = p.seerr, background = p.bg, onBackground = p.ink, surface = p.panel, onSurface = p.ink, surfaceVariant = p.soft,
        onSurfaceVariant = p.mute, outline = p.line, outlineVariant = p.line, error = p.danger, surfaceContainer = p.panel,
        surfaceContainerLow = p.panel, surfaceContainerHigh = p.panel, surfaceContainerHighest = p.soft, surfaceContainerLowest = p.bg,
    )
    val typography = Typography().run {
        copy(
            bodyLarge = bodyLarge.copy(fontFamily = Instrument), bodyMedium = bodyMedium.copy(fontFamily = Instrument), bodySmall = bodySmall.copy(fontFamily = Instrument),
            labelLarge = labelLarge.copy(fontFamily = Instrument, fontWeight = FontWeight.SemiBold), labelMedium = labelMedium.copy(fontFamily = Instrument), labelSmall = labelSmall.copy(fontFamily = Instrument),
            titleLarge = titleLarge.copy(fontFamily = Bricolage, fontWeight = FontWeight.Bold), titleMedium = titleMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.Bold), titleSmall = titleSmall.copy(fontFamily = Instrument),
            headlineSmall = headlineSmall.copy(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold), headlineMedium = headlineMedium.copy(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold),
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography) {
            CompositionLocalProvider(LocalContentColor provides p.ink, LocalTextStyle provides Type.body, content = content)
        }
    }
}

// ---------- Toasts (shown above sheets too: every Sheet renders its own host) ----------

object Toasts {
    var message by mutableStateOf<String?>(null); private set
    var error by mutableStateOf(false); private set
    private var timer: kotlinx.coroutines.Job? = null
    internal var sheets by mutableIntStateOf(0)
    fun show(text: String, isError: Boolean = false) {
        message = text; error = isError
        timer?.cancel(); timer = kotlinx.coroutines.MainScope().launch { delay(3200); message = null }
    }
    // Leaving a screen cancels its coroutines; that's not an error worth showing.
    fun fail(e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) show(e.message ?: "Something went wrong.", true) }
}

/** Runs [block] in [scope]; shows [success] or the failure as a toast. */
fun CoroutineScope.act(success: String? = null, block: suspend () -> Unit) = launch {
    try { block(); success?.let { Toasts.show(it) } } catch (e: Exception) { Toasts.fail(e) }
}

@Composable
fun ToastHost(modifier: Modifier = Modifier, inSheet: Boolean = false) {
    val visible = Toasts.message != null && (inSheet || Toasts.sheets == 0)
    var last by remember { mutableStateOf("") }
    Toasts.message?.let { last = it }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Text(
                last, color = if (Toasts.error) Color.White else C.panel, style = Type.small.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(16.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (Toasts.error) C.danger else C.ink).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

// ---------- Building blocks ----------

val Round = RoundedCornerShape(16.dp)
val RoundSmall = RoundedCornerShape(10.dp)

/** The web app's card: panel background, hairline border, 16dp corners. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, onLongClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(Round).background(C.panel).border(1.dp, C.line, Round)
            .then(if (onClick != null) Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

@Composable
fun PageHeading(title: String, subtitle: String? = null, eyebrow: String? = null, actions: (@Composable RowScope.() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        eyebrow?.let { Text(it.uppercase(), style = Type.eyebrow, color = C.mute) ; Spacer(Modifier.height(6.dp)) }
        Text(title, style = Type.display)
        subtitle?.let { Spacer(Modifier.height(4.dp)); Text(it, style = Type.body, color = C.mute) }
        actions?.let { Spacer(Modifier.height(14.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Type.title, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Small rounded label; [color] tints text and a soft background. */
@Composable
fun Pill(text: String, color: Color = C.mute, modifier: Modifier = Modifier, dot: Boolean = false) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = if (C.dark) .18f else .11f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) { Box(Modifier.size(6.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(5.dp)) }
        Text(text, style = Type.tiny, color = color, maxLines = 1)
    }
}

enum class ButtonKind { Primary, Ghost, Danger, Soft }

@Composable
fun UButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, kind: ButtonKind = ButtonKind.Primary, enabled: Boolean = true, busy: Boolean = false) {
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(C.accent, Color.White, C.accent)
        ButtonKind.Danger -> Triple(Color(0xFFB64040), Color.White, Color(0xFFB64040))
        ButtonKind.Ghost -> Triple(C.panel, C.ink, C.line)
        ButtonKind.Soft -> Triple(C.accentSoft, C.accent, C.accentSoft)
    }
    Row(
        modifier.heightIn(min = 42.dp).clip(RoundSmall).background(bg).border(1.dp, border, RoundSmall)
            .clickable(enabled = enabled && !busy, onClick = onClick).graphicsLayer { alpha = if (enabled) 1f else .5f }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
        else icon?.let { Icon(it, null, Modifier.size(18.dp), tint = fg) }
        if ((busy || icon != null) && text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = Type.small.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), maxLines = 1)
    }
}

@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.ink, enabled: Boolean = true) {
    IconButton(onClick, modifier, enabled = enabled) { Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .4f)) }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = C.accent, height: Dp = 5.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "progress")
    // Filled part is a wave that flows while active (muted colour = paused/skipped, so it holds still), then a flat track.
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "p")
    val moving = color != C.mute
    val track = C.soft
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(height * 1.6f)) {
        val stroke = height.toPx() * .6f; val mid = size.height / 2; val amp = (size.height - stroke) / 2
        val wave = 16.dp.toPx(); val end = size.width * animated; val shift = if (moving) phase * wave else 0f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        if (end < size.width) drawLine(track, Offset(minOf(size.width, end + stroke * 2), mid), Offset(size.width, mid), stroke * .7f, cap)
        if (end <= 0f) return@Canvas
        val path = androidx.compose.ui.graphics.Path().apply { moveTo(0f, mid) }
        var x = 0f
        while (x < end) { x = minOf(end, x + 1.dp.toPx()); path.lineTo(x, mid - amp * kotlin.math.sin((x - shift) / wave * 2 * Math.PI).toFloat()) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = cap))
    }
}

/** Segmented tabs, like the web's All / Movies / TV shows switch. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundSmall).background(C.soft).border(1.dp, C.line, RoundSmall).padding(3.dp).horizontalScroll(rememberScrollState())) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Text(
                label, style = Type.small.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) C.ink else C.mute,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) C.panel else Color.Transparent)
                    .clickable { onSelect(index) }.padding(horizontal = 13.dp, vertical = 7.dp),
            )
        }
    }
}

/** Text input styled like the web form fields. */
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    password: Boolean = false, number: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, note: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) { Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold)); Spacer(Modifier.height(6.dp)) }
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), placeholder = { Text(placeholder, color = C.mute, style = Type.body) }, textStyle = Type.body,
            singleLine = singleLine, minLines = minLines, shape = RoundSmall,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line, focusedContainerColor = C.panel, unfocusedContainerColor = C.panel),
        )
        note?.let { Spacer(Modifier.height(4.dp)); Text(it, style = Type.small, color = C.mute) }
    }
}

/** Dropdown select; [options] are value → label. Opens a styled popup like the web's select-menu. */
@Composable
fun <T> Select(label: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) { Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold)); Spacer(Modifier.height(6.dp)) }
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundSmall).background(C.panel).border(1.dp, if (open) C.accent else C.line, RoundSmall)
                    .clickable { open = true }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.first == value }?.second ?: "Choose…", style = Type.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, null, tint = C.mute)
            }
            DropdownMenu(open, { open = false }, shape = RoundedCornerShape(12.dp), containerColor = C.panel, border = BorderStroke(1.dp, C.line), modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { (key, text) ->
                    val on = key == value
                    DropdownMenuItem(
                        text = { Text(text, style = Type.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) C.accent else C.ink) },
                        trailingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, tint = C.accent, modifier = Modifier.size(18.dp)) }) else null,
                        onClick = { open = false; onChange(key) },
                    )
                }
            }
        }
    }
}

/** Removable/selectable chip used for tags and filters. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.accent) {
    Text(
        text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (selected) color else C.mute,
        modifier = modifier.clip(CircleShape).background(if (selected) color.copy(alpha = if (C.dark) .2f else .12f) else C.soft)
            .border(1.dp, if (selected) color.copy(alpha = .5f) else C.line, CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, note: String? = null) {
    Row(modifier.fillMaxWidth().clip(RoundSmall).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.body)
            note?.let { Text(it, style = Type.small, color = C.mute) }
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.accent, uncheckedTrackColor = C.soft, uncheckedBorderColor = C.line, uncheckedThumbColor = C.mute))
    }
}

// ---------- Images ----------

/** Shimmering placeholder while an image loads (the web's is-loading state). */
fun Modifier.shimmer(shape: Shape = RoundSmall): Modifier = composed {
    val base = C.soft
    val shine = if (C.dark) Color(0xFF2E3946) else Color(0xFFF9FAFC)
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    clip(shape).drawWithContent {
        drawRect(Brush.linearGradient(listOf(base, shine, base), start = Offset(size.width * x - size.width, 0f), end = Offset(size.width * x, size.height)))
        drawContent()
    }
}

/** Poster/backdrop: shimmer until fully decoded, fade in, initials-free fallback on failure. */
@Composable
fun Poster(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), contentScale: ContentScale = ContentScale.Crop, fallback: ImageVector? = null) {
    val context = LocalContext.current
    val source = Api.image(url)
    Box(modifier.clip(shape).background(C.soft), contentAlignment = Alignment.Center) {
        if (source == null) { fallback?.let { Icon(it, null, tint = C.mute, modifier = Modifier.size(28.dp)) }; return@Box }
        // AsyncImage, not SubcomposeAsyncImage: subcomposition per cell makes fast list scrolling stutter.
        var state by remember(source) { mutableIntStateOf(0) } // 0 loading, 1 done, 2 failed
        if (state == 0) Box(Modifier.matchParentSize().shimmer(shape))
        if (state == 2) fallback?.let { Icon(it, null, tint = C.mute, modifier = Modifier.size(28.dp)) }
        AsyncImage(
            ImageRequest.Builder(context).data(source).crossfade(250).build(), null, Modifier.matchParentSize(), contentScale = contentScale,
            onSuccess = { state = 1 }, onError = { state = 2 },
        )
    }
}

/** Bundled official service logo (assets/logos). */
@Composable
fun ServiceLogo(id: String, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val file = if (id in setOf("jellyfin", "qbittorrent", "unifiarr")) "$id.svg" else "$id.png"
    AsyncImage("file:///android_asset/logos/$file", null, modifier.size(size), contentScale = ContentScale.Fit)
}

// ---------- Loading / empty / error ----------

/** The web's "deck shuffle" loader: three gradient cards cycling, with a sweep bar. */
@Composable
fun Loader(text: String = "Loading…", modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "deck").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = CubicBezierEasing(.65f, 0f, .35f, 1f))), label = "t")
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(-1f, 2.5f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "s")
    val cards = listOf(listOf(C.accent, C.seerr), listOf(C.sonarr, C.qbittorrent), listOf(C.radarr, C.accent))
    Column(modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp, 62.dp)) {
            cards.mapIndexed { i, colors -> i to ((t + i / 3f) % 1f) }.sortedBy { (_, p) -> depth(p) }.forEach { (i, p) ->
                val (dx, rot, scale, alpha) = pose(p)
                Box(
                    Modifier.align(Alignment.BottomCenter).size(34.dp, 50.dp).graphicsLayer {
                        translationX = dx * density; translationY = (if (p in .2f..0.46f) -6f else 0f) * density; rotationZ = rot; scaleX = scale; scaleY = scale; this.alpha = alpha
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1.3f)
                    }.clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(cards[i])),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(120.dp, 3.dp).clip(CircleShape).background(C.soft)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(.4f).graphicsLayer { translationX = sweep * size.width }.clip(CircleShape).background(C.accent))
        }
        Spacer(Modifier.height(10.dp))
        Text(text, style = Type.small, color = C.mute)
    }
}

private data class Pose(val dx: Float, val rot: Float, val scale: Float, val alpha: Float)
private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
private val left = Pose(-22f, -14f, .88f, .6f); private val mid = Pose(0f, 0f, 1.04f, 1f); private val right = Pose(22f, 14f, .88f, .6f)
private fun mix(a: Pose, b: Pose, f: Float) = Pose(lerp(a.dx, b.dx, f), lerp(a.rot, b.rot, f), lerp(a.scale, b.scale, f), lerp(a.alpha, b.alpha, f))
private fun pose(p: Float) = when { p < 1 / 3f -> mix(left, mid, p * 3); p < 2 / 3f -> mix(mid, right, p * 3 - 1); else -> mix(right, left, p * 3 - 2) }
private fun depth(p: Float) = when { p < 1 / 3f -> p * 3; p < 2 / 3f -> 2 - p * 3; else -> 0f }

@Composable
fun Empty(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(modifier.fillMaxWidth().clip(Round).border(1.dp, C.line, Round).padding(vertical = 34.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon?.let { Icon(it, null, tint = C.mute, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(10.dp)) }
        Text(text, style = Type.body, color = C.mute)
    }
}

@Composable
fun ErrorBox(message: String, retry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(Round).background(C.danger.copy(alpha = .08f)).border(1.dp, C.danger.copy(alpha = .35f), Round).padding(18.dp)) {
        Text(message, style = Type.body, color = C.danger)
        retry?.let { Spacer(Modifier.height(10.dp)); UButton("Try again", it, kind = ButtonKind.Ghost) }
    }
}

/** Loads data with a suspend block; `reload()` re-runs it, keeping the old value visible (no flicker). */
class Loaded<T>(initial: T?) {
    var value by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    internal var trigger by mutableIntStateOf(0)
    fun reload() { trigger++ }
}

@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    val state = remember(*keys) { Loaded<T>(null) }
    LaunchedEffect(state, state.trigger) {
        state.loading = true
        try { state.value = block(); state.error = null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { state.error = e.message ?: "Couldn't load." }
        state.loading = false
    }
    return state
}

/** Shows the loader the first time, the error if there's nothing to show, else the content. */
@Composable
fun <T> Show(state: Loaded<T>, loadingText: String = "Loading…", content: @Composable (T) -> Unit) {
    val value = state.value
    when {
        value != null -> content(value)
        state.error != null -> ErrorBox(state.error!!, { state.reload() })
        else -> Loader(loadingText)
    }
}

/** Re-runs [block] every [millis] while this composable is on screen (and the app is visible). */
@Composable
fun Every(millis: Long, vararg keys: Any?, block: suspend () -> Unit) {
    val resumed = LocalAppVisible.current
    LaunchedEffect(resumed, *keys) { if (resumed) while (true) { delay(millis); runCatching { block() } } }
}

val LocalAppVisible = compositionLocalOf { true }

// ---------- Formatting (same rules as the web app) ----------

fun bytes(value: Long?): String {
    if (value == null || value < 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var v = value.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$value B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
fun speed(value: Long?) = if (value == null || value <= 0) "0 B/s" else "${bytes(value)}/s"
fun duration(seconds: Long?): String {
    if (seconds == null || seconds < 0 || seconds >= 8640000) return "∞"
    val d = seconds / 86400; val h = seconds % 86400 / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
    return when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m ${s}s"; else -> "${s}s" }
}
fun clock(seconds: Long): String { val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60; return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s) }
fun instant(iso: String?): Instant? = iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { Instant.parse(it + "Z") }.getOrNull() ?: runCatching { java.time.LocalDate.parse(it.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull() }
fun ago(iso: String?): String {
    val then = instant(iso) ?: return "Never"
    val minutes = Duration.between(then, Instant.now()).toMinutes().coerceAtLeast(0)
    return when { minutes < 1 -> "Just now"; minutes < 60 -> "${minutes}m ago"; minutes < 1440 -> "${minutes / 60}h ago"; minutes < 43200 -> "${minutes / 1440}d ago"; else -> day(iso) }
}
fun day(iso: String?): String = instant(iso)?.let { DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(it) } ?: "—"
fun dayTime(iso: String?): String = instant(iso)?.let { DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(it) } ?: "—"
fun epochDay(seconds: Long?): String = if (seconds == null || seconds <= 0) "—" else DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
