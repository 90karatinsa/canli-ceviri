package com.alterra.canliceviri.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    onReportIssue: (note: String, attachAudio: Boolean) -> Unit,
) {
    val status by vm.status.collectAsState()
    val utterances by vm.utterances.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Utterance?>(null) }
    var fullscreen by remember { mutableStateOf<Utterance?>(null) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(vm, onOpenDiagnostics, onOpenSettings = { settingsOpen = true }, onReport = { reportOpen = true })
            Controls(vm, status)
            if (!vm.setup.ready) SetupCard(vm, onInstallVoices, onInstallGoogleTts)
            if (vm.setup.ready && vm.setup.batteryRestricted && !vm.batteryAsked) BatteryCard(onFixBattery)
            StatusBar(vm, status)
            Conversation(
                utterances, Modifier.weight(1f), manual = vm.manualInput, qualityModel = vm.setup.qualityModel,
                onRespeak = vm::respeak, onEdit = { editing = it }, onFullscreen = { fullscreen = it }, onRequality = vm::requality,
            )
            if (status.running && vm.manualInput) HoldButtons(vm, status)
            if (status.running) TextBar(vm, status)
            BigButton(vm, status, onStart)
        }
    }
    if (settingsOpen) SettingsSheet(vm, onClose = { settingsOpen = false }, onFixBattery = onFixBattery)
    if (reportOpen) ReportDialog(vm, onSend = onReportIssue, onClose = { reportOpen = false })
    editing?.let { u -> EditDialog(u, onSave = { vm.edit(u.id, it); editing = null }, onClose = { editing = null }) }
    fullscreen?.let { u -> FullscreenDialog(u, onRespeak = { vm.respeak(u.id) }, onClose = { fullscreen = null }) }
}

@Composable
private fun Header(vm: ConversationViewModel, onOpenDiagnostics: () -> Unit, onOpenSettings: () -> Unit, onReport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Canlı Çeviri", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Sorun bildir") }, onClick = { menu = false; onReport() })
                DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { menu = false; onOpenSettings() })
                DropdownMenuItem(text = { Text("Cihaz testi") }, onClick = { menu = false; onOpenDiagnostics() })
                DropdownMenuItem(text = { Text("Konuşmayı temizle") }, onClick = { menu = false; vm.clear() })
            }
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
private fun StatusBar(vm: ConversationViewModel, status: EngineStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val activity = when {
            !status.running -> "Durduruldu"
            status.paused != null -> "⏸ ${status.paused} — bitince kendiliğinden sürer"
            status.recording == Speaker.USER -> "● Siz konuşuyorsunuz (kayıt ${sec(System.currentTimeMillis() - status.recordingSince)})"
            status.recording == Speaker.PARTNER -> "● Karşı taraf konuşuyor (kayıt ${sec(System.currentTimeMillis() - status.recordingSince)})"
            status.playing -> "🔊 Çeviri okunuyor…"
            status.hearingAnyone && status.hearing == Speaker.USER -> "🗣 Siz konuşuyorsunuz"
            status.hearingAnyone && status.hearing == Speaker.PARTNER -> "👂 Karşı taraf konuşuyor"
            status.hearingAnyone -> "👂 Konuşma duyuluyor"
            status.busy -> "⏳ Çevriliyor…"
            vm.manualInput -> "Konuşurken düğmeyi basılı tutun"
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
            if (status.running) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = vm::requestRepeat, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Tekrar iste") }
            }
        }
        if (status.running && !vm.manualInput) {
            if (status.micMode == MicMode.DUAL) Level("🎧 Siz", status.levelUser)
            Level(if (status.micMode == MicMode.DUAL) "📱 Karşı" else "📱 Mikrofon", status.levelPartner)
        }
        if (status.running && status.micInfo.isNotEmpty()) {
            Text(status.micInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (status.running && !status.headsetAttached && status.speakerVolume < 0.5f) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Hoparlör sesi kısık (%${(status.speakerVolume * 100).toInt()}); karşı taraf duymayabilir.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::raiseVolume) { Text("Yükselt") }
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(
    items: List<Utterance>,
    modifier: Modifier,
    manual: Boolean,
    qualityModel: Boolean,
    onRespeak: (Long) -> Unit,
    onEdit: (Utterance) -> Unit,
    onFullscreen: (Utterance) -> Unit,
    onRequality: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible
    // Klavye açılınca liste kısalır; son baloncuk görünür kalsın.
    LaunchedEffect(items.size, items.lastOrNull()?.state, imeVisible) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }
    if (items.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (manual) "Başlat'a basın. Konuşurken \"Ben konuşuyorum\", karşı taraf konuşurken \"Karşı taraf konuşuyor\" düğmesini basılı tutun."
                else "Başlat'a basın. Karşı taraf konuşunca Türkçesi kulaklıktan, siz konuşunca çevirisi hoparlörden gelir.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { Bubble(it, qualityModel, onRespeak, onEdit, onFullscreen, onRequality) }
    }
}

