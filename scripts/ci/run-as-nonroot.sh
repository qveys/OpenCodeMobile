#!/usr/bin/env bash
# OPE-212 — run a command as an unprivileged user inside a throwaway CI job
# container. See docs/adr/0007-ephemeral-nonroot-hostinger-jobs.md.
#
# The container job's outer step runs as root only to repair the ownership of
# the runner-mounted checkout; the command itself runs as uid 10001, and the
# whole container filesystem is discarded when the job ends.
#
# OPE-291 fix (PR #90 CI regressions): the old version left the runner mounts
# owned by the build uid, so the host-side `Post Run actions/checkout`
# cleanup (running as the runner user, not root) failed with
# `Access to the path '.../_temp/_github_workflow/event.json' is denied`,
# and Gradle resolved plugins as HOME=/root (unwritable for uid 10001).
# This version records the pre-job owner of every repaired mount and
# restores it on EXIT (success or failure), and gives Gradle a writable
# GRADLE_USER_HOME under $RUNNER_TEMP.
#
# Usage (from a job that sets `container:` and `defaults.run.shell: bash`):
#   bash scripts/ci/run-as-nonroot.sh bash scripts/check-no-secret-logging.sh
#
# Environment:
#   KEEP_PATH         override the PATH exported to the unprivileged command
#                     (defaults to the caller's PATH).
#   GRADLE_USER_HOME  Gradle user home for the unprivileged command
#                     (defaults to "$RUNNER_TEMP/gradle", then ~/.gradle).
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

user_home="$(getent passwd "$user" | cut -d: -f6)"
[ -n "$user_home" ] || user_home="/home/$user"

# Writable Gradle home for the unprivileged user. $RUNNER_TEMP is per-job but
# shared with the host runner, so it is NEVER chown-ed wholesale: the runner
# keeps `_github_workflow/event.json` there and the host-side checkout cleanup
# cannot delete it once re-owned (only the Gradle subdir below is handed over,
# a sibling of `_github_workflow`, never the temp dir itself).
if [ -z "${GRADLE_USER_HOME:-}" ]; then
  if [ -n "${RUNNER_TEMP:-}" ]; then
    export GRADLE_USER_HOME="$RUNNER_TEMP/gradle"
  else
    export GRADLE_USER_HOME="$user_home/.gradle"
  fi
fi
if [ -d "$GRADLE_USER_HOME" ]; then
  # Remember who should own it back: its own owner if it already exists,
  # else its parent's owner (mkdir below runs as root).
  _gradle_owner="$(stat -c '%u:%g' "$GRADLE_USER_HOME" 2>/dev/null || true)"
else
  _gradle_owner="$(stat -c '%u:%g' "$(dirname "$GRADLE_USER_HOME")" 2>/dev/null || true)"
fi
mkdir -p "$GRADLE_USER_HOME"

# The runner mounts the checkout owned by the runner user. Record the pre-job
# owner of every repaired path so the EXIT trap can hand it back; otherwise
# the host-side checkout cleanup fails with
# "Access to the path ... is denied".
_restore_dirs=""
for _om_dir in "$GITHUB_WORKSPACE" "$GRADLE_USER_HOME"; do
  [ -n "$_om_dir" ] && [ -d "$_om_dir" ] || continue
  if [ "$_om_dir" = "$GRADLE_USER_HOME" ] && [ -n "${_gradle_owner:-}" ]; then
    _om_owner="$_gradle_owner"
  else
    _om_owner="$(stat -c '%u:%g' "$_om_dir" 2>/dev/null || true)"
  fi
  [ -n "$_om_owner" ] || continue
  _restore_dirs="$_restore_dirs$_om_dir:$_om_owner
"
done
unset _om_dir _om_owner _gradle_owner

restore_ownership() {
  # Best effort: never fail the step on the way out.
  while IFS= read -r _om_entry; do
    [ -n "$_om_entry" ] || continue
    _om_dir="${_om_entry%%:*}"
    _om_owner="${_om_entry##*:}"
    chown -R "$_om_owner" "$_om_dir" 2>/dev/null || true
  done <<< "$_restore_dirs"
  unset _om_entry _om_dir _om_owner
}
trap restore_ownership EXIT

chown -R "$user:$user" "$GITHUB_WORKSPACE" "$GRADLE_USER_HOME" 2>/dev/null || true

# `su` resets the environment, so re-export the toolchain variables and cd to
# the checkout inside a script that is passed through safely (handles spaces
# and newlines in the command).
script="$(mktemp)"
{
  printf '#!/usr/bin/env bash\n'
  printf 'set -euo pipefail\n'
  printf 'export PATH=%q\n' "${KEEP_PATH:-$PATH}"
  printf 'export HOME=%q\n' "$user_home"
  printf 'export GRADLE_USER_HOME=%q\n' "$GRADLE_USER_HOME"
  [ -n "${JAVA_HOME:-}" ] && printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
  [ -n "${ANDROID_HOME:-}" ] && printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
  [ -n "${ANDROID_SDK_ROOT:-}" ] && printf 'export ANDROID_SDK_ROOT=%q\n' "$ANDROID_SDK_ROOT"
  [ -n "${CI:-}" ] && printf 'export CI=%q\n' "$CI"
  for _om_proxy in http_proxy https_proxy no_proxy HTTP_PROXY HTTPS_PROXY NO_PROXY; do
    if [ -n "${!_om_proxy:-}" ]; then printf 'export %s=%q\n' "$_om_proxy" "${!_om_proxy}"; fi
  done
  unset _om_proxy
  printf 'cd %q\n' "$GITHUB_WORKSPACE"
  printf '%q ' "$@"
  printf '\n'
} > "$script"
chmod 0755 "$script"
chown "$user:$user" "$script"

status=0
su -s /bin/bash "$user" -c "bash '$script'" || status=$?
rm -f "$script"
# The EXIT trap restores the mount ownership before the step ends.
exit "$status"
