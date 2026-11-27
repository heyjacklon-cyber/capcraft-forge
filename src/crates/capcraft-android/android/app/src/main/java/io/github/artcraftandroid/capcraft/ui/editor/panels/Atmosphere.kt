package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.Laid
import io.github.artcraftandroid.capcraft.core.Motion
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.engine.Media
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.intoBitmap
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/** An overlay picture of the user's own, in a folder they chose. */
internal class OverlayFile(val uri: Uri, val name: String, val category: String)

/** Where an overlay goes on the frame. */
internal enum class Place { COVER, TOP, BOTTOM, MIDDLE }

/**
 * How one kind of overlay is laid: where, how strong on a picture of middling brightness, how much of the
 * frame its lit part is at the most, across and down, where it does not cover it, and whether it is light,
 * which takes the picture's warmth. Light of the sun's kind is [golden]: its beams yellow, and the picture warmed by them.
 */
internal class Preset(val words: List<String>, val place: Place, val strength: Float, val wide: Float = 1f, val tall: Float = 1f, val light: Boolean = false, val warmth: Float = 0f, val golden: Boolean = false)

private val PRESETS = listOf(
    Preset(listOf("ground smoke"), Place.BOTTOM, 0.85f, 1.1f, 0.5f),
    Preset(listOf("mist", "fog", "haze"), Place.BOTTOM, 0.8f, 1.35f, 0.5f),
    Preset(listOf("cloud"), Place.TOP, 0.9f, 0.95f, 0.55f),
    Preset(listOf("underwater"), Place.COVER, 0.85f, light = true, warmth = -0.5f),
    Preset(listOf("window"), Place.COVER, 0.8f, light = true, warmth = 0.5f, golden = true),
    Preset(listOf("ray", "beam", "flare", "sun"), Place.COVER, 0.85f, light = true, warmth = 0.4f, golden = true),
    Preset(listOf("laser"), Place.COVER, 0.95f, light = true),
    Preset(listOf("light"), Place.COVER, 0.9f, light = true, warmth = 0.2f, golden = true),
    Preset(listOf("dust", "particle", "bokeh", "snow"), Place.COVER, 0.9f),
    Preset(listOf("fullscreen", "full"), Place.COVER, 0.75f),
    Preset(listOf("nebula"), Place.COVER, 0.8f),
    Preset(listOf("steam"), Place.MIDDLE, 0.8f, 0.7f, 0.75f),
    Preset(listOf("slope", "smoke", "structure"), Place.MIDDLE, 0.8f, 0.75f, 0.8f),
)

/** An overlay of no known kind: over the whole frame, gently. */
private val ANY = Preset(emptyList(), Place.COVER, 0.7f)

internal fun presetFor(category: String): Preset =
    PRESETS.firstOrNull { p -> p.words.any { category.contains(it, ignoreCase = true) } } ?: ANY

/** What an overlay is, looked at small: the part of it that is lit (fractions), where its light is strongest, across and down, and how bright that is. */
internal class Seen(val left: Float, val top: Float, val right: Float, val bottom: Float, val heart: Float, val peak: Float, val aspect: Float, val heartY: Float = 0.5f)

internal fun seen(small: Bitmap): Seen {
    val w = small.width
    val h = small.height
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    val light = FloatArray(px.size) { i -> (0.299f * (px[i] shr 16 and 255) + 0.587f * (px[i] shr 8 and 255) + 0.114f * (px[i] and 255)) / 255f }
    var x0 = w
    var y0 = h
    var x1 = 0
    var y1 = 0
    var sum = 0f
    var across = 0f
    var down = 0f
    for (y in 0 until h) for (x in 0 until w) {
        val l = light[y * w + x]
        if (l > 0.07f) {
            x0 = min(x0, x)
            y0 = min(y0, y)
            x1 = max(x1, x + 1)
            y1 = max(y1, y + 1)
        }
        // the brightest of it is where the light comes from
        val k = l * l * l
        sum += k
        across += k * (x + 0.5f) / w
        down += k * (y + 0.5f) / h
    }
    if (x1 <= x0 || y1 <= y0) return Seen(0f, 0f, 1f, 1f, 0.5f, 0.1f, w.toFloat() / h)
    val sorted = light.sortedArray()
    return Seen(x0.toFloat() / w, y0.toFloat() / h, x1.toFloat() / w, y1.toFloat() / h, across / sum.coerceAtLeast(1e-6f), sorted[(sorted.size * 0.997f).toInt().coerceAtMost(sorted.size - 1)], w.toFloat() / h, down / sum.coerceAtLeast(1e-6f))
}

/** The picture under an overlay, looked at small: how bright it is, which side its light is on (-1 left to 1 right), and how warm it is (-1 to 1). */
internal class Scene(val bright: Float, val side: Float, val warm: Float)

internal fun sceneOf(small: Bitmap): Scene {
    val w = small.width
    val h = small.height
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    var sum = 0f
    var left = 0f
    var right = 0f
    var r = 0f
    var b = 0f
    for (i in px.indices) {
        val red = (px[i] shr 16 and 255) / 255f
        val green = (px[i] shr 8 and 255) / 255f
        val blue = (px[i] and 255) / 255f
        val l = 0.299f * red + 0.587f * green + 0.114f * blue
        sum += l
        if (i % w < w / 2) left += l * l else right += l * l
        r += red * l
        b += blue * l
    }
    val n = px.size.coerceAtLeast(1)
    return Scene(sum / n, ((right - left) / (right + left).coerceAtLeast(1e-4f)).coerceIn(-1f, 1f), ((r - b) / (r + b).coerceAtLeast(1e-4f) * 2f).coerceIn(-1f, 1f))
}

