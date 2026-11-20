#!/usr/bin/env bash
# Restore bind-mounted checkout paths to the owner observed on the host runner.
set -euo pipefail

# Container id 0 must map to a non-zero host id. The range may be wider than 1
# (large subuid, e.g. "0 100000 65536"). Host id 0 is real root and is refused,
# as is any missing, unreadable, empty, or malformed map. Only the line whose
# first field is 0 counts; a second such line, including any "0 0 …", refuses.
namespace_root_maps_to_host_user() {
  local map_file=$1
  [[ -r "$map_file" ]] && awk '
    $1 == "0" {
      entries++
      if (NF == 3 && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ && $2 != 0 && $3 >= 1) {
        valid = 1
      }
    }
    END { exit !(entries == 1 && valid == 1) }
  ' "$map_file"
}

# scripts/tests sources this file to call the check without chown.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi

if [[ $# -lt 2 ]]; then
  echo '::error::Usage: restore-runner-workspace-ownership.sh OWNER_SOURCE WORKSPACE [PATH ...]'
  exit 2
fi

owner_source=$1
workspace=$2
shift 2

[[ -d "$workspace" ]] || exit 0
if [[ ! -e "$owner_source" ]]; then
  echo "::error::Runner owner source is missing: $owner_source"
  exit 1
fi

owner=$(stat -c '%u:%g' "$owner_source")
if [[ ! "$owner" =~ ^[0-9]+:[0-9]+$ ]]; then
  echo "::error::Could not determine numeric runner owner from $owner_source."
  exit 1
fi
uid=${owner%%:*}
gid=${owner##*:}

if [[ "$uid" == 0 ]] && ! namespace_root_maps_to_host_user /proc/self/uid_map; then
  echo '::error::Runner owner is uid 0 and uid_map does not prove a rootless mapping; refusing to chown checkout files.'
  exit 1
fi
if [[ "$gid" == 0 ]] && ! namespace_root_maps_to_host_user /proc/self/gid_map; then
  echo '::error::Runner owner is gid 0 and gid_map does not prove a rootless mapping; refusing to chown checkout files.'
  exit 1
fi

chown -R "$owner" "$workspace"
for path in "$@"; do
  [[ ! -e "$path" ]] || chown -R "$owner" "$path"
done
