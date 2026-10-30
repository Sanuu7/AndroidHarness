---
name: android-build
description: Set up, build, sign, install and debug Android apps on this phone.
category: android
---

# Android build (on this phone)

Build real APKs without a PC. Install the tools once with one approval card, build, then install and test. Fix the first real error. Do not clean the universe first.

## When to use
- The user wants to create, build, sign, install or run an Android app
- assembleDebug or a manual build failed ("could not find", SDK, manifest, aapt2, d8, duplicate class)
- A release APK or AAB is needed for GitHub or Google Play

## When not to use
- The app built and installed but crashes (android-crash)
- Only Compose UI work in an existing buildable project (android-compose)

## 1. Get the tools (once)
1. Check what exists: `env_status`, then `shell`: `java -version; d8 --version; aapt2 version; ls $HOME/android-sdk/platforms`. If all four answer and an `android.jar` is listed for the API you need, skip to step 2.
2. Otherwise call `android_sdk_install`. It shows one card and installs OpenJDK 21, D8, aapt2 and apksigner from the package repository, plus `android.jar` from Google's SDK repository. Pass `platform` (the project's compileSdk, default 35) and `gradle=true` only for Gradle projects. Do not install those pieces one by one with `pkg_install`.
3. If the Linux environment is not installed, say so once. The conversation offers the install action. Never tell the user to move to a PC because a tool is missing.
4. Verify with the commands above. A package marker is not proof; a command that runs is.

## 2. Pick the route
- **A, no Gradle**: a plain Java and XML app, or a new app where the user does not need Kotlin, Compose or Maven libraries. Builds in seconds. Use it by default for "make me a small app".
- **B, Gradle**: the project has `gradlew`, `build.gradle` or `build.gradle.kts`, or needs Kotlin, Compose, AndroidX or other dependencies. Slow and memory hungry. Read `references/gradle-on-device.md` before the first build.

## Route A: build without Gradle
1. New project: copy the files under `templates/hello-java/` with `skill_view(name="android-build", file_path="templates/hello-java/<file>")` and `write_file`. Save `gitignore.txt` as `.gitignore`. Rename the package in the manifest and the Java path together. Check: `package` in the manifest matches the folder under `src/`.
2. Once per project, copy `scripts/build-apk.sh` the same way into the project root.
3. `shell`: `sh build-apk.sh` from the project root. It runs aapt2, javac, d8, adds the dex, signs with a debug key and verifies. Check: the last lines print `APK: <path>`.
4. On an error, read the first message only. javac gives `file:line`; aapt2 names the resource or manifest line. Fix with `edit_file`, re-run the same command.
5. Limits: Java only, plain jars in `libs/` are supported, AARs are not. Kotlin, Compose or AndroidX means route B.

## Route B: build with Gradle
Follow `references/gradle-on-device.md`. In short: use `./gradlew` when it exists, pass the aapt2 override on the command line, cap memory, run it with `shell_background`, and expect the first build to download hundreds of MB.

## Install and run
1. Tell the user the APK path. Copy it somewhere a file manager can reach when needed: `cp <apk> /sdcard/Download/`.
2. Install, in this order:
   - With Shizuku or root (check `env_status`): `pm install -r <apk>`. Check: output contains `Success`.
   - Otherwise ask the user to open the APK from the Files screen or any file manager and tap Install. The Play Store build of AndroidHarness cannot install packages itself, a file manager can.
3. Confirm before claiming it is installed: `pm list packages <package>` when privileged, or the user's confirmation.
4. Launch with `monkey -p <package> -c android.intent.category.LAUNCHER 1` (privileged), or ask the user to open it. Then `read_logcat` with `package_name` and fix crashes with the `android-crash` skill.

## Release (GitHub and Google Play)
Read `references/release.md`. A signed APK works for GitHub Releases. Google Play needs an AAB, which only the Gradle route produces. Never print, commit or put a release keystore or its password in the project.

## Pitfalls
- `ANDROID_JAR` and `$HOME/android-sdk` live under the harness prefix. Do not search `/sdcard` for them or invent Termux paths such as `/data/data/com.termux/...`.
- Do not download Google's build-tools zip. Its aapt2, aidl and zipalign are x86 binaries and will not run here. The aarch64 `aapt2` comes from `android_sdk_install`.
- `resources.arsc` must stay stored and 4-byte aligned for targetSdk 30 and up. `build-apk.sh` appends the dex last to keep it that way. Do not re-zip the APK with `zip` or `jar cf`.
- Never accept Android SDK licenses silently. Ask the user first (see the Gradle reference).
- If a basic command exits 126 or 127, run `env_status` once and follow its diagnosis. Do not change permissions broadly or reinstall Linux as a first response.
- Do not use `--offline` unless the error is clearly a network failure.

## Verification
The build command prints an APK path, `apksigner verify` passed, and installation or launch was confirmed by a real result, not assumed.