/**
 * How [seen], an overlay of kind [preset], is laid over [scene] on a frame [frameAspect] wide for its height:
 * its light coming from the side the picture's does, as strong as the picture's brightness bears, and lifted
 * where it is dim of itself.
 */
internal fun laid(preset: Preset, seen: Seen, scene: Scene, frameAspect: Float, golden: Boolean = false): Laid {
    // screened over a bright picture an overlay washes it out; over a dark one it hardly shows
    val bears = when {
        scene.bright < 0.25f -> 1.12f
        scene.bright < 0.5f -> 1.12f - (scene.bright - 0.25f) * 0.6f
        else -> (0.97f - (scene.bright - 0.5f) * 0.9f).coerceAtLeast(0.6f)
    }
    val opacity = (preset.strength * bears).coerceIn(0.25f, 1f)
    val flip = preset.light && abs(scene.side) > 0.06f && abs(seen.heart - 0.5f) > 0.08f && (seen.heart > 0.5f) != (scene.side > 0)
    val x0 = if (flip) 1f - seen.right else seen.left
    val x1 = if (flip) 1f - seen.left else seen.right
    // a scale of 1 fits the whole picture in the frame
    val fitW = if (seen.aspect >= frameAspect) 1f else seen.aspect / frameAspect
    val fitH = if (seen.aspect >= frameAspect) frameAspect / seen.aspect else 1f
    val cover = max(1f / fitW, 1f / fitH)
    val scale: Float
    var x = 0f
    var y = 0f
    if (preset.place == Place.COVER) {
        scale = cover * 1.02f
        if (golden && abs(scene.side) > 0.06f) {
            // where the picture is wider than the frame, the light comes in from the side the photo's does
            val across = fitW * scale
            val heart = if (flip) 1f - seen.heart else seen.heart
            val want = if (scene.side > 0) 0.84f else 0.16f
            val room = ((across - 1f) / 2f).coerceAtLeast(0f)
            x = (want - 0.5f - (heart - 0.5f) * across).coerceIn(-room, room)
        }
    } else {
        scale = min(preset.wide / ((x1 - x0) * fitW).coerceAtLeast(0.05f), preset.tall / ((seen.bottom - seen.top) * fitH).coerceAtLeast(0.05f))
            .coerceIn(0.4f, cover * 2.2f)
        val w = fitW * scale
        val h = fitH * scale
        // the middle of its lit part, from the middle of the frame
        x = -((x0 + x1) / 2f - 0.5f) * w
        val litTop = (seen.top - 0.5f) * h
        val litBottom = (seen.bottom - 0.5f) * h
        y = when (preset.place) {
            Place.TOP -> -0.5f + 0.06f - litTop
            Place.BOTTOM -> 0.5f + 0.03f - litBottom
            else -> -(litTop + litBottom) / 2f + 0.08f
        }
    }
    val adjust = JSONObject()
    // its light the colour of the picture's own, and of what it is: fewer kelvin is warmer
    val warm = (scene.warm * 0.6f + preset.warmth * 0.6f).coerceIn(-1f, 1f)
    if (!golden && preset.light && abs(warm) > 0.08f) adjust.put("temperature", (6500 - warm * 2200).roundToInt())
    return Laid("screen", opacity.toDouble(), scale.toDouble(), x.toDouble(), y.toDouble(), 0.0, flip, if (adjust.length() == 0) "" else adjust.toString())
}

/**
 * A colour of light: what it is where faint, in the beam and at its heart (red, green, blue, 0 to 1), and what it
 * makes of the picture away from it and close to it, to multiply the picture by.
 */
internal class Tint(val name: String, val faint: FloatArray, val beam: FloatArray, val heart: FloatArray, val away: FloatArray, val close: FloatArray)

