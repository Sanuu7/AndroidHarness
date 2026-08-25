---
name: android-build
description: Build, install, or launch failed. Gradle, pm, then read the failure.
category: android
---

# Android build

Read the Gradle / pm error. Fix that error. Do not clean the universe first.

## When to use
- assembleDebug / install failed
- "could not find", SDK, manifest, R8, duplicate class

## When not to use
- The APK built and the app crashed (android-crash)

## Procedure
1. Find the wrapper (`search_files` `gradlew*`). Check: path.
2. `shell` `./gradlew :app:assembleDebug` (or the module that failed) from the project root. Check: the first real error, not the 200-line tail only.
3. `read_file` the cited file. Fix with `edit_file`.
4. Re-run the same Gradle task. Check: SUCCESS.
5. Install only if the user asked: `shell` `pm install -r <apk>` needs Shizuku / privileged tier. If `env_status` says no, tell them to use Android Studio or grant Shizuku.

## Pitfalls
- `gradlew` needs the Linux prefix (or host JDK). If 126/127, `env_status` then stop.
- Do not `--offline` unless the error is clearly network.

## Verification
The same assemble command succeeds.
