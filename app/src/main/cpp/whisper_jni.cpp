// whisper.cpp ile Kotlin arasındaki köprü: yazıya dökme, dil tespiti ve akışlı ses algılama (Silero VAD).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "CanliCeviri"

namespace {

void log_callback(ggml_log_level level, const char * text, void *) {
    // whisper.cpp her VAD çerçevesinde INFO yazıyor; yalnızca uyarı ve hataları geçir.
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

struct Engine {
    whisper_context * ctx = nullptr;
    // Dil tespitinin kodlayıp sakladığı ses (whisper.cpp yaması): aynı ses aynı bağlamla yazıya dökülürse
    // kodlayıcı yeniden çalışmıyor. Başka bir ses gelirse saklanan kodlama bırakılıyor.
    uint64_t kept_hash = 0;
    int kept_audio_ctx = -1;
    std::mutex mutex;
};

// Sesin parmak izi (FNV-1a): dil tespitindeki sesle yazıya dökülen ses aynı mı?
uint64_t fingerprint(const std::vector<float> & pcm) {
    uint64_t h = 1469598103934665603ULL;
    const auto * b = reinterpret_cast<const unsigned char *>(pcm.data());
    for (size_t i = 0, n = pcm.size() * sizeof(float); i < n; ++i) {
        h ^= b[i];
        h *= 1099511628211ULL;
    }
    return h ^ pcm.size();
}

jclass g_result_class = nullptr;
jmethodID g_result_ctor = nullptr;

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

std::vector<float> to_vector(JNIEnv * env, jfloatArray arr) {
    const jsize n = env->GetArrayLength(arr);
    std::vector<float> v(n);
    env->GetFloatArrayRegion(arr, 0, n, v.data());
    return v;
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Metin bayt dizisi olarak dönüyor: Whisper'ın çıktısı standart UTF-8, JNI'nin NewStringUTF'i ise
// "modified UTF-8" bekliyor; emoji gibi 4 baytlık karakterlerde çöküyor. Kotlin tarafında UTF-8 olarak çözülüyor.
jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// Kod çözücü döngüsü: kısaltılmış bağlamda Whisper bazen cümleyi bitirince baştan tekrar etmeye başlıyor
// ("A. A. A. …") ve belirteç sınırına kadar saniyelerce boşuna çözüyor. Son LOOP_N belirteç daha önce aynen
// geçtiyse metin tekrar etmeye başlamış demektir.
constexpr int LOOP_N = 6;

// Döngüye giren kod çözücüyü hemen bitirir: son LOOP_N belirteç daha önce geçtiyse yalnızca metin sonu seçilebilir.
void loop_breaker(whisper_context * ctx, whisper_state *, const whisper_token_data * tokens, int n_tokens, float * logits, void *) {
    if (n_tokens <= LOOP_N) return;
    const whisper_token_data * tail = tokens + n_tokens - LOOP_N;
    for (int j = 0; j < n_tokens - LOOP_N; ++j) {
        int k = 0;
        while (k < LOOP_N && tokens[j + k].id == tail[k].id) ++k;
        if (k < LOOP_N) continue;
        const whisper_token eot = whisper_token_eot(ctx);
        const int n_vocab = whisper_n_vocab(ctx);
        for (int v = 0; v < n_vocab; ++v) {
            if (v != eot) logits[v] = -INFINITY;
        }
        return;
    }
}

// İlk tekrarın başladığı belirteç (LOOP_N belirteçlik bir dizi daha önce aynen geçmiş), yoksa -1.
int first_repeat(const std::vector<whisper_token> & t) {
    for (int i = 1; i + LOOP_N <= (int) t.size(); ++i) {
        for (int j = 0; j < i; ++j) {
            if (std::equal(t.begin() + i, t.begin() + i + LOOP_N, t.begin() + j)) return i;
        }
    }
    return -1;
}

// Metin belirteç sınırında kesilince sonda yarım kalmış çok baytlı UTF-8 karakteri atar.
void trim_partial_utf8(std::string & s) {
    int i = (int) s.size() - 1;
    int cont = 0;
    while (i >= 0 && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80 && cont < 3) {
        --i;
        ++cont;
    }
    if (i < 0) return;
    const auto lead = static_cast<unsigned char>(s[i]);
    const int need = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
    if (need != cont) s.resize(need > cont ? i : s.size());
}

whisper_full_params base_params(int threads, int beam) {
    whisper_full_params p = whisper_full_default_params(beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_timestamps = false;
    p.print_special    = false;
    p.translate        = false;
    p.no_context       = true;
    p.no_timestamps    = true;
    p.single_segment   = true;
    p.suppress_blank   = true;
    p.suppress_nst     = true;
    p.temperature      = 0.0f;
    p.temperature_inc  = 0.0f; // sıcaklık artırarak yeniden deneme yok: gecikmeyi öngörülebilir tutuyor
    p.greedy.best_of   = 1;
    if (beam > 1) p.beam_search.beam_size = beam;
    return p;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    whisper_log_set(log_callback, nullptr);
    jclass local = env->FindClass("com/alterra/canliceviri/asr/NativeTranscript");
    if (local == nullptr) return JNI_ERR;
    g_result_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_result_ctor = env->GetMethodID(g_result_class, "<init>", "([BLjava/lang/String;FFFFFIII)V");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_init(JNIEnv * env, jobject, jstring jpath, jboolean flash_attn) {
    const std::string path = to_string(env, jpath);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu    = false;
    cp.flash_attn = flash_attn;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (ctx == nullptr) return 0;
    auto * e = new Engine();
    e->ctx = ctx;
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(e->mutex);
        whisper_free(e->ctx);
    }
    delete e;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray jsamples, jstring jlang,
        jint threads, jint audio_ctx, jint beam, jint max_tokens, jstring jprompt, jfloat temperature_inc) {
    auto * e = reinterpret_cast<Engine *>(handle);
    std::lock_guard<std::mutex> lock(e->mutex);

    const std::vector<float> pcm = to_vector(env, jsamples);
    const std::string lang   = to_string(env, jlang);
    const std::string prompt = to_string(env, jprompt);

    whisper_full_params p = base_params(threads, beam);
    p.audio_ctx       = audio_ctx;
    p.max_tokens      = max_tokens;
    p.language        = lang.empty() ? "auto" : lang.c_str();
    p.detect_language = false;
    if (!prompt.empty()) p.initial_prompt = prompt.c_str();
    // Kurtarma: çıktı fazla tekrarlıysa (entropi düşük) ya da güven düşükse daha yüksek sıcaklıkla yeniden çöz.
    // Yalnızca takılan parçada ek süre harcanır.
    if (temperature_inc > 0.0f) p.temperature_inc = temperature_inc;
    p.logits_filter_callback = loop_breaker;

    // Dil tespiti bu sesi aynı bağlamla kodladıysa whisper_full kodlayıcıyı atlıyor; değilse kodlamayı bırak.
    const bool reuse = e->kept_audio_ctx >= 0 && e->kept_audio_ctx == audio_ctx && e->kept_hash == fingerprint(pcm);
    if (!reuse) whisper_drop_kept_encoding(e->ctx);
    e->kept_audio_ctx = -1;

    whisper_reset_timings(e->ctx);
    const auto t0 = std::chrono::steady_clock::now();
    const int rc = whisper_full(e->ctx, p, pcm.data(), (int) pcm.size());
    const float total_ms = (float) ms_since(t0);

    // Metin belirteçlerden kuruluyor: döngü varsa ilk tekrardan sonrası atılıyor.
    std::string text;
    float no_speech = 0.0f;
    float sum_p = 0.0f;
    int n_tokens = 0;
    int loop_at = -1;
    if (rc == 0) {
        const whisper_token eot = whisper_token_eot(e->ctx);
        std::vector<whisper_token> ids;
        std::vector<float> probs;
        const int n_segments = whisper_full_n_segments(e->ctx);
        for (int i = 0; i < n_segments; ++i) {
            no_speech = std::max(no_speech, whisper_full_get_segment_no_speech_prob(e->ctx, i));
            const int nt = whisper_full_n_tokens(e->ctx, i);
            for (int j = 0; j < nt; ++j) {
                const whisper_token_data td = whisper_full_get_token_data(e->ctx, i, j);
                if (td.id >= eot) continue; // özel belirteçler (dil, görev, zaman) metne girmiyor
                ids.push_back(td.id);
                probs.push_back(td.p);
            }
        }
        loop_at = first_repeat(ids);
        const int keep = loop_at >= 0 ? loop_at : (int) ids.size();
        for (int i = 0; i < keep; ++i) {
            text += whisper_token_to_str(e->ctx, ids[i]);
            sum_p += probs[i];
            ++n_tokens;
        }
        if (loop_at >= 0) trim_partial_utf8(text);
    }

    whisper_timings * tm = whisper_get_timings(e->ctx);
    const float encode_ms = tm ? tm->encode_ms : 0.0f;
    const float decode_ms = tm ? tm->decode_ms : 0.0f;
    delete tm;

    const int lang_id = whisper_full_lang_id(e->ctx);
    const char * lang_str = lang_id >= 0 ? whisper_lang_str(lang_id) : "";

    jbyteArray jtext = to_bytes(env, text);
    jstring jl = env->NewStringUTF(lang_str ? lang_str : "");
    jobject result = env->NewObject(g_result_class, g_result_ctor, jtext, jl, total_ms, encode_ms, decode_ms,
                                    no_speech, n_tokens > 0 ? sum_p / (float) n_tokens : 0.0f, n_tokens, loop_at, rc);
    env->DeleteLocalRef(jtext);
    env->DeleteLocalRef(jl);
    return result;
}

// Dönüş: [süre_ms, olasılık(aday0), olasılık(aday1), ...]. Ses [audio_ctx] bağlamıyla kodlanıp saklanıyor;
// hemen ardından aynı ses aynı bağlamla yazıya dökülürse kodlayıcı ikinci kez çalışmıyor.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_detectLanguage(
        JNIEnv * env, jobject, jlong handle, jfloatArray jsamples, jint threads, jobjectArray jcandidates, jint audio_ctx) {
    auto * e = reinterpret_cast<Engine *>(handle);
    std::lock_guard<std::mutex> lock(e->mutex);

    const auto t0 = std::chrono::steady_clock::now();
    const std::vector<float> pcm = to_vector(env, jsamples);
    std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
    e->kept_audio_ctx = -1;
    if (whisper_lang_auto_detect_keep(e->ctx, pcm.data(), (int) pcm.size(), audio_ctx, threads, probs.data()) < 0) return nullptr;
    e->kept_hash = fingerprint(pcm);
    e->kept_audio_ctx = audio_ctx;
    const float elapsed = (float) ms_since(t0);

    const jsize n = env->GetArrayLength(jcandidates);
    std::vector<float> out(n + 1, 0.0f);
    out[0] = elapsed;
    for (jsize i = 0; i < n; ++i) {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(jcandidates, i));
        const std::string code = to_string(env, js);
        env->DeleteLocalRef(js);
        const int id = whisper_lang_id(code.c_str());
        out[i + 1] = id >= 0 ? probs[id] : 0.0f;
    }
    jfloatArray arr = env->NewFloatArray(n + 1);
    env->SetFloatArrayRegion(arr, 0, n + 1, out.data());
    return arr;
}

// ---------------------------------------------------------------- Silero VAD (akışlı)

extern "C" JNIEXPORT jlong JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_vadInit(JNIEnv * env, jobject, jstring jpath, jint threads) {
    const std::string path = to_string(env, jpath);
    whisper_vad_context_params vp = whisper_vad_default_context_params();
    vp.n_threads = threads;
    vp.use_gpu   = false;
    return reinterpret_cast<jlong>(whisper_vad_init_from_file_with_params(path.c_str(), vp));
}

// 512 örneklik (32 ms) çerçevenin konuşma olasılığı. LSTM durumu çağrılar arasında korunur.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_vadProcess(JNIEnv * env, jobject, jlong handle, jfloatArray jframe) {
    auto * v = reinterpret_cast<whisper_vad_context *>(handle);
    const std::vector<float> frame = to_vector(env, jframe);
    if (!whisper_vad_detect_speech_no_reset(v, frame.data(), (int) frame.size())) return -1.0f;
    const int n = whisper_vad_n_probs(v);
    const float * probs = whisper_vad_probs(v);
    float best = 0.0f;
    for (int i = 0; i < n; ++i) best = std::max(best, probs[i]);
    return best;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_vadReset(JNIEnv *, jobject, jlong handle) {
    whisper_vad_reset_state(reinterpret_cast<whisper_vad_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_alterra_canliceviri_asr_WhisperNative_vadFree(JNIEnv *, jobject, jlong handle) {
    whisper_vad_free(reinterpret_cast<whisper_vad_context *>(handle));
}
