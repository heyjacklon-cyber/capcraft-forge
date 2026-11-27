package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.json.JSONArray
import org.json.JSONObject

/** Words that carry a sentence along rather than say something: never keywords. */
private val COMMON = (
    "a about above actually after again all almost also always am an and any are aren't as at away back be because been before being below but by " +
        "came can can't cannot come could couldn't did didn't do does doesn't doing don't done down each else even ever every few for from get gets getting give go goes going gonna got had " +
        "hadn't has hasn't have haven't having he her here here's hers him his how how's i i'd i'll i'm i've if in into is isn't it it's its itself just keep kind know let let's like little long look lot made make " +
        "many may maybe me mean might more most much must my myself need never new next no not now of off okay ok on once one only or other our out over own " +
        "put quite really right said same say see seem she should shouldn't since so some something sort still such sure take than that that's the their them then there there's these they " +
        "they're thing things think this those though through time to today too two up us use used using very want was wasn't way we we'll we're we've well went were weren't what " +
        "what's when where which while who why will with without won't would wouldn't yeah yes yet you you'd you'll you're you've your yours"
    ).split(' ').toSet()

/** A word of a caption: where it is, in characters as the engine counts them, and itself without its punctuation. */
private class Word(val start: Int, val end: Int, val bare: String)

/** The words of [text]; their places count Unicode characters, as the engine's runs do, not UTF-16 halves. */
private fun wordsOf(text: String): List<Word> {
    val out = ArrayList<Word>()
    Regex("[\\p{L}\\p{N}$€£%][\\p{L}\\p{N}'’.,%]*").findAll(text).forEach { m ->
        val word = m.value.trimEnd('.', ',', '\'', '’')
        val from = text.codePointCount(0, m.range.first)
        out.add(Word(from, from + word.codePointCount(0, word.length), word.lowercase().replace('’', '\'')))
    }
    return out
}

/**
 * The keywords of [texts]: for each caption the one word that says most - a number, or else the
 * longest of those said least across them all - and none where nothing stands out.
 */
internal fun keywordsOf(texts: List<String>): Set<String> {
    val all = texts.map { wordsOf(it) }
    val said = all.flatten().groupingBy { it.bare }.eachCount()
    return all.mapNotNull { words ->
        words.filter { it.bare !in COMMON && (it.bare.length >= 4 || it.bare.any(Char::isDigit)) }
            .maxByOrNull { w ->
                val number = if (w.bare.any(Char::isDigit)) 6.0 else 0.0
                number + w.bare.length.coerceAtMost(10) - (said[w.bare] ?: 1) * 0.5
            }?.bare
    }.toSet()
}

private val HIGHLIGHTS = listOf("#ffd60a", "#34c759", "#00c8e0", "#ff5c8a", "#ff9f0a", "#bf5af2", "#ff453a", "#ffffff")

/** Every caption with its words, in order. */
private fun captionTexts(vm: EditorViewModel): List<Pair<String, JSONObject>> =
    vm.timeline?.rows.orEmpty().flatMap { it.clips }.filter { it.id in vm.captions }.sortedBy { it.startUs }
        .mapNotNull { c -> vm.clipJson(c.id)?.optJSONObject("text")?.let { c.id to it } }

