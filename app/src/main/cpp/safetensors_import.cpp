#include <jni.h>
#include "local_request.h"
#include "llama.h"
#include "gguf.h"
#include <nlohmann/json.hpp>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <regex>
#include <stdexcept>
#include <vector>

using json = nlohmann::ordered_json;
namespace {
std::string bytes(JNIEnv * env, jbyteArray data) {
    std::string value(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
json read_json(const std::string & path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("Missing model config or tokenizer file.");
    return json::parse(in);
}
void check_cancel(LocalRequest * request) {
    if (request->cancelled.load()) throw std::runtime_error("Model conversion cancelled.");
}
std::string tensor_name(const std::string & name) {
    static const std::map<std::string, std::string> fixed = {
        {"model.embed_tokens.weight", "token_embd.weight"}, {"model.norm.weight", "output_norm.weight"},
        {"lm_head.weight", "output.weight"}};
    if (auto it = fixed.find(name); it != fixed.end()) return it->second;
    std::smatch match;
    if (std::regex_match(name, match, std::regex("model\\.layers\\.([0-9]+)\\.(.+)"))) {
        static const std::map<std::string, std::string> layers = {
            {"self_attn.q_proj.weight", "attn_q.weight"}, {"self_attn.k_proj.weight", "attn_k.weight"},
            {"self_attn.v_proj.weight", "attn_v.weight"}, {"self_attn.o_proj.weight", "attn_output.weight"},
            {"self_attn.q_proj.bias", "attn_q.bias"}, {"self_attn.k_proj.bias", "attn_k.bias"},
            {"self_attn.v_proj.bias", "attn_v.bias"}, {"self_attn.q_norm.weight", "attn_q_norm.weight"},
            {"self_attn.k_norm.weight", "attn_k_norm.weight"}, {"input_layernorm.weight", "attn_norm.weight"},
            {"post_attention_layernorm.weight", "ffn_norm.weight"}, {"mlp.gate_proj.weight", "ffn_gate.weight"},
            {"mlp.up_proj.weight", "ffn_up.weight"}, {"mlp.down_proj.weight", "ffn_down.weight"}};
        if (auto it = layers.find(match[2]); it != layers.end()) return "blk." + match[1].str() + "." + it->second;
    }
    if (name.find("rotary_emb.inv_freq") != std::string::npos) return "";
    throw std::runtime_error("This model uses an unsupported tensor layout: " + name + ". Use a preconverted GGUF for this architecture.");
}
struct Tensor {
    std::string file, name, dtype;
    uint64_t offset = 0, length = 0;
    std::vector<int64_t> shape;
    ggml_tensor * tensor = nullptr;
};
void checked_write(FILE * file, const void * data, size_t size) {
    if (size && fwrite(data, 1, size, file) != size) throw std::runtime_error("Not enough storage to convert the model.");
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_androidharness_app_local_LocalNative_convertSafetensors(JNIEnv * env, jobject, jlong handle,
    jbyteArray directoryBytes, jbyteArray outputBytes, jint threads) {
    std::string intermediate;
    try {
        auto * request = reinterpret_cast<LocalRequest *>(handle);
        const auto directory = bytes(env, directoryBytes);
        const auto output = bytes(env, outputBytes);
        intermediate = output + ".unquantized";
        const auto config = read_json(directory + "/config.json");
        const std::string architecture = config.value("model_type", "");
        if (architecture != "qwen2" && architecture != "qwen3" && architecture != "llama")
            throw std::runtime_error("On-device safetensors conversion currently supports dense Qwen 2/2.5, Qwen 3 and Llama BPE models. Use a preconverted GGUF for this architecture.");
        if (config.contains("quantization_config") || config.contains("num_experts") || config.contains("num_local_experts"))
            throw std::runtime_error("These weights require a different conversion layout. Use a preconverted GGUF.");
        const auto tokenizer = read_json(directory + "/tokenizer.json");
        if (tokenizer.at("model").value("type", "") != "BPE")
            throw std::runtime_error("This import requires a BPE tokenizer. Use a preconverted GGUF for SentencePiece models.");
        auto tokenizerConfig = std::filesystem::exists(directory + "/tokenizer_config.json") ? read_json(directory + "/tokenizer_config.json") : json::object();
        auto generation = std::filesystem::exists(directory + "/generation_config.json") ? read_json(directory + "/generation_config.json") : config;
        std::unique_ptr<gguf_context, decltype(&gguf_free)> gguf(gguf_init_empty(), gguf_free);
        ggml_init_params init{16 * 1024 * 1024, nullptr, true};
        std::unique_ptr<ggml_context, decltype(&ggml_free)> tensors(ggml_init(init), ggml_free);
        if (!tensors) throw std::runtime_error("Could not allocate conversion metadata.");
        gguf_set_val_str(gguf.get(), "general.architecture", architecture.c_str());
        gguf_set_val_str(gguf.get(), "general.name", config.value("_name_or_path", "Imported model").c_str());
        gguf_set_val_u32(gguf.get(), "general.file_type", LLAMA_FTYPE_MOSTLY_F16);
        gguf_set_val_u32(gguf.get(), "general.quantization_version", GGML_QNT_VERSION);
        const int heads = config.at("num_attention_heads").get<int>();
        const int kvHeads = config.value("num_key_value_heads", heads);
        const int hidden = config.at("hidden_size").get<int>();
        if (heads <= 0 || kvHeads <= 0 || hidden <= 0) throw std::runtime_error("Invalid attention configuration.");
        const int headDim = config.value("head_dim", hidden / heads);
        if (headDim <= 0 || headDim % 2) throw std::runtime_error("Invalid attention head dimensions.");
        auto set_u32 = [&](const char * name, uint32_t value) { gguf_set_val_u32(gguf.get(), (architecture + "." + name).c_str(), value); };
        auto set_float = [&](const char * name, float value) { gguf_set_val_f32(gguf.get(), (architecture + "." + name).c_str(), value); };
        set_u32("context_length", config.at("max_position_embeddings").get<uint32_t>());
        set_u32("embedding_length", hidden);
        set_u32("block_count", config.at("num_hidden_layers").get<uint32_t>());
        set_u32("feed_forward_length", config.at("intermediate_size").get<uint32_t>());
        set_u32("attention.head_count", heads); set_u32("attention.head_count_kv", kvHeads);
        set_u32("attention.key_length", headDim); set_u32("attention.value_length", headDim);
        set_u32("rope.dimension_count", headDim);
        auto rope = config.value("rope_parameters", config.value("rope_scaling", json::object()));
        if (rope.is_null()) rope = json::object();
        const float ropeBase = rope.value("rope_theta", config.value("rope_theta", 10000.0f));
        set_float("rope.freq_base", ropeBase);
        set_float("attention.layer_norm_rms_epsilon", config.value("rms_norm_eps", 1e-6f));
        set_u32("vocab_size", config.at("vocab_size").get<uint32_t>());
        auto scaling = rope.value("rope_type", rope.value("type", "default"));
        if (scaling != "default" && scaling != "llama3") {
            if (scaling != "linear" && scaling != "yarn") throw std::runtime_error("This model requires an unsupported RoPE conversion. Use a preconverted GGUF.");
            gguf_set_val_str(gguf.get(), (architecture + ".rope.scaling.type").c_str(), scaling.c_str());
            set_float("rope.scaling.factor", rope.at("factor").get<float>());
            if (rope.contains("original_max_position_embeddings")) set_u32("rope.scaling.original_context_length", rope.at("original_max_position_embeddings").get<uint32_t>());
        }
        auto vocab = tokenizer.at("model").at("vocab");
        const size_t vocabSize = config.at("vocab_size").get<size_t>();
        if (vocabSize == 0 || vocabSize > 10000000) throw std::runtime_error("Invalid tokenizer vocabulary.");
        std::vector<std::string> tokenStrings(vocabSize);
        std::vector<int32_t> tokenTypes(vocabSize, LLAMA_TOKEN_TYPE_UNUSED);
        for (size_t i = 0; i < vocabSize; ++i) tokenStrings[i] = "[PAD" + std::to_string(i) + "]";
        for (const auto & item : vocab.items()) {
            auto id = item.value().get<size_t>();
            if (id >= vocabSize) throw std::runtime_error("Tokenizer vocabulary exceeds model vocabulary.");
            tokenStrings[id] = item.key(); tokenTypes[id] = LLAMA_TOKEN_TYPE_NORMAL;
        }
        for (const auto & item : tokenizer.value("added_tokens", json::array())) {
            auto id = item.at("id").get<size_t>();
            if (id >= vocabSize) throw std::runtime_error("Tokenizer special token exceeds model vocabulary.");
            tokenStrings[id] = item.at("content").get<std::string>();
            tokenTypes[id] = item.value("special", false) ? LLAMA_TOKEN_TYPE_CONTROL : LLAMA_TOKEN_TYPE_USER_DEFINED;
        }
        gguf_set_val_str(gguf.get(), "tokenizer.ggml.model", "gpt2");
        gguf_set_val_str(gguf.get(), "tokenizer.ggml.pre", architecture == "llama" ? "llama-bpe" : "qwen2");
        std::vector<const char *> tokenPtrs;
        for (auto & token : tokenStrings) tokenPtrs.push_back(token.c_str());
        gguf_set_arr_str(gguf.get(), "tokenizer.ggml.tokens", tokenPtrs.data(), tokenPtrs.size());
        gguf_set_arr_data(gguf.get(), "tokenizer.ggml.token_type", GGUF_TYPE_INT32, tokenTypes.data(), tokenTypes.size());
        std::vector<std::string> mergeStrings;
        for (const auto & merge : tokenizer.at("model").at("merges"))
            mergeStrings.push_back(merge.is_string() ? merge.get<std::string>() : merge.at(0).get<std::string>() + " " + merge.at(1).get<std::string>());
        std::vector<const char *> mergePtrs;
        for (auto & merge : mergeStrings) mergePtrs.push_back(merge.c_str());
        gguf_set_arr_str(gguf.get(), "tokenizer.ggml.merges", mergePtrs.data(), mergePtrs.size());
        auto special = [&](const char * source, const char * target) {
            auto value = generation.value(source, config.value(source, json()));
            if (value.is_array() && !value.empty()) value = value.front();
            if (value.is_number_unsigned() || value.is_number_integer()) {
                auto id = value.get<int64_t>();
                if (id < 0 || uint64_t(id) >= vocabSize) throw std::runtime_error("Invalid tokenizer special token ID.");
                gguf_set_val_u32(gguf.get(), target, id);
            }
        };
        special("bos_token_id", "tokenizer.ggml.bos_token_id");
        special("eos_token_id", "tokenizer.ggml.eos_token_id");
        gguf_set_val_bool(gguf.get(), "tokenizer.ggml.add_bos_token", tokenizerConfig.value("add_bos_token", architecture == "llama"));
        gguf_set_val_bool(gguf.get(), "tokenizer.ggml.add_eos_token", tokenizerConfig.value("add_eos_token", false));
        if (auto it = tokenizerConfig.find("chat_template"); it != tokenizerConfig.end()) {
            if (it->is_string()) gguf_set_val_str(gguf.get(), "tokenizer.chat_template", it->get<std::string>().c_str());
            else if (it->is_array()) for (const auto & item : *it) {
                auto name = item.at("name").get<std::string>();
                auto key = name == "default" ? "tokenizer.chat_template" : "tokenizer.chat_template." + name;
                gguf_set_val_str(gguf.get(), key.c_str(), item.at("template").get<std::string>().c_str());
            }
        } else if (std::filesystem::exists(directory + "/chat_template.jinja")) {
            std::ifstream in(directory + "/chat_template.jinja");
            std::string tmpl((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            gguf_set_val_str(gguf.get(), "tokenizer.chat_template", tmpl.c_str());
        } else throw std::runtime_error("This model has no chat template. Import an Instruct/chat model or a preconverted GGUF.");
        std::vector<Tensor> entries;
        std::vector<std::filesystem::path> shards;
        for (const auto & entry : std::filesystem::directory_iterator(directory))
            if (entry.is_regular_file() && entry.path().extension() == ".safetensors") shards.push_back(entry.path());
        std::sort(shards.begin(), shards.end());
        std::map<std::string, bool> names;
        for (const auto & shard : shards) {
            check_cancel(request);
            std::ifstream input(shard, std::ios::binary);
            uint64_t headerSize = 0; input.read(reinterpret_cast<char *>(&headerSize), 8);
            const uint64_t fileSize = std::filesystem::file_size(shard);
            if (!input || headerSize == 0 || headerSize > 64 * 1024 * 1024 || headerSize > fileSize - 8)
                throw std::runtime_error("Invalid safetensors header.");
            std::string header(headerSize, '\0'); input.read(header.data(), headerSize);
            auto metadata = json::parse(header);
            for (const auto & item : metadata.items()) {
                if (item.key() == "__metadata__") continue;
                Tensor t; t.file = shard.string(); t.name = tensor_name(item.key());
                if (t.name.empty()) continue;
                if (names[t.name]) throw std::runtime_error("Duplicate tensor in weight shards.");
                names[t.name] = true;
                t.dtype = item.value().at("dtype").get<std::string>();
                t.shape = item.value().at("shape").get<std::vector<int64_t>>();
                if (t.shape.empty() || t.shape.size() > GGML_MAX_DIMS) throw std::runtime_error("Unsupported tensor dimensions.");
                ggml_type type;
                size_t elementSize;
                if (t.dtype == "F32") { type = GGML_TYPE_F32; elementSize = 4; }
                else if (t.dtype == "F16") { type = GGML_TYPE_F16; elementSize = 2; }
                else if (t.dtype == "BF16") { type = GGML_TYPE_BF16; elementSize = 2; }
                else throw std::runtime_error("This import requires F32, F16 or BF16 weights. Use GGUF for prequantized weights.");
                uint64_t count = 1;
                for (auto dim : t.shape) {
                    if (dim <= 0 || count > std::numeric_limits<uint64_t>::max() / uint64_t(dim)) throw std::runtime_error("Invalid tensor shape.");
                    count *= dim;
                }
                auto offsets = item.value().at("data_offsets");
                uint64_t begin = offsets.at(0).get<uint64_t>(), end = offsets.at(1).get<uint64_t>();
                if (begin > end || end > fileSize - 8 - headerSize || count > std::numeric_limits<uint64_t>::max() / elementSize ||
                    end - begin != count * elementSize) throw std::runtime_error("Invalid tensor byte offsets.");
                t.offset = 8 + headerSize + begin; t.length = end - begin;
                if (t.shape.size() == 1) type = GGML_TYPE_F32;
                int64_t ne[GGML_MAX_DIMS] = {1,1,1,1};
                std::reverse_copy(t.shape.begin(), t.shape.end(), ne);
                t.tensor = ggml_new_tensor(tensors.get(), type, t.shape.size(), ne);
                ggml_set_name(t.tensor, t.name.c_str());
                gguf_add_tensor(gguf.get(), t.tensor);
                entries.push_back(t);
            }
        }
        if (entries.empty() || !names["token_embd.weight"] || !names["output_norm.weight"])
            throw std::runtime_error("Missing required model tensors.");
        std::vector<float> ropeFactors;
        if (scaling == "llama3") {
            const float factor = rope.value("factor", 8.0f), low = rope.value("low_freq_factor", 1.0f), high = rope.value("high_freq_factor", 4.0f);
            const float oldContext = rope.value("original_max_position_embeddings", 8192.0f);
            if (factor <= 0 || low <= 0 || high <= low) throw std::runtime_error("Invalid Llama RoPE scaling.");
            for (int i = 0; i < headDim; i += 2) {
                float frequency = 1.0f / std::pow(ropeBase, float(i) / headDim);
                float wavelength = 2.0f * 3.14159265358979323846f / frequency;
                float value = 1;
                if (wavelength > oldContext / low) value = factor;
                else if (wavelength >= oldContext / high) {
                    float smooth = (oldContext / wavelength - low) / (high - low);
                    value = 1.0f / ((1 - smooth) / factor + smooth);
                }
                ropeFactors.push_back(value);
            }
            auto * tensor = ggml_new_tensor_1d(tensors.get(), GGML_TYPE_F32, ropeFactors.size());
            ggml_set_name(tensor, "rope_freqs.weight"); gguf_add_tensor(gguf.get(), tensor);
        }
        if (!gguf_write_to_file(gguf.get(), intermediate.c_str(), true)) throw std::runtime_error("Could not write converted model.");
        std::unique_ptr<FILE, decltype(&fclose)> out(fopen(intermediate.c_str(), "ab"), fclose);
        if (!out) throw std::runtime_error("Could not open converted model.");
        std::vector<char> buffer(256 * 1024);
        auto pad = [&](size_t size) { const char zeros[32] = {}; checked_write(out.get(), zeros, (32 - size % 32) % 32); };
        for (const auto & tensor : entries) {
            check_cancel(request);
            std::ifstream in(tensor.file, std::ios::binary);
            in.seekg(tensor.offset);
            if (tensor.shape.size() == 1 && tensor.dtype != "F32") {
                std::vector<uint16_t> source(tensor.length / 2); in.read(reinterpret_cast<char *>(source.data()), tensor.length);
                std::vector<float> values(source.size());
                for (size_t i = 0; i < values.size(); ++i) values[i] = tensor.dtype == "F16" ? ggml_fp16_to_fp32(source[i]) : ggml_bf16_to_fp32(ggml_bf16_t{source[i]});
                checked_write(out.get(), values.data(), values.size() * 4);
            } else if (architecture == "llama" && (tensor.name.find("attn_q.weight") != std::string::npos || tensor.name.find("attn_k.weight") != std::string::npos)) {
                const int nHeads = tensor.name.find("attn_q.weight") != std::string::npos ? heads : kvHeads;
                const int64_t rows = tensor.shape.at(0), rowBytes = tensor.length / rows;
                if (tensor.shape.size() != 2 || rows % (nHeads * 2)) throw std::runtime_error("Invalid Llama attention shape.");
                std::vector<char> row(rowBytes);
                const int64_t perHead = rows / nHeads, half = perHead / 2;
                for (int64_t r = 0; r < rows; ++r) {
                    check_cancel(request);
                    const int64_t local = r % perHead, source = (r / perHead) * perHead + (local % 2) * half + local / 2;
                    in.seekg(tensor.offset + source * rowBytes); in.read(row.data(), rowBytes);
                    if (!in) throw std::runtime_error("Truncated model tensor.");
                    checked_write(out.get(), row.data(), rowBytes);
                }
            } else {
                uint64_t remaining = tensor.length;
                while (remaining) {
                    check_cancel(request);
                    auto size = std::min<uint64_t>(remaining, buffer.size()); in.read(buffer.data(), size);
                    if (!in) throw std::runtime_error("Truncated model tensor.");
                    checked_write(out.get(), buffer.data(), size); remaining -= size;
                }
            }
            if (!in) throw std::runtime_error("Truncated model tensor.");
            pad(ggml_nbytes(tensor.tensor));
        }
        if (!ropeFactors.empty()) { checked_write(out.get(), ropeFactors.data(), ropeFactors.size() * 4); pad(ropeFactors.size() * 4); }
        if (fflush(out.get()) != 0) throw std::runtime_error("Could not finish model conversion.");
        out.reset(); check_cancel(request);
        auto quant = llama_model_quantize_default_params();
        quant.ftype = LLAMA_FTYPE_MOSTLY_Q4_K_M;
        quant.nthread = threads;
        quant.max_buf_size = 64 * 1024 * 1024;
        if (llama_model_quantize(intermediate.c_str(), output.c_str(), &quant) != 0)
            throw std::runtime_error("Q4 conversion failed. Free storage or use a preconverted GGUF.");
        check_cancel(request);
        std::filesystem::remove(intermediate);
    } catch (const std::exception & e) {
        if (!intermediate.empty()) std::remove(intermediate.c_str());
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    }
}
