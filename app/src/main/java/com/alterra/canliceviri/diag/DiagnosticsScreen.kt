package com.alterra.canliceviri.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alterra.canliceviri.BuildConfig
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.audio.AudioDevices
import com.alterra.canliceviri.audio.Dsp
import com.alterra.canliceviri.audio.TtsEngine
import java.io.File

@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    onRequestPermission: () -> Unit,
    onShareText: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onShareFiles: (List<File>) -> Unit,
    onOpenTtsSettings: () -> Unit,
    onInstallTtsData: (String?) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("← Çeviriye dön") }
            Column {
                Text("Canlı Çeviri", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Cihaz testi · ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!vm.hasPermission) {
                Section("Mikrofon izni gerekli", "Testler mikrofonu kullanıyor.") {
                    Button(onClick = onRequestPermission) { Text("İzin ver") }
                }
            }
            if (vm.benchLog.isNotEmpty()) {
                Section("Geliştirici · ASR testi") {
                    vm.benchLog.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            OneTapCard(vm, onShareText)
            SummaryCard(vm)
            DualMicCard(vm, onShareFiles)
            OutputCard(vm)
            TtsCard(vm, onOpenTtsSettings, onInstallTtsData)
            DevicesCard(vm)
            ReportCard(vm, onShareText, onCopyText)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ Kartlar

@Composable
private fun OneTapCard(vm: DiagnosticsViewModel, onShareText: (String) -> Unit) {
    Section(
        "Tek düğmeyle test",
        "Kulaklığı takın, telefonun medya sesini orta seviyeye getirin ve telefonu masaya bırakın. " +
            "Yaklaşık 1 dakika sürer: kısa bipler ve iki cümle duyacaksınız, konuşmanız gerekmez. Bitince raporu gönderin.",
    ) {
        Button(
            onClick = vm::runAll,
            enabled = !vm.busy && vm.hasPermission,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.allRunning) "Test sürüyor…" else "Hepsini test et") }
        if (vm.allDone && !vm.busy) {
            Button(onClick = { onShareText(vm.buildReport()) }, modifier = Modifier.fillMaxWidth()) { Text("Raporu gönder") }
        }
    }
}

@Composable
private fun SummaryCard(vm: DiagnosticsViewModel) {
    Section("Özet") {
        SummaryRow("İki mikrofon aynı anda", vm.dual.verdict)
        SummaryRow("Kulaklık takılıyken hoparlör", vm.output.sequential)
        SummaryRow("Aynı anda iki çıkış (ek)", vm.output.simultaneous)
        SummaryRow("Sesli okuma yönlendirme", vm.ttsState.verdict)
        if (vm.scan.pairVerdict != null) SummaryRow("Tarama: iki mikrofon", vm.scan.pairVerdict)
        if (vm.scan.stereoVerdict != null) SummaryRow("Tarama: telefon stereo", vm.scan.stereoVerdict)
        val statuses = vm.ttsState.statuses
        if (statuses.isNotEmpty()) {
            val ready = statuses.count { it.state == TtsEngine.State.READY }
            Text(
                "İnternetsiz sesli okuma: $ready/${statuses.size} dil hazır",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DualMicCard(vm: DiagnosticsViewModel, onShareFiles: (List<File>) -> Unit) {
    val d = vm.dual
    val running = vm.busy && d.phase != DualPhase.IDLE && d.phase != DualPhase.DONE
    Section(
        "1 · İki mikrofon aynı anda",
        "Kulaklığın mikrofonu (A) ve telefonun mikrofonu (B) aynı anda, ayrı ayrı dinlenebiliyor mu?",
    ) {
        Picker(
            label = "A · Kulaklık mikrofonu",
            items = vm.mics,
            selected = vm.mics.firstOrNull { it.id == vm.selectedA },
            itemLabel = { AudioDevices.describe(it) },
            enabled = !vm.busy,
        ) { vm.selectedA = it.id }
        Picker(
            label = "B · Telefon mikrofonu",
            items = vm.mics,
            selected = vm.mics.firstOrNull { it.id == vm.selectedB },
            itemLabel = { AudioDevices.describe(it) },
            enabled = !vm.busy,
        ) { vm.selectedB = it.id }
        Picker(
            label = "Kayıt türü",
            items = AUDIO_SOURCES,
            selected = AUDIO_SOURCES.firstOrNull { it.first == vm.source },
            itemLabel = { it.second },
            enabled = !vm.busy,
        ) { vm.source = it.first }

        Instruction(d.phase, d.secondsLeft)

        if (running || d.phase == DualPhase.DONE && d.levelA > Dsp.FLOOR_DB) {
            LevelBar("A 🎧", d.levelA, MaterialTheme.colorScheme.primary)
            LevelBar("B 📱", d.levelB, MaterialTheme.colorScheme.tertiary)
            Text("Sistemin bağladığı · A: ${d.routedA}", style = MaterialTheme.typography.bodySmall)
            Text("Sistemin bağladığı · B: ${d.routedB}", style = MaterialTheme.typography.bodySmall)
            if (d.silencedA == true || d.silencedB == true) {
                Text(
                    "⚠️ Sistem susturdu · A: ${d.silencedA} · B: ${d.silencedB}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(onClick = vm::stopTest) { Text("Durdur") }
            } else {
                Button(onClick = vm::startDualTest, enabled = !vm.busy && vm.hasPermission) { Text("Testi başlat (18 sn)") }
                OutlinedButton(onClick = vm::startLive, enabled = !vm.busy && vm.hasPermission) { Text("Canlı dinle") }
            }
        }
        VerdictView(d.verdict)
        if (d.detail.isNotEmpty()) Detail(d.detail)
        if (d.recordings.isNotEmpty() && !vm.busy) {
            TextButton(onClick = { onShareFiles(d.recordings) }) { Text("Kayıtları paylaş (2 WAV)") }
        }

        HorizontalDivider()
        val sc = vm.scan
        Text("Otomatik tarama", style = MaterialTheme.typography.labelLarge)
        Detail(
            "Konuşmanız gerekmez, yaklaşık 40 sn sürer. Her kayıt türü eşleşmesini dener ve iki mikrofonun hangisinde " +
                "gerçekten ayrı açıldığını bulur. Telefonun kendi mikrofonlarından stereo kayıt da denenir."
        )
        if (sc.step.isNotEmpty()) {
            Text(sc.step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedButton(onClick = vm::startScan, enabled = !vm.busy && vm.hasPermission) { Text("Taramayı başlat") }
        if (sc.pairVerdict != null) {
            Text("İki mikrofon", style = MaterialTheme.typography.labelMedium)
            VerdictView(sc.pairVerdict)
            sc.pairs.forEach { Detail(DiagnosticsViewModel.pairLine(it)) }
        }
        if (sc.stereoVerdict != null) {
            Text("Telefonun kendi mikrofonları (stereo)", style = MaterialTheme.typography.labelMedium)
            VerdictView(sc.stereoVerdict)
            sc.stereo.forEach { Detail(DiagnosticsViewModel.stereoLine(it)) }
        }
    }
}

@Composable
private fun Instruction(phase: DualPhase, seconds: Int) {
    val (text, strong) = when (phase) {
        DualPhase.IDLE, DualPhase.DONE ->
            "Mikrofonlu kulaklığı takın ve sessiz bir yerde başlatın. Test 2 aşamalı, toplam 18 saniye." to false
        DualPhase.LIVE ->
            "Canlı: önce kulaklığın mikrofonuna, sonra telefonun alt ucuna konuşun. Hangi çubuğun oynadığına bakın." to false
        DualPhase.PREP_HEADSET ->
            "Hazırlanın… $seconds\nSıradaki: telefonu masaya bırakın ya da kolunuzu uzatıp uzakta tutun. Kulaklığın mikrofonuna konuşacaksınız." to true
        DualPhase.REC_HEADSET ->
            "🎧 Şimdi KULAKLIĞIN mikrofonuna konuşun (1'den 10'a sayın) · $seconds" to true
        DualPhase.PREP_PHONE ->
            "Hazırlanın… $seconds\nSıradaki: kulaklığın mikrofonunu avucunuzla kapatın ya da uzaklaştırın. Telefonun alt ucuna konuşacaksınız." to true
        DualPhase.REC_PHONE ->
            "📱 Şimdi TELEFONUN alt ucuna konuşun (1'den 10'a sayın) · $seconds" to true
    }
    if (strong) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OutputCard(vm: DiagnosticsViewModel) {
    val o = vm.output
    Section(
        "2 · Kulaklık takılıyken hoparlör",
        "Türkçe kulaklıktan, yabancı dil telefon hoparlöründen çıkacak. Telefonun mikrofonu bipleri dinleyip sonucu kendisi ölçer.",
    ) {
        Text(
            "Kulaklığı takın, telefonun medya sesini orta seviyeye getirin. Kısa bipler duyacaksınız.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (o.step.isNotEmpty()) {
            Text(o.step, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startOutputTest(false) }, enabled = !vm.busy && vm.hasPermission) { Text("Sırayla test et") }
            OutlinedButton(onClick = { vm.startOutputTest(true) }, enabled = !vm.busy && vm.hasPermission) { Text("Aynı anda") }
        }
        Text("Sırayla", style = MaterialTheme.typography.labelLarge)
        VerdictView(o.sequential)
        if (o.sequentialDetail.isNotEmpty()) Detail(o.sequentialDetail)
        Text("Aynı anda (ek)", style = MaterialTheme.typography.labelLarge)
        VerdictView(o.simultaneous)
        if (o.simultaneousDetail.isNotEmpty()) Detail(o.simultaneousDetail)
    }
}

@Composable
private fun TtsCard(vm: DiagnosticsViewModel, onOpenTtsSettings: () -> Unit, onInstallTtsData: (String?) -> Unit) {
    val t = vm.ttsState
    Section(
        "3 · Sesli okuma (TTS)",
        "Telefonun seslendirme motoru her dili internetsiz okuyabiliyor mu, ses doğru yere gidiyor mu?",
    ) {
        Picker(
            label = "Seslendirme motoru",
            items = t.engines,
            selected = t.engines.firstOrNull { it.packageName == t.engine },
            itemLabel = { "${it.label} (${it.packageName})" },
            enabled = !vm.busy,
        ) { vm.initTts(it.packageName) }
        if (!t.ready) Text("Motor hazırlanıyor…", style = MaterialTheme.typography.bodyMedium)
        t.statuses.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stateEmoji(s.state), modifier = Modifier.width(28.dp))
                Text("${s.lang.nameTr} (${s.lang.code})", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenTtsSettings) { Text("Ses ayarları") }
            TextButton(onClick = { onInstallTtsData(t.engine) }) { Text("Ses verisi indir") }
            TextButton(onClick = vm::refreshTtsStatuses) { Text("Yenile") }
        }
        HorizontalDivider()
        Button(
            onClick = { vm.speakTest(toHeadset = true) },
            enabled = !vm.busy && t.ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🎧 Türkçe → kulaklık") }
        Picker(
            label = "Yabancı dil",
            items = Languages.FOREIGN,
            selected = t.selected,
            itemLabel = { "${it.nameTr} · ${it.nativeName}" },
            enabled = !vm.busy,
        ) { vm.selectTtsLang(it) }
        Button(
            onClick = { vm.speakTest(toHeadset = false) },
            enabled = !vm.busy && t.ready,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🔊 ${t.selected.nameTr} → hoparlör") }
        t.headsetResult?.let { Detail(it) }
        t.speakerResult?.let { Detail(it) }
        VerdictView(t.verdict)
    }
}

@Composable
private fun DevicesCard(vm: DiagnosticsViewModel) {
    Section("Ses cihazları", vm.deviceSummary) {
        Text("Girişler", style = MaterialTheme.typography.labelLarge)
        vm.inputs.forEach { Detail("• ${AudioDevices.describe(it)}") }
        Text("Çıkışlar", style = MaterialTheme.typography.labelLarge)
        vm.outputs.forEach { Detail("• ${AudioDevices.describe(it)}") }
        if (vm.microphones.isNotEmpty()) {
            Text("Fiziksel mikrofonlar (${vm.microphones.size})", style = MaterialTheme.typography.labelLarge)
            vm.microphones.forEach { Detail("• $it") }
        }
    }
}

@Composable
private fun ReportCard(vm: DiagnosticsViewModel, onShareText: (String) -> Unit, onCopyText: (String) -> Unit) {
    Section("Rapor", "Sonuçları göndermek için paylaşın ya da kopyalayın.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onShareText(vm.buildReport()) }) { Text("Raporu paylaş") }
            OutlinedButton(onClick = { onCopyText(vm.buildReport()) }) { Text("Kopyala") }
        }
        if (vm.log.isNotEmpty()) {
            Text("Günlük", style = MaterialTheme.typography.labelLarge)
            vm.log.takeLast(12).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ------------------------------------------------------------------ Yapı taşları

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun verdictColor(kind: Kind): Color {
    val dark = isSystemInDarkTheme()
    return when (kind) {
        Kind.OK -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        Kind.WARN -> if (dark) Color(0xFFFFB74D) else Color(0xFF9A5B00)
        Kind.FAIL -> MaterialTheme.colorScheme.error
    }
}

private fun verdictEmoji(kind: Kind) = when (kind) {
    Kind.OK -> "✅"
    Kind.WARN -> "⚠️"
    Kind.FAIL -> "❌"
}

private fun stateEmoji(s: TtsEngine.State) = when (s) {
    TtsEngine.State.READY -> "✅"
    TtsEngine.State.NEEDS_DOWNLOAD -> "⬇️"
    TtsEngine.State.NETWORK_ONLY -> "🌐"
    TtsEngine.State.UNSUPPORTED -> "❌"
    TtsEngine.State.UNKNOWN -> "❔"
}

@Composable
private fun VerdictView(v: Verdict?) {
    if (v == null) {
        Text("Henüz denenmedi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row {
        Text(verdictEmoji(v.kind), modifier = Modifier.width(28.dp))
        Text(v.text, color = verdictColor(v.kind), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SummaryRow(label: String, v: Verdict?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(v?.let { verdictEmoji(it.kind) } ?: "○", modifier = Modifier.width(28.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LevelBar(label: String, db: Float, color: Color) {
    val fraction = ((db + 70f) / 70f).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color)
            )
        }
        Text(
            if (db <= Dsp.FLOOR_DB + 1f) "—" else "%.0f dB".format(db),
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun <T> Picker(
    label: String,
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled && items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected?.let(itemLabel) ?: "Seçilmedi",
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(" ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            open = false
                            onSelect(item)
                        },
                    )
                }
            }
        }
    }
}
