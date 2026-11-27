package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.capcraft.core.ClipBox
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.Patch
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.Thing
import io.github.artcraftandroid.capcraft.core.ThingListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.cancelObjects
import io.github.artcraftandroid.capcraft.core.objectsDownload
import io.github.artcraftandroid.capcraft.core.releaseObjects
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something chosen in the photo: its outline, and the outline to draw. */
private class Marked(val patch: Patch, val picture: ImageBitmap)

/** The most things offered at once. */
private const val MOST_THINGS = 24
/** A painted outline's side, pixels. */
private const val PAINT = 640

/** What Remove objects has found in the photo under it and what is chosen, while it is open. */
private object Erasing {
    /** The photo looked at: the choices are its. */
    var path by mutableStateOf<String?>(null)
    var things by mutableStateOf<List<Thing>?>(null)
    /** The found things chosen, by their place in the list. */
    val picked = mutableStateMapOf<Int, Marked>()
    /** What was tapped or painted besides. */
    val extra = mutableStateListOf<Marked>()
    var painting by mutableStateOf(false)
    var brush by mutableFloatStateOf(0.06f)
    /** What is being done, and how far it is, 0 to 1 (under 0 when it cannot be told). */
    var job by mutableStateOf<Pair<String, Float>?>(null)
    /** The thing whose outline is on its way. */
    var asked by mutableStateOf<Int?>(null)

    fun forget() {
        things = null
        picked.clear()
        extra.clear()
        asked = null
    }

    val chosen: List<Marked> get() = picked.toSortedMap().values + extra
}

