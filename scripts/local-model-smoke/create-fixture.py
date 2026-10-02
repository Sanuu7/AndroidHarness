"""Small deterministic safetensors model for conversion/load tests; not a trained model."""
import json
import math
import pathlib
import struct
import sys
import zlib

root = pathlib.Path(sys.argv[1])
root.mkdir(parents=True, exist_ok=True)
config = dict(model_type="qwen2", architectures=["Qwen2ForCausalLM"], hidden_size=64,
              intermediate_size=128, num_hidden_layers=1, num_attention_heads=4,
              num_key_value_heads=2, vocab_size=512, max_position_embeddings=8192,
              rms_norm_eps=1e-6, rope_theta=10000.0, bos_token_id=258, eos_token_id=257,
              tie_word_embeddings=True)
(root / "config.json").write_text(json.dumps(config))
chars = list(range(ord("!"), ord("~") + 1)) + list(range(161, 173)) + list(range(174, 256))
byte_encoder = {value: value for value in chars}
extra = 0
for value in range(256):
    if value not in byte_encoder:
        byte_encoder[value] = 256 + extra
        extra += 1
vocab = {chr(byte_encoder[i]): i for i in range(256)}
added = [dict(id=256+i, content=token, special=True) for i, token in enumerate(["<|im_start|>", "<|im_end|>", "<|endoftext|>"])]
(root / "tokenizer.json").write_text(json.dumps(dict(model=dict(type="BPE", vocab=vocab, merges=[]), added_tokens=added)))
template = "{% for message in messages %}{{ '<|im_start|>' + message['role'] + '\\n' + message['content'] + '<|im_end|>\\n' }}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant\\n' }}{% endif %}"
(root / "tokenizer_config.json").write_text(json.dumps(dict(chat_template=template, add_bos_token=False, add_eos_token=False)))
header = {}
body = bytearray()

def tensor(name, shape, dtype="BF16", norm=False):
    count = math.prod(shape)
    data = bytearray()
    for i in range(count):
        value = 1.0 if norm else math.sin(i + 1) * 0.02
        if dtype == "BF16":
            data += struct.pack("<H", struct.unpack("<I", struct.pack("<f", value))[0] >> 16)
        elif dtype == "F16":
            data += struct.pack("<e", value)
        else:
            data += struct.pack("<f", value)
    begin = len(body)
    body.extend(data)
    header[name] = dict(dtype=dtype, shape=shape, data_offsets=[begin, len(body)])

tensor("model.embed_tokens.weight", [512, 64])
tensor("model.norm.weight", [64], "F16", norm=True)
for name, shape in [("q_proj.weight", [64,64]), ("k_proj.weight", [32,64]), ("v_proj.weight", [32,64]), ("o_proj.weight", [64,64])]:
    tensor("model.layers.0.self_attn." + name, shape)
for name, shape in [("q_proj.bias", [64]), ("k_proj.bias", [32]), ("v_proj.bias", [32])]:
    tensor("model.layers.0.self_attn." + name, shape, "F32")
for name in ["input_layernorm.weight", "post_attention_layernorm.weight"]:
    tensor("model.layers.0." + name, [64], "BF16", norm=True)
for name, shape in [("gate_proj.weight", [128,64]), ("up_proj.weight", [128,64]), ("down_proj.weight", [64,128])]:
    tensor("model.layers.0.mlp." + name, shape)
encoded = json.dumps(header, separators=(",", ":")).encode()
(root / "model.safetensors").write_bytes(struct.pack("<Q",len(encoded)) + encoded + body)
# A solid red PNG for the trained vision-model test, using only the standard library.
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
width = height = 64
pixels = b"".join(b"\0" + bytes([255,0,0])*width for _ in range(height))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR",struct.pack(">IIBBBBB",width,height,8,2,0,0,0)) + chunk(b"IDAT",zlib.compress(pixels)) + chunk(b"IEND",b"")
(root / "red.png").write_bytes(png)
print("Wrote conversion fixture and red image to", root)