private fun c(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

internal val TINTS = listOf(
    Tint("Golden", c(1f, 0.46f, 0.08f), c(1f, 0.66f, 0.24f), c(1f, 0.92f, 0.66f), c(0.9f, 0.665f, 0.43f), c(1f, 0.83f, 0.55f)),
    Tint("Sunset", c(1f, 0.3f, 0.06f), c(1f, 0.5f, 0.16f), c(1f, 0.85f, 0.6f), c(0.88f, 0.58f, 0.42f), c(1f, 0.74f, 0.5f)),
    Tint("Warm white", c(1f, 0.75f, 0.5f), c(1f, 0.9f, 0.75f), c(1f, 0.98f, 0.92f), c(0.95f, 0.88f, 0.78f), c(1f, 0.95f, 0.86f)),
    Tint("Pink", c(1f, 0.35f, 0.55f), c(1f, 0.55f, 0.72f), c(1f, 0.9f, 0.93f), c(0.9f, 0.72f, 0.8f), c(1f, 0.84f, 0.88f)),
    Tint("Blue hour", c(0.25f, 0.45f, 1f), c(0.5f, 0.72f, 1f), c(0.9f, 0.96f, 1f), c(0.72f, 0.8f, 0.95f), c(0.86f, 0.92f, 1f)),
)

/** The colour of light of [tint] [l] bright (0 to 1), each channel 0 to 1: faint, in the beam, near white at its heart. */
internal fun tintAt(tint: Tint, l: Float): FloatArray {
    val t = (l / 0.55f).coerceIn(0f, 1f)
    val u = ((l - 0.55f) / 0.45f).coerceIn(0f, 1f)
    return FloatArray(3) { k -> l * ((tint.faint[k] * (1 - t) + tint.beam[k] * t) * (1 - u) + tint.heart[k] * u) }
}

/**
 * Where an overlay's black is: a little above the level most of its dark is at, so that its grain and the noise
 * of its file go with it. Below that it is taken away, and so leaves the picture under it as it was.
 */
internal fun blackOf(small: Bitmap): Float {
    val w = small.width
    val h = small.height
    val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
    val counts = IntArray(256)
    for (p in px) counts[((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) shr 8]++
    var seen = 0
    var quarter = 0
    while (quarter < 255 && seen + counts[quarter] < px.size / 4) seen += counts[quarter++]
    return ((quarter + 4) / 255f).coerceAtMost(0.16f)
}

/**
 * [picture] with what is below [black] gone to black, and the rest lifted by [lift]: an overlay whose dark leaves
 * the picture under it untouched however it is brightened.
 */
internal fun cleaned(picture: Bitmap, black: Float, lift: Float): Bitmap {
    val w = picture.width
    val h = picture.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val floor = black * 255f
    val table = IntArray(256) { v ->
        val t = ((v - floor) / (255f - floor)).coerceAtLeast(0f)
        // a soft toe, so what is just above black fades in rather than steps
        val toe = if (t < 0.06f) t * t / 0.12f else t - 0.03f
        (toe * lift * 255f).roundToInt().coerceIn(0, 255)
    }
    val row = IntArray(w)
    for (y in 0 until h) {
        picture.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val p = row[x]
            row[x] = (0xFF shl 24) or (table[p shr 16 and 255] shl 16) or (table[p shr 8 and 255] shl 8) or table[p and 255]
        }
        out.setPixels(row, 0, w, 0, y, w, 1)
    }
    return out
}

/**
 * [picture], an overlay of light on black, made the sun's: its beams yellow whatever colour they were, lifted
 * where they are faint, and a glow about their heart, [reach] pixels of it to one of the frame's shorter sides.
 */
internal fun golden(picture: Bitmap, seen: Seen, reach: Float, tint: Tint = TINTS[0], glowing: Float = 1f, black: Float = 0f): Bitmap {
    val w = picture.width
    val h = picture.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val lift = min(1f / seen.peak.coerceAtLeast(0.2f), 2.2f) * 0.8f
    val colors = IntArray(256) { i ->
        val c = tintAt(tint, i / 255f)
        (0xFF shl 24) or ((c[0] * 255).roundToInt() shl 16) or ((c[1] * 255).roundToInt() shl 8) or (c[2] * 255).roundToInt()
    }
    // the glow by the square of the distance, so that no pixel takes a square root
    val steps = 2048
    val far = 6f
    val glow = FloatArray(steps + 1) { i ->
        val d2 = i * far / steps
        (glowing * (0.6 * exp(-d2 / (0.24 * 0.24)) + 0.14 * exp(-d2 / (0.45 * 0.45)))).toFloat()
    }
    val cx = seen.heart * w
    val cy = seen.heartY * h
    val row = IntArray(w)
    for (y in 0 until h) {
        picture.getPixels(row, 0, w, 0, y, w, 1)
        val dy = (y - cy) / reach
        for (x in 0 until w) {
            val p = row[x]
            val light = ((((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) / 65280f - black) / (1f - black)).coerceAtLeast(0f)
            val dx = (x - cx) / reach
            val g = glow[((dx * dx + dy * dy) * steps / far).toInt().coerceAtMost(steps)]
            // half a level of noise keeps the glow from showing steps
            val noise = ((x * 73856093 xor y * 19349663) and 255) / 255f - 0.5f
            val l = 1f - (1f - min(light * lift, 1f)) * (1f - g)
            row[x] = colors[(l * 255f + 0.5f + noise * 0.8f).toInt().coerceIn(0, 255)]
        }
        out.setPixels(row, 0, w, 0, y, w, 1)
    }
    return out
}

/** The warmth that golden light from ([x], [y]) of a frame [w] by [h] gives the picture, to multiply it by. */
internal fun warmth(w: Int, h: Int, x: Float, y: Float, tint: Tint = TINTS[0], amount: Float = 1f): Bitmap {
    val px = IntArray(w * h)
    val reach = min(w, h).toFloat()
    for (j in 0 until h) for (i in 0 until w) {
        val dx = (i - x * w) / reach
        val dy = (j - y * h) / reach
        val near = exp(-(dx * dx + dy * dy) / 0.81f)
        val noise = ((i * 73856093 xor j * 19349663) and 255) / 255f - 0.5f
        fun at(away: Float, close: Float) = ((away * (1 - near) + close * near) * 255f + 0.5f + noise).toInt().coerceIn(0, 255)
        fun of(k: Int) = at(1f - (1f - tint.away[k]) * amount, 1f - (1f - tint.close[k]) * amount)
        px[j * w + i] = (0xFF shl 24) or (of(0) shl 16) or (of(1) shl 8) or of(2)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** The folders of overlays the user chose: their own files, read where they are. */
internal object OverlayFolders {
    private const val FILE = "capcraft-overlays"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = prefs(context).getString("folder", null)?.let(Uri::parse)

    fun set(context: Context, tree: Uri?) {
        folder(context)?.let { old -> if (old != tree) runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (tree != null) context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit().putString("folder", tree?.toString()).apply()
    }

    fun favorites(context: Context): Set<String> = prefs(context).getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun setFavorites(context: Context, values: Set<String>) = prefs(context).edit().putStringSet("favorites", values).apply()
    fun recent(context: Context): List<String> = prefs(context).getString("recent", "").orEmpty().split('\n').filter(String::isNotBlank)
    fun setRecent(context: Context, values: List<String>) = prefs(context).edit().putString("recent", values.joinToString("\n")).apply()

    /** The colour light of the sun's kind is laid in: one of [TINTS] by name, or "Original" as the overlay has it. */
    fun tint(context: Context): String = prefs(context).getString("tint", null) ?: if (prefs(context).getBoolean("golden", true)) TINTS[0].name else "Original"

    fun setTint(context: Context, name: String) = prefs(context).edit().putString("tint", name).apply()

    /** How much the light glows about its heart, 0 to 1.5. */
    fun glow(context: Context): Float = prefs(context).getFloat("glow", 0.8f)

    /** How much the light warms the picture, 0 to 1. */
    fun warmth(context: Context): Float = prefs(context).getFloat("warmth", 0.66f)

    fun setLook(context: Context, glow: Float, warmth: Float) = prefs(context).edit().putFloat("glow", glow).putFloat("warmth", warmth).apply()

    /** Whether light is laid as in the scene (3D: the people in front of it, casting shadows away from it) or flat over the picture (2D). */
    fun depth(context: Context): Boolean = prefs(context).getBoolean("depth", true)

    fun setDepth(context: Context, on: Boolean) = prefs(context).edit().putBoolean("depth", on).apply()

    private val PICTURES = setOf("jpg", "jpeg", "png", "webp")

    fun read(context: Context): List<OverlayFile> {
        val original = OriginalAtmosphere.files(context)
        val tree = folder(context) ?: return original
        val root = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return original
        val found = ArrayList<OverlayFile>()
        val todo = ArrayDeque(listOf(Triple(root, "叠加素材", 0)))
        while (todo.isNotEmpty() && found.size < 4000) {
            val (id, category, depth) = todo.removeFirst()
            runCatching {
                context.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 4) todo.add(Triple(c.getString(0), soundCategory(name.replace(Regex("\\s*overlays?$", RegexOption.IGNORE_CASE), "")), depth + 1))
                        } else if (name.substringAfterLast('.').lowercase() in PICTURES && !name.startsWith(".")) {
                            found.add(OverlayFile(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name.substringBeforeLast('.'), category))
                        }
                    }
                }
            }
        }
        return (original + found).sortedWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))
    }

    private val small = LruCache<String, Bitmap>(220)

    /** [file] made small, kept in memory and in the cache. */
    fun thumb(context: Context, file: OverlayFile): Bitmap? {
        val key = file.uri.toString()
        small.get(key)?.let { return it }
        val kept = File(File(context.cacheDir, "overlay-thumbs").apply { mkdirs() }, Integer.toHexString(key.hashCode()) + "-" + key.length + ".jpg")
        val made = BitmapFactory.decodeFile(kept.path) ?: decode(context, file.uri, 220)?.also { b -> runCatching { kept.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } } }
        if (made != null) small.put(key, made)
        return made
    }

    /** The picture in [uri], its longer side [side] at the most. */
    fun decode(context: Context, uri: Uri, side: Int): Bitmap? = runCatching {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, size) }
        var by = 1
        while (max(size.outWidth, size.outHeight) / (by * 2) >= side) by *= 2
        val b = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = by }) } ?: return null
        val k = side.toFloat() / max(b.width, b.height)
        if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
    }.getOrNull()
}

