#!/usr/bin/env bash
# Sanitized ephemeral container entrypoint for evaluating Pilot on Multi-SWE-bench.
# Runs BEFORE Pilot starts:
#   1. Strips all Multi-SWE-bench answer artifacts (/home/*.patch, /home/*.sh)
#   2. Destroys future git history in /home/$MSB_REPO and creates a single clean 'baseline' commit
#      (preserving container-baked build tweaks like pinned pom.xml so git diff HEAD = ONLY Pilot edits)
#   3. Pins Maven (/root/.mavenrc, /etc/mavenrc) & Gradle (/root/.gradle/gradle.properties) to the
#      container's native JDK so Pilot running on /opt/pilot-jre (JDK 25) does not hijack repo builds.
#   4. Starts Pilot server bound to 0.0.0.0:9998 with ephemeral bearer token auth.
set -euo pipefail

REPO_DIR="/home/${MSB_REPO:?MSB_REPO is required}"
if [[ ! -d "$REPO_DIR" ]]; then
  echo "[entrypoint] ERROR: repo dir not found: $REPO_DIR" >&2
  exit 1
fi

# 1. Remove answer/harness artifacts baked into mswebench images
rm -f /home/fix.patch /home/test.patch /home/fix-run.sh /home/test-run.sh /home/run.sh /home/prepare.sh /home/check_git_changes.sh

# 2. Detect container's native JDK BEFORE Pilot runs (for Maven / Gradle repos)
NATIVE_JAVA_HOME="${JAVA_HOME:-}"
if [[ -z "$NATIVE_JAVA_HOME" ]] && command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(readlink -f "$(command -v java)")"
  # Handle both .../jre/bin/java and .../bin/java
  CANDIDATE="$(dirname "$(dirname "$JAVA_BIN")")"
  if [[ "$(basename "$CANDIDATE")" == "jre" ]] && [[ -d "$(dirname "$CANDIDATE")/bin" ]]; then
    CANDIDATE="$(dirname "$CANDIDATE")"
  fi
  if [[ -x "$CANDIDATE/bin/java" ]]; then
    NATIVE_JAVA_HOME="$CANDIDATE"
  fi
fi

if [[ -n "$NATIVE_JAVA_HOME" ]]; then
  echo "[entrypoint] Pinning Maven/Gradle to container native JDK: $NATIVE_JAVA_HOME"
  printf 'export JAVA_HOME="%s"\nexport PATH="%s/bin:$PATH"\n' "$NATIVE_JAVA_HOME" "$NATIVE_JAVA_HOME" > /root/.mavenrc
  cp /root/.mavenrc /etc/mavenrc 2>/dev/null || true
  mkdir -p /root/.gradle
  # Append or set org.gradle.java.home without losing existing gradle.properties
  touch /root/.gradle/gradle.properties
  grep -v '^org\.gradle\.java\.home=' /root/.gradle/gradle.properties > /root/.gradle/gradle.properties.tmp || true
  printf 'org.gradle.java.home=%s\n' "$NATIVE_JAVA_HOME" >> /root/.gradle/gradle.properties.tmp
  mv /root/.gradle/gradle.properties.tmp /root/.gradle/gradle.properties
  # Also pin repo wrapper scripts (gradlew / mvnw) BEFORE baseline commit so they never appear in git diff HEAD.
  # If a repo ships ./mvnw without maven-wrapper.jar (broken offline wrapper in mswebench images) and system `mvn`
  # is installed, delegate ./mvnw to system `mvn` with native JDK so both `mvn` and `./mvnw` work identically.
  if [[ -f "$REPO_DIR/mvnw" ]] && [[ ! -f "$REPO_DIR/.mvn/wrapper/maven-wrapper.jar" ]] && command -v mvn >/dev/null 2>&1; then
    SYS_MVN="$(command -v mvn)"
    echo "[entrypoint] Delegating offline-broken ./mvnw to system ${SYS_MVN} with native JDK ${NATIVE_JAVA_HOME}"
    cat > "$REPO_DIR/mvnw" <<EOF
