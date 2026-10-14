# Canlı Çeviri

*Offline live speech translation for Android (Turkish ↔ 18 languages), fully on-device: whisper.cpp + Hy-MT2 (llama.cpp) + system TTS. Source-visible under the PolyForm Noncommercial license; see [Lisans](#lisans).*

Android için internetsiz ve ücretsiz canlı sesli çeviri. Yurt dışında birebir konuşma için:

- Karşı taraf konuşur, Türkçe çeviri **kulaklıktan** gelir.
- Kullanıcı Türkçe konuşur, çeviri karşı tarafın dilinde **telefon hoparlöründen** çalar.
- Kulaklığın mikrofonu ve telefonun mikrofonu aynı anda dinlenir. Hangisinde ses daha güçlüyse konuşan odur, sıra için butona basılmaz.

Diller: Türkçe ↔ İngilizce, Çince, Hintçe, İspanyolca, Arapça, Fransızca, Bengalce, Portekizce, Rusça, Endonezce, Almanca, İtalyanca, Hollandaca, Lehçe, Çekçe, Ukraynaca, Japonca, Korece (Yunanca çeviri modelinde yok; Farsça'nın internetsiz Google sesi yok).

Hedef cihaz Samsung Galaxy S24 Ultra; geliştirme Pixel 9 Pro (GrapheneOS) üzerinde.

## Aşamalar

1. **Cihaz testi (şu an):** uygulamanın dayandığı üç platform davranışını telefonda ölçer.
   - İki mikrofon aynı anda ayrı ayrı kaydedilebiliyor mu?
   - Kulaklık takılıyken ses ayrıca telefon hoparlörüne verilebiliyor mu?
   - Sistem seslendirmesi istenen cihaza gidiyor mu, hangi diller internetsiz okunabiliyor?
2. **Çekirdek çeviri (offline):** ses algılama (Silero VAD), konuşma tanıma (Whisper, telefonda), çeviri, sistem seslendirmesi; konuşma ve dinleme modları.
3. **S24 Ultra iyileştirmeleri:** NPU'da Whisper, gürültü temizleme, kaliteli çeviri modu.

## Yapı

| Parça | Ne kullanılıyor | Nerede |
|---|---|---|
| Mikrofon | Kulaklık mikrofonu ("görüşme" türü) + telefon mikrofonu ("tanıma" türü); olmazsa tek mikrofon | `pipeline/AudioFrontEnd.kt` |
| Konuşma algılama | Silero VAD (whisper.cpp içinden, 32 ms çerçeve) | `pipeline/Segmenter.kt` |
| Mikrofon efektleri | Telefon mikrofonuna sistemin gürültü bastırma + otomatik kazanç efektleri (ayarla kapatılabilir); kulaklık mikrofonunda VOICE_COMMUNICATION kaynağı zaten uyguluyor | `audio/MicCapture.kt` |
| Whisper istemi | Türkçede nötr cümle + seyahat sözlüğü; diğer dillerde dil başına seyahat sözlüğü (`Lang.glossary`). Ölçümde `sozluk=1` ile açılır; test cümleleri sözlük kelimeleri içerdiği için o ölçüm iyimserdir | `Languages.kt`, `ConversationEngine.processAudio` |
| Kim konuştu | Çift mikrofonda iki kaydın seviye farkı; tek mikrofonda dil (Türkçe mi) | `pipeline/ConversationEngine.kt` |
| Karşı tarafın dili | Her karşı taraf cümlesinde tespit (kodlayıcı paylaşıldığı için ucuz); cümlenin kendi tespiti ≥%75 ise o, değilse son cümlelerin üstel ortalaması (katsayı 0,6, ~3 cümle hafıza). Kilit/çözülme yok; kişi değişince birkaç cümlede geçer. Elle seçim her zaman mümkün | `ConversationEngine.observeLang` |
| Yazıya dökme | Whisper small (q8_0), whisper.cpp (yamalı), telefonun işlemcisinde | `app/src/main/cpp/whisper_jni.cpp`, `asr/` |
| Çeviri | Tencent Hy-MT2-1.8B (Q4_K_M, 1,1 GB), llama.cpp, telefonun işlemcisinde; Google servisi ve telemetri yok. İsteğe bağlı Hy-MT2-7B (Q4_K_M, 4,6 GB): "kaliteli" mod, yazılı metin, düzeltme ve baloncuktan "kaliteli çevir". Kaliteli modda 1,8B bırakılır; hızlı modda 7B 3 dk boşta kalınca ya da bellek daralınca bırakılır | `llm/` modülü, `pipeline/ConversationEngine.kt` |
| Seslendirme | Google'ın ses motoru (Speech Services by Google; yoksa sistemin motoru); ses parçaları uygulamaya alınıp istenen cihaza çalınıyor. Dilin sesi yoksa başka dilin sesiyle okunmuyor; hoparlör bacağı yumuşak sınırlayıcıyla +5 dB | `audio/TtsEngine.kt`, `audio/PcmPlayer.kt` |
| Arka plan | Motor uygulama ömründe (`CanliCeviriApp`); mikrofon türünde ön plan hizmeti ekran kapalıyken de dinletiyor (Android 14+: yalnızca ekrandayken başlatılabilir, arama kesintisinde kapatılmıyor) | `TranslationService.kt` |
| Kesinti | Arama/susturma 2 Hz denetleniyor (`AudioManager.mode`, `isClientSilenced`, 1 sn sıfır çerçeve); duraklama → mikrofonlar kapanır, okumalar kesilir; bitince yeniden açılır, konuşma yarıda kaldıysa karşı taraftan sabit cümleyle tekrar istenir. Kulaklık takılıp çıkınca mikrofonlar yeniden açılır. Okuma sırasında geçici ses odağı (müzik kısılır) | `pipeline/ConversationEngine.kt` |
| Giriş biçimleri | İşçi dört iş türü işliyor: ses parçası (VAD ya da bas-konuş düğmesi, konuşan zorlanmış), yazılan metin, tekrar okuma, düzeltme | `pipeline/ConversationEngine.kt` |
| Sesli okuma kuralı | Ayar: her zaman / yalnızca konuşma modunda / kapalı. Dinleme modunda ayrıca: kulaklık yoksa okunmaz (hoparlör mikrofonu kapatır), bekleyen okuma 2'ye ulaşınca yeni çeviriler yalnızca ekranda (okuma konuşma kadar sürer, sürekli konuşmada yetişemez) | `ConversationEngine.shouldSpeak` |
| Sorun bildirimi | Son 30 konuşma, olay halkası, ayarlar, cihaz, önceki kapanış nedeni + son parçaların WAV'ları; WhatsApp'a `jid` ile (numara `local.properties` → `BuildConfig.RAPOR_WHATSAPP`) | `report/`, `MainActivity.sendIssue` |

## Derleme

```bash
scripts/kurulum.sh    # whisper.cpp (yamalı) ve llama.cpp kaynak kodu (sabit sürümler)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties`'e `rapor.whatsapp=905…` (sorun bildiriminin gideceği numara) eklenir; boş kalırsa paylaşımda kişi elle seçilir.

**Sürüm imzası:** müşteri APK'sı projeye özel anahtarla imzalanır: `local.properties` → `imza.dosya` (örn. `~/.android/canli-ceviri.jks`), `imza.takma`, `imza.parola`. Anahtar ve parola git dışıdır; **yedekleyin**, kaybolursa müşterinin telefonuna güncelleme kurulamaz (uygulama silinip modeller yeniden indirilir). Anahtar yoksa derleme geliştirme anahtarıyla imzalar ve uyarır; o APK müşteriye gönderilmez.

```bash
keytool -genkeypair -keystore ~/.android/canli-ceviri.jks -alias canliceviri -keyalg RSA -keysize 2048 -validity 10000
```

Modeller ilk açılışta uygulamadan indirilir. Geliştirirken adb ile de konabilir:

```bash
adb push ggml-small-q8_0.bin ggml-silero-v6.2.0.bin Hy-MT2-1.8B-Q4_K_M.gguf /sdcard/Android/data/com.alterra.canliceviri/files/models/
```

## Geliştirici komutları (yalnızca debug sürüm)

```bash
tools/test-sesleri.sh <adb-seri>   # macOS sesleriyle 11 dilde test cümleleri üretip telefona gönderir
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es bench "small;diller=tr;ctx=auto,768"'
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es mt "threads=4"'
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es simule "tr1,en,tr2,es"'
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es simule "karsi=bn;bn,tr1"'   # karşı tarafın dili elle
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es simule "dugme:tr1,en"'       # bas-konuş: her ses bir basış
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es simule "karsi=en;yaz=tr:Merhaba|en:Hello;tr3"'  # yazılı giriş
adb logcat -s CanliCeviri CanliCeviriBench
```

Sonuçlar `files/bench.txt`, `files/mt.txt` ve logcat'e yazılır.

## Ölçümler (Pixel 9 Pro, işlemci, 4 çekirdek)

Süreler arka planda işlemciyi %100-370 kullanan bir uygulamayla ölçüldü; temiz telefonda daha kısa olmalı. Hata oranları (CER) bundan etkilenmez.

- **Whisper small, kısaltılmış bağlam (ses + 1 sn), flash attention kapalı:** 3-5 sn'lik cümlede dil tespiti + yazıya dökme toplam 0,7-0,9 sn (kodlayıcı bir kez çalışıyor).
  - Türkçe CER %4,6 (8 cümle).
  - İngilizce, İspanyolca, Arapça, Fransızca, Rusça, Endonezce %0; Portekizce %2, Hintçe %10, Çince %14. Dil tespiti 17/18.
  - Almanca %1,5, İtalyanca %0, Hollandaca %6, Lehçe %2, Çekçe %3, Ukraynaca %0, Japonca %4, Korece %6 (hepsi 0,6-0,9 sn, dil tespiti dahil). Ukraynaca dil tespitinde Rusça sanılıyor (%91); elle seçilince yazı hatasız. Çeviri (Hy-MT2) bu dillerde ön planda 1,5-2,3 sn; arka planda ekran kapalıyken ölçülen 5-15 sn sistem kısıtlamasından (ön plan hizmeti olmadan çalıştırılmıştı).
  - Bengalce: Whisper small Bengalceyi Devanagari harfleriyle yazıyor; Bengal alfabesine çevriliyor ama ses hataları yüzünden çeviri güvenilmez. Dil tespiti Bengalceyi Hintçe sanıyor; elle seçilmeli. Türkçe → Bengalce yönü sorunsuz.
- **whisper.cpp flash attention hatası:** açıkken kodlayıcı ve çapraz dikkat bağlamı 256'nın katına dolduruyor ama dolguyu maskelemiyor. Kısaltılmış bağlam 256'nın katı değilse model önceki seslerden kalan verilere de bakıyor; Türkçe CER %3'ten %20-45'e çıkıyordu. Bu yüzden kapalı (`asr/Whisper.kt`).
- **Döngü:** kısaltılmış bağlamda Whisper bazen cümleyi bitirip baştan tekrar ediyor. Son 6 belirteç daha önce geçtiyse çözme kesiliyor; cümle yarıda kaldıysa 768'lik bağlamla bir kez daha çözülüyor.
- **Dil tespiti + yazıya dökme tek kodlama:** whisper.cpp yaması (`scripts/whisper-kodlama-paylasimi.patch`) dil tespitinin kodladığı sesi yazıya dökmede yeniden kullanıyor; tek mikrofonda cümle başına ~0,5 sn kazanç.
- Işın araması (5) Türkçede fayda etmedi (%3,9'a karşı %4,0), çözmeyi yarı yarıya yavaşlattı. Tam bağlam (30 sn) CER %4,0 ama 3 kat yavaş; 768 bağlam %3,2, 2 kat yavaş.
- Whisper large-v3-turbo (q5_0) işlemcide cümle başına 20 sn'den fazla: kullanılamaz.
- **Çeviri modeli karşılaştırması (54 cümle, Pixel, ön planda):** 1,8B ortalama 1,7 sn; 7B ortalama 13,9 sn (kısa cümle 6-8 sn, Hintçe/Bengalce gibi uzun yazımlılar 50 sn). Kalite: "Uçağım üçte kalkıyor" cümlesinde 1,8B 18 dilin 10'unda saati bozdu, 7B hepsinde doğru. 7B yüklemesi ~10 sn, iki model birlikte ~8 GB RSS; takas baskısında 7B çevirisi dakikalara çıkıyor.
- **Kesintisiz dinleme (dinleme modu, 36 sn'lik 8 cümlelik tek parça, `simule "akis"`):** 8/8 cümle ayrı parça olarak yakalandı ve doğru dilde çevrildi; cümle bitiminden yazıya 1,2-3,2 sn, çeviriye +1,3-1,7 sn; kuyruk birikmedi (işleme gerçek zamandan hızlı).
- **Uçtan uca (simülasyon, tek mikrofon, dil tespiti dahil):** sessizlik algılandıktan sonra ilk çeviri sesi 2,1-3,1 sn (ağır arka plan yükü altında). Çeviri (Hy-MT2) cümle başına 0,9-1,9 sn; Bengalce gibi uzun yazımlı dillerde 3-4 sn.
- **Seslendirme (Google TTS, 11 dilin internetsiz sesi):** Başlat'ta Türkçe ve karşı tarafın dili sessizce bir kez sentezleniyor; ilk Türkçe okuma 1,4 sn'den 0,05 sn'ye indi. Denetim: `--es ses 1` her dili hoparlörden okuyup kullanılan sesi `files/ses.txt`'ye yazar.
- İki dahili mikrofon: yalnızca biri "görüşme" türündeyse aynı anda ayrı açılabiliyor.

## Cihaz testi nasıl yapılır

1. Mikrofonlu kulaklığı takın, uygulamayı açın, mikrofon iznini verin.
2. **1 · İki mikrofon:** "Testi başlat" deyin; önce kulaklığın mikrofonuna, sonra telefonun alt ucuna konuşun (18 sn).
3. **2 · Kulaklık / hoparlör:** medya sesini orta seviyeye getirip "Sırayla test et" deyin; telefon bipleri kendisi dinleyip ölçer.
4. **3 · Sesli okuma:** Türkçe → kulaklık ve yabancı dil → hoparlör düğmelerini deneyin.
5. "Raporu paylaş" ile sonucu gönderin. Rapor ayrıca telefonda `Android/data/com.alterra.canliceviri/files/rapor.txt` dosyasına yazılır:

```bash
adb shell cat /sdcard/Android/data/com.alterra.canliceviri/files/rapor.txt
```

## Lisans

Bu depo **PolyForm Noncommercial 1.0.0** lisansıyla yayımlanmıştır (`LICENSE`): kaynak kod herkese açıktır, kişisel, eğitim ve
araştırma amaçlı kullanılabilir; **ticari kullanım, satış ve ticari dağıtım için yazılı izin gerekir.** Telif hakkı © 2026 Bora Yunusoğlu.

Kullanılan üçüncü taraf bileşenler kendi lisanslarıyla gelir ve bu depoya dahil değildir (`scripts/kurulum.sh` indirir, uygulama modelleri
ilk açılışta indirir):

| Bileşen | Lisans |
|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) ve OpenAI Whisper modelleri | MIT |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT |
| [Tencent Hy-MT2](https://huggingface.co/tencent/Hy-MT2-1.8B) çeviri modelleri (1,8B, 7B) | Apache-2.0 |
| [Silero VAD](https://github.com/snakers4/silero-vad) (whisper.cpp dönüşümü) | MIT |
| Google Speech Services (sistem seslendirme motoru) | Google'ın kendi koşulları; uygulamaya dahil değil |
