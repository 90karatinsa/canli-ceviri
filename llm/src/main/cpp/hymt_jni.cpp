// llama.cpp ile Kotlin arasındaki köprü: çeviri modeli (Hy-MT2) için tek turlu üretim.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "CanliCeviriLlm"

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    std::string tmpl;
    // Bellekteki (KV) belirteçler: önceki istem + üretilen çıktı. Yeni istem aynı talimatla başlıyorsa
    // ortak baş yeniden hesaplanmıyor.
    std::vector<llama_token> kv_tokens;
    std::mutex mutex;
};

jclass g_result_class = nullptr;
jmethodID g_result_ctor = nullptr;

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Kotlin'in String'i UTF-16; Türkçe, Arapça, Bengalce vb. için baytları doğrudan UTF-8 olarak al.
std::string to_utf8(JNIEnv * env, jbyteArray arr) {
    const jsize n = env->GetArrayLength(arr);
    std::string s(n, '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jobject make_result(JNIEnv * env, const std::string & text, int n_prompt, int n_gen, float prompt_ms, float gen_ms, int rc) {
    jbyteArray jtext = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(jtext, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    jobject r = env->NewObject(g_result_class, g_result_ctor, jtext, n_prompt, n_gen, prompt_ms, gen_ms, rc);
    env->DeleteLocalRef(jtext);
    return r;
}

std::string apply_template(const std::string & tmpl, const std::string & user) {
    llama_chat_message msg{"user", user.c_str()};
    std::vector<char> buf(user.size() * 2 + 256);
    for (const char * t : {tmpl.empty() ? nullptr : tmpl.c_str(), "hunyuan-dense"}) {
        if (t == nullptr) continue;
        int n = llama_chat_apply_template(t, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(t, &msg, 1, true, buf.data(), (int32_t) buf.size());
        }
        if (n >= 0) return std::string(buf.data(), n);
    }
    return user;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    jclass local = env->FindClass("com/alterra/canliceviri/llm/LlmResult");
    if (local == nullptr) return JNI_ERR;
    g_result_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_result_ctor = env->GetMethodID(g_result_class, "<init>", "([BIIFFI)V");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alterra_canliceviri_llm_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alterra_canliceviri_llm_LlamaNative_load(JNIEnv * env, jobject, jstring jpath, jint threads, jint n_ctx) {
    const std::string path = to_string(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    // Çeviride tutarlılık için açgözlü seçim: her adımda en olası belirteç.
    e->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(e->sampler, llama_sampler_init_greedy());
    const char * t = llama_model_chat_template(model, nullptr);
    e->tmpl = t ? t : "";
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alterra_canliceviri_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(e->mutex);
        llama_sampler_free(e->sampler);
        llama_free(e->ctx);
        llama_model_free(e->model);
    }
    delete e;
}

// [userMessage] UTF-8 bayt dizisi; model sohbet şablonuyla sarılıp tek turda yanıtlanır.
extern "C" JNIEXPORT jobject JNICALL
Java_com_alterra_canliceviri_llm_LlamaNative_generate(JNIEnv * env, jobject, jlong handle, jbyteArray juser, jint max_tokens) {
    auto * e = reinterpret_cast<Engine *>(handle);
    std::lock_guard<std::mutex> lock(e->mutex);

    const std::string prompt = apply_template(e->tmpl, to_utf8(env, juser));

    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n > 0 ? n : 1);
    n = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), (int32_t) tokens.size(), true, true);
    if (n < 0) return make_result(env, "", 0, 0, 0, 0, -1);
    tokens.resize(n);
    if (n + max_tokens > (int) llama_n_ctx(e->ctx)) return make_result(env, "", n, 0, 0, 0, -2);
    if (n == 0) return make_result(env, "", 0, 0, 0, 0, -1);

    // Önceki istemle ortak başı bul; en az bir belirteç yeniden işlenmeli ki çıktı olasılıkları hesaplansın.
    size_t common = 0;
    while (common < e->kv_tokens.size() && common < tokens.size() && e->kv_tokens[common] == tokens[common]) ++common;
    if (common >= tokens.size()) common = tokens.size() - 1;
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (common == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    e->kv_tokens.assign(tokens.begin(), tokens.begin() + (long) common);
    llama_sampler_reset(e->sampler);

    const auto t0 = std::chrono::steady_clock::now();
    for (int i = (int) common; i < n; i += 512) {
        const int len = std::min(512, n - i);
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, len)) != 0) {
            llama_memory_clear(mem, true);
            e->kv_tokens.clear();
            return make_result(env, "", n, 0, (float) ms_since(t0), 0, -3);
        }
        e->kv_tokens.insert(e->kv_tokens.end(), tokens.begin() + i, tokens.begin() + i + len);
    }
    const float prompt_ms = (float) ms_since(t0);
    const int n_reused = (int) common;

    const auto t1 = std::chrono::steady_clock::now();
    std::string out;
    int n_gen = 0;
    char piece[256];
    while (n_gen < max_tokens) {
        llama_token tok = llama_sampler_sample(e->sampler, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        const int len = llama_token_to_piece(e->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        ++n_gen;
        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
        e->kv_tokens.push_back(tok);
    }
    const float gen_ms = (float) ms_since(t1);
    // İstem belirteci sayısı yerine yeniden kullanılmayan (işlenen) belirteç sayısını döndür.
    return make_result(env, out, n - n_reused, n_gen, prompt_ms, gen_ms, 0);
}