/**
 * Highlight: the captions' keywords in a colour, bigger if asked. The words are chosen from the
 * captions to start with; a tap adds or takes one, wherever it is said.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HighlightPanel(vm: EditorViewModel) {
    val captions = remember(vm.timeline?.revision) { captionTexts(vm) }
    val texts = captions.map { it.second.optString("content") }
    // the words marked now, where there are any; else those chosen for the user
    val marked = remember {
        captions.flatMap { (_, text) ->
            val runs = text.optJSONArray("runs") ?: JSONArray()
            val words = wordsOf(text.optString("content"))
            (0 until runs.length()).mapNotNull { i ->
                val r = runs.getJSONObject(i)
                words.firstOrNull { it.start == r.optInt("start") }?.bare
            }
        }.toSet()
    }
    var chosen by remember { mutableStateOf(marked) }
    var color by remember { mutableStateOf(HIGHLIGHTS[0]) }
    var bigger by remember { mutableStateOf(false) }

    fun apply(words: Set<String>, gesture: String = "highlight") {
        val batch = JSONArray()
        captions.forEach { (id, text) ->
            val runs = JSONArray()
            wordsOf(text.optString("content")).filter { it.bare in words }.forEach { w ->
                val run = JSONObject().put("start", w.start).put("end", w.end).put("color", color)
                if (bigger) run.put("fontScale", 1.25)
                runs.put(run)
            }
            val had = text.optJSONArray("runs")?.length() ?: 0
            if (runs.length() == 0 && had == 0) return@forEach
            batch.put(JSONObject().put("op", "updateClip").put("clipId", id).put("patch", JSONObject().put("text", JSONObject(text.toString()).put("runs", runs))))
        }
        chosen = words
        if (batch.length() > 0) vm.command(JSONObject().put("op", "batch").put("commands", batch), gesture)
    }

    PanelFrame("Highlight", vm, height = 330.dp, left = {
        if (chosen.isNotEmpty()) {
            Text("清除", style = Type.Small.copy(color = Palette.DockText), modifier = Modifier.clickable { apply(emptySet()) }.padding(8.dp))
        }
    }, onDone = { vm.endGesture(); vm.panel = null }) {
        if (captions.isEmpty()) {
            Text(
                "Make captions first - with Auto captions, Enter captions or a caption file - then highlight their keywords here.",
                style = Type.Small,
                modifier = Modifier.padding(24.dp),
            )
            return@PanelFrame
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HIGHLIGHTS.forEach { c ->
                Box(
                    Modifier.padding(end = 10.dp).size(30.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(c)))
                        .border(2.dp, if (c == color) Palette.Accent else Color.Transparent, CircleShape)
                        .clickable { color = c; if (chosen.isNotEmpty()) apply(chosen) },
                )
            }
            Chip("Bigger", bigger) { bigger = !bigger; if (chosen.isNotEmpty()) apply(chosen) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Box(Modifier.weight(1f)) { WideButton("Highlight keywords") { apply(keywordsOf(texts)) } }
        }
        Spacer(Modifier.height(10.dp))
        val words = remember(texts) { texts.flatMap { wordsOf(it) }.map { it.bare }.distinct() }
        FlowRow(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            words.forEach { w -> Chip(w, w in chosen) { apply(if (w in chosen) chosen - w else chosen + w) } }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) Palette.Accent else Color(0xFF3A3A3A)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Type.Small.copy(fontSize = 13.sp, color = if (on) Palette.OnAccent else Palette.Text)) }
}

/** What an emoji is added for: its words, the start of one enough where it ends in "*". */
private val EMOJI = listOf(
    "😂" to "funny laugh* joke* hilarious lol comedy", "😍" to "beautiful gorgeous pretty stunning lovely", "❤️" to "love loves loved heart loving", "😊" to "happy smile* glad nice kind joy",
    "😢" to "sad cry crying cried tears sorry", "😡" to "angry mad hate hated furious", "😱" to "scary scared shock* terrif* horror", "🤔" to "think thinking wonder* question* why maybe",
    "🤯" to "crazy insane amazing incredible unbelievable mindblow*", "😴" to "sleep* tired bed nap dream*", "🥳" to "party celebrat* birthday congrat*", "😎" to "cool awesome style stylish",
    "🔥" to "fire hot lit burn* trending", "💯" to "hundred perfect exactly absolutely", "👍" to "good great okay agree thumbs", "👏" to "clap* applau* bravo", "🙏" to "thank* please pray* grateful", "💪" to "strong strength gym workout muscle* exercise power*",
    "🎉" to "win wins won winner* success* launch*", "⭐" to "star stars best favourite favorite", "✨" to "magic* shine shiny sparkl* new", "💡" to "idea ideas tip tips learn* lesson* smart", "⚠️" to "warning careful danger* caution beware", "❌" to "wrong mistake* never stop bad fail*", "✅" to "correct done yes right complete*",
    "💰" to "money cash rich expensive cost* price* profit* salary dollar* rupee*", "💸" to "spend* paid pay buy* bought", "📈" to "grow* increase* rising stock* invest*", "📉" to "decreas* drop* falling loss*", "🛒" to "shop* store cart market grocer*", "🎁" to "gift* present surprise*",
    "💻" to "computer* laptop* code coding software programming website*", "📱" to "phone* mobile app apps iphone android", "🤖" to "ai robot* chatgpt automat*", "📷" to "camera* photo* picture*", "🎬" to "video* movie* film* edit editing scene", "🎵" to "music song* sing* sound* audio beat*", "🎮" to "game* gaming play player*", "📚" to "book* read reading study* school college", "✍️" to "write writing wrote pen",
    "⏰" to "time clock hour* minute* deadline late early", "📅" to "date calendar week* month* year* schedule*", "🌅" to "morning sunrise sunset", "🌙" to "night* moon", "☀️" to "sun sunny summer", "🌧️" to "rain* storm*", "❄️" to "cold snow* winter ice freez*", "🌍" to "world earth planet global countr*", "🌊" to "ocean sea wave* beach swim*", "🏔️" to "mountain* hill* hik* climb*", "🌳" to "tree* forest* nature green", "🌸" to "flower* spring bloom* garden*",
    "🍕" to "pizza", "🍔" to "burger* food eat* ate hungry lunch dinner", "☕" to "coffee tea breakfast", "🍰" to "cake* sweet* dessert*", "🥗" to "health* diet salad vegetable*", "🍎" to "apple* fruit*", "💧" to "water drink* thirsty",
    "🚗" to "car* drive* driving road*", "✈️" to "travel* flight* fly* airport plane* trip* vacation*", "🏠" to "home house* family", "🏢" to "office* company business work* job*", "🏥" to "doctor* hospital* sick", "🏆" to "champion* trophy award* first", "⚽" to "football soccer goal*", "🏏" to "cricket", "🏃" to "run* running fast quick* speed*", "🧠" to "brain* mind* memory",
    "🐶" to "dog* puppy puppies", "🐱" to "cat cats kitten*", "🐦" to "bird*", "🦁" to "lion*", "🐟" to "fish*", "👶" to "baby babies child* kid kids", "👫" to "friend* together people team", "👑" to "king queen boss", "🚀" to "rocket* space fast launch* boost*", "🔒" to "secret* safe* secur* private password*", "🔑" to "key keys unlock* important", "📍" to "place* location* here city", "📣" to "announce* tell news", "👀" to "look* watch* see eyes", "👇" to "below down link comment*", "🔔" to "subscribe* notif* bell", "🎯" to "target* focus* aim*",
).flatMap { (emoji, words) -> words.split(' ').map { it to emoji } }