/** An outline to draw over the photo. */
private fun drawn(patch: Patch): ImageBitmap {
    val pixels = IntArray(patch.mask.size) { i -> ((patch.mask[i].toInt() and 255) * 150 / 255 shl 24) or 0x00C8E0 }
    return Bitmap.createBitmap(pixels, patch.maskWidth.toInt(), patch.maskHeight.toInt(), Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** What a found thing is called: "人物 2" where there are more of its kind. */
private fun nameOf(things: List<Thing>, index: Int): String {
    val thing = things[index]
    val name = thing.kind.replaceFirstChar { it.uppercase() }
    val same = things.indices.filter { things[it].kind == thing.kind }
    return if (same.size == 1) name else "$name ${same.indexOf(index) + 1}"
}

private fun listen(vm: EditorViewModel, found: (List<Thing>) -> Unit = {}, outline: (Patch) -> Unit = {}, failed: () -> Unit = {}, doing: String) = object : ThingListener {
    private fun main(block: () -> Unit) {
        vm.viewModelScope.launch(Dispatchers.Main) { block() }
    }

    override fun onProgress(fraction: Float, stage: String) = main {
        Erasing.job = if (stage == "download") "正在下载模型" to fraction else doing to -1f
    }

    override fun onFound(things: List<Thing>) = main {
        Erasing.job = null
        found(things)
    }

    override fun onOutline(patch: Patch) = main {
        Erasing.job = null
        Erasing.asked = null
        outline(patch)
    }

    override fun onFailed(message: String) = main {
        Erasing.job = null
        Erasing.asked = null
        vm.message = message
        failed()
    }
}

private fun find(vm: EditorViewModel, clip: ClipView) {
    val s = vm.session ?: return
    Erasing.job = "正在识别人物" to -1f
    val path = clip.path
    val listener = listen(vm, found = { if (Erasing.path == path) Erasing.things = it.take(MOST_THINGS) }, failed = { Erasing.things = emptyList() }, doing = "正在识别人物")
    vm.viewModelScope.launch(Threads.commands) {
        runCatching { s.findThings(clip.id, listener) }.onFailure { e ->
            withContext(Dispatchers.Main) { Erasing.job = null; Erasing.things = emptyList(); vm.message = e.userMessage }
        }
    }
}

/** Chooses found thing [index], its outline asked for the first time; or takes it off. */
private fun toggle(vm: EditorViewModel, clip: ClipView, index: Int) {
    if (Erasing.picked.remove(index) != null) return
    if (Erasing.job != null) return
    val s = vm.session ?: return
    val thing = Erasing.things?.getOrNull(index) ?: return
    val path = clip.path
    Erasing.asked = index
    Erasing.job = "正在识别轮廓" to -1f
    val listener = listen(vm, outline = { patch -> if (Erasing.path == path) Erasing.picked[index] = Marked(patch, drawn(patch)) }, doing = "正在识别轮廓")
    vm.viewModelScope.launch(Threads.commands) {
        runCatching { s.outlineThing(clip.id, thing, listener) }.onFailure { e ->
            withContext(Dispatchers.Main) { Erasing.job = null; Erasing.asked = null; vm.message = e.userMessage }
        }
    }
}

/** Chooses what is at [at] in the photo, a thing not found by itself. */
private fun choose(vm: EditorViewModel, clip: ClipView, at: Offset) {
    if (Erasing.job != null) return
    val s = vm.session ?: return
    val path = clip.path
    Erasing.job = "正在识别轮廓" to -1f
    val listener = listen(vm, outline = { patch -> if (Erasing.path == path) Erasing.extra.add(Marked(patch, drawn(patch))) }, doing = "正在识别轮廓")
    vm.viewModelScope.launch(Threads.commands) {
        runCatching { s.outlineAt(clip.id, at.x, at.y, listener) }.onFailure { e ->
            withContext(Dispatchers.Main) { Erasing.job = null; vm.message = e.userMessage }
        }
    }
}

/** Takes what is chosen out of the photo. */
private fun remove(vm: EditorViewModel, clip: ClipView) {
    val s = vm.session ?: return
    val patches = Erasing.chosen.map { it.patch }
    if (patches.isEmpty() || Erasing.job != null) return
    Erasing.job = "正在移除" to 0f
    val listener = object : SpeechListener {
        override fun onProgress(fraction: Float, stage: String) {
            vm.viewModelScope.launch(Dispatchers.Main) { Erasing.job = (if (stage == "download") "正在下载模型" else "正在移除") to fraction }
        }

        override fun onDone(result: TimelineView?) {
            vm.viewModelScope.launch(Dispatchers.Main) {
                Erasing.job = null
                if (result != null) vm.applyView(result)
                vm.message = if (patches.size == 1) "已移除" else "${patches.size} removed"
            }
        }

        override fun onFailed(message: String) {
            vm.viewModelScope.launch(Dispatchers.Main) {
                Erasing.job = null
                vm.message = message
            }
        }
    }
    vm.viewModelScope.launch(Threads.commands) {
        runCatching { s.erase(clip.id, patches, listener) }.onFailure { e ->
            withContext(Dispatchers.Main) { Erasing.job = null; vm.message = e.userMessage }
        }
    }
}

/** Puts each thing chosen over the video as a picture of its own, where it is in the photo. */
private fun lift(vm: EditorViewModel, clip: ClipView) {
    val s = vm.session ?: return
    val patches = Erasing.chosen.map { it.patch }
    if (patches.isEmpty() || Erasing.job != null) return
    Erasing.job = "正在叠加到视频" to -1f
    vm.viewModelScope.launch {
        val result = withContext(Threads.commands) {
            runCatching {
                var view: TimelineView? = null
                patches.forEach { view = s.lift(clip.id, it) }
                view
            }
        }
        Erasing.job = null
        result.onSuccess { v ->
            if (v != null) vm.applyView(v)
            vm.message = if (patches.size == 1) "已添加到叠加层" else "${patches.size} added to the overlay"
        }.onFailure { vm.message = it.userMessage }
    }
}

/**
 * Remove objects, on a photo on the main track or over it: the people and things in it found, each to
 * tap on - in the list or on the picture - along with anything else tapped or painted over. What is
 * chosen is removed, the picture filled in behind it, or put over the video as a picture of its own.
 */
@Composable
fun EraseObjectsPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    if (clip.kind != "image") {
        vm.panel = null
        vm.message = "此功能适用于照片，请先将视频画面定格为照片。"
        return
    }
    val toFetch = remember(clip.path) { runCatching { objectsDownload().toLong() }.getOrDefault(0L) }
    // the models are a few downloads the first time: asked for, not begun by opening the panel
    var go by remember { mutableStateOf(toFetch == 0L) }
    LaunchedEffect(clip.path, go) {
        if (Erasing.path != clip.path || Erasing.things == null) {
            Erasing.forget()
            Erasing.path = clip.path
            if (go) find(vm, clip)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cancelObjects() }
            Erasing.forget()
            Erasing.path = null
            Erasing.job = null
            Erasing.painting = false
            vm.viewModelScope.launch(Threads.commands) { runCatching { releaseObjects() } }
        }
    }
    val chosen = Erasing.chosen.size
    PanelFrame("移除物体", vm, height = 300.dp, left = {
        if (chosen > 0 && Erasing.job == null) {
            Text(
                "清除",
                style = Type.Small.copy(color = Palette.DockText),
                modifier = Modifier.clickable { Erasing.picked.clear(); Erasing.extra.clear() }.padding(8.dp),
            )
        }
    }) {
        if (!go) {
            Text(
                "Finds the people and things in this photo, to remove or to put over the video. It runs on this phone; nothing is uploaded.",
                style = Type.Small,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            )
            Box(Modifier.padding(horizontal = 16.dp)) { WideButton("Download (%.0f MB)".format(toFetch / 1_048_576.0)) { go = true } }
            return@PanelFrame
        }
        // the found things, a tap each to choose; painted or tapped ones are only on the picture
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val things = Erasing.things
            if (things != null && things.isEmpty()) {
                Text("未识别到人物。请点击或涂抹照片中需要移除的对象。", style = Type.Small, modifier = Modifier.padding(vertical = 6.dp))
            }
            things?.forEachIndexed { i, _ ->
                Pill(nameOf(things, i), i in Erasing.picked, dim = Erasing.asked == i) { toggle(vm, clip, i) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("点选", !Erasing.painting) { Erasing.painting = false }
            Spacer(Modifier.width(8.dp))
            Pill("涂抹", Erasing.painting) { Erasing.painting = true }
            Spacer(Modifier.weight(1f))
            val people = Erasing.things.orEmpty().indices.filter { Erasing.things!![it].kind == "person" }
            if (people.size > 1) Pill("所有人物", people.all { it in Erasing.picked }) { chooseAll(vm, clip, people) }
        }
        if (Erasing.painting) {
            ValueSlider(
                value = Erasing.brush * 100f,
                range = 1f..20f,
                label = { "${it.roundToInt()}" },
                onChange = { Erasing.brush = it / 100f },
                onDone = {},
                title = "画笔大小",
            )
        } else {
            Text(
                "点击照片中的人物或物体，也可以从列表中选择。",
                style = Type.Small.copy(color = Palette.TextSecondary),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val job = Erasing.job
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (job != null) {
                WideButton(job.first + if (job.second >= 0f) "  ${(job.second * 100).roundToInt()}%" else "", enabled = false) {}
            } else {
                Box(Modifier.weight(1f)) { WideButton(if (chosen > 1) "Remove ($chosen)" else "移除", chosen > 0) { remove(vm, clip) } }
                Box(Modifier.weight(1f)) { WideButton(if (chosen > 1) "To overlay ($chosen)" else "添加到叠加层", chosen > 0) { lift(vm, clip) } }
            }
        }
    }
}

/** Chooses every thing of [indices], an outline after another; or takes them all off where all are chosen. */
private fun chooseAll(vm: EditorViewModel, clip: ClipView, indices: List<Int>) {
    if (indices.all { it in Erasing.picked }) {
        indices.forEach { Erasing.picked.remove(it) }
        return
    }
    if (Erasing.job != null) return
    val s = vm.session ?: return
    val path = clip.path
    val left = ArrayDeque(indices.filter { it !in Erasing.picked })
    fun next() {
        val index = left.removeFirstOrNull() ?: return
        val thing = Erasing.things?.getOrNull(index) ?: return
        val doing = "正在识别轮廓"
        Erasing.asked = index
        Erasing.job = doing to 1f - (left.size + 1f) / indices.size
        val listener = listen(vm, outline = { patch ->
            if (Erasing.path == path) {
                Erasing.picked[index] = Marked(patch, drawn(patch))
                next()
            }
        }, failed = { if (Erasing.path == path) next() }, doing = doing)
        vm.viewModelScope.launch(Threads.commands) {
            runCatching { s.outlineThing(clip.id, thing, listener) }.onFailure { e ->
                withContext(Dispatchers.Main) { Erasing.job = null; Erasing.asked = null; vm.message = e.userMessage }
            }
        }
    }
    next()
}

@Composable
private fun Pill(label: String, on: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Accent else if (dim) Color(0xFF555555) else Color(0xFF3A3A3A))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.Small.copy(fontSize = 13.sp, color = if (on) Palette.OnAccent else Palette.Text)) }
}

