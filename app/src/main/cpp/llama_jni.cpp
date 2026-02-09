/**
 * Direct llama.cpp JNI bindings for Android
 * 
 * This bypasses the ARM AiChat wrapper and calls llama.cpp directly.
 * Supports Qwen3 and other architectures that may not be in the ARM whitelist.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <unistd.h>

// llama.cpp headers
#include "llama.h"
#include "common.h"
#include "sampling.h"

#define LOG_TAG "LlamaCppJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global state
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static common_sampler* g_sampler = nullptr;
static std::mutex g_mutex;

// Chat state
static std::vector<llama_token> g_input_tokens;
static std::vector<llama_token> g_output_tokens;
static int g_n_past = 0;
static bool g_is_generating = false;
static std::string g_system_prompt;

// Configuration
static constexpr int DEFAULT_N_CTX = 4096;
static constexpr int DEFAULT_N_BATCH = 512;
static constexpr int DEFAULT_N_THREADS = 4;

static void log_callback(ggml_log_level level, const char* text, void* user_data) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            LOGE("%s", text);
            break;
        case GGML_LOG_LEVEL_WARN:
            LOGW("%s", text);
            break;
        case GGML_LOG_LEVEL_INFO:
            LOGI("%s", text);
            break;
        default:
            LOGD("%s", text);
            break;
    }
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeInit(
    JNIEnv* env, jobject thiz, jstring native_lib_dir) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    const char* lib_dir = env->GetStringUTFChars(native_lib_dir, nullptr);
    LOGI("Initializing llama.cpp from: %s", lib_dir);
    
    // Set log callback
    llama_log_set(log_callback, nullptr);
    
    // Load backends from the native lib directory
    ggml_backend_load_all_from_path(lib_dir);
    
    // Initialize backend
    llama_backend_init();
    
    env->ReleaseStringUTFChars(native_lib_dir, lib_dir);
    
    LOGI("llama.cpp backend initialized");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeLoadModel(
    JNIEnv* env, jobject thiz, jstring model_path, jint n_ctx, jint n_threads) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    // Unload previous model if any
    if (g_model != nullptr) {
        if (g_sampler) {
            common_sampler_free(g_sampler);
            g_sampler = nullptr;
        }
        if (g_ctx) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s", path);
    
    // Model parameters
    llama_model_params model_params = llama_model_default_params();
    
    // Load model
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);
    
    if (!g_model) {
        LOGE("Failed to load model");
        return -1;
    }
    
    // Context parameters
    int actual_n_ctx = (n_ctx > 0) ? n_ctx : DEFAULT_N_CTX;
    int actual_n_threads = (n_threads > 0) ? n_threads : 
        std::min(DEFAULT_N_THREADS, (int)sysconf(_SC_NPROCESSORS_ONLN));
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = actual_n_ctx;
    ctx_params.n_batch = DEFAULT_N_BATCH;
    ctx_params.n_ubatch = DEFAULT_N_BATCH;
    ctx_params.n_threads = actual_n_threads;
    ctx_params.n_threads_batch = actual_n_threads;
    
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return -2;
    }
    
    // Initialize sampler
    common_params_sampling sparams;
    sparams.temp = 0.7f;
    sparams.top_p = 0.9f;
    sparams.top_k = 40;
    sparams.penalty_repeat = 1.1f;
    
    g_sampler = common_sampler_init(g_model, sparams);
    if (!g_sampler) {
        LOGE("Failed to create sampler");
        llama_free(g_ctx);
        g_ctx = nullptr;
        llama_model_free(g_model);
        g_model = nullptr;
        return -3;
    }
    
    // Reset state
    g_input_tokens.clear();
    g_output_tokens.clear();
    g_n_past = 0;
    g_is_generating = false;
    
    char model_desc[256];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));
    LOGI("Model loaded: %s", model_desc);
    LOGI("Context size: %d, Threads: %d", actual_n_ctx, actual_n_threads);
    
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeSetSystemPrompt(
    JNIEnv* env, jobject thiz, jstring prompt) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return -1;
    }
    
    const char* prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    g_system_prompt = prompt_cstr;
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    LOGI("System prompt set (%zu chars)", g_system_prompt.length());
    
    // Tokenize and process system prompt
    g_input_tokens = common_tokenize(g_ctx, g_system_prompt, true, true);
    
    // Clear past context
    llama_memory_clear(llama_get_memory(g_ctx), false);
    g_n_past = 0;
    
    // Process system prompt tokens
    llama_batch batch = llama_batch_init(g_input_tokens.size(), 0, 1);
    for (size_t i = 0; i < g_input_tokens.size(); i++) {
        common_batch_add(batch, g_input_tokens[i], g_n_past + i, {0}, false);
    }
    
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to process system prompt");
        llama_batch_free(batch);
        return -2;
    }
    
    g_n_past += g_input_tokens.size();
    llama_batch_free(batch);
    
    LOGI("System prompt processed (%zu tokens)", g_input_tokens.size());
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeStartGeneration(
    JNIEnv* env, jobject thiz, jstring prompt, jint max_tokens) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return -1;
    }
    
    const char* prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string user_prompt(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    LOGI("Starting generation for prompt (%zu chars)", user_prompt.length());
    
    // Format with chat template if available
    std::string formatted_prompt = "<|user|>\n" + user_prompt + "\n<|assistant|>\n";
    
    // Tokenize user prompt
    auto user_tokens = common_tokenize(g_ctx, formatted_prompt, true, true);
    
    // Process user prompt tokens
    llama_batch batch = llama_batch_init(user_tokens.size(), 0, 1);
    for (size_t i = 0; i < user_tokens.size(); i++) {
        bool is_last = (i == user_tokens.size() - 1);
        common_batch_add(batch, user_tokens[i], g_n_past + i, {0}, is_last);
    }
    
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to process user prompt");
        llama_batch_free(batch);
        return -2;
    }
    
    g_n_past += user_tokens.size();
    llama_batch_free(batch);
    
    g_is_generating = true;
    g_output_tokens.clear();
    
    LOGI("Ready to generate (user tokens: %zu, n_past: %d)", user_tokens.size(), g_n_past);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeGetNextToken(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (!g_model || !g_ctx || !g_sampler) {
        return nullptr;
    }
    
    if (!g_is_generating) {
        return nullptr;
    }
    
    // Sample next token
    llama_token new_token = common_sampler_sample(g_sampler, g_ctx, -1);
    common_sampler_accept(g_sampler, new_token, true);
    
    // Check for end of generation
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token)) {
        g_is_generating = false;
        LOGI("Generation complete (EOG token)");
        return nullptr;
    }
    
    // Decode the new token
    llama_batch batch = llama_batch_init(1, 0, 1);
    common_batch_add(batch, new_token, g_n_past, {0}, true);
    
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to decode token");
        llama_batch_free(batch);
        g_is_generating = false;
        return nullptr;
    }
    
    g_n_past++;
    llama_batch_free(batch);
    
    g_output_tokens.push_back(new_token);
    
    // Convert token to text
    std::string token_text = common_token_to_piece(g_ctx, new_token);
    
    return env->NewStringUTF(token_text.c_str());
}

JNIEXPORT void JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeStopGeneration(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    g_is_generating = false;
    LOGI("Generation stopped by request");
}

JNIEXPORT void JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeUnloadModel(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    g_is_generating = false;
    
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    g_input_tokens.clear();
    g_output_tokens.clear();
    g_n_past = 0;
    g_system_prompt.clear();
    
    LOGI("Model unloaded");
}

JNIEXPORT void JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeShutdown(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    // Unload model first
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    llama_backend_free();
    LOGI("llama.cpp shutdown complete");
}

JNIEXPORT jstring JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeGetSystemInfo(
    JNIEnv* env, jobject thiz) {
    
    const char* info = llama_print_system_info();
    return env->NewStringUTF(info);
}

JNIEXPORT jboolean JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeIsModelLoaded(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr);
}

JNIEXPORT jboolean JNICALL
Java_com_llamafarm_atmosphere_inference_LlamaCppEngine_00024Companion_nativeIsGenerating(
    JNIEnv* env, jobject thiz) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_is_generating;
}

} // extern "C"