/** The emoji for what [text] says: its first word that has one, or null. */
internal fun emojiFor(text: String): String? = wordsOf(text).firstNotNullOfOrNull { w ->
    EMOJI.firstOrNull { (key, _) -> if (key.endsWith('*')) w.bare.startsWith(key.dropLast(1)) else w.bare == key }?.second
}

private val EMOJIS by lazy { EMOJI.map { it.second }.toSet() }

/** [text] without the emoji put on its end here. */
private fun plain(text: String): String {
    val trimmed = text.trimEnd()
    val mine = EMOJIS.firstOrNull { trimmed.endsWith(it) } ?: return text
    return trimmed.removeSuffix(mine).trimEnd()
}

/**
 * Puts an emoji on the end of every caption that says something there is one for, in one step, or
 * takes them off where there are any; says what it did.
 */
internal fun toggleCaptionEmojis(vm: EditorViewModel) {
    val captions = captionTexts(vm)
    if (captions.isEmpty()) {
        vm.message = "Make captions first"
        return
    }
    val had = captions.any { (_, t) -> plain(t.optString("content")) != t.optString("content") }
    val batch = JSONArray()
    captions.forEach { (id, text) ->
        val now = text.optString("content")
        val next = if (had) plain(now) else emojiFor(now)?.let { "$now $it" } ?: now
        // at the end, so highlighted words stay where they are
        if (next != now) batch.put(JSONObject().put("op", "updateClip").put("clipId", id).put("patch", JSONObject().put("text", JSONObject(text.toString()).put("content", next))))
    }
    if (batch.length() > 0) vm.command(JSONObject().put("op", "batch").put("commands", batch))
    vm.message = when {
        had -> "Emojis taken off"
        batch.length() == 0 -> "No emojis fit these captions"
        else -> "Emojis added to ${batch.length()} caption" + if (batch.length() == 1) "" else "s"
    }
}
