#!/usr/bin/env bash
#
# OPE-98 — source this from a CI step on a company self-hosted runner to get
# the JDK 21 + Android SDK toolchain that
# scripts/ci/provision-runner-toolchain.sh installed.
#
#   . scripts/ci/runner-toolchain-env.sh
#
# It is intentionally dependency-free and safe under `set -euo pipefail`.
# It never fails the calling step: if the toolchain is missing it leaves the
# environment untouched so the step's own `java -version` / Gradle invocation
# produces the clear error.

# 1. Precomputed locations written by the provisioning script (preferred).
for _om_env in "$HOME/.opencode-mobile-runner.env" /etc/opencode-mobile-runner.env; do
  if [ -f "$_om_env" ]; then
    # shellcheck disable=SC1090
    . "$_om_env"
  fi
done
unset _om_env

# 2. JDK 21 — resolve JAVA_HOME if it is still unset (fresh job, env file absent).
_om_jdk="${JAVA_HOME:-}"
if [ -z "$_om_jdk" ] && [ -x /usr/libexec/java_home ]; then
  _om_jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [ -z "$_om_jdk" ] || [ ! -x "$_om_jdk/bin/java" ]; then
  for _om_d in \
    /opt/jdk-21 \
    "$HOME/.local/jdk-21" \
    /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
    "$HOME/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" \
    /usr/lib/jvm/*21* /usr/lib/jvm/java-21-openjdk*; do
    if [ -x "$_om_d/bin/java" ]; then _om_jdk="$_om_d"; break; fi
  done
fi
if [ -n "$_om_jdk" ] && [ -x "$_om_jdk/bin/java" ]; then
  export JAVA_HOME="$_om_jdk"
  case ":$PATH:" in
    *":$JAVA_HOME/bin:"*) ;;
    *) export PATH="$JAVA_HOME/bin:$PATH" ;;
  esac
fi
unset _om_jdk _om_d

# 3. Android SDK — resolve ANDROID_HOME if unset.
_om_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$_om_sdk" ]; then
  for _om_d in /opt/android-sdk "$HOME/android-sdk" "$HOME/Library/Android/sdk" /usr/local/lib/android/sdk; do
    if [ -d "$_om_d" ]; then _om_sdk="$_om_d"; break; fi
  done
fi
if [ -n "$_om_sdk" ] && [ -d "$_om_sdk" ]; then
  export ANDROID_HOME="$_om_sdk"
  export ANDROID_SDK_ROOT="$_om_sdk"
  for _om_sub in "$ANDROID_HOME/cmdline-tools/latest/bin" "$ANDROID_HOME/platform-tools"; do
    case ":$PATH:" in
      *":$_om_sub:"*) ;;
      *) export PATH="$_om_sub:$PATH" ;;
    esac
  done
fi
unset _om_sdk _om_d _om_sub
