package com.alterra.canliceviri.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.pipeline.EngineStatus
import com.alterra.canliceviri.pipeline.MicMode
import com.alterra.canliceviri.pipeline.Mode
import com.alterra.canliceviri.pipeline.Speaker
import com.alterra.canliceviri.pipeline.Utterance
import com.alterra.canliceviri.pipeline.UtteranceState
import java.util.Locale

@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
    onOpenDiagnostics: () -> Unit,
    onInstallVoices: (String?) -> Unit,
    onInstallGoogleTts: () -> Unit,
    onStart: () -> Unit,
    onFixBattery: () -> Unit,
) {
    val status by vm.status.collectAsState()
    val utterances by vm.utterances.collectAsState()

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Canlı Çeviri", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenDiagnostics) { Text("Cihaz testi") }
            }
            Controls(vm, status)
            if (!vm.setup.ready) SetupCard(vm, onInstallVoices, onInstallGoogleTts)
            if (vm.setup.ready && vm.setup.batteryRestricted && !vm.batteryAsked) BatteryCard(onFixBattery)
            StatusBar(status)
            Conversation(utterances, Modifier.weight(1f))
            BigButton(vm, status, onStart)
        }
    }
}

@Composable
private fun Controls(vm: ConversationViewModel, status: EngineStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = vm.mode == Mode.CONVERSATION, onClick = { vm.selectMode(Mode.CONVERSATION) }, label = { Text("Konuşma") })
        FilterChip(selected = vm.mode == Mode.LISTENING, onClick = { vm.selectMode(Mode.LISTENING) }, label = { Text("Dinleme") })
        Spacer(Modifier.weight(1f))
        LanguagePicker(vm, status)
    }
}

@Composable
private fun LanguagePicker(vm: ConversationViewModel, status: EngineStatus) {
    var open by remember { mutableStateOf(false) }
    val label = when {
        vm.partnerLang != null -> Languages.FOREIGN.first { it.code == vm.partnerLang }.nameTr
        status.partnerLang != null -> "Otomatik: " + (Languages.FOREIGN.firstOrNull { it.code == status.partnerLang }?.nameTr ?: status.partnerLang) +
            if (status.partnerLangLocked) "" else "?"
        else -> "Dil: otomatik"
    }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("$label ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Otomatik algıla") }, onClick = { open = false; vm.selectPartnerLang(null) })
            Languages.FOREIGN.forEach { l ->
                DropdownMenuItem(text = { Text("${l.nameTr} · ${l.nativeName}") }, onClick = { open = false; vm.selectPartnerLang(l.code) })
            }
        }
    }
}

@Composable
private fun SetupCard(vm: ConversationViewModel, onInstallVoices: (String?) -> Unit, onInstallGoogleTts: () -> Unit) {
    val s = vm.setup
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kurulum (bir kez, internet gerekir)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!s.checked) Text("Kontrol ediliyor…")
            if (s.missingModels.isNotEmpty()) {
                Text(
                    "Eksik modeller: ${s.missingModels.joinToString()}. Konuşma tanıma (~265 MB) ve çeviri (~1,1 GB) modelleri bir kez indirilir.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (s.modelProgress != null) Text(s.modelProgress, style = MaterialTheme.typography.bodySmall)
                else Button(onClick = vm::downloadModels) { Text("Modelleri indir") }
            }
            if (s.missingVoices.isNotEmpty() && s.googleTtsMissing) {
                Text(
                    "Sesli okuma için Google'ın ses motoru gerekli (\"Speech Services by Google\"). Play Store'dan kurun, " +
                        "sonra buraya dönüp dillerin sesini indirin.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onInstallGoogleTts) { Text("Play Store'da aç") }
            } else if (s.missingVoices.isNotEmpty()) {
                Text(
                    "Sesli okuma için internetsiz ses eksik: ${s.missingVoices.joinToString()}. " +
                        "Açılan listede bu dillere dokunup ses paketini indirin; eksik dil İngilizce sesle okunmaz, yalnızca ekranda görünür.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { onInstallVoices(vm.ttsEnginePackage) }) { Text("Ses paketlerini indir") }
            }
            TextButton(onClick = vm::refreshSetup) { Text("Yeniden kontrol et") }
        }
    }
}

@Composable
private fun BatteryCard(onFixBattery: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pil kısıtlaması", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Ekran kapalıyken çevirinin durmaması için telefonun bu uygulamayı uyutmaması gerekir. Açılan pencerede izin verin.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onFixBattery) { Text("Kısıtlamayı kaldır") }
        }
    }
}

