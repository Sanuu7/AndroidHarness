#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
    echo "Usage: $0 ADB_SERIAL RELEASE_APK GGUF_FIXTURE [GPU_PACKAGE.gz]" >&2
    exit 2
fi
release_serial=$1
release_apk=$(realpath "$2")
release_model=$(realpath "$3")
release_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-toolchain/android-sdk}}
release_java=${JAVA_HOME:-$HOME/.local/share/android-toolchain/jdk17}
release_tools=$(printf '%s\n' "$release_sdk"/build-tools/* | sort -V | tail -1)
release_platform=$(printf '%s\n' "$release_sdk"/platforms/* | sort -V | tail -1)
release_here=$(cd "$(dirname "$0")" && pwd)
release_work=$(mktemp -d -t androidharness-release-probe.XXXXXXXX)
release_stage="/data/local/tmp/androidharness-release-probe-$$"
release_abi=$(adb -s "$release_serial" shell getprop ro.product.cpu.abi | tr -d '\r')

mkdir -p "$release_work/classes"
"$release_java/bin/javac" -source 17 -target 17 -d "$release_work/classes" "$release_here/ReleaseProbe.java"
"$release_java/bin/jar" --create --file "$release_work/probe.jar" -C "$release_work/classes" .
JAVA_HOME="$release_java" "$release_tools/d8" --release --min-api 26 \
    --lib "$release_platform/android.jar" --output "$release_work/probe-dex.jar" "$release_work/probe.jar"

adb -s "$release_serial" shell mkdir -p "$release_stage"
adb -s "$release_serial" push "$release_work/probe-dex.jar" "$release_stage/probe.jar" >/dev/null
adb -s "$release_serial" push "$release_apk" "$release_stage/app.apk" >/dev/null
adb -s "$release_serial" push "$release_model" "$release_stage/model.gguf" >/dev/null
release_gpu_path=""
if [[ $# -eq 4 ]]; then
    gzip -dc "$4" > "$release_work/libggml-vulkan.so"
    release_gpu_path="$release_stage/libggml-vulkan.so"
    adb -s "$release_serial" push "$release_work/libggml-vulkan.so" "$release_gpu_path" >/dev/null
    adb -s "$release_serial" shell chmod 444 "$release_gpu_path"
fi
set +e
adb -s "$release_serial" shell \
    "CLASSPATH='$release_stage/probe.jar:$release_stage/app.apk' app_process -Djava.library.path='$release_stage/app.apk!/lib/$release_abi' /system/bin ReleaseProbe '$release_stage/model.gguf' '$release_gpu_path'" \
    >"$release_work/result.log" 2>&1
release_status=$?
set -e
cat "$release_work/result.log"
# The probe deliberately uses the same SIGKILL termination as Android. An
# assertion, JNI abort, or any failure before the success marker still fails.
if [[ $release_status -ne 137 ]] || ! grep -qx RELEASE_PROBE_OK "$release_work/result.log"; then
    echo "Release probe failed (exit $release_status). Files retained at $release_stage" >&2
    exit 1
fi

adb -s "$release_serial" shell rm "$release_stage/probe.jar" "$release_stage/app.apk" "$release_stage/model.gguf"
if [[ -n "$release_gpu_path" ]]; then adb -s "$release_serial" shell rm "$release_gpu_path"; fi
adb -s "$release_serial" shell rmdir "$release_stage"
