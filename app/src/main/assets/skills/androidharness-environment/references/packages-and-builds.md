# Linux packages and builds

## What the Linux environment is

AndroidHarness installs Termux-derived packages inside its own private Linux prefix. It is not a desktop Ubuntu/container environment and does not require the separate Termux app. A second staged copy is used by the shell UID when Shizuku executes outside app storage. The harness supplies launch shims, library paths, certificate paths, HOME, caches, `PREFIX` and `HARNESS_SCRATCH` for the chosen tier.

Use plain commands through `shell`, `shell_background` or the terminal. Do not invoke Android's linker yourself, construct replacement PATHs, use another app's prefix, or run `apt`/`pkg` in the shell. Use `pkg_search`, `pkg_install` and `pkg_list`. Package installation resolves dependencies and goes through the harness's approval policy. A command needing an uninstalled Linux environment can offer an install action in the conversation; do not repeatedly retry toybox variants of missing development tools.

`Ready` means the general environment is installed. Package markers and files are separate evidence from successful executable checks. A missing/broken add-on does not necessarily mean the entire environment needs reinstalling. Existing compiler launchers are repaired, and supported missing compiler/JDK files are detected for package repair. Check the actual tool result before claiming success.

## Android APK builds

Load the `android-build` skill when it is available. Inspect the project's wrapper, build configuration and first actual error. Java, D8, the SDK/platform, native resource tools, signing tools and a compatible Gradle/Android plugin are separate requirements. Do not conclude that a phone cannot build APKs because one prerequisite is missing.

- When the build tools are missing, call `android_sdk_install`. It installs OpenJDK, D8, aapt2, apksigner (and Gradle with `gradle=true`) plus `android.jar` under `$HOME/android-sdk/platforms/android-<api>/` behind one approval card. It works the same in the GitHub and Google Play builds because the packages come from the same repository `pkg_install` uses.
- Verify `java -version`; use a JDK compatible with that project's build system.
- For a manual DEX workflow, verify `d8 --version`. The current repository's D8 package depends on OpenJDK 21; use `pkg_search` to inspect the available package and `pkg_install` to install it when needed.
- The D8/R8 JAR belongs at `$PREFIX/share/java/d8.jar`, and the APK signing JAR at `$PREFIX/share/java/apksigner.jar`. Never use `/data/data/com.termux/...` inside AndroidHarness.
- The harness exports the selected installed JDK as `JAVA_HOME` and exposes its commands and libraries. Do not hand-edit Java aliases or prepend libraries to work around the harness.
- General environment readiness does not verify an SDK or `android.jar`. Use the actual platform path available to this project. Native tools must match the Android CPU/ABI; a desktop Linux SDK executable is not automatically compatible.
- Run the actual build; D8's version alone is not build proof. For a manual workflow, compilation, DEX conversion, resource packaging and APK signing are separate steps. Installation also needs an Android installer or permitted package-manager route.
- Shizuku/root readiness does not install a compiler or SDK. Compiling ordinary app-workspace code does not inherently require root.

## Shared storage and scratch

Shared storage such as `/sdcard` normally lacks working executable bits and symlinks. Keep source where the user selected it. Use the current `HARNESS_SCRATCH` for extracted JDKs, Gradle/native tools or other exec-dependent intermediates. The app and shell tiers have different scratch paths. Do not assume the app can inspect the shell-only scratch directory.

The harness can retarget certain archive extractions to exec-capable scratch and adds `--no-bin-links` to relevant npm installations on shared storage. Read returned notes because they identify the actual path used. A successful extraction into scratch does not move the project's source root. If a framework needs symlinks in the project itself, explain the filesystem requirement and agree on an appropriate workspace rather than silently copying it.

For a normal build failure, fix the cited dependency/configuration/source problem. For a basic command's exit 126/127 or permission failure, use `env_status` once and follow the concrete diagnosis. Do not clean caches, uninstall Linux or change permissions broadly as a first response.
