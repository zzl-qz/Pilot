#!/usr/bin/env bash
set -euo pipefail
pilot_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$pilot_root"
source "$pilot_root/scripts/bootstrap-java.sh"
pilot_mode=launch
if [[ "${PILOT_START_CHECK_ONLY:-false}" == true ]]; then pilot_mode=prepare; fi
exec "$pilot_java" --enable-native-access=ALL-UNNAMED \
  "$pilot_root/pilot-core/src/main/java/com/pilot/core/runtime/bootstrap/SourceEnvironment.java" "$pilot_mode" "$pilot_root" "$@"
