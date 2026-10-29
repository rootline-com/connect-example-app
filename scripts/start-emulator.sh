#!/usr/bin/env bash
set -euo pipefail

# Starts a local Android emulator with the connect-app emulator (:connect-app-emulator) installed,
# without Docker. Works on macOS (Apple Silicon and Intel, through the macOS hypervisor) and on
# Linux (through KVM). The emulator keeps running in the background; stop it with --stop.
# Requires: a JDK 17-21 and an Android SDK (Android Studio installs one). The script installs the
# command-line tools, the emulator and the system image into that SDK when they are missing.
#
# Usage:
#   scripts/start-emulator.sh                      # API 34 aosp_atd, ABI of this machine, with a window
#   scripts/start-emulator.sh --headless           # no window, for CI or a terminal session
#   scripts/start-emulator.sh --api 35             # specific API level
#   scripts/start-emulator.sh --variant google_apis  # another system image variant
#   scripts/start-emulator.sh --apk path/to/emulator.apk  # use a built APK, skip Gradle
#   scripts/start-emulator.sh --stop               # stop the emulator this script started

# Written for the Bash 3.2 that macOS ships: no associative arrays, no `timeout`.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
AVD_NAME="connect-emulator"
# A fixed console port gives a known adb serial. 5584 is the last port the emulator scans, so it
# rarely collides with an emulator started from Android Studio.
CONSOLE_PORT="5584"
SERIAL="emulator-${CONSOLE_PORT}"
BOOT_TIMEOUT_SECONDS=300

CMDLINE_TOOLS_VERSION="14742923"

# Defaults
API_LEVEL="34"
VARIANT="aosp_atd"
APK=""
HEADLESS=false
STOP=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --api)      API_LEVEL="$2"; shift 2 ;;
        --variant)  VARIANT="$2"; shift 2 ;;
        --apk)      APK="$2"; shift 2 ;;
        --headless) HEADLESS=true; shift ;;
        --stop)     STOP=true; shift ;;
        --help|-h)
            head -17 "$0" | tail -16
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# --- Locate the Android SDK: environment, then local.properties, then the Android Studio default ---

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f "$REPO_ROOT/local.properties" ]]; then
    SDK="$(grep '^sdk.dir=' "$REPO_ROOT/local.properties" | cut -d= -f2- || true)"
fi
if [[ -z "$SDK" ]]; then
    case "$(uname -s)" in
        Darwin) SDK="$HOME/Library/Android/sdk" ;;
        *)      SDK="$HOME/Android/Sdk" ;;
    esac
fi
if [[ ! -d "$SDK" ]]; then
    echo "ERROR: no Android SDK at ${SDK}"
    echo "Install Android Studio, or set ANDROID_HOME to an Android SDK."
    exit 1
fi
export ANDROID_HOME="$SDK"

ADB="$SDK/platform-tools/adb"

# --- Stop mode ---

if [[ "$STOP" == "true" ]]; then
    if [[ -x "$ADB" ]] && "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
        "$ADB" -s "$SERIAL" emu kill
        echo "==> Stopped ${SERIAL}"
    else
        echo "==> ${SERIAL} is not running"
    fi
    exit 0
fi

# --- Choose the system image for this machine ---

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64|Linux-aarch64) ABI="arm64-v8a" ;;
    Darwin-x86_64|Linux-x86_64) ABI="x86_64" ;;
    *) echo "ERROR: unsupported machine $(uname -s) $(uname -m)"; exit 1 ;;
esac

if [[ "$(uname -s)" == "Linux" && ! -e /dev/kvm ]]; then
    echo "ERROR: /dev/kvm not found. KVM is required for the Android emulator on Linux."
    echo "On Linux, ensure kvm kernel module is loaded: sudo modprobe kvm"
    exit 1
fi

