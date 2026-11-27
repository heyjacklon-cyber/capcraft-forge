package io.github.artcraftandroid.capcraft.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.exportSize
import io.github.artcraftandroid.capcraft.core.suggestedBitrateKbps
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.MaterialIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.panels.Tabs
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RESOLUTIONS = listOf(480, 720, 1080, 1440, 2160)
private val RESOLUTION_LABELS = listOf("480p", "720p", "1080p", "2k", "4k")
private val FRAME_RATES = listOf(24, 25, 30, 50, 60)
/** The bitrate stops, megabits a second. */
private val MBPS = listOf(5, 10, 20, 50, 100)
private val GIF_SIZES = listOf(240, 320, 480)
private val GIF_RATES = listOf(8, 10, 15)

/**
 * The resolution an export uses: the one chosen, or else the sharpest of the canvas and its videos and photos, up to
 * 4K. The canvas is 1080 at most, since the preview composites in it, so 4K clips would otherwise leave as 1080p.
 */
fun exportResolutionOf(vm: EditorViewModel): Int {
    val t = vm.timeline
    val short = t?.let { min(it.width.toInt(), it.height.toInt()) } ?: 1080
    val sharpest = t?.rows.orEmpty().flatMap { it.clips }
        .filter { (it.kind == "video" || it.kind == "image") && it.mediaWidth > 0u && it.mediaHeight > 0u }
        .maxOfOrNull { min(it.mediaWidth.toInt(), it.mediaHeight.toInt()) } ?: 0
    return vm.exportResolution ?: RESOLUTIONS.lastOrNull { it <= max(max(short, sharpest), 480) } ?: 1080
}

/** The bitrate an export uses, kilobits a second: the one chosen, or the one suggested for its size. */
fun exportKbps(vm: EditorViewModel, width: Int, height: Int): Int =
    vm.exportMbps?.let { it * 1000 } ?: suggestedBitrateKbps(width.toUInt(), height.toUInt(), vm.exportFps.toUInt()).toInt()

/**
 * The export settings, dropped down from the top over the dimmed editor: Video or GIF, then the
 * resolution, frame rate and bitrate on stepped sliders, and the size the file will come to.
 */
