#!/usr/bin/env bash
#
# OPE-98 — provision the JDK 21 + Android SDK toolchain on a company
# self-hosted GitHub Actions runner (Linux "hostinger" VPS or macOS MacBook).
#
# WHY A SCRIPT ON THE RUNNER:
#   The repository Actions allowlist permits only `actions/checkout@*`. Every
#   other marketplace action (`actions/setup-java`, `gradle/actions/setup-gradle`,
#   `android-actions/setup-android`) is rejected with `startup_failure` before a
#   job can start. The Gradle toolchain therefore has to be physically present
#   on the persistent runner machines, and the only channel we have to those
#   machines is a workflow job — not SSH. This script is that job's payload.
#
#   It is idempotent: run it as often as you like. Already-present components
#   are detected and skipped.
#
# USAGE (inside a job running on the runner, from the repo root):
#   bash scripts/ci/provision-runner-toolchain.sh
#
# WHAT IT GUARANTEES:
#   - JDK 21 discoverable via JAVA_HOME, `java` on PATH, and (macOS)
#     /usr/libexec/java_home -v 21.
#   - Android SDK (platform-tools, platforms;android-35, build-tools;35.0.0)
#     at a fixed location, exported through scripts/ci/runner-toolchain-env.sh.
#   - $HOME/.opencode-mobile-runner.env and /etc/opencode-mobile-runner.env
#     describing those locations for future jobs / operator shells.
#
# CI steps should NOT call this on every run; they source
# scripts/ci/runner-toolchain-env.sh instead. This script is the one-time (or
# re-run-on-demand) provisioning entry point driven by
# .github/workflows/provision-runner-toolchain.yml.

set -uo pipefail

# The GitHub runner executes Linux self-hosted jobs as `root` (via the runner's
# systemd unit), where HOME can be unset. Everything below relies on HOME, so
# establish a sane default before anything else.
if [ -z "${HOME:-}" ] || [ ! -d "${HOME:-}" ]; then
  if [ "$(id -u)" -eq 0 ]; then HOME=/root; else HOME="$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6)"; fi
  [ -n "${HOME:-}" ] && [ -d "$HOME" ] || HOME=/tmp
  export HOME
fi

log()  { printf '\n=== %s ===\n' "$*"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '::warning::%s\n' "$*"; }
err()  { printf '::error::%s\n' "$*"; }

OS="$(uname -s)"
ARCH_RAW="$(uname -m)"
case "$ARCH_RAW" in
  x86_64|amd64)   TEMURIN_ARCH=x64 ;;
  arm64|aarch64)  TEMURIN_ARCH=aarch64 ;;
  *)              TEMURIN_ARCH="$ARCH_RAW" ;;
esac

# ---------------------------------------------------------------------------
# 0. Diagnostics — always printed so a failed provisioning run is self-explaining
# ---------------------------------------------------------------------------
log "Runner diagnostics"
info "host=$(hostname 2>/dev/null || echo '?') user=$(id -un) uid=$(id -u)"
info "os=$OS arch=$ARCH_RAW kernel=$(uname -r)"
info "HOME=$HOME"
info "PATH=$PATH"
for c in curl wget tar unzip python3 jar brew apt-get dnf yum java sdkmanager; do
  if command -v "$c" >/dev/null 2>&1; then info "$c=$(command -v "$c")"; else info "$c=MISSING"; fi
done
[ -x /usr/libexec/java_home ] && info "/usr/libexec/java_home=present" || info "/usr/libexec/java_home=absent"
[ -n "${RUNNER_NAME:-}" ] && info "RUNNER_NAME=$RUNNER_NAME" || true
[ -n "${RUNNER_WORKSPACE:-}" ] && info "RUNNER_WORKSPACE=$RUNNER_WORKSPACE" || true

# Passwordless root capability (sudo -n) decides where we may install.
CAN_ROOT=0
if [ "$(id -u)" -eq 0 ]; then
  CAN_ROOT=1
elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  CAN_ROOT=1
fi
SUDO=""
if [ "$CAN_ROOT" -eq 1 ] && [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi
info "passwordless root capability: $CAN_ROOT (sudo='$SUDO')"

run_root() {
  if [ "$(id -u)" -eq 0 ]; then "$@"
  elif [ -n "$SUDO" ]; then "$@"
  else return 1
  fi
}

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
download() { # url out
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 5 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$2" "$1"
  else
    return 1
  fi
}

extract_zip() { # zip dest
  mkdir -p "$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$1" -d "$2"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$1" "$2"
  elif command -v jar >/dev/null 2>&1; then
    ( cd "$2" && jar xf "$1" )
  else
    return 1
  fi
}

is_jdk21() { # dir
  [ -n "${1:-}" ] && [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'
}

find_jdk21() {
  local d
  if [ -n "${JAVA_HOME:-}" ] && is_jdk21 "$JAVA_HOME"; then printf '%s' "$JAVA_HOME"; return 0; fi
  if [ -x /usr/libexec/java_home ]; then
    d="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$d" ] && is_jdk21 "$d"; then printf '%s' "$d"; return 0; fi
  fi
  for d in \
    /opt/jdk-21 \
    "$HOME/.local/jdk-21" \
    /Library/Java/JavaVirtualMachines/*/Contents/Home \
    "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
    /usr/lib/jvm/*21* /usr/lib/jvm/java-21-openjdk*; do
    if is_jdk21 "$d"; then printf '%s' "$d"; return 0; fi
  done
  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21'; then
    d="$(dirname "$(dirname "$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)")")"
    if is_jdk21 "$d"; then printf '%s' "$d"; return 0; fi
  fi
  return 1
}

# ---------------------------------------------------------------------------
# 1. JDK 21
# ---------------------------------------------------------------------------
JDK_HOME_FINAL=""
install_jdk21() {
  if JDK_HOME_FINAL="$(find_jdk21)"; then
    info "JDK 21 already present: $JDK_HOME_FINAL"
    return 0
  fi
  log "Installing JDK 21 (Temurin)"

  # Linux fast path: distro package.
  if [ "$OS" = "Linux" ] && [ "$CAN_ROOT" -eq 1 ] && command -v apt-get >/dev/null 2>&1; then
    info "trying apt-get openjdk-21-jdk-headless"
    if run_root apt-get update -qq >/dev/null 2>&1 \
       && run_root apt-get install -y -qq openjdk-21-jdk-headless >/dev/null 2>&1; then
      if JDK_HOME_FINAL="$(find_jdk21)"; then
        info "JDK 21 installed via apt: $JDK_HOME_FINAL"
        return 0
      fi
    fi
    info "apt path unavailable; falling back to Temurin tarball"
  fi

  # macOS fast path: Homebrew.
  if [ "$OS" = "Darwin" ] && command -v brew >/dev/null 2>&1; then
    info "trying Homebrew openjdk@21"
    if brew list --versions openjdk@21 >/dev/null 2>&1 || brew install openjdk@21 >/dev/null 2>&1; then
      local bp
      bp="$(brew --prefix openjdk@21 2>/dev/null || true)"
      if [ -n "$bp" ] && is_jdk21 "$bp"; then
        if [ "$CAN_ROOT" -eq 1 ]; then
          run_root mkdir -p /Library/Java/JavaVirtualMachines
          run_root ln -sfn "$bp/libexec/openjdk.jdk" /Library/Java/JavaVirtualMachines/openjdk-21.jdk
        fi
        JDK_HOME_FINAL="$bp"
        info "JDK 21 installed via Homebrew: $bp"
        return 0
      fi
    fi
    info "Homebrew path unavailable; falling back to Temurin tarball"
  fi

  # Universal fallback: a Temurin/Corretto/Microsoft JDK tarball. Several
  # independent mirrors are tried so a blocked or moved URL does not fail the
  # whole provisioning run.
  local os_seg target tmp src url got
  case "$OS" in
    Linux)  os_seg=linux ;;
    Darwin) os_seg=mac ;;
    *) warn "unsupported OS: $OS"; return 1 ;;
  esac
  if [ "$CAN_ROOT" -eq 1 ] && [ "$OS" = "Linux" ]; then
    target=/opt/jdk-21
  elif [ "$CAN_ROOT" -eq 1 ] && [ "$OS" = "Darwin" ]; then
    target=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
  else
    target="$HOME/.local/jdk-21"
  fi
  tmp="$(mktemp -d)"
  got=0
  for url in \
    "https://api.adoptium.net/v3/binary/latest/21/ga/${os_seg}/${TEMURIN_ARCH}/jdk/hotspot/normal/eclipse" \
    "https://corretto.aws/downloads/latest/amazon-corretto-21-${TEMURIN_ARCH}-${os_seg}-jdk.tar.gz" \
    "https://aka.ms/download-jdk/microsoft-jdk-21-${os_seg}-${TEMURIN_ARCH}.tar.gz"; do
    info "downloading $url"
    if download "$url" "$tmp/jdk21.tar.gz" && tar -tzf "$tmp/jdk21.tar.gz" >/dev/null 2>&1; then
      got=1; break
    fi
    warn "source unavailable: $url"
  done
  if [ "$got" -ne 1 ]; then
    warn "could not download a JDK 21 tarball from any known mirror"
    rm -rf "$tmp"; return 1
  fi
  mkdir -p "$tmp/x"
  if ! tar -xzf "$tmp/jdk21.tar.gz" -C "$tmp/x"; then
    warn "tar extraction failed"
    rm -rf "$tmp"; return 1
  fi
  # Layout-agnostic: Linux tarballs are <jdk>/bin/java, macOS are
  # <jdk>/Contents/Home/bin/java.
  src="$(find "$tmp/x" -maxdepth 5 -type f -path '*/bin/java' 2>/dev/null | head -n1)"
  if [ -n "$src" ]; then src="$(dirname "$(dirname "$src")")"; fi
  if [ -z "$src" ] || [ ! -x "$src/bin/java" ]; then
    warn "could not locate an extracted JDK under $tmp/x"
    rm -rf "$tmp"; return 1
  fi
  info "installing to $target"
  run_root rm -rf "$target" 2>/dev/null || rm -rf "$target"
  run_root mkdir -p "$(dirname "$target")" 2>/dev/null || mkdir -p "$(dirname "$target")"
  if [ "$CAN_ROOT" -eq 1 ]; then
    run_root cp -R "$src" "$target"
  else
    cp -R "$src" "$target"
  fi
  rm -rf "$tmp"
  if ! is_jdk21 "$target"; then warn "installed JDK at $target but it does not report 21"; return 1; fi
  JDK_HOME_FINAL="$target"
  info "JDK 21 installed at $target"
}

expose_jdk() { # home
  local home="$1" tool
  if [ "$CAN_ROOT" -eq 1 ]; then
    run_root mkdir -p /usr/local/bin 2>/dev/null || true
    for tool in java javac jar keytool; do
      [ -x "$home/bin/$tool" ] && { run_root ln -sfn "$home/bin/$tool" "/usr/local/bin/$tool" 2>/dev/null || warn "could not symlink /usr/local/bin/$tool"; }
    done
  fi
  # user-local fallback so a shell that only has $HOME/.local/bin still resolves java
  mkdir -p "$HOME/.local/bin"
  for tool in java javac jar keytool; do
    [ -x "$home/bin/$tool" ] && ln -sfn "$home/bin/$tool" "$HOME/.local/bin/$tool"
  done
  export JAVA_HOME="$home"
  export PATH="$home/bin:$HOME/.local/bin:$PATH"
  info "java -> $(command -v java || echo MISSING)"
}

# ---------------------------------------------------------------------------
# 2. Android SDK
# ---------------------------------------------------------------------------
# Command-line tools build; platform 35 + build-tools 35 match the project's
# compileSdk = 35 (see androidApp/build.gradle.kts).
COMMAND_LINE_TOOLS_BUILD="${COMMAND_LINE_TOOLS_BUILD:-11076708}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-35}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}"
SDK_HOME_FINAL=""

install_android_sdk() {
  if [ -n "${ANDROID_HOME:-}" ] \
     && [ -d "$ANDROID_HOME/platforms/$ANDROID_PLATFORM" ] \
     && [ -d "$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS" ]; then
    info "Android SDK already present: $ANDROID_HOME"
    SDK_HOME_FINAL="$ANDROID_HOME"
    return 0
  fi
  log "Installing Android SDK command-line tools + $ANDROID_PLATFORM"

  local os_seg sdk
  case "$OS" in
    Linux)
      os_seg=linux
      sdk=/opt/android-sdk
      [ "$CAN_ROOT" -eq 1 ] || sdk="$HOME/android-sdk"
      ;;
    Darwin)
      os_seg=mac
      sdk="$HOME/Library/Android/sdk"
      ;;
    *) warn "unsupported OS: $OS"; return 1 ;;
  esac
  # Reuse an existing (possibly partial) SDK if one is already configured.
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then sdk="$ANDROID_HOME"; fi

  if [ "$CAN_ROOT" -eq 1 ] && [ "$sdk" = "/opt/android-sdk" ]; then
    run_root mkdir -p "$sdk/cmdline-tools"
    # let the runner user manage packages in this tree
    run_root chown -R "$(id -u):$(id -g)" "$sdk" 2>/dev/null || true
  else
    mkdir -p "$sdk/cmdline-tools" "$sdk"
  fi

  if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    local tmp url
    tmp="$(mktemp -d)"
    url="https://dl.google.com/android/repository/commandlinetools-${os_seg}-${COMMAND_LINE_TOOLS_BUILD}_latest.zip"
    info "downloading $url"
    if ! download "$url" "$tmp/clt.zip"; then
      warn "could not download Android command-line tools"
      rm -rf "$tmp"; return 1
    fi
    if ! extract_zip "$tmp/clt.zip" "$sdk/cmdline-tools"; then
      warn "could not unzip Android command-line tools"
      rm -rf "$tmp"; return 1
    fi
    rm -rf "$tmp"
    run_root rm -rf "$sdk/cmdline-tools/latest" 2>/dev/null || rm -rf "$sdk/cmdline-tools/latest"
    mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
  fi

  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export PATH="$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:$PATH"

  info "accepting Android SDK licenses"
  if command -v yes >/dev/null 2>&1; then
    yes | sdkmanager --sdk_root="$sdk" --licenses >/dev/null 2>&1 || warn "license acceptance returned non-zero (continuing)"
  else
    printf 'y\ny\ny\ny\ny\ny\ny\ny\ny\ny\n' | sdkmanager --sdk_root="$sdk" --licenses >/dev/null 2>&1 || true
  fi

  info "installing platform-tools, platforms;$ANDROID_PLATFORM, build-tools;$ANDROID_BUILD_TOOLS"
  if ! sdkmanager --sdk_root="$sdk" "platform-tools" "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS"; then
    warn "sdkmanager failed to install packages"
    return 1
  fi
  SDK_HOME_FINAL="$sdk"
  info "Android SDK installed at $sdk"
}