/** What the panel last laid, taken off again when another is tried. */
private object Trying {
    var clip: String? = null
    var file by mutableStateOf<OverlayFile?>(null)
    var pending by mutableStateOf<OverlayFile?>(null)
    var id by mutableStateOf<String?>(null)
    /** The warmth golden light gives the picture, a layer of its own. */
    var warm by mutableStateOf<String?>(null)
    /** The shadow a light in 3D casts of the people. */
    var shadow by mutableStateOf<String?>(null)
    /** How the light now laid looks, for its shadow to be traced again when it is moved. */
    var seen: Seen? = null
    var frame = 1f
    var intensity = 0.8
    var key = 0xFFC468FFu
    var strength by mutableFloatStateOf(0.7f)
    var busy by mutableStateOf(false)
    /** The last one laid is behind the people. */
    @Volatile var behind = false
}

private class Laying(val view: TimelineView, val how: Laid, val id: String, val warm: String, val shadow: String = "")

private suspend fun lay(vm: EditorViewModel, context: Context, clip: ClipView, file: OverlayFile) {
    val s = vm.session ?: return
    val t = vm.timeline ?: return
    Trying.busy = true
    val at = vm.playhead.coerceIn(clip.startUs, clip.startUs + clip.durationUs - 1)
    val old = listOfNotNull(Trying.id, Trying.warm, Trying.shadow).filter { vm.clip(it) != null }
    val preset = presetFor(file.category)
    val tint = TINTS.firstOrNull { it.name == OverlayFolders.tint(context) }
    val sun = preset.golden && tint != null
    val glowing = OverlayFolders.glow(context)
    val warming = OverlayFolders.warmth(context)
    val deep = preset.light && OverlayFolders.depth(context)
    Trying.pending = file
    try {
        val result = withContext(Threads.commands) {
            runCatching {
            if (old.isNotEmpty()) s.delete(old)
            val h = 120
            val w = (h * t.width.toFloat() / t.height.toFloat()).roundToInt().coerceAtLeast(16)
            val scene = s.frame(at, w.toUInt(), h.toUInt(), Motion.STILL).intoBitmap(null)?.let(::sceneOf) ?: Scene(0.4f, 0f, 0f)
            // no larger than a frame wants it, so it stays quick to draw
            val picture = OverlayFolders.decode(context, file.uri, 2560) ?: error("无法读取该叠加素材。")
            val small = Bitmap.createScaledBitmap(picture, 128, (128f * picture.height / picture.width).roundToInt().coerceAtLeast(1), true)
            val dir = File(vm.path, "media").apply { mkdirs() }
            val out = Media.uniqueFile(dir, Media.safeName(file.name) + ".jpg")
            val frame = t.width.toFloat() / t.height.toFloat()
            val look = seen(small)
            val how = laid(preset, look, scene, frame, sun)
            val black = blackOf(small)
            var warm = ""
            // where the light is brightest on the frame, from its top left
            val fitW = if (look.aspect >= frame) 1f else look.aspect / frame
            val fitH = if (look.aspect >= frame) frame / look.aspect else 1f
            val heart = if (how.flip) 1f - look.heart else look.heart
            val hx = 0.5f + how.x.toFloat() + (heart - 0.5f) * fitW * how.scale.toFloat()
            val hy = 0.5f + how.y.toFloat() + (look.heartY - 0.5f) * fitH * how.scale.toFloat()
            if (sun) {
                // what of the picture the frame shows, its shorter side
                val tall = if (look.aspect >= frame) picture.height.toFloat() else picture.width / frame
                val made = golden(picture, look, min(tall, tall * frame), tint ?: TINTS[0], glowing, black)
                out.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                val gw = if (frame >= 1f) 640 else (640 * frame).roundToInt()
                val gh = if (frame >= 1f) (640 / frame).roundToInt() else 640
                val shade = Media.uniqueFile(dir, "Golden light.jpg")
                shade.outputStream().use { warmth(gw, gh, hx, hy, tint ?: TINTS[0], warming).compress(Bitmap.CompressFormat.JPEG, 95, it) }
                if (warming > 0.02f) warm = s.layOver(clip.id, shade.absolutePath, Laid("multiply", 0.9, 1.01, 0.0, 0.0, 0.0, false, "")).createdId
            } else {
                // brightened here, after its black is taken away, so the black stays black
                val lift = if (look.peak < 0.7f) min(0.8f / look.peak.coerceAtLeast(0.05f), if (preset.light) 4f else 2.5f) else 1f
                out.outputStream().use { cleaned(picture, black, lift).compress(Bitmap.CompressFormat.JPEG, 94, it) }
            }
            // the people stay in front of the next one tried, where they were of the last
            val stay = Trying.behind
            var view = s.layOver(clip.id, out.absolutePath, how)
            val made = view.createdId
            var copy = bodyOf(vm, clip.id)?.first
            var shadow = ""
            if (deep) {
                // light in the scene: the people cut out (once), in front of it, their shadow cast away from it
                if (copy == null) {
                    val done = kotlinx.coroutines.CompletableDeferred<TimelineView?>()
                    val listener = object : SpeechListener {
                        override fun onProgress(fraction: Float, stage: String) {}
                        override fun onDone(result: TimelineView?) { done.complete(result) }
                        override fun onFailed(message: String) { done.complete(null) }
                    }
                    runCatching { s.bodyEffect(clip.id, "front", listener) }.onFailure { done.complete(null) }
                    done.await()?.let { view = it }
                    copy = runCatching { JSONObject(s.extra("capcraftBody")).optJSONObject(clip.id)?.optString("copy") }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
                if (copy != null) {
                    // the person lit in the light's own colour, from its side
                    val beam = (tint ?: TINTS.first { it.name == "Warm white" }).beam
                    val key = ((beam[0] * 255).roundToInt() shl 24 or ((beam[1] * 255).roundToInt() shl 16) or ((beam[2] * 255).roundToInt() shl 8) or 0xFF).toUInt()
                    // as strong as the light: the overlay's strength and how bright its heart is
                    val intensity = (how.opacity * (look.peak * 1.25f).coerceIn(0.3f, 1f).toDouble()).coerceIn(0.1, 1.0)
                    Trying.seen = look
                    Trying.frame = frame
                    Trying.intensity = intensity
                    Trying.key = key
                    runCatching { s.castShadow(copy, hx.toDouble(), hy.toDouble(), intensity, key) }.onSuccess { v -> view = v; shadow = v.createdId }
                    view = s.restack(copy, 99)
                    if (warm.isNotEmpty()) view = s.restack(warm, 99)
                }
            } else if (copy != null && preset.light) {
                // light laid flat: no shadow, no light on the people
                view = s.unlightPerson(copy)
                if (stay) view = s.restack(copy, 99)
                if (stay && warm.isNotEmpty()) view = s.restack(warm, 99)
            } else if (stay && copy != null) {
                view = s.restack(copy, 99)
                // the people stand in the same light
                if (warm.isNotEmpty()) view = s.restack(warm, 99)
            }
                Laying(view, how, made, warm, shadow)
            }
        }
        result.onSuccess { done ->
            vm.applyView(done.view)
            Trying.id = done.id.ifEmpty { null }
            Trying.warm = done.warm.ifEmpty { null }
            Trying.shadow = done.shadow.ifEmpty { null }
            Trying.file = file
            Trying.strength = done.how.opacity.toFloat()
        }.onFailure { vm.message = it.userMessage }
    } finally {
        Trying.pending = null
        Trying.busy = false
    }
}

/**
 * The shadow of the light now laid traced again, from where its brightest point is now that it has
 * been scaled, turned or moved: in 3D, with the people cut out.
 */
private fun recast(vm: EditorViewModel, context: Context, clip: ClipView?, scope: kotlinx.coroutines.CoroutineScope) {
    val s = vm.session ?: return
    val id = Trying.id ?: return
    val look = Trying.seen ?: return
    val file = Trying.file ?: return
    if (clip == null || !presetFor(file.category).light || !OverlayFolders.depth(context)) return
    val copy = bodyOf(vm, clip.id)?.first ?: return
    val j = vm.clipJson(id) ?: return
    val scale = j.optDouble("scale", 1.0).toFloat()
    val x = j.optDouble("offsetX", 0.0).toFloat()
    val y = j.optDouble("offsetY", 0.0).toFloat()
    val frame = Trying.frame
    val fitW = if (look.aspect >= frame) 1f else look.aspect / frame
    val fitH = if (look.aspect >= frame) frame / look.aspect else 1f
    val heart = if (j.optBoolean("flipH")) 1f - look.heart else look.heart
    val heartY = if (j.optBoolean("flipV")) 1f - look.heartY else look.heartY
    val hx = 0.5f + x + (heart - 0.5f) * fitW * scale
    val hy = 0.5f + y + (heartY - 0.5f) * fitH * scale
    Trying.busy = true
    scope.launch {
        val view = withContext(Threads.commands) {
            runCatching {
                var v = s.castShadow(copy, hx.toDouble(), hy.toDouble(), Trying.intensity, Trying.key)
                Trying.shadow = v.createdId.ifEmpty { null }
                v = s.restack(copy, 99)
                Trying.warm?.let { w -> v = s.restack(w, 99) }
                v
            }
        }
        Trying.busy = false
        view.onSuccess { vm.applyView(it) }.onFailure { vm.message = it.userMessage }
    }
}

/**
 * Atmosphere: smoke, mist, light, dust and the like from a folder of the user's own overlays, each laid
 * as its kind is - where it belongs, blended, as strong as the picture bears, its light from the side the
 * picture's comes from. The files stay the user's: they are read from the folder and copied into the project.
 */
@Composable
fun AtmospherePanel(vm: EditorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = vm.selectedClip?.takeIf { it.kind == "video" || it.kind == "image" } ?: vm.mainClipAtPlayhead()
    var reads by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<OverlayFile>?>(null) }
    var category by remember { mutableIntStateOf(0) }
    var finding by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(OverlayFolders.favorites(context)) }
    var recent by remember { mutableStateOf(OverlayFolders.recent(context)) }
    var comparing by remember { mutableStateOf(false) }
    var compareUris by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(reads) { all = withContext(Dispatchers.IO) { OverlayFolders.read(context) } }
    DisposableEffect(clip?.id) {
        if (Trying.clip != clip?.id) {
            Trying.clip = clip?.id
            Trying.id = null
            Trying.warm = null
            Trying.file = null
        }
        onDispose {
            Trying.id = null
            Trying.warm = null
            Trying.file = null
            Trying.clip = null
        }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) runCatching { OverlayFolders.set(context, tree) }.onSuccess { all = null; category = 0; reads++ }.onFailure { vm.message = "Could not read that folder" }
    }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { finding = "${(fraction * 100).roundToInt()}%" }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    finding = null
                    result?.let { vm.applyView(it) }
                    Trying.warm?.let { w -> if (vm.clip(w) != null) vm.edit { restack(w, 99) } }
                }
            }

            override fun onFailed(message: String) {
                scope.launch(Dispatchers.Main) {
                    finding = null
                    vm.message = message
                }
            }
        }
    }
    // whether the people's copy is in front of the overlay: the boxes come the front one first
    val front by produceState(false, vm.timeline?.revision, Trying.id, clip?.id) {
        val over = Trying.id
        val copy = clip?.let { bodyOf(vm, it.id) }?.first
        val s = vm.session
        val at = clip?.let { vm.playhead.coerceIn(it.startUs, it.startUs + it.durationUs - 1) } ?: 0L
        value = if (over == null || copy == null || s == null) false else withContext(Threads.commands) {
            runCatching { s.clipBoxes(at).map { it.clipId } }.getOrDefault(emptyList()).let { o -> copy in o && over in o && o.indexOf(copy) < o.indexOf(over) }
        }
    }

    if (Trying.id != null) Trying.behind = front
    PanelFrame("氛围效果", vm, tall = true, titleOnTop = true, left = {
        if (Trying.id != null && !Trying.busy) {
            Text(
                "移除",
                style = Type.Small.copy(color = Palette.DockText),
                modifier = Modifier.clickable {
                    val id = Trying.id ?: return@clickable
                    vm.edit { delete(listOfNotNull(id, Trying.warm?.takeIf { w -> vm.clip(w) != null }, Trying.shadow?.takeIf { w -> vm.clip(w) != null })) }
                    Trying.id = null
                    Trying.warm = null
                    Trying.shadow = null
                    Trying.file = null
                }.padding(8.dp),
            )
        }
    }) {
        val list = all
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在读取叠加素材", style = Type.Body.copy(color = Palette.TextSecondary)) }
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("该文件夹中没有图片。", style = Type.Body.copy(color = Palette.TextSecondary))
                Spacer(Modifier.height(14.dp))
                WideButton("选择其他文件夹") { choose.launch(shelf("图片")) }
            }
            else -> {
                val categories = remember(list) { list.map { it.category }.distinct() }
                Tabs(categories, category) { category = it }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("全部", category == -1) { category = -1 }
                    Choice("★ 收藏", category == -2) { category = -2 }
                    Choice("最近使用", category == -3) { category = -3 }
                    Choice("对比", comparing) { comparing = !comparing; compareUris = emptyList() }
                }
                if (comparing) {
                    val selected = compareUris.mapNotNull { key -> list.firstOrNull { it.uri.toString() == key } }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        selected.forEach { CompareThumb(it) }
                        if (selected.size < 2) Text("再选择 ${2 - selected.size} 个进行对比", style = Type.Small.copy(color = Palette.TextSecondary), modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
                Text(
                    "内置效果在本机生成 · 添加文件夹以使用自己的叠加素材",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    style = Type.Small.copy(color = Palette.TextSecondary),
                )
                if (Trying.id != null && vm.clip(Trying.id!!) != null) {
                    // one setting at a time: how strong, how big, which way turned, and where on each axis
                    var setting by remember { mutableStateOf("强度") }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("强度", "缩放", "旋转", "水平位置", "垂直位置").forEach { name -> Choice(name, name == setting) { setting = name } }
                    }
                    val id = Trying.id!!
                    val now = remember(id, vm.timeline?.revision) { vm.clipJson(id) }
                    fun place(key: String, value: Double) =
                        vm.command(JSONObject().put("op", "setClipTransform").put("clipId", id).put(key, value), "atmosphere")
                    when (setting) {
                        "缩放" -> ValueSlider(
                            value = ((now?.optDouble("scale", 1.0) ?: 1.0) * 100).toFloat(), range = 20f..400f, label = { "${it.roundToInt()}%" },
                            onChange = { place("scale", it / 100.0) }, onDone = { vm.endGesture(); recast(vm, context, clip, scope) }, title = "缩放",
                        )
                        "旋转" -> ValueSlider(
                            value = (now?.optDouble("rotation", 0.0) ?: 0.0).toFloat().coerceIn(-180f, 180f), range = -180f..180f, label = { "${it.roundToInt()}°" },
                            onChange = { place("rotation", it.toDouble()) }, onDone = { vm.endGesture(); recast(vm, context, clip, scope) }, title = "旋转",
                        )
                        "水平位置" -> ValueSlider(
                            value = ((now?.optDouble("offsetX", 0.0) ?: 0.0) * 100).toFloat().coerceIn(-100f, 100f), range = -100f..100f, label = { "${it.roundToInt()}" },
                            onChange = { place("offsetX", it / 100.0) }, onDone = { vm.endGesture(); recast(vm, context, clip, scope) }, title = "左右",
                        )
                        "垂直位置" -> ValueSlider(
                            value = ((now?.optDouble("offsetY", 0.0) ?: 0.0) * 100).toFloat().coerceIn(-100f, 100f), range = -100f..100f, label = { "${it.roundToInt()}" },
                            onChange = { place("offsetY", it / 100.0) }, onDone = { vm.endGesture(); recast(vm, context, clip, scope) }, title = "上下",
                        )
                        else -> ValueSlider(
                            value = Trying.strength * 100f, range = 0f..100f, label = { "${it.roundToInt()}" },
                            onChange = { v ->
                                Trying.strength = v / 100f
                                Trying.id?.let { vm.patch(it, JSONObject().put("opacity", (v / 100f).toDouble()), "atmosphere") }
                                Trying.warm?.takeIf { vm.clip(it) != null }?.let { vm.patch(it, JSONObject().put("opacity", min(1f, v / 100f * 1.1f).toDouble()), "atmosphere") }
                            },
                            onDone = { vm.endGesture() }, title = "强度",
                        )
                    }
                    val lit = Trying.file?.takeIf { presetFor(it.category).golden }
                    if (lit != null && clip != null) {
                        val again = { if (!Trying.busy) scope.launch { lay(vm, context, clip, lit) } }
                        val tintName = remember(Trying.id) { OverlayFolders.tint(context) }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (TINTS.map { it.name } + "Original").forEach { name ->
                                Choice(name, name == tintName) {
                                    OverlayFolders.setTint(context, name)
                                    again()
                                }
                            }
                        }
                        if (tintName != "Original") {
                            var glow by remember { mutableFloatStateOf(OverlayFolders.glow(context)) }
                            var warming by remember { mutableFloatStateOf(OverlayFolders.warmth(context)) }
                            ValueSlider(
                                value = glow * 100f, range = 0f..150f, label = { "${it.roundToInt()}" },
                                onChange = { glow = it / 100f }, onDone = { OverlayFolders.setLook(context, glow, warming); again() }, title = "发光",
                            )
                            ValueSlider(
                                value = warming * 100f, range = 0f..100f, label = { "${it.roundToInt()}" },
                                onChange = { warming = it / 100f }, onDone = { OverlayFolders.setLook(context, glow, warming); again() }, title = "暖色照片",
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("动画", false) {
                            val overlay = vm.clip(id) ?: return@Choice
                            val length = overlay.durationUs.coerceAtLeast(1).toDouble()
                            val keys = JSONArray()
                            listOf(
                                Triple(0.0, -0.025, 0.98),
                                Triple(0.5, 0.025, 1.04),
                                Triple(1.0, -0.025, 0.98),
                            ).forEach { (at, x, scale) ->
                                keys.put(JSONObject().put("op", "setClipKey").put("clipId", id).put("property", "offsetX").put("at", at).put("value", x))
                                keys.put(JSONObject().put("op", "setClipKey").put("clipId", id).put("property", "scale").put("at", at).put("value", scale))
                            }
                            vm.command(JSONObject().put("op", "batch").put("commands", keys), "atmosphere-motion")
                            vm.message = "已添加轻柔漂移动画，时长 ${(length / 1_000_000.0).toInt().coerceAtLeast(1)} 秒"
                        }
                        Choice("镜像", false) {
                            val id = Trying.id ?: return@Choice
                            val now = vm.clipJson(id)?.optBoolean("flipH") ?: false
                            vm.patch(id, JSONObject().put("flipH", !now))
                        }
                        Choice("翻转", false) {
                            // upside down, about the other axis
                            val id = Trying.id ?: return@Choice
                            val now = vm.clipJson(id)?.optBoolean("flipV") ?: false
                            vm.patch(id, JSONObject().put("flipV", !now))
                        }
                        val light = Trying.file?.takeIf { presetFor(it.category).light }
                        if (light != null && clip != null) {
                            var deep by remember { mutableStateOf(OverlayFolders.depth(context)) }
                            Choice(if (deep) "3D 光效" else "2D 光效", deep) {
                                deep = !deep
                                OverlayFolders.setDepth(context, deep)
                                // laid again the new way: the shadow cast, or taken away
                                if (!Trying.busy) scope.launch { lay(vm, context, clip, light) }
                            }
                        }
                        Choice(if (finding != null) "Finding people ${finding}" else "人物后方", front) {
                            val s = vm.session ?: return@Choice
                            val c = clip ?: return@Choice
                            if (finding != null) return@Choice
                            val over = Trying.id ?: return@Choice
                            val body = bodyOf(vm, c.id)
                            when {
                                // the people are cut out already, for their light or an effect: one or the other goes to the front
                                body != null && !front -> {
                                    vm.edit { restack(body.first, 99) }
                                    Trying.warm?.let { w -> if (vm.clip(w) != null) vm.edit { restack(w, 99) } }
                                }
                                body != null && body.second != "front" -> vm.edit { restack(over, 99) }
                                else -> {
                                    finding = ""
                                    scope.launch(Threads.commands) {
                                        runCatching { s.bodyEffect(c.id, if (front) "" else "front", listener) }.onFailure { e -> withContext(Dispatchers.Main) { finding = null; vm.message = e.userMessage } }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Choice("保留", false) {
                            // the next one goes over this one rather than in its place
                            Trying.id = null
                            Trying.warm = null
                            Trying.shadow = null
                            Trying.file = null
                        }
                    }
                }
                val byUri = list.associateBy { it.uri.toString() }
                val ordered = when (category) {
                    -1 -> list
                    -2 -> favorites.mapNotNull(byUri::get)
                    -3 -> recent.mapNotNull(byUri::get)
                    else -> list.filter { it.category == categories.getOrNull(category) }
                }
                val shown = ordered
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { f ->
                        Thumb(f, f.uri == Trying.file?.uri || f.uri == Trying.pending?.uri, Trying.busy && f.uri == Trying.pending?.uri, f.uri.toString() in favorites, {
                            val key = f.uri.toString()
                            favorites = (favorites - key).let { if (key in favorites) it else it + key }
                            OverlayFolders.setFavorites(context, favorites)
                        }) {
                            if (comparing) {
                                val key = f.uri.toString()
                                compareUris = if (key in compareUris) compareUris - key else (compareUris + key).takeLast(2)
                            } else if (clip == null) vm.message = "请先添加片段"
                            else if (!Trying.busy) {
                                recent = (listOf(f.uri.toString()) + recent.filterNot { it == f.uri.toString() }).take(24)
                                OverlayFolders.setRecent(context, recent)
                                scope.launch { lay(vm, context, clip, f) }
                            }
                        }
                    }
                    item { Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2E2E2E)).clickable { choose.launch(shelf("图片")) }, contentAlignment = Alignment.Center) { Glyph(LucideIcons.FolderOpen, Palette.DockText, size = 22.dp, description = "其他文件夹") } }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else Color(0xFF3A3A3A)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.Small.copy(fontSize = 13.sp, color = if (on) Palette.OnAccent else Palette.Text)) }
}

