#!/bin/sh
# Gradle-less Android APK build: aapt2 + javac + d8 + apksigner.
# Usage: sh build-apk.sh [project_dir]
#
# Project layout:
#   AndroidManifest.xml     package="..." is read from here
#   src/**/*.java           sources (Kotlin needs the Gradle route)
#   res/  assets/  libs/*.jar   all optional
#
# Environment (all optional):
#   ANDROID_JAR   path to android.jar (default: newest under $HOME/android-sdk/platforms)
#   MIN_SDK       default 26        TARGET_SDK  default 35
#   VERSION_CODE  default 1         VERSION_NAME default 1.0
#   KEYSTORE      default $HOME/.android/debug.keystore (created when missing)
#   KS_ALIAS      default androiddebugkey
#   KS_PASS       default android; read by apksigner through env:, never on a command line
set -eu

PROJECT=${1:-.}
cd "$PROJECT"

MIN_SDK=${MIN_SDK:-26}
TARGET_SDK=${TARGET_SDK:-35}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
KS_PASS=${KS_PASS:-android}
export KS_PASS

fail() { echo "build-apk: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 is not installed. Run android_sdk_install first."; }

for tool in aapt2 javac jar d8 apksigner keytool; do need "$tool"; done
[ -f AndroidManifest.xml ] || fail "AndroidManifest.xml not found in $(pwd)"

if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=$(ls -d "$HOME"/android-sdk/platforms/android-*/android.jar 2>/dev/null | sort | tail -n 1 || true)
fi
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] || fail "android.jar not found. Run android_sdk_install, or set ANDROID_JAR."

PACKAGE=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -n 1)
[ -n "$PACKAGE" ] || fail "AndroidManifest.xml has no package attribute"

rm -rf build
mkdir -p build/classes build/dex build/gen

LINK_INPUTS=""
if [ -d res ] && [ -n "$(ls -A res 2>/dev/null)" ]; then
    aapt2 compile --dir res -o build/res.zip
    LINK_INPUTS="build/res.zip"
fi
ASSET_ARGS=""
[ -d assets ] && ASSET_ARGS="-A assets"

# shellcheck disable=SC2086
aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml $ASSET_ARGS \
    --java build/gen --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o build/base.apk $LINK_INPUTS

CLASSPATH_JARS="$ANDROID_JAR"
LIB_JARS=""
for lib in libs/*.jar; do
    [ -f "$lib" ] || continue
    CLASSPATH_JARS="$CLASSPATH_JARS:$lib"
    LIB_JARS="$LIB_JARS $lib"
done

find src build/gen -name '*.java' > build/sources.txt
[ -s build/sources.txt ] || fail "no .java sources under src/"
javac -source 8 -target 8 -bootclasspath "$CLASSPATH_JARS" -Xlint:-options \
    -d build/classes @build/sources.txt

jar cf build/classes.jar -C build/classes .
# shellcheck disable=SC2086
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output build/dex build/classes.jar $LIB_JARS

# Adding entries to the end keeps resources.arsc stored and 4-byte aligned, which
# Android 11+ requires when targetSdk is 30 or higher.
cp build/base.apk build/unsigned.apk
for dex in build/dex/classes*.dex; do
    jar --update --file build/unsigned.apk -C build/dex "$(basename "$dex")"
done

if [ ! -f "$KEYSTORE" ]; then
    [ "$KEYSTORE" = "$HOME/.android/debug.keystore" ] || fail "keystore $KEYSTORE does not exist"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

OUT="build/$PACKAGE.apk"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "$OUT" build/unsigned.apk
apksigner verify "$OUT"

echo "APK: $(pwd)/$OUT"
echo "Package: $PACKAGE"
