# Release builds for GitHub and Google Play

## Which file goes where

| Destination | File | Route |
| --- | --- | --- |
| GitHub Releases, direct install | Signed APK | Either route |
| Google Play | Signed AAB | Gradle only (`bundleRelease`) |

The Gradle-less script makes APKs only. If the user wants Play and the project is on route A, explain that it needs the Gradle route and offer to convert it.

## Signing keys

- The debug key is created automatically by `build-apk.sh` at `$HOME/.android/debug.keystore`. Never use it for a release.
- Create a release key only when the user asks for a release build. Store it outside the project, for example `$HOME/.android/release.jks`.
- Generate the password into a file instead of showing it. Do not print it in chat, do not put it in the project, and do not pass it on a command line:

```sh
mkdir -p "$HOME/.android" && umask 077
head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$HOME/.android/release.pass"
KS_PASS=$(cat "$HOME/.android/release.pass")
export KS_PASS
keytool -genkeypair -keystore "$HOME/.android/release.jks" -alias release \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass:env KS_PASS -keypass:env KS_PASS -dname "CN=<app or developer name>"
```

- Tell the user where both files are and that losing them means they cannot ship updates signed with that key. Ask them to back both up outside the phone.
- A signed APK from route A: `KEYSTORE="$HOME/.android/release.jks" KS_ALIAS=release KS_PASS=$(cat "$HOME/.android/release.pass") VERSION_CODE=<n> VERSION_NAME=<x.y> sh build-apk.sh`.
- Gradle: read the signing values from environment variables in `signingConfigs`, never from committed files.

## Google Play

- Play needs an AAB, a unique `applicationId`, and a `versionCode` higher than any uploaded build. Bump both `versionCode` and `versionName` for every upload.
- Recommend Play App Signing. Then the key above is only an upload key, and Google can reset it if it is lost.
- The first release of a new app is created in Play Console by the user: store listing, content rating, data safety form and the testing track rules for their account type. Do not claim you can do these. After the first release exists, uploading a new AAB in Console is a manual step; the agent prepares the file and the release notes.
- `targetSdkVersion` must meet Play's current requirement. Check Play Console or the Android developer documentation for the present minimum rather than assuming one.

## GitHub

- Commit source with the git tools. Do not commit `build/`, `local.properties`, keystores or password files.
- To publish the APK, create a release and upload the file with `github_api` only when the user asks. Name the asset after the app and version.
- Build and install steps never need GitHub. Pushing is separate and needs the user's approval like any other push.
