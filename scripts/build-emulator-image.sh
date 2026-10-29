#!/usr/bin/env bash
set -euo pipefail

# Builds a Docker image with a headless Android emulator and the connect-app emulator
# (:connect-app-emulator) installed. Start a container, connect adb, and run the instrumented tests.
# Requires: Docker, KVM (/dev/kvm), and a JDK 17-21 plus Android SDK for the Gradle build
# (or pass --apk).
#
# Usage:
#   scripts/build-emulator-image.sh                           # default: API 34 aosp_atd x86_64
#   scripts/build-emulator-image.sh --api 35                  # specific API level
#   scripts/build-emulator-image.sh --list                    # list available system images
#   scripts/build-emulator-image.sh --variant google_apis     # another system image variant
#   scripts/build-emulator-image.sh --apk path/to/emulator.apk  # use a built APK, skip Gradle
#   scripts/build-emulator-image.sh --clean                   # remove build context after build

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="${REPO_ROOT}/.android-emulator-build"
IMAGE_NAME="connect-app-emulator"

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"

MAESTRO_RUNNER_VERSION="1.1.12"
MAESTRO_RUNNER_SHA256="8a209214bbd9afddd9fba4e323c973bebdfac4c4f8be836073b9c350258315f2"

# Defaults
API_LEVEL="34"
ABI="x86_64"
VARIANT="aosp_atd"
APK=""
LIST_ONLY=false
CLEAN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --api)      API_LEVEL="$2"; shift 2 ;;
        --abi)      ABI="$2"; shift 2 ;;
        --variant)  VARIANT="$2"; shift 2 ;;
        --apk)      APK="$2"; shift 2 ;;
        --list)     LIST_ONLY=true; shift ;;
        --clean)    CLEAN=true; shift ;;
        --help|-h)
            head -15 "$0" | tail -14
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# --- Preflight checks ---

if [[ ! -e /dev/kvm ]]; then
    echo "ERROR: /dev/kvm not found. KVM is required for Android emulator images."
    echo "On Linux, ensure kvm kernel module is loaded: sudo modprobe kvm"
    exit 1
fi

if ! command -v docker &>/dev/null; then
    echo "ERROR: docker is not installed or not on PATH"
    exit 1
fi

# --- Install SDK command-line tools ---

ANDROID_SDK_ROOT="$WORK_DIR/sdk"
export ANDROID_SDK_ROOT
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -x "$SDKMANAGER" ]]; then
    echo "==> Downloading Android SDK command-line tools..."
    mkdir -p "$WORK_DIR"
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$WORK_DIR/cmdline-tools.zip"
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    unzip -q -o "$WORK_DIR/cmdline-tools.zip" -d "$WORK_DIR/cmdline-tools-tmp"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$WORK_DIR/cmdline-tools-tmp/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -rf "$WORK_DIR/cmdline-tools-tmp" "$WORK_DIR/cmdline-tools.zip"
fi

# Accept all licenses
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# --- List mode ---

if [[ "$LIST_ONLY" == "true" ]]; then
    echo "==> Available system images:"
    "$SDKMANAGER" --list 2>/dev/null | grep "system-images;" | grep "${ABI}" || true
    exit 0
fi

# --- Build the connect-app emulator APK ---

if [[ -z "$APK" ]]; then
    echo "==> Building :connect-app-emulator"
    (cd "$REPO_ROOT" && ./gradlew :connect-app-emulator:assembleDebug)
    APK="$REPO_ROOT/connect-app-emulator/build/outputs/apk/debug/connect-app-emulator-debug.apk"
fi

if [[ ! -f "$APK" ]]; then
    echo "ERROR: APK not found at ${APK}"
    exit 1
fi

# --- Install emulator + system image ---

SYS_IMAGE_PKG="system-images;android-${API_LEVEL};${VARIANT};${ABI}"
TAG="${IMAGE_NAME}:api-${API_LEVEL}-${VARIANT}-${ABI}"

echo "==> Installing Android SDK packages"
echo "    System image: ${SYS_IMAGE_PKG}"
echo "    Image tag:    ${TAG}"
echo ""