/** Where a picture's own fractions lie on the preview, as [sourcePoint] reads them back: the clip's place, crop and flips. */
internal class OnScreen(val box: ClipBox, val shape: FloatArray, val flipH: Boolean, val flipV: Boolean, w: Float, h: Float) {
    val bw = (box.width * w).toFloat()
    val bh = (box.height * h).toFloat()
    val centre = Offset((box.cx * w).toFloat(), (box.cy * h).toFloat())
    private val spanU = (1f - shape[0] - shape[2]).coerceAtLeast(0.01f)
    private val spanV = (1f - shape[1] - shape[3]).coerceAtLeast(0.01f)

    /** [u] across and [v] down the picture, in pixels from the clip's middle before it is turned. */
    fun local(u: Float, v: Float): Offset {
        val x = (u - shape[0]) / spanU - 0.5f
        val y = (v - shape[1]) / spanV - 0.5f
        return Offset((if (flipH) -x else x) * bw, (if (flipV) -y else y) * bh)
    }

    /** The other way: a place on the preview in the picture's fractions, or null off the picture. */
    fun source(p: Offset): Offset? {
        val a = Math.toRadians(-box.rotation).toFloat()
        val d = p - centre
        var x = (d.x * cos(a) - d.y * sin(a)) / bw
        var y = (d.x * sin(a) + d.y * cos(a)) / bh
        if (x !in -0.5f..0.5f || y !in -0.5f..0.5f) return null
        if (flipH) x = -x
        if (flipV) y = -y
        return Offset(shape[0] + (x + 0.5f) * spanU, shape[1] + (y + 0.5f) * spanV)
    }

