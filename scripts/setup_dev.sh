#!/usr/bin/env bash
# Install the local Android/Rust toolchain and build the project dependencies.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Homebrew's rustup package keeps cargo shims outside the default PATH.
for rust_bin in /opt/homebrew/opt/rustup/bin /usr/local/opt/rustup/bin; do
  [[ -d "$rust_bin" ]] && PATH="$rust_bin:$PATH"
done
[[ -d "$HOME/.cargo/bin" ]] && PATH="$HOME/.cargo/bin:$PATH"
export PATH
SDK_VERSION="36"
BUILD_TOOLS_VERSION="36.0.0"
NDK_VERSION="28.2.13676358"
CMAKE_VERSION="3.22.1"
CHECK_ONLY=0
SKIP_SDK=0
SKIP_BUILD=0
BUILD_PLUGIN=0
RUN_APP=0
DEVICE=""

usage() {
  cat <<'EOF'
Usage: scripts/setup_dev.sh [options]

Options:
  --check-only             Check tools without installing or building.
  --skip-sdk               Do not install Android SDK packages.
  --skip-build             Install/check tools only.
  --build-building-plugin Build the optional building/OCR plugin.
  --run                    Install and launch the debug APK after building.
  --device SERIAL          adb serial used with --run (default: first device).
  -h, --help               Show this help.
EOF
}

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '\n==> %s\n' "$*"; }
has() { command -v "$1" >/dev/null 2>&1; }

while (($#)); do
  case "$1" in
    --check-only) CHECK_ONLY=1 ;;
    --skip-sdk) SKIP_SDK=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --build-building-plugin) BUILD_PLUGIN=1 ;;
    --run) RUN_APP=1 ;;
    --device) (($# >= 2)) || die "--device requires a serial"; DEVICE="$2"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1 (use --help)" ;;
  esac
  shift
done

detect_sdk() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    printf '%s' "$ANDROID_SDK_ROOT"; return
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    printf '%s' "$ANDROID_HOME"; return
  fi
  local candidate
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "/opt/homebrew/share/android-commandlinetools" \
    "/usr/local/share/android-commandlinetools"; do
    if [[ -d "$candidate" ]]; then printf '%s' "$candidate"; return; fi
  done
  return 1
}

install_host_tools() {
  if ! has java; then
    if has brew && ((CHECK_ONLY == 0)); then brew install openjdk@17; else die "Java 17+ is required; install a JDK and retry"; fi
  fi
  if ! has rustup; then
    if ((CHECK_ONLY == 0)); then
      if has brew; then brew install rustup; else curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y; fi
      export PATH="$HOME/.cargo/bin:$PATH"
    else die "rustup is required; install it from https://rustup.rs"; fi
  fi
  has cargo || die "cargo is not on PATH; run: source \"$HOME/.cargo/env\""
  if ! has cargo-ndk; then
    ((CHECK_ONLY == 0)) || die "cargo-ndk is missing; run: cargo install cargo-ndk"
    cargo install cargo-ndk
  fi
  if ((CHECK_ONLY == 0)); then rustup default stable >/dev/null; fi
  for target in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
    if ((CHECK_ONLY == 0)); then rustup target add "$target" >/dev/null; fi
  done
}

install_android_sdk() {
  local sdk sdkmanager
  if ! sdk=$(detect_sdk 2>/dev/null); then
    if has brew && ((CHECK_ONLY == 0)); then brew install --cask android-commandlinetools; sdk=$(detect_sdk) || die "Android SDK was installed but could not be located";
    else die "Android SDK not found; set ANDROID_SDK_ROOT or install Android Studio"; fi
  fi
  export ANDROID_SDK_ROOT="$sdk" ANDROID_HOME="$sdk"
  export ANDROID_NDK_HOME="$sdk/ndk/$NDK_VERSION"
  sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "$sdkmanager" ]] || sdkmanager="$(command -v sdkmanager || true)"
  [[ -x "$sdkmanager" ]] || die "sdkmanager not found under $sdk"
  export PATH="$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:$PATH"
  if ((CHECK_ONLY == 0 && SKIP_SDK == 0)); then
    yes | "$sdkmanager" --licenses >/dev/null || true
    "$sdkmanager" "platform-tools" "platforms;android-$SDK_VERSION" "build-tools;$BUILD_TOOLS_VERSION" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION"
  fi
  [[ -d "$sdk/ndk/$NDK_VERSION" ]] || die "NDK $NDK_VERSION is missing"
  if ((CHECK_ONLY == 0)); then printf 'sdk.dir=%s\n' "$sdk" > "$ROOT_DIR/local.properties"; fi
}

build_project() {
  info "Building bundled Root server"
  (cd "$ROOT_DIR/third_party/sunserver" && ./gradlew assembleDebug --no-daemon)
  cp "$ROOT_DIR/third_party/sunserver/app/build/outputs/apk/debug/app-debug.apk" "$ROOT_DIR/app/src/main/assets/server.apk"
  if ((BUILD_PLUGIN)); then
    info "Building optional building plugin"
    (cd "$ROOT_DIR/third_party/building_plugin" && ./gradlew assembleDebug --no-daemon)
  fi
  info "Building main application"
  (cd "$ROOT_DIR" && ./gradlew assembleDebug --no-daemon)
}

run_app() {
  local args=(-Pdevice="${DEVICE:-}")
  if [[ -n "$DEVICE" ]]; then args=(-Pdevice="$DEVICE"); else args=(); fi
  (cd "$ROOT_DIR" && ./gradlew "${args[@]}" runDebugOnDevice --no-daemon)
}

info "Checking host tools"
install_host_tools
if ((SKIP_SDK == 0)); then install_android_sdk; else info "Skipping Android SDK package installation"; fi
if ((CHECK_ONLY)); then info "Environment check completed"; exit 0; fi
if ((SKIP_BUILD == 0)); then build_project; fi
if ((RUN_APP)); then run_app; fi
info "Done. Start on a rooted emulator with: ./gradlew -Pdevice=emulator-5554 runDebugOnDevice"
