# Gradle builds on the phone

Gradle works only when its Android plugin can run `aapt2` on the phone, and that needs a few overrides. This route has more moving parts than the Gradle-less one. Say so to the user and fall back to route A for anything that does not need Gradle.

## Prerequisites

Call `android_sdk_install` with `gradle=true` and the project's compileSdk as `platform`. That gives OpenJDK 21, Gradle, an aarch64 `aapt2` and `android.jar`. Kotlin needs no separate package: the Gradle plugin fetches its compiler from Maven.

Check the project's needs first: `read_file` the root and app `build.gradle(.kts)`, `gradle/wrapper/gradle-wrapper.properties` and `gradle/libs.versions.toml`. Note compileSdk, the AGP version, the Kotlin version and the JDK it expects (usually 17 or newer; OpenJDK 21 is fine).

## Environment

Run these in the same command as the build. The path differs between execution tiers, so do not commit it.

```sh
export ANDROID_HOME="$HOME/android-sdk"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties   # skip if it already exists
```

`local.properties` is machine specific. Make sure it is in `.gitignore`.

## Licenses and the platform

The Android Gradle plugin downloads missing SDK pieces itself, but only after the SDK license is accepted. That is the user's decision, not yours.

1. `ask_user`: "Gradle needs you to accept Google's Android SDK License. Accept it for this phone?"
2. Only on a yes: `mkdir -p "$ANDROID_HOME/licenses" && echo 24333f8a63b6825ea9c5514f83c2829b004d1fee > "$ANDROID_HOME/licenses/android-sdk-license"`.
3. If Gradle reports `platforms/android-NN` as incomplete or corrupt, delete that folder and re-run. The plugin downloads the full platform. The `android.jar` from `android_sdk_install` is only for route A.

## The build command

```sh
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" \
  --no-daemon \
  -Dorg.gradle.jvmargs=-Xmx1536m \
  -Dorg.gradle.workers.max=2 \
  -Dorg.gradle.native=false \
  -Dorg.gradle.vfs.watch=false \
  -Dkotlin.compiler.execution.strategy=in-process
```

- No `gradlew`: use the installed `gradle` command with the same flags. The wrapper downloads its distribution on first use, which is large.
- Memory: read `grep MemTotal /proc/meminfo`. With 8 GB or more, `-Xmx2g` is fine. With 6 GB keep `-Xmx1536m`. Below 4 GB, warn the user that a Compose or Kotlin build may be killed and suggest route A.
- Run it with `shell_background`, then watch with `bg_list` and read the output. The first build commonly takes many minutes because dependencies download. Tell the user to stay on Wi-Fi and keep the screen awake or the app in the foreground.
- A different module or variant: change the task (`:feature:assembleRelease`). Keep every flag.

## Outputs

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release AAB for Play: `./gradlew :app:bundleRelease` then `app/build/outputs/bundle/release/app-release.aab`
- Release APK: `app/build/outputs/apk/release/`

## Failure guide

| Message | Cause and action |
| --- | --- |
| `AAPT2 ... Daemon startup failed`, `Syntax error: word unexpected`, exec format error | The plugin ran Google's x86 aapt2. The override flag is missing or points at the wrong file. Re-check `command -v aapt2`. |
| `SDK location not found` | Create `local.properties` or export `ANDROID_HOME`. |
| `License for package ... not accepted` | Ask the user, then write the license file as above. |
| `Could not resolve` or `Could not find ... in repositories` | Network or a wrong coordinate. Retry once; if it persists read the cited dependency. |
| Process killed, exit 137, `OutOfMemoryError` | Lower `-Xmx`, set `workers.max=1`, close other apps. If it still dies the phone cannot build this project with Gradle; say so and offer route A for the simple parts. |
| `Unsupported class file major version` or a JDK mismatch | The project needs a different JDK. `pkg_search openjdk` and `pkg_install` the version, then re-run. |
| aapt2 reports an unknown option or fails to link resources | The aapt2 package is older or newer than the plugin expects. Align AGP with the installed `aapt2 version`, or use a project pinned to a nearby AGP. |
| `Failed to load native library` | Keep `-Dorg.gradle.native=false`. |

Fix the first real error, then re-run the same task. Quote the failing lines when you report back; do not summarize a stack trace away.
