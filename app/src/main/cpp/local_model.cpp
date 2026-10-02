#include <jni.h>
#include "llama.h"
#include "local_request.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Request = LocalRequest;
std::once_flag initialized;
bool aborted(void * data) { return static_cast<Request *>(data)->cancelled.load(); }
bool progress(float, void * data) { return !aborted(data); }
std::string bytes(JNIEnv * env, jbyteArray value) {
    const auto size = env->GetArrayLength(value);
    std::string result(size, '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
void fail(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidharness_app_local_LocalNative_create(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new Request());
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_cancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Request *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_destroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Request *>(handle);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_androidharness_app_local_LocalNative_generate(
    JNIEnv * env, jobject, jlong handle, jbyteArray pathBytes, jobjectArray roles,
    jobjectArray contents, jint contextSize, jint inputLimit, jint outputLimit,
    jint threads, jobject callback) {
    try {
        auto * request = reinterpret_cast<Request *>(handle);
        std::call_once(initialized, [] { llama_backend_init(); });
        if (aborted(request)) throw std::runtime_error("Local generation stopped.");
        if (contextSize < 1 || inputLimit < 1 || outputLimit < 1 ||
            int64_t(inputLimit) + outputLimit > contextSize || threads < 1)
            throw std::runtime_error("Invalid local model limits.");
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.progress_callback = progress;
        params.progress_callback_user_data = request;
        const auto path = bytes(env, pathBytes);
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path.c_str(), params), llama_model_free);
        if (!model) throw std::runtime_error("Could not load model. Free memory or reinstall the model.");
        if (aborted(request)) throw std::runtime_error("Local generation stopped.");
        const auto count = env->GetArrayLength(roles);
        if (count != env->GetArrayLength(contents)) throw std::runtime_error("Invalid chat messages.");
        std::vector<std::string> roleValues, textValues;
        roleValues.reserve(count);
        textValues.reserve(count);
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            const char * value = env->GetStringUTFChars(role, nullptr);
            roleValues.emplace_back(value);
            env->ReleaseStringUTFChars(role, value);
            env->DeleteLocalRef(role);
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            textValues.push_back(bytes(env, content));
            env->DeleteLocalRef(content);
        }
        std::vector<llama_chat_message> messages;
        for (int i = 0; i < count; ++i) messages.push_back({roleValues[i].c_str(), textValues[i].c_str()});
        const char * chatTemplate = llama_model_chat_template(model.get(), nullptr);
        if (!chatTemplate) throw std::runtime_error("Model has no supported chat template.");
        int size = llama_chat_apply_template(chatTemplate, messages.data(), messages.size(), true, nullptr, 0);
        if (size <= 0) throw std::runtime_error("Unsupported model chat template.");
        std::string prompt(size, '\0');
        const int written = llama_chat_apply_template(chatTemplate, messages.data(), messages.size(), true, prompt.data(), size);
        if (written < 0 || written > size) throw std::runtime_error("Could not format chat.");
        prompt.resize(written);
        const auto * vocab = llama_model_get_vocab(model.get());
        int tokenCount = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (tokenCount <= 0 || tokenCount > inputLimit)
            throw std::runtime_error("Input exceeds the local model input limit. Start a new chat, shorten the message, or increase limits in Local models.");
        std::vector<llama_token> tokens(tokenCount);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true) != tokenCount)
            throw std::runtime_error("Could not tokenize chat.");
        auto contextParams = llama_context_default_params();
        contextParams.n_ctx = contextSize;
        contextParams.n_batch = 256;
        contextParams.n_ubatch = 128;
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        contextParams.abort_callback = aborted;
        contextParams.abort_callback_data = request;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), contextParams), llama_free);
        if (!context) throw std::runtime_error("Not enough memory for this context. Lower it in Local models.");
        for (int offset = 0; offset < tokenCount; offset += 256) {
            if (aborted(request)) throw std::runtime_error("Local generation stopped.");
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, tokenCount - offset));
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Local prompt evaluation stopped or failed.");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        const auto callbackClass = env->GetObjectClass(callback);
        const auto onToken = env->GetMethodID(callbackClass, "onToken", "([B)Z");
        if (!onToken) return nullptr;
        int generated = 0;
        bool ended = false;
        while (generated < outputLimit) {
            if (aborted(request)) throw std::runtime_error("Local generation stopped.");
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            if (length < 0) throw std::runtime_error("Could not decode output token.");
            auto data = env->NewByteArray(length);
            env->SetByteArrayRegion(data, 0, length, reinterpret_cast<const jbyte *>(piece.data()));
            const bool keepGoing = env->CallBooleanMethod(callback, onToken, data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck()) return nullptr;
            if (!keepGoing) throw std::runtime_error("Local generation stopped.");
            ++generated;
            if (generated < outputLimit) {
                auto batch = llama_batch_get_one(&token, 1);
                if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Local generation stopped or failed.");
            }
        }
        jint counts[] = {tokenCount, generated, ended ? 0 : 1};
        auto result = env->NewIntArray(3);
        env->SetIntArrayRegion(result, 0, 3, counts);
        return result;
    } catch (const std::exception & error) {
        fail(env, error.what());
        return nullptr;
    } catch (...) {
        fail(env, "Native inference failed.");
        return nullptr;
    }
}