    /** How wide one picture's width is on the preview. */
    val wide: Float get() = bw / spanU
}

/** The photo under Remove objects: the things found ringed, those chosen tinted; a tap chooses, a stroke paints. */
@Composable
fun EraseSurface(vm: EditorViewModel, modifier: Modifier) {
    val clip = vm.selectedClip ?: return
    val session = vm.session
    val playhead = vm.playhead
    val box by produceState<ClipBox?>(null, session, vm.timeline?.revision, playhead, clip.id) {
        val s = session ?: return@produceState
        value = withContext(Threads.commands) { runCatching { s.clipBoxes(playhead) }.getOrDefault(emptyList()) }.firstOrNull { it.clipId == clip.id }
    }
    val shape = remember(clip.json) { clipShape(vm, clip) }
    val flips = remember(clip.json) { vm.clipJson(clip.id).let { (it?.optBoolean("flipH") ?: false) to (it?.optBoolean("flipV") ?: false) } }
    val latest by rememberUpdatedState(Triple(box, shape, flips))
    val now by rememberUpdatedState(clip)
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier.pointerInput(clip.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val points = mutableListOf(down.position)
                if (Erasing.painting) live = points.toList()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - points.last()).getDistance() > 3f) {
                        points.add(change.position)
                        if (Erasing.painting) live = points.toList()
                    }
                    change.consume()
                }
                live = emptyList()
                val (b, sh, f) = latest
                val place = OnScreen(b ?: return@awaitEachGesture, sh, f.first, f.second, size.width.toFloat(), size.height.toFloat())
                if (Erasing.path != now.path) return@awaitEachGesture
                if (Erasing.painting) {
                    val mapped = points.mapNotNull { place.source(it) }
                    if (mapped.isNotEmpty() && Erasing.job == null) {
                        val aspect = now.mediaWidth.toFloat() / now.mediaHeight.toFloat().coerceAtLeast(1f)
                        painted(mapped, Erasing.brush, aspect).let { Erasing.extra.add(Marked(it, drawn(it))) }
                    }
                    return@awaitEachGesture
                }
                // a tap, not a drag: the smallest thing found there, else whatever is there
                if ((points.last() - points.first()).getDistance() > 24f) return@awaitEachGesture
                val at = place.source(down.position) ?: return@awaitEachGesture
                // what was tapped or painted before goes at a second tap
                val over = Erasing.extra.lastOrNull { covers(it.patch, at) }
                if (over != null) {
                    Erasing.extra.remove(over)
                    return@awaitEachGesture
                }
                val things = Erasing.things.orEmpty()
                val hit = things.indices.filter { i ->
                    val t = things[i]
                    at.x in t.x..t.x + t.width && at.y in t.y..t.y + t.height
                }.minByOrNull { things[it].width * things[it].height }
                if (hit != null) toggle(vm, now, hit) else choose(vm, now, at)
            }
        },
    ) {
        val b = box ?: return@Canvas
        val place = OnScreen(b, shape, flips.first, flips.second, size.width, size.height)
        withTransform({
            translate(place.centre.x, place.centre.y)
            rotate(b.rotation.toFloat(), Offset.Zero)
        }) {
            if (Erasing.path == clip.path) {
                Erasing.chosen.forEach { outlineOn(it, place) }
                Erasing.things.orEmpty().forEachIndexed { i, t ->
                    if (i in Erasing.picked) return@forEachIndexed
                    val p = place.local(t.x, t.y)
                    val q = place.local(t.x + t.width, t.y + t.height)
                    val corner = Offset(minOf(p.x, q.x), minOf(p.y, q.y))
                    val span = Size(kotlin.math.abs(q.x - p.x), kotlin.math.abs(q.y - p.y))
                    val colour = if (Erasing.asked == i) Palette.Accent else if (t.kind == "person") Color.White else Color(0xAAFFFFFF)
                    drawRoundRect(colour, corner, span, androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke((if (t.kind == "person") 2f else 1f).dp.toPx()))
                }
            }
        }
        val radius = Erasing.brush * place.wide / 2f
        live.forEach { drawCircle(Color(0x8800C8E0), radius, it) }
    }
}

