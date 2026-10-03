#!/usr/bin/env bash
set -euo pipefail

# Resets only the debug app on the selected Android 9/10 emulator between cases.
storage_repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
storage_serial=${1:-emulator-5560}
storage_sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$storage_repo/local.properties")}
storage_adb="$storage_sdk/platform-tools/adb"
storage_package=com.androidharness.app.debug
storage_tests=com.androidharness.app.ui.common.StorageAccessDeviceTest
storage_reports="$storage_repo/app/build/reports/android${2:-9}-storage"

if [[ $storage_serial != emulator-* ]] ||
   [[ $("$storage_adb" -s "$storage_serial" shell getprop ro.kernel.qemu | tr -d '\r') != 1 ]]; then
    printf 'Choose an Android emulator serial. Physical devices are not supported.\n' >&2
    exit 1
fi
storage_api=$("$storage_adb" -s "$storage_serial" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ $storage_api != 28 && $storage_api != 29 ]]; then
    printf 'This suite requires Android 9 or 10, found API %s.\n' "$storage_api" >&2
    exit 1
fi
mkdir -p "$storage_reports"

storage_methods=(
    deniedStorageStillAllowsSetupAndAppWorkspace
    normalPermissionGrantUnlocksDeviceFolders
    permanentDenialOpensAppSettingsAndSetupRemainsAvailable
    settingsCanGrantLegacyStorageAfterSkippingSetup
    systemPickerWithoutStoragePreservesFilesAndProjectHistory
)
storage_images=(
    storage-denied-setup.png storage-denied-app-open.png storage-granted-setup.png
    storage-device-folder-browser.png storage-permanent-denial-settings.png
    storage-settings-granted.png storage-system-picker.png
    storage-system-picker-workspace.png storage-test-failure.png
)
for storage_method in "${storage_methods[@]}"; do
    "$storage_adb" -s "$storage_serial" shell pm clear "$storage_package" >/dev/null
    "$storage_adb" -s "$storage_serial" shell am instrument -w -r \
        -e storageEmulator true -e class "$storage_tests#$storage_method" \
        "$storage_package.test/androidx.test.runner.AndroidJUnitRunner" \
        > "$storage_reports/$storage_method.txt" 2>&1
    for storage_image in "${storage_images[@]}"; do
        if "$storage_adb" -s "$storage_serial" shell run-as "$storage_package" /system/bin/ls "cache/$storage_image" >/dev/null 2>&1; then
            "$storage_adb" -s "$storage_serial" exec-out run-as "$storage_package" cat "cache/$storage_image" \
                > "$storage_reports/$storage_image"
        fi
    done
    if ! rg -q 'OK \(1 test\)' "$storage_reports/$storage_method.txt"; then
        cat "$storage_reports/$storage_method.txt"
        exit 1
    fi
    printf 'PASS %s (Android API %s)\n' "$storage_method" "$storage_api"
done
printf 'Reports and screenshots: %s\n' "$storage_reports"
