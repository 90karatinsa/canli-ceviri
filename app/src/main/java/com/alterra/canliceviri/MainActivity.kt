package com.alterra.canliceviri

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.alterra.canliceviri.diag.DiagnosticsScreen
import com.alterra.canliceviri.diag.DiagnosticsViewModel
import com.alterra.canliceviri.ui.CanliCeviriTheme
import com.alterra.canliceviri.ui.ConversationScreen
import com.alterra.canliceviri.ui.ConversationViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: DiagnosticsViewModel by viewModels()
    private val convo: ConversationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleDevIntent(intent)
        setContent {
            CanliCeviriTheme {
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    vm.onPermissionResult(granted)
                }
                LaunchedEffect(Unit) {
                    if (hasMicPermission()) vm.onPermissionResult(true) else permission.launch(Manifest.permission.RECORD_AUDIO)
                }
                // Çeviri ya da test sürerken ekran kapanmasın: arka plandaki uygulamanın mikrofonu susturuluyor.
                val convoStatus by convo.status.collectAsState()
                val keepScreenOn = convoStatus.running || vm.busy
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                var screen by rememberSaveable { mutableStateOf("konusma") }
                BackHandler(enabled = screen == "test") { screen = "konusma" }
                if (screen == "test") {
                    DiagnosticsScreen(
                        vm = vm,
                        onRequestPermission = { requestPermissionOrOpenSettings { permission.launch(Manifest.permission.RECORD_AUDIO) } },
                        onShareText = ::shareText,
                        onCopyText = ::copyText,
                        onShareFiles = ::shareFiles,
                        onOpenTtsSettings = ::openTtsSettings,
                        onInstallTtsData = ::installTtsData,
                        onBack = { screen = "konusma" },
                    )
                } else {
                    ConversationScreen(
                        vm = convo,
                        onOpenDiagnostics = {
                            // Testler mikrofonu kullanıyor; çeviri çalışıyorsa durdur.
                            if (convo.status.value.running) convo.engine.stop()
                            screen = "test"
                        },
                        onInstallVoices = ::installTtsData,
                        onInstallGoogleTts = ::openGoogleTtsInStore,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDevIntent(intent)
    }

    /** Geliştirici komutları yalnızca debug sürümde: adb shell am start … --es bench "small,turbo". */
    private fun handleDevIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i("CanliCeviri", "geliştirici niyeti: ${intent?.extras?.keySet()}")
        intent?.getStringExtra("bench")?.let {
            intent.removeExtra("bench")
            vm.runBench(it)
        }
        intent?.getStringExtra("simule")?.let {
            intent.removeExtra("simule")
            convo.simulate(it)
        }
        intent?.getStringExtra("mt")?.let {
            intent.removeExtra("mt")
            vm.runMtBench(it)
        }
        intent?.getStringExtra("ses")?.let {
            intent.removeExtra("ses")
            vm.runVoiceCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ayarlardan izin ya da ses verisi indirip dönülmüş olabilir.
        vm.onPermissionResult(hasMicPermission())
        vm.refreshTtsStatuses()
        convo.refreshSetup()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** İzin kalıcı olarak reddedildiyse sistem penceresi açılmaz; uygulama ayarlarına yönlendir. */
    private fun requestPermissionOrOpenSettings(request: () -> Unit) {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !hasAskedBefore()) {
            markAsked()
            request()
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun hasAskedBefore() = getPreferences(MODE_PRIVATE).getBoolean("izin_soruldu", false)
    private fun markAsked() = getPreferences(MODE_PRIVATE).edit().putBoolean("izin_soruldu", true).apply()

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Canlı Çeviri cihaz testi")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Raporu paylaş"))
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Canlı Çeviri raporu", text))
        Toast.makeText(this, "Rapor kopyalandı", Toast.LENGTH_SHORT).show()
    }

    private fun shareFiles(files: List<File>) {
        val uris = ArrayList(files.filter { it.exists() }.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        if (uris.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("audio/wav")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Kayıtları paylaş"))
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Google'ın ses motoru (11 dilin internetsiz sesi) Play Store'da; mağaza yoksa tarayıcıda. */
    private fun openGoogleTtsInStore() {
        val id = "com.google.android.tts"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
        }
    }

    private fun installTtsData(enginePackage: String?) {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { enginePackage?.let { setPackage(it) } })
        } catch (_: ActivityNotFoundException) {
            openTtsSettings()
        }
    }
}