@Composable
private fun Thumb(file: OverlayFile, on: Boolean, busy: Boolean, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val picture by produceState<Bitmap?>(null, file.uri) { value = withContext(Dispatchers.IO) { OverlayFolders.thumb(context, file) } }
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.Black)
            .border(2.dp, if (on) Palette.Accent else Color(0xFF2E2E2E), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        picture?.let { Image(it.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text(
            file.name,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).padding(horizontal = 4.dp, vertical = 3.dp),
            style = Type.Small.copy(color = Color.White, fontSize = 9.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(if (favorite) "★" else "☆", Modifier.align(Alignment.TopEnd).clickable(onClick = onFavorite).padding(horizontal = 7.dp, vertical = 3.dp), style = Type.Body.copy(color = Color.White))
        if (busy) Box(Modifier.fillMaxSize().background(Color(0x88000000)))
    }
}

@Composable
private fun CompareThumb(file: OverlayFile) {
    val context = LocalContext.current
    val picture by produceState<Bitmap?>(null, file.uri) { value = withContext(Dispatchers.IO) { OverlayFolders.thumb(context, file) } }
    Column(Modifier.fillMaxWidth(0.46f), horizontalAlignment = Alignment.CenterHorizontally) {
        picture?.let { Image(it.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp))) }
        Text(file.name, style = Type.Small.copy(color = Palette.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
