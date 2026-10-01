# Canlı Çeviri

Android için internetsiz ve ücretsiz canlı sesli çeviri. Yurt dışında birebir konuşma için:

- Karşı taraf konuşur, Türkçe çeviri **kulaklıktan** gelir.
- Kullanıcı Türkçe konuşur, çeviri karşı tarafın dilinde **telefon hoparlöründen** çalar.
- Kulaklığın mikrofonu ve telefonun mikrofonu aynı anda dinlenir. Hangisinde ses daha güçlüyse konuşan odur, sıra için butona basılmaz.

Diller: Türkçe ↔ İngilizce, Çince, Hintçe, İspanyolca, Arapça, Fransızca, Bengalce, Portekizce, Rusça, Endonezce.

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
| Kim konuştu | Çift mikrofonda iki kaydın seviye farkı; tek mikrofonda dil (Türkçe mi) | `pipeline/ConversationEngine.kt` |
| Yazıya dökme | Whisper small (q8_0), whisper.cpp (yamalı), telefonun işlemcisinde | `app/src/main/cpp/whisper_jni.cpp`, `asr/` |
| Çeviri | Tencent Hy-MT2-1.8B (Q4_K_M, 1,1 GB), llama.cpp, telefonun işlemcisinde; Google servisi ve telemetri yok | `llm/` modülü |
| Seslendirme | Google'ın ses motoru (Speech Services by Google; yoksa sistemin motoru); ses parçaları uygulamaya alınıp istenen cihaza çalınıyor. Dilin sesi yoksa başka dilin sesiyle okunmuyor | `audio/TtsEngine.kt`, `audio/PcmPlayer.kt` |

## Derleme

```bash
scripts/kurulum.sh    # whisper.cpp (yamalı) ve llama.cpp kaynak kodu (sabit sürümler)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
adb logcat -s CanliCeviri CanliCeviriBench
```

Sonuçlar `files/bench.txt`, `files/mt.txt` ve logcat'e yazılır.

## Ölçümler (Pixel 9 Pro, işlemci, 4 çekirdek)

Süreler arka planda işlemciyi %100-370 kullanan bir uygulamayla ölçüldü; temiz telefonda daha kısa olmalı. Hata oranları (CER) bundan etkilenmez.

- **Whisper small, kısaltılmış bağlam (ses + 1 sn), flash attention kapalı:** 3-5 sn'lik cümlede dil tespiti + yazıya dökme toplam 0,7-0,9 sn (kodlayıcı bir kez çalışıyor).
  - Türkçe CER %4,6 (8 cümle).
  - İngilizce, İspanyolca, Arapça, Fransızca, Rusça, Endonezce %0; Portekizce %2, Hintçe %10, Çince %14. Dil tespiti 17/18.
  - Bengalce: Whisper small Bengalceyi Devanagari harfleriyle yazıyor; Bengal alfabesine çevriliyor ama ses hataları yüzünden çeviri güvenilmez. Dil tespiti Bengalceyi Hintçe sanıyor; elle seçilmeli. Türkçe → Bengalce yönü sorunsuz.
- **whisper.cpp flash attention hatası:** açıkken kodlayıcı ve çapraz dikkat bağlamı 256'nın katına dolduruyor ama dolguyu maskelemiyor. Kısaltılmış bağlam 256'nın katı değilse model önceki seslerden kalan verilere de bakıyor; Türkçe CER %3'ten %20-45'e çıkıyordu. Bu yüzden kapalı (`asr/Whisper.kt`).
- **Döngü:** kısaltılmış bağlamda Whisper bazen cümleyi bitirip baştan tekrar ediyor. Son 6 belirteç daha önce geçtiyse çözme kesiliyor; cümle yarıda kaldıysa 768'lik bağlamla bir kez daha çözülüyor.
- **Dil tespiti + yazıya dökme tek kodlama:** whisper.cpp yaması (`scripts/whisper-kodlama-paylasimi.patch`) dil tespitinin kodladığı sesi yazıya dökmede yeniden kullanıyor; tek mikrofonda cümle başına ~0,5 sn kazanç.
- Işın araması (5) Türkçede fayda etmedi (%3,9'a karşı %4,0), çözmeyi yarı yarıya yavaşlattı. Tam bağlam (30 sn) CER %4,0 ama 3 kat yavaş; 768 bağlam %3,2, 2 kat yavaş.
- Whisper large-v3-turbo (q5_0) işlemcide cümle başına 20 sn'den fazla: kullanılamaz.
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
