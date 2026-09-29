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
