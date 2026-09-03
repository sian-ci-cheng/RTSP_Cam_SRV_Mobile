#!/usr/bin/env bash
# Build (best-effort) and deploy MediaStreamTest to a connected Android device.
#
# NOTE on "build": this project's Gradle setup depends on a private,
# authenticated Maven repo (exchange.videoexpertsgroup.com) for
# com.vxg.mediasdk:encodersdk, uses the defunct jcenter, and has no
# gradle-wrapper.properties/jar checked in. A real `./gradlew assembleDebug`
# will fail without VXG repo credentials. This script attempts it anyway
# (so it "just works" once credentials are wired in), and falls back to
# installing the prebuilt bin/MediaStreamTest.apk when the build isn't
# available.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROJECT_DIR="$REPO_ROOT/src"
PREBUILT_APK="$REPO_ROOT/bin/MediaStreamTest.apk"
PACKAGE_NAME="veg.mediacapture.sdk.test.demo"
MAIN_ACTIVITY="veg.mediacapture.sdk.test.MainActivity"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/Volumes/Crucial/Code/Android/sdk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"

log()  { printf '\n[build_and_deploy] %s\n' "$1"; }
fail() { printf '\n[build_and_deploy] ERROR: %s\n' "$1" >&2; exit 1; }

[ -x "$ADB" ] || fail "adb not found at $ADB (set ANDROID_SDK_ROOT to override)"

# ---------------------------------------------------------------------------
# 1. Device check
# ---------------------------------------------------------------------------
log "Checking for a connected device..."
DEVICE_LINE="$("$ADB" devices | awk 'NR>1 && NF>0')"

if [ -z "$DEVICE_LINE" ]; then
  fail "No device detected. Plug in the phone via USB (not through the RJ45 hub) and retry."
fi

if echo "$DEVICE_LINE" | grep -q "unauthorized"; then
  fail "Device is unauthorized. Accept the 'Allow USB debugging' prompt on the phone, then retry."
fi

DEVICE_ID="$(echo "$DEVICE_LINE" | awk '{print $1}')"
log "Using device: $DEVICE_ID"

# ---------------------------------------------------------------------------
# 2. Build (best-effort)
# ---------------------------------------------------------------------------
APK_TO_INSTALL=""

if [ -x "$GRADLE_PROJECT_DIR/gradlew" ] && [ -f "$GRADLE_PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  log "Attempting Gradle build (assembleDebug)..."
  if (cd "$GRADLE_PROJECT_DIR" && ./gradlew :MediaStreamTest_astudio:assembleDebug); then
    BUILT_APK="$(find "$GRADLE_PROJECT_DIR" -path '*/outputs/apk/*debug*.apk' -print -quit)"
    if [ -n "$BUILT_APK" ]; then
      APK_TO_INSTALL="$BUILT_APK"
      log "Build succeeded: $APK_TO_INSTALL"
    fi
  else
    log "Gradle build failed (likely missing VXG repo credentials for encodersdk). Falling back to prebuilt APK."
  fi
else
  log "Gradle wrapper jar not present — skipping source build. Falling back to prebuilt APK."
fi

if [ -z "$APK_TO_INSTALL" ]; then
  [ -f "$PREBUILT_APK" ] || fail "No built APK and no prebuilt APK found at $PREBUILT_APK"
  APK_TO_INSTALL="$PREBUILT_APK"
fi

# ---------------------------------------------------------------------------
# 3. Deploy
# ---------------------------------------------------------------------------
log "Installing $APK_TO_INSTALL ..."
"$ADB" -s "$DEVICE_ID" install -r "$APK_TO_INSTALL"

log "Launching $PACKAGE_NAME/$MAIN_ACTIVITY ..."
"$ADB" -s "$DEVICE_ID" shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"

log "Done."
