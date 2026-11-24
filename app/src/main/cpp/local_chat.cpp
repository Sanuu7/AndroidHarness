#include <jni.h>
#include "local_request.h"
#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "sampling.h"
#include "speculative.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <climits>
#include <chrono>
#include <cstdio>
#include <cstring>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <memory>
#include <mutex>
#include <stdexcept>
#include <sys/stat.h>
#include <vector>

using json = nlohmann::ordered_json;
namespace {
std::mutex model_mutex;
std::unique_ptr<llama_model, decltype(&llama_model_free)> cached_model(nullptr, llama_model_free);
std::unique_ptr<llama_context, decltype(&llama_free)> cached_context(nullptr, llama_free);
std::vector<llama_token> cached_tokens;
std::string cached_session;
int cached_context_size = 0, cached_threads = 0, cached_prompt_threads = 0;
std::string cached_path;
bool cached_gpu = false;
ino_t cached_inode = 0;
off_t cached_size = 0;
timespec cached_mtime{};
void clear_model() {
    cached_context.reset(); cached_tokens.clear(); cached_session.clear();
    cached_model.reset();
    cached_path.clear();
}
std::string jbytes(JNIEnv * env, jbyteArray data) {
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
bool aborted(void * p) { return static_cast<LocalRequest *>(p)->cancelled.load(); }
bool progress(float, void * p) { return !aborted(p); }
void initialize_backend() {
    static std::once_flag initialized;
    std::call_once(initialized, [] {
#ifdef __ANDROID__
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN || std::strstr(text, "offload") || std::strstr(text, "Vulkan"))
                __android_log_write(ANDROID_LOG_INFO, "HarnessGGUF", text);
            else std::fputs(text, stderr);
        }, nullptr);
#endif
        llama_backend_init();
    });
}
void load_model(const std::string & path, bool gpu, LocalRequest * request) {
    if (aborted(request)) throw std::runtime_error("Local generation stopped.");
    auto mp = llama_model_default_params();
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    if (gpu) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto dev = ggml_backend_dev_get(i);
            if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU || ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_IGPU) { devices[0] = dev; break; }
        }
        if (!devices[0]) throw std::runtime_error("No compatible Vulkan GPU is available. Choose CPU in Local models.");
    }
    mp.devices = devices;
    mp.n_gpu_layers = gpu ? 999 : 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.progress_callback = progress;
    mp.progress_callback_user_data = request;
    struct stat info{};
    if (stat(path.c_str(), &info) != 0) throw std::runtime_error("Model file is unavailable.");
    const bool same = cached_model && cached_path == path && cached_gpu == gpu &&
        cached_inode == info.st_ino && cached_size == info.st_size &&
        cached_mtime.tv_sec == info.st_mtim.tv_sec && cached_mtime.tv_nsec == info.st_mtim.tv_nsec;
    if (!same) {
        clear_model();
        cached_model.reset(llama_model_load_from_file(path.c_str(), mp));
        if (cached_model) {
            cached_path = path; cached_gpu = gpu; cached_inode = info.st_ino;
            cached_size = info.st_size; cached_mtime = info.st_mtim;
        }
    }
    if (aborted(request)) { clear_model(); throw std::runtime_error("Local generation stopped."); }
    if (!cached_model) throw std::runtime_error("Could not load this model. The architecture may be unsupported or memory allocation failed.");
}
bool complete_utf8(const std::string & s) {
    if (s.empty()) return true;
    size_t start = s.size() - 1;
    while (start > 0 && (uint8_t(s[start]) & 0xc0) == 0x80) --start;
    uint8_t c = s[start];
    size_t need = c < 0x80 ? 1 : c < 0xe0 ? 2 : c < 0xf0 ? 3 : 4;
    return s.size() - start >= need;
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_releaseCachedModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(model_mutex);
    clear_model();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidharness_app_local_LocalNative_isModelCached(JNIEnv * env, jobject, jbyteArray path) {
    std::lock_guard<std::mutex> lock(model_mutex);
    return cached_model && cached_path == jbytes(env, path);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_androidharness_app_local_LocalNative_cachedModelPath(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(model_mutex);
    return env->NewStringUTF(cached_path.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_loadModel(JNIEnv * env, jobject, jlong handle, jbyteArray path, jboolean gpu) {
    try {
        std::lock_guard<std::mutex> lock(model_mutex);
        initialize_backend();
        load_model(jbytes(env, path), bool(gpu), reinterpret_cast<LocalRequest *>(handle));
    } catch (const std::exception & e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_androidharness_app_local_LocalNative_benchmark(JNIEnv * env, jobject, jlong handle, jbyteArray path,
                                                        jboolean gpu, jint threads, jint promptThreads, jobject callback, jbyteArray promptBytes) {
    try {
        std::lock_guard<std::mutex> lock(model_mutex);
        auto * request = reinterpret_cast<LocalRequest *>(handle);
        auto callbackClass = env->GetObjectClass(callback);
        auto progressMethod = env->GetMethodID(callbackClass, "onProgress", "(Ljava/lang/String;ID)Z");
        auto outputMethod = env->GetMethodID(callbackClass, "onOutput", "([B)Z");
        if (!progressMethod || !outputMethod) return nullptr;
        auto report = [&](const char * phase, int tokens, double seconds) {
            if (aborted(request)) throw std::runtime_error("Benchmark stopped.");
            auto name = env->NewStringUTF(phase);
            bool keep = env->CallBooleanMethod(callback, progressMethod, name, tokens, seconds);
            env->DeleteLocalRef(name);
            if (env->ExceptionCheck() || !keep) throw std::runtime_error("Benchmark stopped.");
        };
        if (threads < 1 || promptThreads < 1) throw std::runtime_error("Thread counts must be positive.");
        initialize_backend();
        load_model(jbytes(env, path), bool(gpu), request);
        // A benchmark must not reuse or alter a conversation's KV cache.
        cached_context.reset(); cached_tokens.clear(); cached_session.clear();
        auto cp = llama_context_default_params();
        cp.n_ctx = 512; cp.n_batch = 128; cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = promptThreads;
        cp.abort_callback = aborted; cp.abort_callback_data = request;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(cached_model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("Could not allocate the benchmark context.");
        const auto * vocab = llama_model_get_vocab(cached_model.get());
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(json::array({{{"role", "user"}, {"content", jbytes(env, promptBytes)}}}).dump()));
        inputs.enable_thinking = false;
        auto templates = common_chat_templates_init(cached_model.get(), "");
        const auto formatted = common_chat_templates_apply(templates.get(), inputs).prompt.str();
        int count = -llama_tokenize(vocab, formatted.data(), formatted.size(), nullptr, 0, true, true);
        if (count < 1 || count + 32 > 512) throw std::runtime_error("The benchmark prompt does not fit the test context.");
        std::vector<llama_token> prompt(count);
        if (llama_tokenize(vocab, formatted.data(), formatted.size(), prompt.data(), count, true, true) != count)
            throw std::runtime_error("Could not tokenize the benchmark prompt.");
        report("prompt", 0, 0);
        auto start = std::chrono::steady_clock::now();
        for (int offset = 0; offset < int(prompt.size()); offset += 128) {
            auto batch = llama_batch_get_one(prompt.data() + offset, std::min(128, int(prompt.size()) - offset));
            if (llama_decode(ctx.get(), batch) != 0 || aborted(request)) throw std::runtime_error("Benchmark stopped or prompt processing failed.");
        }
        llama_synchronize(ctx.get());
        auto promptEnd = std::chrono::steady_clock::now();
        report("prompt", int(prompt.size()), std::chrono::duration<double>(promptEnd - start).count());
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        int outputTokens = 0;
        std::string output;
        for (int i = 0; i < 32; ++i) {
            if (aborted(request)) throw std::runtime_error("Benchmark stopped.");
            auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            llama_sampler_accept(sampler.get(), token);
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true); }
            if (size < 0) throw std::runtime_error("Could not decode the benchmark answer.");
            output.append(piece.data(), size);
            ++outputTokens;
            auto next = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx.get(), next) != 0) throw std::runtime_error("Benchmark stopped or generation failed.");
            llama_synchronize(ctx.get());
            if (complete_utf8(output)) {
                auto data = env->NewByteArray(output.size());
                env->SetByteArrayRegion(data, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
                bool keep = env->CallBooleanMethod(callback, outputMethod, data);
                env->DeleteLocalRef(data);
                if (env->ExceptionCheck() || !keep) throw std::runtime_error("Benchmark stopped.");
            }
            report("generation", outputTokens, std::chrono::duration<double>(std::chrono::steady_clock::now() - promptEnd).count());
        }
        if (outputTokens == 0) throw std::runtime_error("The model returned an empty answer for this test.");
        while (!complete_utf8(output)) output.pop_back();
        llama_synchronize(ctx.get());
        auto end = std::chrono::steady_clock::now();
        const double promptSeconds = std::chrono::duration<double>(promptEnd - start).count();
        const double generationSeconds = std::chrono::duration<double>(end - promptEnd).count();
        if (promptSeconds <= 0 || generationSeconds <= 0) throw std::runtime_error("Benchmark timing was unavailable.");
        const auto result = json({{"prompt_tps", prompt.size() / promptSeconds}, {"generation_tps", outputTokens / generationSeconds}, {"text", output}, {"input_tokens", prompt.size()}, {"output_tokens", outputTokens}}).dump();
        auto bytes = env->NewByteArray(result.size());
        env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return bytes;
    } catch (const std::exception & e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_androidharness_app_local_LocalNative_generateChat(
    JNIEnv * env, jobject, jlong handle, jbyteArray path, jbyteArray projector,
    jbyteArray requestBytes, jobjectArray imageBytes, jint requestedContext, jint inputLimit,
    jint outputLimit, jint threads, jint promptThreads, jboolean useGpu, jbyteArray cacheKey, jobject callback) {
    try {
        std::lock_guard<std::mutex> lock(model_mutex);
        auto * request = reinterpret_cast<LocalRequest *>(handle);
        initialize_backend();
        auto cbClass = env->GetObjectClass(callback);
        auto eventMethod = env->GetMethodID(cbClass, "onEvent", "([B)Z");
        auto warningMethod = env->GetMethodID(cbClass, "onWarning", "([B)Z");
        if (!eventMethod || !warningMethod) return nullptr;
        auto invoke = [&](jmethodID method, const std::string & value) {
            if (aborted(request)) throw std::runtime_error("Local generation stopped.");
            auto data = env->NewByteArray(value.size());
            env->SetByteArrayRegion(data, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
            bool keep = env->CallBooleanMethod(callback, method, data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck() || !keep) throw std::runtime_error("Local generation stopped.");
        };
        auto stage = [&](const char * value) { invoke(eventMethod, json({{"status", value}}).dump()); };
        if (requestedContext < 1 || inputLimit < 1 || outputLimit < 1 || threads < 1 || promptThreads < 1)
            throw std::runtime_error("Context, token budgets and threads must be positive.");
        stage("Loading model");
        stage(useGpu ? "Loading model on GPU (Vulkan)" : "Loading model on CPU");
        load_model(jbytes(env, path), bool(useGpu), request);
        auto & model = cached_model;
        auto raw = json::parse(jbytes(env, requestBytes));
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(raw.at("messages").dump()));
        inputs.tools = common_chat_tools_parse_oaicompat(common_json::parse(raw.at("tools").dump()));
        inputs.enable_thinking = raw.value("enable_thinking", false);
        inputs.reasoning_format = raw.value("thinking_model", "AUTO") == "THINKING" ? COMMON_REASONING_FORMAT_DEEPSEEK : COMMON_REASONING_FORMAT_AUTO;
        inputs.parallel_tool_calls = false;
        auto tmpls = common_chat_templates_init(model.get(), "");
        auto chat = common_chat_templates_apply(tmpls.get(), inputs);
        common_chat_parser_params parser(chat);
        parser.parser = chat.parser;
        const auto * vocab = llama_model_get_vocab(model.get());
        parser.generation_prompt = common_chat_input_tokenize(vocab, chat.generation_prompt);
        // Content-only template fallbacks do not know that a reasoning opening
        // tag was already included in the assistant generation prefix.
        if (chat.format == COMMON_CHAT_FORMAT_CONTENT_ONLY) {
            const auto prefix = chat.generation_prompt.empty() ? chat.prompt.str() : chat.generation_prompt;
            const auto end = prefix.find_last_not_of(" \t\r\n");
            if (end != std::string::npos && end >= 6 && prefix.compare(end - 6, 7, "<think>") == 0)
                invoke(eventMethod, json({{"thinking_prefill", true}}).dump());
        }

        const auto prompt = chat.prompt.str();
        std::unique_ptr<mtmd_context, decltype(&mtmd_free)> vision(nullptr, mtmd_free);
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(nullptr, mtmd_input_chunks_free);
        std::vector<std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>> bitmaps;
        std::vector<const mtmd_bitmap *> bitmapPtrs;
        std::vector<llama_token> tokens;
        int64_t promptCount = 0, promptPositions = 0;
        stage("Processing conversation");
        if (env->GetArrayLength(imageBytes) > 0) {
            const auto projectorPath = jbytes(env, projector);
            if (projectorPath.empty()) throw std::runtime_error("Add this vision model's matching projector in Local models before sending images.");
            auto vp = mtmd_context_params_default();
            vp.use_gpu = false;
            vp.n_threads = threads;
            vp.warmup = false;
            vp.progress_callback = progress;
            vp.progress_callback_user_data = request;
            vision.reset(mtmd_init_from_file(projectorPath.c_str(), model.get(), vp));
            if (!vision || !mtmd_support_vision(vision.get())) throw std::runtime_error("The projector is incompatible with this model or does not support images.");
            for (int i = 0; i < env->GetArrayLength(imageBytes); ++i) {
                auto data = static_cast<jbyteArray>(env->GetObjectArrayElement(imageBytes, i));
                auto image = jbytes(env, data);
                env->DeleteLocalRef(data);
                auto wrapper = mtmd_helper_bitmap_init_from_buf(vision.get(), reinterpret_cast<const unsigned char *>(image.data()),
                    image.size(), false, mtmd_helper_init_opt_default());
                if (!wrapper.bitmap) throw std::runtime_error("Could not decode an attached image.");
                bitmaps.emplace_back(wrapper.bitmap, mtmd_bitmap_free);
                bitmapPtrs.push_back(wrapper.bitmap);
            }
            chunks.reset(mtmd_input_chunks_init());
            mtmd_input_text text{prompt.data(), prompt.size(), true, true};
            if (mtmd_tokenize(vision.get(), chunks.get(), &text, bitmapPtrs.data(), bitmapPtrs.size()) != 0)
                throw std::runtime_error("Could not process images with this model's chat template.");
            promptCount = mtmd_helper_get_n_tokens(chunks.get());
            promptPositions = mtmd_helper_get_n_pos(chunks.get());
        } else {
            int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
            if (count <= 0) throw std::runtime_error("Could not tokenize chat.");
            tokens.resize(count);
            if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
                throw std::runtime_error("Could not tokenize chat.");
            promptCount = promptPositions = count;
        }
        const int64_t required = std::max<int64_t>(promptCount, promptPositions) + outputLimit;
        if (required >= INT_MAX) throw std::runtime_error("The requested context exceeds the engine's addressable token range.");
        int contextSize = std::max<int64_t>(requestedContext, required);
        if (promptCount > inputLimit || required > requestedContext) {
            invoke(warningMethod, "This conversation exceeds your saved token budget. Continue with " + std::to_string(contextSize) +
                " context tokens? This uses more RAM and may fail or cause Android to close the app.");
        }
        if (contextSize > llama_model_n_ctx_train(model.get())) {
            invoke(warningMethod, "This model was trained for " + std::to_string(llama_model_n_ctx_train(model.get())) +
                " context tokens. Continue with " + std::to_string(contextSize) + "? Answers may degrade or generation may fail.");
        }
        auto cp = llama_context_default_params();
        cp.n_ctx = contextSize;
        cp.offload_kqv = useGpu;
        cp.op_offload = useGpu;
        cp.n_batch = 256; cp.n_ubatch = 128;
        if (vision) {
            // Non-causal image chunks must fit in a single microbatch.
            for (size_t i = 0; i < mtmd_input_chunks_size(chunks.get()); ++i) {
                auto * chunk = mtmd_input_chunks_get(chunks.get(), i);
                if (mtmd_decode_use_non_causal(vision.get(), chunk)) {
                    cp.n_ubatch = std::max<uint32_t>(cp.n_ubatch, mtmd_input_chunk_get_n_tokens(chunk));
                }
            }
            cp.n_batch = std::max(cp.n_batch, cp.n_ubatch);
        }
        cp.n_threads = threads; cp.n_threads_batch = promptThreads;
        cp.abort_callback = aborted; cp.abort_callback_data = request;
        const auto session = jbytes(env, cacheKey);
        const bool canCache = !session.empty() && !vision;
        bool reusable = canCache && cached_context && cached_session == session &&
            cached_context_size == contextSize && cached_threads == threads && cached_prompt_threads == promptThreads;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(nullptr, llama_free);
        if (reusable) ctx = std::move(cached_context);
        else { cached_context.reset(); cached_tokens.clear(); cached_session.clear(); ctx.reset(llama_init_from_model(model.get(), cp)); }
        if (!ctx) throw std::runtime_error("The engine could not allocate this context. Try a smaller context or model.");
        llama_set_abort_callback(ctx.get(), aborted, request);
        size_t reused = 0;
        if (reusable) {
            const auto count = std::min(cached_tokens.size(), tokens.size());
            while (reused < count && cached_tokens[reused] == tokens[reused]) ++reused;
            // Re-evaluate at least one token to obtain current logits.
            if (reused == tokens.size() && reused > 0) --reused;
            if (!llama_memory_seq_rm(llama_get_memory(ctx.get()), 0, reused, -1)) {
                llama_memory_clear(llama_get_memory(ctx.get()), true); reused = 0;
            }
        }
        cached_tokens.clear(); cached_session.clear();
        llama_pos nPast = 0;
        if (vision) {
            if (mtmd_helper_eval_chunks(vision.get(), ctx.get(), chunks.get(), 0, 0, cp.n_batch, true, &nPast) != 0)
                throw std::runtime_error("Image or prompt evaluation failed or was stopped.");
        } else {
            for (int offset = int(reused); offset < int(tokens.size()); offset += 256) {
                if (aborted(request)) throw std::runtime_error("Local generation stopped.");
                auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, int(tokens.size()) - offset));
                if (llama_decode(ctx.get(), batch) != 0) throw std::runtime_error("Prompt evaluation failed or was stopped.");
            }
            nPast = tokens.size();
        }
        common_params_sampling sp;
        sp.temp = 0;
        sp.grammar.grammar = chat.grammar;
        sp.grammar.type = chat.grammar.empty() ? COMMON_GRAMMAR_TYPE_NONE : COMMON_GRAMMAR_TYPE_TOOL_CALLS;
        sp.grammar_lazy = chat.grammar_lazy;
        sp.grammar_triggers = chat.grammar_triggers;
        for (const auto & token : chat.preserved_tokens) {
            int id = -1;
            if (llama_tokenize(vocab, token.data(), token.size(), &id, 1, false, true) == 1) sp.preserved_tokens.insert(id);
        }
        std::unique_ptr<common_sampler, decltype(&common_sampler_free)> sampler(common_sampler_init(model.get(), sp), common_sampler_free);
        if (!sampler) throw std::runtime_error("Could not initialize tool-call sampling.");
        common_chat_input output;
        common_chat_msg previous;
        auto publish = [&](bool partial) {
            auto parsed = common_chat_parse(output, partial, parser);
            json event = json::object();
            // Text/reasoning are streamed; tool calls are emitted only after complete validation.
            if (parsed.content.size() >= previous.content.size() && parsed.content.compare(0, previous.content.size(), previous.content) == 0)
                event["text"] = parsed.content.substr(previous.content.size());
            if (parsed.reasoning_content.size() >= previous.reasoning_content.size() && parsed.reasoning_content.compare(0, previous.reasoning_content.size(), previous.reasoning_content) == 0)
                event["thinking"] = parsed.reasoning_content.substr(previous.reasoning_content.size());
            if (!event.empty()) invoke(eventMethod, event.dump(-1, ' ', false, json::error_handler_t::replace));
            previous = parsed;
            return parsed;
        };
        // Draftless speculative decoding: guesses come from repeated n-grams in
        // the conversation and the model verifies them in one batch. Sampling is
        // greedy, so the output is identical to plain decoding. Image chats and
        // recurrent or hybrid models cannot roll back rejected guesses, so they
        // always decode one token at a time.
        const auto speculativeMode = raw.value("speculative", std::string("OFF"));
        common_params_speculative specParams;
        specParams.ngram_simple.size_n = 8; specParams.ngram_simple.size_m = 16;
        specParams.ngram_mod.n_match = 16; specParams.ngram_mod.n_max = 16; specParams.ngram_mod.n_min = 4;
        if (speculativeMode == "NGRAM_SIMPLE") specParams.types = {COMMON_SPECULATIVE_TYPE_NGRAM_SIMPLE};
        else if (speculativeMode == "NGRAM_MOD") specParams.types = {COMMON_SPECULATIVE_TYPE_NGRAM_MOD};
        const bool speculate = specParams.types.front() != COMMON_SPECULATIVE_TYPE_NONE && !vision &&
            !llama_model_is_recurrent(model.get()) && !llama_model_is_hybrid(model.get());
        common_speculative_ptr spec(speculate ? common_speculative_init(specParams, 1) : nullptr);
        constexpr int maxDraft = 16;
        llama_tokens history, draft;
        if (spec) { history = tokens; common_speculative_begin(spec.get(), 0, history); }
        int drafted = 0, accepted = 0;

        stage("Generating");
        int generated = 0;
        bool ended = false;
        // Returns true once generation must end (end token, stop marker or output limit).
        auto emit = [&](llama_token token) {
            if (llama_vocab_is_eog(vocab, token)) { ended = true; return true; }
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true); }
            if (size < 0) throw std::runtime_error("Could not decode output token.");
            output.append(std::string(piece.data(), size), token);
            ++generated;
            if (complete_utf8(output.text)) publish(true);
            for (const auto & marker : chat.additional_stops) if (!marker.empty() && output.text.size() >= marker.size() &&
                output.text.compare(output.text.size() - marker.size(), marker.size(), marker) == 0) { ended = true; return true; }
            return generated >= outputLimit;
        };
        // Decodes the last sampled token followed by any draft, all with logits.
        // Explicit positions also work for image models using M-RoPE.
        auto decode = [&](llama_token token) {
            const int count = 1 + int(draft.size());
            auto batch = llama_batch_init(count, 0, 1);
            batch.n_tokens = count;
            for (int i = 0; i < count; ++i) {
                batch.token[i] = i == 0 ? token : draft[i - 1]; batch.pos[i] = nPast + i;
                batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0; batch.logits[i] = true;
            }
            int result = llama_decode(ctx.get(), batch);
            llama_batch_free(batch);
            if (result != 0) throw std::runtime_error("Generation failed or was stopped.");
        };
        if (aborted(request)) throw std::runtime_error("Local generation stopped.");
        auto token = common_sampler_sample(sampler.get(), ctx.get(), -1);
        common_sampler_accept(sampler.get(), token, true);
        while (!emit(token)) {
            if (aborted(request)) throw std::runtime_error("Local generation stopped.");
            draft.clear();
            if (spec) {
                const int room = std::min({maxDraft, outputLimit - generated - 1, contextSize - int(nPast) - 2});
                if (room > 0) {
                    auto & dp = common_speculative_get_draft_params(spec.get(), 0);
                    dp.drafting = true; dp.n_max = room; dp.pos0 = nPast; dp.id_last = token;
                    dp.prompt = &history; dp.result = &draft;
                    common_speculative_draft(spec.get());
                    if (int(draft.size()) > room) draft.resize(room);
                }
            }
            decode(token);
            if (spec) history.push_back(token);
            ++nPast;
            if (draft.empty()) {
                token = common_sampler_sample(sampler.get(), ctx.get(), -1);
                common_sampler_accept(sampler.get(), token, true);
                continue;
            }
            // Samples (and accepts) each position until the model disagrees with the draft.
            auto ids = common_sampler_sample_and_accept_n(sampler.get(), ctx.get(), draft);
            const int matched = int(ids.size()) - 1;
            drafted += int(draft.size()); accepted += matched;
            common_speculative_accept(spec.get(), 0, uint16_t(matched));
            // Rejected guesses must leave the KV cache before the next decode.
            if (!llama_memory_seq_rm(llama_get_memory(ctx.get()), 0, nPast + matched, -1))
                throw std::runtime_error("This model cannot use speculative decoding. Turn it off in the model's settings.");
            bool done = false;
            for (int i = 0; i < matched && !done; ++i) {
                done = emit(ids[i]);
                history.push_back(ids[i]); ++nPast;
            }
            if (done) break;
            token = ids.back();
        }
#ifdef __ANDROID__
        if (spec) __android_log_print(ANDROID_LOG_INFO, "HarnessLocal", "speculative %s: %d/%d draft tokens accepted, %d generated",
            speculativeMode.c_str(), accepted, drafted, generated);
#endif
        auto final = publish(false);
        json calls = json::array();
        // A truncated tool call must never become an executable action.
        if (ended) for (const auto & call : final.tool_calls) {
            auto arguments = json::parse(call.arguments);
            if (!arguments.is_object()) throw std::runtime_error("The local model returned invalid tool arguments.");
            bool known = false;
            for (const auto & tool : inputs.tools) if (tool.name == call.name) known = true;
            if (!known) throw std::runtime_error("The local model requested an unknown tool.");
            calls.push_back({{"name", call.name}, {"arguments", arguments}, {"id", call.id}});
        }
        auto result = json({{"input", promptCount}, {"output", generated}, {"ended", ended}, {"calls", calls}, {"backend", useGpu ? "GPU" : "CPU"}, {"reused_prompt_tokens", reused},
            {"speculative", bool(spec)}, {"drafted_tokens", drafted}, {"accepted_tokens", accepted}}).dump();
        if (canCache && !aborted(request)) {
            llama_set_abort_callback(ctx.get(), nullptr, nullptr);
            cached_context = std::move(ctx); cached_tokens = tokens; cached_session = session;
            cached_context_size = contextSize; cached_threads = threads; cached_prompt_threads = promptThreads;
        }
        auto data = env->NewByteArray(result.size());
        env->SetByteArrayRegion(data, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return data;
    } catch (const std::exception & e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidharness_app_local_LocalNative_gpuDevice(JNIEnv * env, jobject) {
    try {
        llama_backend_init();
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto dev = ggml_backend_dev_get(i);
            if ((ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU || ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_IGPU))
                return env->NewStringUTF(ggml_backend_dev_description(dev));
        }
    } catch (const std::exception &) { }
    return env->NewStringUTF("");
}
