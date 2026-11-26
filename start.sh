#!/usr/bin/env bash
set -euo pipefail
pilot_shell_os="$(uname -s)"
case "$pilot_shell_os" in
  MINGW*|MSYS*|CYGWIN*) printf 'Pilot: Windows Git Bash is not supported by start.sh; run scripts/start.ps1 from PowerShell.\n' >&2; exit 1 ;;
esac
pilot_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$pilot_root"
source "$pilot_root/scripts/bootstrap-java.sh"
pilot_mode=launch
if [[ "${PILOT_START_CHECK_ONLY:-false}" == true ]]; then pilot_mode=prepare; fi
exec "$pilot_java" --enable-native-access=ALL-UNNAMED \
  "$pilot_root/pilot-core/src/main/java/com/pilot/core/runtime/bootstrap/SourceEnvironment.java" "$pilot_mode" "$pilot_root" "$@"