private fun DrawScope.outlineOn(chosen: Marked, place: OnScreen) {
    val p = chosen.patch
    val a = place.local(p.x, p.y)
    val b = place.local(p.x + p.width, p.y + p.height)
    withTransform({
        translate(a.x, a.y)
        scale(if (b.x < a.x) -1f else 1f, if (b.y < a.y) -1f else 1f, Offset.Zero)
    }) {
        drawImage(
            chosen.picture,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(kotlin.math.abs(b.x - a.x).roundToInt().coerceAtLeast(1), kotlin.math.abs(b.y - a.y).roundToInt().coerceAtLeast(1)),
        )
    }
}

/** Whether [patch]'s outline is over [at], in the picture's fractions. */
private fun covers(patch: Patch, at: Offset): Boolean {
    val u = (at.x - patch.x) / patch.width
    val v = (at.y - patch.y) / patch.height
    if (u !in 0f..1f || v !in 0f..1f) return false
    val x = (u * patch.maskWidth.toInt()).toInt().coerceIn(0, patch.maskWidth.toInt() - 1)
    val y = (v * patch.maskHeight.toInt()).toInt().coerceIn(0, patch.maskHeight.toInt() - 1)
    return (patch.mask[y * patch.maskWidth.toInt() + x].toInt() and 255) >= 100
}

/** A stroke [size] of the picture's width wide through [points] (its fractions), as an outline over the part it covers. */
private fun painted(points: List<Offset>, size: Float, aspect: Float): Patch {
    val rx = size / 2f
    val ry = rx * aspect
    val left = (points.minOf { it.x } - rx).coerceAtLeast(0f)
    val top = (points.minOf { it.y } - ry).coerceAtLeast(0f)
    val right = (points.maxOf { it.x } + rx).coerceAtMost(1f)
    val bottom = (points.maxOf { it.y } + ry).coerceAtMost(1f)
    val w = (right - left).coerceAtLeast(0.001f)
    val h = (bottom - top).coerceAtLeast(0.001f)
    // as fine across as down, the longer side at the most
    val tall = h / aspect
    val k = PAINT / maxOf(w, tall)
    val mw = (w * k).roundToInt().coerceIn(8, PAINT)
    val mh = (tall * k).roundToInt().coerceIn(8, PAINT)
    val bitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = size * k
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        style = android.graphics.Paint.Style.STROKE
    }
    fun at(p: Offset) = Offset((p.x - left) / w * mw, (p.y - top) / h * mh)
    val path = android.graphics.Path()
    points.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
    // a dot, for a stroke that did not move
    if (points.size == 1) at(points[0]).let { path.lineTo(it.x + 0.1f, it.y) }
    canvas.drawPath(path, paint)
    val row = bitmap.rowBytes
    val raw = java.nio.ByteBuffer.allocate(row * mh).also { bitmap.copyPixelsToBuffer(it) }.array()
    val mask = ByteArray(mw * mh) { i -> raw[i / mw * row + i % mw] }
    bitmap.recycle()
    return Patch(left, top, w, h, mw.toUInt(), mh.toUInt(), mask)
}