# --- Install the command-line tools when the SDK has none ---

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
    case "$(uname -s)" in
        Darwin) TOOLS_OS="mac" ;;
        *)      TOOLS_OS="linux" ;;
    esac
    echo "==> Installing Android SDK command-line tools into ${SDK}"
    TMP_DIR="$(mktemp -d)"
    curl -fsSL "https://dl.google.com/android/repository/commandlinetools-${TOOLS_OS}-${CMDLINE_TOOLS_VERSION}_latest.zip" \
        -o "$TMP_DIR/cmdline-tools.zip"
    unzip -q "$TMP_DIR/cmdline-tools.zip" -d "$TMP_DIR"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$TMP_DIR/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$TMP_DIR"
fi

# --- Install emulator + system image ---

SYS_IMAGE_PKG="system-images;android-${API_LEVEL};${VARIANT};${ABI}"
echo "==> Installing Android SDK packages"
echo "    System image: ${SYS_IMAGE_PKG}"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --install "emulator" "platform-tools" "${SYS_IMAGE_PKG}" >/dev/null

# --- Build the connect-app emulator APK ---

if [[ -z "$APK" ]]; then
    echo "==> Building :connect-app-emulator"
    (cd "$REPO_ROOT" && ./gradlew -q :connect-app-emulator:assembleDebug)
    APK="$REPO_ROOT/connect-app-emulator/build/outputs/apk/debug/connect-app-emulator-debug.apk"
fi
if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at ${APK}"
    exit 1
fi

# --- Create the AVD once: a Pixel 6 with 2 GB RAM, the profile of the Docker image ---

AVD_DIR="${ANDROID_AVD_HOME:-$HOME/.android/avd}/${AVD_NAME}.avd"
if [[ -d "$AVD_DIR" ]] && ! grep -q "image.sysdir.1=system-images/android-${API_LEVEL}/${VARIANT}/${ABI}/" "$AVD_DIR/config.ini"; then
    echo "==> Recreating AVD ${AVD_NAME} for ${SYS_IMAGE_PKG}"
    "$AVDMANAGER" delete avd -n "$AVD_NAME" >/dev/null
fi
if [[ ! -d "$AVD_DIR" ]]; then
    echo "==> Creating AVD ${AVD_NAME}"
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYS_IMAGE_PKG" -d pixel_6 >/dev/null
    printf 'hw.ramSize=2048\nhw.keyboard=yes\ndisk.dataPartition.size=4G\n' >> "$AVD_DIR/config.ini"
fi

# --- Boot ---

if "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
    echo "==> ${SERIAL} is already running"
else
    EMULATOR_ARGS=(-avd "$AVD_NAME" -port "$CONSOLE_PORT" -no-snapshot-save -no-boot-anim -no-audio)
    if [[ "$HEADLESS" == "true" ]]; then
        EMULATOR_ARGS+=(-no-window -gpu swiftshader_indirect)
    fi
    LOG_FILE="${TMPDIR:-/tmp}/${AVD_NAME}.log"
    echo "==> Booting ${AVD_NAME} as ${SERIAL} (log: ${LOG_FILE})"
    nohup "$SDK/emulator/emulator" "${EMULATOR_ARGS[@]}" >"$LOG_FILE" 2>&1 &
fi

"$ADB" -s "$SERIAL" wait-for-device
WAITED=0
until [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if [[ "$WAITED" -ge "$BOOT_TIMEOUT_SECONDS" ]]; then
        echo "ERROR: ${SERIAL} did not boot within ${BOOT_TIMEOUT_SECONDS} s"
        exit 1
    fi
    sleep 2
    WAITED=$((WAITED + 2))
done

# Animations make UI tests slow and flaky.
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0

"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null

echo ""
echo "==> Done! ${SERIAL} is running with the connect-app emulator installed."
echo ""
echo "Run the instrumented tests against it:"
echo "  ANDROID_SERIAL=${SERIAL} ./gradlew :app:connectedDebugAndroidTest"
echo "Stop it:"
echo "  scripts/start-emulator.sh --stop"
