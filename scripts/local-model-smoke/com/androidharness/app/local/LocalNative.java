package com.androidharness.app.local;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class LocalNative {
    static { System.loadLibrary("harness_local"); }
    native long create();
    native void cancel(long handle);
    native void destroy(long handle);
    native int[] generate(long handle, byte[] path, String[] roles, byte[][] contents,
                         int context, int input, int output, int threads, Callback callback);
    public interface Callback { boolean onToken(byte[] bytes); }
    native byte[] generateChat(long handle, byte[] path, byte[] projector, byte[] request,
                              byte[][] images, int context, int input, int output, int threads, ChatCallback callback);
    native void convertSafetensors(long handle, byte[] directory, byte[] output, int threads);
    public interface ChatCallback { boolean onEvent(byte[] bytes); boolean onWarning(byte[] bytes); }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static final ChatCallback callback = new ChatCallback() {
        public boolean onEvent(byte[] data) { return true; }
        public boolean onWarning(byte[] data) { System.out.println("WARNING " + new String(data, StandardCharsets.UTF_8)); return true; }
    };
    static void chatTests(LocalNative runtime, byte[] path) {
        long handle = runtime.create();
        try {
            String request = "{\"messages\":[{\"role\":\"system\",\"content\":\"You are a helpful assistant. Use the tools to fulfill user requests.\"},{\"role\":\"user\",\"content\":\"Read the file README.md using read_file.\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"description\":\"Read the contents of a file\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}}]}";
            String result = new String(runtime.generateChat(handle, path, utf8(""), utf8(request), new byte[0][], 1024, 768, 128, 2, callback), StandardCharsets.UTF_8);
            if (!result.contains("\"name\":\"read_file\"") || !result.contains("README.md")) throw new AssertionError("Tool call not parsed: " + result);
            System.out.println("PASS native tool calling " + result);
        } finally { runtime.destroy(handle); }
        long expanded = runtime.create();
        try {
            String request = "{\"messages\":[{\"role\":\"user\",\"content\":\"What is 2+2?\"}],\"tools\":[]}";
            int[] warnings = {0};
            runtime.generateChat(expanded, path, utf8(""), utf8(request), new byte[0][], 16, 1, 16, 2, new ChatCallback() {
                public boolean onEvent(byte[] data) { return true; }
                public boolean onWarning(byte[] data) { warnings[0]++; return true; }
            });
            if (warnings[0] == 0) throw new AssertionError("Expected context warning");
            System.out.println("PASS context warning and Continue");
        } finally { runtime.destroy(expanded); }
        long cancelled = runtime.create();
        try {
            String request = "{\"messages\":[{\"role\":\"user\",\"content\":\"What is 2+2?\"}],\"tools\":[]}";
            expectFailure(() -> runtime.generateChat(cancelled, path, utf8(""), utf8(request), new byte[0][], 16, 1, 16, 2, new ChatCallback() {
                public boolean onEvent(byte[] data) { return true; }
                public boolean onWarning(byte[] data) { return false; }
            }), "stopped");
        } finally { runtime.destroy(cancelled); }
    }

    public static void main(String[] args) {
        LocalNative runtime = new LocalNative();
        byte[] path = args[0].getBytes(StandardCharsets.UTF_8);
        byte[][] messages = {"You are a helpful assistant.".getBytes(StandardCharsets.UTF_8),
            "What is 2 + 2? Answer briefly.".getBytes(StandardCharsets.UTF_8)};
        String[] roles = {"system", "user"};
        for (int round = 0; round < 2; round++) {
            long handle = runtime.create();
            try {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                int[] counts = runtime.generate(handle, path, roles, messages, 512, 384, 32, 2,
                    bytes -> { text.write(bytes, 0, bytes.length); return true; });
                String answer = text.toString(StandardCharsets.UTF_8);
                if (!answer.contains("4") || counts[0] <= 0 || counts[1] <= 0 || counts[1] > 32)
                    throw new AssertionError("Bad generation: " + answer + Arrays.toString(counts));
                System.out.println("PASS load/generate/unload " + round + ": " + answer + " " + Arrays.toString(counts));
            } finally { runtime.destroy(handle); }
        }
        long cancelled = runtime.create();
        try {
            runtime.cancel(cancelled);
            expectFailure(() -> runtime.generate(cancelled, path, roles, messages, 512, 384, 32, 2, bytes -> true), "stopped");
        } finally { runtime.destroy(cancelled); }
        long oversized = runtime.create();
        try {
            expectFailure(() -> runtime.generate(oversized, path, roles, messages, 512, 1, 32, 2, bytes -> true), "Input exceeds");
        } finally { runtime.destroy(oversized); }
        long stopped = runtime.create();
        try {
            expectFailure(() -> runtime.generate(stopped, path, roles, messages, 512, 384, 128, 2,
                bytes -> { runtime.cancel(stopped); return false; }), "stopped");
        } finally { runtime.destroy(stopped); }
        chatTests(runtime, path);
        if (args.length > 1) {
            long converter = runtime.create();
            try {
                runtime.convertSafetensors(converter, utf8(args[1]), utf8(args[1] + "/converted.gguf"), 2);
                long inference = runtime.create();
                try {
                    String request = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"tools\":[]}";
                    String result = new String(runtime.generateChat(inference, utf8(args[1] + "/converted.gguf"), utf8(""), utf8(request), new byte[0][], 512, 384, 8, 2, callback), StandardCharsets.UTF_8);
                    if (!result.contains("\"input\":")) throw new AssertionError(result);
                    System.out.println("PASS safetensors conversion and inference " + result);
                } finally { runtime.destroy(inference); }
            } finally { runtime.destroy(converter); }
        }
        if (args.length > 3) {
            long vision = runtime.create();
            try {
                String request = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"media_marker\",\"text\":\"<__media__>\"},{\"type\":\"text\",\"text\":\"What color is this square? Answer briefly.\"}]}],\"tools\":[]}";
                ByteArrayOutputStream events = new ByteArrayOutputStream();
                byte[] image = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[4]));
                String result = new String(runtime.generateChat(vision, utf8(args[2]), utf8(args[3]), utf8(request), new byte[][]{image}, 2048, 1536, 64, 2, new ChatCallback() {
                    public boolean onEvent(byte[] data) { events.write(data, 0, data.length); return true; }
                    public boolean onWarning(byte[] data) { return true; }
                }), StandardCharsets.UTF_8);
                String answer = events.toString(StandardCharsets.UTF_8);
                if (!answer.toLowerCase().contains("red")) throw new AssertionError("Vision failed: " + answer + result);
                System.out.println("PASS vision " + answer + result);
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
            finally { runtime.destroy(vision); }
        }
        System.out.println("PASS native smoke suite");
    }

    static void expectFailure(Runnable operation, String expected) {
        try { operation.run(); throw new AssertionError("Expected " + expected); }
        catch (IllegalStateException error) {
            if (!error.getMessage().contains(expected)) throw error;
            System.out.println("PASS " + expected);
        }
    }
}