@Composable
private fun Bubble(
    u: Utterance, qualityModel: Boolean,
    onRespeak: (Long) -> Unit, onEdit: (Utterance) -> Unit, onFullscreen: (Utterance) -> Unit, onRequality: (Long) -> Unit,
) {
    val mine = u.speaker == Speaker.USER
    if (u.state == UtteranceState.DROPPED) {
        Text(
            "· ${u.note ?: "atlandı"}${u.text?.let { ": $it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    var menu by remember { mutableStateOf(false) }
    val settled = u.state == UtteranceState.DONE || u.state == UtteranceState.ERROR
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Card(
                onClick = { if (settled) menu = true },
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
                        (if (mine) "Siz" else "Karşı taraf") + (if (u.typed) " ✎" else "") + (if (u.quality) " ✦" else "") +
                            (if (src.isNotEmpty()) " · $src" else "") + (if (dst.isNotEmpty()) " → $dst" else ""),
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
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (u.translation != null) DropdownMenuItem(text = { Text("Tekrar oku") }, onClick = { menu = false; onRespeak(u.id) })
                if (qualityModel && !u.quality && u.text != null && u.sourceLang != null) {
                    DropdownMenuItem(text = { Text("Kaliteli çevir (yavaş)") }, onClick = { menu = false; onRequality(u.id) })
                }
                if (mine && u.text != null) DropdownMenuItem(text = { Text("Düzenle") }, onClick = { menu = false; onEdit(u) })
                if (u.translation != null) DropdownMenuItem(text = { Text("Tam ekran göster") }, onClick = { menu = false; onFullscreen(u) })
            }
        }
    }
}

