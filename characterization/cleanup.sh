#!/usr/bin/env bash
# Remove per-case run directories (and with --all, the compiled programs).
# Evidence under characterization/.evidence/ is never touched.
# `cleanup.sh stop` stops the warm server java/characterization-adapter keeps running.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
if [[ "${1:-}" == "stop" ]]; then
  pidfile="$root/../java/target/characterization/server.pid"
  if [[ -f "$pidfile" ]] && kill "$(cat "$pidfile")" 2>/dev/null; then
    echo "stopped the java characterization server (pid $(cat "$pidfile"))"
  else
    echo "no java characterization server running"
  fi
  rm -f "$pidfile"
  exit 0
fi
rm -rf "$root/.build/runs"
if [[ "${1:-}" == "--all" ]]; then
  rm -rf "$root/.build"
fi
echo "cleaned $root/.build${1:+ (all)}; evidence kept in $root/.evidence"
