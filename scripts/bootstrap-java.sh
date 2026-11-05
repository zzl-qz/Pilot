#!/usr/bin/env bash
# Sourced by start.sh. Only this process receives the selected JAVA_HOME.
pilot_manifest="$pilot_root/pilot-core/src/main/resources/bootstrap/runtime.properties"
pilot_cache="${PILOT_BOOTSTRAP_DIR:-${PILOT_DATA_DIR:-$pilot_root/.pilot}/bootstrap}"
pilot_property() {
  local pilot_key pilot_value
  while IFS='=' read -r pilot_key pilot_value; do
    if [[ "$pilot_key" == "$1" ]]; then printf '%s\n' "$pilot_value"; return 0; fi
  done < "$pilot_manifest"
  return 1
}
pilot_java_usable() {
  local pilot_details pilot_version pilot_home pilot_arch
  pilot_details=$("$1" -XshowSettings:properties -version 2>&1) || return 1
  pilot_version=$(printf '%s\n' "$pilot_details" | awk '/java.specification.version =/{print $3;exit}')
  pilot_home=$(printf '%s\n' "$pilot_details" | sed -n 's/^[[:space:]]*java.home = //p' | head -1)
  pilot_arch=$(printf '%s\n' "$pilot_details" | awk '/os.arch =/{print $3;exit}')
  [[ "$pilot_version" =~ ^[0-9]+$ && "$pilot_version" -ge 25 && -x "$pilot_home/bin/javac" ]] || return 1
  if [[ "$pilot_os" == mac && "$pilot_machine" == aarch64 && "$pilot_arch" != aarch64 && "$pilot_arch" != arm64 ]]; then return 1; fi
  export JAVA_HOME="$pilot_home"
  pilot_java="$JAVA_HOME/bin/java"
}
pilot_os=$(uname -s)
case "$pilot_os" in Darwin) pilot_os=mac ;; Linux) pilot_os=linux ;; *) printf 'Pilot: unsupported operating system: %s\n' "$pilot_os" >&2; return 1 ;; esac
pilot_machine=$(uname -m)
if [[ "$pilot_os" == mac && $(sysctl -n hw.optional.arm64 2>/dev/null || true) == 1 ]]; then pilot_machine=aarch64; fi
case "$pilot_machine" in arm64|aarch64) pilot_machine=aarch64 ;; x86_64|amd64) pilot_machine=x64 ;; *) printf 'Pilot: a 64-bit x64 or ARM64 system is required.\n' >&2; return 1 ;; esac
if [[ "$pilot_os" == linux ]] && ldd --version 2>&1 | grep -qi musl; then
  printf 'Pilot: automatic setup currently requires a glibc Linux distribution; musl is not supported.\n' >&2; return 1
fi
pilot_java=''
if [[ "${PILOT_FORCE_MANAGED_JAVA:-false}" != true ]]; then
  if [[ -n "${JAVA_HOME:-}" ]]; then pilot_java_usable "$JAVA_HOME/bin/java" || true; fi
  if [[ -z "$pilot_java" ]] && command -v java >/dev/null 2>&1; then pilot_java_usable "$(command -v java)" || true; fi
  if [[ -z "$pilot_java" && "$pilot_os" == mac && -x /usr/libexec/java_home ]]; then
    pilot_installed=$(/usr/libexec/java_home -v 25 2>/dev/null || true)
    if [[ -n "$pilot_installed" ]]; then pilot_java_usable "$pilot_installed/bin/java" || true; fi
  fi
fi
if [[ -z "$pilot_java" ]]; then
  pilot_asset="jdk25.$pilot_os.$pilot_machine"
  pilot_url=$(pilot_property "$pilot_asset.url")
  pilot_sha=$(pilot_property "$pilot_asset.sha256")
  pilot_dest="$pilot_cache/$(pilot_property "$pilot_asset.home")"
  pilot_binary="$pilot_dest/bin/java"
  if [[ "$pilot_os" == mac ]]; then pilot_binary="$pilot_dest/Contents/Home/bin/java"; fi
  if ! pilot_java_usable "$pilot_binary"; then
    if [[ "${PILOT_OFFLINE:-false}" == true ]]; then printf 'Pilot: offline mode has no usable cached JDK 25.\n' >&2; return 1; fi
    mkdir -p "$pilot_cache"
    pilot_lock="$pilot_cache/java-bootstrap.lockdir"
    pilot_wait=0
    until mkdir "$pilot_lock" 2>/dev/null; do
      if [[ -f "$pilot_lock/pid" ]] && ! kill -0 "$(cat "$pilot_lock/pid")" 2>/dev/null; then rm -f "$pilot_lock/pid"; rmdir "$pilot_lock" 2>/dev/null || true; fi
      pilot_wait=$((pilot_wait + 1)); if (( pilot_wait > 600 )); then printf 'Pilot: another setup process did not finish.\n' >&2; return 1; fi
      sleep 1
    done
    printf '%s\n' "$$" > "$pilot_lock/pid"
    pilot_stage=$(mktemp -d "$pilot_cache/.jdk25-XXXXXXXX")
    pilot_cleanup_java() { rm -rf "$pilot_stage"; rm -f "$pilot_lock/pid"; rmdir "$pilot_lock" 2>/dev/null || true; }
    trap pilot_cleanup_java EXIT
    if ! pilot_java_usable "$pilot_binary"; then
      printf 'Pilot: downloading and verifying a private JDK 25. Global Java settings will not change.\n'
      if command -v curl >/dev/null 2>&1; then
        curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 --connect-timeout 20 --max-time 600 -o "$pilot_stage/jdk.tar.gz" "$pilot_url"
      elif command -v wget >/dev/null 2>&1; then
        wget --https-only --timeout=30 --tries=2 -O "$pilot_stage/jdk.tar.gz" "$pilot_url"
      else printf 'Pilot: install curl or wget to prepare the private Java runtime.\n' >&2; return 1; fi
      if command -v sha256sum >/dev/null 2>&1; then pilot_actual=$(sha256sum "$pilot_stage/jdk.tar.gz"); else pilot_actual=$(shasum -a 256 "$pilot_stage/jdk.tar.gz"); fi
      [[ "${pilot_actual%% *}" == "$pilot_sha" ]] || { printf 'Pilot: JDK checksum failed. Nothing was installed.\n' >&2; return 1; }
      mkdir "$pilot_stage/unpack"
      tar -xzf "$pilot_stage/jdk.tar.gz" -C "$pilot_stage/unpack"
      pilot_entries=("$pilot_stage/unpack/"*)
      [[ ${#pilot_entries[@]} == 1 && -d "${pilot_entries[0]}" ]] || { printf 'Pilot: JDK archive is invalid.\n' >&2; return 1; }
      if [[ -e "$pilot_dest" ]]; then mv "$pilot_dest" "$pilot_dest.invalid-$(date +%s)-$$"; fi
      printf '%s' "$pilot_sha" > "${pilot_entries[0]}/.pilot-verified"
      mv "${pilot_entries[0]}" "$pilot_dest"
      pilot_java_usable "$pilot_binary" || { printf 'Pilot: downloaded Java cannot run on this system.\n' >&2; return 1; }
    fi
    pilot_cleanup_java
    trap - EXIT
  fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