/** Bas-konuş: basılı tutulduğu sürece kayıt; parmak nereye kayarsa kaysın kalkınca biter. */
@Composable
private fun HoldButtons(vm: ConversationViewModel, status: EngineStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HoldButton(
            "Ben konuşuyorum", active = status.recording == Speaker.USER,
            color = MaterialTheme.colorScheme.primary, onColor = MaterialTheme.colorScheme.onPrimary,
            onBegin = { vm.beginManual(Speaker.USER) }, onEnd = vm::endManual, modifier = Modifier.weight(1f),
        )
        HoldButton(
            "Karşı taraf konuşuyor", active = status.recording == Speaker.PARTNER,
            color = MaterialTheme.colorScheme.tertiary, onColor = MaterialTheme.colorScheme.onTertiary,
            onBegin = { vm.beginManual(Speaker.PARTNER) }, onEnd = vm::endManual, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HoldButton(
    label: String, active: Boolean, color: Color, onColor: Color, onBegin: () -> Unit, onEnd: () -> Unit, modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) color.copy(alpha = 0.55f) else color)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBegin()
                    try {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        onEnd()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (active) "● $label" else label,
            color = onColor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** Yazıyla çeviri: soldaki anahtar kimin yazdığını seçer (Ben / karşı taraf; karşı taraf için dil seçili olmalı). */
@Composable
private fun TextBar(vm: ConversationViewModel, status: EngineStatus) {
    var who by rememberSaveable { mutableStateOf(Speaker.USER) }
    var text by rememberSaveable { mutableStateOf("") }
    val partner = Languages.FOREIGN.firstOrNull { it.code == status.partnerLang }
    val enabled = who == Speaker.USER || partner != null
    val hint = when {
        who == Speaker.USER -> Languages.TURKISH.typeHint
        partner != null -> partner.typeHint
        else -> "Önce karşı tarafın dilini seçin"
    }
    fun send() {
        if (text.isBlank() || !enabled) return
        if (vm.submitText(text, who)) text = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = who == Speaker.PARTNER,
            onClick = { who = if (who == Speaker.USER) Speaker.PARTNER else Speaker.USER },
            label = { Text(if (who == Speaker.USER) "Ben ▾" else "${partner?.nameTr ?: "Karşı taraf"} ▾") },
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(hint) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = { send() }, enabled = text.isNotBlank() && enabled) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
                }
            },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(vm: ConversationViewModel, onClose: () -> Unit, onFixBattery: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Ayarlar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            Text("Konuşma biçimi", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.manualInput, onClick = { vm.chooseManualInput(false) }, label = { Text("Otomatik") })
                FilterChip(selected = vm.manualInput, onClick = { vm.chooseManualInput(true) }, label = { Text("Düğmeyle") })
            }
            Text(
                "Otomatik: uygulama kimin konuştuğunu kendisi anlar. Düğmeyle: konuşan, ilgili düğmeyi basılı tutar; " +
                    "gürültülü yerde ve kulaklıksız kullanımda daha güvenilir.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Çeviri kalitesi", style = MaterialTheme.typography.titleMedium)
            if (vm.setup.qualityModel) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !vm.quality, onClick = { vm.chooseQuality(false) }, label = { Text("Hızlı") })
                    FilterChip(selected = vm.quality, onClick = { vm.chooseQuality(true) }, label = { Text("Kaliteli (yavaş)") })
                }
                Text(
                    "Hızlı: her cümle 1-2 sn, ara sıra anlam hatası. Kaliteli: 5-10 sn, çok daha güvenilir; hastane, polis gibi önemli " +
                        "konuşmalar için. Yazılan metinler ve \"Kaliteli çevir\" her zaman kaliteli modeli kullanır.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Kaliteli çeviri modeli (4,6 GB, Wi-Fi'de bir kez) indirilirse önemli cümleler daha güvenilir çevrilir; " +
                        "canlı sohbet hızlı modelle sürer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val progress = vm.setup.qualityProgress
                if (progress != null) Text(progress, style = MaterialTheme.typography.bodySmall)
                else OutlinedButton(onClick = vm::downloadQualityModel) { Text("Kaliteli modeli indir (4,6 GB)") }
            }

            Text("Okuma hızı", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.speechRate >= 1f, onClick = { vm.chooseSpeechRate(1f) }, label = { Text("Normal") })
                FilterChip(selected = vm.speechRate < 1f, onClick = { vm.chooseSpeechRate(0.8f) }, label = { Text("Yavaş") })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sorun bildirimine son konuşmaların sesini ekle", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.attachAudio, onCheckedChange = vm::chooseAttachAudio)
            }

            Text(
                "Hoparlör sesi: kulaklık takılıyken ses tuşu kulaklığın sesini ayarlar. Hoparlörün sesini kulaklığı çıkarıp ayarlayın.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vm.setup.batteryRestricted) TextButton(onClick = { onClose(); onFixBattery() }) { Text("Pil kısıtlamasını kaldır") }
        }
    }
}

/** Sorun bildirimi: kısa açıklama + ses kaydı seçeneği; rapor WhatsApp'la geliştiriciye gider. */
@Composable
private fun ReportDialog(vm: ConversationViewModel, onSend: (String, Boolean) -> Unit, onClose: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(vm.attachAudio) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Sorun bildir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text("Ne oldu? (isteğe bağlı)") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Son konuşmaların ses kaydını ekle (karşı tarafın sesi de gider)",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    Switch(checked = attach, onCheckedChange = { attach = it })
                }
                Text(
                    "Son konuşmalar, süreler ve kararlar rapora yazılır. WhatsApp açılır; geliştirici seçili gelmezse listeden seçin ve gönderin.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSend(note, attach); onClose() }) { Text("WhatsApp'tan gönder") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Vazgeç") } },
    )
}

@Composable
private fun EditDialog(u: Utterance, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(u.text ?: "") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Söylediğinizi düzeltin") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        },
        confirmButton = { Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Çevir ve oku") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Vazgeç") } },
    )
}

/** Çeviriyi karşı tarafa göstermek için: koyu zemin, büyük yazı; dokununca kapanır. */
@Composable
private fun FullscreenDialog(u: Utterance, onRespeak: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val text = u.translation ?: ""
        val size = when {
            text.length <= 40 -> 48.sp
            text.length <= 120 -> 36.sp
            else -> 28.sp
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onClose)
                .padding(24.dp)
        ) {
            Text(langName(u.targetLang), color = Color.Gray, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopStart))
            Text(
                text, color = Color.White, fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center),
            )
            Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRespeak) { Text("Oku") }
                OutlinedButton(onClick = onClose) { Text("Kapat", color = Color.White) }
            }
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