"$SDKMANAGER" --install "emulator" "platform-tools" "${SYS_IMAGE_PKG}"

# --- Locate installed files ---

EMU_DIR="$ANDROID_SDK_ROOT/emulator"
SYS_IMG_DIR="$ANDROID_SDK_ROOT/system-images/android-${API_LEVEL}/${VARIANT}/${ABI}"

if [[ ! -d "$SYS_IMG_DIR" ]]; then
    echo "ERROR: System image not found at ${SYS_IMG_DIR}"
    echo "Run with --list to see available images."
    exit 1
fi

# --- Build Docker image ---

DOCKER_BUILD_DIR="$WORK_DIR/docker-build"
rm -rf "$DOCKER_BUILD_DIR"
mkdir -p "$DOCKER_BUILD_DIR"

echo "==> Preparing Docker build context..."
cp -r "$EMU_DIR" "$DOCKER_BUILD_DIR/emulator"
cp -r "$SYS_IMG_DIR" "$DOCKER_BUILD_DIR/system-image"
cp -r "$ANDROID_SDK_ROOT/platform-tools" "$DOCKER_BUILD_DIR/platform-tools"
cp -r "$ANDROID_SDK_ROOT/cmdline-tools" "$DOCKER_BUILD_DIR/cmdline-tools"
cp "$APK" "$DOCKER_BUILD_DIR/connect-app-emulator.apk"

# --- Download pinned maestro-runner (linux-amd64), for UI flows against the emulator ---
MAESTRO_RUNNER_TARBALL="$WORK_DIR/maestro-runner-${MAESTRO_RUNNER_VERSION}-linux-amd64.tar.gz"
MAESTRO_RUNNER_URL="https://github.com/devicelab-dev/maestro-runner/releases/download/v${MAESTRO_RUNNER_VERSION}/maestro-runner-${MAESTRO_RUNNER_VERSION}-linux-amd64.tar.gz"
if [[ ! -f "$MAESTRO_RUNNER_TARBALL" ]]; then
    echo "==> Downloading maestro-runner v${MAESTRO_RUNNER_VERSION}..."
    curl -fsSL -o "$MAESTRO_RUNNER_TARBALL" "$MAESTRO_RUNNER_URL"
fi
ACTUAL_SHA256=$(sha256sum "$MAESTRO_RUNNER_TARBALL" | awk '{print $1}')
if [[ "$ACTUAL_SHA256" != "$MAESTRO_RUNNER_SHA256" ]]; then
    echo "ERROR: maestro-runner checksum mismatch" >&2
    echo "  expected: $MAESTRO_RUNNER_SHA256" >&2
    echo "  actual:   $ACTUAL_SHA256" >&2
    exit 1
fi
cp "$MAESTRO_RUNNER_TARBALL" "$DOCKER_BUILD_DIR/maestro-runner.tar.gz"

cat > "$DOCKER_BUILD_DIR/entrypoint.sh" << 'ENTRYPOINT_EOF'
#!/usr/bin/env bash
set -eu
# Match container kvm group gid to the host's /dev/kvm gid so the android user can access it
if [[ -e /dev/kvm ]]; then
    HOST_KVM_GID=$(stat -c '%g' /dev/kvm)
    CURRENT_KVM_GID=$(getent group kvm | cut -d: -f3)
    if [[ "$HOST_KVM_GID" != "$CURRENT_KVM_GID" ]]; then
        groupmod -g "$HOST_KVM_GID" kvm
    fi
fi
exec runuser -u android -- /opt/start-emulator.sh
ENTRYPOINT_EOF
chmod +x "$DOCKER_BUILD_DIR/entrypoint.sh"

cat > "$DOCKER_BUILD_DIR/start-emulator.sh" << 'START_EOF'
#!/usr/bin/env bash
set -eu
# Boots the emulator headless, installs the connect-app emulator, and forwards the emulator's adb
# port to 0.0.0.0:5556. Publish it on a host port outside 5555-5585, for example `-p 15555:5556`:
# the host adb server probes those ports for local emulators and would list the device twice.
rm -f /tmp/emulator-ready
emulator -avd connect -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
    -no-snapshot-save -ports 5554,5555 &