@Composable
fun ExportSheet(vm: EditorViewModel, onExport: () -> Unit) {
    val t = vm.timeline ?: return
    BackHandler { vm.exportSheet = false }
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.exportSheet = false },
    ) {
        Column(
            Modifier.fillMaxWidth().background(Palette.Timeline)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .statusBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Tabs(listOf("视频", "GIF", "照片"), if (vm.exportPhoto) 2 else if (vm.exportGif) 1 else 0) {
                        vm.exportGif = it == 1
                        vm.exportPhoto = it == 2
                    }
                }
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Chip)
                        .clickable { vm.exportSheet = false }.padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = when {
                        vm.exportGif -> "GIF"
                        else -> resolutionLabel(exportResolutionOf(vm))
                    }
                    Text(label, style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Glyph(MaterialIcons.ArrowDropUp, Palette.Text, size = 20.dp)
                }
                Spacer(Modifier.width(9.dp))
                Box(
                    Modifier.height(32.dp).width(65.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Accent)
                        .clickable { vm.exportSheet = false; onExport() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("导出", style = Type.BodyStrong.copy(color = Palette.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                }
                Spacer(Modifier.width(16.dp))
            }
            if (vm.exportGif) {
                val size = GIF_SIZES.indexOf(vm.gifSize).coerceAtLeast(0)
                SettingSlider("分辨率", "文件越小，加载越快", GIF_SIZES.map { "${it}p" }, size.toFloat()) {
                    vm.gifSize = GIF_SIZES[it.roundToInt().coerceIn(0, GIF_SIZES.size - 1)]
                }
                val rate = GIF_RATES.indexOf(vm.gifFps).coerceAtLeast(0)
                SettingSlider("帧率", "帧率越高，画面越流畅", GIF_RATES.map { "$it" }, rate.toFloat()) {
                    vm.gifFps = GIF_RATES[it.roundToInt().coerceIn(0, GIF_RATES.size - 1)]
                }
                Divider()
                Text(
                    "无声音的循环动图",
                    style = Type.Small.copy(fontSize = 13.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                return@Column
            }
            if (!vm.exportPhoto) Presets(vm, t.width.toInt(), t.height.toInt())
            val res = exportResolutionOf(vm)
            val r = RESOLUTIONS.indexOf(res).coerceAtLeast(0)
            SettingSlider("分辨率", RESOLUTION_HINTS[r], RESOLUTION_LABELS, r.toFloat()) {
                vm.exportResolution = RESOLUTIONS[it.roundToInt().coerceIn(0, RESOLUTIONS.size - 1)]
            }
            if (vm.exportPhoto) {
                SwitchRow("动态照片", "将视频片段制作为无声动态照片", vm.exportLive) { vm.exportLive = !vm.exportLive }
                Divider()
                Text(
                    "将播放头所在画面保存为照片",
                    style = Type.Small.copy(fontSize = 13.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                return@Column
            }
            val f = FRAME_RATES.indexOf(vm.exportFps).coerceAtLeast(2)
            SettingSlider("帧率", if (vm.exportFps >= 50) "非常流畅" else "日常流畅", FRAME_RATES.map { "$it" }, f.toFloat()) {
                vm.exportFps = FRAME_RATES[it.roundToInt().coerceIn(0, FRAME_RATES.size - 1)]
            }
            val dims = exportSize(t.width, t.height, res.toUInt())
            val suggested = (suggestedBitrateKbps(dims[0], dims[1], vm.exportFps.toUInt()).toInt() / 1000.0).roundToInt().coerceAtLeast(1)
            val mbps = vm.exportMbps ?: suggested
            SettingSlider("码率（Mbps）", "Suggested for this video: $suggested", MBPS.map { "$it" }, mbpsToPosition(mbps), continuous = true) {
                val chosen = positionToMbps(it)
                // the suggestion is sticky, so it can be found again
                vm.exportMbps = if (abs(chosen - suggested) <= max(1, suggested / 10)) null else chosen
            }
            if (t.hdr) SwitchRow("HDR", "使用 H.265 保留视频的 HDR 效果", vm.exportHdr) { vm.exportHdr = !vm.exportHdr }
            Divider()
            val kbps = exportKbps(vm, dims[0].toInt(), dims[1].toInt())
            val mb = (kbps + 128) / 8.0 / 1000.0 * (t.durationUs / 1e6)
            Text(
                "Estimated file size: %s MB".format(if (mb < 10) "%.1f".format(mb) else "${mb.roundToInt()}"),
                style = Type.Small.copy(fontSize = 13.sp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * A platform's upload, as its own help pages suggest it: 1080p vertical, the frame rate, and a bitrate high enough
 * that its own re-encode has something to work from. A starting point the sliders below change; the platforms change
 * their advice, and their re-encode decides in the end.
 */
private data class Preset(val name: String, val resolution: Int, val fps: Int, val mbps: Int, val hint: String)

private val PRESETS = listOf(
    Preset("Reels", 1080, 30, 20, "Instagram Reels: 1080p, 30 fps"),
    Preset("抖音", 1080, 30, 15, "TikTok: 1080p, 30 fps"),
    Preset("YouTube Shorts", 1080, 60, 25, "YouTube Shorts: 1080p, 60 fps"),
)

/** One tap for a platform's upload: the chips above the sliders, the one the settings match lit. */
@Composable
private fun Presets(vm: EditorViewModel, width: Int, height: Int) {
    val matching = PRESETS.firstOrNull {
        exportResolutionOf(vm) == it.resolution && vm.exportFps == it.fps && vm.exportMbps == it.mbps
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRESETS.forEach { preset ->
            val on = preset == matching
            Box(
                Modifier.height(30.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (on) Palette.Accent else Palette.Chip)
                    .clickable {
                        vm.exportResolution = preset.resolution
                        vm.exportFps = preset.fps
                        vm.exportMbps = preset.mbps
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(preset.name, style = Type.Body.copy(fontSize = 14.sp, color = if (on) Palette.OnAccent else Palette.Text))
            }
        }
    }
    val hint = when {
        matching == null -> null
        // a 9:16 frame is what these play full screen; anything else gets bars or a crop
        abs(width * 16 - height * 9) > height / 50 -> "${matching.hint}. They play 9:16 full screen: set Ratio to 9:16 first"
        else -> matching.hint
    }
    if (hint != null) {
        Text(hint, style = Type.Small.copy(fontSize = 12.sp, color = Palette.TextSecondary), modifier = Modifier.padding(horizontal = 20.dp))
    }
}

private val RESOLUTION_HINTS = listOf(
    "文件较小，适合快速分享",
    "适合大多数分享场景",
    "在手机上清晰显示",
    "在大屏幕上更加清晰",
    "最高画质，文件也最大",
)

/** A setting that is on or off: its name over a line about it, and a switch. */
@Composable
private fun SwitchRow(label: String, hint: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text))
            Text(hint, style = Type.Small.copy(fontSize = 12.sp, color = Palette.TextSecondary))
        }
        Box(
            Modifier.width(40.dp).height(24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Accent else Color(0xFF555555)).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.width(18.dp).height(18.dp).clip(RoundedCornerShape(9.dp)).background(Color.White)) }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
}

/** Where [mbps] sits on the bitrate slider: between its stops, on a log scale. */
private fun mbpsToPosition(mbps: Int): Float {
    val v = mbps.coerceIn(MBPS.first(), MBPS.last()).toDouble()
    val i = MBPS.indexOfLast { it <= v }.coerceIn(0, MBPS.size - 2)
    val a = MBPS[i].toDouble()
    val b = MBPS[i + 1].toDouble()
    return (i + (ln(v) - ln(a)) / (ln(b) - ln(a))).toFloat()
}

private fun positionToMbps(position: Float): Int {
    val p = position.coerceIn(0f, (MBPS.size - 1).toFloat())
    val i = p.toInt().coerceIn(0, MBPS.size - 2)
    val f = p - i
    return exp(ln(MBPS[i].toDouble()) + f * (ln(MBPS[i + 1].toDouble()) - ln(MBPS[i].toDouble()))).roundToInt()
}

/**
 * A setting's row: its name with a hint at the right, a track with a tick at each stop and a white
 * thumb, and the stops' names under it. Stepped unless [continuous].
 */
@Composable
private fun SettingSlider(title: String, hint: String, labels: List<String>, position: Float, continuous: Boolean = false, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.Body.copy(fontSize = 16.sp, color = Palette.DockText), modifier = Modifier.weight(1f))
            Text(hint, style = Type.Small.copy(fontSize = 13.sp))
        }
        Spacer(Modifier.height(12.dp))
        val last = (labels.size - 1).toFloat()
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            fun at(x: Float): Float {
                val p = (x / widthPx * last).coerceIn(0f, last)
                return if (continuous) p else p.roundToInt().toFloat()
            }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(labels, continuous) { detectTapGestures { onChange(at(it.x)) } }
                    .pointerInput(labels, continuous) { detectDragGestures { change, _ -> onChange(at(change.position.x)) } },
            ) {
                val y = size.height / 2
                val x = size.width * (position / last)
                drawLine(Color(0xFF4A4A4A), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
                for (i in 1 until labels.size - 1) {
                    val tx = size.width * i / last
                    drawLine(if (tx <= x) Palette.Accent else Color(0xFF5A5A5A), Offset(tx, y - 5.dp.toPx()), Offset(tx, y + 5.dp.toPx()), 2.dp.toPx())
                }
                drawLine(Palette.Accent, Offset(0f, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White, 11.dp.toPx(), Offset(x.coerceIn(11.dp.toPx(), size.width - 11.dp.toPx()), y))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l,
                    style = Type.Small.copy(fontSize = 13.sp, color = Palette.DockText),
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        labels.size - 1 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
