package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel

private val LENGTHS = listOf(15 to "15s", 30 to "30s", 60 to "1 min", 180 to "3 min")

/** Auto highlight: how long a short to make of the main track's liveliest moments. */
@Composable
fun AutoHighlightPanel(vm: EditorViewModel) {
    var length by remember { mutableIntStateOf(30) }
    var working by remember { mutableStateOf(false) }
    PanelFrame("智能高光", vm, height = 260.dp) {
        Text(
            "根据声音识别视频中最精彩的片段，并剪掉其余部分。可以随时撤销恢复。",
            style = Type.Small,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LENGTHS.forEach { (seconds, label) ->
                val on = length == seconds
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333))
                        .border(1.5.dp, if (on) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { length = seconds },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = Type.Body.copy(fontSize = 14.sp, color = if (on) Palette.Accent else Palette.Text)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            WideButton(if (working) "正在分析音频" else "生成精彩片段", !working) {
                working = true
                val before = vm.timeline?.revision
                vm.edit(settled = {
                    working = false
                }) {
                    autoHighlight(length * 1_000_000L).also { if (it.revision != before) vm.panel = null }
                }
            }
        }
    }
}
