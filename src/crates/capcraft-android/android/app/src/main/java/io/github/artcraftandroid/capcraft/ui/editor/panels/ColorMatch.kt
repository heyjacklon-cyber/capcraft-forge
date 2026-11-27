package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.Motion
import io.github.artcraftandroid.capcraft.core.Picture
import io.github.artcraftandroid.capcraft.core.colorMatch
import io.github.artcraftandroid.capcraft.core.still
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.engine.toBitmap
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The colour match package. */
internal const val COLOR_MATCH = "capcraft.color-match"

/** The straightening, which stays first on a chain: the looks are laid on the turned picture. */
private const val STRAIGHTEN_FIRST = "capcraft.straighten"

/** How large the pictures compared are, along their longer side. */
private const val COMPARED = 160

/** Whether [clip] can be matched: it has a picture of its own. */
internal fun matchable(clip: ClipView?): Boolean = clip != null && (clip.kind == "video" || clip.kind == "image")

/** The colour match on [clipId], or null when it has none. */
internal fun colorMatchOn(vm: EditorViewModel, clipId: String?): JSONObject? {
    val chain = clipId?.let { vm.clipJson(it) }?.optJSONArray("videoEffects") ?: return null
    return (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == COLOR_MATCH }?.let { it.optJSONObject("params") ?: JSONObject() }
}

/** The colour match on [clipId] set to [params], or taken off (null). It is laid before the clip's other looks, as it was worked out from the bare picture. */
private fun setColorMatch(vm: EditorViewModel, clipId: String, params: JSONObject?, gesture: String? = null) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: return
    val others = (0 until chain.length()).map { chain.getJSONObject(it) }.filter { it.optString("id") != COLOR_MATCH }
    val next = JSONArray()
    others.filter { it.optString("id") == STRAIGHTEN_FIRST }.forEach { next.put(it) }
    if (params != null) next.put(JSONObject().put("id", COLOR_MATCH).put("params", params))
    others.filter { it.optString("id") != STRAIGHTEN_FIRST }.forEach { next.put(it) }
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/** A bitmap as the engine's picture. */
private fun Bitmap.toPicture(): Picture {
    val b = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val bytes = ByteArray(b.width * b.height * 4)
    b.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
    return Picture(b.width.toUInt(), b.height.toUInt(), bytes)
}

/** The photo at [uri], small. */
private fun smallPhoto(vm: EditorViewModel, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(vm.getApplication<android.app.Application>().contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = COMPARED.toFloat() / max(info.size.width, info.size.height).coerceAtLeast(1)
        if (scale < 1f) {
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    }
}

/** Where in its file [clip] is at the playhead, or at its start when the playhead is off it. */
private fun sourceTime(vm: EditorViewModel, clip: ClipView): Long {
    if (clip.kind != "video") return 0L
    val speed = clip.speed.takeIf { it > 0 } ?: 1.0
    return (clip.sourceStartUs + ((vm.playhead - clip.startUs).coerceIn(0, clip.durationUs) * speed).toLong())
        .coerceIn(clip.sourceStartUs, maxOf(clip.sourceStartUs, clip.mediaDurationUs - 50_000))
}

/**
 * Color match: the clip given the colours of a frame of the timeline, found with the slider, or of a
 * photo; then how much of the match is on. Reset takes it off.
 */
@Composable
internal fun ColumnScope.ColorMatchSheet(vm: EditorViewModel, clip: ClipView, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val session = vm.session
    val timeline = vm.timeline
    val end = (timeline?.durationUs ?: 0L).coerceAtLeast(1L)
    var at by remember(clip.id) { mutableFloatStateOf(vm.playhead.toFloat() / end) }
    // the photo chosen as the reference, in place of a frame
    var photo by remember(clip.id) { mutableStateOf<Bitmap?>(null) }
    var working by remember(clip.id) { mutableStateOf(false) }
    val time = (at.toDouble() * end).toLong().coerceIn(0L, (end - 1).coerceAtLeast(0L))
    val (w, h) = remember(timeline?.width, timeline?.height) {
        val tw = (timeline?.width?.toInt() ?: 16).coerceAtLeast(1)
        val th = (timeline?.height?.toInt() ?: 9).coerceAtLeast(1)
        val scale = COMPARED.toFloat() / max(tw, th)
        (tw * scale).roundToInt().coerceAtLeast(2) to (th * scale).roundToInt().coerceAtLeast(2)
    }
    // the frame under the slider, made once the finger slows
    val frame by produceState<Bitmap?>(null, session, time, w, h) {
        delay(60)
        val s = session ?: return@produceState
        withContext(Threads.thumbnails) { runCatching { s.frame(time, w.toUInt(), h.toUInt(), Motion.SCRUBBING).toBitmap() }.getOrNull() }?.let { value = it }
    }

    /** Matches the clip to [reference]. */
    fun match(reference: suspend () -> Bitmap?) {
        if (working) return
        working = true
        val from = sourceTime(vm, clip)
        scope.launch {
            val found = withContext(Threads.thumbnails) {
                runCatching {
                    val to = reference() ?: return@runCatching null
                    JSONObject(colorMatch(to.toPicture(), still(clip.path, from, COMPARED.toUInt())))
                }.getOrNull()
            }
            working = false
            if (found == null) {
                vm.message = "无法读取图片"
            } else {
                found.put("strength", colorMatchOn(vm, clip.id)?.optDouble("strength", 100.0) ?: 100.0)
                setColorMatch(vm, clip.id, found)
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Threads.thumbnails) { runCatching { smallPhoto(vm, uri) }.getOrNull() }
                if (picked == null) {
                    vm.message = "无法读取照片"
                } else {
                    photo = picked
                    match { picked }
                }
            }
        }
    }
    val on = remember(clip.json) { colorMatchOn(vm, clip.id) }
    SheetHead("色彩匹配", {
        photo = null
        if (on != null) setColorMatch(vm, clip.id, null)
    }, close)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            (photo ?: frame)?.let { Image(it.asImageBitmap(), contentDescription = "参考画面", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (photo != null) "一张照片" else "The frame at ${clock(time)}", style = Type.Small)
            CapSlider(
                value = at,
                range = 0f..1f,
                label = null,
                onChange = {
                    photo = null
                    at = it
                },
                onDone = {},
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MatchButton("匹配此画面", LucideIcons.Film, enabled = !working && photo == null) {
                    match { session?.frame(time, w.toUInt(), h.toUInt(), Motion.STILL)?.toBitmap() }
                }
                MatchButton("照片", LucideIcons.Image, enabled = !working) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (on != null) {
        var held by remember(clip.id) { mutableStateOf<Float?>(null) }
        ValueSlider(
            value = held ?: on.optDouble("strength", 100.0).toFloat(),
            range = 0f..100f,
            label = { "${it.roundToInt()}" },
            onChange = {
                held = it
                setColorMatch(vm, clip.id, JSONObject(on.toString()).put("strength", it.roundToInt()), "color-match")
            },
            onDone = {
                held = null
                vm.endGesture()
            },
            title = "匹配强度",
        )
    } else {
        Text(
            if (working) "正在读取图片…" else "选择一个视频画面或照片作为色彩参考。",
            style = Type.Small,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
        )
    }
}

/** A small button of the sheet's. */
@Composable
private fun MatchButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Palette.DockText else Palette.Disabled
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3A3A)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, tint, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.Small.copy(color = tint))
    }
}
