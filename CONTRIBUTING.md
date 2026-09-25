# Contributing to AndroidHarness

Thanks for helping make AndroidHarness better. Bug fixes, tests, UI improvements, and clearer documentation are all welcome. You don't need to know the whole app to make a useful change.

## Before you start

For a small fix, feel free to open a pull request. For a bigger feature or a change to how the agent behaves, open an issue first so we can talk through the approach before you spend time building it.

If you're reporting a bug, tell us what you tried, what you expected, and what happened instead. Your Android version, app version, and steps to reproduce help a lot. Screenshots or logs are useful too, but please remove tokens, private file paths, and other personal data before sharing them.

## Build and test

You'll need JDK 17 or newer and the Android SDK. The app is a Kotlin and Jetpack Compose project with a single `app` module.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Tests live under `app/src/test` and device tests under `app/src/androidTest`. If your change needs a real device to verify, say what you tested and what you couldn't test. GitHub Actions runs the unit tests and builds the debug APK.

## Pull requests

Keep the change focused and explain why it matters. If it fixes an issue, link it. Add or update tests when you change behavior. For visible UI changes, a screenshot helps reviewers see the difference.

Please don't commit API keys, signing keys, `local.properties`, or personal workspace data. Before opening the pull request, check the diff for anything you didn't mean to include.

The project is MIT licensed; see the LICENSE file.