@Composable
private fun StatusBar(status: EngineStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val activity = when {
            !status.running -> "Durduruldu"
            status.playing -> "🔊 Çeviri okunuyor…"
            status.hearingAnyone && status.hearing == Speaker.USER -> "🗣 Siz konuşuyorsunuz"
            status.hearingAnyone && status.hearing == Speaker.PARTNER -> "👂 Karşı taraf konuşuyor"
            status.hearingAnyone -> "👂 Konuşma duyuluyor"
            status.busy -> "⏳ Çevriliyor…"
            else -> "Dinliyor…"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(activity, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val mic = when (status.micMode) {
                MicMode.DUAL -> "🎧+📱"
                MicMode.SINGLE -> "📱"
                MicMode.NONE -> ""
            }
            Text(mic, style = MaterialTheme.typography.titleMedium)
        }
        if (status.running) {
            if (status.micMode == MicMode.DUAL) Level("🎧 Siz", status.levelUser)
            Level(if (status.micMode == MicMode.DUAL) "📱 Karşı" else "📱 Mikrofon", status.levelPartner)
            if (status.micInfo.isNotEmpty()) Text(status.micInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Level(label: String, db: Float) {
    val fraction = ((db + 70f) / 70f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp))
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun Conversation(items: List<Utterance>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(items.size, items.lastOrNull()?.state) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Başlat'a basın. Karşı taraf konuşunca Türkçesi kulaklıktan, siz konuşunca çevirisi hoparlörden gelir.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { Bubble(it) }
    }
}

@Composable
private fun Bubble(u: Utterance) {
    val mine = u.speaker == Speaker.USER
    if (u.state == UtteranceState.DROPPED) {
        Text(
            "· ${u.note ?: "atlandı"}${u.text?.let { ": $it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        ) {
            val onBubble = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            val onBubbleMuted = onBubble.copy(alpha = 0.72f)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val src = langName(u.sourceLang)
                val dst = langName(u.targetLang)
                Text(
                    (if (mine) "Siz" else "Karşı taraf") + (if (src.isNotEmpty()) " · $src" else "") + (if (dst.isNotEmpty()) " → $dst" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = onBubbleMuted,
                )
                when {
                    u.text == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Yazıya dökülüyor…", style = MaterialTheme.typography.bodyMedium, color = onBubble)
                    }
                    else -> Text(u.text, style = MaterialTheme.typography.bodyMedium, color = onBubbleMuted)
                }
                u.translation?.let {
                    // Kullanıcının çevirisi karşı tarafa gösterilebilsin diye büyük.
                    Text(
                        it,
                        style = if (mine) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onBubble,
                    )
                }
                val timing = listOfNotNull(
                    u.asrMs?.let { "yazı ${sec(it)}" },
                    u.mtMs?.let { "çeviri ${sec(it)}" },
                    u.latencyMs?.let { "ses ${sec(it)}" },
                ).joinToString(" · ")
                val footer = listOfNotNull(timing.takeIf { it.isNotEmpty() }, u.note, stateText(u.state)).joinToString(" · ")
                if (footer.isNotEmpty()) Text(footer, style = MaterialTheme.typography.bodySmall, color = onBubbleMuted)
            }
        }
    }
}

@Composable
private fun BigButton(vm: ConversationViewModel, status: EngineStatus, onStart: () -> Unit) {
    val running = status.running
    Button(
        onClick = { if (running) vm.toggle() else onStart() },
        enabled = !status.starting && (running || vm.setup.missingModels.isEmpty()),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
    ) {
        if (status.starting) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Hazırlanıyor…", style = MaterialTheme.typography.titleMedium)
        } else {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (running) Color.White else MaterialTheme.colorScheme.onPrimary)
            )
            Spacer(Modifier.width(10.dp))
            Text(if (running) "Durdur" else "Başlat", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun langName(code: String?): String = code?.let { c -> Languages.ALL.firstOrNull { it.code == c }?.nameTr ?: c } ?: ""

private fun sec(ms: Long) = String.format(Locale.forLanguageTag("tr"), "%.1f sn", ms / 1000f)

private fun stateText(s: UtteranceState): String? = when (s) {
    UtteranceState.TRANSCRIBING -> null
    UtteranceState.TRANSLATING -> "çevriliyor"
    UtteranceState.SPEAKING -> "okunuyor"
    UtteranceState.ERROR -> "hata"
    UtteranceState.DONE, UtteranceState.DROPPED -> null
}
