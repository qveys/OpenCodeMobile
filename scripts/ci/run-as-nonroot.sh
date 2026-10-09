#!/usr/bin/env bash
# OPE-212 — run a command as an unprivileged user inside a throwaway CI job
# container. See docs/adr/0007-ephemeral-nonroot-hostinger-jobs.md.
#
# The container job's outer step runs as root only to repair the ownership of
# the runner-mounted checkout; the command itself runs as uid 10001, and the
# whole container filesystem is discarded when the job ends.
#
# Usage (from a job that sets `container:` and `defaults.run.shell: bash`):
#   bash scripts/ci/run-as-nonroot.sh bash scripts/check-no-secret-logging.sh
#
# Environment:
#   KEEP_PATH   override the PATH exported to the unprivileged command
#               (defaults to the caller's PATH).
#   PRESERVE_ENV comma-separated variable names to forward to the child. Use
#               only for trusted jobs that need deployment credentials.
set -euo pipefail

if [ -z "${GITHUB_WORKSPACE:-}" ]; then
  echo "run-as-nonroot: GITHUB_WORKSPACE is unset; run this from a job step" >&2
  exit 2
fi
if [ "$#" -eq 0 ]; then
  echo "usage: run-as-nonroot.sh <command> [args...]" >&2
  exit 2
fi

user=builder
uid=10001

if ! id -u "$user" >/dev/null 2>&1; then
  if command -v useradd >/dev/null 2>&1; then
    useradd -m -u "$uid" -s /bin/bash "$user"
  else
    echo "run-as-nonroot: useradd is unavailable in this image" >&2
    exit 3
  fi
fi

# The runner mounts _work (and /__w/_temp) owned by root. Hand the checkout to
# the unprivileged user so checkout and the build can write there.
chown -R "$user:$user" "$GITHUB_WORKSPACE" /__w/_temp 2>/dev/null || true

# `su` resets the environment, so re-export the toolchain variables and cd to
# the checkout inside a script that is passed through safely (handles spaces
# and newlines in the command).
script="$(mktemp)"
{
  printf '#!/usr/bin/env bash\n'
  printf 'set -euo pipefail\n'
  printf 'export PATH=%q\n' "${KEEP_PATH:-$PATH}"
  [ -n "${JAVA_HOME:-}" ] && printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
  [ -n "${ANDROID_HOME:-}" ] && printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
  [ -n "${ANDROID_SDK_ROOT:-}" ] && printf 'export ANDROID_SDK_ROOT=%q\n' "$ANDROID_SDK_ROOT"
  if [ -n "${PRESERVE_ENV:-}" ]; then
    IFS=',' read -r -a preserve_names <<< "$PRESERVE_ENV"
    for name in "${preserve_names[@]}"; do
      if [[ ! "$name" =~ ^[A-Z_][A-Z0-9_]*$ ]]; then
        echo "run-as-nonroot: invalid PRESERVE_ENV name '$name'" >&2
        exit 2
      fi
      if [[ -v "$name" ]]; then
        printf -v value '%s' "${!name}"
        printf 'export %s=%q\n' "$name" "$value"
      fi
    done
  fi
  printf 'cd %q\n' "$GITHUB_WORKSPACE"
  printf '%q ' "$@"
  printf '\n'
} > "$script"
chmod 0755 "$script"
chown "$user:$user" "$script"

status=0
su -s /bin/bash "$user" -c "bash '$script'" || status=$?
rm -f "$script"
exit "$status"
