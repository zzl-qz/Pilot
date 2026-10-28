#!/usr/bin/env bash
set -euo pipefail

pilot_root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$pilot_root"

pilot_java=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  pilot_java="$JAVA_HOME/bin/java"
fi

pilot_java_major() {
  local pilot_output pilot_line pilot_major
  pilot_output=$("$1" -XshowSettings:properties -version 2>&1) || return 1
  while IFS= read -r pilot_line; do
    if [[ "$pilot_line" =~ java\.specification\.version[[:space:]]*=[[:space:]]*([0-9]+)(\.([0-9]+))? ]]; then
      pilot_major="${BASH_REMATCH[1]}"
      if [[ "$pilot_major" == 1 && -n "${BASH_REMATCH[3]:-}" ]]; then
        pilot_major="${BASH_REMATCH[3]}"
      fi
      printf '%s\n' "$pilot_major"
      return 0
    fi
  done <<< "$pilot_output"
  return 1
}

pilot_major=$(pilot_java_major "$pilot_java") || pilot_major=unavailable
if [[ "$pilot_major" == unavailable || "$pilot_major" -lt 25 ]]; then
  # Change only this process environment; leave the user's global Java settings intact.
  if [[ -x /usr/libexec/java_home ]] && pilot_java_home=$(/usr/libexec/java_home -v 25 2>/dev/null); then
    pilot_fallback_major=$(pilot_java_major "$pilot_java_home/bin/java") || pilot_fallback_major=unavailable
    if [[ "$pilot_fallback_major" != unavailable && "$pilot_fallback_major" -ge 25 ]]; then
      printf 'Pilot: Java %s is unsuitable; using JDK %s for this launch: %s\n' "$pilot_major" "$pilot_fallback_major" "$pilot_java_home"
      export JAVA_HOME="$pilot_java_home"
      pilot_java="$JAVA_HOME/bin/java"
      pilot_major="$pilot_fallback_major"
    fi
  fi
fi

if [[ "$pilot_major" == unavailable || "$pilot_major" -lt 25 ]]; then
  printf 'Pilot requires JDK 25 or newer. Detected Java %s via %s.\nSet JAVA_HOME or PATH to a suitable JDK before starting.\n' "$pilot_major" "$pilot_java" >&2
  exit 1
fi

# Install all reactor dependencies so server-only builds cannot use stale core jars.
bash ./mvnw -pl pilot-server -am install
exec "$pilot_java" --enable-native-access=ALL-UNNAMED \
  -jar pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar "$@"
