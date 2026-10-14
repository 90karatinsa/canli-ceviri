# Canlı Çeviri — Offline Live Speech Translator for Android

**Real-time, two-way voice translation that runs 100% on the phone.** No internet, no cloud API, no account, no telemetry.
Speech recognition with **whisper.cpp**, machine translation with **Tencent Hy-MT2** on **llama.cpp**, speech output with on-device
**text-to-speech**. Built for face-to-face conversations while travelling abroad.

**Keywords:** offline translator, offline speech translation, real-time voice translation, speech-to-speech translation, live interpreter app,
on-device AI, edge AI, local LLM, whisper.cpp Android, llama.cpp Android, GGUF, Hy-MT2, Silero VAD, speech recognition (ASR),
machine translation (MT), text-to-speech (TTS), Kotlin, Jetpack Compose, JNI, Android NDK, privacy-first, no internet required.

## How it works

- The other person speaks; the translation into Turkish plays in **your headset**.
- You speak Turkish; the translation in their language plays from the **phone speaker**.
- The headset mic and the phone mic are recorded at the same time. Whoever is louder on which mic is the speaker, so nobody
  presses a button to take turns. With a single mic, the speaker is decided by language detection.
- **Listening mode** translates everything it hears (announcements, interviews, tour guides) continuously into Turkish.

**Languages:** Turkish ↔ English, Chinese, Hindi, Spanish, Arabic, French, Bengali, Portuguese, Russian, Indonesian, German,
Italian, Dutch, Polish, Czech, Ukrainian, Japanese, Korean (18 languages).

## Features

- Fully offline speech-to-speech translation pipeline: VAD → ASR → MT → TTS
- Dual-microphone speaker separation (headset mic + phone mic), automatic turn-taking
- Continuous per-sentence language identification with exponential averaging; manual override
- Two translation models: fast **Hy-MT2-1.8B** for live speech, optional high-quality **Hy-MT2-7B** for typed text and re-translation
- Push-to-talk buttons as a fallback, typed input in both directions, edit and re-speak, full-screen text for showing to people
- Works with the screen off (microphone foreground service); pauses on phone calls and asks the other side to repeat
- Ducks music while speaking, adjustable speech rate, speaker volume boost
- One-tap issue report with logs and audio clips, shared via WhatsApp or any app

## Architecture

| Stage | Implementation | Code |
|---|---|---|
| Audio capture | Headset mic (`VOICE_COMMUNICATION`) + phone mic (`VOICE_RECOGNITION`), falls back to one mic; optional NoiseSuppressor and AutomaticGainControl | `pipeline/AudioFrontEnd.kt`, `audio/MicCapture.kt` |
| Voice activity detection | Silero VAD (via whisper.cpp), 32 ms frames | `pipeline/Segmenter.kt` |
| Speaker attribution | Level difference between the two mics; with one mic, language identification | `pipeline/ConversationEngine.kt` |
| Language identification | Whisper language detection on every sentence, encoder output reused for transcription (patched whisper.cpp) | `scripts/whisper-kodlama-paylasimi.patch` |
| Speech recognition | Whisper **small** (q8_0), whisper.cpp on CPU, shortened audio context, loop breaker, per-language travel glossary prompt | `app/src/main/cpp/whisper_jni.cpp`, `asr/` |
| Translation | Tencent Hy-MT2-1.8B (Q4_K_M, 1.1 GB) via llama.cpp with KV-cache prefix reuse; optional Hy-MT2-7B (Q4_K_M, 4.6 GB), unloaded when idle or low on memory | `llm/` |
| Text-to-speech | Google Speech Services offline voices (or the system engine); audio is synthesized to PCM and routed to the right output device | `audio/TtsEngine.kt`, `audio/PcmPlayer.kt` |
| Background | App-scoped engine + microphone foreground service, wake lock, battery-optimization exemption | `TranslationService.kt`, `CanliCeviriApp.kt` |
| Interruptions | Call / mic-silencing detection, audio focus with ducking, headset plug/unplug handling | `pipeline/ConversationEngine.kt` |

## Performance (Pixel 9 Pro, CPU only, 4 threads)

| Measure | Result |
|---|---|
| Language detection + transcription of a 3–5 s sentence | 0.7–0.9 s |
| Turkish character error rate (Whisper small) | 4.6 % |
| Average CER over the other 18 languages | ~3 % |
| Translation, Hy-MT2-1.8B | 1.7 s per sentence on average |
| Translation, Hy-MT2-7B | 13.9 s per sentence on average, noticeably more accurate |
| End of speech → first translated audio | 2.1–3.1 s |
| Continuous listening, 36 s / 8 sentences | 8/8 captured and translated, no backlog |

Engineering notes from the measurements:

- **whisper.cpp flash-attention bug:** with flash attention on, the padded audio context is not masked, so a shortened context
  that is not a multiple of 256 attends to stale audio. Turkish CER went from 3 % to 20–45 %. Flash attention is disabled.
- **Repetition loops** in short contexts are cut when the last 6 tokens repeat; a truncated sentence is decoded again with a larger context.
- Beam search (5) gave no gain for Turkish and halved decoding speed. Whisper large-v3-turbo takes over 20 s per sentence on CPU.
- Whisper medium (q5_0) lowers Turkish CER to 1.1 % but takes 4–19 s per sentence, so it is not used.

## Build

```bash
scripts/kurulum.sh    # fetches pinned whisper.cpp (patched) and llama.cpp sources
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Models are downloaded by the app on first launch. During development they can be pushed with adb:

```bash
adb push ggml-small-q8_0.bin ggml-silero-v6.2.0.bin Hy-MT2-1.8B-Q4_K_M.gguf /sdcard/Android/data/com.alterra.canliceviri/files/models/
```

Optional `local.properties` keys: `rapor.whatsapp` (phone number that issue reports are sent to) and
`imza.dosya` / `imza.takma` / `imza.parola` (release keystore path, alias, password). Without a keystore, release builds are
signed with the debug key and a warning is printed.

### Debug-build developer commands

```bash
tools/test-sesleri.sh <adb-serial>   # generates test sentences with macOS voices and pushes them to the phone
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es bench "small;diller=tr;ctx=auto,768"'   # ASR benchmark
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es mt "threads=4"'                          # MT benchmark
adb shell 'am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es simule "tr1,en,tr2,es"'                  # end-to-end simulation
adb logcat -s CanliCeviri CanliCeviriBench
```

## Requirements

- Android 8.0+ (arm64-v8a), 8 GB RAM recommended; 12 GB for the 7B quality model
- About 2 GB of storage for the base models, 6.5 GB with the quality model
- A wired or Bluetooth headset with a microphone for two-way conversation

The app UI is in Turkish. A Turkish end-user guide is in [KULLANIM.md](KULLANIM.md).

## License

Source-available under the **PolyForm Noncommercial License 1.0.0** (see [LICENSE](LICENSE)): free for personal, educational and
research use. **Commercial use, resale or commercial distribution requires written permission.**

Third-party components keep their own licenses and are not included in this repository (`scripts/kurulum.sh` fetches the sources,
the app downloads the models):

| Component | License |
|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and OpenAI Whisper models | MIT |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | MIT |
| [Tencent Hy-MT2](https://huggingface.co/tencent/Hy-MT2-1.8B) translation models (1.8B, 7B) | Apache-2.0 |
| [Silero VAD](https://github.com/snakers4/silero-vad) (whisper.cpp conversion) | MIT |
| Google Speech Services (system TTS engine) | Google's terms; not bundled |
