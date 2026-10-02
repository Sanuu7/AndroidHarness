#include <jni.h>
#include "local_request.h"
#include "llama.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <climits>
#include <memory>
#include <mutex>
#include <stdexcept>

using json = nlohmann::ordered_json;
namespace {
std::string jbytes(JNIEnv * env, jbyteArray data) {
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
bool aborted(void * p) { return static_cast<LocalRequest *>(p)->cancelled.load(); }
bool progress(float, void * p) { return !aborted(p); }
bool complete_utf8(const std::string & s) {
    if (s.empty()) return true;
    size_t start = s.size() - 1;
    while (start > 0 && (uint8_t(s[start]) & 0xc0) == 0x80) --start;
    uint8_t c = s[start];
    size_t need = c < 0x80 ? 1 : c < 0xe0 ? 2 : c < 0xf0 ? 3 : 4;
    return s.size() - start >= need;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_androidharness_app_local_LocalNative_generateChat(
    JNIEnv * env, jobject, jlong handle, jbyteArray path, jbyteArray projector,
    jbyteArray requestBytes, jobjectArray imageBytes, jint requestedContext, jint inputLimit,
    jint outputLimit, jint threads, jobject callback) {
    try {
        auto * request = reinterpret_cast<LocalRequest *>(handle);
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_backend_init(); });
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
        if (requestedContext < 1 || inputLimit < 1 || outputLimit < 1 || threads < 1)
            throw std::runtime_error("Context, token budgets and threads must be positive.");
        stage("Loading model");
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = progress;
        mp.progress_callback_user_data = request;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(jbytes(env, path).c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error("Could not load this model. The architecture may be unsupported or memory allocation failed.");
        auto raw = json::parse(jbytes(env, requestBytes));
        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(raw.at("messages").dump()));
        inputs.tools = common_chat_tools_parse_oaicompat(common_json::parse(raw.at("tools").dump()));
        inputs.enable_thinking = raw.value("enable_thinking", false);
        inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        inputs.parallel_tool_calls = false;
        auto tmpls = common_chat_templates_init(model.get(), "");
        auto chat = common_chat_templates_apply(tmpls.get(), inputs);
        common_chat_parser_params parser(chat);
        parser.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
        if (!chat.parser.empty()) parser.parser.load(chat.parser);
        const auto * vocab = llama_model_get_vocab(model.get());
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
            mtmd_input_text text{chat.prompt.data(), chat.prompt.size(), true, true};
            if (mtmd_tokenize(vision.get(), chunks.get(), &text, bitmapPtrs.data(), bitmapPtrs.size()) != 0)
                throw std::runtime_error("Could not process images with this model's chat template.");
            promptCount = mtmd_helper_get_n_tokens(chunks.get());
            promptPositions = mtmd_helper_get_n_pos(chunks.get());
        } else {
            int count = -llama_tokenize(vocab, chat.prompt.data(), chat.prompt.size(), nullptr, 0, true, true);
            if (count <= 0) throw std::runtime_error("Could not tokenize chat.");
            tokens.resize(count);
            if (llama_tokenize(vocab, chat.prompt.data(), chat.prompt.size(), tokens.data(), count, true, true) != count)
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
        cp.n_threads = cp.n_threads_batch = threads;
        cp.abort_callback = aborted; cp.abort_callback_data = request;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("The engine could not allocate this context. Try a smaller context or model.");
        llama_pos nPast = 0;
        if (vision) {
            if (mtmd_helper_eval_chunks(vision.get(), ctx.get(), chunks.get(), 0, 0, cp.n_batch, true, &nPast) != 0)
                throw std::runtime_error("Image or prompt evaluation failed or was stopped.");
        } else {
            for (int offset = 0; offset < int(tokens.size()); offset += 256) {
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
        std::string output;
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
        stage("Generating");
        int generated = 0;
        bool ended = false;
        while (generated < outputLimit) {
            if (aborted(request)) throw std::runtime_error("Local generation stopped.");
            auto token = common_sampler_sample(sampler.get(), ctx.get(), -1);
            common_sampler_accept(sampler.get(), token, true);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true); }
            if (size < 0) throw std::runtime_error("Could not decode output token.");
            output.append(piece.data(), size);
            ++generated;
            if (complete_utf8(output)) publish(true);
            bool stop = false;
            for (const auto & marker : chat.additional_stops) if (!marker.empty() && output.size() >= marker.size() &&
                output.compare(output.size() - marker.size(), marker.size(), marker) == 0) stop = true;
            if (stop) { ended = true; break; }
            if (generated < outputLimit) {
                // Explicit positions also work for image models using M-RoPE.
                auto batch = llama_batch_init(1, 0, 1);
                batch.n_tokens = 1; batch.token[0] = token; batch.pos[0] = nPast++;
                batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = true;
                int result = llama_decode(ctx.get(), batch);
                llama_batch_free(batch);
                if (result != 0) throw std::runtime_error("Generation failed or was stopped.");
            }
        }
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
        auto result = json({{"input", promptCount}, {"output", generated}, {"ended", ended}, {"calls", calls}}).dump();
        auto data = env->NewByteArray(result.size());
        env->SetByteArrayRegion(data, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
        return data;
    } catch (const std::exception & e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}