# ---------------------------------------------------------------------------
# 3. Persist locations for future jobs and operator shells
# ---------------------------------------------------------------------------
write_env() { # jdk sdk
  local jdk="$1" sdk="$2" envf block
  envf="$HOME/.opencode-mobile-runner.env"
  {
    echo "# Generated by scripts/ci/provision-runner-toolchain.sh (OPE-98). Do not edit by hand."
    echo "export JAVA_HOME=\"$jdk\""
    echo "export PATH=\"$jdk/bin:\$PATH\""
    if [ -n "$sdk" ]; then
      echo "export ANDROID_HOME=\"$sdk\""
      echo "export ANDROID_SDK_ROOT=\"$sdk\""
      echo "export PATH=\"$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:\$PATH\""
    fi
  } > "$envf"
  info "wrote $envf"

  if [ "$CAN_ROOT" -eq 1 ]; then
    block="$(mktemp)"
    {
      echo "# Generated by scripts/ci/provision-runner-toolchain.sh (OPE-98)."
      echo "export JAVA_HOME=\"$jdk\""
      echo "export PATH=\"$jdk/bin:\$PATH\""
      if [ -n "$sdk" ]; then
        echo "export ANDROID_HOME=\"$sdk\""
        echo "export ANDROID_SDK_ROOT=\"$sdk\""
        echo "export PATH=\"$sdk/cmdline-tools/latest/bin:$sdk/platform-tools:\$PATH\""
      fi
    } > "$block"
    run_root cp "$block" /etc/opencode-mobile-runner.env 2>/dev/null || warn "could not write /etc/opencode-mobile-runner.env"
    if [ "$OS" = "Linux" ]; then
      run_root mkdir -p /etc/profile.d 2>/dev/null || true
      printf '%s\n' '[ -f /etc/opencode-mobile-runner.env ] && . /etc/opencode-mobile-runner.env' \
        | run_root tee /etc/profile.d/opencode-mobile-runner.sh >/dev/null 2>&1 \
        || warn "could not write /etc/profile.d/opencode-mobile-runner.sh"
    fi
    rm -f "$block"
  fi

  if [ "$OS" = "Darwin" ]; then
    local rc line
    line='[ -f "$HOME/.opencode-mobile-runner.env" ] && . "$HOME/.opencode-mobile-runner.env"'
    for rc in "$HOME/.bash_profile" "$HOME/.zprofile" "$HOME/.zshrc"; do
      touch "$rc" 2>/dev/null || continue
      grep -qF 'opencode-mobile-runner.env' "$rc" 2>/dev/null || printf '%s\n' "$line" >> "$rc" 2>/dev/null || true
    done
  fi

  # Best-effort: the GitHub runner also reads an `.env` file from its install
  # directory at startup. Appending here helps after a runner restart, but CI
  # never relies on it — scripts/ci/runner-toolchain-env.sh is authoritative.
  local runner_env
  runner_env="$(find "$HOME" -maxdepth 3 -name '.env' -path '*actions-runner*' 2>/dev/null | head -n1 || true)"
  if [ -n "$runner_env" ] && [ -w "$runner_env" ]; then
    {
      echo "JAVA_HOME=$jdk"
      [ -n "$sdk" ] && echo "ANDROID_HOME=$sdk"
      [ -n "$sdk" ] && echo "ANDROID_SDK_ROOT=$sdk"
    } >> "$runner_env"
    info "appended toolchain paths to $runner_env (effective after runner restart)"
  fi
}

# ---------------------------------------------------------------------------
# 4. Run
# ---------------------------------------------------------------------------
install_jdk21 || { err "JDK 21 provisioning failed"; exit 1; }
expose_jdk "$JDK_HOME_FINAL"

install_android_sdk || { err "Android SDK provisioning failed"; exit 1; }
export ANDROID_HOME="$SDK_HOME_FINAL"
export ANDROID_SDK_ROOT="$SDK_HOME_FINAL"

write_env "$JDK_HOME_FINAL" "$SDK_HOME_FINAL"

log "Verification"
info "JAVA_HOME=$JDK_HOME_FINAL"
java -version 2>&1 | sed 's/^/  /' || true
info "ANDROID_HOME=$SDK_HOME_FINAL"
"$SDK_HOME_FINAL/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_HOME_FINAL" --list_installed 2>/dev/null | sed 's/^/  /' || true

log "Provisioning complete"