#!/usr/bin/env sh
export JAVA_HOME="${NATIVE_JAVA_HOME}"
export PATH="${NATIVE_JAVA_HOME}/bin:\$PATH"
exec "${SYS_MVN}" "\$@"
EOF
    chmod +x "$REPO_DIR/mvnw"
  fi
  for wrapper in "$REPO_DIR/gradlew" "$REPO_DIR/mvnw"; do
    if [[ -f "$wrapper" ]] && head -n 1 "$wrapper" | grep -q '^#!' && ! grep -q "export JAVA_HOME=\"${NATIVE_JAVA_HOME}\"" "$wrapper"; then
      {
        head -n 1 "$wrapper"
        printf 'export JAVA_HOME="%s"\nexport PATH="%s/bin:$PATH"\n' "$NATIVE_JAVA_HOME" "$NATIVE_JAVA_HOME"
        tail -n +2 "$wrapper"
      } > "${wrapper}.pin.tmp"
      chmod --reference="$wrapper" "${wrapper}.pin.tmp" 2>/dev/null || chmod +x "${wrapper}.pin.tmp"
      mv "${wrapper}.pin.tmp" "$wrapper"
    fi
  done
fi

# 2b. Work around Apple Rosetta 2 readlinkat(/proc/<pid>/exe) assertion crash when Vitest >=1.x spawns child forks
# while JVM ProcessHandle polls descendants. Wrapping git-ignored node_modules/.bin/vitest to default to --pool=threads
# leaves git diff HEAD and official Multi-SWE-bench Docker scoring 100% untouched.
if [[ -f "$REPO_DIR/node_modules/.bin/vitest" ]] && grep -q 'vitest@[1-9]' "$REPO_DIR/node_modules/.bin/vitest"; then
  echo "[entrypoint] Wrapping vitest >=1.x with default --pool=threads to avoid Rosetta 2 forks crash"
  mv "$REPO_DIR/node_modules/.bin/vitest" "$REPO_DIR/node_modules/.bin/vitest.real"
  cat > "$REPO_DIR/node_modules/.bin/vitest" <<'EOF'
#!/bin/sh
has_pool=0
for arg in "$@"; do
  case "$arg" in
    --pool|--pool=*|--version|-v|--help|-h) has_pool=1 ;;
  esac
done
if [ "$has_pool" -eq 0 ]; then
  exec "$(dirname "$0")/vitest.real" --pool=threads "$@"
else
  exec "$(dirname "$0")/vitest.real" "$@"
fi
EOF
  chmod +x "$REPO_DIR/node_modules/.bin/vitest"
fi

# 3. Strip git history completely so the evaluated Agent cannot read future PR commits/tags/reflogs
cd "$REPO_DIR"
rm -rf .git
git init -q
git config user.name "pilot-eval"
git config user.email "eval@local"
# Preserve .gitignore behavior when adding; force-ensure tracked baseline reflects container state
git add -A
git commit -qm "baseline"

# 4. Prepare isolated Pilot data directory
mkdir -p /opt/pilot-data

# 5. Build Pilot JVM arguments cleanly
JAVA_ARGS=(
  -Xms256m
  -Xmx2048m
  -Dpilot.os.sandbox=false
  -jar /opt/pilot/pilot-server.jar
  --server.address=0.0.0.0
  --server.port=9998
  --spring.profiles.active=eval
  --pilot.security.mode=token
  "--pilot.security.token=${PILOT_EVAL_TOKEN:?PILOT_EVAL_TOKEN is required}"
  --pilot.data.dir=/opt/pilot-data
  "--pilot.agent.workspace=${REPO_DIR}"
  --pilot.agent.permission-mode=AUTO
  --pilot.desktop.launch=false
  --pilot.desktop.provision=false
  --pilot.runtime.provision=false
  --pilot.browser.enabled=false
  --pilot.browser.provision=false
  --pilot.browser.download-chromium=false
  --pilot.mobile.mode=direct
  --pilot.web-search.local=false
  --pilot.model.capability-discovery.enabled=false
)

# Optional ablation/arm overrides passed as newline-separated Spring args in PILOT_EXTRA_ARGS
if [[ -n "${PILOT_EXTRA_ARGS:-}" ]]; then
  while IFS= read -r arg; do
    [[ -n "$arg" ]] && JAVA_ARGS+=("$arg")
  done <<< "$PILOT_EXTRA_ARGS"
fi

echo "[entrypoint] Starting Pilot server on 0.0.0.0:9998 for workspace ${REPO_DIR}..."
# Clear container JAVA_TOOL_OPTIONS only for Pilot's own JDK 25 launcher if incompatible,
# while preserving UTF-8 encoding.
exec env -u JAVA_TOOL_OPTIONS /opt/pilot-jre/bin/java "${JAVA_ARGS[@]}"