EMULATOR_PID=$!

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
done

# Animations make UI tests slow and flaky.
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb install -r /opt/connect-app-emulator.apk
echo "==> Emulator booted, connect-app emulator installed. Connect with: adb connect localhost:15555"
touch /tmp/emulator-ready

socat TCP-LISTEN:5556,fork,reuseaddr TCP:127.0.0.1:5555 &
wait "$EMULATOR_PID"
START_EOF
chmod +x "$DOCKER_BUILD_DIR/start-emulator.sh"

cat > "$DOCKER_BUILD_DIR/Dockerfile" << DOCKERFILE_EOF
FROM debian:bookworm-slim

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_AVD_HOME=/home/android/.android/avd
ENV PATH="\${ANDROID_SDK_ROOT}/emulator:\${ANDROID_SDK_ROOT}/platform-tools:\${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:\${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \\
        libx11-6 libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 \\
        libxi6 libxrandr2 libxrender1 libxtst6 libfreetype6 \\
        libglib2.0-0 libxkbfile1 procps qemu-utils socat default-jdk-headless \\
    && rm -rf /var/lib/apt/lists/*

# Install SDK components
COPY emulator       \${ANDROID_SDK_ROOT}/emulator/
COPY platform-tools \${ANDROID_SDK_ROOT}/platform-tools/
COPY system-image   \${ANDROID_SDK_ROOT}/system-images/android-${API_LEVEL}/${VARIANT}/${ABI}/
COPY cmdline-tools  \${ANDROID_SDK_ROOT}/cmdline-tools/

# Install pinned maestro-runner (extracts to /opt/maestro-runner/)
COPY maestro-runner.tar.gz /tmp/maestro-runner.tar.gz
RUN echo "${MAESTRO_RUNNER_SHA256}  /tmp/maestro-runner.tar.gz" | sha256sum -c - \\
    && tar -xzf /tmp/maestro-runner.tar.gz --exclude='._*' -C /opt/ \\
    && ln -s /opt/maestro-runner/bin/maestro-runner /usr/local/bin/maestro-runner \\
    && rm /tmp/maestro-runner.tar.gz

# Non-root user with kvm group (gid adjusted at runtime by entrypoint)
RUN groupadd -r kvm && groupadd -r android && useradd -r -g android -G kvm -m android

# Create the AVD at build time: a Pixel 6 with 2 GB RAM, the same profile Rootline tests with.
USER android
RUN echo no | avdmanager create avd -n connect -k "system-images;android-${API_LEVEL};${VARIANT};${ABI}" -d pixel_6 \\
    && printf 'hw.ramSize=2048\nhw.keyboard=yes\ndisk.dataPartition.size=4G\n' >> \${ANDROID_AVD_HOME}/connect.avd/config.ini
USER root

COPY connect-app-emulator.apk /opt/
COPY entrypoint.sh start-emulator.sh /opt/

EXPOSE 5556
HEALTHCHECK --interval=10s --start-period=60s CMD test -f /tmp/emulator-ready
ENTRYPOINT ["/opt/entrypoint.sh"]
DOCKERFILE_EOF

echo ""
echo "==> Building Docker image: ${TAG}"
docker build -t "${TAG}" "$DOCKER_BUILD_DIR"

if [[ "$CLEAN" == "true" ]]; then
    echo ""
    echo "==> Cleaning up build context..."
    rm -rf "$DOCKER_BUILD_DIR"
    echo "    Removed ${DOCKER_BUILD_DIR}"
fi

echo ""
echo "==> Done! Image available as: ${TAG}"
echo ""
echo "Run the instrumented tests against it (requires KVM):"
echo "  docker run -d --name connect-emulator --device /dev/kvm --shm-size=2g -p 15555:5556 ${TAG}"
echo "  # wait until 'docker ps' shows the container as healthy (about a minute)"
echo "  adb connect localhost:15555"
echo "  ./gradlew :app:connectedDebugAndroidTest"
